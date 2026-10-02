package com.limelight

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.grid.AppGridAdapter
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.AdapterFragment
import com.limelight.ui.AdapterFragmentCallbacks
import com.limelight.utils.CacheHelper
import com.limelight.utils.Dialog
import com.limelight.utils.NotificationHelper
import com.limelight.utils.ServerHelper
import com.limelight.utils.ShortcutHelper
import com.limelight.utils.SpinnerDialog
import com.limelight.utils.ThemeManager
import com.limelight.utils.UiHelper
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader
import java.util.HashSet
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

class AppView : AppCompatActivity(), AdapterFragmentCallbacks {
    private var appGridAdapter: AppGridAdapter? = null
    private var uuidString: String? = null
    private var shortcutHelper: ShortcutHelper? = null

    private var computer: ComputerDetails? = null
    private var poller: ComputerManagerService.ApplistPoller? = null
    private var blockingLoadSpinner: SpinnerDialog? = null
    private var lastRawApplist: String? = null
    private var lastRunningAppId = 0
    private var suspendGridUpdates = false
    private var inForeground = false
    private var showHiddenApps = false
    private val hiddenAppIds = HashSet<Int>()

    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as ComputerManagerService.ComputerManagerBinder

            thread {
                localBinder.waitForReady()

                val comp = localBinder.getComputer(uuidString)
                if (comp == null) {
                    finish()
                    return@thread
                }
                computer = comp

                val sHelper = shortcutHelper
                if (sHelper != null) {
                    sHelper.createAppViewShortcut(comp, true, intent.getBooleanExtra(NEW_PAIR_EXTRA, false))
                    sHelper.reportComputerShortcutUsed(comp)
                }

                try {
                    appGridAdapter = AppGridAdapter(
                        this@AppView,
                        PreferenceConfiguration.readPreferences(this@AppView),
                        comp,
                        localBinder.uniqueId,
                        showHiddenApps
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    finish()
                    return@thread
                }

                appGridAdapter?.updateHiddenApps(hiddenAppIds, true)

                managerBinder = localBinder

                populateAppGridWithCache()
                startComputerUpdates()

                runOnUiThread {
                    if (isFinishing || isChangingConfigurations) {
                        return@runOnUiThread
                    }

                    try {
                        @Suppress("DEPRECATION")
                        fragmentManager.beginTransaction()
                            .replace(R.id.appFragmentContainer, AdapterFragment())
                            .commitAllowingStateLoss()
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                    }
                }
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            managerBinder = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val adapter = appGridAdapter
        if (adapter != null) {
            adapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this))

            try {
                @Suppress("DEPRECATION")
                fragmentManager.beginTransaction()
                    .replace(R.id.appFragmentContainer, AdapterFragment())
                    .commitAllowingStateLoss()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }
    }

    private fun startComputerUpdates() {
        val binder = managerBinder ?: return
        if (!inForeground) {
            return
        }

        binder.startPolling(object : ComputerManagerListener {
            override fun notifyComputerUpdated(details: ComputerDetails) {
                if (suspendGridUpdates) {
                    return
                }

                if (!details.uuid.equals(uuidString, ignoreCase = true)) {
                    return
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    runOnUiThread {
                        Toast.makeText(this@AppView, resources.getText(R.string.lost_connection), Toast.LENGTH_SHORT).show()
                        NotificationHelper.notifyConnectionLost(this@AppView, details.name)
                        finish()
                    }
                    return
                }

                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    runOnUiThread {
                        shortcutHelper?.disableComputerShortcut(
                            details,
                            resources.getString(R.string.scut_not_paired)
                        )
                        Toast.makeText(this@AppView, resources.getText(R.string.scut_not_paired), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return
                }

                if (details.rawAppList == null || details.rawAppList == lastRawApplist) {
                    if (details.runningGameId != lastRunningAppId) {
                        lastRunningAppId = details.runningGameId
                        updateUiWithServerinfo(details)
                    }
                    return
                }

                lastRunningAppId = details.runningGameId
                lastRawApplist = details.rawAppList

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(StringReader(details.rawAppList)))
                    updateUiWithServerinfo(details)

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner?.dismiss()
                        blockingLoadSpinner = null
                    }
                } catch (e: XmlPullParserException) {
                    e.printStackTrace()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        })

        if (poller == null) {
            val comp = computer
            if (comp != null) {
                poller = binder.createAppListPoller(comp)
            }
        }
        poller?.start()
    }

