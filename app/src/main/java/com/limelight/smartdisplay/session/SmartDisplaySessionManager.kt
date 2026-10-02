package com.limelight.smartdisplay.session

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.preference.PreferenceManager
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.smartdisplay.AppContainer
import com.limelight.smartdisplay.TextFocusWatcher
import com.limelight.smartdisplay.bus.SmartDisplayBus
import com.limelight.smartdisplay.capture.ScreenCaptureManager
import com.limelight.smartdisplay.clipboard.ClipboardSyncManager
import com.limelight.smartdisplay.connection.CompanionConfig
import com.limelight.smartdisplay.connection.CompanionDiscoveryReceiver
import com.limelight.smartdisplay.context.CursorProfileRegistry
import com.limelight.smartdisplay.context.ForegroundAppRegistry
import com.limelight.smartdisplay.files.FileShareCoordinator
import com.limelight.smartdisplay.input.StreamMotionInputHandler
import com.limelight.smartdisplay.motion.WindowMotionCoordinator
import com.limelight.smartdisplay.voice.VoiceCaptureManager
import com.limelight.ui.AdaptiveCursorView
import com.limelight.ui.FileBrowserController
import com.limelight.ui.LogicalKeyboardOverlay
import com.limelight.ui.PortraitHybridController
import com.limelight.ui.SmartCursorEngine
import com.limelight.ui.SmartTaskbarController
import org.json.JSONObject
import java.io.File

/**
 * SmartDisplaySessionManager — Encapsula todo el ciclo de vida y orquestación
 * de la sesión inteligente con el PC Companion:
 *  - SmartDisplayBus (WebSocket)
 *  - TextFocusWatcher (detección remota de campos de texto e I-beam)
 *  - VoiceCaptureManager (audio bidireccional de baja latencia)
 *  - ClipboardSyncManager (sincronización bidireccional)
 *  - FileShareCoordinator & FileBrowser (transferencia WiFi)
 *  - ScreenCaptureManager (envío de pantalla a desktop)
 *  - WindowMotionCoordinator (interpolación fluida de ventanas)
 *  - CompanionDiscoveryReceiver (detección dinámica de IP/Tailscale)
 */
