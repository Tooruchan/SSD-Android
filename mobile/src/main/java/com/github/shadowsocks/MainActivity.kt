/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks

import android.app.Activity
import android.app.backup.BackupManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.VpnService
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.*
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceDataStore
import com.crashlytics.android.Crashlytics
import com.github.shadowsocks.acl.CustomRulesFragment
import com.github.shadowsocks.aidl.IShadowsocksService
import com.github.shadowsocks.aidl.ShadowsocksConnection
import com.github.shadowsocks.aidl.TrafficStats
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.plugin.AlertDialogFragment
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.preference.OnPreferenceDataStoreChangeListener
import com.github.shadowsocks.utils.Key
import com.github.shadowsocks.widget.ServiceButton
import com.github.shadowsocks.widget.StatsBar
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.parcel.Parcelize
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.util.*

class MainActivity : AppCompatActivity(), ShadowsocksConnection.Callback, OnPreferenceDataStoreChangeListener,
        NavigationView.OnNavigationItemSelectedListener {
    companion object {
        private const val TAG = "ShadowsocksMainActivity"
        private const val REQUEST_CONNECT = 1

        var stateListener: ((Int) -> Unit)? = null
    }

    @Parcelize
    data class ProfilesArg(val profiles: List<Profile>) : Parcelable

    class ImportProfilesDialogFragment : AlertDialogFragment<ProfilesArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.add_profile_dialog)
            setPositiveButton(R.string.yes) { _, _ -> arg.profiles.forEach { ProfileManager.createProfile(it) } }
            setNegativeButton(R.string.no, null)
            setMessage(arg.profiles.joinToString("\n"))
        }
    }

    // UI
    private lateinit var fab: ServiceButton
    private lateinit var stats: StatsBar
    internal lateinit var drawer: DrawerLayout
    private lateinit var navigation: NavigationView

    val snackbar by lazy { findViewById<CoordinatorLayout>(R.id.snackbar) }
    fun snackbar(text: CharSequence = "") = Snackbar.make(snackbar, text, Snackbar.LENGTH_LONG).apply {
        view.translationY += fab.top + fab.translationY - snackbar.measuredHeight
    }

    private val customTabsIntent by lazy {
        CustomTabsIntent.Builder()
                .setToolbarColor(ContextCompat.getColor(this, R.color.color_primary))
                .build()
    }

    fun launchUrl(uri: String) = try {
        customTabsIntent.launchUrl(this, uri.toUri())
    } catch (_: ActivityNotFoundException) {
        snackbar(uri).show()
    }

    // service
    var state = BaseService.IDLE

    override fun stateChanged(state: Int, profileName: String?, msg: String?) = changeState(state, msg, true)
    override fun trafficUpdated(profileId: Long, stats: TrafficStats) {
        if (profileId == 0L) this@MainActivity.stats.updateTraffic(
                stats.txRate, stats.rxRate, stats.txTotal, stats.rxTotal)
        if (state != BaseService.STOPPING) {
            (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment)
                    ?.onTrafficUpdated(profileId, stats)
        }
    }

    override fun trafficPersisted(profileId: Long) {
        ProfilesFragment.instance?.onTrafficPersisted(profileId)
    }

    private fun changeState(state: Int, msg: String? = null, animate: Boolean = false) {
        fab.changeState(state, animate)
        stats.changeState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
        this.state = state
        ProfilesFragment.instance?.profilesAdapter?.notifyDataSetChanged()  // refresh button enabled state
        //region SSD
        ProfilesFragment.instance?.subscriptionsAdapter?.notifyDataSetChanged()
        //endregion
        stateListener?.invoke(state)
    }

    private fun toggle() = when {
        state == BaseService.CONNECTED -> Core.stopService()
        DataStore.serviceMode == Key.modeVpn -> {
            val intent = VpnService.prepare(this)
            if (intent != null) startActivityForResult(intent, REQUEST_CONNECT)
            else onActivityResult(REQUEST_CONNECT, Activity.RESULT_OK, null)
        }
        else -> Core.startService()
    }

    private val handler = Handler()
    private val connection = ShadowsocksConnection(handler, true)
    override fun onServiceConnected(service: IShadowsocksService) = changeState(try {
        service.state
    } catch (_: DeadObjectException) {
        BaseService.IDLE
    })

    override fun onServiceDisconnected() = changeState(BaseService.IDLE)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode != REQUEST_CONNECT -> super.onActivityResult(requestCode, resultCode, data)
            resultCode == Activity.RESULT_OK -> Core.startService()
            else -> {
                snackbar().setText(R.string.vpn_permission_denied).show()
                Crashlytics.log(Log.ERROR, TAG, "Failed to start VpnService from onActivityResult: $data")
            }
        }
    }

    //region SSD
    private class CheckVersion : AsyncTask<Unit, Int, String>() {
        val versionURL = "https://api.github.com/repos/TheCGDF/SSD-Android/releases/latest"
        lateinit var checkUpdateContext: Context

        override fun doInBackground(vararg params: Unit?): String {
            var urlResult = ""
            try {
                urlResult = URL(versionURL).readText()
            } catch (e: Exception) {

            }
            return urlResult
        }

        fun compareVersion(versionString1: String, versionString2: String): Int {
            val versionSplit1 = versionString1.split(".")
            val versionSplit2 = versionString2.split(".")
            for (index in 0 until 3) {
                if (versionSplit1[index].toInt() > versionSplit2[index].toInt()) {
                    //String1 > String2
                    return 1
                }
                if (versionSplit1[index].toInt() < versionSplit2[index].toInt()) {
                    //String1 < String2
                    return -1
                }
            }
            //String1 = String2
            return 0
        }

        override fun onPostExecute(result: String?) {
            super.onPostExecute(result)
            if (result.isNullOrBlank()) {
                Toast.makeText(checkUpdateContext, R.string.message_check_update_fail, Toast.LENGTH_LONG).show()
                return
            }
            val jsonObject = JSONObject(result)
            val tagName = jsonObject.optString("tag_name")
            val buildVersion = BuildConfig.VERSION_NAME
            val compareResult = compareVersion(tagName, buildVersion)
            if (compareResult != 1) {
                return
            }

            val limitBody = jsonObject.optString("body")
            val limitRegex = Regex("""(?<=Limit:\s)\d+\.\d+\.\d+""")
            val limitVersion = limitRegex.find(limitBody)?.value

            limitVersion ?: return

            if (compareVersion(limitVersion, buildVersion) == 1) {
                Toast.makeText(checkUpdateContext, R.string.message_update_must, Toast.LENGTH_LONG).show()
                (checkUpdateContext as Activity).finishAndRemoveTask()
            }

            Toast.makeText(checkUpdateContext, R.string.message_check_update_new, Toast.LENGTH_LONG).show()
        }
    }

    private fun getProp(key: String): String {
        val process = Runtime.getRuntime().exec("getprop " + key)
        return BufferedReader(InputStreamReader(process.inputStream)).readLine().trim().toLowerCase()
    }

    //endregion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //region SSD
        if (Build.BOARD.toLowerCase(Locale.getDefault()).trim() == "huawei" || Build.MANUFACTURER.toLowerCase(Locale.getDefault()).trim() == "huawei") {
        //    Toast.makeText(this, getString(R.string.message_mine_detected, "HUAWEI"), Toast.LENGTH_LONG).show()
        //    finishAndRemoveTask()
        }
        if (getProp("ro.product.brand") == "smartisan" || getProp("ro.product.manufacturer") == "smartisan") {
        //    Toast.makeText(this, getString(R.string.message_mine_detected, "Smartisan"), Toast.LENGTH_LONG).show()
            //   finishAndRemoveTask()
        }

        if (getProp("ro.miui.ui.version.code").isNotEmpty() || getProp("ro.miui.ui.version.name").isNotEmpty()) {
        //    Toast.makeText(this, getString(R.string.message_premine_detected, "MIUI"), Toast.LENGTH_LONG).show()
        }

        CheckVersion().apply {
            checkUpdateContext = this@MainActivity
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        //endregion
        setContentView(R.layout.layout_main)
        stats = findViewById(R.id.stats)
        stats.setOnClickListener { if (state == BaseService.CONNECTED) stats.testConnection() }
        drawer = findViewById(R.id.drawer)
        navigation = findViewById(R.id.navigation)
        navigation.setNavigationItemSelectedListener(this)
        if (savedInstanceState == null) {
            navigation.menu.findItem(R.id.profiles).isChecked = true
            displayFragment(ProfilesFragment())
        }

        fab = findViewById(R.id.fab)
        fab.setOnClickListener { toggle() }

        changeState(BaseService.IDLE)   // reset everything to init state
        connection.connect(this, this)
        DataStore.publicStore.registerChangeListener(this)

        val intent = this.intent
        if (intent != null) handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent) {
        val sharedStr = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                if (rawMsgs != null && rawMsgs.isNotEmpty()) String((rawMsgs[0] as NdefMessage).records[0].payload)
                else null
            }
            else -> null
        }
        if (sharedStr.isNullOrEmpty()) return
        val profiles = Profile.findAllUrls(sharedStr, Core.currentProfile?.first).toList()
        if (profiles.isEmpty()) snackbar().setText(R.string.profile_invalid_input).show()
        else ImportProfilesDialogFragment().withArg(ProfilesArg(profiles)).show(supportFragmentManager, null)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String?) {
        when (key) {
            Key.serviceMode -> handler.post {
                connection.disconnect(this)
                connection.connect(this, this)
            }
        }
    }

    private fun displayFragment(fragment: ToolbarFragment) {
        supportFragmentManager.beginTransaction().replace(R.id.fragment_holder, fragment).commitAllowingStateLoss()
        drawer.closeDrawers()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) drawer.closeDrawers() else {
            when (item.itemId) {
                R.id.profiles -> displayFragment(ProfilesFragment())
                R.id.globalSettings -> displayFragment(GlobalSettingsFragment())
                R.id.about -> {
                    Core.analytics.logEvent("about", Bundle())
                    displayFragment(AboutFragment())
                }
                R.id.faq -> {
                    launchUrl(getString(R.string.faq_url))
                    return true
                }
                //region SSD
                R.id.project_repository -> {
                    launchUrl("https://github.com/TheCGDF/SSD-Android")
                    return true
                }
                //endregion
                R.id.customRules -> displayFragment(CustomRulesFragment())
                else -> return false
            }
            item.isChecked = true
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        //region SSD
        val virusList = arrayListOf(
//                "com.qihoo360.mobilesafe",
//                "com.qihoo.appstore",
//                "com.qihoo.browser",
//                "cn.opda.a.phonoalbumshoushou",
//                "com.baidu.appsearch",
//                "com.tencent.qqpimsecure",
//                "com.market2345"
                ""
        )
        for (virusName in virusList) {
            try {
                val appInfo = packageManager.getApplicationInfo(virusName, 0)
                val appName = packageManager.getApplicationLabel(appInfo).toString()
                Toast.makeText(this, getString(R.string.message_virus_detected, appName), Toast.LENGTH_LONG).show()
                finishAndRemoveTask()
            } catch (exception: Exception) {
            }
        }
        //endregion
        connection.bandwidthTimeout = 500
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawers() else {
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as ToolbarFragment
            if (!currentFragment.onBackPressed()) {
                if (currentFragment is ProfilesFragment) super.onBackPressed() else {
                    navigation.menu.findItem(R.id.profiles).isChecked = true
                    displayFragment(ProfilesFragment())
                }
            }
        }
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent) = when {
        keyCode == KeyEvent.KEYCODE_G && event.hasModifiers(KeyEvent.META_CTRL_ON) -> {
            toggle()
            true
        }
        keyCode == KeyEvent.KEYCODE_T && event.hasModifiers(KeyEvent.META_CTRL_ON) -> {
            stats.testConnection()
            true
        }
        else -> (supportFragmentManager.findFragmentById(R.id.fragment_holder) as ToolbarFragment).toolbar.menu.let {
            it.setQwertyMode(KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC)
            it.performShortcut(keyCode, event, 0)
        }
    }

    override fun onStop() {
        connection.bandwidthTimeout = 0
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        DataStore.publicStore.unregisterChangeListener(this)
        connection.disconnect(this)
        BackupManager(this).dataChanged()
        handler.removeCallbacksAndMessages(null)
    }
}
