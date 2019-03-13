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

import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.AsyncTask
import android.os.Bundle
import android.text.format.Formatter
import android.util.Base64
import android.util.LongSparseArray
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.*
import com.github.shadowsocks.aidl.TrafficStats
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.database.Subscription
import com.github.shadowsocks.database.SubscriptionManager
import com.github.shadowsocks.plugin.PluginConfiguration
import com.github.shadowsocks.plugin.PluginOptions
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.Action
import com.github.shadowsocks.utils.datas
import com.github.shadowsocks.utils.printLog
import com.github.shadowsocks.widget.UndoSnackbarManager
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import net.glxn.qrgen.android.QRCode
import org.json.JSONArray
import org.json.JSONObject
import java.net.*
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProfilesFragment : ToolbarFragment(), Toolbar.OnMenuItemClickListener {
    companion object {
        /**
         * used for callback from stateChanged from MainActivity
         */
        var instance: ProfilesFragment? = null

        private const val KEY_URL = "com.github.shadowsocks.QRCodeDialog.KEY_URL"
        private const val REQUEST_IMPORT_PROFILES = 1
        private const val REQUEST_EXPORT_PROFILES = 2
    }

    /**
     * Is ProfilesFragment editable at all.
     */
    private val isEnabled
        get() = when ((activity as MainActivity).state) {
            BaseService.CONNECTED, BaseService.STOPPED -> true
            else -> false
        }

    private fun isProfileEditable(id: Long) = when ((activity as MainActivity).state) {
        BaseService.CONNECTED -> id !in Core.activeProfileIds
        BaseService.STOPPED -> true
        else -> false
    }

    @SuppressLint("ValidFragment")
    class QRCodeDialog() : DialogFragment() {
        constructor(url: String) : this() {
            arguments = bundleOf(Pair(KEY_URL, url))
        }

        private val url get() = arguments?.getString(KEY_URL)!!
        private val nfcShareItem by lazy { url.toByteArray() }
        private var adapter: NfcAdapter? = null

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            val image = ImageView(context)
            image.layoutParams = LinearLayout.LayoutParams(-1, -1)
            val size = resources.getDimensionPixelSize(R.dimen.qr_code_size)
            image.setImageBitmap((QRCode.from(url).withSize(size, size) as QRCode).bitmap())
            return image
        }

        override fun onAttach(context: Context) {
            super.onAttach(context)
            val adapter = NfcAdapter.getDefaultAdapter(context)
            adapter?.setNdefPushMessage(NdefMessage(arrayOf(
                    NdefRecord(NdefRecord.TNF_ABSOLUTE_URI, nfcShareItem, byteArrayOf(), nfcShareItem))), activity)
            this.adapter = adapter
        }

        override fun onDetach() {
            super.onDetach()
            val activity = activity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed)
                adapter?.setNdefPushMessage(null, activity)
            adapter = null
        }
    }

    inner class ProfileViewHolder(view: View) : RecyclerView.ViewHolder(view),
            View.OnClickListener, PopupMenu.OnMenuItemClickListener {
        internal lateinit var item: Profile

        private val text1 = itemView.findViewById<TextView>(android.R.id.text1)
        private val text2 = itemView.findViewById<TextView>(android.R.id.text2)
        private val traffic = itemView.findViewById<TextView>(R.id.traffic)
        private val edit = itemView.findViewById<View>(R.id.edit)
        private var adView: AdView? = null

        init {
            edit.setOnClickListener {
                item = ProfileManager.getProfile(item.id)!!
                startConfig(item)
            }
            TooltipCompat.setTooltipText(edit, edit.contentDescription)
            itemView.setOnClickListener(this)
            val share = itemView.findViewById<View>(R.id.share)
            share.setOnClickListener {
                val popup = PopupMenu(requireContext(), share)
                popup.menuInflater.inflate(R.menu.profile_share_popup, popup.menu)
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }
            TooltipCompat.setTooltipText(share, share.contentDescription)
        }

        fun bind(item: Profile) {
            this.item = item
            val editable = isProfileEditable(item.id)
            edit.isEnabled = editable
            edit.alpha = if (editable) 1F else .5F
            var tx = item.tx
            var rx = item.rx
            statsCache[item.id]?.apply {
                tx += txTotal
                rx += rxTotal
            }
            text1.text = item.formattedName
            //region SSD
            if (!profilesAdapter.lockEditable) {
                edit.isEnabled = false
                edit.alpha = .5F
            }
            text1.text = "[" + latencyText(item) + "]" + item.formattedName
            //endregion SSD
            text2.text = ArrayList<String>().apply {
                if (!item.name.isNullOrEmpty()) this += item.formattedAddress
                val id = PluginConfiguration(item.plugin ?: "").selected
                if (id.isNotEmpty()) this += getString(R.string.profile_plugin, id)
            }.joinToString("\n")
            val context = requireContext()
            traffic.text = if (tx <= 0 && rx <= 0) null else getString(R.string.traffic,
                    Formatter.formatFileSize(context, tx), Formatter.formatFileSize(context, rx))

            if (item.id == DataStore.profileId) {
                itemView.isSelected = true
                selectedItem = this
                //region SSD
                selectedProfileSubscription = null
                //endregion
            } else {
                itemView.isSelected = false
                if (selectedItem === this) selectedItem = null
            }

            var adView = adView
            if (item.host == "198.199.101.152") {
                if (adView == null) {
                    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT)
                    params.gravity = Gravity.CENTER_HORIZONTAL
                    adView = AdView(context)
                    adView.layoutParams = params
                    adView.adUnitId = "ca-app-pub-9097031975646651/7760346322"
                    adView.adSize = AdSize.FLUID
                    val padding = context.resources.getDimensionPixelOffset(R.dimen.profile_padding)
                    adView.setPadding(padding, 0, 0, padding)

                    itemView.findViewById<LinearLayout>(R.id.content).addView(adView)

                    // Load Ad
                    val adBuilder = AdRequest.Builder()
                    adBuilder.addTestDevice("B08FC1764A7B250E91EA9D0D5EBEB208")
                    adView.loadAd(adBuilder.build())
                    this.adView = adView
                } else adView.visibility = View.VISIBLE
            } else adView?.visibility = View.GONE
        }

        override fun onClick(v: View?) {
            if (isEnabled) {
                val activity = activity as MainActivity
                val old = DataStore.profileId
                Core.switchProfile(item.id)
                profilesAdapter.refreshId(old)
                //region SSD
                subscriptionsAdapter.refreshProfileId(old)
                //endregion
                itemView.isSelected = true
                if (activity.state == BaseService.CONNECTED) Core.reloadService()
            }
        }

        override fun onMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
            R.id.action_qr_code_nfc -> {
                requireFragmentManager().beginTransaction().add(QRCodeDialog(this.item.toString()), "")
                        .commitAllowingStateLoss()
                true
            }
            R.id.action_export_clipboard -> {
                clipboard.primaryClip = ClipData.newPlainText(null, this.item.toString())
                true
            }
            else -> false
        }
    }

    inner class ProfilesAdapter : RecyclerView.Adapter<ProfileViewHolder>(), ProfileManager.Listener {
        //region SSD
        //internal val profiles = ProfileManager.getAllProfiles()?.toMutableList() ?: mutableListOf()
        internal val profiles = ProfileManager.getSubscription(0)?.toMutableList()
                ?: mutableListOf()

        var lockEditable = true
        fun lockEdit(editableState: Boolean) {
            lockEditable = editableState
            profiles.forEach {
                refreshId(it.id)
            }
        }
        //endregion

        private val updated = HashSet<Profile>()

        init {
            setHasStableIds(true)   // see: http://stackoverflow.com/a/32488059/2245107
        }

        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) = holder.bind(profiles[position])
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder = ProfileViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.layout_profile, parent, false))

        override fun getItemCount(): Int = profiles.size
        override fun getItemId(position: Int): Long = profiles[position].id

        override fun onAdd(profile: Profile) {
            undoManager.flush()
            val pos = itemCount
            profiles += profile
            notifyItemInserted(pos)
            //region SSD
            checkVisible()
            //endregion
        }

        fun move(from: Int, to: Int) {
            undoManager.flush()
            val first = profiles[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(-1, to + 1 downTo from)
            for (i in range) {
                val next = profiles[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                profiles[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            profiles[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            updated.forEach { ProfileManager.updateProfile(it) }
            updated.clear()
        }

        fun remove(pos: Int) {
            profiles.removeAt(pos)
            notifyItemRemoved(pos)
            //region SSD
            checkVisible()
            //endregion
        }

        fun undo(actions: List<Pair<Int, Profile>>) {
            for ((index, item) in actions) {
                profiles.add(index, item)
                notifyItemInserted(index)
            }
            //region SSD
            checkVisible()
            //endregion
        }

        fun commit(actions: List<Pair<Int, Profile>>) {
            for ((_, item) in actions) ProfileManager.delProfile(item.id)
        }

        fun refreshId(id: Long) {
            val index = profiles.indexOfFirst { it.id == id }
            if (index >= 0) notifyItemChanged(index)
        }

        fun deepRefreshId(id: Long) {
            val index = profiles.indexOfFirst { it.id == id }
            if (index < 0) return
            profiles[index] = ProfileManager.getProfile(id)!!
            notifyItemChanged(index)
        }

        override fun onRemove(profileId: Long) {
            val index = profiles.indexOfFirst { it.id == profileId }
            if (index < 0) return
            profiles.removeAt(index)
            notifyItemRemoved(index)
            if (profileId == DataStore.profileId) DataStore.profileId = 0   // switch to null profile
            //region SSD
            val subscriptionId = ProfileManager.getProfile(profileId)?.subscription
            if (subscriptionId != null && subscriptionId != 0L) {
                val subscriptionList = ProfileManager.getSubscription(subscriptionId)
                if (subscriptionList != null && subscriptionList.isEmpty()) {
                    subscriptionsAdapter.onRemove(subscriptionId)
                } else {
                    subscriptionsAdapter.refreshSubscriptionId(subscriptionId)
                }
            }
            checkVisible()
            //endregion
        }
    }

    //region SSD
    inner class ProfileSubscriptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        internal lateinit var item: Subscription
        internal var profiles = emptyList<Profile>()
        private val trafficUsage = itemView.findViewById<TextView>(R.id.text_traffic_usage)
        private val expiry = itemView.findViewById<TextView>(R.id.text_expiry)
        private val text1 = itemView.findViewById<TextView>(R.id.airport_name)
        private val text2 = itemView.findViewById<TextView>(android.R.id.text2)
        private val traffic = itemView.findViewById<TextView>(R.id.traffic)
        private val serverDroplist = itemView.findViewById<Spinner>(R.id.server_list)
        private val edit = itemView.findViewById<View>(R.id.edit)

        init {
            edit.setOnClickListener {
                val editProfile = ProfileManager.getProfile(item.selectedProfileId)
                if (editProfile != null) {
                    startConfig(editProfile)
                }
            }
            TooltipCompat.setTooltipText(edit, edit.contentDescription)

            val share = itemView.findViewById<View>(R.id.share)
            share.setOnClickListener {
                clipboard.primaryClip = ClipData.newPlainText(null, item.url)
                Toast.makeText(requireContext(), R.string.message_subscription_copied, Toast.LENGTH_SHORT).show()
            }
            serverDroplist.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {

                }

                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedProfile = profiles.first {
                        it.id == (serverDroplist.selectedItem as Profile).id
                    }
                    item.selectedProfileId = selectedProfile.id
                    SubscriptionManager.updateSubscription(item)

                    if (ProfileManager.getProfile(DataStore.profileId)?.subscription == item.id &&
                            DataStore.profileId != item.selectedProfileId) {
                        val activity = activity as MainActivity
                        val oldProfile = DataStore.profileId
                        Core.switchProfile(item.selectedProfileId)
                        profilesAdapter.refreshId(oldProfile)
                        subscriptionsAdapter.refreshProfileId(oldProfile)
                        //reloadService() will call onItemSelected() again
                        itemView.isSelected = true
                        if (activity.state == BaseService.CONNECTED) {
                            Core.reloadService()
                        }
                    }
                }
            }
            itemView.setOnClickListener {
                val activity = activity as MainActivity
                val oldProfile = DataStore.profileId
                Core.switchProfile(item.selectedProfileId)
                profilesAdapter.refreshId(oldProfile)
                subscriptionsAdapter.refreshProfileId(oldProfile)
                itemView.isSelected = true
                if (activity.state == BaseService.CONNECTED) {
                    Core.reloadService()
                }
            }
        }

        fun bind(item: Subscription) {
            this.item = item
            profiles = ProfileManager.getSubscription(item.id)!!
            text1.text = item.airport
            if (item.trafficUsed >= 0 && item.trafficTotal > 0) {
                trafficUsage.text = "%.2f / %.2f G".format(item.trafficUsed, item.trafficTotal)
            } else {
                trafficUsage.text = "? / ? G"
            }
            try {
                val expiryDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(item.expiry)
                val expiryDays = TimeUnit.DAYS.convert(expiryDate.time - Calendar.getInstance().time.time, TimeUnit.MILLISECONDS)
                expiry.text = getString(R.string.subscription_expiry).format(item.expiry, expiryDays)
            } catch (e: Exception) {
                expiry.text = "????-??-?? ??:??:??"
            }
            var editable = true
            ProfileManager.getSubscription(item.id)?.firstOrNull {
                if (!isProfileEditable(it.id)) {
                    editable = false
                    true
                } else {
                    false
                }
            }
            editable = editable && subscriptionsAdapter.lockEditable
            edit.isEnabled = editable
            edit.alpha = if (editable) 1F else .5F

            var selectedProfile = ProfileManager.getProfile(item.selectedProfileId)
            if (selectedProfile == null || selectedProfile.subscription != item.id) {
                selectedProfile = profiles.first {
                    it.subscription == item.id
                }
                item.selectedProfileId = selectedProfile.id
                SubscriptionManager.updateSubscription(item)
            }

            var tx = selectedProfile.tx
            var rx = selectedProfile.rx
            statsCache[item.id]?.apply {
                tx += txTotal
                rx += rxTotal
            }

            //todo ssd: complete text2
            /*text2.text = ArrayList<String>().apply {
                if (!item.name.isNullOrEmpty()) this += item.formattedAddress
                val id = PluginConfiguration(item.plugin ?: "").selected
                if (id.isNotEmpty()) this += getString(R.string.profile_plugin, id)
            }.joinToString("\n")*/

            val context = requireContext()
            traffic.text = if (tx <= 0 && rx <= 0) null else getString(R.string.traffic,
                    Formatter.formatFileSize(context, tx), Formatter.formatFileSize(context, rx))

            if (ProfileManager.getProfile(DataStore.profileId)?.subscription == item.id) {
                itemView.isSelected = true
                selectedItem = null
                selectedProfileSubscription = this
            } else {
                itemView.isSelected = false
                if (selectedProfileSubscription === this) {
                    selectedProfileSubscription = null
                }
            }

            //todo ssd: check again ,when empty,delete itself
            //should check in adapter
            val subscriptionAdapter = object : ArrayAdapter<Profile>(context, android.R.layout.simple_spinner_item, profiles) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return getDropDownView(position, convertView, parent)
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val itemView: TextView = super.getDropDownView(position, convertView, parent) as TextView
                    val viewItem = getItem(position)!!
                    val viewText = "[" + latencyText(viewItem) + " x" + viewItem.ratio + "] " + viewItem.name
                    itemView.text = viewText
                    return itemView
                }
            }
            subscriptionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            serverDroplist.adapter = subscriptionAdapter
            val selectionIndex = profiles.indexOfFirst {
                it.id == selectedProfile.id
            }
            serverDroplist.setSelection(selectionIndex)
        }
    }

    inner class ProfileSubscriptionsAdapter : RecyclerView.Adapter<ProfileSubscriptionViewHolder>(), SubscriptionManager.Listener {
        internal val subscriptions = SubscriptionManager.getAllSubscriptions()?.toMutableList()
                ?: mutableListOf()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileSubscriptionViewHolder = ProfileSubscriptionViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.layout_subscription, parent, false))

        override fun getItemCount(): Int = subscriptions.size
        override fun getItemId(position: Int): Long = subscriptions[position].id
        override fun onBindViewHolder(holder: ProfileSubscriptionViewHolder, position: Int) = holder.bind(subscriptions[position])

        var lockEditable = true
        fun lockEdit(editableState: Boolean) {
            lockEditable = editableState
            subscriptions.forEach {
                refreshSubscriptionId(it.id)
            }
        }

        override fun onAdd(subscription: Subscription) {
            val pos = itemCount
            subscriptions += subscription
            notifyItemInserted(pos)
            checkVisible()
        }

        fun remove(pos: Int) {
            val deleteSubscriptionId = subscriptions[pos].id
            SubscriptionManager.delSubscriptionWithProfiles(deleteSubscriptionId)
            subscriptions.removeAt(pos)
            notifyItemRemoved(pos)
            checkVisible()
        }

        override fun onRemove(subscriptionId: Long) {
            val index = subscriptions.indexOfFirst { it.id == subscriptionId }
            if (index < 0) return
            SubscriptionManager.delSubscriptionWithProfiles(subscriptionId)
            subscriptions.removeAt(index)
            notifyItemRemoved(index)
            if (subscriptionId == ProfileManager.getProfile(DataStore.profileId)?.subscription) {
                DataStore.profileId = 0
            }
            checkVisible()
        }

        fun refreshSubscriptionId(id: Long) {
            val index = subscriptions.indexOfFirst { it.id == id }
            if (index >= 0) notifyItemChanged(index)
        }

        fun refreshProfileId(id: Long) {
            val index = subscriptions.indexOfFirst {
                it.id == ProfileManager.getProfile(id)?.subscription
            }
            if (index >= 0) notifyItemChanged(index)
        }

        fun deepRefreshSubscriptionId(id: Long) {
            val index = subscriptions.indexOfFirst { it.id == id }
            if (index < 0) return
            subscriptions[index] = SubscriptionManager.getSubscription(id)!!
            notifyItemChanged(index)
        }
    }

    private var selectedProfileSubscription: ProfileSubscriptionViewHolder? = null

    fun latencyText(profile: Profile) = when (profile.latency) {
        Profile.LATENCY_UNKNOWN -> "?"
        Profile.LATENCY_TESTING -> getString(R.string.latency_testing)
        Profile.LATENCY_PENDING -> getString(R.string.latency_pending)
        Profile.LATENCY_ERROR -> getString(R.string.latency_error)
        else -> profile.latency.toString() + "ms"
    }

    private class LockEdit : AsyncTask<Unit, Int, Unit>() {
        var lockSubscriptionAdapter: ProfileSubscriptionsAdapter? = null
        var lockProfileAdapter: ProfilesAdapter? = null
        var lockState: Boolean = false
        override fun doInBackground(vararg params: Unit?) {

        }

        override fun onPostExecute(result: Unit?) {
            lockSubscriptionAdapter?.lockEdit(lockState)
            lockProfileAdapter?.lockEdit(lockState)
        }
    }

    private class TcpingLatency : AsyncTask<Unit, Int, Int>() {
        lateinit var mainActivity: MainActivity
        lateinit var tcpingProfile: Profile
        lateinit var latencySubscriptionAdapter: ProfileSubscriptionsAdapter
        lateinit var latencyProfileAdapter: ProfilesAdapter

        override fun onProgressUpdate(vararg values: Int?) {
            super.onProgressUpdate(*values)
            tcpingProfile.latency = Profile.LATENCY_TESTING
            ProfileManager.updateProfile(tcpingProfile)
            if (tcpingProfile.subscription == 0L) {
                latencyProfileAdapter.deepRefreshId(tcpingProfile.id)
            } else {
                latencySubscriptionAdapter.refreshProfileId(tcpingProfile.id)
            }
        }

        override fun doInBackground(vararg params: Unit?): Int {
            publishProgress(0)
            if (mainActivity.state != BaseService.STOPPED) {
                return Profile.LATENCY_ERROR
            }
            val tcpingSocket = Socket(Proxy.NO_PROXY)
            var latency = Profile.LATENCY_ERROR
            try {
                val profileIP = InetAddress.getByName(tcpingProfile.host)
                val profileAddress = InetSocketAddress(profileIP, tcpingProfile.remotePort)
                val stopwatchStart = System.currentTimeMillis()
                tcpingSocket.connect(profileAddress, 2000)
                val stopwatchTime = (System.currentTimeMillis() - stopwatchStart).toInt()
                if (tcpingSocket.isConnected) {
                    latency = stopwatchTime
                }
            } catch (e: Exception) {
            }

            if (!tcpingSocket.isClosed) {
                tcpingSocket.close()
            }
            return latency
        }

        override fun onPostExecute(result: Int) {
            super.onPostExecute(result)
            tcpingProfile.latency = result
            ProfileManager.updateProfile(tcpingProfile)
            if (tcpingProfile.subscription == 0L) {
                latencyProfileAdapter.deepRefreshId(tcpingProfile.id)
            } else {
                latencySubscriptionAdapter.refreshProfileId(tcpingProfile.id)
            }
        }
    }

    private class ParseURL : AsyncTask<Unit, Int, String>() {
        var urlParse = ""
        var parseContext: Context? = null
        lateinit var addSubscriptionsAdapter: ProfileSubscriptionsAdapter

        override fun doInBackground(vararg params: Unit): String {
            var urlResult = ""
            urlParse = urlParse.trim()
            try {
                urlResult = URL(urlParse).readText()
            } catch (e: Exception) {
                printLog(e)
            }
            return urlResult
        }

        override fun onPostExecute(result: String?) {
            super.onPostExecute(result)

            var oldSubscription = SubscriptionManager.getAllSubscriptions()?.firstOrNull {
                it.url == urlParse
            }
            var oldAirport = "?"
            if (oldSubscription != null) {
                oldAirport = oldSubscription.airport
            }

            if (result.isNullOrBlank()) {
                val toastMessage = parseContext?.getString(R.string.message_subscribe_fail, oldAirport)
                Toast.makeText(parseContext, toastMessage, Toast.LENGTH_SHORT).show()
                return
            }

            val newSubscription = SubscriptionManager.createSubscription()
            try {
                val base64Encoded = result.replace("ssd://", "")
                val base64Decoded = Base64
                        .decode(base64Encoded, Base64.URL_SAFE)
                        .toString(Charset.forName("UTF-8"))
                val jsonObject = JSONObject(base64Decoded)
                newSubscription.apply {
                    airport = jsonObject.getString("airport")
                    port = jsonObject.getInt("port")
                    encryption = jsonObject.getString("encryption")
                    password = jsonObject.getString("password")

                    url = jsonObject.optString("url", urlParse)
                    trafficUsed = jsonObject.optDouble("traffic_used", -1.0)
                    trafficTotal = jsonObject.optDouble("traffic_total", -1.0)
                    expiry = jsonObject.optString("expiry", "")
                    plugin = jsonObject.optString("plugin", "")
                    pluginOptions = jsonObject.optString("plugin_options", "")
                }

                oldSubscription = SubscriptionManager.getAllSubscriptions()?.firstOrNull {
                    it.url == newSubscription.url
                }
                if (oldSubscription != null) {
                    oldAirport = oldSubscription.airport
                }

                var oldSelectedServer: Profile? = null
                if (oldSubscription != null) {
                    oldSelectedServer = ProfileManager.getProfile(oldSubscription.selectedProfileId)
                    addSubscriptionsAdapter.onRemove(oldSubscription.id)
                }

                jsonObject.optJSONArray("servers")?.let {
                    for (index in 0 until it.length()) {
                        val newProfileJSON = it.optJSONObject(index)
                        ProfileManager.createSubscriptionProfile().apply {
                            subscription = newSubscription.id
                            innerId = newProfileJSON.optInt("id", 0)
                            host = newProfileJSON.optString("server")
                            name = newProfileJSON.optString("remarks")
                            ratio = newProfileJSON.optDouble("ratio", -1.0)

                            remotePort = newProfileJSON.optInt("port", newSubscription.port)
                            method = newProfileJSON.optString("encryption", newSubscription.encryption)
                            password = newProfileJSON.optString("password", newSubscription.password)

                            var profilePlugin = newProfileJSON.optString("plugin", newSubscription.plugin)
                            val profilePluginOptions = newProfileJSON.optString("plugin_options", newSubscription.pluginOptions)
                            if (profilePlugin == "simple-obfs") {
                                profilePlugin = "obfs-local"
                                //it's not a good idea
                            }
                            if (profilePlugin != "") {
                                plugin = PluginOptions(profilePlugin, profilePluginOptions).toString(false)
                            }

                            if (oldSelectedServer != null) {
                                route = oldSelectedServer.route
                                remoteDns = oldSelectedServer.remoteDns
                                proxyApps = oldSelectedServer.proxyApps
                                bypass = oldSelectedServer.bypass
                                udpdns = oldSelectedServer.udpdns
                                ipv6 = oldSelectedServer.ipv6
                                individual = oldSelectedServer.individual
                                if (innerId == oldSelectedServer.innerId) {
                                    newSubscription.selectedProfileId = id
                                }
                            }

                            ProfileManager.updateProfile(this)
                        }
                    }
                }
                SubscriptionManager.updateSubscription(newSubscription)
                addSubscriptionsAdapter.onAdd(newSubscription)
                val toastMessage = parseContext?.getString(R.string.message_subscribe_success, newSubscription.airport)
                Toast.makeText(parseContext, toastMessage, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val toastMessage = parseContext?.getString(R.string.message_subscribe_fail, oldAirport)
                Toast.makeText(parseContext, toastMessage, Toast.LENGTH_SHORT).show()
                SubscriptionManager.delSubscriptionWithProfiles(newSubscription.id)
            }
        }
    }

    private inner class AddSubscriptionDialog {
        val builder: AlertDialog.Builder
        val editText: EditText
        lateinit var dialog: AlertDialog

        init {
            val activity = requireActivity()
            val view = activity.layoutInflater.inflate(R.layout.dialog_add_subscription, null)
            editText = view.findViewById(R.id.url_content)
            builder = AlertDialog.Builder(activity)
                    .setTitle(R.string.add_subscription)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        Toast.makeText(context, R.string.message_loading_subscription, Toast.LENGTH_SHORT).show()

                        val singleThreadExecutor = Executors.newSingleThreadExecutor()
                        ParseURL().apply {
                            urlParse = editText.text.toString()
                            parseContext = context
                            addSubscriptionsAdapter = subscriptionsAdapter
                        }.executeOnExecutor(singleThreadExecutor)
                        LockEdit().apply {
                            lockSubscriptionAdapter = subscriptionsAdapter
                            lockProfileAdapter = profilesAdapter
                            lockState = true
                        }.executeOnExecutor(singleThreadExecutor)
                    }
                    .setView(view)
        }

        fun show() {
            dialog = builder.create()
            dialog.show()
        }
    }

    val subscriptionsAdapter by lazy { ProfileSubscriptionsAdapter() }

    fun checkVisible() {
        val profilesList = view?.findViewById<RecyclerView>(R.id.list)
        val profileTitle = view?.findViewById<TextView>(R.id.tv_title_common_profile)
        val subscriptionsList = view?.findViewById<RecyclerView>(R.id.list_subscription)
        val subscriptionTitle = view?.findViewById<TextView>(R.id.tv_title_subscription)
        if (subscriptionsAdapter.subscriptions.isEmpty()) {
            subscriptionsList?.visibility = View.INVISIBLE
            subscriptionTitle?.visibility = View.INVISIBLE
            profilesList?.visibility = View.VISIBLE
            profileTitle?.visibility = View.VISIBLE
        } else {
            subscriptionsList?.visibility = View.VISIBLE
            subscriptionTitle?.visibility = View.VISIBLE
            if (profilesAdapter.profiles.isEmpty()) {
                profilesList?.visibility = View.INVISIBLE
                profileTitle?.visibility = View.INVISIBLE
            } else {
                profilesList?.visibility = View.VISIBLE
                profileTitle?.visibility = View.VISIBLE
            }
        }
    }