class SmartDisplaySessionManager(
    private val activity: Activity,
    private val streamView: SurfaceView,
    private val prefConfig: PreferenceConfiguration?,
    private val transformWrapper: View?,
    private val adaptiveCursor: AdaptiveCursorView?,
    private val smartCursorEngine: SmartCursorEngine?,
    private val logicalKeyboard: LogicalKeyboardOverlay?,
    private val portraitHybridCtrl: PortraitHybridController?,
    private val smartTaskbar: SmartTaskbarController?,
    private val fileBrowser: FileBrowserController?,
    private val streamMotionInputHandler: StreamMotionInputHandler?
) {

    var smartDisplayBus: SmartDisplayBus? = null
        private set
    var textFocusWatcher: TextFocusWatcher? = null
        private set
    var voiceManager: VoiceCaptureManager? = null
        private set
    var clipboardSyncManager: ClipboardSyncManager? = null
        private set
    var fileShareCoordinator: FileShareCoordinator? = null
        private set
    var screenCaptureManager: ScreenCaptureManager? = null
        private set
    var companionDiscoveryReceiver: CompanionDiscoveryReceiver? = null
        private set
    var windowMotionCoordinator: WindowMotionCoordinator? = null
        private set

    fun start(host: String?) {
        if (host.isNullOrEmpty()) return

        companionDiscoveryReceiver = AppContainer.createCompanionDiscoveryReceiver(activity) {
            screenCaptureManager?.stopService()
        }
        companionDiscoveryReceiver?.register()

        screenCaptureManager = AppContainer.createScreenCaptureManager(activity)

        var companionHost = CompanionConfig().getHost()
        if (companionHost.isNullOrEmpty()) companionHost = host

        val companionPin = PreferenceManager.getDefaultSharedPreferences(activity)
            .getString("companion_pin", "")?.trim() ?: ""

        voiceManager = VoiceCaptureManager(companionHost, companionPin)

        fileBrowser?.setCompanionHost(companionHost, companionPin)

        smartDisplayBus = SmartDisplayBus(companionHost, object : SmartDisplayBus.MessageListener {
            override fun onMessageReceived(json: JSONObject) {
                when (json.optString("type", "")) {
                    "clipboard" -> {
                        val text = json.optString("text", "")
                        activity.runOnUiThread {
                            clipboardSyncManager?.applyRemoteClipboard(text)
                        }
                    }
                    "foreground_app" -> {
                        val process = json.optString("process", "")
                        val title = json.optString("title", "")
                        if (process.isNotEmpty()) {
                            ForegroundAppRegistry.update(process, title)
                        }
                    }
                    "cursor_profile" -> {
                        val app = json.optString("app", "")
                        val sens = json.optDouble("sensitivity", 1.0)
                        val prec = json.optBoolean("precision", false)
                        val mag = json.optDouble("magnetism", 0.15)
                        CursorProfileRegistry.update(app, sens.toFloat(), prec, mag.toFloat())
                    }
                    "windows" -> {
                        val wins = json.optJSONArray("windows")
                        activity.runOnUiThread {
                            smartTaskbar?.render(wins)
                        }
                    }
                    "window_moved" -> {
                        val hwnd = json.optInt("hwnd", 0)
                        val x = json.optDouble("x", 0.0).toFloat()
                        val y = json.optDouble("y", 0.0).toFloat()
                        val w = json.optDouble("w", 0.0).toFloat()
                        val h = json.optDouble("h", 0.0).toFloat()
                        activity.runOnUiThread {
                            windowMotionCoordinator?.onWindowMoved(hwnd, x, y, w, h)
                        }
                    }
                }
            }

            override fun onConnected() {
                LimeLog.info("SD_Bus: Bus de comunicación conectado")
            }

            override fun onDisconnected() {
                LimeLog.info("SD_Bus: Bus de comunicación desconectado")
            }
        }, companionPin).apply { start() }

        clipboardSyncManager = AppContainer.createClipboardSyncManager(activity) { json ->
            smartDisplayBus?.sendMessage(json)
        }.apply { start() }

        fileShareCoordinator = AppContainer.createFileShareCoordinator(activity) { json ->
            smartDisplayBus?.sendMessage(json)
        }

        val pcWidth = if (prefConfig != null && prefConfig.width > 0) prefConfig.width else 1920
        val pcHeight = if (prefConfig != null && prefConfig.height > 0) prefConfig.height else 1080
        val bus = smartDisplayBus
        if (bus != null) {
            windowMotionCoordinator = AppContainer.createWindowMotionCoordinator(
                bus, streamView, activity, pcWidth, pcHeight
            ).apply {
                (streamView.parent as? ViewGroup)?.let { attachToContainer(it) }
            }
            streamMotionInputHandler?.windowMotionCoordinator = windowMotionCoordinator
        }

        textFocusWatcher = TextFocusWatcher("ws://$companionHost:8765", object : TextFocusWatcher.Callback {
            override fun onTextFieldFocused(x: Float, y: Float, w: Float, h: Float, app: String) {
                LimeLog.info("TextFocusWatcher: Foco en campo de texto. App: $app")
                if (logicalKeyboard != null && !logicalKeyboard.isVisible()) {
                    logicalKeyboard.show()
                }
                portraitHybridCtrl?.updateSmartBarForApp(app)

                if (adaptiveCursor != null && prefConfig != null && prefConfig.width > 0 && prefConfig.height > 0) {
                    val scaleX = streamView.width.toFloat() / prefConfig.width
                    val scaleY = streamView.height.toFloat() / prefConfig.height
                    val localX = streamView.x + (x * scaleX)
                    val localY = streamView.y + (y * scaleY)
                    val localW = w * scaleX
                    val localH = h * scaleY

                    adaptiveCursor.setFocusHighlight(localX, localY, localW, localH)
                    smartCursorEngine?.onHoverElementChanged(SmartCursorEngine.ElementType.TEXT_INPUT)
                }
            }

            override fun onCursorPos(x: Float, y: Float) {
                if (adaptiveCursor == null || prefConfig == null || prefConfig.width <= 0 || prefConfig.height <= 0) return
                val scaleX = streamView.width.toFloat() / prefConfig.width
                val scaleY = streamView.height.toFloat() / prefConfig.height
                var localX = streamView.x + (x * scaleX)
                var localY = streamView.y + (y * scaleY)

                val wrapper = transformWrapper
                if (wrapper != null && wrapper.width > 0) {
                    val pivotX = wrapper.width / 2f
                    val pivotY = wrapper.height / 2f
                    localX = pivotX + (localX - pivotX) * wrapper.scaleX + wrapper.translationX
                    localY = pivotY + (localY - pivotY) * wrapper.scaleY + wrapper.translationY
                }
                val nowMs = SystemClock.elapsedRealtime()
                streamMotionInputHandler?.lastCursorPosMs = nowMs
                adaptiveCursor.moveTo(localX, localY)
            }

            override fun onTextFieldCleared() {
                LimeLog.info("TextFocusWatcher: Foco fuera de campo de texto")
                adaptiveCursor?.setFocusHighlight(0f, 0f, 0f, 0f)
                smartCursorEngine?.onHoverElementChanged(SmartCursorEngine.ElementType.NONE)
            }

            override fun onHoverElement(
                element: String,
                x: Float,
                y: Float,
                w: Float,
                h: Float,
                snapX: Float,
                snapY: Float,
                hasSnap: Boolean,
                app: String
            ) {
                val engine = smartCursorEngine ?: return
                val type = when (element) {
                    "text_input" -> SmartCursorEngine.ElementType.TEXT_INPUT
                    "button" -> SmartCursorEngine.ElementType.BUTTON
                    "link" -> SmartCursorEngine.ElementType.LINK
                    "ide_workspace" -> SmartCursorEngine.ElementType.IDE_WORKSPACE
                    else -> SmartCursorEngine.ElementType.NONE
                }
                engine.onHoverElementChanged(type)

                if (hasSnap && adaptiveCursor != null && prefConfig != null && prefConfig.width > 0 && prefConfig.height > 0) {
                    val scaleX = streamView.width.toFloat() / prefConfig.width
                    val scaleY = streamView.height.toFloat() / prefConfig.height
                    val localSnapX = streamView.x + (snapX * scaleX)
                    val localSnapY = streamView.y + (snapY * scaleY)
                    engine.evaluateSmartSnap(
                        adaptiveCursor.getCursorX(), adaptiveCursor.getCursorY(),
                        localSnapX, localSnapY
                    )
                }
            }

            override fun onConnectionStatus(connected: Boolean) {
                LimeLog.info("TextFocusWatcher: Conexión ${if (connected) "establecida" else "perdida"}")
            }
        }, companionPin).apply { connect() }
    }

    fun sendBusCommand(type: String, hwnd: Int?) {
        val bus = smartDisplayBus ?: return
        try {
            val j = JSONObject().apply {
                put("type", type)
                if (hwnd != null) put("hwnd", hwnd.toInt())
            }
            bus.sendMessage(j)
        } catch (ignored: Exception) {}
    }

    fun toggleVoice() {
        toggleVoiceWithAudioFeedback(null)
    }

    fun toggleVoiceWithAudioFeedback(audioRenderer: com.limelight.binding.audio.AndroidAudioRenderer?) {
        val vm = voiceManager
        if (vm != null) {
            if (vm.checkPermissions(activity, 100)) {
                if (vm.isRecording()) {
                    vm.stop()
                    audioRenderer?.setMuted(false)
                    Toast.makeText(activity, R.string.voice_deactivated, Toast.LENGTH_SHORT).show()
                } else {
                    audioRenderer?.setMuted(true)
                    vm.start()
                    Toast.makeText(activity, R.string.voice_activated, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(activity, R.string.voice_manager_not_ready, Toast.LENGTH_SHORT).show()
        }
    }

    fun handleAudioPermissionResult(
        grantResults: IntArray,
        audioRenderer: com.limelight.binding.audio.AndroidAudioRenderer?
    ) {
        if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!isVoiceRecording()) {
                audioRenderer?.setMuted(true)
                startVoiceIfAllowed()
                Toast.makeText(activity, R.string.voice_activated, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(activity, R.string.voice_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    fun openFileBrowser() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
            && !android.os.Environment.isExternalStorageManager()
        ) {
            Toast.makeText(
                activity,
                "Concede una vez 'Acceso a todos los archivos' para enviar sin salir de la proyección",
                Toast.LENGTH_LONG
            ).show()
            try {
                activity.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + activity.packageName)
                    )
                )
            } catch (e: Exception) {
                try {
                    activity.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                } catch (ignored: Exception) {}
            }
            return
        }
        ensureFileServerStarted()
        fileBrowser?.show()
    }

    fun startVoiceIfAllowed() {
        val vm = voiceManager
        if (vm != null && !vm.isRecording() && vm.checkPermissions(activity, 100)) {
            vm.start()
        }
    }

    fun stopVoice() {
        voiceManager?.stop()
    }

    fun isVoiceRecording(): Boolean = voiceManager?.isRecording() == true

    fun snapWindow(hwnd: Int, zone: String) {
        windowMotionCoordinator?.snapWindow(hwnd, zone)
    }

    fun startWindowDrag(hwnd: Int, winX: Float, winY: Float, winW: Float, winH: Float) {
        val coord = windowMotionCoordinator ?: return
        val pcW = if (prefConfig != null && prefConfig.width > 0) prefConfig.width else 1920
        val pcH = if (prefConfig != null && prefConfig.height > 0) prefConfig.height else 1080
        coord.updateResolution(pcW, pcH)
        coord.startDrag(hwnd, winX, winY, winW, winH)
        Toast.makeText(activity, "Desliza con el dedo para mover la ventana", Toast.LENGTH_SHORT).show()
    }

    fun ensureFileServerStarted() {
        fileShareCoordinator?.ensureServerStarted()
    }

    fun sendPickedFiles(files: List<File>) {
        fileShareCoordinator?.sendPickedFiles(files)
    }

    fun shareFilesViaFab() {
        fileShareCoordinator?.launchSystemFilePicker()
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == ScreenCaptureManager.REQ_SCREEN_CAPTURE) {
            screenCaptureManager?.onActivityResult(resultCode, data)
            return true
        }
        if (requestCode == FileShareCoordinator.REQ_FAB_FILE_SHARE) {
            if (resultCode == Activity.RESULT_OK) {
                fileShareCoordinator?.handleActivityResult(data)
            }
            return true
        }
        return false
    }

    fun destroy() {
        voiceManager?.stop()
        voiceManager = null

        smartDisplayBus?.let {
            try {
                val restoreAudioMsg = JSONObject().apply {
                    put("type", "restore_audio")
                }
                it.sendMessage(restoreAudioMsg)
            } catch (ignored: Exception) {}
            it.stop()
        }
        smartDisplayBus = null

        ForegroundAppRegistry.reset()
        CursorProfileRegistry.reset()

        fileShareCoordinator?.release()
        fileShareCoordinator = null

        clipboardSyncManager?.release()
        clipboardSyncManager = null

        companionDiscoveryReceiver?.unregister()
        companionDiscoveryReceiver = null

        screenCaptureManager?.stopService()
        screenCaptureManager = null

        windowMotionCoordinator?.release()
        windowMotionCoordinator = null

        textFocusWatcher?.disconnect()
        textFocusWatcher = null
    }
}
