package com.limelight

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.PictureInPictureParams
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.limelight.binding.PlatformBinding
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.binding.input.capture.InputCaptureManager
import com.limelight.binding.input.capture.InputCaptureProvider
import com.limelight.binding.input.driver.UsbDriverService
import com.limelight.binding.input.evdev.EvdevListener
import com.limelight.binding.input.virtual_controller.VirtualController
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.qos.AdaptiveQualityControllerV2
import com.limelight.qos.DesktopContentClassifier
import com.limelight.qos.StreamTimingController
import com.limelight.smartdisplay.recovery.AutoReconnectManager
import com.limelight.smartdisplay.recovery.SessionRecoveryManager
import com.limelight.smartdisplay.recovery.WakeOnLanManager
import com.limelight.ui.AdaptiveCursorView
import com.limelight.ui.AudioHudController
import com.limelight.ui.GameGestures
import com.limelight.ui.LogicalKeyboardOverlay
import com.limelight.ui.OverlayFabController
import com.limelight.ui.StreamView
import com.limelight.ui.StreamViewTransformController
import com.limelight.ui.WindowControlsController
import com.limelight.utils.Dialog
import com.limelight.utils.ShortcutHelper
import com.limelight.utils.SpinnerDialog
import com.limelight.utils.UiHelper
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