//endregion

    private var selectedItem: ProfileViewHolder? = null

    val profilesAdapter by lazy { ProfilesAdapter() }
    private lateinit var undoManager: UndoSnackbarManager<Profile>
    private val statsCache = LongSparseArray<TrafficStats>()

    private val clipboard by lazy { requireContext().getSystemService<ClipboardManager>()!! }

    private fun startConfig(profile: Profile) {
        profile.serialize()
        startActivity(Intent(context, ProfileConfigActivity::class.java).putExtra(Action.EXTRA_PROFILE_ID, profile.id))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.layout_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.profiles)
        toolbar.inflateMenu(R.menu.profile_manager_menu)
        toolbar.setOnMenuItemClickListener(this)

        ProfileManager.ensureNotEmpty()
        val profilesList = view.findViewById<RecyclerView>(R.id.list)
        val layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        profilesList.layoutManager = layoutManager
        profilesList.addItemDecoration(DividerItemDecoration(context, layoutManager.orientation))
        layoutManager.scrollToPosition(profilesAdapter.profiles.indexOfFirst { it.id == DataStore.profileId })
        val animator = DefaultItemAnimator()
        animator.supportsChangeAnimations = false // prevent fading-in/out when rebinding
        profilesList.itemAnimator = animator
        profilesList.adapter = profilesAdapter
        instance = this
        ProfileManager.listener = profilesAdapter
        undoManager = UndoSnackbarManager(activity as MainActivity, profilesAdapter::undo, profilesAdapter::commit)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                ItemTouchHelper.START or ItemTouchHelper.END) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                    if (isProfileEditable((viewHolder as ProfileViewHolder).item.id))
                        super.getSwipeDirs(recyclerView, viewHolder) else 0

            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                    if (isEnabled) super.getDragDirs(recyclerView, viewHolder) else 0

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.adapterPosition
                profilesAdapter.remove(index)
                undoManager.remove(Pair(index, (viewHolder as ProfileViewHolder).item))
            }

            override fun onMove(recyclerView: RecyclerView,
                                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                profilesAdapter.move(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                profilesAdapter.commitMove()
            }
        }).attachToRecyclerView(profilesList)
        //region SSD
        val subscriptionsList = view.findViewById<RecyclerView>(R.id.list_subscription)
        val subscriptionLayoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        subscriptionsList.layoutManager = subscriptionLayoutManager
        subscriptionsList.addItemDecoration(DividerItemDecoration(context, subscriptionLayoutManager.orientation))
        subscriptionLayoutManager.scrollToPosition(subscriptionsAdapter.subscriptions.indexOfFirst {
            it.id == ProfileManager.getProfile(DataStore.profileId)?.subscription
        })
        val animatorSubscription = DefaultItemAnimator()
        subscriptionsList.itemAnimator = animatorSubscription
        subscriptionsList.adapter = subscriptionsAdapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.START or ItemTouchHelper.END) {
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.adapterPosition
                subscriptionsAdapter.remove(index)
            }

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return false
            }
        }).attachToRecyclerView(subscriptionsList)

        checkVisible()
        //endregion
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
                true
            }
            R.id.action_import_clipboard -> {
                try {
                    val profiles = Profile.findAllUrls(
                            clipboard.primaryClip!!.getItemAt(0).text,
                            Core.currentProfile?.first
                    ).toList()
                    if (profiles.isNotEmpty()) {
                        profiles.forEach { ProfileManager.createProfile(it) }
                        (activity as MainActivity).snackbar().setText(R.string.action_import_msg).show()
                        return true
                    }
                } catch (exc: Exception) {
                    exc.printStackTrace()
                }
                (activity as MainActivity).snackbar().setText(R.string.action_import_err).show()
                true
            }
            R.id.action_import_file -> {
                startFilesForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/*", "text/*"))
                }, REQUEST_IMPORT_PROFILES)
                true
            }
            R.id.action_manual_settings -> {
                startConfig(ProfileManager.createProfile(
                        Profile().also { Core.currentProfile?.first?.copyFeatureSettingsTo(it) }))
                true
            }
            //region SSD
            R.id.action_add_subscription -> {
                if (!subscriptionsAdapter.lockEditable) {
                    Toast.makeText(context, R.string.message_profiles_being_used, Toast.LENGTH_SHORT).show()
                    return true
                }
                subscriptionsAdapter.lockEdit(false)

                AddSubscriptionDialog().show()
                true
            }
            R.id.action_update_subscription -> {
                if ((activity as MainActivity).state != BaseService.STOPPED) {
                    Toast.makeText(context, R.string.message_disconnect_first, Toast.LENGTH_SHORT).show()
                    return true
                }

                if (!subscriptionsAdapter.lockEditable) {
                    Toast.makeText(context, R.string.message_profiles_being_used, Toast.LENGTH_SHORT).show()
                    return true
                }
                subscriptionsAdapter.lockEdit(false)

                Toast.makeText(context, R.string.message_updating_subscription, Toast.LENGTH_SHORT).show()

                val singleThreadExecutor = Executors.newSingleThreadExecutor()
                SubscriptionManager.getAllSubscriptions()?.forEach {
                    ParseURL().apply {
                        urlParse = it.url
                        parseContext = context
                        addSubscriptionsAdapter = subscriptionsAdapter
                    }.executeOnExecutor(singleThreadExecutor)
                }
                LockEdit().apply {
                    lockSubscriptionAdapter = subscriptionsAdapter
                    lockState = true
                }.executeOnExecutor(singleThreadExecutor)
                true
            }

            R.id.action_tcping_latency -> {
                if ((activity as MainActivity).state != BaseService.STOPPED) {
                    Toast.makeText(context, R.string.message_disconnect_first, Toast.LENGTH_SHORT).show()
                    return true
                }

                if (!subscriptionsAdapter.lockEditable || !profilesAdapter.lockEditable) {
                    Toast.makeText(context, R.string.message_profiles_being_used, Toast.LENGTH_SHORT).show()
                    return true
                }
                subscriptionsAdapter.lockEdit(false)
                profilesAdapter.lockEdit(false)

                val profileListWithOrder = mutableListOf<Profile>()
                SubscriptionManager.getAllSubscriptions()?.forEach {
                    val subscriptionProfile = ProfileManager.getSubscription(it.id)
                    if (subscriptionProfile != null) {
                        profileListWithOrder.addAll(subscriptionProfile)
                    }
                }

                val commonProfileList = ProfileManager.getSubscription(0)
                if (commonProfileList != null) {
                    profileListWithOrder.addAll(commonProfileList)
                }

                profileListWithOrder.forEach {
                    it.latency = Profile.LATENCY_PENDING
                    ProfileManager.updateProfile(it)
                }
                profilesAdapter.profiles.forEach {
                    profilesAdapter.deepRefreshId(it.id)
                }
                subscriptionsAdapter.subscriptions.forEach {
                    subscriptionsAdapter.refreshSubscriptionId(it.id)
                }

                val singleThreadExecutor = Executors.newSingleThreadExecutor()
                profileListWithOrder.forEach {
                    TcpingLatency().apply {
                        mainActivity = activity as MainActivity
                        tcpingProfile = it
                        latencySubscriptionAdapter = subscriptionsAdapter
                        latencyProfileAdapter = profilesAdapter
                    }.executeOnExecutor(singleThreadExecutor)
                }
                LockEdit().apply {
                    lockSubscriptionAdapter = subscriptionsAdapter
                    lockProfileAdapter = profilesAdapter
                    lockState = true
                }.executeOnExecutor(singleThreadExecutor)
                true
            }
            //endregion
            R.id.action_export_clipboard -> {
                val profiles = ProfileManager.getAllProfiles()
                (activity as MainActivity).snackbar().setText(if (profiles != null) {
                    clipboard.primaryClip = ClipData.newPlainText(null, profiles.joinToString("\n"))
                    R.string.action_export_msg
                } else R.string.action_export_err).show()
                true
            }
            R.id.action_export_file -> {
                startFilesForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "profiles.json")   // optional title that can be edited
                }, REQUEST_EXPORT_PROFILES)
                true
            }
            else -> false
        }
    }

    private fun startFilesForResult(intent: Intent?, requestCode: Int) {
        try {
            startActivityForResult(intent, requestCode)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        (activity as MainActivity).snackbar(getString(R.string.file_manager_missing)).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) super.onActivityResult(requestCode, resultCode, data)
        else when (requestCode) {
            REQUEST_IMPORT_PROFILES -> {
                val feature = Core.currentProfile?.first
                var success = false
                val activity = activity as MainActivity
                for (uri in data!!.datas) try {
                    Profile.parseJson(activity.contentResolver.openInputStream(uri)!!.bufferedReader().readText(),
                            feature) {
                        ProfileManager.createProfile(it)
                        success = true
                    }
                } catch (e: Exception) {
                    printLog(e)
                }
                activity.snackbar().setText(if (success) R.string.action_import_msg else R.string.action_import_err)
                        .show()
            }
            REQUEST_EXPORT_PROFILES -> {
                val profiles = ProfileManager.getAllProfiles()
                if (profiles != null) try {
                    val lookup = LongSparseArray<Profile>(profiles.size).apply { profiles.forEach { put(it.id, it) } }
                    requireContext().contentResolver.openOutputStream(data?.data!!)!!.bufferedWriter().use {
                        it.write(JSONArray(profiles.map { it.toJson(lookup) }.toTypedArray()).toString(2))
                    }
                } catch (e: Exception) {
                    printLog(e)
                    (activity as MainActivity).snackbar(e.localizedMessage).show()
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onTrafficUpdated(profileId: Long, stats: TrafficStats) {
        if (profileId != 0L) {  // ignore aggregate stats
            statsCache.put(profileId, stats)
            profilesAdapter.refreshId(profileId)
        }
    }

    fun onTrafficPersisted(profileId: Long) {
        statsCache.remove(profileId)
        profilesAdapter.deepRefreshId(profileId)
    }

    override fun onDestroyView() {
        undoManager.flush()
        super.onDestroyView()
    }

    override fun onDestroy() {
        instance = null
        ProfileManager.listener = null
        super.onDestroy()
    }
}
