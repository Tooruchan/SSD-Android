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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.AsyncTask
import android.os.Bundle
import android.text.format.Formatter
import android.util.Base64
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
import com.github.shadowsocks.App.Companion.app
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
import java.net.URL
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProfilesFragment : ToolbarFragment(), Toolbar.OnMenuItemClickListener {
    companion object {
        /**
         * used for callback from ProfileManager and stateChanged from MainActivity
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
        BaseService.CONNECTED -> id != DataStore.profileId
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

        override fun onAttach(context: Context?) {
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
            if (item.id == bandwidthProfile) {
                tx += txTotal
                rx += rxTotal
            }
            text1.text = item.formattedName
            text2.text = ArrayList<String>().apply {
                if (!item.name.isNullOrEmpty()) this += item.formattedAddress
                val id = PluginConfiguration(item.plugin ?: "").selected
                if (id.isNotEmpty()) this += app.getString(R.string.profile_plugin, id)
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
                app.switchProfile(item.id)
                profilesAdapter.refreshId(old)
                //region SSD
                subscriptionsAdapter.refreshProfileId(old)
                //endregion
                itemView.isSelected = true
                if (activity.state == BaseService.CONNECTED) app.reloadService()
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


    inner class ProfilesAdapter : RecyclerView.Adapter<ProfileViewHolder>() {
        internal val profiles = ProfileManager.getAllProfiles()?.toMutableList() ?: mutableListOf()
        private val updated = HashSet<Profile>()

        init {
            setHasStableIds(true)   // see: http://stackoverflow.com/a/32488059/2245107
        }

        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) = holder.bind(profiles[position])

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder = ProfileViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.layout_profile, parent, false))

        override fun getItemCount(): Int = profiles.size
        override fun getItemId(position: Int): Long = profiles[position].id

        fun add(item: Profile) {
            undoManager.flush()
            val pos = itemCount
            profiles += item
            notifyItemInserted(pos)
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
        }

        fun undo(actions: List<Pair<Int, Profile>>) {
            for ((index, item) in actions) {
                profiles.add(index, item)
                notifyItemInserted(index)
            }
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

        fun removeId(id: Long) {
            val index = profiles.indexOfFirst { it.id == id }
            if (index < 0) return
            profiles.removeAt(index)
            notifyItemRemoved(index)
            if (id == DataStore.profileId) DataStore.profileId = 0  // switch to null profile
        }
    }

    //region SSD
    inner class ProfileSubscriptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        internal lateinit var item: Subscription
        internal var profiles = ProfileManager.getAllProfilesWithSubscription()?.toMutableList()
                ?: mutableListOf()
        private val traffic_usage = itemView.findViewById<TextView>(R.id.text_traffic_usage)
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

            serverDroplist.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {

                }

                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedProfile = profiles.first {
                        it.name == serverDroplist.selectedItem.toString()
                    }
                    item.selectedProfileId = selectedProfile.id
                    SubscriptionManager.updateSubscription(item)
                    if (selectedProfileSubscription == this@ProfileSubscriptionViewHolder) {
                        val activity = activity as MainActivity
                        app.switchProfile(item.selectedProfileId)
                        itemView.isSelected = true
                        if (activity.state == BaseService.CONNECTED) {
                            app.reloadService()
                        }
                    }
                }
            }
            itemView.setOnClickListener {
                val activity = activity as MainActivity
                val oldProfile = DataStore.profileId
                app.switchProfile(item.selectedProfileId)
                profilesAdapter.refreshId(oldProfile)
                subscriptionsAdapter.refreshProfileId(oldProfile)
                itemView.isSelected = true
                if (activity.state == BaseService.CONNECTED) {
                    app.reloadService()
                }
            }
        }

        fun bind(item: Subscription) {
            updateProfiles()
            this.item = item
            text1.text = item.airport
            traffic_usage.text = "%.2f / %.2f G".format(item.trafficUsed, item.trafficTotal)
            val expiryDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(item.expiry)
            val expiryDays = TimeUnit.DAYS.convert(expiryDate.time - Calendar.getInstance().time.time, TimeUnit.MILLISECONDS)
            expiry.text = getString(R.string.subscription_expiry).format(item.expiry, expiryDays)
            edit.alpha = 1F
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
            if (item.id == bandwidthProfile) {
                tx += txTotal
                rx += rxTotal
            }

            val context = requireContext()
            traffic.text = if (tx <= 0 && rx <= 0) null else getString(R.string.traffic,
                    Formatter.formatFileSize(context, tx), Formatter.formatFileSize(context, rx))

            if (selectedProfile.id == DataStore.profileId) {
                itemView.isSelected = true
                selectedItem = null
                selectedProfileSubscription = this
            } else {
                itemView.isSelected = false
                if (selectedProfileSubscription === this) {
                    selectedProfileSubscription = null
                }
            }

            val subscriptionProfileList = ArrayList<String>()
            ProfileManager.getSubscription(item.id)?.forEach {
                subscriptionProfileList.add(it.name ?: it.host)
            }
            if (subscriptionProfileList.isEmpty()) {
//todo when empty,delete itself
            }
            val subscriptionAdapter = ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, subscriptionProfileList)
            subscriptionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_item)
            serverDroplist.adapter = subscriptionAdapter
            val selectionIndex = subscriptionProfileList.indexOfFirst {
                it == selectedProfile.name
            }
            serverDroplist.setSelection(selectionIndex)
        }

        private fun updateProfiles() {
            profiles = ProfileManager.getAllProfilesWithSubscription()?.toMutableList() ?: mutableListOf()
        }
    }

    inner class ProfileSubscriptionsAdapter : RecyclerView.Adapter<ProfileSubscriptionViewHolder>() {
        internal val subscriptions = SubscriptionManager.getAllSubscriptions()?.toMutableList()
                ?: mutableListOf()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileSubscriptionViewHolder = ProfileSubscriptionViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.layout_main_subscription, parent, false))

        override fun getItemCount(): Int = subscriptions.size
        override fun getItemId(position: Int): Long = subscriptions[position].id
        override fun onBindViewHolder(holder: ProfileSubscriptionViewHolder, position: Int) = holder.bind(subscriptions[position])


        fun add(item: Subscription) {
            val pos = itemCount
            subscriptions += item
            notifyItemInserted(pos)
        }

        fun remove(pos: Int) {
            val deleteSubscriptionId = subscriptions[pos].id
            SubscriptionManager.delSubscriptionWithProfiles(deleteSubscriptionId)
            subscriptions.removeAt(pos)
            notifyItemRemoved(pos)
        }

        fun removeSubscriptionId(id: Long) {
            val index = subscriptions.indexOfFirst { it.id == id }
            if (index < 0) return
            SubscriptionManager.delSubscriptionWithProfiles(id)
            subscriptions.removeAt(index)
            notifyItemRemoved(index)
            if (id == ProfileManager.getProfile(DataStore.profileId)?.subscription) {
                DataStore.profileId = 0
            }
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

    private class ParseURL : AsyncTask<Unit, Int, String>() {
        var urlParse = ""
        var parseContext: Context? = null
        var addSubscriptionsAdapter: ProfileSubscriptionsAdapter? = null
        override fun doInBackground(vararg params: Unit): String {
            var urlResult = ""
            try {
                urlResult = URL(urlParse).readText()
            } catch (e: Exception) {

            }
            return urlResult
        }

        override fun onPostExecute(result: String?) {
            super.onPostExecute(result)
            if (result.isNullOrBlank()) {
                val messageShow = parseContext?.getString(R.string.message_subscribe_fail)
                Toast.makeText(parseContext, messageShow, Toast.LENGTH_LONG).show()
                return
            }

            val newSubscription = SubscriptionManager.createSubscription()
            try {
                val base64Encoded = result?.replace("ssd://", "")
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
                    if(plugin=="simple-obfs"){
                        plugin="obfs-local"
                        //it's not a good idea
                    }
                    pluginOptions = jsonObject.optString("plugin_options", "")
                }
                var oldSubscription = SubscriptionManager.getAllSubscriptions()?.firstOrNull {
                    it.url == urlParse
                }
                if (oldSubscription != null) {
                    addSubscriptionsAdapter?.removeSubscriptionId(oldSubscription.id)
                }
                SubscriptionManager.updateSubscription(newSubscription)

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
                            if (newSubscription.plugin != "") {
                                plugin = PluginOptions(newSubscription.plugin, newSubscription.pluginOptions).toString(false)
                            }
                            ProfileManager.updateProfile(this)
                        }
                    }
                }
                addSubscriptionsAdapter?.add(newSubscription)
            } catch (e: Exception) {
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
                        val messageShow = getString(R.string.message_loading_subscription)
                        Toast.makeText(context, messageShow, Toast.LENGTH_SHORT).show()
                        ParseURL().apply {
                            urlParse = editText.text.toString()
                            parseContext = context
                            addSubscriptionsAdapter = subscriptionsAdapter
                        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
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
        val subscriptionsList = view?.findViewById<RecyclerView>(R.id.list_subscription)
        if (!SubscriptionManager.isNotEmpty()) {
            //if subscription is empty
            subscriptionsList?.visibility = View.GONE
            profilesList?.visibility = View.VISIBLE
        } else {
            subscriptionsList?.visibility = View.VISIBLE
            if (!ProfileManager.withoutSubscriptionIsNotEmpty()) {
                //if subscription is not empty and profile is empty
                profilesList?.visibility = View.GONE
            } else {
                profilesList?.visibility = View.VISIBLE
            }
        }
    }

    //endregion

    private var selectedItem: ProfileViewHolder? = null

    val profilesAdapter by lazy { ProfilesAdapter() }
    private lateinit var undoManager: UndoSnackbarManager<Profile>
    private var bandwidthProfile = 0L
    private var txTotal: Long = 0L
    private var rxTotal: Long = 0L

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

        if (!ProfileManager.isNotEmpty()) DataStore.profileId = ProfileManager.createProfile().id
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
                    val profiles = Profile.findAllUrls(clipboard.primaryClip!!.getItemAt(0).text, app.currentProfile)
                            .toList()
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
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, REQUEST_IMPORT_PROFILES)
                true
            }
            R.id.action_manual_settings -> {
                startConfig(ProfileManager.createProfile(
                        Profile().also { app.currentProfile?.copyFeatureSettingsTo(it) }))
                true
            }
            //region SSD
            R.id.action_add_subscription -> {
                AddSubscriptionDialog().show()
                true
            }
            R.id.action_update_subscription -> {
                val messageShow = getString(R.string.message_updating_subscription)
                Toast.makeText(context, messageShow, Toast.LENGTH_LONG).show()
                SubscriptionManager.getAllSubscriptions()?.forEach {
                    ParseURL().apply {
                        urlParse = it.url
                        parseContext = context
                        addSubscriptionsAdapter = subscriptionsAdapter
                    }.executeOnExecutor(Executors.newSingleThreadExecutor())
                }
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
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "profiles.json")   // optional title that can be edited
                }, REQUEST_EXPORT_PROFILES)
                true
            }
            else -> false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) super.onActivityResult(requestCode, resultCode, data)
        else when (requestCode) {
            REQUEST_IMPORT_PROFILES -> {
                val feature = app.currentProfile
                var success = false
                val activity = activity as MainActivity
                for (uri in data!!.datas) try {
                    Profile.parseJson(activity.contentResolver.openInputStream(uri)!!.bufferedReader().readText(),
                            feature).forEach {
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
                    requireContext().contentResolver.openOutputStream(data?.data!!)!!.bufferedWriter().use {
                        it.write(JSONArray(profiles.map { it.toJson() }.toTypedArray()).toString(2))
                    }
                } catch (e: Exception) {
                    printLog(e)
                    (activity as MainActivity).snackbar(e.localizedMessage).show()
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onTrafficUpdated(profileId: Long, txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long) {
        if (profileId != -1L) { // ignore resets from MainActivity
            if (bandwidthProfile != profileId) {
                onTrafficPersisted(bandwidthProfile)
                bandwidthProfile = profileId
            }
            this.txTotal = txTotal
            this.rxTotal = rxTotal
            profilesAdapter.refreshId(profileId)
        }
    }

    fun onTrafficPersisted(profileId: Long) {
        txTotal = 0
        rxTotal = 0
        if (bandwidthProfile != profileId) {
            onTrafficPersisted(bandwidthProfile)
            bandwidthProfile = profileId
        }
        profilesAdapter.deepRefreshId(profileId)
    }

    override fun onDestroyView() {
        undoManager.flush()
        super.onDestroyView()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
