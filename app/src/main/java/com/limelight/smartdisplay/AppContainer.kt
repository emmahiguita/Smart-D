package com.limelight.smartdisplay

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/**
 * Manual Dependency Injection container for SmartDisplay AI.
 *
 * Provides singleton-scoped services and factory methods for smartdisplay
 * components. Wire this in your Application.onCreate() (if you have an
 * Application subclass) or call [init] lazily from the first Activity.
 *
 * This is the Android-recommended manual DI pattern: lightweight, zero
 * annotation processing, no reflection, fully testable via interfaces.
 *
 * Usage:
 *   AppContainer.init(context)
 *   val bus = AppContainer.createSmartDisplayBus(host, listener, pin)
 *   val theme = AppContainer.instance.themeManager.getCurrentThemeKey()
 */
object AppContainer {

    lateinit var instance: AppContainer
        private set

    private var initialized = false

    private lateinit var appContext: Context
    val mainHandler: Handler = Handler(Looper.getMainLooper())

    // ── Sub-systems (singletons, lazily created on first access) ──────────
    val themeManager: ThemeManagerService by lazy { ThemeManagerService() }
    val companionConfig: CompanionConfig by lazy { CompanionConfig() }

    /** Cached session recovery manager; cleared on session clear. */
    private var _recoveryManager: com.limelight.smartdisplay.recovery.SessionRecoveryManager? = null

