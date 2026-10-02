package com.limelight.binding.input.capture

import android.app.Activity
import com.limelight.BuildConfig
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.binding.input.evdev.EvdevCaptureProviderShim
import com.limelight.binding.input.evdev.EvdevListener

object InputCaptureManager {
    @JvmStatic
    fun getInputCaptureProvider(activity: Activity, rootListener: EvdevListener): InputCaptureProvider {
        return when {
            AndroidNativePointerCaptureProvider.isCaptureProviderSupported() -> {
                LimeLog.info("Using Android O+ native mouse capture")
                AndroidNativePointerCaptureProvider(activity, activity.findViewById(R.id.surfaceView))
            }
            !BuildConfig.ROOT_BUILD && ShieldCaptureProvider.isCaptureProviderSupported() -> {
                LimeLog.info("Using NVIDIA mouse capture extension")
                ShieldCaptureProvider(activity)
            }
            EvdevCaptureProviderShim.isCaptureProviderSupported() -> {
                LimeLog.info("Using Evdev mouse capture")
                EvdevCaptureProviderShim.createEvdevCaptureProvider(activity, rootListener)
            }
            AndroidPointerIconCaptureProvider.isCaptureProviderSupported() -> {
                LimeLog.info("Using Android N+ pointer hiding")
                AndroidPointerIconCaptureProvider(activity, activity.findViewById(R.id.surfaceView))
            }
            else -> {
                LimeLog.info("Mouse capture not available")
                NullCaptureProvider()
            }
        }
    }
}