class Game : AppCompatActivity(), SurfaceHolder.Callback,
    View.OnGenericMotionListener, View.OnTouchListener, NvConnectionListener, EvdevListener,
    View.OnSystemUiVisibilityChangeListener, GameGestures, StreamView.InputCallbacks,
    UsbDriverService.UsbDriverStateListener, View.OnKeyListener {

    private var audioRenderer: AndroidAudioRenderer? = null
    private var lastCursorPosMs: Long = 0
    private var autoEnterPip = false
    private var isTransitioningToPip = false
    private var smartTaskbar: com.limelight.ui.SmartTaskbarController? = null
    private var smartDisplaySessionManager: com.limelight.smartdisplay.session.SmartDisplaySessionManager? = null
    private var streamMotionInputHandler: com.limelight.smartdisplay.input.StreamMotionInputHandler? = null
    private var streamCrashRecoveryManager: com.limelight.smartdisplay.recovery.StreamCrashRecoveryManager? = null

    private var controllerHandler: ControllerHandler? = null
    private var keyboardTranslator: KeyboardTranslator? = null
    private var virtualController: VirtualController? = null

    private lateinit var prefConfig: PreferenceConfiguration

    private var streamConfig: StreamConfiguration? = null
    private var connHost: String? = null
    private var connPort: Int = 0
    private var connHttpsPort: Int = 0
    private var connUniqueId: String? = null
    private var connServerCert: X509Certificate? = null

    private var conn: NvConnection? = null
    private var spinner: SpinnerDialog? = null
    private var displayedFailureDialog = false
    private var connecting = false
    private var connected = false
    private var surfaceCreated = false
    private var attemptedConnection = false
    private var suppressPipRefCount = 0
    private var pcName: String? = null
    private var appName: String? = null
    private var app: NvApp? = null
    private var desiredRefreshRate = 0f

    private lateinit var inputCaptureProvider: InputCaptureProvider
    private var grabbedInput = true
    private var cursorVisible = false
    private lateinit var streamView: StreamView
    private var isScreenOffMode = false
    private var streamKeyboardHandler: com.limelight.smartdisplay.input.StreamKeyboardHandler? = null
    private var sessionDisplayManager: com.limelight.smartdisplay.display.SessionDisplayManager? = null
    private var sessionRecoveryUiManager: com.limelight.smartdisplay.recovery.SessionRecoveryUiManager? = null

    private var isHidingOverlays = false
    private lateinit var notificationOverlayView: TextView
    private var requestedNotificationOverlayVisibility = View.GONE
    private var performanceHudController: com.limelight.ui.PerformanceHudController? = null

    private var overlayFabController: OverlayFabController? = null
    private var logicalKeyboard: LogicalKeyboardOverlay? = null
    var streamTransformCtrl: StreamViewTransformController? = null

    private var adaptiveCursor: AdaptiveCursorView? = null
    private var smartCursorEngine: com.limelight.ui.SmartCursorEngine? = null
    private var audioHudController: AudioHudController? = null
    private var windowControlsCtrl: WindowControlsController? = null
    private var mouseModeCircle: com.limelight.ui.MouseModeCircle? = null

    private var sessionPowerManager: com.limelight.smartdisplay.power.SessionPowerManager? = null
    private var fileBrowser: com.limelight.ui.FileBrowserController? = null
    private var portraitHybridLayout: View? = null
    private var portraitHybridCtrl: com.limelight.ui.PortraitHybridController? = null

    private var autoReconnectManager: AutoReconnectManager? = null
    private var sessionRecoveryManager: SessionRecoveryManager? = null
    private var wakeOnLanManager: WakeOnLanManager? = null
    private var reconnectOverlayView: View? = null
    private var reconnectStatusText: TextView? = null

    private var adaptiveQualityController: AdaptiveQualityControllerV2? = null
    private var desktopContentClassifier: DesktopContentClassifier? = null
    private var streamTimingController: StreamTimingController? = null
    private var adaptiveReconnecting = false

    private lateinit var decoderRenderer: MediaCodecDecoderRenderer
    private var connectedToUsbDriverService = false
    private var usbDriverBinder: UsbDriverService.UsbDriverBinder? = null

    private val usbDriverServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            val binder = iBinder as? UsbDriverService.UsbDriverBinder
            usbDriverBinder = binder
            binder?.setListener(controllerHandler)
            binder?.setStateListener(this@Game)
            binder?.start()
            connectedToUsbDriverService = true
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            usbDriverBinder = null
            connectedToUsbDriverService = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        com.limelight.smartdisplay.AppContainer.init(this)
        UiHelper.setLocale(this)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.decorView.setOnSystemUiVisibilityChangeListener(this)
        volumeControlStream = AudioManager.STREAM_MUSIC

        setContentView(R.layout.activity_game)

        findViewById<View>(R.id.btnBackToDesktop)?.setOnClickListener { finish() }

        spinner = SpinnerDialog.displayDialog(
            this, resources.getString(R.string.conn_establishing_title),
            resources.getString(R.string.conn_establishing_msg), true
        )

        prefConfig = PreferenceConfiguration.readPreferences(this)
        streamCrashRecoveryManager = com.limelight.smartdisplay.AppContainer.createStreamCrashRecoveryManager(this)

        val initialConnMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val adaptiveEnabled = !initialConnMgr.isActiveNetworkMetered

        adaptiveQualityController = AdaptiveQualityControllerV2(
            prefConfig.width, prefConfig.height, prefConfig.bitrate, adaptiveEnabled
        )
        adaptiveQualityController?.listener = object : AdaptiveQualityControllerV2.OnQualityAdjustmentListener {
            override fun onQualityAdjustmentRequested(newLevel: Int, targetWidth: Int, targetHeight: Int, targetBitrate: Int, reason: String) {
                triggerAdaptiveReconnect()
            }
        }

        desktopContentClassifier = DesktopContentClassifier()
        streamTimingController = StreamTimingController()
        desktopContentClassifier?.addListener(object : DesktopContentClassifier.OnProfileChangeListener {
            override fun onProfileChanged(newProfile: DesktopContentClassifier.ContentProfile, oldProfile: DesktopContentClassifier.ContentProfile) {
                streamTimingController?.updateContentProfile(newProfile)
            }
        })

        if (prefConfig.stretchVideo || shouldIgnoreInsetsForResolution(prefConfig.width, prefConfig.height)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        streamView = findViewById(R.id.surfaceView)
        streamView.setOnGenericMotionListener(this)
        streamView.setOnKeyListener(this)
        streamView.inputCallbacks = this

        sessionDisplayManager = com.limelight.smartdisplay.AppContainer.createSessionDisplayManager(this, streamView, prefConfig)
        sessionDisplayManager?.setPreferredOrientationForCurrentDisplay()

        val backgroundTouchView = findViewById<View>(R.id.backgroundTouchView)
        backgroundTouchView.setOnTouchListener(this)

        val streamWrapper = findViewById<View>(R.id.streamTransformWrapper)
        if (streamWrapper != null) {
            streamTransformCtrl = StreamViewTransformController(this, streamWrapper, backgroundTouchView)
            val zoomIndicator = findViewById<com.limelight.ui.viewport.ZoomIndicatorView>(R.id.zoomIndicator)
            if (zoomIndicator != null) {
                streamTransformCtrl?.zoomIndicator = zoomIndicator
            }
            streamTransformCtrl?.engine?.addListener(object : com.limelight.ui.viewport.DesktopViewportEngine.OnViewportChangedListener {
                override fun onScaleChanged(scale: Float, mode: com.limelight.ui.viewport.DesktopViewportEngine.ViewportMode) {
                    desktopContentClassifier?.notifyZoomScale(scale)
                    adaptiveQualityController?.notifyZoomScale(scale)
                }
            })
        }

        audioHudController = AudioHudController(this, streamView.parent as android.view.ViewGroup)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            streamView.requestUnbufferedDispatch(
                InputDevice.SOURCE_CLASS_BUTTON or
                        InputDevice.SOURCE_CLASS_JOYSTICK or
                        InputDevice.SOURCE_CLASS_POINTER or
                        InputDevice.SOURCE_CLASS_POSITION or
                        InputDevice.SOURCE_CLASS_TRACKBALL
            )
            backgroundTouchView.requestUnbufferedDispatch(
                InputDevice.SOURCE_CLASS_BUTTON or
                        InputDevice.SOURCE_CLASS_JOYSTICK or
                        InputDevice.SOURCE_CLASS_POINTER or
                        InputDevice.SOURCE_CLASS_POSITION or
                        InputDevice.SOURCE_CLASS_TRACKBALL
            )
        }

        notificationOverlayView = findViewById(R.id.notificationOverlay)
        performanceHudController = com.limelight.ui.PerformanceHudController(this, findViewById(R.id.performanceOverlay))
        performanceHudController?.setup(prefConfig.enablePerfOverlay)

        val fabMenuView = findViewById<View>(R.id.overlayFabMenu)
        val keyboardPanelView = findViewById<View>(R.id.overlayKeyboard)

        if (fabMenuView != null && keyboardPanelView != null) {
            logicalKeyboard = LogicalKeyboardOverlay(keyboardPanelView, this)
            overlayFabController = OverlayFabController(fabMenuView)

            logicalKeyboard?.setOnVisibilityChangedListener { visible, topY ->
                overlayFabController?.onKeyboardVisibilityChanged(visible, topY)
                smartTaskbar?.onKeyboardVisibilityChanged(visible, topY)
                mouseModeCircle?.adjustForKeyboard(if (visible) topY else 0)
            }

            // Píldora Dynamic Island: Modo Mover Pantalla
            val bannerMoveMode = findViewById<View>(R.id.bannerMoveMode)
            val btnExitMoveMode = findViewById<View>(R.id.btnExitMoveMode)

            fun updateMoveModeBanner(active: Boolean) {
                if (active) {
                    bannerMoveMode?.visibility = View.VISIBLE
                    bannerMoveMode?.alpha = 0f
                    bannerMoveMode?.scaleX = 0.85f
                    bannerMoveMode?.scaleY = 0.85f
                    bannerMoveMode?.animate()
                        ?.alpha(1f)
                        ?.scaleX(1f)
                        ?.scaleY(1f)
                        ?.setDuration(220)
                        ?.setInterpolator(android.view.animation.OvershootInterpolator(1.3f))
                        ?.start()
                    bannerMoveMode?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                } else {
                    bannerMoveMode?.animate()
                        ?.alpha(0f)
                        ?.scaleX(0.85f)
                        ?.scaleY(0.85f)
                        ?.setDuration(180)
                        ?.withEndAction { bannerMoveMode?.visibility = View.GONE }
                        ?.start()
                }
                windowControlsCtrl?.updateMoveButtonState(active)
            }

            bannerMoveMode?.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                streamTransformCtrl?.setMoveMode(false)
                updateMoveModeBanner(false)
            }
            btnExitMoveMode?.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                streamTransformCtrl?.setMoveMode(false)
                updateMoveModeBanner(false)
            }

            streamTransformCtrl?.onMoveModeChangedListener = { active ->
                updateMoveModeBanner(active)
            }

            overlayFabController?.setOnActionListener(object : OverlayFabController.OnActionListener {
                override fun onKeyboardToggle() {
                    if (logicalKeyboard?.isVisible() == true) {
                        logicalKeyboard?.hide()
                    } else {
                        logicalKeyboard?.show()
                    }
                }

                override fun onMoveToggle() {
                    val nowActive = streamTransformCtrl?.toggleMoveMode() ?: false
                    updateMoveModeBanner(nowActive)
                    Toast.makeText(this@Game, if (nowActive) R.string.hub_move_mode_on else R.string.hub_move_mode_off, Toast.LENGTH_SHORT).show()
                }

                override fun onItemAction(action: Int) {
                    when (action) {
                        OverlayFabController.A_SCREEN -> togglePortraitHybridMode()
                        OverlayFabController.A_MOUSE -> toggleMouseModeUnified()
                        OverlayFabController.A_FILES -> openFileBrowser()
                        OverlayFabController.A_VOICE -> toggleVoiceWithAudioFeedback()
                        OverlayFabController.A_PIP -> sessionDisplayManager?.enterPipMode(appName, pcName)
                        OverlayFabController.A_EXIT -> finish()
                        OverlayFabController.A_ZOOM_IN -> streamTransformCtrl?.zoomIn()
                        OverlayFabController.A_ZOOM_OUT -> streamTransformCtrl?.zoomOut()
                        OverlayFabController.A_ZOOM_FIT -> streamTransformCtrl?.resetViewport()
                        OverlayFabController.A_CENTER -> streamTransformCtrl?.centerViewport()
                        OverlayFabController.A_MOVE_MODE -> onMoveToggle()
                    }
                }

                override fun onMouseModeToggle() {
                    toggleMouseModeUnified()
                }
            })
        }

        mouseModeCircle = findViewById(R.id.mouseModeCircle)

        val taskbarView = findViewById<View>(R.id.overlaySmartTaskbar)
        if (taskbarView != null) {
            smartTaskbar = com.limelight.ui.SmartTaskbarController(taskbarView,
                object : com.limelight.ui.SmartTaskbarController.Actions {
                    override fun requestWindows() { sendBusCommand("get_windows", null) }
                    override fun focus(hwnd: Int) { sendBusCommand("focus_window", hwnd) }
                    override fun closeWindow(hwnd: Int) { sendBusCommand("close_window", hwnd) }
                    override fun minimize(hwnd: Int) { sendBusCommand("minimize_window", hwnd) }
                    override fun snap(hwnd: Int, zone: String) { smartDisplaySessionManager?.snapWindow(hwnd, zone) }
                    override fun startDrag(hwnd: Int, winX: Float, winY: Float, winW: Float, winH: Float) {
                        smartDisplaySessionManager?.startWindowDrag(hwnd, winX, winY, winW, winH)
                    }
                })
        }

        val overlayAccent = com.limelight.utils.ThemeManager.accentColor(this)
        com.limelight.ui.TaskbarEdgeTabHelper.setup(
            findViewById(R.id.overlayBtnTaskbar),
            findViewById(R.id.taskbarEdgeTab),
            smartTaskbar,
            overlayAccent
        )

        val fileBrowserView = findViewById<View>(R.id.fileBrowserOverlay)
        if (fileBrowserView != null) {
            fileBrowser = com.limelight.ui.FileBrowserController(fileBrowserView, this) { files ->
                sendPickedFiles(files)
            }
        }

        adaptiveCursor = findViewById(R.id.adaptiveCursorView)
        if (adaptiveCursor != null) {
            smartCursorEngine = com.limelight.ui.SmartCursorEngine(adaptiveCursor!!)
        }

        val windowControlsView = findViewById<View>(R.id.overlayWindowControls)
        if (windowControlsView != null) {
            windowControlsCtrl = WindowControlsController(windowControlsView, this)
            windowControlsCtrl?.setStreamTransformController(streamTransformCtrl)
        }

        portraitHybridLayout = findViewById(R.id.portraitHybridLayout)
        if (portraitHybridLayout != null) {
            portraitHybridCtrl = com.limelight.ui.PortraitHybridController(portraitHybridLayout!!, this)
            portraitHybridCtrl?.setKeyboard(logicalKeyboard)
            portraitHybridCtrl?.setCursorView(adaptiveCursor)
            portraitHybridLayout?.findViewById<View>(R.id.hybridStreamContainer)?.setOnTouchListener(this)
        }

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this)

        streamMotionInputHandler = com.limelight.smartdisplay.AppContainer.createStreamMotionInputHandler(
            prefConfig, inputCaptureProvider
        ) {
            toggleKeyboard()
        }
        streamMotionInputHandler?.streamView = streamView
        streamMotionInputHandler?.adaptiveCursor = adaptiveCursor
        streamMotionInputHandler?.windowControlsCtrl = windowControlsCtrl
        streamMotionInputHandler?.desktopContentClassifier = desktopContentClassifier
        streamMotionInputHandler?.streamTransformCtrl = streamTransformCtrl

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            streamView.setOnCapturedPointerListener { view, motionEvent ->
                streamMotionInputHandler?.handleMotionEvent(view, motionEvent) ?: false
            }
        }

        if (initialConnMgr.isActiveNetworkMetered) {
            displayTransientMessage(resources.getString(R.string.conn_metered))
        }

        sessionPowerManager = com.limelight.smartdisplay.AppContainer.createSessionPowerManager(this)
        sessionPowerManager?.acquireLocks()

        appName = intent.getStringExtra(EXTRA_APP_NAME)
        pcName = intent.getStringExtra(EXTRA_PC_NAME) ?: ""

        reconnectOverlayView = findViewById(R.id.reconnectOverlay)
        reconnectStatusText = findViewById(R.id.reconnectStatusText)
        reconnectOverlayView?.visibility = View.GONE
        
        sessionRecoveryUiManager = com.limelight.smartdisplay.AppContainer.createSessionRecoveryUiManager(
            this, streamView, reconnectOverlayView, reconnectStatusText
        )

        sessionRecoveryManager = SessionRecoveryManager(this)
        autoReconnectManager = AutoReconnectManager(this, object : AutoReconnectManager.ReconnectCallback {
            override fun doReconnect() {
                runOnUiThread {
                    if (streamView.holder.surface.isValid) {
                        com.limelight.LimeLog.info("AutoReconnect: iniciando nueva sesiÃ³n limpia de conexiÃ³n")
                        startNewConnectionSession()
                    }
                }
            }
            override fun showReconnectingUI(attempt: Int, maxAttempts: Int) { sessionRecoveryUiManager?.showReconnectingUI(attempt, maxAttempts) }
            override fun hideReconnectingUI() { sessionRecoveryUiManager?.hideReconnectingUI() }
            override fun onReconnectFailed() {
                sessionRecoveryUiManager?.onReconnectFailed {
                    displayedFailureDialog = false
                }
            }
            override fun showWakeOnLanUI(elapsedSeconds: Int, maxSeconds: Int) { sessionRecoveryUiManager?.showWakeOnLanUI(elapsedSeconds, maxSeconds) }
            override fun hideWakeOnLanUI() { sessionRecoveryUiManager?.hideWakeOnLanUI() }
        })
        autoReconnectManager?.start()

        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT)
        val httpsPort = intent.getIntExtra(EXTRA_HTTPS_PORT, 0)
        val appId = intent.getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID)
        val uniqueId = intent.getStringExtra(EXTRA_UNIQUEID) ?: ""
        val appSupportsHdr = intent.getBooleanExtra(EXTRA_APP_HDR, false)
        val derCertData = intent.getByteArrayExtra(EXTRA_SERVER_CERT)

        val macAddress = intent.getStringExtra(EXTRA_MAC) ?: ""
        wakeOnLanManager = WakeOnLanManager(host, httpsPort, macAddress)
        autoReconnectManager?.setWakeOnLan(wakeOnLanManager)

        if (!host.isNullOrEmpty()) {
            smartDisplaySessionManager = com.limelight.smartdisplay.AppContainer.createSmartDisplaySessionManager(
                this, streamView, prefConfig, findViewById(R.id.streamTransformWrapper),
                adaptiveCursor, smartCursorEngine, logicalKeyboard, portraitHybridCtrl,
                smartTaskbar, fileBrowser, streamMotionInputHandler
            )
            smartDisplaySessionManager?.start(host)
            updateLayoutForOrientation(resources.configuration.orientation)
        }

        app = NvApp(appName ?: "app", appId, appSupportsHdr)

        var serverCert: X509Certificate? = null
        try {
            if (derCertData != null) {
                serverCert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
            }
        } catch (e: CertificateException) {
            e.printStackTrace()
        }

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish()
            return
        }

        val glPrefs = GlPreferences.readPreferences(this)
        MediaCodecHelper.initialize(this, glPrefs.glRenderer)

        var willStreamHdr = false
        if (prefConfig.enableHdr) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val display = windowManager.defaultDisplay
                val hdrCaps = display.hdrCapabilities
                if (hdrCaps != null) {
                    for (hdrType in hdrCaps.supportedHdrTypes) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            willStreamHdr = true
                            break
                        }
                    }
                }
                if (!willStreamHdr) {
                    Toast.makeText(this, "La pantalla no soporta HDR10", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "HDR requiere Android 7.0 o posterior", Toast.LENGTH_LONG).show()
            }
        }

        decoderRenderer = MediaCodecDecoderRenderer(
            this, prefConfig, streamCrashRecoveryManager!!,
            streamCrashRecoveryManager?.getCrashCount() ?: 0,
            initialConnMgr.isActiveNetworkMetered, willStreamHdr, glPrefs.glRenderer ?: "", performanceHudController!!
        )
        decoderRenderer.setStreamTimingController(streamTimingController)

        if (prefConfig.enableHdr && !decoderRenderer.isHevcMain10Hdr10Supported() && !decoderRenderer.isAv1Main10Supported()) {
            willStreamHdr = false
            Toast.makeText(this, "El decodificador no soporta el perfil HDR10", Toast.LENGTH_LONG).show()
        }

        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC && !decoderRenderer.isHevcSupported()) {
            Toast.makeText(this, "No se encontrÃ³ decodificador HEVC", Toast.LENGTH_LONG).show()
        }

        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 && !decoderRenderer.isAv1Supported()) {
            Toast.makeText(this, "No se encontrÃ³ decodificador AV1", Toast.LENGTH_LONG).show()
        }

        var supportedVideoFormats: Short = MoonBridge.VIDEO_FORMAT_H264.toShort()
        if (decoderRenderer.isHevcSupported()) {
            supportedVideoFormats = (supportedVideoFormats.toInt() or MoonBridge.VIDEO_FORMAT_H265.toInt()).toShort()
            if (prefConfig.enableHdr && decoderRenderer.isHevcMain10Hdr10Supported()) {
                supportedVideoFormats = (supportedVideoFormats.toInt() or MoonBridge.VIDEO_FORMAT_H265_MAIN10.toInt()).toShort()
            }
        }
        if (decoderRenderer.isAv1Supported()) {
            supportedVideoFormats = (supportedVideoFormats.toInt() or MoonBridge.VIDEO_FORMAT_AV1_MAIN8.toInt()).toShort()
            if (prefConfig.enableHdr && decoderRenderer.isAv1Main10Supported()) {
                supportedVideoFormats = (supportedVideoFormats.toInt() or MoonBridge.VIDEO_FORMAT_AV1_MAIN10.toInt()).toShort()
            }
        }

        var gamepadMask: Short = ControllerHandler.getAttachedControllerMask(this)
        if (!prefConfig.multiController) {
            gamepadMask = 1.toShort()
        }
        if (prefConfig.onscreenController) {
            gamepadMask = (gamepadMask.toInt() or 1).toShort()
        }

        val displayRefreshRate = sessionDisplayManager?.prepareDisplayForRendering() ?: 60f
        com.limelight.LimeLog.info("Display refresh rate: $displayRefreshRate")

        val roundedRefreshRate = Math.round(displayRefreshRate)
        var chosenFrameRate = prefConfig.fps
        if (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (prefConfig.fps >= roundedRefreshRate) {
                if (prefConfig.fps > roundedRefreshRate + 3) {
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED
                    com.limelight.LimeLog.info("Using drop mode for FPS > Hz")
                } else if (roundedRefreshRate <= 49) {
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED
                    com.limelight.LimeLog.info("Bogus refresh rate: $roundedRefreshRate")
                } else {
                    chosenFrameRate = roundedRefreshRate - 1
                    com.limelight.LimeLog.info("Adjusting FPS target for screen to $chosenFrameRate")
                }
            }
        }

        var effectiveBitrate = prefConfig.bitrate
        var effectiveWidth = prefConfig.width
        var effectiveHeight = prefConfig.height
        if (adaptiveQualityController?.isEnabled == true && adaptiveQualityController!!.currentLevel > 0) {
            val lvl = adaptiveQualityController!!.currentLevel
            effectiveBitrate = adaptiveQualityController!!.getBitrateForLevel(lvl)
            val res = adaptiveQualityController!!.getResolutionForLevel(lvl)
            effectiveWidth = res[0]
            effectiveHeight = res[1]
            com.limelight.LimeLog.info("AdaptiveQuality: nivel $lvl -> ${effectiveWidth}x$effectiveHeight @ $effectiveBitrate kbps")
        }

        val config = StreamConfiguration.Builder()
            .setResolution(effectiveWidth, effectiveHeight)
            .setLaunchRefreshRate(prefConfig.fps)
            .setRefreshRate(chosenFrameRate)
            .setApp(app!!)
            .setBitrate(effectiveBitrate)
            .setEnableSops(prefConfig.enableSops)
            .enableLocalAudioPlayback(prefConfig.playHostAudio)
            .setMaxPacketSize(1392)
            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
            .setSupportedVideoFormats(supportedVideoFormats.toInt())
            .setAttachedGamepadMask(gamepadMask.toInt())
            .setClientRefreshRateX100((displayRefreshRate * 100).toInt())
            .setAudioConfiguration(prefConfig.audioConfiguration)
            .setColorSpace(0 /* decoderRenderer.getPreferredColorSpace() */)
            .setColorRange(0 /* decoderRenderer.getPreferredColorRange() */)
            .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
            .build()

        this.connHost = host
        this.connPort = port
        this.connHttpsPort = httpsPort
        this.connUniqueId = uniqueId
        this.connServerCert = serverCert
        this.streamConfig = config

        conn = NvConnection(
            applicationContext, ComputerDetails.AddressTuple(host!!, port),
            httpsPort, uniqueId, config, PlatformBinding.getCryptoProvider(this), serverCert
        )
        controllerHandler = ControllerHandler(this, conn!!, this, prefConfig)
        keyboardTranslator = KeyboardTranslator()
        
        streamKeyboardHandler = com.limelight.smartdisplay.AppContainer.createStreamKeyboardHandler(
            object : com.limelight.smartdisplay.input.KeyboardSessionCallback {
                override fun isInputGrabbed(): Boolean = grabbedInput
                override fun toggleInputGrab() {
                    runOnUiThread { setInputGrabState(!grabbedInput) }
                }
                override fun finishSession() { finish() }
                override fun toggleCursorVisibility() {
                    runOnUiThread {
                        if (!grabbedInput) {
                            inputCaptureProvider.enableCapture()
                            grabbedInput = true
                        }
                        cursorVisible = !cursorVisible
                        if (cursorVisible) {
                            inputCaptureProvider.showCursor()
                        } else {
                            inputCaptureProvider.hideCursor()
                        }
                    }
                }
                override fun isMouseNavButtonsEnabled(): Boolean = prefConfig.mouseNavButtons
            },
            keyboardTranslator!!, controllerHandler, conn
        )

        logicalKeyboard?.setConnection(conn!!)
        windowControlsCtrl?.setConnection(conn!!)
        mouseModeCircle?.setConnection(conn!!)
        portraitHybridCtrl?.setConnection(conn!!)

        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(keyboardTranslator, null)

        com.limelight.smartdisplay.profile.ProfileManager(this).applyToActive()

        streamMotionInputHandler?.apply {
            connection = conn
// // // //             setControllerHandler(controllerHandler)
            setupTouchContexts(conn, streamView)
        }

        if (prefConfig.onscreenController) {
            virtualController = VirtualController(controllerHandler, streamView.parent as FrameLayout, this)
            virtualController?.refreshLayout()
            virtualController?.show()
// // // //             streamMotionInputHandler?.setVirtualController(virtualController)
        }

        if (prefConfig.touchscreenTrackpad) {
            adaptiveCursor?.visibility = View.GONE
        }

        if (prefConfig.usbDriver) {
            bindService(Intent(this, UsbDriverService::class.java), usbDriverServiceConnection, Service.BIND_AUTO_CREATE)
        }

        if (!decoderRenderer.isAvcSupported()) {
            spinner?.dismiss()
            spinner = null
            Dialog.displayDialog(this, resources.getString(R.string.conn_error_title), "This device or ROM doesn't support hardware accelerated H.264 playback.", true)
            return
        }

        streamView.holder.addCallback(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (sessionDisplayManager != null) {
            // sessionDisplayManager?.onConfigurationChanged(newConfig)
        } else {
            val streamWrapper = findViewById<View>(R.id.streamTransformWrapper)
            if (streamWrapper != null) {
                if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    val scale = resources.displayMetrics.heightPixels.toFloat() / resources.displayMetrics.widthPixels.toFloat()
                    streamWrapper.scaleX = scale
                    streamWrapper.scaleY = scale
                    streamWrapper.rotation = 90f
                } else {
                    streamWrapper.scaleX = 1f
                    streamWrapper.scaleY = 1f
                    streamWrapper.rotation = 0f
                }
            }
        }
        
        updateLayoutForOrientation(newConfig.orientation)
    }

    private fun updatePipAutoEnter() {
        sessionDisplayManager?.updatePipAutoEnter(connected, suppressPipRefCount, appName, pcName)
    }

    private fun rebindConnection(newConn: NvConnection) {
        controllerHandler?.setConnection(newConn)
        streamKeyboardHandler?.connection = newConn
        logicalKeyboard?.setConnection(newConn)
        windowControlsCtrl?.setConnection(newConn)
        mouseModeCircle?.setConnection(newConn)
        portraitHybridCtrl?.setConnection(newConn)
        streamMotionInputHandler?.connection = newConn
        streamMotionInputHandler?.setupTouchContexts(newConn, streamView)
    }

    private fun stopConnection() {
        if (connecting || connected) {
            connecting = false
            connected = false
            updatePipAutoEnter()
            controllerHandler?.stop()
            UiHelper.notifyStreamEnded(this)
            val c = conn
            Thread {
                try {
                    c?.stop()
                } catch (ignored: Exception) {}
            }.start()
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun setMetaKeyCaptureState(capture: Boolean) {
        sessionDisplayManager?.setMetaKeyCaptureState(capture)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            sessionDisplayManager?.enterPipMode(appName, pcName)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isTransitioningToPip = false
        val fabContainer = findViewById<View>(R.id.overlayFabContainer)
        if (fabContainer != null) {
            fabContainer.visibility = View.VISIBLE
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    override fun onPictureInPictureRequested(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            sessionDisplayManager?.enterPipMode(appName, pcName)
        }
        return true
    }

    override fun onPause() {
        logicalKeyboard?.onInputSuspended()
        if (isFinishing) {
            controllerHandler?.stop()
            setInputGrabState(false)
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (isScreenOffMode) {
            com.limelight.LimeLog.info("onResume: volviendo de pantalla suspendida")
            isScreenOffMode = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            overlayFabController?.exitPerformanceMode()
        } else if (connected) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    override fun onStop() {
        super.onStop()
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (inPip || isTransitioningToPip) {
            com.limelight.LimeLog.info("onStop ignorado por estar en PiP mode")
            return
        }
        if (isFinishing) {
            conn?.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.limelight.LimeLog.info("Game Activity destroyed")

        smartDisplaySessionManager?.destroy()
        smartDisplaySessionManager = null

        autoReconnectManager?.stop()
        autoReconnectManager = null

        overlayFabController?.release()
        logicalKeyboard?.release()
        adaptiveCursor?.release()
        windowControlsCtrl?.release()

        controllerHandler?.destroy()
        controllerHandler = null

        keyboardTranslator?.let {
            val inputManager = getSystemService(Context.INPUT_SERVICE) as? InputManager
            inputManager?.unregisterInputDeviceListener(it)
        }

        if (connectedToUsbDriverService) {
            try {
                usbDriverBinder?.setListener(null)
                usbDriverBinder?.setStateListener(null)
                usbDriverBinder = null
                unbindService(usbDriverServiceConnection)
            } catch (ignored: Exception) {}
            connectedToUsbDriverService = false
        }

        try {
            inputCaptureProvider.destroy()
        } catch (ignored: Exception) {}

        conn?.stop()
        conn = null

        audioRenderer?.stop()
        audioRenderer = null
    }

    private fun getStreamHdrMode(): Int { return 0 }


    private fun hideSystemUi(delayMs: Int) {
        if (!connected) return

        val decorView = window.decorView
        val uiOptions = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        decorView.systemUiVisibility = uiOptions

        if (delayMs > 0) {
            Handler().postDelayed({
                if (connected) {
                    decorView.systemUiVisibility = uiOptions
                }
            }, delayMs.toLong())
        }
    }

    private fun setInputGrabState(grab: Boolean) {
        if (grabbedInput != grab) {
            grabbedInput = grab

            if (grabbedInput) {
                com.limelight.LimeLog.info("Grabbed input")
                inputCaptureProvider.enableCapture()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setMetaKeyCaptureState(true)
                }

                if (cursorVisible) {
                    inputCaptureProvider.showCursor()
                } else {
                    inputCaptureProvider.hideCursor()
                }

                if (!prefConfig.mouseNavButtons) {
// // // //                     streamMotionInputHandler?.setMouseNavButtonsEnabled(false)
                }
            } else {
                com.limelight.LimeLog.info("Ungrabbed input")
                inputCaptureProvider.disableCapture()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setMetaKeyCaptureState(false)
                }
            }
                    }
    }

    private fun shouldIgnoreInsetsForResolution(width: Int, height: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return (width == 3120 && height == 1440) ||
                    (width == 1440 && height == 3120) ||
                    (width == 3040 && height == 1440) ||
                    (width == 1440 && height == 3040) ||
                    (width == 2960 && height == 1440) ||
                    (width == 1440 && height == 2960) ||
                    (width == 3168 && height == 1440) ||
                    (width == 1440 && height == 3168)
        }
        return false
    }

    private fun startNewConnectionSession() {
        com.limelight.LimeLog.info("startNewConnectionSession CALLED!")
        if (conn == null) return

        val lvl = adaptiveQualityController?.currentLevel ?: 0
        var effectiveBitrate = prefConfig.bitrate
        var effectiveWidth = prefConfig.width
        var effectiveHeight = prefConfig.height

        if (adaptiveQualityController?.isEnabled == true && lvl > 0) {
            effectiveBitrate = adaptiveQualityController!!.getBitrateForLevel(lvl)
            val res = adaptiveQualityController!!.getResolutionForLevel(lvl)
            effectiveWidth = res[0]
            effectiveHeight = res[1]
        }

        var gamepadMask: Short = ControllerHandler.getAttachedControllerMask(this)
        if (!prefConfig.multiController) {
            gamepadMask = 1.toShort()
        }
        if (prefConfig.onscreenController) {
            gamepadMask = (gamepadMask.toInt() or 1).toShort()
        }

        var supportedVideoFormats: Short = MoonBridge.VIDEO_FORMAT_H264.toShort()
        if (decoderRenderer.isHevcSupported()) {
            supportedVideoFormats = (supportedVideoFormats.toInt() or MoonBridge.VIDEO_FORMAT_H265.toInt()).toShort()
            if (prefConfig.enableHdr && decoderRenderer.isHevcMain10Hdr10Supported()) {
                supportedVideoFormats = (supportedVideoFormats.toInt() or MoonBridge.VIDEO_FORMAT_H265_MAIN10.toInt()).toShort()
            }
        }
        if (decoderRenderer.isAv1Supported()) {
            supportedVideoFormats = (supportedVideoFormats.toInt() or MoonBridge.VIDEO_FORMAT_AV1_MAIN8.toInt()).toShort()
            if (prefConfig.enableHdr && decoderRenderer.isAv1Main10Supported()) {
                supportedVideoFormats = (supportedVideoFormats.toInt() or MoonBridge.VIDEO_FORMAT_AV1_MAIN10.toInt()).toShort()
            }
        }

        val config = StreamConfiguration.Builder()
            .setResolution(effectiveWidth, effectiveHeight)
            .setLaunchRefreshRate(prefConfig.fps)
            .setRefreshRate(prefConfig.fps)
            .setApp(app!!)
            .setBitrate(effectiveBitrate)
            .setEnableSops(prefConfig.enableSops)
            .enableLocalAudioPlayback(prefConfig.playHostAudio)
            .setMaxPacketSize(1392)
            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
            .setSupportedVideoFormats(supportedVideoFormats.toInt())
            .setAttachedGamepadMask(gamepadMask.toInt())
            .setClientRefreshRateX100((desiredRefreshRate * 100).toInt())
            .setAudioConfiguration(prefConfig.audioConfiguration)
            .setColorSpace(0 /* decoderRenderer.getPreferredColorSpace() */)
            .setColorRange(0 /* decoderRenderer.getPreferredColorRange() */)
            .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
            .build()

        conn = NvConnection(
            applicationContext, ComputerDetails.AddressTuple(connHost!!, connPort),
            connHttpsPort, connUniqueId!!, config, PlatformBinding.getCryptoProvider(this), connServerCert!!
        )

        controllerHandler?.setConnection(conn!!)
        streamKeyboardHandler?.connection = conn
        logicalKeyboard?.setConnection(conn!!)
        windowControlsCtrl?.setConnection(conn!!)
        mouseModeCircle?.setConnection(conn!!)
        portraitHybridCtrl?.setConnection(conn!!)
        streamMotionInputHandler?.connection = conn
        streamMotionInputHandler?.setupTouchContexts(conn, streamView)

        connecting = true
        decoderRenderer.setRenderTarget(streamView.holder)
        audioRenderer = AndroidAudioRenderer(this, prefConfig.enableAudioFx)
        if (smartDisplaySessionManager?.isVoiceRecording() == true) {
            audioRenderer?.setMuted(true)
        }

        com.limelight.LimeLog.info("Calling conn?.start() now!")
        
        
// // // //         com.limelight.smartdisplay.AppContainer.currentActivity = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi(0)
            if (connected) {
                setInputGrabState(true)
            }
        } else {
            setInputGrabState(false)
        }
    }

    private fun toggleMouseModeUnified() {
        val shown = mouseModeCircle?.toggle() ?: false
        if (prefConfig.touchscreenTrackpad != shown) {
            // toggleTouchMode()   // sincroniza el modo de entrada con el circulo
        }
        // Con el circulo activo el unico cursor visible es el REAL del PC:
        // ocultamos la flecha adaptativa para no mostrar dos cursores.
        if (shown) {
            adaptiveCursor?.visibility = View.GONE
        }
        Toast.makeText(this,
                if (shown) "Modo mouse: activado (trackpad + circulo)" else "Modo mouse: desactivado",
                Toast.LENGTH_SHORT).show()
    }

    private fun togglePortraitHybridMode() {
        val currentOrientation = resources.configuration.orientation
        if (currentOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
    }

    override fun toggleKeyboard() {
        if (logicalKeyboard?.isVisible() == true) {
            logicalKeyboard?.hide()
        } else {
            logicalKeyboard?.show()
        }
    }

    private fun toggleVoiceWithAudioFeedback() {
        smartDisplaySessionManager?.toggleVoiceWithAudioFeedback(audioRenderer)
    }

    private fun sendBusCommand(command: String, data: Int?) {
        smartDisplaySessionManager?.sendBusCommand(command, data)
    }

    override fun handleKeyDown(event: KeyEvent): Boolean {
        // Fallback or empty logic before delegating. In Game_old.java this was completely delegated
        // except for some mouse source back checks, but StreamKeyboardHandler takes care of everything.
        return streamKeyboardHandler?.handleKeyDown(event) ?: false
    }

    override fun handleKeyUp(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return false
        }
        return streamKeyboardHandler?.handleKeyUp(event) ?: false
    }

    private fun handleKeyMultiple(event: KeyEvent): Boolean {
        return streamKeyboardHandler?.handleKeyMultiple(event) ?: false
    }

    fun sendHardwareKey(keyMap: Short, keyDirection: Byte) {
        conn?.sendKeyboardInput(keyMap, keyDirection, 0.toByte(), 0.toByte())
    }

    override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
        return streamMotionInputHandler?.handleMotionEvent(v, event) ?: false
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!connected) {
            return true
        }

        // 1. Si el controlador de transformación (pellizco multitáctil, arrastre con 2 dedos
        //    o modo mover pantalla con 1 dedo) consume el evento, cancelamos toques remotos
        //    y evitamos enviar clics erróneos al PC.
        if (streamTransformCtrl?.onTouchEvent(event) == true) {
            streamMotionInputHandler?.cancelAllTouches()
            return true
        }

        // 2. Si no es un gesto de viewport, se envía normalmente al ratón/touch del PC.
        if (streamMotionInputHandler?.handleMotionEvent(v, event) == true) {
            return true
        }

        return true
    }

    override fun stageStarting(stage: String) {
        if (autoReconnectManager != null && autoReconnectManager?.isReconnecting() == true) {
            return
        }

        runOnUiThread {
            if (spinner != null) {
                spinner?.setMessage(stage)
            }
        }
    }

    override fun stageComplete(stage: String) {
    }

    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
        if (autoReconnectManager != null && autoReconnectManager?.isReconnecting() == true) {
            com.limelight.LimeLog.warning("AutoReconnect: stageFailed durante reconexiÃ³n ($stage, code=$errorCode)")
            if (conn != null) {
                Thread { conn?.stop() }.start()
            }
            autoReconnectManager?.onReconnectAttemptFailed(errorCode)
            return
        }

        runOnUiThread {
            if (spinner != null) {
                spinner?.dismiss()
                spinner = null
            }
        }

        if (!displayedFailureDialog) {
            displayedFailureDialog = true
            com.limelight.smartdisplay.recovery.StreamErrorDialogHelper.showStageFailed(
                this, stage, portFlags, errorCode,
                streamView.holder.surface.isValid
            )
        }
    }

    override fun connectionTerminated(errorCode: Int) {
        if (logicalKeyboard != null) {
            logicalKeyboard?.onInputSuspended()
        }
        if (adaptiveReconnecting) {
            com.limelight.LimeLog.info("AdaptiveBitrate: ignorando connectionTerminated (relanzamiento en curso)")
            return
        }

        if (autoReconnectManager != null && !displayedFailureDialog) {
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                controllerHandler?.stop()
                setInputGrabState(false)
            }

            connecting = false
            connected = false

            if (autoReconnectManager?.isReconnecting() == true) {
                if (conn != null) {
                    Thread { conn?.stop() }.start()
                }
                autoReconnectManager?.onReconnectAttemptFailed(errorCode)
                return
            }

            if (autoReconnectManager?.onConnectionTerminated(errorCode) == true) {
                if (conn != null) {
                    Thread { conn?.stop() }.start()
                }
                return
            }
        }

        runOnUiThread {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controllerHandler?.stop()
            setInputGrabState(false)

            if (overlayFabController != null) {
                overlayFabController?.exitPerformanceMode()
            }
            if (logicalKeyboard != null) {
                logicalKeyboard?.resumeAnimations()
            }

            if (!displayedFailureDialog) {
                displayedFailureDialog = true
                stopConnection()
                com.limelight.smartdisplay.recovery.StreamErrorDialogHelper.showTerminationDialog(
                    this@Game, errorCode
                ) {
                    finish()
                }
            }
        }
    }

    override fun connectionStatusUpdate(connectionStatus: Int) {
        if (adaptiveQualityController != null && adaptiveQualityController?.isEnabled == true && connected && autoReconnectManager?.isReconnecting() == false) {
            val now = System.currentTimeMillis()
            adaptiveQualityController?.onConnectionStatusChanged(connectionStatus, now)
        }

        runOnUiThread {
            if (prefConfig.disableWarnings) {
                return@runOnUiThread
            }

            if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
                requestedNotificationOverlayVisibility = View.GONE
            } else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY) {
                requestedNotificationOverlayVisibility = View.GONE
            }

            if (!isHidingOverlays) {
                notificationOverlayView.visibility = requestedNotificationOverlayVisibility
            }
        }
    }

    private fun triggerAdaptiveReconnect() {
        if (adaptiveQualityController == null) return
        
        val host = intent.getStringExtra(EXTRA_HOST) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT)
        val httpsPort = intent.getIntExtra(EXTRA_HTTPS_PORT, 0)
        val uniqueId = intent.getStringExtra(EXTRA_UNIQUEID) ?: ""
        val derCertData = intent.getByteArrayExtra(EXTRA_SERVER_CERT)
        var serverCert: X509Certificate? = null
        try {
            if (derCertData != null) {
                serverCert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val finalServerCert = serverCert
        runOnUiThread {
            if (!connected || conn == null || autoReconnectManager?.isReconnecting() == true) {
                adaptiveReconnecting = false
                return@runOnUiThread
            }
            val lvl = adaptiveQualityController!!.currentLevel
            val res = adaptiveQualityController!!.getResolutionForLevel(lvl)
            val bitrate = adaptiveQualityController!!.getBitrateForLevel(lvl)
            com.limelight.LimeLog.info("AdaptiveQuality: relanzando conexiÃ³n nivel $lvl -> ${res[0]}x${res[1]} @ $bitrate kbps")
            
            if (reconnectOverlayView != null) {
                reconnectOverlayView?.alpha = 0f
                reconnectOverlayView?.visibility = View.VISIBLE
                reconnectOverlayView?.animate()?.alpha(0.6f)?.setDuration(200)?.start()
            }
            if (reconnectStatusText != null) {
                val labels = arrayOf("Calidad mÃ¡xima", "Calidad media", "Calidad baja")
                val idx = Math.min(lvl, labels.size - 1)
                reconnectStatusText?.text = "Ajustando: ${labels[idx]} (${res[0]}x${res[1]})"
            }
            
            controllerHandler?.stop()
            setInputGrabState(false)
            connecting = false
            connected = false
            adaptiveReconnecting = true
            
            val oldConn = conn
            Thread {
                oldConn?.stop()
                
                var gamepadMask = ControllerHandler.getAttachedControllerMask(this@Game)
                if (!prefConfig.multiController) {
                    gamepadMask = 1.toShort()
                }
                if (prefConfig.onscreenController) {
                    gamepadMask = (gamepadMask.toInt() or 1).toShort()
                }

                val newConfig = StreamConfiguration.Builder()
                    .setResolution(res[0], res[1])
                    .setLaunchRefreshRate(prefConfig.fps)
                    .setRefreshRate(prefConfig.fps)
                    .setApp(app!!)
                    .setBitrate(bitrate)
                    .setEnableSops(prefConfig.enableSops)
                    .enableLocalAudioPlayback(prefConfig.playHostAudio)
                    .setMaxPacketSize(1392)
                    .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                    .setSupportedVideoFormats(if (decoderRenderer.activeVideoFormat != 0) decoderRenderer.activeVideoFormat else MoonBridge.VIDEO_FORMAT_H264)
                    .setAttachedGamepadMask(gamepadMask.toInt())
                    .setClientRefreshRateX100((desiredRefreshRate * 100).toInt())
                    .setAudioConfiguration(prefConfig.audioConfiguration)
                    .setColorSpace(0 /* decoderRenderer.getPreferredColorSpace() */)
                    .setColorRange(0 /* decoderRenderer.getPreferredColorRange() */)
                    .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
                    .build()

                val newConn = NvConnection(
                    applicationContext, ComputerDetails.AddressTuple(host!!, port),
                    httpsPort, uniqueId, newConfig, PlatformBinding.getCryptoProvider(this@Game), finalServerCert
                )

                runOnUiThread {
                    adaptiveReconnecting = false
                    if (streamView.holder.surface.isValid) {
                        conn = newConn
                        connecting = true
                        decoderRenderer.setRenderTarget(streamView.holder)
                        audioRenderer = AndroidAudioRenderer(this@Game, prefConfig.enableAudioFx)
                        if (smartDisplaySessionManager?.isVoiceRecording() == true) {
                            audioRenderer?.setMuted(true)
                        }
                        rebindConnection(newConn)
                        newConn.start(audioRenderer!!, decoderRenderer, this@Game)
                        
                        val labels = arrayOf("Calidad mÃ¡xima", "Calidad media", "Calidad baja")
                        val idx = Math.min(lvl, labels.size - 1)
                        Toast.makeText(this@Game, "${labels[idx]}: ${res[0]}x${res[1]} @ $bitrate kbps", Toast.LENGTH_SHORT).show()
                    } else {
                        adaptiveReconnecting = false
                        if (reconnectOverlayView != null) {
                            reconnectOverlayView?.visibility = View.GONE
                        }
                    }
                }
            }.start()
        }
    }

    override fun connectionStarted() {
        runOnUiThread {
            if (spinner != null) {
                spinner?.dismiss()
                spinner = null
            }

            connected = true
            connecting = false
            updatePipAutoEnter()

            if (autoReconnectManager != null) {
                autoReconnectManager?.onConnectionEstablished()
            }
            if (portraitHybridCtrl != null) {
                portraitHybridCtrl?.startStatsPolling({ connected }, { decoderRenderer })
            }

            Handler().postDelayed({
                setInputGrabState(true)
            }, 500)

            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

            
            if (overlayFabController != null) {
                overlayFabController?.enterPerformanceMode()
            }
            if (logicalKeyboard != null) {
                logicalKeyboard?.pauseAnimations()
            }

            hideSystemUi(1000)
        }

        if (sessionRecoveryManager != null) {
            val host = intent.getStringExtra(EXTRA_HOST)
            val port = intent.getIntExtra(EXTRA_PORT, 47989)
            val httpsPort = intent.getIntExtra(EXTRA_HTTPS_PORT, 0)
            val appId = intent.getIntExtra(EXTRA_APP_ID, -1)
            val uniqueId = intent.getStringExtra(EXTRA_UNIQUEID) ?: ""
            val pcUuid = intent.getStringExtra(EXTRA_PC_UUID) ?: ""
            val appHdr = intent.getBooleanExtra(EXTRA_APP_HDR, false)
            var macAddr = intent.getStringExtra(EXTRA_MAC) ?: "" ?: "" ?: "" ?: ""
            if (macAddr == null) macAddr = ""
            sessionRecoveryManager?.saveSession(
                host, port, httpsPort, appName, appId,
                uniqueId, pcUuid, pcName, appHdr, macAddr
            )
        }

        val computer = ComputerDetails()
        computer.name = pcName ?: ""
        computer.uuid = intent.getStringExtra(EXTRA_PC_UUID) ?: ""
        val shortcutHelper = ShortcutHelper(this)
        shortcutHelper.reportComputerShortcutUsed(computer)
        if (appName != null) {
            shortcutHelper.reportGameLaunched(computer, app!!)
        }
    }

    override fun displayMessage(message: String) {
        runOnUiThread {
            Toast.makeText(this@Game, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun displayTransientMessage(message: String) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread {
                Toast.makeText(this@Game, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        com.limelight.LimeLog.info(String.format(null as Locale?, "Rumble on gamepad %d: %04x %04x", controllerNumber, lowFreqMotor, highFreqMotor))
        controllerHandler?.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor)
    }

    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        com.limelight.LimeLog.info(String.format(null as Locale?, "Rumble on gamepad triggers %d: %04x %04x", controllerNumber, leftTrigger, rightTrigger))
        controllerHandler?.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
    }

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        com.limelight.LimeLog.info("Display HDR mode: " + (if (enabled) "enabled" else "disabled"))
        decoderRenderer.setHdrMode(enabled, hdrMetadata)
    }

    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
        controllerHandler?.handleSetMotionEventState(controllerNumber, motionType, reportRateHz)
    }

    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        controllerHandler?.handleSetControllerLED(controllerNumber, r, g, b)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        com.limelight.LimeLog.info("surfaceChanged CALLED!")
        if (!surfaceCreated) {
            throw IllegalStateException("Surface changed before creation!")
        }

        if (!attemptedConnection) {
            attemptedConnection = true
                        decoderRenderer.setRenderTarget(holder)
            audioRenderer = AndroidAudioRenderer(this, prefConfig.enableAudioFx)
            com.limelight.LimeLog.info("Calling conn?.start() now!")
            conn?.start(audioRenderer!!, decoderRenderer, this)
        } else {
            if (isScreenOffMode && connected) {
                com.limelight.LimeLog.info("surfaceChanged: recuperando de pantalla suspendida â€” reiniciando pipeline de video limpiamente")
                isScreenOffMode = false
                startNewConnectionSession()
                com.limelight.LimeLog.info("surfaceChanged: pipeline de video reiniciado tras pantalla suspendida")
            } else {
                decoderRenderer.setRenderTarget(holder)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        com.limelight.LimeLog.info("surfaceCreated CALLED!")
        surfaceCreated = true

        val desiredFrameRate: Float
        if (mayReduceRefreshRate() || desiredRefreshRate < prefConfig.fps) {
            desiredFrameRate = prefConfig.fps.toFloat()
        } else {
            desiredFrameRate = desiredRefreshRate
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            holder.surface.setFrameRate(
                desiredFrameRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface.setFrameRate(
                desiredFrameRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            )
        }
    }

    private fun mayReduceRefreshRate(): Boolean {
        // ImplementaciÃ³n auxiliar, en Java original no estaba el cuerpo explÃ­cito aquÃ­ pero 
        // asumimos que es una llamada a algo de prefConfig o similar.
        return prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!surfaceCreated) {
            throw IllegalStateException("Surface destroyed before creation!")
        }

        if (attemptedConnection) {
            if (connected) {
                var inPip = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    inPip = isInPictureInPictureMode
                }

                if (!inPip && !(sessionDisplayManager != null && sessionDisplayManager?.isTransitioningToPip == true)) {
                    if (isScreenOffMode) {
                        com.limelight.LimeLog.info("surfaceDestroyed: pantalla suspendida â€” pausando decoder sin matar conexiÃ³n")
                        decoderRenderer.prepareForStop()
                    } else {
                        decoderRenderer.prepareForStop()
                        stopConnection()
                    }
                } else {
                    com.limelight.LimeLog.info("surfaceDestroyed ignorado por estar en PiP mode o transicionando.")
                }
            } else {
                decoderRenderer.prepareForStop()
            }
        }
    }

    override fun mouseMove(deltaX: Int, deltaY: Int) {
        if (conn != null) {
            conn?.sendMouseMove(deltaX.toShort(), deltaY.toShort())
        }
    }

    override fun mouseButtonEvent(buttonId: Int, down: Boolean) {
        if (conn == null) {
            return
        }

        val buttonIndex: Byte = when (buttonId) {
            EvdevListener.BUTTON_LEFT -> MouseButtonPacket.BUTTON_LEFT
            EvdevListener.BUTTON_MIDDLE -> MouseButtonPacket.BUTTON_MIDDLE
            EvdevListener.BUTTON_RIGHT -> MouseButtonPacket.BUTTON_RIGHT
            EvdevListener.BUTTON_X1 -> MouseButtonPacket.BUTTON_X1
            EvdevListener.BUTTON_X2 -> MouseButtonPacket.BUTTON_X2
            else -> {
                com.limelight.LimeLog.warning("Unhandled button: $buttonId")
                return
            }
        }

        if (down) {
            conn?.sendMouseButtonDown(buttonIndex)
        } else {
            conn?.sendMouseButtonUp(buttonIndex)
        }
    }

    override fun mouseVScroll(amount: Byte) {
        if (conn != null) {
            conn?.sendMouseScroll(amount)
        }
    }

    override fun mouseHScroll(amount: Byte) {
        if (conn != null) {
            conn?.sendMouseHScroll(amount)
        }
    }

    override fun keyboardEvent(buttonDown: Boolean, keyCode: Short) {
        if (streamKeyboardHandler != null) {
            streamKeyboardHandler?.handleVirtualKeyEvent(keyCode, buttonDown)
        }
    }

    override fun onSystemUiVisibilityChange(visibility: Int) {
        if (sessionDisplayManager != null) {
            sessionDisplayManager?.onSystemUiVisibilityChange(visibility, connected)
        }
    }

    override fun onUsbPermissionPromptStarting() {
        suppressPipRefCount++
        updatePipAutoEnter()
    }

    override fun onUsbPermissionPromptCompleted() {
        suppressPipRefCount--
        updatePipAutoEnter()
    }

    private fun openFileBrowser() {
        if (smartDisplaySessionManager != null) {
            smartDisplaySessionManager?.openFileBrowser()
        }
    }

    private fun sendPickedFiles(files: List<java.io.File>) {
        if (smartDisplaySessionManager != null) {
            smartDisplaySessionManager?.sendPickedFiles(files)
        }
    }

    private fun shareFilesViaFab() {
        if (smartDisplaySessionManager != null) {
            smartDisplaySessionManager?.shareFilesViaFab()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (smartDisplaySessionManager != null) {
            smartDisplaySessionManager?.handleActivityResult(requestCode, resultCode, data)
        }
    }

    private fun togglePerformanceHud() {
        if (performanceHudController != null) {
            performanceHudController?.toggle(prefConfig)
        }
    }

    override fun onKey(view: View, keyCode: Int, keyEvent: KeyEvent): Boolean {
        return when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> handleKeyDown(keyEvent)
            KeyEvent.ACTION_UP -> handleKeyUp(keyEvent)
            KeyEvent.ACTION_MULTIPLE -> handleKeyMultiple(keyEvent)
            else -> false
        }
    }



    private fun updateLayoutForOrientation(orientation: Int) {
        if (portraitHybridLayout != null) {
            portraitHybridLayout?.visibility = View.GONE
        }

        val overlayContainer = findViewById<View>(R.id.overlayContainer)
        val windowControls = findViewById<View>(R.id.overlayWindowControls)

        if (overlayContainer != null) {
            overlayContainer.visibility = View.VISIBLE
        }

        if (windowControls != null && !isHidingOverlays) {
            windowControls.visibility = View.VISIBLE
        }

        if (overlayFabController != null) {
            overlayFabController?.onOrientationChanged()
        }

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            if (virtualController != null) virtualController?.hide()
        } else {
            if (virtualController != null && !isHidingOverlays) virtualController?.show()
        }
    }

    companion object {
        const val EXTRA_HOST = "Host"
        const val EXTRA_PORT = "Port"
        const val EXTRA_HTTPS_PORT = "HttpsPort"
        const val EXTRA_APP_NAME = "AppName"
        const val EXTRA_APP_ID = "AppId"
        const val EXTRA_PC_NAME = "PcName"
        const val EXTRA_PC_UUID = "PcUuid"
        const val EXTRA_UNIQUEID = "UniqueId"
        const val EXTRA_SERVER_CERT = "ServerCert"
        const val EXTRA_APP_HDR = "AppHdr"
        const val EXTRA_MAC = "Mac"
    }


}



