package com.limelight.binding.video

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ConfigurationInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.limelight.LimeLog
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.Collections
import java.util.LinkedList
import java.util.Locale
import java.util.regex.Pattern

object MediaCodecHelper {

    private val preferredDecoders: MutableList<String> = LinkedList()
    private val blacklistedDecoderPrefixes: MutableList<String> = LinkedList()
    private val spsFixupBitstreamFixupDecoderPrefixes: MutableList<String> = LinkedList()
    private val blacklistedAdaptivePlaybackPrefixes: MutableList<String> = LinkedList()
    private val baselineProfileHackPrefixes: MutableList<String> = LinkedList()
    private val directSubmitPrefixes: MutableList<String> = LinkedList()
    private val constrainedHighProfilePrefixes: MutableList<String> = LinkedList()
    private val whitelistedHevcDecoders: MutableList<String> = LinkedList()
    private val refFrameInvalidationAvcPrefixes: MutableList<String> = LinkedList()
    private val refFrameInvalidationHevcPrefixes: MutableList<String> = LinkedList()
    private val useFourSlicesPrefixes: MutableList<String> = LinkedList()
    private val qualcommDecoderPrefixes: MutableList<String> = LinkedList()
    private val kirinDecoderPrefixes: MutableList<String> = LinkedList()
    private val exynosDecoderPrefixes: MutableList<String> = LinkedList()
    private val amlogicDecoderPrefixes: MutableList<String> = LinkedList()
    private val knownVendorLowLatencyOptions: MutableList<String> = LinkedList()

    @JvmField
    val SHOULD_BYPASS_SOFTWARE_BLOCK: Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "cheets" || Build.BRAND == "Android-x86"

    private var isLowEndSnapdragon = false
    private var isAdreno620 = false
    private var initialized = false

    init {
        // These decoders have low enough input buffer latency that they
        // can be directly invoked from the receive thread
        directSubmitPrefixes.add("omx.qcom")
        directSubmitPrefixes.add("omx.sec")
        directSubmitPrefixes.add("omx.exynos")
        directSubmitPrefixes.add("omx.intel")
        directSubmitPrefixes.add("omx.brcm")
        directSubmitPrefixes.add("omx.TI")
        directSubmitPrefixes.add("omx.arc")
        directSubmitPrefixes.add("omx.nvidia")

        // All Codec2 decoders
        directSubmitPrefixes.add("c2.")

        refFrameInvalidationHevcPrefixes.add("omx.exynos")
        refFrameInvalidationHevcPrefixes.add("c2.exynos")
        // Qualcomm and NVIDIA may be added at runtime

        // Blacklist software decoders that don't support H264 high profile except on systems
        // that are expected to only have software decoders (like emulators).
        if (!SHOULD_BYPASS_SOFTWARE_BLOCK) {
            blacklistedDecoderPrefixes.add("omx.google")
            blacklistedDecoderPrefixes.add("AVCDecoder")

            // We want to avoid ffmpeg decoders since they're usually software decoders,
            // but we'll defer to the Android 10 isSoftwareOnly() API on newer devices
            // to determine if we should use these or not.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                blacklistedDecoderPrefixes.add("OMX.ffmpeg")
            }
        }

        // Force these decoders disabled because:
        // 1) They are software decoders, so the performance is terrible
        // 2) They crash with our HEVC stream anyway (at least prior to CSD batching)
        blacklistedDecoderPrefixes.add("OMX.qcom.video.decoder.hevcswvdec")
        blacklistedDecoderPrefixes.add("OMX.SEC.hevc.sw.dec")

        // If a decoder qualifies for reference frame invalidation,
        // these entries will be ignored for those decoders.
        spsFixupBitstreamFixupDecoderPrefixes.add("omx.nvidia")
        spsFixupBitstreamFixupDecoderPrefixes.add("omx.qcom")
        spsFixupBitstreamFixupDecoderPrefixes.add("omx.brcm")

        baselineProfileHackPrefixes.add("omx.intel")

