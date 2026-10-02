package com.limelight.preferences

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaCodecInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.preference.CheckBoxPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.preference.PreferenceScreen
import android.util.DisplayMetrics
import android.util.Range
import android.view.Display
import android.view.DisplayCutout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import com.limelight.LimeLog
import com.limelight.PcView
import com.limelight.R
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.utils.Dialog
import com.limelight.utils.ThemeManager
import com.limelight.utils.UiHelper

class StreamSettings : Activity() {
    private var previousPrefs: PreferenceConfiguration? = null
    private var previousDisplayPixelCount: Int = 0

    companion object {
        @JvmField
        var displayCutoutP: DisplayCutout? = null
    }

    fun reloadSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = windowManager.defaultDisplay.mode
            previousDisplayPixelCount = mode.physicalWidth * mode.physicalHeight
        }
        fragmentManager.beginTransaction().replace(
            R.id.stream_settings, SettingsFragment()
        ).commitAllowingStateLoss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        previousPrefs = PreferenceConfiguration.readPreferences(this)
        UiHelper.setLocale(this)
        setContentView(R.layout.activity_stream_settings)

        val btnClose = findViewById<View>(R.id.btnClose)
        btnClose?.setOnClickListener { onBackPressed() }

        UiHelper.notifyNewRootView(this)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_fade_enter, R.anim.activity_slide_out_right)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            val insets = window.decorView.rootWindowInsets
            if (insets != null) {
                displayCutoutP = insets.displayCutout
            }
        }

        reloadSettings()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = windowManager.defaultDisplay.mode
            if (mode.physicalWidth * mode.physicalHeight != previousDisplayPixelCount) {
                reloadSettings()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finish()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val newPrefs = PreferenceConfiguration.readPreferences(this)
            val prev = previousPrefs
            if (prev != null && newPrefs.language != prev.language) {
                val intent = Intent(this, PcView::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent, null)
            }
        }
    }

    class SettingsFragment : PreferenceFragment() {
        private var nativeResolutionStartIndex = Int.MAX_VALUE
        private var nativeFramerateShown = false

        private fun setValue(preferenceKey: String, value: String) {
            val pref = findPreference(preferenceKey) as? ListPreference ?: return
            pref.value = value
        }

        private fun appendPreferenceEntry(pref: ListPreference, newEntryName: String, newEntryValue: String) {
            val oldEntries = pref.entries ?: emptyArray()
            val oldValues = pref.entryValues ?: emptyArray()

            val newEntries = oldEntries.copyOf(oldEntries.size + 1)
            val newValues = oldValues.copyOf(oldValues.size + 1)

            newEntries[newEntries.size - 1] = newEntryName
            newValues[newValues.size - 1] = newEntryValue

            pref.entries = newEntries
            pref.entryValues = newValues
        }

        private fun addNativeResolutionEntry(nativeWidth: Int, nativeHeight: Int, insetsRemoved: Boolean, portrait: Boolean) {
            val pref = findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING) as? ListPreference ?: return

            var newName: String = if (insetsRemoved) {
                resources.getString(R.string.resolution_prefix_native_fullscreen)
            } else {
                resources.getString(R.string.resolution_prefix_native)
            }

            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                newName += if (portrait) {
                    " " + resources.getString(R.string.resolution_prefix_native_portrait)
                } else {
                    " " + resources.getString(R.string.resolution_prefix_native_landscape)
                }
            }

            newName += " (" + nativeWidth + "x" + nativeHeight + ")"
            val newValue = nativeWidth.toString() + "x" + nativeHeight

            val entryValues = pref.entryValues ?: emptyArray()
            for (value in entryValues) {
                if (newValue == value.toString()) {
                    return
                }
            }

            if (entryValues.size < nativeResolutionStartIndex) {
                nativeResolutionStartIndex = entryValues.size
            }
            appendPreferenceEntry(pref, newName, newValue)
        }

        private fun addNativeResolutionEntries(nativeWidth: Int, nativeHeight: Int, insetsRemoved: Boolean) {
            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                addNativeResolutionEntry(nativeHeight, nativeWidth, insetsRemoved, true)
            }
            addNativeResolutionEntry(nativeWidth, nativeHeight, insetsRemoved, false)
        }

        private fun addNativeFrameRateEntry(framerate: Float) {
            val frameRateRounded = Math.round(framerate)
            if (frameRateRounded == 0) {
                return
            }

            val pref = findPreference(PreferenceConfiguration.FPS_PREF_STRING) as? ListPreference ?: return
            val fpsValue = frameRateRounded.toString()
            val fpsName = resources.getString(R.string.resolution_prefix_native) +
                    " (" + fpsValue + " " + resources.getString(R.string.fps_suffix_fps) + ")"

            val entryValues = pref.entryValues ?: emptyArray()
            for (value in entryValues) {
                if (fpsValue == value.toString()) {
                    nativeFramerateShown = false
                    return
                }
            }

            appendPreferenceEntry(pref, fpsName, fpsValue)
            nativeFramerateShown = true
        }

        private fun removeValue(preferenceKey: String, value: String, onMatched: Runnable) {
            val pref = findPreference(preferenceKey) as? ListPreference ?: return
            val entryValues = pref.entryValues ?: emptyArray()
            val entries = pref.entries ?: emptyArray()

            var matchingCount = 0
            for (seq in entryValues) {
                if (seq.toString().equals(value, ignoreCase = true)) {
                    matchingCount++
                }
            }

            val newEntries = arrayOfNulls<CharSequence>(entries.size - matchingCount)
            val newEntryValues = arrayOfNulls<CharSequence>(entryValues.size - matchingCount)
            var outIndex = 0
            for (i in entryValues.indices) {
                if (entryValues[i].toString().equals(value, ignoreCase = true)) {
                    continue
                }
                newEntries[outIndex] = entries[i]
                newEntryValues[outIndex] = entryValues[i]
                outIndex++
            }

            if (pref.value != null && pref.value.equals(value, ignoreCase = true)) {
                onMatched.run()
            }

            pref.entries = newEntries
            pref.entryValues = newEntryValues
        }

        private fun resetBitrateToDefault(prefs: SharedPreferences, resParam: String?, fpsParam: String?) {
            val res = resParam ?: prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION) ?: PreferenceConfiguration.DEFAULT_RESOLUTION
            val fps = fpsParam ?: prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS) ?: PreferenceConfiguration.DEFAULT_FPS

            prefs.edit()
                .putInt(
                    PreferenceConfiguration.BITRATE_PREF_STRING,
                    PreferenceConfiguration.getDefaultBitrate(res, fps)
                )
                .apply()
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            val view = super.onCreateView(inflater, container, savedInstanceState)
            if (view != null) {
                UiHelper.applyStatusBarPadding(view)
            }
            return view
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            addPreferencesFromResource(R.xml.preferences)
            val screen = preferenceScreen
            val activity = activity ?: return

            if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                val category = findPreference("category_onscreen_controls") as? PreferenceCategory
                if (category != null) {
                    screen.removePreference(category)
                }
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                activity.packageManager.hasSystemFeature("com.nvidia.feature.shield")) {
                val category = findPreference("category_input_settings") as? PreferenceCategory
                val pref = findPreference("checkbox_absolute_mouse_mode")
                if (category != null && pref != null) {
                    category.removePreference(pref)
                }
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val category = findPreference("category_gamepad_settings") as? PreferenceCategory
                val pref = findPreference("checkbox_gamepad_motion_sensors")
                if (category != null && pref != null) {
                    category.removePreference(pref)
                }
            }

            if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
                !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
                val category = findPreference("category_gamepad_settings") as? PreferenceCategory
                val pref = findPreference("checkbox_gamepad_motion_fallback")
                if (category != null && pref != null) {
                    category.removePreference(pref)
                }
            }

            if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                val category = findPreference("category_gamepad_settings") as? PreferenceCategory
                val bindAll = findPreference("checkbox_usb_bind_all")
                val driver = findPreference("checkbox_usb_driver")
                if (category != null) {
                    if (bindAll != null) category.removePreference(bindAll)
                    if (driver != null) category.removePreference(driver)
                }
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !activity.packageManager.hasSystemFeature("android.software.picture_in_picture") ||
                activity.packageManager.hasSystemFeature("com.amazon.software.fireos")) {
                val category = findPreference("category_ui_settings") as? PreferenceCategory
                val pipPref = findPreference("checkbox_enable_pip")
                if (category != null && pipPref != null) {
                    category.removePreference(pipPref)
                }
            }

            val categoryGamepadSettings = findPreference("category_gamepad_settings") as? PreferenceCategory
            val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator == null || !vibrator.hasVibrator()) {
                val vibrateFallback = findPreference("checkbox_vibrate_fallback")
                val vibrateStrength = findPreference("seekbar_vibrate_fallback_strength")
                if (categoryGamepadSettings != null) {
                    if (vibrateFallback != null) categoryGamepadSettings.removePreference(vibrateFallback)
                    if (vibrateStrength != null) categoryGamepadSettings.removePreference(vibrateStrength)
                }
                val oscCategory = findPreference("category_onscreen_controls") as? PreferenceCategory
                val vibrateOsc = findPreference("checkbox_vibrate_osc")
                if (oscCategory != null && vibrateOsc != null) {
                    oscCategory.removePreference(vibrateOsc)
                }
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !vibrator.hasAmplitudeControl()) {
                val vibrateStrength = findPreference("seekbar_vibrate_fallback_strength")
                if (categoryGamepadSettings != null && vibrateStrength != null) {
                    categoryGamepadSettings.removePreference(vibrateStrength)
                }
            }

            val display = activity.windowManager.defaultDisplay
            var maxSupportedFps = display.refreshRate

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var maxSupportedResW = 0
                var hasInsets = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val cutout: DisplayCutout? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        display.cutout
                    } else {
                        displayCutoutP
                    }

                    if (cutout != null) {
                        val widthInsets = cutout.safeInsetLeft + cutout.safeInsetRight
                        val heightInsets = cutout.safeInsetBottom + cutout.safeInsetTop

                        if (widthInsets != 0 || heightInsets != 0) {
                            val metrics = DisplayMetrics()
                            display.getRealMetrics(metrics)

                            val width = Math.max(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets)
                            val height = Math.min(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets)

                            addNativeResolutionEntries(width, height, false)
                            hasInsets = true
                        }
                    }
                }

                for (candidate in display.supportedModes) {
                    val width = Math.max(candidate.physicalWidth, candidate.physicalHeight)
                    val height = Math.min(candidate.physicalWidth, candidate.physicalHeight)

                    if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                        (width > 3840 || height > 2160)) {
                        addNativeResolutionEntries(width, height, hasInsets)
                    }

                    if ((width >= 3840 || height >= 2160) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840
                    } else if ((width >= 2560 || height >= 1440) && maxSupportedResW < 2560) {
                        maxSupportedResW = 2560
                    } else if ((width >= 1920 || height >= 1080) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920
                    }

                    if (candidate.refreshRate > maxSupportedFps) {
                        maxSupportedFps = candidate.refreshRate
                    }
                }

                val ctx = context ?: activity
                MediaCodecHelper.initialize(ctx, GlPreferences.readPreferences(ctx).glRenderer)

                val avcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", -1)
                val hevcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1)

                if (avcDecoder != null) {
                    val avcWidthRange = avcDecoder.getCapabilitiesForType("video/avc").videoCapabilities.supportedWidths
                    LimeLog.info("AVC supported width range: " + avcWidthRange.lower + " - " + avcWidthRange.upper)

                    if (avcWidthRange.contains(1280)) {
                        if (avcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840
                        } else if (avcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920
                        } else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280
                        }
                    }
                }

                if (hevcDecoder != null) {
                    val hevcWidthRange = hevcDecoder.getCapabilitiesForType("video/hevc").videoCapabilities.supportedWidths
                    LimeLog.info("HEVC supported width range: " + hevcWidthRange.lower + " - " + hevcWidthRange.upper)

                    if (hevcWidthRange.contains(1280)) {
                        if (hevcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840
                        } else if (hevcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920
                        } else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280
                        }
                    }
                }

                LimeLog.info("Maximum resolution slot: $maxSupportedResW")

                if (maxSupportedResW != 0) {
                    if (maxSupportedResW < 3840) {
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_4K) {
                            val prefs = PreferenceManager.getDefaultSharedPreferences(getActivity())
                            setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P)
                            resetBitrateToDefault(prefs, null, null)
                        }
                    }
                    if (maxSupportedResW < 2560) {
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P) {
                            val prefs = PreferenceManager.getDefaultSharedPreferences(getActivity())
                            setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P)
                            resetBitrateToDefault(prefs, null, null)
                        }
                    }
                    if (maxSupportedResW < 1920) {
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P) {
                            val prefs = PreferenceManager.getDefaultSharedPreferences(getActivity())
                            setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_720P)
                            resetBitrateToDefault(prefs, null, null)
                        }
                    }
                }
            } else {
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)
                val width = Math.max(metrics.widthPixels, metrics.heightPixels)
                val height = Math.min(metrics.widthPixels, metrics.heightPixels)
                addNativeResolutionEntries(width, height, false)
            }

            if (!PreferenceConfiguration.readPreferences(activity).unlockFps) {
                if (maxSupportedFps < 118) {
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "120") {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(getActivity())
                        setValue(PreferenceConfiguration.FPS_PREF_STRING, "90")
                        resetBitrateToDefault(prefs, null, null)
                    }
                }
                if (maxSupportedFps < 88) {
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "90") {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(getActivity())
                        setValue(PreferenceConfiguration.FPS_PREF_STRING, "60")
                        resetBitrateToDefault(prefs, null, null)
                    }
                }
            }
            addNativeFrameRateEntry(maxSupportedFps)

            val unlockFpsPref = findPreference(PreferenceConfiguration.UNLOCK_FPS_STRING)
            unlockFpsPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                val h = Handler(Looper.getMainLooper())
                h.postDelayed({
                    val settingsActivity = activity as? StreamSettings
                    settingsActivity?.reloadSettings()
                }, 500)
                true
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                LimeLog.info("Excluding HDR toggle based on OS")
                val category = findPreference("category_advanced_settings") as? PreferenceCategory
                val hdrPref = findPreference("checkbox_enable_hdr")
                if (category != null && hdrPref != null) {
                    category.removePreference(hdrPref)
                }
            } else {
                val hdrCaps = display.hdrCapabilities
                var foundHdr10 = false
                if (hdrCaps != null) {
                    for (hdrType in hdrCaps.supportedHdrTypes) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            foundHdr10 = true
                            break
                        }
                    }
                }

                if (!foundHdr10) {
                    LimeLog.info("Excluding HDR toggle based on display capabilities")
                    val category = findPreference("category_advanced_settings") as? PreferenceCategory
                    val hdrPref = findPreference("checkbox_enable_hdr")
                    if (category != null && hdrPref != null) {
                        category.removePreference(hdrPref)
                    }
                } else if (PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()) {
                    LimeLog.info("Disabling HDR toggle on old broken SHIELD TV firmware")
                    val category = findPreference("category_advanced_settings") as? PreferenceCategory
                    val hdrPref = category?.findPreference("checkbox_enable_hdr") as? CheckBoxPreference
                    if (hdrPref != null) {
                        hdrPref.isEnabled = false
                        hdrPref.isChecked = false
                        hdrPref.summary = "Update the firmware on your NVIDIA SHIELD Android TV to enable HDR"
                    }
                }
            }

            val resPref = findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING)
            resPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
                val act = getActivity() ?: return@OnPreferenceChangeListener true
                val prefs = PreferenceManager.getDefaultSharedPreferences(act)
                val valueStr = newValue as String

                val values = (preference as ListPreference).entryValues ?: emptyArray()
                var isNativeRes = true
                for (i in values.indices) {
                    if (valueStr == values[i].toString() && i < nativeResolutionStartIndex) {
                        isNativeRes = false
                        break
                    }
                }

                if (isNativeRes) {
                    Dialog.displayDialog(
                        act,
                        resources.getString(R.string.title_native_res_dialog),
                        resources.getString(R.string.text_native_res_dialog),
                        false
                    )
                }

                resetBitrateToDefault(prefs, valueStr, null)
                true
            }

            val fpsPref = findPreference(PreferenceConfiguration.FPS_PREF_STRING)
            fpsPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
                val act = getActivity() ?: return@OnPreferenceChangeListener true
                val prefs = PreferenceManager.getDefaultSharedPreferences(act)
                val valueStr = newValue as String

                val values = (preference as ListPreference).entryValues ?: emptyArray()
                if (nativeFramerateShown && values.isNotEmpty() && values[values.size - 1].toString() == newValue.toString()) {
                    Dialog.displayDialog(
                        act,
                        resources.getString(R.string.title_native_fps_dialog),
                        resources.getString(R.string.text_native_res_dialog),
                        false
                    )
                }

                resetBitrateToDefault(prefs, null, valueStr)
                true
            }
        }
    }
}