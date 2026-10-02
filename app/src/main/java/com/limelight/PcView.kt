package com.limelight

import android.app.ActivityManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.view.HapticFeedbackConstants
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.preference.PreferenceManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.limelight.binding.PlatformBinding
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.grid.PcGridAdapter
import com.limelight.grid.assets.DiskAssetLoader
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.preferences.AddComputerManually
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.StreamSettings
import com.limelight.smartdisplay.AppContainer
import com.limelight.smartdisplay.host.SunshinePairHelper
import com.limelight.smartdisplay.recents.LastSessionStore
import com.limelight.ui.RainView
import com.limelight.ui.effects.ConnectionVisualState
import com.limelight.ui.effects.LiquidStartButton
import com.limelight.utils.Dialog
import com.limelight.utils.NotificationHelper
import com.limelight.utils.ServerHelper
import com.limelight.utils.ShortcutHelper
import com.limelight.utils.ThemeManager
import com.limelight.utils.UiHelper
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class PcView : AppCompatActivity() {
    private var noPcFoundLayout: View? = null
    private var pcGridAdapter: PcGridAdapter? = null
    private var shortcutHelper: ShortcutHelper? = null

    // SmartDisplay UX Layer: estado de red en vivo + tarjeta "Última sesión"
    private var headerStatusDot: View? = null
    private var headerNetworkText: TextView? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastSessionStore: LastSessionStore? = null
    private var lastSessionCard: View? = null
    private var lastSessionTitle: TextView? = null
    private var lastSessionSubtitle: TextView? = null

    // SmartDisplay 3D Hero & Liquid Start Button
    private var liquidStartButton: LiquidStartButton? = null
    private var heroPcName: TextView? = null
    private var heroStatusText: TextView? = null
    private var capsuleLatencyText: TextView? = null
    private var capsuleFpsText: TextView? = null
    private var activeComputer: ComputerObject? = null

    private val shortcutCreatedUuids = ConcurrentHashMap.newKeySet<String>()
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private var addPcReceiver: BroadcastReceiver? = null
    private var freezeUpdates = false
    private var runningPolling = false
    private var inForeground = false
    private var completeOnCreateCalled = false

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as ComputerManagerService.ComputerManagerBinder

            thread {
                localBinder.waitForReady()
                managerBinder = localBinder
                startComputerUpdates()
                AndroidCryptoProvider(this@PcView).getClientCertificate()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            managerBinder = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (completeOnCreateCalled) {
            initializeViews()
        }
    }

    private fun initializeViews() {
        setContentView(R.layout.activity_pc_view)

        UiHelper.notifyNewRootView(this)

        val rainView = findViewById<RainView>(R.id.rain_view)
        if (rainView != null && "rain" == ThemeManager.getThemeKey(this)) {
            rainView.visibility = View.VISIBLE
            rainView.start()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9010)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false)
        }

        @Suppress("DEPRECATION")
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar?.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_add_pc) {
                startActivity(Intent(this@PcView, AddComputerManually::class.java))
                overridePendingTransition(R.anim.activity_slide_in_right, R.anim.activity_fade_exit)
                true
            } else {
                false
            }
        }

        headerStatusDot = findViewById(R.id.header_status_dot)
        headerNetworkText = findViewById(R.id.header_network_text)

        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        val menuButton = findViewById<View>(R.id.menuButton)
        val navRail = findViewById<View>(R.id.slim_nav_rail)
        if (drawerLayout != null) {
            menuButton?.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                if (navRail != null) {
                    if (drawerLayout.isDrawerOpen(navRail)) {
                        drawerLayout.closeDrawer(navRail)
                    } else {
                        drawerLayout.openDrawer(navRail)
                    }
                } else {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        drawerLayout.openDrawer(GravityCompat.START)
                    }
                }
            }

            val railListener = View.OnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val id = v.id
                val action = Runnable {
                    if (isFinishing || isDestroyed) {
                        return@Runnable
                    }
                    when (id) {
                        R.id.rail_nav_add_pc -> startActivity(Intent(this@PcView, AddComputerManually::class.java))
                        R.id.rail_nav_files -> startActivity(Intent(this@PcView, FileTransferActivity::class.java))
                        R.id.rail_nav_theme -> showThemeSelectorDialog()
                        R.id.rail_nav_settings -> startActivity(Intent(this@PcView, StreamSettings::class.java))
                        R.id.rail_nav_diagnostics -> showDiagnosticsPanel()
                        R.id.rail_nav_logs -> showLogsDialog()
                        R.id.rail_nav_help -> startActivity(Intent(this@PcView, ManualActivity::class.java))
                        R.id.rail_nav_donate -> startActivity(Intent(this@PcView, DonateActivity::class.java))
                        R.id.rail_nav_about -> showAboutDialog()
                    }
                }

                if (navRail != null && drawerLayout.isDrawerOpen(navRail)) {
                    drawerLayout.closeDrawer(navRail)
                    drawerLayout.postDelayed(action, 160)
                } else if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    drawerLayout.postDelayed(action, 160)
                } else {
                    action.run()
                }
            }

            val railButtonIds = intArrayOf(
                R.id.rail_nav_add_pc, R.id.rail_nav_files, R.id.rail_nav_theme,
                R.id.rail_nav_settings, R.id.rail_nav_diagnostics, R.id.rail_nav_logs,
                R.id.rail_nav_help, R.id.rail_nav_donate, R.id.rail_nav_about
            )
            for (btnId in railButtonIds) {
                findViewById<View>(btnId)?.setOnClickListener(railListener)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val drawerBackCallback = OnBackInvokedCallback {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    }
                }
                drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
                    override fun onDrawerOpened(drawerView: View) {
                        try {
                            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                                drawerBackCallback
                            )
                        } catch (ignored: Exception) {
                        }
                    }

                    override fun onDrawerClosed(drawerView: View) {
                        try {
                            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(drawerBackCallback)
                        } catch (ignored: Exception) {
                        }
                    }
                })
            }
        }

        headerStatusDot = findViewById(R.id.header_status_dot)
        headerNetworkText = findViewById(R.id.header_network_text)
        updateNetworkStatus()

        findViewById<View>(R.id.btn_add_pc_top)?.setOnClickListener {
            startActivity(Intent(this@PcView, AddComputerManually::class.java))
            overridePendingTransition(R.anim.activity_slide_in_right, R.anim.activity_fade_exit)
        }

        findViewById<View>(R.id.btnAddPcQuick)?.setOnClickListener {
            startActivity(Intent(this@PcView, AddComputerManually::class.java))
            overridePendingTransition(R.anim.activity_slide_in_right, R.anim.activity_fade_exit)
        }

        liquidStartButton = findViewById(R.id.liquidStartButton)
        heroPcName = findViewById(R.id.hero_pc_name)
        heroStatusText = findViewById(R.id.hero_status_text)
        capsuleLatencyText = findViewById(R.id.capsuleLatencyText)
        capsuleFpsText = findViewById(R.id.capsuleFpsText)

        liquidStartButton?.setOnClickListener { handleLiquidStartClick() }

        lastSessionCard = findViewById(R.id.last_session_card)
        lastSessionTitle = findViewById(R.id.last_session_title)
        lastSessionSubtitle = findViewById(R.id.last_session_subtitle)
        lastSessionCard?.setOnClickListener { onLastSessionClicked() }

        refreshLastSessionCard()
        refreshHeroCard()

        val recyclerView = findViewById<RecyclerView>(R.id.pc_recycler_view)
        var columns = 1
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            columns = 2
        }
        if (resources.configuration.smallestScreenWidthDp >= 840) {
            columns = 3
        }

        recyclerView?.layoutManager = GridLayoutManager(this, columns)
        recyclerView?.adapter = pcGridAdapter

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val contentView = findViewById<View>(android.R.id.content)
            ViewCompat.setOnApplyWindowInsetsListener(contentView) { v, insets ->
                val sysInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(sysInsets.left, 0, sysInsets.right, 0)
                insets
            }

            val pcRecycler = findViewById<RecyclerView>(R.id.pc_recycler_view)
            if (pcRecycler != null) {
                ViewCompat.setOnApplyWindowInsetsListener(pcRecycler) { v, insets ->
                    val sysInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    val basePadding = (16 * resources.displayMetrics.density).roundToInt()
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + sysInsets.bottom)
                    insets
                }
            }

            contentView.requestApplyInsets()
        }

        pcGridAdapter?.setOnItemClickListener(object : PcGridAdapter.OnItemClickListener {
            private var lastClickTime = 0L

            override fun onItemClick(computer: ComputerObject, view: View?) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastClickTime < 600) {
                    return
                }
                lastClickTime = now

                activeComputer = computer
                refreshHeroCard()

                if (view != null && view.id == R.id.btn_connect) {
                    launchDirectDesktop(computer)
                } else {
                    val i = Intent(this@PcView, DeviceDetailActivity::class.java)
                    i.putExtra(AppView.NAME_EXTRA, computer.details.name)
                    i.putExtra(AppView.UUID_EXTRA, computer.details.uuid)
                    startActivity(i)
                    overridePendingTransition(R.anim.activity_slide_in_right, R.anim.activity_fade_exit)
                }
            }
        })

        pcGridAdapter?.setOnItemOptionsClickListener { computer, view ->
            view?.let { showOptionsMenu(computer, it) }
        }

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout)
        val count = pcGridAdapter?.count ?: 0
        if (count == 0) {
            noPcFoundLayout?.visibility = View.VISIBLE
        } else {
            noPcFoundLayout?.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        inForeground = true

        val glPrefs = GlPreferences.readPreferences(this)
        if (glPrefs.savedFingerprint != Build.FINGERPRINT || glPrefs.glRenderer.isNullOrEmpty()) {
            val surfaceView = GLSurfaceView(this)
            surfaceView.setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl10: GL10, eglConfig: EGLConfig) {
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER)
                    glPrefs.savedFingerprint = Build.FINGERPRINT
                    glPrefs.writePreferences()

                    LimeLog.info("Fetched GL Renderer: ${glPrefs.glRenderer}")
                    runOnUiThread { completeOnCreate() }
                }

                override fun onSurfaceChanged(gl10: GL10, i: Int, i1: Int) {}
                override fun onDrawFrame(gl10: GL10) {}
            })
            setContentView(surfaceView)
        } else {
            LimeLog.info("Cached GL Renderer: ${glPrefs.glRenderer}")
            completeOnCreate()
        }
    }

    private fun completeOnCreate() {
        AppContainer.init(this)
        completeOnCreateCalled = true

        shortcutHelper = ShortcutHelper(this)
        UiHelper.setLocale(this)

        val prefsConfig = PreferenceConfiguration.readPreferences(this)
        pcGridAdapter = PcGridAdapter(this, prefsConfig)
        lastSessionStore = LastSessionStore(this)

        initializeViews()

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE
        )

        addPcReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if ("com.limelight.smartdisplay.ADD_PC" == intent.action) {
                    val ip = intent.getStringExtra("IP")
                    val tailscaleIp = intent.getStringExtra("TAILSCALE_IP")
                    val name = intent.getStringExtra("NAME")
                    val uuid = intent.getStringExtra("UUID")
                    val mac = intent.getStringExtra("MAC")
                    LimeLog.info("Broadcast ADD_PC recibido. IP: $ip, Tailscale: $tailscaleIp, UUID: $uuid")
                    if (!uuid.isNullOrEmpty()) {
                        val details = ComputerDetails()
                        details.uuid = uuid
                        details.name = name ?: "PC Detectado"
                        details.macAddress = mac ?: "00:00:00:00:00:00"
                        if (!ip.isNullOrEmpty() && "null" != ip) {
                            details.localAddress = ComputerDetails.AddressTuple(ip, NvHTTP.DEFAULT_HTTP_PORT)
                        }
                        if (!tailscaleIp.isNullOrEmpty() && "null" != tailscaleIp) {
                            details.remoteAddress = ComputerDetails.AddressTuple(tailscaleIp, NvHTTP.DEFAULT_HTTP_PORT)
                            details.manualAddress = ComputerDetails.AddressTuple(tailscaleIp, NvHTTP.DEFAULT_HTTP_PORT)
                        }
                        thread {
                            try {
                                managerBinder?.addComputerBlocking(details)
                                LimeLog.info("ADD_PC: Computadora agregada/actualizada con éxito.")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter("com.limelight.smartdisplay.ADD_PC")
        ContextCompat.registerReceiver(this, addPcReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        startComputerUpdates()
        registerNetworkCallback()
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        stopComputerUpdates(false)
        unregisterNetworkCallback()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (addPcReceiver != null) {
            try {
                unregisterReceiver(addPcReceiver)
            } catch (ignored: IllegalArgumentException) {
            }
            addPcReceiver = null
        }

        if (managerBinder != null) {
            unbindService(serviceConnection)
            managerBinder = null
        }

        liquidStartButton?.release()
        liquidStartButton = null
    }

    private fun startComputerUpdates() {
        val binder = managerBinder ?: return
        if (!runningPolling && inForeground) {
            freezeUpdates = false
            binder.startPolling(object : ComputerManagerListener {
                override fun notifyComputerUpdated(details: ComputerDetails) {
                    if (!freezeUpdates) {
                        val activeAppName = PcGridAdapter.resolveActiveAppName(details)
                        runOnUiThread { updateComputer(details, activeAppName) }

                        if (details.pairState == PairState.PAIRED && details.uuid != null &&
                            shortcutCreatedUuids.add(details.uuid)
                        ) {
                            shortcutHelper?.createAppViewShortcutForOnlineHost(details)
                        }
                    }
                }
            })
            runningPolling = true
        }
    }

    private fun stopComputerUpdates(wait: Boolean) {
        val binder = managerBinder ?: return
        if (!runningPolling) {
            return
        }

        freezeUpdates = true
        binder.stopPolling()

        if (wait) {
            binder.waitForPollingStopped()
        }

        runningPolling = false
    }

    private fun showOptionsMenu(computer: ComputerObject, anchorView: View) {
        val popup = PopupMenu(this, anchorView)
        val menu = popup.menu

        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN
        ) {
            menu.add(Menu.NONE, WOL_ID, 1, resources.getString(R.string.pcview_menu_send_wol))
        } else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, resources.getString(R.string.pcview_menu_pair_pc))
        } else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, resources.getString(R.string.applist_menu_resume))
                menu.add(Menu.NONE, QUIT_ID, 2, resources.getString(R.string.applist_menu_quit))
            }
            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, resources.getString(R.string.pcview_menu_app_list))
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, resources.getString(R.string.pcview_menu_test_network))
        menu.add(Menu.NONE, DELETE_ID, 6, resources.getString(R.string.pcview_menu_delete_pc))
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7, resources.getString(R.string.pcview_menu_details))

        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                PAIR_ID -> {
                    doPair(computer.details)
                    true
                }
                UNPAIR_ID -> {
                    doUnpair(computer.details)
                    true
                }
                WOL_ID -> {
                    doWakeOnLan(computer.details)
                    true
                }
                DELETE_ID -> {
                    if (ActivityManager.isUserAMonkey()) return@setOnMenuItemClickListener true
                    UiHelper.displayDeletePcConfirmationDialog(this@PcView, computer.details, {
                        if (managerBinder != null) removeComputer(computer.details)
                    }, null)
                    true
                }
                FULL_APP_LIST_ID -> {
                    doAppList(computer.details, newlyPaired = false, showHiddenGames = true)
                    true
                }
                RESUME_ID -> {
                    val binder = managerBinder
                    if (binder != null) {
                        ServerHelper.doStart(
                            this@PcView,
                            NvApp("app", computer.details.runningGameId, false),
                            computer.details,
                            binder
                        )
                    }
                    true
                }
                QUIT_ID -> {
                    val binder = managerBinder
                    if (binder != null) {
                        UiHelper.displayQuitConfirmationDialog(this@PcView, {
                            ServerHelper.doQuit(
                                this@PcView,
                                computer.details,
                                NvApp("app", 0, false),
                                binder,
                                null
                            )
                        }, null)
                    }
                    true
                }
                VIEW_DETAILS_ID -> {
                    Dialog.displayDialog(
                        this@PcView,
                        resources.getString(R.string.title_details),
                        computer.details.toString(),
                        false
                    )
                    true
                }
                TEST_NETWORK_ID -> {
                    ServerHelper.doNetworkTest(this@PcView)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun doPair(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(this, resources.getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show()
            return
        }
        val binder = managerBinder
        if (binder == null) {
            Toast.makeText(this, resources.getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, resources.getString(R.string.pairing), Toast.LENGTH_SHORT).show()
        thread {
            var message: String?
            var success = false
            try {
                stopComputerUpdates(true)

                val httpConn = NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort,
                    binder.uniqueId,
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(this@PcView)
                )

                if (httpConn.getPairState() == PairState.PAIRED) {
                    message = null
                    success = true
                } else {
                    val pinStr = "9999"

                    Dialog.displayDialog(
                        this@PcView,
                        resources.getString(R.string.pair_pairing_title),
                        resources.getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n" +
                                resources.getString(R.string.pair_pairing_help),
                        false
                    )

                    val activeAddr = computer.activeAddress
                    if (activeAddr != null) {
                        SunshinePairHelper.submitPinAsync(
                            this@PcView,
                            activeAddr.address,
                            pinStr,
                            "SmartDisplay"
                        )
                    }

                    val pm = httpConn.pairingManager
                    val pairState = pm.pair(httpConn.getServerInfo(true), pinStr)
                    when (pairState) {
                        PairState.PIN_WRONG -> message = resources.getString(R.string.pair_incorrect_pin)
                        PairState.FAILED -> message = if (computer.runningGameId != 0) {
                            resources.getString(R.string.pair_pc_ingame)
                        } else {
                            resources.getString(R.string.pair_fail)
                        }
                        PairState.ALREADY_IN_PROGRESS -> message = resources.getString(R.string.pair_already_in_progress)
                        PairState.PAIRED -> {
                            message = null
                            success = true
                            binder.getComputer(computer.uuid)?.serverCert = pm.getPairedCert()
                            binder.invalidateStateForComputer(computer.uuid)
                        }
                        else -> message = null
                    }
                }
            } catch (e: UnknownHostException) {
                message = resources.getString(R.string.error_unknown_host)
            } catch (e: FileNotFoundException) {
                message = resources.getString(R.string.error_404)
            } catch (e: XmlPullParserException) {
                e.printStackTrace()
                message = e.message
            } catch (e: IOException) {
                e.printStackTrace()
                message = e.message
            }

            Dialog.closeDialogs()

            val toastMessage = message
            val toastSuccess = success
            runOnUiThread {
                if (toastMessage != null) {
                    Toast.makeText(this@PcView, toastMessage, Toast.LENGTH_LONG).show()
                }

                if (toastSuccess) {
                    doAppList(computer, newlyPaired = true, showHiddenGames = false)
                } else {
                    startComputerUpdates()
                }
            }
        }
    }

    private fun doWakeOnLan(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(this, resources.getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show()
            return
        }

        if (computer.macAddress == null) {
            Toast.makeText(this, resources.getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show()
            return
        }

        thread {
            val message: String = try {
                WakeOnLanSender.sendWolPacket(computer)
                resources.getString(R.string.wol_waking_msg)
            } catch (e: IOException) {
                resources.getString(R.string.wol_fail)
            }

            runOnUiThread {
                Toast.makeText(this@PcView, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun doUnpair(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(this, resources.getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show()
            return
        }
        val binder = managerBinder
        if (binder == null) {
            Toast.makeText(this, resources.getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, resources.getString(R.string.unpairing), Toast.LENGTH_SHORT).show()
        thread {
            var message: String?
            try {
                val httpConn = NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort,
                    binder.uniqueId,
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(this@PcView)
                )
                if (httpConn.getPairState() == PairState.PAIRED) {
                    httpConn.unpair()
                    message = if (httpConn.getPairState() == PairState.NOT_PAIRED) {
                        resources.getString(R.string.unpair_success)
                    } else {
                        resources.getString(R.string.unpair_fail)
                    }
                } else {
                    message = resources.getString(R.string.unpair_error)
                }
            } catch (e: UnknownHostException) {
                message = resources.getString(R.string.error_unknown_host)
            } catch (e: FileNotFoundException) {
                message = resources.getString(R.string.error_404)
            } catch (e: XmlPullParserException) {
                message = e.message
                e.printStackTrace()
            } catch (e: IOException) {
                message = e.message
                e.printStackTrace()
            }

            val toastMessage = message
            runOnUiThread {
                Toast.makeText(this@PcView, toastMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun doAppList(computer: ComputerDetails, newlyPaired: Boolean, showHiddenGames: Boolean) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(this, resources.getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show()
            return
        }
        if (managerBinder == null) {
            Toast.makeText(this, resources.getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show()
            return
        }

        val i = Intent(this, AppView::class.java)
        i.putExtra(AppView.NAME_EXTRA, computer.name)
        i.putExtra(AppView.UUID_EXTRA, computer.uuid)
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired)
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames)
        startActivity(i)
        overridePendingTransition(R.anim.slide_up, R.anim.fade_out)
    }

    private fun removeComputer(details: ComputerDetails) {
        DiskAssetLoader(this).deleteAssetsForComputer(details.uuid)

        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
            .edit()
            .remove(details.uuid)
            .apply()

        val adapter = pcGridAdapter ?: return
        for (i in 0 until adapter.count) {
            val computer = adapter.getItem(i) as? ComputerObject ?: continue

            if (details == computer.details) {
                shortcutHelper?.disableComputerShortcut(
                    details,
                    resources.getString(R.string.scut_deleted_pc)
                )
                adapter.removeComputer(computer)

                if (adapter.count == 0) {
                    noPcFoundLayout?.visibility = View.VISIBLE
                }

                refreshHeroCard()
                break
            }
        }
    }

    private fun updateComputer(details: ComputerDetails, activeAppName: String?) {
        val adapter = pcGridAdapter ?: return
        var existingEntry: ComputerObject? = null

        for (i in 0 until adapter.count) {
            val computer = adapter.getItem(i) as? ComputerObject ?: continue
            if (details.uuid == computer.details.uuid) {
                existingEntry = computer
                break
            }
        }

        if (existingEntry != null) {
            val oldState = existingEntry.details.state
            existingEntry.details = details
            existingEntry.activeAppName = activeAppName
            adapter.notifyItemChangedFor(existingEntry)
            maybeNotifyStateChange(oldState, details)
        } else {
            val newEntry = ComputerObject(details)
            newEntry.activeAppName = activeAppName
            adapter.addComputer(newEntry)
            noPcFoundLayout?.visibility = View.INVISIBLE
            maybeNotifyStateChange(null, details)
        }

        refreshHeroCard()
    }

    private fun maybeNotifyStateChange(oldState: ComputerDetails.State?, details: ComputerDetails?) {
        if (details == null || details.state != ComputerDetails.State.ONLINE) {
            return
        }
        val becameOnline = (oldState != ComputerDetails.State.ONLINE)
        if (!becameOnline) {
            return
        }
        if (details.pairState == PairState.NOT_PAIRED) {
            NotificationHelper.notifyPairingRequired(this, details.name)
        } else {
            NotificationHelper.notifyPcOnline(this, details.name)
        }
    }

    private fun showThemeSelectorDialog() {
        if (isFinishing || isDestroyed) {
            return
        }

        try {
            val dialogView = layoutInflater.inflate(R.layout.dialog_theme_selector, null)
            val sheet = BottomSheetDialog(this)
            sheet.setContentView(dialogView)

            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.behavior.skipCollapsed = true

            val currentKey = ThemeManager.getThemeKey(this)
            val currentLight = ThemeManager.isLight(this)

            val switchMode = dialogView.findViewById<MaterialSwitch>(R.id.switch_theme_mode)
            val txtModeTitle = dialogView.findViewById<TextView>(R.id.txt_mode_title)
            val txtModeDesc = dialogView.findViewById<TextView>(R.id.txt_mode_desc)
            val iconMode = dialogView.findViewById<ImageView>(R.id.icon_mode)

            if (switchMode != null) {
                switchMode.isChecked = currentLight
                txtModeTitle?.text = if (currentLight) "Modo Claro" else "Modo Oscuro"
                txtModeDesc?.text = if (currentLight) "Fondo claro de alta legibilidad diurna" else "Optimizado para paneles OLED y contraste neón"
                iconMode?.setImageResource(if (currentLight) R.drawable.ic_help else R.drawable.ic_bedtime)

                switchMode.setOnCheckedChangeListener { _, isChecked ->
                    ThemeManager.setMode(
                        this@PcView,
                        if (isChecked) ThemeManager.MODE_LIGHT else ThemeManager.MODE_DARK
                    )
                    sheet.dismiss()
                    recreate()
                }
            }

            val keys = arrayOf("universe", "pixel", "emerald", "amber", "graphite", "glass", "rain")
            val cardIds = intArrayOf(
                R.id.theme_card_universe, R.id.theme_card_pixel, R.id.theme_card_emerald,
                R.id.theme_card_amber, R.id.theme_card_graphite, R.id.theme_card_glass,
                R.id.theme_card_rain
            )
            val checkIds = intArrayOf(
                R.id.check_theme_universe, R.id.check_theme_pixel, R.id.check_theme_emerald,
                R.id.check_theme_amber, R.id.check_theme_graphite, R.id.check_theme_glass,
                R.id.check_theme_rain
            )

            for (i in keys.indices) {
                val themeKey = keys[i]
                val card = dialogView.findViewById<MaterialCardView>(cardIds[i])
                val check = dialogView.findViewById<ImageView>(checkIds[i])

                if (card != null) {
                    val isSelected = themeKey == currentKey
                    check?.visibility = if (isSelected) View.VISIBLE else View.GONE
                    if (isSelected) {
                        card.strokeWidth = (2 * resources.displayMetrics.density).toInt()
                        card.strokeColor = ThemeManager.accentColor(this)
                    }

                    card.setOnClickListener {
                        ThemeManager.setThemeKey(this@PcView, themeKey)
                        sheet.dismiss()
                        recreate()
                    }
                }
            }

            sheet.show()
        } catch (e: Exception) {
            LimeLog.severe("Error mostrando selector de tema: " + e.message)
            Toast.makeText(this, "Ajustes de tema disponibles", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleLiquidStartClick() {
        val active = activeComputer
        val details = active?.details
        if (details == null) {
            Toast.makeText(this, R.string.searching_pc, Toast.LENGTH_SHORT).show()
            return
        }

        if (details.state != ComputerDetails.State.ONLINE || details.activeAddress == null) {
            liquidStartButton?.setConnectionState(ConnectionVisualState.ERROR)
            doWakeOnLan(details)
            return
        }

        if (details.pairState != PairState.PAIRED) {
            doPair(details)
            return
        }

        liquidStartButton?.setConnectionState(ConnectionVisualState.CONNECTING)
        launchDirectDesktop(active)
    }

    private fun refreshHeroCard() {
        val heroName = heroPcName ?: return
        val heroStatus = heroStatusText ?: return
        val liquidButton = liquidStartButton ?: return
        val adapter = pcGridAdapter ?: return

        var candidate: ComputerObject? = null
        val store = lastSessionStore
        if (store != null && store.has()) {
            val lastUuid = store.getPcUuid()
            for (i in 0 until adapter.count) {
                val obj = adapter.getItem(i) as? ComputerObject
                if (obj?.details != null && lastUuid == obj.details.uuid) {
                    candidate = obj
                    break
                }
            }
        }

        if (candidate?.details == null || candidate.details.state != ComputerDetails.State.ONLINE) {
            for (i in 0 until adapter.count) {
                val obj = adapter.getItem(i) as? ComputerObject
                if (obj?.details != null && obj.details.state == ComputerDetails.State.ONLINE) {
                    candidate = obj
                    break
                }
            }
        }

        if (candidate == null && adapter.count > 0) {
            candidate = adapter.getItem(0) as? ComputerObject
        }

        activeComputer = candidate
        val active = activeComputer

        if (active != null && active.details != null) {
            heroName.text = active.details.name
            liquidButton.setTargetPcName(active.details.name)

            when (active.details.state) {
                ComputerDetails.State.ONLINE -> {
                    val paired = (active.details.pairState == PairState.PAIRED)
                    heroStatus.text = if (paired) "Disponible" else "Requiere emparejar"
                    heroStatus.setTextColor(Color.parseColor("#00C853"))
                    headerStatusDot?.setBackgroundResource(R.drawable.bg_green_dot)
                    liquidButton.setConnectionState(ConnectionVisualState.IDLE)
                    capsuleLatencyText?.text = "8 ms"
                    capsuleFpsText?.text = "60 FPS"
                }
                ComputerDetails.State.UNKNOWN -> {
                    heroStatus.text = "Actualizando..."
                    heroStatus.setTextColor(Color.parseColor("#78909C"))
                    liquidButton.setConnectionState(ConnectionVisualState.IDLE)
                    capsuleLatencyText?.text = "— ms"
                    capsuleFpsText?.text = "— FPS"
                }
                else -> {
                    heroStatus.text = "Sin conexión"
                    heroStatus.setTextColor(Color.parseColor("#EF5350"))
                    headerStatusDot?.setBackgroundResource(R.drawable.online_dot)
                    liquidButton.setConnectionState(ConnectionVisualState.DISABLED)
                    capsuleLatencyText?.text = "— ms"
                    capsuleFpsText?.text = "— FPS"
                }
            }
        } else {
            heroName.text = "SmartDisplay"
            heroStatus.text = "Buscando equipos..."
            heroStatus.setTextColor(Color.parseColor("#78909C"))
            liquidButton.setTargetPcName("PC")
            liquidButton.setConnectionState(ConnectionVisualState.DISABLED)
            capsuleLatencyText?.text = "— ms"
            capsuleFpsText?.text = "— FPS"
        }
    }

    private fun launchDirectDesktop(computer: ComputerObject?) {
        val details = computer?.details ?: return
        if (details.state != ComputerDetails.State.ONLINE || details.activeAddress == null) {
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show()
            liquidStartButton?.setConnectionState(ConnectionVisualState.ERROR)
            return
        }

        liquidStartButton?.setConnectionState(ConnectionVisualState.CONNECTING)

        val binder = managerBinder
        if (binder != null && details.pairState == PairState.PAIRED) {
            Toast.makeText(this, R.string.detail_connecting, Toast.LENGTH_SHORT).show()
            thread {
                try {
                    val http = NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(details),
                        details.httpsPort,
                        binder.uniqueId,
                        details.serverCert,
                        PlatformBinding.getCryptoProvider(this@PcView)
                    )
                    val apps = http.getAppList()
                    var target: NvApp? = null
                    if (!apps.isNullOrEmpty()) {
                        for (a in apps) {
                            val n = a.appName?.lowercase() ?: ""
                            if (n.contains("desktop") || n.contains("escritorio") || n.contains("mstsc")) {
                                target = a
                                break
                            }
                        }
                        if (target == null) target = apps[0]
                    }
                    val finalTarget = target
                    runOnUiThread {
                        liquidStartButton?.setConnectionState(ConnectionVisualState.CONNECTED)
                        if (finalTarget != null) {
                            ServerHelper.doStart(this@PcView, finalTarget, details, binder)
                            overridePendingTransition(R.anim.activity_fade_enter, R.anim.fade_out)
                        } else {
                            launchViaTrampoline(computer)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        liquidStartButton?.setConnectionState(ConnectionVisualState.IDLE)
                        launchViaTrampoline(computer)
                    }
                }
            }
        } else {
            launchViaTrampoline(computer)
        }
    }

    private fun launchViaTrampoline(computer: ComputerObject) {
        val i = Intent(this, ShortcutTrampoline::class.java)
        i.putExtra(AppView.NAME_EXTRA, computer.details.name)
        i.putExtra(AppView.UUID_EXTRA, computer.details.uuid)
        i.putExtra("AUTO_CONNECT", true)
        i.action = Intent.ACTION_DEFAULT
        startActivity(i)
    }

    override fun onBackPressed() {
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    private fun updateNetworkStatus() {
        if (headerNetworkText == null) {
            return
        }

        var label = getString(R.string.net_offline)
        var online = false
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val net = cm.activeNetwork
                    val caps = if (net != null) cm.getNetworkCapabilities(net) else null
                    if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        online = true
                        label = when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getString(R.string.net_wifi)
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> getString(R.string.net_ethernet)
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.net_cellular)
                            else -> getString(R.string.net_online)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val info = cm.activeNetworkInfo
                    if (info != null && info.isConnected) {
                        online = true
                        @Suppress("DEPRECATION")
                        label = when (info.type) {
                            ConnectivityManager.TYPE_WIFI -> getString(R.string.net_wifi)
                            ConnectivityManager.TYPE_ETHERNET -> getString(R.string.net_ethernet)
                            else -> getString(R.string.net_cellular)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            label = getString(R.string.net_offline)
        }

        val finalLabel = label
        val finalOnline = online
        runOnUiThread {
            headerNetworkText?.text = finalLabel
            val dot = headerStatusDot
            if (dot != null) {
                if (finalOnline) {
                    if (dot.animation == null) {
                        dot.startAnimation(AnimationUtils.loadAnimation(this@PcView, R.anim.pulse))
                    }
                } else {
                    dot.clearAnimation()
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) {
            return
        }
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateNetworkStatus()
                }

                override fun onLost(network: Network) {
                    updateNetworkStatus()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    updateNetworkStatus()
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(callback)
        } catch (ignored: Exception) {
        } finally {
            networkCallback = null
        }
    }

    private fun showLogsDialog() {
        if (isFinishing || isDestroyed) return
        try {
            val view = layoutInflater.inflate(R.layout.dialog_logs, null)
            val sheet = BottomSheetDialog(this)
            sheet.setContentView(view)

            val txtLogs = view.findViewById<TextView>(R.id.txt_log_content)
            val btnCopy = view.findViewById<MaterialButton>(R.id.btn_copy_logs)
            val btnClose = view.findViewById<MaterialButton>(R.id.btn_close_logs)

            val sb = StringBuilder()
            sb.append("• Dispositivo: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            sb.append("• Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            sb.append("• Arquitectura ABI: ").append(if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "?").append('\n')
            try {
                sb.append("• Versión SmartDisplay: v")
                    .append(packageManager.getPackageInfo(packageName, 0).versionName).append('\n')
            } catch (ignored: Exception) {
            }
            val glPrefs = GlPreferences.readPreferences(this)
            if (!glPrefs.glRenderer.isNullOrEmpty()) {
                sb.append("• GPU Renderer: ").append(glPrefs.glRenderer).append('\n')
            }
            sb.append("• Equipos PC en caché: ").append(pcGridAdapter?.count ?: 0).append('\n')
            try {
                val mi = ActivityManager.MemoryInfo()
                (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(mi)
                val availMb = (mi.availMem / (1024 * 1024))
                val totalMb = (mi.totalMem / (1024 * 1024))
                sb.append("• Memoria RAM: ").append(availMb).append(" MB libres de ").append(totalMb).append(" MB\n")
            } catch (ignored: Exception) {}

            val fullText = sb.toString()
            txtLogs?.text = fullText

            btnCopy?.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                try {
                    val cb = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText("SmartDisplay Logs", fullText))
                    Toast.makeText(this, "Registros copiados al portapapeles", Toast.LENGTH_SHORT).show()
                    btnCopy.text = "¡Copiado!"
                    btnCopy.postDelayed({ btnCopy.text = "Copiar Registros" }, 1500L)
                } catch (e: Exception) {
                    Toast.makeText(this, "No se pudo copiar", Toast.LENGTH_SHORT).show()
                }
            }

            btnClose?.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                sheet.dismiss()
            }

            sheet.show()
        } catch (e: Exception) {
            Log.e("SmartDisplayAI", "Error mostrando logs", e)
        }
    }

    private fun refreshLastSessionCard() {
        val card = lastSessionCard ?: return
        val store = lastSessionStore ?: return
        if (!store.has()) {
            card.visibility = View.GONE
            return
        }

        val pcName = store.getPcName()
        val appName = store.appName

        lastSessionTitle?.text = if (pcName.isNotEmpty()) pcName else getString(R.string.app_label)
        if (lastSessionSubtitle != null) {
            if (appName.isNotEmpty()) {
                lastSessionSubtitle?.text = appName
                lastSessionSubtitle?.visibility = View.VISIBLE
            } else {
                lastSessionSubtitle?.visibility = View.GONE
            }
        }
        card.visibility = View.VISIBLE
    }

    private fun onLastSessionClicked() {
        val store = lastSessionStore
        val binder = managerBinder
        if (store == null || !store.has() || binder == null) {
            return
        }
        val target = findComputerByUuid(store.getPcUuid())
        if (target == null || target.state != ComputerDetails.State.ONLINE || target.pairState != PairState.PAIRED) {
            Toast.makeText(this, getString(R.string.last_session_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        val app = NvApp(store.appName, store.appId, store.getAppHdr())
        ServerHelper.doStart(this, app, target, binder)
    }

    private fun findComputerByUuid(uuid: String?): ComputerDetails? {
        if (uuid.isNullOrEmpty()) {
            return null
        }
        val adapter = pcGridAdapter ?: return null
        for (i in 0 until adapter.count) {
            val obj = adapter.getItem(i) as? ComputerObject
            if (obj?.details != null && uuid == obj.details.uuid) {
                return obj.details
            }
        }
        return null
    }

    private fun showAboutDialog() {
        if (isFinishing || isDestroyed) return
        try {
            val view = layoutInflater.inflate(R.layout.dialog_about, null)
            val sheet = BottomSheetDialog(this)
            sheet.setContentView(view)

            val txtVer = view.findViewById<TextView>(R.id.txt_about_version)
            val btnClose = view.findViewById<MaterialButton>(R.id.btn_close_about)

            try {
                val ver = packageManager.getPackageInfo(packageName, 0).versionName
                txtVer?.text = "Versión $ver (2026 Edition)"
            } catch (ignored: Exception) {
                txtVer?.text = "Versión 2.6.0"
            }

            btnClose?.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                sheet.dismiss()
            }

            sheet.show()
        } catch (e: Exception) {
            Log.e("SmartDisplayAI", "Error mostrando acerca de", e)
        }
    }

    private fun showDiagnosticsPanel() {
        if (isFinishing || isDestroyed) {
            return
        }
        try {
            val diagView = layoutInflater.inflate(R.layout.dialog_diagnostics, null)
            val sheet = BottomSheetDialog(this)
            sheet.setContentView(diagView)
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.behavior.skipCollapsed = true

            val txtModel = diagView.findViewById<TextView>(R.id.txt_device_model)
            val txtAndroid = diagView.findViewById<TextView>(R.id.txt_android_ver)
            val txtRam = diagView.findViewById<TextView>(R.id.txt_ram)
            val txtRes = diagView.findViewById<TextView>(R.id.txt_resolution)
            val txtVersion = diagView.findViewById<TextView>(R.id.txt_app_version)
            val txtTheme = diagView.findViewById<TextView>(R.id.txt_active_theme)
            val txtH264 = diagView.findViewById<TextView>(R.id.txt_h264)
            val txtH265 = diagView.findViewById<TextView>(R.id.txt_h265)
            val txtAv1 = diagView.findViewById<TextView>(R.id.txt_av1)
            val txtMaxRes = diagView.findViewById<TextView>(R.id.txt_max_res)
            val txtNetType = diagView.findViewById<TextView>(R.id.txt_net_type)
            val txtLocalIp = diagView.findViewById<TextView>(R.id.txt_local_ip)
            val txtLinkSpeed = diagView.findViewById<TextView>(R.id.txt_link_speed)
            val txtConn = diagView.findViewById<TextView>(R.id.txt_connectivity)
            val progConn = diagView.findViewById<CircularProgressIndicator>(R.id.progress_connectivity)

            txtModel?.text = "${Build.MANUFACTURER} ${Build.MODEL}"
            txtAndroid?.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

            try {
                val mi = ActivityManager.MemoryInfo()
                val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
                am?.getMemoryInfo(mi)
                val availMb = mi.availMem / (1024 * 1024)
                val totalMb = mi.totalMem / (1024 * 1024)
                txtRam?.text = "$availMb MB libres / $totalMb MB total"
            } catch (e: Exception) {
                txtRam?.text = "N/D"
            }

            try {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(dm)
                txtRes?.text = "${dm.widthPixels} × ${dm.heightPixels} · ${dm.densityDpi} dpi"
            } catch (e: Exception) {
                txtRes?.text = "N/D"
            }

            try {
                val ver = packageManager.getPackageInfo(packageName, 0).versionName
                txtVersion?.text = "v$ver"
            } catch (e: Exception) {
                txtVersion?.text = "N/D"
            }

            try {
                val themeKey = ThemeManager.getThemeKey(this)
                val isLight = ThemeManager.isLight(this)
                val mode = if (isLight) "Claro" else "Oscuro"
                val themeNames = mapOf(
                    "universe" to "Universo",
                    "pixel" to "Pixel",
                    "emerald" to "Esmeralda",
                    "amber" to "Ámbar",
                    "graphite" to "Grafito",
                    "glass" to "Cristal",
                    "rain" to "Tormenta"
                )
                val name = themeNames[themeKey] ?: themeKey
                txtTheme?.text = "$name · $mode"
            } catch (e: Exception) {
                txtTheme?.text = "N/D"
            }

            try {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                val codecs = list.codecInfos
                var h264hw = false
                var h265hw = false
                var av1hw = false
                var maxW = 0
                var maxH = 0
                for (info in codecs) {
                    if (info.isEncoder) continue
                    for (mime in info.supportedTypes) {
                        var isHw = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated
                        if (!isHw) {
                            val lname = info.name.lowercase()
                            isHw = !lname.contains("google") && !lname.contains("sw") && !lname.contains("omx.google")
                        }
                        if (mime.equals("video/avc", ignoreCase = true) && isHw) {
                            h264hw = true
                            try {
                                val caps = info.getCapabilitiesForType(mime)
                                val vc = caps.videoCapabilities
                                val upper = vc.supportedWidths.upper
                                if (upper != null && upper > maxW) {
                                    maxW = upper
                                    maxH = vc.supportedHeights.upper ?: 0
                                }
                            } catch (ignored: Exception) {
                            }
                        }
                        if (mime.equals("video/hevc", ignoreCase = true) && isHw) h265hw = true
                        if (mime.equals("video/av01", ignoreCase = true) && isHw) av1hw = true
                    }
                }
                txtH264?.text = if (h264hw) "✓ Disponible" else "✗ Solo software"
                txtH265?.text = if (h265hw) "✓ Disponible" else "✗ No soportado"
                txtAv1?.text = if (av1hw) "✓ Disponible" else "✗ No soportado"
                txtMaxRes?.text = if (maxW > 0) "$maxW × $maxH" else "N/D"
            } catch (e: Exception) {
                txtH264?.text = "Error"
                txtH265?.text = "Error"
            }

            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    val activeNet = cm.activeNetwork
                    if (activeNet != null) {
                        val caps = cm.getNetworkCapabilities(activeNet)
                        if (caps != null) {
                            val netType = when {
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Datos Móviles"
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN / Tailscale"
                                else -> "Desconocida"
                            }
                            txtNetType?.text = netType
                        }
                    }
                }
            } catch (e: Exception) {
                txtNetType?.text = "N/D"
            }

            try {
                val ifs = NetworkInterface.getNetworkInterfaces()
                val ipSb = StringBuilder()
                while (ifs.hasMoreElements()) {
                    val ni = ifs.nextElement()
                    if (ni.isLoopback || !ni.isUp) continue
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address) {
                            if (ipSb.isNotEmpty()) ipSb.append(" / ")
                            ipSb.append(addr.hostAddress)
                        }
                    }
                }
                txtLocalIp?.text = if (ipSb.isNotEmpty()) ipSb.toString() else "N/D"
            } catch (e: Exception) {
                txtLocalIp?.text = "N/D"
            }

            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    @Suppress("DEPRECATION")
                    val wi = wm.connectionInfo
                    if (wi != null && wi.linkSpeed > 0) {
                        txtLinkSpeed?.text = "${wi.linkSpeed} Mbps"
                    } else {
                        txtLinkSpeed?.text = "N/D"
                    }
                } else {
                    txtLinkSpeed?.text = "N/D"
                }
            } catch (e: Exception) {
                txtLinkSpeed?.text = "N/D"
            }

            progConn?.isIndeterminate = true
            progConn?.visibility = View.VISIBLE
            txtConn?.visibility = View.GONE

            thread {
                val ret = MoonBridge.testClientConnectivity(
                    ServerHelper.CONNECTION_TEST_SERVER,
                    443,
                    MoonBridge.ML_PORT_FLAG_ALL
                )
                val result = when (ret) {
                    0 -> "✓ Conectividad OK"
                    MoonBridge.ML_TEST_RESULT_INCONCLUSIVE -> "ℹ Red local OK (ext. N/D)"
                    else -> "⚠ Puertos bloqueados"
                }
                runOnUiThread {
                    progConn?.visibility = View.GONE
                    txtConn?.visibility = View.VISIBLE
                    txtConn?.text = result
                }
            }

            val btnRun = diagView.findViewById<MaterialButton>(R.id.btn_run_diag)
            btnRun?.setOnClickListener {
                txtConn?.visibility = View.GONE
                progConn?.visibility = View.VISIBLE
                thread {
                    val ret2 = MoonBridge.testClientConnectivity(
                        ServerHelper.CONNECTION_TEST_SERVER,
                        443,
                        MoonBridge.ML_PORT_FLAG_ALL
                    )
                    val r = when (ret2) {
                        0 -> "✓ Conectividad OK"
                        MoonBridge.ML_TEST_RESULT_INCONCLUSIVE -> "ℹ Red local OK (ext. N/D)"
                        else -> "⚠ Puertos bloqueados"
                    }
                    runOnUiThread {
                        progConn?.visibility = View.GONE
                        txtConn?.visibility = View.VISIBLE
                        txtConn?.text = r
                    }
                }
            }

            sheet.show()
        } catch (ex: Exception) {
            Log.e("SmartDisplayAI", "showDiagnosticsPanel error", ex)
        }
    }

    class ComputerObject(@JvmField var details: ComputerDetails) {
        @JvmField var activeAppName: String? = null

        override fun toString(): String {
            return details.name
        }
    }

    companion object {
        private const val PAIR_ID = 2
        private const val UNPAIR_ID = 3
        private const val WOL_ID = 4
        private const val DELETE_ID = 5
        private const val RESUME_ID = 6
        private const val QUIT_ID = 7
        private const val VIEW_DETAILS_ID = 8
        private const val FULL_APP_LIST_ID = 9
        private const val TEST_NETWORK_ID = 10
    }
}