        // The Intel decoder on Lollipop on Nexus Player would increase latency badly
        // if adaptive playback was enabled so let's avoid it to be safe.
        blacklistedAdaptivePlaybackPrefixes.add("omx.intel")
        // The MediaTek decoder crashes at 1080p when adaptive playback is enabled
        // on some Android TV devices with HEVC only.
        blacklistedAdaptivePlaybackPrefixes.add("omx.mtk")

        constrainedHighProfilePrefixes.add("omx.intel")

        // Allow software HEVC decoding in the official AOSP emulator
        if (Build.HARDWARE == "ranchu") {
            whitelistedHevcDecoders.add("omx.google")
        }

        // Exynos seems to be the only HEVC decoder that works reliably
        whitelistedHevcDecoders.add("omx.exynos")

        if (!Build.DEVICE.equals("shieldtablet", ignoreCase = true) &&
            !Build.DEVICE.equals("mocha", ignoreCase = true) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            whitelistedHevcDecoders.add("omx.nvidia")
        }

        // Plot twist: On newer Sony devices (BRAVIA_ATV2, BRAVIA_ATV3_4K, BRAVIA_UR1_4K) the H.264 decoder crashes
        // on several configurations (> 60 FPS and 1440p) that work with HEVC, so we'll whitelist those devices for HEVC.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.DEVICE.startsWith("BRAVIA_")) {
            whitelistedHevcDecoders.add("omx.mtk")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !Build.DEVICE.equals("sabrina", ignoreCase = true)) {
            whitelistedHevcDecoders.add("omx.amlogic")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            whitelistedHevcDecoders.add("omx.realtek")
        }

        // Let's see if HEVC decoders are finally stable with C2
        whitelistedHevcDecoders.add("c2.")

        // Software decoders will use 4 slices per frame to allow for slice multithreading
        useFourSlicesPrefixes.add("omx.google")
        useFourSlicesPrefixes.add("AVCDecoder")
        useFourSlicesPrefixes.add("omx.ffmpeg")
        useFourSlicesPrefixes.add("c2.android")

        knownVendorLowLatencyOptions.add("vendor.qti-ext-dec-low-latency.enable")
        knownVendorLowLatencyOptions.add("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req")
        knownVendorLowLatencyOptions.add("vendor.rtc-ext-dec-low-latency.enable")
        knownVendorLowLatencyOptions.add("vendor.low-latency.enable")

        qualcommDecoderPrefixes.add("omx.qcom")
        qualcommDecoderPrefixes.add("c2.qti")

        kirinDecoderPrefixes.add("omx.hisi")
        kirinDecoderPrefixes.add("c2.hisi")

        exynosDecoderPrefixes.add("omx.exynos")
        exynosDecoderPrefixes.add("c2.exynos")

        amlogicDecoderPrefixes.add("omx.amlogic")
        amlogicDecoderPrefixes.add("c2.amlogic")
    }

    private fun isPowerVR(glRenderer: String?): Boolean {
        return glRenderer?.lowercase(Locale.ROOT)?.contains("powervr") == true
    }

    private fun getAdrenoVersionString(glRenderer: String?): String? {
        val trimmed = glRenderer?.lowercase(Locale.ROOT)?.trim() ?: return null
        if (!trimmed.contains("adreno")) {
            return null
        }

        val modelNumberPattern = Pattern.compile("(.*)([0-9]{3})(.*)")
        val matcher = modelNumberPattern.matcher(trimmed)
        if (!matcher.matches()) {
            return null
        }

        val modelNumber = matcher.group(2)
        LimeLog.info("Found Adreno GPU: $modelNumber")
        return modelNumber
    }

    private fun isLowEndSnapdragonRenderer(glRenderer: String?): Boolean {
        val modelNumber = getAdrenoVersionString(glRenderer) ?: return false
        return modelNumber.length >= 2 && modelNumber[1] == '0'
    }

    private fun getAdrenoRendererModelNumber(glRenderer: String?): Int {
        val modelNumber = getAdrenoVersionString(glRenderer) ?: return -1
        return try {
            modelNumber.toInt()
        } catch (e: NumberFormatException) {
            -1
        }
    }

    private fun isGLES31SnapdragonRenderer(glRenderer: String?): Boolean {
        return getAdrenoRendererModelNumber(glRenderer) >= 400
    }

    @JvmStatic
    fun initialize(context: Context, glRenderer: String?) {
        if (initialized) {
            return
        }

        if (context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        ) {
            whitelistedHevcDecoders.add("omx.mtk")
            refFrameInvalidationHevcPrefixes.add("omx.mtk")
            refFrameInvalidationHevcPrefixes.add("c2.mtk")
            whitelistedHevcDecoders.add("omx.amlogic")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                refFrameInvalidationHevcPrefixes.add("omx.amlogic")
                refFrameInvalidationHevcPrefixes.add("c2.amlogic")
            }
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val configInfo = activityManager.deviceConfigurationInfo
        if (configInfo.reqGlEsVersion != ConfigurationInfo.GL_ES_VERSION_UNDEFINED) {
            LimeLog.info("OpenGL ES version: ${configInfo.reqGlEsVersion}")

            isLowEndSnapdragon = isLowEndSnapdragonRenderer(glRenderer)
            isAdreno620 = getAdrenoRendererModelNumber(glRenderer) == 620

            if (configInfo.reqGlEsVersion >= 0x30000) {
                LimeLog.info("Added omx.nvidia/c2.nvidia to reference frame invalidation support list")
                refFrameInvalidationAvcPrefixes.add("omx.nvidia")

                if (!Build.DEVICE.equals("dragon", ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    refFrameInvalidationHevcPrefixes.add("omx.nvidia")
                }

                refFrameInvalidationAvcPrefixes.add("c2.nvidia")
                refFrameInvalidationHevcPrefixes.add("c2.nvidia")

                LimeLog.info("Added omx.qcom/c2.qti to reference frame invalidation support list")
                refFrameInvalidationAvcPrefixes.add("omx.qcom")
                refFrameInvalidationHevcPrefixes.add("omx.qcom")
                refFrameInvalidationAvcPrefixes.add("c2.qti")
                refFrameInvalidationHevcPrefixes.add("c2.qti")
            }

            if (isGLES31SnapdragonRenderer(glRenderer)) {
                LimeLog.info("Added omx.qcom/c2.qti to HEVC decoders based on GLES 3.1+ support")
                whitelistedHevcDecoders.add("omx.qcom")
                whitelistedHevcDecoders.add("c2.qti")
            } else {
                blacklistedDecoderPrefixes.add("OMX.qcom.video.decoder.hevc")
                useFourSlicesPrefixes.add("omx.qcom")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isPowerVR(glRenderer)) {
                LimeLog.info("Added omx.mtk to HEVC decoders based on PowerVR GPU")
                whitelistedHevcDecoders.add("omx.mtk")

                if (glRenderer != null && glRenderer.contains("GX6")) {
                    LimeLog.info("Added omx.mtk/c2.mtk to RFI list for HEVC")
                    refFrameInvalidationHevcPrefixes.add("omx.mtk")
                    refFrameInvalidationHevcPrefixes.add("c2.mtk")
                }
            }
        }

        initialized = true
    }

    private fun isDecoderInList(decoderList: List<String>, decoderName: String): Boolean {
        check(initialized) { "MediaCodecHelper must be initialized before use" }

        for (badPrefix in decoderList) {
            if (decoderName.length >= badPrefix.length) {
                val prefix = decoderName.substring(0, badPrefix.length)
                if (prefix.equals(badPrefix, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    private fun decoderSupportsAndroidRLowLatency(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (decoderInfo.getCapabilitiesForType(mimeType).isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)) {
                    LimeLog.info("Low latency decoding mode supported (FEATURE_LowLatency)")
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
    }

    private fun decoderSupportsKnownVendorLowLatencyOption(decoderName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var testCodec: MediaCodec? = null
            try {
                testCodec = MediaCodec.createByCodecName(decoderName)
                for (supportedOption in testCodec.supportedVendorParameters) {
                    for (knownLowLatencyOption in knownVendorLowLatencyOptions) {
                        if (supportedOption.equals(knownLowLatencyOption, ignoreCase = true)) {
                            LimeLog.info("$decoderName supports known low latency option: $supportedOption")
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                testCodec?.release()
            }
        }
        return false
    }

    private fun decoderSupportsMaxOperatingRate(decoderName: String): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                isDecoderInList(qualcommDecoderPrefixes, decoderName) &&
                !isAdreno620
    }

    @JvmStatic
    fun setDecoderLowLatencyOptions(videoFormat: MediaFormat, decoderInfo: MediaCodecInfo, tryNumber: Int): Boolean {
        var setNewOption = false

        if (tryNumber < 1) {
            videoFormat.setInteger("low-latency", 1)
            setNewOption = true

            val mime = videoFormat.getString(MediaFormat.KEY_MIME)
            if (mime != null && decoderSupportsAndroidRLowLatency(decoderInfo, mime)) {
                return true
            }
        }

        if (tryNumber < 2 &&
            (!Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) || Build.VERSION.SDK_INT > Build.VERSION_CODES.M)
        ) {
            videoFormat.setInteger("vdec-lowlatency", 1)
            setNewOption = true
        }

        if (tryNumber < 3) {
            if (decoderSupportsMaxOperatingRate(decoderInfo.name)) {
                videoFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, java.lang.Short.MAX_VALUE.toInt())
                setNewOption = true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
                setNewOption = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isDecoderInList(qualcommDecoderPrefixes, decoderInfo.name)) {
                if (tryNumber < 4) {
                    videoFormat.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
                    setNewOption = true
                }
                if (tryNumber < 5) {
                    videoFormat.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                    setNewOption = true
                }
            } else if (isDecoderInList(kirinDecoderPrefixes, decoderInfo.name)) {
                if (tryNumber < 4) {
                    videoFormat.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
                    videoFormat.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1)
                    setNewOption = true
                }
            } else if (isDecoderInList(exynosDecoderPrefixes, decoderInfo.name)) {
                if (tryNumber < 4) {
                    videoFormat.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                    setNewOption = true
                }
            } else if (isDecoderInList(amlogicDecoderPrefixes, decoderInfo.name)) {
                if (tryNumber < 4) {
                    videoFormat.setInteger("vendor.low-latency.enable", 1)
                    setNewOption = true
                }
            }
        }

        return setNewOption
    }

    @JvmStatic
    fun decoderSupportsFusedIdrFrame(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
        try {
            if (decoderInfo.getCapabilitiesForType(mimeType).isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback)) {
                LimeLog.info("Decoder supports fused IDR frames (FEATURE_AdaptivePlayback)")
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    @JvmStatic
    fun decoderSupportsAdaptivePlayback(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
        if (isDecoderInList(blacklistedAdaptivePlaybackPrefixes, decoderInfo.name)) {
            LimeLog.info("Decoder blacklisted for adaptive playback")
            return false
        }

        try {
            if (decoderInfo.getCapabilitiesForType(mimeType).isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback)) {
                LimeLog.info("Adaptive playback supported (FEATURE_AdaptivePlayback)")
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    @JvmStatic
    fun decoderNeedsConstrainedHighProfile(decoderName: String): Boolean {
        return isDecoderInList(constrainedHighProfilePrefixes, decoderName)
    }

    @JvmStatic
    fun decoderCanDirectSubmit(decoderName: String): Boolean {
        return isDecoderInList(directSubmitPrefixes, decoderName) && !isExynos4Device()
    }

    @JvmStatic
    fun decoderNeedsSpsBitstreamRestrictions(decoderName: String): Boolean {
        return isDecoderInList(spsFixupBitstreamFixupDecoderPrefixes, decoderName)
    }

    @JvmStatic
    fun decoderNeedsBaselineSpsHack(decoderName: String): Boolean {
        return isDecoderInList(baselineProfileHackPrefixes, decoderName)
    }

    @JvmStatic
    fun getDecoderOptimalSlicesPerFrame(decoderName: String): Byte {
        return if (isDecoderInList(useFourSlicesPrefixes, decoderName)) {
            4.toByte()
        } else {
            1.toByte()
        }
    }

    @JvmStatic
    fun decoderSupportsRefFrameInvalidationAvc(decoderName: String, videoHeight: Int): Boolean {
        if (videoHeight > 720 && isLowEndSnapdragon) {
            return false
        }
        if (Build.DEVICE == "b3" || Build.DEVICE == "b5") {
            return false
        }
        return isDecoderInList(refFrameInvalidationAvcPrefixes, decoderName)
    }

    @JvmStatic
    fun decoderSupportsRefFrameInvalidationHevc(decoderInfo: MediaCodecInfo): Boolean {
        if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/hevc") ||
            decoderSupportsKnownVendorLowLatencyOption(decoderInfo.name)
        ) {
            LimeLog.info("Enabling HEVC RFI based on low latency option support")
            return true
        }
        return isDecoderInList(refFrameInvalidationHevcPrefixes, decoderInfo.name)
    }

    @JvmStatic
    fun decoderSupportsRefFrameInvalidationAv1(decoderInfo: MediaCodecInfo): Boolean {
        if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/av01") ||
            decoderSupportsKnownVendorLowLatencyOption(decoderInfo.name)
        ) {
            LimeLog.info("Enabling AV1 RFI based on low latency option support")
            return true
        }
        return false
    }

    @JvmStatic
    fun decoderIsWhitelistedForHevc(decoderInfo: MediaCodecInfo): Boolean {
        if (decoderInfo.name.contains("sw")) {
            LimeLog.info("Disallowing HEVC on software decoder: ${decoderInfo.name}")
            return false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (!decoderInfo.isHardwareAccelerated || decoderInfo.isSoftwareOnly)) {
            LimeLog.info("Disallowing HEVC on software decoder: ${decoderInfo.name}")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            LimeLog.info("Media performance class: ${Build.VERSION.MEDIA_PERFORMANCE_CLASS}")
            if (Build.VERSION.MEDIA_PERFORMANCE_CLASS >= Build.VERSION_CODES.S) {
                LimeLog.info("Allowing HEVC based on media performance class")
                return true
            }
        }

        if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/hevc")) {
            LimeLog.info("Allowing HEVC based on FEATURE_LowLatency support")
            return true
        }

        return isDecoderInList(whitelistedHevcDecoders, decoderInfo.name)
    }

    @JvmStatic
    fun isDecoderWhitelistedForAv1(decoderInfo: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        if (decoderInfo.name.contains("sw")) {
            LimeLog.info("Disallowing AV1 on software decoder: ${decoderInfo.name}")
            return false
        } else if (!decoderInfo.isHardwareAccelerated || decoderInfo.isSoftwareOnly) {
            LimeLog.info("Disallowing AV1 on software decoder: ${decoderInfo.name}")
            return false
        }

        return false
    }

    @SuppressLint("NewApi")
    private fun getMediaCodecList(): LinkedList<MediaCodecInfo> {
        val infoList = LinkedList<MediaCodecInfo>()
        val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        Collections.addAll(infoList, *mcl.codecInfos)
        return infoList
    }

    @JvmStatic
    @Throws(Exception::class)
    fun dumpDecoders(): String {
        val sb = StringBuilder()
        for (codecInfo in getMediaCodecList()) {
            if (codecInfo.isEncoder) {
                continue
            }
            sb.append("Decoder: ").append(codecInfo.name).append("\n")
            for (type in codecInfo.supportedTypes) {
                sb.append("\t").append(type).append("\n")
                val caps = codecInfo.getCapabilitiesForType(type)
                for (profile in caps.profileLevels) {
                    sb.append("\t\t").append(profile.profile).append(" ").append(profile.level).append("\n")
                }
            }
        }
        return sb.toString()
    }

    private fun findPreferredDecoder(): MediaCodecInfo? {
        check(initialized) { "MediaCodecHelper must be initialized before use" }

        for (preferredDecoder in preferredDecoders) {
            for (codecInfo in getMediaCodecList()) {
                if (codecInfo.isEncoder) {
                    continue
                }
                if (preferredDecoder.equals(codecInfo.name, ignoreCase = true)) {
                    LimeLog.info("Preferred decoder choice is ${codecInfo.name}")
                    return codecInfo
                }
            }
        }
        return null
    }

    private fun isCodecBlacklisted(codecInfo: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!SHOULD_BYPASS_SOFTWARE_BLOCK && codecInfo.isSoftwareOnly) {
                LimeLog.info("Skipping software-only decoder: ${codecInfo.name}")
                return true
            }
        }

        if (isDecoderInList(blacklistedDecoderPrefixes, codecInfo.name)) {
            LimeLog.info("Skipping blacklisted decoder: ${codecInfo.name}")
            return true
        }

        return false
    }

    @JvmStatic
    fun findFirstDecoder(mimeType: String): MediaCodecInfo? {
        for (codecInfo in getMediaCodecList()) {
            if (codecInfo.isEncoder) {
                continue
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (codecInfo.isAlias) {
                    continue
                }
            }

            for (mime in codecInfo.supportedTypes) {
                if (mime.equals(mimeType, ignoreCase = true)) {
                    if (isCodecBlacklisted(codecInfo)) {
                        continue
                    }

                    LimeLog.info("First decoder choice is ${codecInfo.name}")
                    return codecInfo
                }
            }
        }
        return null
    }

    @JvmStatic
    fun findProbableSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
        val info = findPreferredDecoder()
        if (info != null) {
            return info
        }

        return try {
            findKnownSafeDecoder(mimeType, requiredProfile)
        } catch (e: Exception) {
            findFirstDecoder(mimeType)
        }
    }

    @Throws(Exception::class)
    private fun findKnownSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
        for (i in 0 until 2) {
            for (codecInfo in getMediaCodecList()) {
                if (codecInfo.isEncoder) {
                    continue
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (codecInfo.isAlias) {
                        continue
                    }
                }

                for (mime in codecInfo.supportedTypes) {
                    if (mime.equals(mimeType, ignoreCase = true)) {
                        LimeLog.info("Examining decoder capabilities of ${codecInfo.name} (round ${i + 1})")

                        if (isCodecBlacklisted(codecInfo)) {
                            continue
                        }

                        val caps = codecInfo.getCapabilitiesForType(mime)

                        if (i == 0 && !decoderSupportsAndroidRLowLatency(codecInfo, mime)) {
                            LimeLog.info("Skipping decoder that lacks FEATURE_LowLatency for round 1")
                            continue
                        }

                        if (requiredProfile != -1) {
                            for (profile in caps.profileLevels) {
                                if (profile.profile == requiredProfile) {
                                    LimeLog.info("Decoder ${codecInfo.name} supports required profile")
                                    return codecInfo
                                }
                            }
                            LimeLog.info("Decoder ${codecInfo.name} does NOT support required profile")
                        } else {
                            return codecInfo
                        }
                    }
                }
            }
        }
        return null
    }

    @JvmStatic
    @Throws(Exception::class)
    fun readCpuinfo(): String {
        val cpuInfo = StringBuilder()
        BufferedReader(FileReader(File("/proc/cpuinfo"))).use { br ->
            while (true) {
                val ch = br.read()
                if (ch == -1) break
                cpuInfo.append(ch.toChar())
            }
        }
        return cpuInfo.toString()
    }

    private fun stringContainsIgnoreCase(string: String, substring: String): Boolean {
        return string.lowercase(Locale.ENGLISH).contains(substring.lowercase(Locale.ENGLISH))
    }

    @JvmStatic
    fun isExynos4Device(): Boolean {
        try {
            val cpuInfo = readCpuinfo()
            if (stringContainsIgnoreCase(cpuInfo, "SMDK4")) {
                LimeLog.info("Found SMDK4 in /proc/cpuinfo")
                return true
            }
            if (stringContainsIgnoreCase(cpuInfo, "Exynos 4")) {
                LimeLog.info("Found Exynos 4 in /proc/cpuinfo")
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val systemDir = File("/sys/devices/system")
            val files = systemDir.listFiles()
            if (files != null) {
                for (f in files) {
                    if (stringContainsIgnoreCase(f.name, "exynos4")) {
                        LimeLog.info("Found exynos4 in /sys/devices/system")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }
}