    /**
     * Initialize the container. Safe to call multiple times; idempotent.
     * @param context Application or Activity context (application context stored).
     */
    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        instance = AppContainer
        appContext = context.applicationContext
        initialized = true
    }

    /** Application-level context. Prefer over Activity context for long-lived refs. */
    fun appContext(): Context = appContext

    // ═══════════════════════════════════════════════════════════════════════
    // SmartDisplay component factories
    // ═══════════════════════════════════════════════════════════════════════

    fun createSmartDisplayBus(
        host: String,
        listener: com.limelight.smartdisplay.bus.SmartDisplayBus.MessageListener,
        pin: String = ""
    ): com.limelight.smartdisplay.bus.SmartDisplayBus {
        return com.limelight.smartdisplay.bus.SmartDisplayBus(host, listener, pin)
    }

    fun createVoiceCaptureManager(hostIp: String, pin: String = ""): com.limelight.smartdisplay.voice.VoiceCaptureManager {
        return com.limelight.smartdisplay.voice.VoiceCaptureManager(hostIp, pin)
    }

    fun createFileTransferServer(port: Int = com.limelight.smartdisplay.files.FileTransferServer.DEFAULT_PORT): com.limelight.smartdisplay.files.FileTransferServer {
        return com.limelight.smartdisplay.files.FileTransferServer(appContext, port)
    }

    fun getSessionRecoveryManager(): com.limelight.smartdisplay.recovery.SessionRecoveryManager {
        if (_recoveryManager == null) {
            _recoveryManager = com.limelight.smartdisplay.recovery.SessionRecoveryManager(appContext)
        }
        return _recoveryManager!!
    }

    fun invalidateSessionRecovery() {
        _recoveryManager = null
    }

    fun createAutoReconnectManager(
        callback: com.limelight.smartdisplay.recovery.AutoReconnectManager.ReconnectCallback
    ): com.limelight.smartdisplay.recovery.AutoReconnectManager {
        return com.limelight.smartdisplay.recovery.AutoReconnectManager(appContext, callback)
    }

    fun createWakeOnLanManager(
        host: String,
        httpsPort: Int,
        macAddress: String
    ): com.limelight.smartdisplay.recovery.WakeOnLanManager {
        return com.limelight.smartdisplay.recovery.WakeOnLanManager(host, httpsPort, macAddress)
    }

    fun createTextFocusWatcher(
        wsUrl: String,
        callback: com.limelight.smartdisplay.TextFocusWatcher.Callback
    ): com.limelight.smartdisplay.TextFocusWatcher {
        return com.limelight.smartdisplay.TextFocusWatcher(wsUrl, callback)
    }

    fun createLastSessionStore(): com.limelight.smartdisplay.recents.LastSessionStore {
        return com.limelight.smartdisplay.recents.LastSessionStore(appContext)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UI controller factories
    // ═══════════════════════════════════════════════════════════════════════



    fun createSmartTaskbarController(
        rootView: android.view.View,
        actions: com.limelight.ui.SmartTaskbarController.Actions
    ): com.limelight.ui.SmartTaskbarController {
        return com.limelight.ui.SmartTaskbarController(rootView, actions)
    }

    fun createFileBrowserController(
        rootView: android.view.View,
        activity: android.app.Activity,
        onFilesPicked: (List<java.io.File>) -> kotlin.Unit
    ): com.limelight.ui.FileBrowserController {
        return com.limelight.ui.FileBrowserController(rootView, activity, onFilesPicked)
    }

    fun createSmartCursorEngine(
        cursorView: com.limelight.ui.AdaptiveCursorView
    ): com.limelight.ui.SmartCursorEngine {
        return com.limelight.ui.SmartCursorEngine(cursorView)
    }

    fun createPortraitHybridController(
        root: android.view.View,
        activity: android.app.Activity
    ): com.limelight.ui.PortraitHybridController {
        return com.limelight.ui.PortraitHybridController(root, activity)
    }

    fun createStreamViewTransformController(
        activity: android.app.Activity,
        streamWrapper: android.view.View,
        backgroundTouchView: android.view.View
    ): com.limelight.ui.StreamViewTransformController {
        return com.limelight.ui.StreamViewTransformController(activity, streamWrapper, backgroundTouchView)
    }

    fun createAudioHudController(
        activity: android.app.Activity,
        parent: android.view.ViewGroup
    ): com.limelight.ui.AudioHudController {
        return com.limelight.ui.AudioHudController(activity as com.limelight.Game, parent)
    }

    fun createOverlayFabController(
        fabMenuView: android.view.View
    ): com.limelight.ui.OverlayFabController {
        return com.limelight.ui.OverlayFabController(fabMenuView)
    }

    fun createLogicalKeyboardOverlay(
        keyboardPanelView: android.view.View,
        activity: android.app.Activity
    ): com.limelight.ui.LogicalKeyboardOverlay {
        return com.limelight.ui.LogicalKeyboardOverlay(keyboardPanelView, activity)
    }

    fun createWindowControlsController(
        rootView: android.view.View,
        activity: android.app.Activity
    ): com.limelight.ui.WindowControlsController {
        return com.limelight.ui.WindowControlsController(rootView, activity)
    }

    fun createAdaptiveCursorView(
        activity: android.app.Activity
    ): com.limelight.ui.AdaptiveCursorView {
        return com.limelight.ui.AdaptiveCursorView(activity)
    }

    fun createMouseModeCircle(
        activity: android.app.Activity
    ): com.limelight.ui.MouseModeCircle {
        return com.limelight.ui.MouseModeCircle(activity)
    }

    fun createSmartDisplaySessionManager(
        activity: android.app.Activity,
        streamView: android.view.SurfaceView,
        prefConfig: com.limelight.preferences.PreferenceConfiguration?,
        transformWrapper: android.view.View?,
        adaptiveCursor: com.limelight.ui.AdaptiveCursorView?,
        smartCursorEngine: com.limelight.ui.SmartCursorEngine?,
        logicalKeyboard: com.limelight.ui.LogicalKeyboardOverlay?,
        portraitHybridCtrl: com.limelight.ui.PortraitHybridController?,
        smartTaskbar: com.limelight.ui.SmartTaskbarController?,
        fileBrowser: com.limelight.ui.FileBrowserController?,
        streamMotionInputHandler: com.limelight.smartdisplay.input.StreamMotionInputHandler?
    ): com.limelight.smartdisplay.session.SmartDisplaySessionManager {
        return com.limelight.smartdisplay.session.SmartDisplaySessionManager(
            activity,
            streamView,
            prefConfig,
            transformWrapper,
            adaptiveCursor,
            smartCursorEngine,
            logicalKeyboard,
            portraitHybridCtrl,
            smartTaskbar,
            fileBrowser,
            streamMotionInputHandler
        )
    }

    /**
     * Crea el orquestador unificado de movimiento de ventanas [WindowMotionCoordinator].
     * Aplica Facade y Dependency Inversion (SOLID).
     */
    fun createWindowMotionCoordinator(
        transport:  com.limelight.smartdisplay.motion.WindowMotionTransport,
        streamView: android.view.View,
        context:    android.content.Context,
        pcWidth:    Int = 1920,
        pcHeight:   Int = 1080
    ): com.limelight.smartdisplay.motion.WindowMotionCoordinator {
        return com.limelight.smartdisplay.motion.WindowMotionCoordinator(
            transport, streamView, context, pcWidth, pcHeight
        )
    }

    /**
     * Crea un [LocalWindowAnimator] Choreographer-driven a 60 FPS.
     */
    fun createLocalWindowAnimator(
        onFrame: (Map<Int, android.graphics.RectF>) -> Unit
    ): com.limelight.smartdisplay.motion.LocalWindowAnimator {
        return com.limelight.smartdisplay.motion.LocalWindowAnimator(onFrame)
    }

    /**
     * Crea un [WindowOverlayView] para dibujar el overlay de animacion
     * local sobre el StreamView.
     */
    fun createWindowOverlayView(
        activity: android.app.Activity
    ): com.limelight.smartdisplay.motion.WindowOverlayView {
        return com.limelight.smartdisplay.motion.WindowOverlayView(activity)
    }

    // ── Subsistemas Clean Architecture ───────────────────────────────────

    fun createSessionPowerManager(
        context: android.content.Context
    ): com.limelight.smartdisplay.power.SessionPowerManager {
        return com.limelight.smartdisplay.power.SessionPowerManager(context)
    }

    fun createClipboardSyncManager(
        context: android.content.Context,
        sendToBus: (org.json.JSONObject) -> Unit
    ): com.limelight.smartdisplay.clipboard.ClipboardSyncManager {
        return com.limelight.smartdisplay.clipboard.ClipboardSyncManager(context, sendToBus)
    }

    fun createFileShareCoordinator(
        activity: android.app.Activity,
        sendToBus: (org.json.JSONObject) -> Unit
    ): com.limelight.smartdisplay.files.FileShareCoordinator {
        return com.limelight.smartdisplay.files.FileShareCoordinator(activity, sendToBus)
    }

    fun createScreenCaptureManager(
        activity: android.app.Activity
    ): com.limelight.smartdisplay.capture.ScreenCaptureManager {
        return com.limelight.smartdisplay.capture.ScreenCaptureManager(activity)
    }

    fun createCompanionDiscoveryReceiver(
        context: android.content.Context,
        onStopRequested: () -> Unit
    ): com.limelight.smartdisplay.connection.CompanionDiscoveryReceiver {
        return com.limelight.smartdisplay.connection.CompanionDiscoveryReceiver(context, onStopRequested)
    }

    fun createStreamKeyboardHandler(
        callback: com.limelight.smartdisplay.input.KeyboardSessionCallback,
        keyboardTranslator: com.limelight.binding.input.KeyboardTranslator,
        controllerHandler: com.limelight.binding.input.ControllerHandler?,
        connection: com.limelight.nvstream.NvConnection?
    ): com.limelight.smartdisplay.input.StreamKeyboardHandler {
        return com.limelight.smartdisplay.input.StreamKeyboardHandler(
            callback, keyboardTranslator, controllerHandler, connection
        )
    }

    fun createSessionDisplayManager(
        activity: android.app.Activity,
        streamView: com.limelight.ui.StreamView,
        prefConfig: com.limelight.preferences.PreferenceConfiguration
    ): com.limelight.smartdisplay.display.SessionDisplayManager {
        return com.limelight.smartdisplay.display.SessionDisplayManager(activity, streamView, prefConfig)
    }

    fun createSessionRecoveryUiManager(
        activity: android.app.Activity,
        streamView: android.view.View?,
        reconnectOverlayView: android.view.View?,
        reconnectStatusText: android.widget.TextView?
    ): com.limelight.smartdisplay.recovery.SessionRecoveryUiManager {
        return com.limelight.smartdisplay.recovery.SessionRecoveryUiManager(
            activity, streamView, reconnectOverlayView, reconnectStatusText
        )
    }

    fun createStreamCrashRecoveryManager(
        context: Context
    ): com.limelight.smartdisplay.recovery.StreamCrashRecoveryManager {
        return com.limelight.smartdisplay.recovery.StreamCrashRecoveryManager(context)
    }

    fun createStreamMotionInputHandler(
        prefConfig: com.limelight.preferences.PreferenceConfiguration,
        inputCaptureProvider: com.limelight.binding.input.capture.InputCaptureProvider,
        onToggleKeyboard: () -> Unit
    ): com.limelight.smartdisplay.input.StreamMotionInputHandler {
        return com.limelight.smartdisplay.input.StreamMotionInputHandler(
            prefConfig, inputCaptureProvider, onToggleKeyboard
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SharedPreferences accessors
    // ═══════════════════════════════════════════════════════════════════════

    fun companionPrefs(): SharedPreferences {
        return appContext.getSharedPreferences("smartdisplay_companion", Context.MODE_PRIVATE)
    }

    fun themePrefs(): SharedPreferences {
        return appContext.getSharedPreferences("smartdisplay_theme", Context.MODE_PRIVATE)
    }

    fun tombstonePrefs(): SharedPreferences {
        return appContext.getSharedPreferences("DecoderTombstone", Context.MODE_PRIVATE)
    }

    fun hiddenAppsPrefs(): SharedPreferences {
        return appContext.getSharedPreferences("HiddenApps", Context.MODE_PRIVATE)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ThemeManagerService — delegates to ThemeManager with additional capabilities
// ═══════════════════════════════════════════════════════════════════════════

class ThemeManagerService {

    companion object {
        const val MODE_LIGHT = "light"
        const val MODE_DARK = "dark"

        /** All available theme accent keys. */
        val ACCENT_KEYS: List<String> = listOf(
            "universe", "pixel", "emerald", "amber", "graphite", "glass", "rain"
        )

        /** Human-readable names for each accent key. */
        val ACCENT_NAMES: Map<String, String> = mapOf(
            "universe" to "Universo",
            "pixel" to "Pixel",
            "emerald" to "Esmeralda",
            "amber" to "Ámbar",
            "graphite" to "Grafito",
            "glass" to "Cristal",
            "rain" to "Lluvia"
        )
    }

    fun getCurrentThemeKey(): String {
        return com.limelight.utils.ThemeManager.getThemeKey(AppContainer.appContext())
    }

    fun setThemeKey(key: String) {
        com.limelight.utils.ThemeManager.setThemeKey(AppContainer.appContext(), key)
    }

    fun getCurrentMode(): String {
        return com.limelight.utils.ThemeManager.getMode(AppContainer.appContext())
    }

    fun setMode(mode: String) {
        com.limelight.utils.ThemeManager.setMode(AppContainer.appContext(), mode)
    }

    fun isLight(): Boolean = getCurrentMode() == MODE_LIGHT

    fun apply(activity: android.app.Activity) {
        com.limelight.utils.ThemeManager.apply(activity)
    }

    fun accentColor(): Int {
        return com.limelight.utils.ThemeManager.accentColor(AppContainer.appContext())
    }

    fun accentColorFor(themeKey: String, light: Boolean): Int {
        val styleRes = com.limelight.utils.ThemeManager.resolveStyle(themeKey, light)
        val themed = android.view.ContextThemeWrapper(AppContainer.appContext(), styleRes)
        val tv = android.util.TypedValue()
        return if (themed.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)) {
            tv.data
        } else {
            0xFF22D3EE.toInt() // cian fallback
        }
    }

    fun isGlassTheme(): Boolean = getCurrentThemeKey() == "glass"
    fun isRainTheme(): Boolean = getCurrentThemeKey() == "rain"
    fun isUniverseTheme(): Boolean = getCurrentThemeKey() == "universe"
}

// ═══════════════════════════════════════════════════════════════════════════
// CompanionConfig — centralized access to companion host/token
// ═══════════════════════════════════════════════════════════════════════════

class CompanionConfig {

    companion object {
        const val PREFS_NAME = "smartdisplay_companion"
        const val KEY_HOST = "companion_host"
        const val KEY_TOKEN = "companion_token"
    }

    /** Resolved companion host: stored address or stream host fallback. */
    fun getHost(fallback: String? = null): String? {
        val stored = AppContainer.companionPrefs().getString(KEY_HOST, null)
        if (!stored.isNullOrEmpty()) return stored
        return fallback
    }

    fun setHost(host: String) {
        AppContainer.companionPrefs().edit().putString(KEY_HOST, host).apply()
    }

    fun getPin(): String {
        return (AppContainer.appContext()
            .getSharedPreferences(AppContainer.appContext().packageName + "_preferences", Context.MODE_PRIVATE)
            .getString("companion_pin", "") ?: "").trim()
    }

    fun getToken(): String? {
        return AppContainer.companionPrefs().getString(KEY_TOKEN, null)
    }

    fun setToken(token: String) {
        AppContainer.companionPrefs().edit().putString(KEY_TOKEN, token).apply()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SessionRecoveryAccess — proxy to SessionRecoveryManager
// ═══════════════════════════════════════════════════════════════════════════

private interface SessionRecoveryAccess {
    fun hasSession(): Boolean {
        return AppContainer.instance.getSessionRecoveryManager().hasSession()
    }

    fun getHost(): String? {
        return AppContainer.instance.getSessionRecoveryManager().getHost()
    }

    fun clearSession() {
        AppContainer.instance.getSessionRecoveryManager().clearSession()
    }
}