    private fun stopComputerUpdates() {
        poller?.stop()
        managerBinder?.stopPolling()
        appGridAdapter?.cancelQueuedOperations()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        inForeground = true
        shortcutHelper = ShortcutHelper(this)
        UiHelper.setLocale(this)

        setContentView(R.layout.activity_app_view)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false)
        }

        UiHelper.notifyNewRootView(this)

        findViewById<View>(R.id.btnClose)?.setOnClickListener { finish() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val contentView = findViewById<View>(android.R.id.content)
            ViewCompat.setOnApplyWindowInsetsListener(contentView) { v, insets ->
                val sysInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(sysInsets.left, 0, sysInsets.right, 0)
                insets
            }

            val appContainer = findViewById<View>(R.id.appFragmentContainer)
            if (appContainer != null) {
                ViewCompat.setOnApplyWindowInsetsListener(appContainer) { v, insets ->
                    val sysInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    val basePadding = (16 * resources.displayMetrics.density).roundToInt()
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + sysInsets.bottom)
                    insets
                }
            }

            contentView.requestApplyInsets()
        }

        showHiddenApps = intent.getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false)
        uuidString = intent.getStringExtra(UUID_EXTRA)

        val hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
        val savedSet = hiddenAppsPrefs.getStringSet(uuidString, HashSet<String>())
        if (savedSet != null) {
            for (hiddenAppIdStr in savedSet) {
                try {
                    hiddenAppIds.add(hiddenAppIdStr.toInt())
                } catch (ignored: NumberFormatException) {
                }
            }
        }

        val computerName = intent.getStringExtra(NAME_EXTRA)
        val label = findViewById<TextView>(R.id.appListText)
        title = computerName
        label?.text = computerName

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE
        )
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_fade_enter, R.anim.activity_slide_out_right)
    }

    private fun updateHiddenApps(hideImmediately: Boolean) {
        val hiddenAppIdStringSet = HashSet<String>()
        for (hiddenAppId in hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString())
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
            .edit()
            .putStringSet(uuidString, hiddenAppIdStringSet)
            .apply()

        appGridAdapter?.updateHiddenApps(hiddenAppIds, hideImmediately)
    }

    private fun populateAppGridWithCache() {
        try {
            lastRawApplist = CacheHelper.readInputStreamToString(
                CacheHelper.openCacheFileForInput(cacheDir, "applist", uuidString)
            )
            val applist = NvHTTP.getAppListByReader(StringReader(lastRawApplist))
            updateUiWithAppList(applist)
            LimeLog.info("Loaded applist from cache")
        } catch (e: Exception) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: $lastRawApplist")
                e.printStackTrace()
            }
            LimeLog.info("Loading applist from the network")
            loadAppsBlocking()
        }
    }

    private fun loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(
            this,
            resources.getString(R.string.applist_refresh_title),
            resources.getString(R.string.applist_refresh_msg),
            true
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        SpinnerDialog.closeDialogs(this)
        Dialog.closeDialogs()

        if (managerBinder != null) {
            unbindService(serviceConnection)
        }
    }

    override fun onResume() {
        super.onResume()
        UiHelper.showDecoderCrashDialog(this)
        inForeground = true
        startComputerUpdates()
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        stopComputerUpdates()
    }

    private fun showAppPopupMenu(selectedApp: AppObject, anchorView: View) {
        val popup = PopupMenu(this, anchorView)
        val menu = popup.menu

        if (lastRunningAppId != 0) {
            if (lastRunningAppId == selectedApp.app.appId) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, resources.getString(R.string.applist_menu_resume))
                menu.add(Menu.NONE, QUIT_ID, 2, resources.getString(R.string.applist_menu_quit))
            } else {
                menu.add(Menu.NONE, START_WITH_QUIT, 1, resources.getString(R.string.applist_menu_quit_and_start))
            }
        }

        if (lastRunningAppId != selectedApp.app.appId || selectedApp.isHidden) {
            val hideItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, resources.getString(R.string.applist_menu_hide_app))
            hideItem.isCheckable = true
            hideItem.isChecked = selectedApp.isHidden
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, resources.getString(R.string.applist_menu_details))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appImageView = anchorView.findViewById<ImageView>(R.id.grid_image)
            if (appImageView != null) {
                val drawable = appImageView.drawable as? BitmapDrawable
                if (drawable != null && drawable.bitmap != null) {
                    menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, resources.getString(R.string.applist_menu_scut))
                }
            }
        }

        popup.setOnMenuItemClickListener { item: MenuItem ->
            val comp = computer ?: return@setOnMenuItemClickListener false
            val binder = managerBinder ?: return@setOnMenuItemClickListener false
            when (item.itemId) {
                START_WITH_QUIT -> {
                    UiHelper.displayQuitConfirmationDialog(this, {
                        ServerHelper.doStart(this@AppView, selectedApp.app, comp, binder)
                    }, null)
                    true
                }
                START_OR_RESUME_ID -> {
                    ServerHelper.doStart(this@AppView, selectedApp.app, comp, binder)
                    true
                }
                QUIT_ID -> {
                    UiHelper.displayQuitConfirmationDialog(this, {
                        suspendGridUpdates = true
                        ServerHelper.doQuit(this@AppView, comp, selectedApp.app, binder) {
                            suspendGridUpdates = false
                            poller?.pollNow()
                        }
                    }, null)
                    true
                }
                VIEW_DETAILS_ID -> {
                    Dialog.displayDialog(
                        this@AppView,
                        resources.getString(R.string.title_details),
                        selectedApp.app.toString(),
                        false
                    )
                    true
                }
                HIDE_APP_ID -> {
                    if (selectedApp.isHidden) {
                        hiddenAppIds.remove(selectedApp.app.appId)
                    } else {
                        hiddenAppIds.add(selectedApp.app.appId)
                    }
                    updateHiddenApps(false)
                    true
                }
                CREATE_SHORTCUT_ID -> {
                    val imgView = anchorView.findViewById<ImageView>(R.id.grid_image)
                    if (imgView != null) {
                        val bmp = (imgView.drawable as? BitmapDrawable)?.bitmap
                        val helper = shortcutHelper
                        if (helper == null || !helper.createPinnedGameShortcut(comp, selectedApp.app, bmp)) {
                            Toast.makeText(
                                this@AppView,
                                resources.getString(R.string.unable_to_pin_shortcut),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun updateUiWithServerinfo(details: ComputerDetails) {
        runOnUiThread {
            val adapter = appGridAdapter ?: return@runOnUiThread
            var updated = false

            for (i in 0 until adapter.count) {
                val existingApp = adapter.getItem(i) as? AppObject ?: continue

                if (existingApp.isRunning && existingApp.app.appId == details.runningGameId) {
                    return@runOnUiThread
                } else if (existingApp.app.appId == details.runningGameId) {
                    existingApp.isRunning = true
                    updated = true
                } else if (existingApp.isRunning) {
                    existingApp.isRunning = false
                    updated = true
                }
            }

            if (updated) {
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun updateUiWithAppList(appList: List<NvApp>) {
        runOnUiThread {
            val adapter = appGridAdapter ?: return@runOnUiThread
            val comp = computer ?: return@runOnUiThread
            var updated = false

            for (app in appList) {
                var foundExistingApp = false

                for (i in 0 until adapter.count) {
                    val existingApp = adapter.getItem(i) as? AppObject ?: continue
                    if (existingApp.app.appId == app.appId) {
                        if (existingApp.app.appName != app.appName) {
                            existingApp.app.appName = app.appName
                            updated = true
                        }
                        foundExistingApp = true
                        break
                    }
                }

                if (!foundExistingApp) {
                    adapter.addApp(AppObject(app))
                    shortcutHelper?.enableAppShortcut(comp, app)
                    updated = true
                }
            }

            var i = 0
            while (i < adapter.count) {
                var foundExistingApp = false
                val existingApp = adapter.getItem(i) as? AppObject
                if (existingApp == null) {
                    i++
                    continue
                }

                for (app in appList) {
                    if (existingApp.app.appId == app.appId) {
                        foundExistingApp = true
                        break
                    }
                }

                if (!foundExistingApp) {
                    shortcutHelper?.disableAppShortcut(comp, existingApp.app, "App removed from PC")
                    adapter.removeApp(existingApp)
                    updated = true
                    continue
                }
                i++
            }

            if (updated) {
                adapter.refreshVisibleList()
            }
        }
    }

    override fun getAdapterFragmentLayoutId(): Int {
        return if (PreferenceConfiguration.readPreferences(this).smallIconMode) {
            R.layout.app_grid_view_small
        } else {
            R.layout.app_grid_view
        }
    }

    override fun receiveAbsListView(gridView: AbsListView) {
        // No-op; AppView uses RecyclerView exclusively
    }

    override fun receiveRecyclerView(recyclerView: RecyclerView) {
        val prefs = PreferenceConfiguration.readPreferences(this)

        val screenWidthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val columnWidthDp = if (prefs.smallIconMode) 100 else 150
        val computed = max(1, screenWidthDp / columnWidthDp)

        recyclerView.layoutManager = GridLayoutManager(this, computed)
        recyclerView.adapter = appGridAdapter
        UiHelper.applyStatusBarPadding(recyclerView)

        appGridAdapter?.setOnAppClickListener { app, _ ->
            val comp = computer ?: return@setOnAppClickListener
            val binder = managerBinder ?: return@setOnAppClickListener
            if (lastRunningAppId != 0 && lastRunningAppId != app.app.appId) {
                UiHelper.displayQuitConfirmationDialog(this, {
                    ServerHelper.doStart(this@AppView, app.app, comp, binder)
                }, null)
            } else {
                ServerHelper.doStart(this@AppView, app.app, comp, binder)
            }
        }

        appGridAdapter?.setOnAppLongClickListener { app, itemView ->
            showAppPopupMenu(app, itemView)
            true
        }

        recyclerView.requestFocus()
    }

    class AppObject(@JvmField val app: NvApp) {
        @JvmField var isRunning = false
        @JvmField var isHidden = false

        override fun toString(): String {
            return app.appName
        }
    }

    companion object {
        private const val START_OR_RESUME_ID = 1
        private const val QUIT_ID = 2
        private const val START_WITH_QUIT = 4
        private const val VIEW_DETAILS_ID = 5
        private const val CREATE_SHORTCUT_ID = 6
        private const val HIDE_APP_ID = 7

        const val HIDDEN_APPS_PREF_FILENAME = "HiddenApps"
        const val NAME_EXTRA = "Name"
        const val UUID_EXTRA = "UUID"
        const val NEW_PAIR_EXTRA = "NewPair"
        const val SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps"
    }
}
