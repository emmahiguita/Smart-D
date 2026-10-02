package com.limelight.smartdisplay.display

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.util.Rational
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.limelight.LimeLog
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.StreamView
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * SessionDisplayManager — Single Responsibility Principle (SRP)
 *
 * Encapsula la configuración física y visual de la pantalla:
 * - Selección de modo de refresco y resolución óptima (60Hz, 90Hz, 120Hz, 4K, TV).
 * - Integración con Picture-in-Picture (PiP) de Android Oreo / S / Tiramisu.
 * - Modo inmersivo permanente (SYSTEM_UI_FLAG_IMMERSIVE_STICKY).
 * - Soporte para Samsung SemWindowManager meta-key capture.
 */
class SessionDisplayManager(
    private val activity: Activity,
    private val streamView: StreamView,
    private val prefConfig: PreferenceConfiguration
) {
    var desiredRefreshRate: Float = 60.0f
        private set

    var isTransitioningToPip: Boolean = false
    var autoEnterPip: Boolean = false

    private val hideSystemUiRunnable = Runnable {
        applySystemUiVisibility()
    }

    fun applySystemUiVisibility() {
        val window = activity.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    fun hideSystemUi(delayMs: Int) {
        val h = activity.window?.decorView?.handler
        if (h != null) {
            h.removeCallbacks(hideSystemUiRunnable)
            h.postDelayed(hideSystemUiRunnable, delayMs.toLong())
        }
    }

    fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        val window = activity.window ?: return
        if (isInMultiWindowMode) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        hideSystemUi(50)
    }

    @Suppress("DEPRECATION")
    fun onSystemUiVisibilityChange(visibility: Int, isConnected: Boolean) {
        if (!isConnected) return
        if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 ||
            (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            hideSystemUi(2000)
        }
    }

    /**
     * Configura la orientación preferida de la pantalla según el aspecto del display
     * y las preferencias del usuario (OSC, resolución nativa, libre rotación).
     */
    @Suppress("DEPRECATION")
    fun setPreferredOrientationForCurrentDisplay() {
        val display = activity.windowManager.defaultDisplay
        if (PreferenceConfiguration.isSquarishScreen(display)) {
            var desiredOrientation = Configuration.ORIENTATION_UNDEFINED
            if (prefConfig.onscreenController) {
                desiredOrientation = Configuration.ORIENTATION_LANDSCAPE
            }
            if (PreferenceConfiguration.isNativeResolution(prefConfig.width, prefConfig.height)) {
                desiredOrientation = if (prefConfig.width > prefConfig.height) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                }
            }
            when (desiredOrientation) {
                Configuration.ORIENTATION_LANDSCAPE -> activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                Configuration.ORIENTATION_PORTRAIT -> activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                else -> activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    fun getPictureInPictureParams(autoEnter: Boolean, appName: String?, pcName: String?): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(prefConfig.width, prefConfig.height))
            .setSourceRectHint(Rect(
                streamView.left, streamView.top,
                streamView.right, streamView.bottom
            ))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (appName != null) {
                builder.setTitle(appName)
                if (pcName != null) {
                    builder.setSubtitle(pcName)
                }
            } else if (pcName != null) {
                builder.setTitle(pcName)
            }
        }

        return builder.build()
    }

    fun updatePipAutoEnter(connected: Boolean, suppressPipRefCount: Int, appName: String?, pcName: String?) {
        if (!prefConfig.enablePip) return

        val autoEnter = connected && suppressPipRefCount == 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.setPictureInPictureParams(getPictureInPictureParams(autoEnter, appName, pcName))
        } else {
            autoEnterPip = autoEnter
        }
    }

    fun enterPipMode(appName: String?, pcName: String?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return try {
                isTransitioningToPip = true
                activity.enterPictureInPictureMode(getPictureInPictureParams(false, appName, pcName))
            } catch (e: Exception) {
                isTransitioningToPip = false
                e.printStackTrace()
                false
            }
        }
        return false
    }

    fun isRefreshRateEqualMatch(refreshRate: Float): Boolean {
        return refreshRate >= prefConfig.fps && refreshRate <= prefConfig.fps + 3
    }

    fun isRefreshRateGoodMatch(refreshRate: Float): Boolean {
        return refreshRate >= prefConfig.fps && (refreshRate.roundToInt() % prefConfig.fps) <= 3
    }

    fun shouldIgnoreInsetsForResolution(width: Int, height: Int): Boolean {
        if (!PreferenceConfiguration.isNativeResolution(width, height)) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val display = activity.windowManager.defaultDisplay
            for (candidate in display.supportedModes) {
                if ((width == candidate.physicalWidth && height == candidate.physicalHeight) ||
                    (height == candidate.physicalWidth && width == candidate.physicalHeight)) {
                    return true
                }
            }
        }
        return false
    }

    fun mayReduceRefreshRate(): Boolean {
        return prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS ||
                prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED && prefConfig.reduceRefreshRate)
    }

    fun prepareDisplayForRendering(): Float {
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay
        val windowLayoutParams = activity.window.attributes
        var displayRefreshRate: Float

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var bestMode = display.mode
            val isNativeResolutionStream = PreferenceConfiguration.isNativeResolution(prefConfig.width, prefConfig.height)
            var refreshRateIsGood = isRefreshRateGoodMatch(bestMode.refreshRate)
            var refreshRateIsEqual = isRefreshRateEqualMatch(bestMode.refreshRate)

            LimeLog.info("Current display mode: ${bestMode.physicalWidth}x${bestMode.physicalHeight}x${bestMode.refreshRate}")

            for (candidate in display.supportedModes) {
                val refreshRateReduced = candidate.refreshRate < bestMode.refreshRate
                val resolutionReduced = candidate.physicalWidth < bestMode.physicalWidth ||
                        candidate.physicalHeight < bestMode.physicalHeight
                val resolutionFitsStream = candidate.physicalWidth >= prefConfig.width &&
                        candidate.physicalHeight >= prefConfig.height

                LimeLog.info("Examining display mode: ${candidate.physicalWidth}x${candidate.physicalHeight}x${candidate.refreshRate}")

                if (candidate.physicalWidth > 4096 && prefConfig.width <= 4096) {
                    continue
                }

                if (prefConfig.width < 3840 && prefConfig.fps <= 60 && !isNativeResolutionStream) {
                    if (display.mode.physicalWidth != candidate.physicalWidth ||
                        display.mode.physicalHeight != candidate.physicalHeight) {
                        continue
                    }
                }

                if (resolutionReduced && !(prefConfig.fps > 60 && resolutionFitsStream)) {
                    continue
                }

                if (mayReduceRefreshRate() && refreshRateIsEqual && !isRefreshRateEqualMatch(candidate.refreshRate)) {
                    continue
                } else if (refreshRateIsGood) {
                    if (!isRefreshRateGoodMatch(candidate.refreshRate)) {
                        continue
                    }
                    if (mayReduceRefreshRate()) {
                        if (candidate.refreshRate > bestMode.refreshRate) {
                            continue
                        }
                    } else {
                        if (refreshRateReduced) {
                            continue
                        }
                    }
                } else if (!isRefreshRateGoodMatch(candidate.refreshRate)) {
                    if (refreshRateReduced) {
                        continue
                    }
                }

                bestMode = candidate
                refreshRateIsGood = isRefreshRateGoodMatch(candidate.refreshRate)
                refreshRateIsEqual = isRefreshRateEqualMatch(candidate.refreshRate)
            }

            LimeLog.info("Best display mode: ${bestMode.physicalWidth}x${bestMode.physicalHeight}x${bestMode.refreshRate}")

            if (display.mode.modeId != bestMode.modeId) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    display.mode.physicalWidth != bestMode.physicalWidth ||
                    display.mode.physicalHeight != bestMode.physicalHeight) {
                    windowLayoutParams.preferredDisplayModeId = bestMode.modeId
                    activity.window.attributes = windowLayoutParams
                } else {
                    LimeLog.info("Using setFrameRate() instead of preferredDisplayModeId due to matching resolution")
                }
            } else {
                LimeLog.info("Current display mode is already the best display mode")
            }

            displayRefreshRate = bestMode.refreshRate
        } else {
            @Suppress("DEPRECATION")
            var bestRefreshRate = display.refreshRate
            @Suppress("DEPRECATION")
            for (candidate in display.supportedRefreshRates) {
                if (candidate > bestRefreshRate) {
                    if (prefConfig.fps <= 60 && candidate >= 63) {
                        continue
                    }
                    bestRefreshRate = candidate
                }
            }
            windowLayoutParams.preferredRefreshRate = bestRefreshRate
            displayRefreshRate = bestRefreshRate
            activity.window.attributes = windowLayoutParams
        }

        var aspectRatioMatch = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            val screenSize = Point(0, 0)
            @Suppress("DEPRECATION")
            display.getSize(screenSize)
            val screenAspectRatio = screenSize.y.toDouble() / screenSize.x
            val streamAspectRatio = prefConfig.height.toDouble() / prefConfig.width
            if (abs(screenAspectRatio - streamAspectRatio) < 0.001) {
                aspectRatioMatch = true
            }
        }

        if (prefConfig.stretchVideo || aspectRatioMatch) {
            streamView.holder.setFixedSize(prefConfig.width, prefConfig.height)
        } else {
            streamView.desiredAspectRatio = prefConfig.width.toDouble() / prefConfig.height.toDouble()
        }

        desiredRefreshRate = displayRefreshRate

        return if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            displayRefreshRate
        } else {
            @Suppress("DEPRECATION")
            display.refreshRate.coerceAtMost(displayRefreshRate)
        }
    }

    fun setMetaKeyCaptureState(enabled: Boolean) {
        try {
            val semWindowManager = Class.forName("com.samsung.android.view.SemWindowManager")
            val getInstanceMethod = semWindowManager.getMethod("getInstance")
            val manager = getInstanceMethod.invoke(null)

            if (manager != null) {
                val parameterTypes = arrayOf(ComponentName::class.java, Boolean::class.javaPrimitiveType)
                val requestMetaKeyEventMethod = semWindowManager.getDeclaredMethod("requestMetaKeyEvent", *parameterTypes)
                requestMetaKeyEventMethod.invoke(manager, activity.componentName, enabled)
            }
        } catch (ignored: Exception) {}
    }
}
