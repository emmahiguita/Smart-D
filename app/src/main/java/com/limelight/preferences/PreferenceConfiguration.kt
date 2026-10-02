package com.limelight.preferences

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.preference.PreferenceManager
import android.view.Display
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class PreferenceConfiguration {
    enum class FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
        FORCE_H264
    }

    enum class AnalogStickForScrolling {
        NONE,
        RIGHT,
        LEFT
    }

    companion object {
        private const val LEGACY_RES_FPS_PREF_STRING = "list_resolution_fps"
        private const val LEGACY_ENABLE_51_SURROUND_PREF_STRING = "checkbox_51_surround"

        const val RESOLUTION_PREF_STRING = "list_resolution"
        const val FPS_PREF_STRING = "list_fps"
        const val BITRATE_PREF_STRING = "seekbar_bitrate_kbps"
        private const val BITRATE_PREF_OLD_STRING = "seekbar_bitrate"
        private const val STRETCH_PREF_STRING = "checkbox_stretch_video"
        private const val SOPS_PREF_STRING = "checkbox_enable_sops"
        private const val DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings"
        private const val HOST_AUDIO_PREF_STRING = "checkbox_host_audio"
        private const val DEADZONE_PREF_STRING = "seekbar_deadzone"
        private const val OSC_OPACITY_PREF_STRING = "seekbar_osc_opacity"
        private const val LANGUAGE_PREF_STRING = "list_languages"
        private const val SMALL_ICONS_PREF_STRING = "checkbox_small_icon_mode"
        private const val MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller"
        const val AUDIO_CONFIG_PREF_STRING = "list_audio_config"
        private const val USB_DRIVER_PREF_SRING = "checkbox_usb_driver"
        private const val VIDEO_FORMAT_PREF_STRING = "video_format"
        private const val ONSCREEN_CONTROLLER_PREF_STRING = "checkbox_show_onscreen_controls"
        private const val ONLY_L3_R3_PREF_STRING = "checkbox_only_show_L3R3"
        private const val SHOW_GUIDE_BUTTON_PREF_STRING = "checkbox_show_guide_button"
        private const val LEGACY_DISABLE_FRAME_DROP_PREF_STRING = "checkbox_disable_frame_drop"
        private const val ENABLE_HDR_PREF_STRING = "checkbox_enable_hdr"
        private const val ENABLE_PIP_PREF_STRING = "checkbox_enable_pip"
        private const val ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay"
        private const val BIND_ALL_USB_STRING = "checkbox_usb_bind_all"
        private const val MOUSE_EMULATION_STRING = "checkbox_mouse_emulation"
        private const val ANALOG_SCROLLING_PREF_STRING = "analog_scrolling"
        private const val MOUSE_NAV_BUTTONS_STRING = "checkbox_mouse_nav_buttons"
        const val UNLOCK_FPS_STRING = "checkbox_unlock_fps"
        private const val VIBRATE_OSC_PREF_STRING = "checkbox_vibrate_osc"
        private const val VIBRATE_FALLBACK_PREF_STRING = "checkbox_vibrate_fallback"
        private const val VIBRATE_FALLBACK_STRENGTH_PREF_STRING = "seekbar_vibrate_fallback_strength"
        private const val FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons"
        private const val TOUCHSCREEN_TRACKPAD_PREF_STRING = "checkbox_touchscreen_trackpad"
        private const val LATENCY_TOAST_PREF_STRING = "checkbox_enable_post_stream_toast"
        private const val FRAME_PACING_PREF_STRING = "frame_pacing"
        private const val ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode"
        private const val ENABLE_AUDIO_FX_PREF_STRING = "checkbox_enable_audiofx"
        private const val REDUCE_REFRESH_RATE_PREF_STRING = "checkbox_reduce_refresh_rate"
        private const val FULL_RANGE_PREF_STRING = "checkbox_full_range"
        private const val GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING = "checkbox_gamepad_touchpad_as_mouse"
        private const val GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors"
        private const val GAMEPAD_MOTION_FALLBACK_PREF_STRING = "checkbox_gamepad_motion_fallback"

        const val DEFAULT_RESOLUTION = "1280x720"
        const val DEFAULT_FPS = "60"
        private const val DEFAULT_STRETCH = false
        private const val DEFAULT_SOPS = true
        private const val DEFAULT_DISABLE_TOASTS = false
        private const val DEFAULT_HOST_AUDIO = false
        private const val DEFAULT_DEADZONE = 7
        private const val DEFAULT_OPACITY = 90
        const val DEFAULT_LANGUAGE = "default"
        private const val DEFAULT_MULTI_CONTROLLER = true
        private const val DEFAULT_USB_DRIVER = true
        private const val DEFAULT_VIDEO_FORMAT = "auto"

        private const val ONSCREEN_CONTROLLER_DEFAULT = false
        private const val ONLY_L3_R3_DEFAULT = false
        private const val SHOW_GUIDE_BUTTON_DEFAULT = true
        private const val DEFAULT_ENABLE_HDR = false
        private const val DEFAULT_ENABLE_PIP = false
        private const val DEFAULT_ENABLE_PERF_OVERLAY = false
        private const val DEFAULT_BIND_ALL_USB = false
        private const val DEFAULT_MOUSE_EMULATION = true
        private const val DEFAULT_ANALOG_STICK_FOR_SCROLLING = "right"
        private const val DEFAULT_MOUSE_NAV_BUTTONS = false
        private const val DEFAULT_UNLOCK_FPS = false
        private const val DEFAULT_VIBRATE_OSC = true
        private const val DEFAULT_VIBRATE_FALLBACK = false
        private const val DEFAULT_VIBRATE_FALLBACK_STRENGTH = 100
        private const val DEFAULT_FLIP_FACE_BUTTONS = false
        private const val DEFAULT_TOUCHSCREEN_TRACKPAD = true
        private const val DEFAULT_AUDIO_CONFIG = "2" // Stereo
        private const val DEFAULT_LATENCY_TOAST = false
        private const val DEFAULT_FRAME_PACING = "latency"
        private const val DEFAULT_ABSOLUTE_MOUSE_MODE = true
        private const val DEFAULT_ENABLE_AUDIO_FX = false
        private const val DEFAULT_REDUCE_REFRESH_RATE = false
        private const val DEFAULT_FULL_RANGE = false
        private const val DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false
        private const val DEFAULT_GAMEPAD_MOTION_SENSORS = true
        private const val DEFAULT_GAMEPAD_MOTION_FALLBACK = false

        const val FRAME_PACING_MIN_LATENCY = 0
        const val FRAME_PACING_BALANCED = 1
        const val FRAME_PACING_CAP_FPS = 2
        const val FRAME_PACING_MAX_SMOOTHNESS = 3

        const val RES_360P = "640x360"
        const val RES_480P = "854x480"
        const val RES_720P = "1280x720"
        const val RES_1080P = "1920x1080"
        const val RES_1440P = "2560x1440"
        const val RES_4K = "3840x2160"
        const val RES_NATIVE = "Native"

        @JvmStatic
        fun isNativeResolution(width: Int, height: Int): Boolean {
            if (width == 640 && height == 360) return false
            if (width == 854 && height == 480) return false
            if (width == 1280 && height == 720) return false
            if (width == 1920 && height == 1080) return false
            if (width == 2560 && height == 1440) return false
            if (width == 3840 && height == 2160) return false
            return true
        }

        @JvmStatic
        fun isSquarishScreen(width: Int, height: Int): Boolean {
            val longDim = max(width.toDouble(), height.toDouble()).toFloat()
            val shortDim = min(width.toDouble(), height.toDouble()).toFloat()
            return longDim / shortDim < 1.3f
        }

        @JvmStatic
        fun isSquarishScreen(display: Display): Boolean {
            val width: Int
            val height: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                width = display.mode.physicalWidth
                height = display.mode.physicalHeight
            } else {
                @Suppress("DEPRECATION")
                width = display.width
                @Suppress("DEPRECATION")
                height = display.height
            }
            return isSquarishScreen(width, height)
        }

        @JvmStatic
        fun convertFromLegacyResolutionString(resString: String): String {
            return when {
                resString.equals("360p", ignoreCase = true) -> RES_360P
                resString.equals("480p", ignoreCase = true) -> RES_480P
                resString.equals("720p", ignoreCase = true) -> RES_720P
                resString.equals("1080p", ignoreCase = true) -> RES_1080P
                resString.equals("1440p", ignoreCase = true) -> RES_1440P
                resString.equals("4K", ignoreCase = true) -> RES_4K
                else -> RES_720P
            }
        }

        @JvmStatic
        fun getWidthFromResolutionString(resString: String): Int {
            return resString.split("x")[0].toInt()
        }

        @JvmStatic
        fun getHeightFromResolutionString(resString: String): Int {
            return resString.split("x")[1].toInt()
        }

        @JvmStatic
        fun getResolutionString(width: Int, height: Int): String {
            return when (height) {
                360 -> RES_360P
                480 -> RES_480P
                1080 -> RES_1080P
                1440 -> RES_1440P
                2160 -> RES_4K
                else -> RES_720P
            }
        }

        @JvmStatic
        fun getDefaultBitrate(resString: String, fpsString: String): Int {
            val width = getWidthFromResolutionString(resString)
            val height = getHeightFromResolutionString(resString)
            val fps = fpsString.toInt()

            val frameRateFactor = (if (fps <= 60) fps.toDouble() else sqrt(fps / 60.0) * 60.0) / 30.0

            val pixelVals = intArrayOf(
                640 * 360,
                854 * 480,
                1280 * 720,
                1920 * 1080,
                2560 * 1440,
                3840 * 2160,
                -1
            )
            val factorVals = intArrayOf(
                1,
                2,
                5,
                10,
                20,
                40,
                -1
            )

            val resolutionFactor: Float
            val pixels = width * height
            var i = 0
            while (true) {
                if (pixels == pixelVals[i]) {
                    resolutionFactor = factorVals[i].toFloat()
                    break
                } else if (pixels < pixelVals[i]) {
                    resolutionFactor = if (i == 0) {
                        factorVals[i].toFloat()
                    } else {
                        ((pixels - pixelVals[i - 1]).toFloat() / (pixelVals[i] - pixelVals[i - 1])) *
                                (factorVals[i] - factorVals[i - 1]) + factorVals[i - 1]
                    }
                    break
                } else if (pixelVals[i] == -1) {
                    resolutionFactor = factorVals[i - 1].toFloat()
                    break
                }
                i++
            }

            return (resolutionFactor * frameRateFactor).roundToInt() * 1000
        }

        @JvmStatic
        fun getDefaultSmallMode(context: Context): Boolean {
            val manager = context.packageManager
            if (manager != null) {
                if (manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                    return false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    if (manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                        return false
                    }
                }
            }
            return context.resources.configuration.smallestScreenWidthDp < 500
        }

        @JvmStatic
        fun getDefaultBitrate(context: Context): Int {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return getDefaultBitrate(
                prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION,
                prefs.getString(FPS_PREF_STRING, DEFAULT_FPS) ?: DEFAULT_FPS
            )
        }

        private fun getVideoFormatValue(context: Context): FormatOption {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return when (prefs.getString(VIDEO_FORMAT_PREF_STRING, DEFAULT_VIDEO_FORMAT)) {
                "auto" -> FormatOption.AUTO
                "forceav1" -> FormatOption.FORCE_AV1
                "forceh265" -> FormatOption.FORCE_HEVC
                "neverh265" -> FormatOption.FORCE_H264
                else -> FormatOption.AUTO
            }
        }

        private fun getFramePacingValue(context: Context): Int {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)

            if (prefs.contains(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)) {
                val legacyNeverDropFrames = prefs.getBoolean(LEGACY_DISABLE_FRAME_DROP_PREF_STRING, false)
                prefs.edit()
                    .remove(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)
                    .putString(FRAME_PACING_PREF_STRING, if (legacyNeverDropFrames) "balanced" else "latency")
                    .apply()
            }

            return when (prefs.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING)) {
                "latency" -> FRAME_PACING_MIN_LATENCY
                "balanced" -> FRAME_PACING_BALANCED
                "cap-fps" -> FRAME_PACING_CAP_FPS
                "smoothness" -> FRAME_PACING_MAX_SMOOTHNESS
                else -> FRAME_PACING_MIN_LATENCY
            }
        }

        private fun getAnalogStickForScrollingValue(context: Context): AnalogStickForScrolling {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return when (prefs.getString(ANALOG_SCROLLING_PREF_STRING, DEFAULT_ANALOG_STICK_FOR_SCROLLING)) {
                "right" -> AnalogStickForScrolling.RIGHT
                "left" -> AnalogStickForScrolling.LEFT
                else -> AnalogStickForScrolling.NONE
            }
        }

        @JvmStatic
        fun resetStreamingSettings(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit()
                .remove(BITRATE_PREF_STRING)
                .remove(BITRATE_PREF_OLD_STRING)
                .remove(LEGACY_RES_FPS_PREF_STRING)
                .remove(RESOLUTION_PREF_STRING)
                .remove(FPS_PREF_STRING)
                .remove(VIDEO_FORMAT_PREF_STRING)
                .remove(ENABLE_HDR_PREF_STRING)
                .remove(UNLOCK_FPS_STRING)
                .remove(FULL_RANGE_PREF_STRING)
                .apply()
        }

        @JvmStatic
        fun completeLanguagePreferenceMigration(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE).apply()
        }

        @JvmStatic
        fun isShieldAtvFirmwareWithBrokenHdr(): Boolean {
            return Build.MANUFACTURER.equals("NVIDIA", ignoreCase = true) &&
                    Build.FINGERPRINT.contains("PPR1.180610.011/4079208_2235.1395")
        }

        @JvmStatic
        fun readPreferences(context: Context): PreferenceConfiguration {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val config = PreferenceConfiguration()

            if (prefs.contains(LEGACY_ENABLE_51_SURROUND_PREF_STRING)) {
                if (prefs.getBoolean(LEGACY_ENABLE_51_SURROUND_PREF_STRING, false)) {
                    prefs.edit()
                        .remove(LEGACY_ENABLE_51_SURROUND_PREF_STRING)
                        .putString(AUDIO_CONFIG_PREF_STRING, "51")
                        .apply()
                }
            }

            val str = prefs.getString(LEGACY_RES_FPS_PREF_STRING, null)
            if (str != null) {
                when (str) {
                    "360p30" -> { config.width = 640; config.height = 360; config.fps = 30 }
                    "360p60" -> { config.width = 640; config.height = 360; config.fps = 60 }
                    "720p30" -> { config.width = 1280; config.height = 720; config.fps = 30 }
                    "720p60" -> { config.width = 1280; config.height = 720; config.fps = 60 }
                    "1080p30" -> { config.width = 1920; config.height = 1080; config.fps = 30 }
                    "1080p60" -> { config.width = 1920; config.height = 1080; config.fps = 60 }
                    "4K30" -> { config.width = 3840; config.height = 2160; config.fps = 30 }
                    "4K60" -> { config.width = 3840; config.height = 2160; config.fps = 60 }
                    else -> { config.width = 1280; config.height = 720; config.fps = 60 }
                }

                prefs.edit()
                    .remove(LEGACY_RES_FPS_PREF_STRING)
                    .putString(RESOLUTION_PREF_STRING, getResolutionString(config.width, config.height))
                    .putString(FPS_PREF_STRING, "" + config.fps)
                    .apply()
            } else {
                var resStr = prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION
                if (!resStr.contains("x")) {
                    resStr = convertFromLegacyResolutionString(resStr)
                    prefs.edit().putString(RESOLUTION_PREF_STRING, resStr).apply()
                }

                config.width = getWidthFromResolutionString(resStr)
                config.height = getHeightFromResolutionString(resStr)
                config.fps = (prefs.getString(FPS_PREF_STRING, DEFAULT_FPS) ?: DEFAULT_FPS).toInt()
            }

            if (!prefs.contains(SMALL_ICONS_PREF_STRING)) {
                prefs.edit().putBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context)).apply()
            }

            if (!prefs.contains(GAMEPAD_MOTION_SENSORS_PREF_STRING) && Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
                prefs.edit().putBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, false).apply()
            }

            config.bitrate = prefs.getInt(BITRATE_PREF_STRING, prefs.getInt(BITRATE_PREF_OLD_STRING, 0) * 1000)
            if (config.bitrate == 0) {
                config.bitrate = getDefaultBitrate(context)
            }

            val audioConfig = prefs.getString(AUDIO_CONFIG_PREF_STRING, DEFAULT_AUDIO_CONFIG)
            config.audioConfiguration = when (audioConfig) {
                "71" -> MoonBridge.AUDIO_CONFIGURATION_71_SURROUND
                "51" -> MoonBridge.AUDIO_CONFIGURATION_51_SURROUND
                else -> MoonBridge.AUDIO_CONFIGURATION_STEREO
            }

            config.videoFormat = getVideoFormatValue(context)
            config.framePacing = getFramePacingValue(context)
            config.analogStickForScrolling = getAnalogStickForScrollingValue(context)
            config.deadzonePercentage = prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE)
            config.oscOpacity = prefs.getInt(OSC_OPACITY_PREF_STRING, DEFAULT_OPACITY)
            config.language = prefs.getString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE

            config.disableWarnings = prefs.getBoolean(DISABLE_TOASTS_PREF_STRING, DEFAULT_DISABLE_TOASTS)
            config.enableSops = prefs.getBoolean(SOPS_PREF_STRING, DEFAULT_SOPS)
            config.stretchVideo = prefs.getBoolean(STRETCH_PREF_STRING, DEFAULT_STRETCH)
            config.playHostAudio = prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO)
            config.smallIconMode = prefs.getBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context))
            config.multiController = prefs.getBoolean(MULTI_CONTROLLER_PREF_STRING, DEFAULT_MULTI_CONTROLLER)
            config.usbDriver = prefs.getBoolean(USB_DRIVER_PREF_SRING, DEFAULT_USB_DRIVER)
            config.onscreenController = prefs.getBoolean(ONSCREEN_CONTROLLER_PREF_STRING, ONSCREEN_CONTROLLER_DEFAULT)
            config.onlyL3R3 = prefs.getBoolean(ONLY_L3_R3_PREF_STRING, ONLY_L3_R3_DEFAULT)
            config.showGuideButton = prefs.getBoolean(SHOW_GUIDE_BUTTON_PREF_STRING, SHOW_GUIDE_BUTTON_DEFAULT)
            config.enableHdr = prefs.getBoolean(ENABLE_HDR_PREF_STRING, DEFAULT_ENABLE_HDR) && !isShieldAtvFirmwareWithBrokenHdr()
            config.enablePip = prefs.getBoolean(ENABLE_PIP_PREF_STRING, DEFAULT_ENABLE_PIP)
            config.enablePerfOverlay = prefs.getBoolean(ENABLE_PERF_OVERLAY_STRING, DEFAULT_ENABLE_PERF_OVERLAY)
            config.bindAllUsb = prefs.getBoolean(BIND_ALL_USB_STRING, DEFAULT_BIND_ALL_USB)
            config.mouseEmulation = prefs.getBoolean(MOUSE_EMULATION_STRING, DEFAULT_MOUSE_EMULATION)
            config.mouseNavButtons = prefs.getBoolean(MOUSE_NAV_BUTTONS_STRING, DEFAULT_MOUSE_NAV_BUTTONS)
            config.unlockFps = prefs.getBoolean(UNLOCK_FPS_STRING, DEFAULT_UNLOCK_FPS)
            config.vibrateOsc = prefs.getBoolean(VIBRATE_OSC_PREF_STRING, DEFAULT_VIBRATE_OSC)
            config.vibrateFallbackToDevice = prefs.getBoolean(VIBRATE_FALLBACK_PREF_STRING, DEFAULT_VIBRATE_FALLBACK)
            config.vibrateFallbackToDeviceStrength = prefs.getInt(VIBRATE_FALLBACK_STRENGTH_PREF_STRING, DEFAULT_VIBRATE_FALLBACK_STRENGTH)
            config.flipFaceButtons = prefs.getBoolean(FLIP_FACE_BUTTONS_PREF_STRING, DEFAULT_FLIP_FACE_BUTTONS)
            config.touchscreenTrackpad = prefs.getBoolean(TOUCHSCREEN_TRACKPAD_PREF_STRING, DEFAULT_TOUCHSCREEN_TRACKPAD)
            config.enableLatencyToast = prefs.getBoolean(LATENCY_TOAST_PREF_STRING, DEFAULT_LATENCY_TOAST)
            config.absoluteMouseMode = true
            config.enableAudioFx = prefs.getBoolean(ENABLE_AUDIO_FX_PREF_STRING, DEFAULT_ENABLE_AUDIO_FX)
            config.reduceRefreshRate = prefs.getBoolean(REDUCE_REFRESH_RATE_PREF_STRING, DEFAULT_REDUCE_REFRESH_RATE)
            config.fullRange = prefs.getBoolean(FULL_RANGE_PREF_STRING, DEFAULT_FULL_RANGE)
            config.gamepadTouchpadAsMouse = prefs.getBoolean(GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING, DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE)
            config.gamepadMotionSensors = prefs.getBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, DEFAULT_GAMEPAD_MOTION_SENSORS)
            config.gamepadMotionSensorsFallbackToDevice = prefs.getBoolean(GAMEPAD_MOTION_FALLBACK_PREF_STRING, DEFAULT_GAMEPAD_MOTION_FALLBACK)

            return config
        }
    }

    @JvmField var width: Int = 0
    @JvmField var height: Int = 0
    @JvmField var fps: Int = 0
    @JvmField var bitrate: Int = 0
    @JvmField var videoFormat: FormatOption = FormatOption.AUTO
    @JvmField var deadzonePercentage: Int = 0
    @JvmField var oscOpacity: Int = 0
    @JvmField var stretchVideo: Boolean = false
    @JvmField var enableSops: Boolean = false
    @JvmField var playHostAudio: Boolean = false
    @JvmField var disableWarnings: Boolean = false
    @JvmField var language: String = DEFAULT_LANGUAGE
    @JvmField var smallIconMode: Boolean = false
    @JvmField var multiController: Boolean = false
    @JvmField var usbDriver: Boolean = false
    @JvmField var flipFaceButtons: Boolean = false
    @JvmField var onscreenController: Boolean = false
    @JvmField var onlyL3R3: Boolean = false
    @JvmField var showGuideButton: Boolean = false
    @JvmField var enableHdr: Boolean = false
    @JvmField var enablePip: Boolean = false
    @JvmField var enablePerfOverlay: Boolean = false
    @JvmField var enableLatencyToast: Boolean = false
    @JvmField var bindAllUsb: Boolean = false
    @JvmField var mouseEmulation: Boolean = false
    @JvmField var analogStickForScrolling: AnalogStickForScrolling = AnalogStickForScrolling.NONE
    @JvmField var mouseNavButtons: Boolean = false
    @JvmField var unlockFps: Boolean = false
    @JvmField var vibrateOsc: Boolean = false
    @JvmField var vibrateFallbackToDevice: Boolean = false
    @JvmField var vibrateFallbackToDeviceStrength: Int = 0
    @JvmField var touchscreenTrackpad: Boolean = false
    @JvmField var audioConfiguration: MoonBridge.AudioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO
    @JvmField var framePacing: Int = 0
    @JvmField var absoluteMouseMode: Boolean = true
    @JvmField var enableAudioFx: Boolean = false
    @JvmField var reduceRefreshRate: Boolean = false
    @JvmField var fullRange: Boolean = false
    @JvmField var gamepadMotionSensors: Boolean = false
    @JvmField var gamepadTouchpadAsMouse: Boolean = false
    @JvmField var gamepadMotionSensorsFallbackToDevice: Boolean = false
}
