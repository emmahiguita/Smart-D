package com.limelight.binding.video

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodec.CodecException
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Range
import android.view.Choreographer
import android.view.SurfaceHolder
import com.limelight.BuildConfig
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.nvstream.av.video.VideoDecoderRenderer
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.qos.StreamTimingController
import org.jcodec.codecs.h264.H264Utils
import org.jcodec.codecs.h264.io.model.SeqParameterSet
import org.jcodec.codecs.h264.io.model.VUIParameters
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import java.util.Arrays
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class MediaCodecDecoderRenderer(
    activity: Activity,
    prefs: PreferenceConfiguration,
    crashListener: CrashListener,
    consecutiveCrashCount: Int,
    meteredData: Boolean,
    requestedHdr: Boolean,
    glRenderer: String,
    perfListener: PerfOverlayListener
) : VideoDecoderRenderer(), Choreographer.FrameCallback {

    companion object {
        private const val USE_FRAME_RENDER_TIME = false
        private const val FRAME_RENDER_TIME_ONLY = USE_FRAME_RENDER_TIME && false

        private const val CR_MAX_TRIES = 10
        private const val CR_RECOVERY_TYPE_NONE = 0
        private const val CR_RECOVERY_TYPE_FLUSH = 1
        private const val CR_RECOVERY_TYPE_RESTART = 2
        private const val CR_RECOVERY_TYPE_RESET = 3

        private const val CR_FLAG_INPUT_THREAD = 0x1
        private const val CR_FLAG_RENDER_THREAD = 0x2
        private const val CR_FLAG_CHOREOGRAPHER = 0x4
        private const val CR_FLAG_ALL = CR_FLAG_INPUT_THREAD or CR_FLAG_RENDER_THREAD or CR_FLAG_CHOREOGRAPHER

        private const val EXCEPTION_REPORT_DELAY_MS = 3000
        private const val OUTPUT_BUFFER_QUEUE_LIMIT = 2
    }

    // Used on versions < 5.0
    private var legacyInputBuffers: Array<ByteBuffer>? = null

    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var av1Decoder: MediaCodecInfo? = null

    private val vpsBuffers = ArrayList<ByteArray>()
    private val spsBuffers = ArrayList<ByteArray>()
    private val ppsBuffers = ArrayList<ByteArray>()
    private var submittedCsd = false
    private var currentHdrMetadata: ByteArray? = null

    private var nextInputBufferIndex = -1
    private var nextInputBuffer: ByteBuffer? = null
    private var timingController: StreamTimingController? = null

    fun setStreamTimingController(timingController: StreamTimingController?) {
        this.timingController = timingController
    }

    private var context: Context = activity
    private var activity: Activity = activity
    private var videoDecoder: MediaCodec? = null
    private var rendererThread: Thread? = null
    private var needsSpsBitstreamFixup = false
    private var isExynos4 = false
    private var adaptivePlayback = false
    private var directSubmit = false
    private var fusedIdrFrame = false
    private var constrainedHighProfile = false
    private var refFrameInvalidationAvc = false
    private var refFrameInvalidationHevc = false
    private var refFrameInvalidationAv1 = false
    private var optimalSlicesPerFrame: Byte = 0
    private var refFrameInvalidationActive = false
    private var initialWidth = 0
    private var initialHeight = 0
    private var videoFormat = 0
    private var renderTarget: SurfaceHolder? = null
    @Volatile
    private var stopping = false
    private var crashListener: CrashListener = crashListener
    private var reportedCrash = false
    private var consecutiveCrashCount: Int = consecutiveCrashCount
    private var glRenderer: String = glRenderer
    private var foreground = true
    private var perfListener: PerfOverlayListener = perfListener

    private val codecRecoveryType = AtomicInteger(CR_RECOVERY_TYPE_NONE)
    private val codecRecoveryMonitor = Any()

    private var codecRecoveryThreadQuiescedFlags = 0
    private var codecRecoveryAttempts = 0

    private var inputFormat: MediaFormat? = null
    private var outputFormat: MediaFormat? = null
    private var configuredFormat: MediaFormat? = null

    private var needsBaselineSpsHack = false
    private var savedSps: SeqParameterSet? = null

    private var initialException: RendererException? = null
    private var initialExceptionTimestamp: Long = 0

    private var activeWindowVideoStats: VideoStats = VideoStats()
    private var lastWindowVideoStats: VideoStats = VideoStats()
    private var globalVideoStats: VideoStats = VideoStats()

    private var lastTimestampUs: Long = 0
    private var lastFrameNumber = 0
    private var refreshRate = 0
    private var prefs: PreferenceConfiguration = prefs

    private val outputBufferQueue = LinkedBlockingQueue<Int>()
    private var lastRenderedFrameTimeNanos: Long = 0
    private var choreographerHandlerThread: HandlerThread? = null
    private var choreographerHandler: Handler? = null

    private var numSpsIn = 0
    private var numPpsIn = 0
    private var numVpsIn = 0
    private var numFramesIn = 0
    private var numFramesOut = 0

    init {
        avcDecoder = findAvcDecoder()
        if (avcDecoder != null) {
            LimeLog.info("Selected AVC decoder: ${avcDecoder?.name}")
        } else {
            LimeLog.warning("No AVC decoder found")
        }

        hevcDecoder = findHevcDecoder(prefs, meteredData, requestedHdr)
        if (hevcDecoder != null) {
            LimeLog.info("Selected HEVC decoder: ${hevcDecoder?.name}")
        } else {
            LimeLog.info("No HEVC decoder found")
        }

        av1Decoder = findAv1Decoder(prefs)
        if (av1Decoder != null) {
            LimeLog.info("Selected AV1 decoder: ${av1Decoder?.name}")
        } else {
            LimeLog.info("No AV1 decoder found")
        }

        var avcOptimalSlicesPerFrame = 0
        var hevcOptimalSlicesPerFrame = 0
        val currentAvc = avcDecoder
        if (currentAvc != null) {
            directSubmit = MediaCodecHelper.decoderCanDirectSubmit(currentAvc.name)
            refFrameInvalidationAvc = MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(currentAvc.name, prefs.height)
            avcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(currentAvc.name).toInt()

            if (directSubmit) {
                LimeLog.info("Decoder ${currentAvc.name} will use direct submit")
            }
            if (refFrameInvalidationAvc) {
                LimeLog.info("Decoder ${currentAvc.name} will use reference frame invalidation for AVC")
            }
            LimeLog.info("Decoder ${currentAvc.name} wants $avcOptimalSlicesPerFrame slices per frame")
        }

        val currentHevc = hevcDecoder
        if (currentHevc != null) {
            refFrameInvalidationHevc = MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(currentHevc)
            hevcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(currentHevc.name).toInt()

            if (refFrameInvalidationHevc) {
                LimeLog.info("Decoder ${currentHevc.name} will use reference frame invalidation for HEVC")
            }

            LimeLog.info("Decoder ${currentHevc.name} wants $hevcOptimalSlicesPerFrame slices per frame")
        }

        val currentAv1 = av1Decoder
        if (currentAv1 != null) {
            refFrameInvalidationAv1 = MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(currentAv1)

            if (refFrameInvalidationAv1) {
                LimeLog.info("Decoder ${currentAv1.name} will use reference frame invalidation for AV1")
            }
        }

        optimalSlicesPerFrame = Math.max(avcOptimalSlicesPerFrame, hevcOptimalSlicesPerFrame).toByte()
        LimeLog.info("Requesting $optimalSlicesPerFrame slices per frame")

        if (consecutiveCrashCount % 2 == 1) {
            refFrameInvalidationAvc = false
            refFrameInvalidationHevc = false
            LimeLog.warning("Disabling RFI due to previous crash")
        }
    }

    private fun findAvcDecoder(): MediaCodecInfo? {
        var decoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        if (decoder == null) {
            decoder = MediaCodecHelper.findFirstDecoder("video/avc")
        }
        return decoder
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun decoderCanMeetPerformancePoint(caps: MediaCodecInfo.VideoCapabilities, prefs: PreferenceConfiguration): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targetPerfPoint = MediaCodecInfo.VideoCapabilities.PerformancePoint(prefs.width, prefs.height, prefs.fps)
            val perfPoints = caps.supportedPerformancePoints
            if (perfPoints != null) {
                for (perfPoint in perfPoints) {
                    if (perfPoint.covers(targetPerfPoint)) {
                        return true
                    }
                }
                return false
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val fpsRange: Range<Double>? = caps.getAchievableFrameRatesFor(prefs.width, prefs.height)
                if (fpsRange != null) {
                    return prefs.fps <= (fpsRange.upper ?: 0.0)
                }
            } catch (e: IllegalArgumentException) {
                return false
            }
        }

        return caps.areSizeAndRateSupported(prefs.width, prefs.height, prefs.fps.toDouble())
    }

    private fun decoderCanMeetPerformancePointWithHevcAndNotAvc(
        hevcDecoderInfo: MediaCodecInfo,
        avcDecoderInfo: MediaCodecInfo,
        prefs: PreferenceConfiguration
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").videoCapabilities
            val hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").videoCapabilities
            !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(hevcCaps, prefs)
        } else {
            false
        }
    }

    private fun decoderCanMeetPerformancePointWithAv1AndNotHevc(
        av1DecoderInfo: MediaCodecInfo,
        hevcDecoderInfo: MediaCodecInfo,
        prefs: PreferenceConfiguration
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").videoCapabilities
            val hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").videoCapabilities
            !decoderCanMeetPerformancePoint(hevcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs)
        } else {
            false
        }
    }

    private fun decoderCanMeetPerformancePointWithAv1AndNotAvc(
        av1DecoderInfo: MediaCodecInfo,
        avcDecoderInfo: MediaCodecInfo,
        prefs: PreferenceConfiguration
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").videoCapabilities
            val av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").videoCapabilities
            !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs)
        } else {
            false
        }
    }

    private fun findHevcDecoder(prefs: PreferenceConfiguration, meteredNetwork: Boolean, requestedHdr: Boolean): MediaCodecInfo? {
        if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264) {
            return null
        }

        val hevcDecoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1)
        if (hevcDecoderInfo != null) {
            if (!MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoderInfo)) {
                LimeLog.info("Found HEVC decoder, but it's not whitelisted - ${hevcDecoderInfo.name}")

                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC) {
                    LimeLog.info("Forcing HEVC enabled despite non-whitelisted decoder")
                } else if (requestedHdr) {
                    LimeLog.info("Forcing HEVC enabled for HDR streaming")
                } else if (prefs.width > 4096 || prefs.height > 4096) {
                    LimeLog.info("Forcing HEVC enabled for over 4K streaming")
                } else if (avcDecoder != null && decoderCanMeetPerformancePointWithHevcAndNotAvc(hevcDecoderInfo, avcDecoder!!, prefs)) {
                    LimeLog.info("Using non-whitelisted HEVC decoder to meet performance point")
                } else {
                    return null
                }
            }
        }

        return hevcDecoderInfo
    }

    private fun findAv1Decoder(prefs: PreferenceConfiguration): MediaCodecInfo? {
        if (prefs.videoFormat != PreferenceConfiguration.FormatOption.FORCE_AV1) {
            return null
        }

        val decoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1)
        if (decoderInfo != null) {
            if (!MediaCodecHelper.isDecoderWhitelistedForAv1(decoderInfo)) {
                LimeLog.info("Found AV1 decoder, but it's not whitelisted - ${decoderInfo.name}")

                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1) {
                    LimeLog.info("Forcing AV1 enabled despite non-whitelisted decoder")
                } else if (hevcDecoder != null && decoderCanMeetPerformancePointWithAv1AndNotHevc(decoderInfo, hevcDecoder!!, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point")
                } else if (hevcDecoder == null && avcDecoder != null && decoderCanMeetPerformancePointWithAv1AndNotAvc(decoderInfo, avcDecoder!!, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point")
                } else {
                    return null
                }
            }
        }

        return decoderInfo
    }

    fun setRenderTarget(renderTarget: SurfaceHolder?) {
        this.renderTarget = renderTarget
    }

    fun isHevcSupported(): Boolean = hevcDecoder != null

    fun isAvcSupported(): Boolean = avcDecoder != null

    fun isHevcMain10Hdr10Supported(): Boolean {
        val decoder = hevcDecoder ?: return false
        for (profileLevel in decoder.getCapabilitiesForType("video/hevc").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
                LimeLog.info("HEVC decoder ${decoder.name} supports HEVC Main10 HDR10")
                return true
            }
        }
        return false
    }

    fun isAv1Supported(): Boolean = av1Decoder != null

    fun isAv1Main10Supported(): Boolean {
        val decoder = av1Decoder ?: return false
        for (profileLevel in decoder.getCapabilitiesForType("video/av01").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
                LimeLog.info("AV1 decoder ${decoder.name} supports AV1 Main 10 HDR10")
                return true
            }
        }
        return false
    }

    fun getPreferredColorSpace(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || hevcDecoder != null || av1Decoder != null) {
            MoonBridge.COLORSPACE_REC_709
        } else {
            MoonBridge.COLORSPACE_REC_601
        }
    }

    fun getPreferredColorRange(): Int {
        return if (prefs.fullRange) {
            MoonBridge.COLOR_RANGE_FULL
        } else {
            MoonBridge.COLOR_RANGE_LIMITED
        }
    }

    fun notifyVideoForeground() {
        foreground = true
    }

    fun notifyVideoBackground() {
        foreground = false
    }

    val activeVideoFormat: Int
        get() = this.videoFormat

    private fun createBaseMediaFormat(mimeType: String): MediaFormat {
        val format = MediaFormat.createVideoFormat(mimeType, initialWidth, initialHeight)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate)
        }

        if (adaptivePlayback) {
            format.setInteger(MediaFormat.KEY_MAX_WIDTH, initialWidth)
            format.setInteger(MediaFormat.KEY_MAX_HEIGHT, initialHeight)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            format.setInteger(
                MediaFormat.KEY_COLOR_RANGE,
                if (getPreferredColorRange() == MoonBridge.COLOR_RANGE_FULL)
                    MediaFormat.COLOR_RANGE_FULL
                else
                    MediaFormat.COLOR_RANGE_LIMITED
            )

            if ((activeVideoFormat and MoonBridge.VIDEO_FORMAT_MASK_10BIT) == 0) {
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                when (getPreferredColorSpace()) {
                    MoonBridge.COLORSPACE_REC_601 -> format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                    MoonBridge.COLORSPACE_REC_709 -> format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    MoonBridge.COLORSPACE_REC_2020 -> format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                }
            }
        }

        return format
    }

    private fun configureAndStartDecoder(format: MediaFormat) {
        val decoder = videoDecoder ?: throw IllegalStateException("videoDecoder is null")
        val surface = renderTarget?.surface

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val hdrMeta = currentHdrMetadata
            if (hdrMeta != null) {
                val hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
                val hdrMetadata = ByteBuffer.wrap(hdrMeta).order(ByteOrder.LITTLE_ENDIAN)

                hdrStaticInfo.put(0.toByte())
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)

                hdrStaticInfo.rewind()
                format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO)
            }
        }

        LimeLog.info("Configuring with format: $format")

        decoder.configure(format, surface, null, 0)
        configuredFormat = format

        submittedCsd = false
        vpsBuffers.clear()
        spsBuffers.clear()
        ppsBuffers.clear()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            inputFormat = decoder.inputFormat
            LimeLog.info("Input format: $inputFormat")
        }

        decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        decoder.start()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            legacyInputBuffers = decoder.inputBuffers
        }
    }

    private fun tryConfigureDecoder(selectedDecoderInfo: MediaCodecInfo, format: MediaFormat, throwOnCodecError: Boolean): Boolean {
        var configured = false
        try {
            videoDecoder = MediaCodec.createByCodecName(selectedDecoderInfo.name)
            configureAndStartDecoder(format)
            LimeLog.info("Using codec ${selectedDecoderInfo.name} for hardware decoding ${format.getString(MediaFormat.KEY_MIME)}")
            configured = true
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            if (throwOnCodecError) throw e
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            if (throwOnCodecError) throw e
        } catch (e: IOException) {
            e.printStackTrace()
            if (throwOnCodecError) throw RuntimeException(e)
        } finally {
            if (!configured && videoDecoder != null) {
                videoDecoder?.release()
                videoDecoder = null
            }
        }
        return configured
    }

    fun initializeDecoder(throwOnCodecError: Boolean): Int {
        val mimeType: String
        val selectedDecoderInfo: MediaCodecInfo

        if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
            mimeType = "video/avc"
            selectedDecoderInfo = avcDecoder ?: run {
                LimeLog.severe("No available AVC decoder!")
                return -1
            }

            if (initialWidth > 4096 || initialHeight > 4096) {
                LimeLog.severe("> 4K streaming only supported on HEVC")
                return -1
            }

            needsSpsBitstreamFixup = MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(selectedDecoderInfo.name)
            needsBaselineSpsHack = MediaCodecHelper.decoderNeedsBaselineSpsHack(selectedDecoderInfo.name)
            constrainedHighProfile = MediaCodecHelper.decoderNeedsConstrainedHighProfile(selectedDecoderInfo.name)
            isExynos4 = MediaCodecHelper.isExynos4Device()

            if (needsSpsBitstreamFixup) {
                LimeLog.info("Decoder ${selectedDecoderInfo.name} needs SPS bitstream restrictions fixup")
            }
            if (needsBaselineSpsHack) {
                LimeLog.info("Decoder ${selectedDecoderInfo.name} needs baseline SPS hack")
            }
            if (constrainedHighProfile) {
                LimeLog.info("Decoder ${selectedDecoderInfo.name} needs constrained high profile")
            }
            if (isExynos4) {
                LimeLog.info("Decoder ${selectedDecoderInfo.name} is on Exynos 4")
            }

            refFrameInvalidationActive = refFrameInvalidationAvc
        } else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
            mimeType = "video/hevc"
            selectedDecoderInfo = hevcDecoder ?: run {
                LimeLog.severe("No available HEVC decoder!")
                return -2
            }

            refFrameInvalidationActive = refFrameInvalidationHevc
        } else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
            mimeType = "video/av01"
            selectedDecoderInfo = av1Decoder ?: run {
                LimeLog.severe("No available AV1 decoder!")
                return -2
            }

            refFrameInvalidationActive = refFrameInvalidationAv1
        } else {
            LimeLog.severe("Unknown format")
            return -3
        }

        adaptivePlayback = MediaCodecHelper.decoderSupportsAdaptivePlayback(selectedDecoderInfo, mimeType)
        fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(selectedDecoderInfo, mimeType)

        var tryNumber = 0
        while (true) {
            LimeLog.info("Decoder configuration try: $tryNumber")
            val mediaFormat = createBaseMediaFormat(mimeType)
            val newFormat = MediaCodecHelper.setDecoderLowLatencyOptions(mediaFormat, selectedDecoderInfo, tryNumber)

            if (tryConfigureDecoder(selectedDecoderInfo, mediaFormat, !newFormat && throwOnCodecError)) {
                break
            }

            if (!newFormat) {
                return -5
            }
            tryNumber++
        }

        if (USE_FRAME_RENDER_TIME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoDecoder?.setOnFrameRenderedListener({ _, presentationTimeUs, renderTimeNanos ->
                val delta = (renderTimeNanos / 1000000L) - (presentationTimeUs / 1000)
                if (delta in 0 until 1000) {
                    if (USE_FRAME_RENDER_TIME) {
                        activeWindowVideoStats.totalTimeMs += delta
                    }
                }
            }, null)
        }

        return 0
    }

    override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int {
        this.initialWidth = width
        this.initialHeight = height
        this.videoFormat = format
        this.refreshRate = redrawRate

        return initializeDecoder(false)
    }

    private fun doCodecRecoveryIfRequired(quiescenceFlag: Int): Boolean {
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            return false
        }

        synchronized(codecRecoveryMonitor) {
            if (choreographerHandlerThread == null) {
                codecRecoveryThreadQuiescedFlags = codecRecoveryThreadQuiescedFlags or CR_FLAG_CHOREOGRAPHER
            }

            codecRecoveryThreadQuiescedFlags = codecRecoveryThreadQuiescedFlags or quiescenceFlag

            if (codecRecoveryThreadQuiescedFlags == CR_FLAG_ALL) {
                nextInputBuffer = null
                nextInputBufferIndex = -1
                outputBufferQueue.clear()

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH) {
                    LimeLog.warning("Flushing decoder")
                    try {
                        videoDecoder?.flush()
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART)
                    }
                }

                if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    codecRecoveryAttempts++
                    LimeLog.info("Codec recovery attempt: $codecRecoveryAttempts")
                }

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESTART) {
                    LimeLog.warning("Trying to restart decoder after CodecException")
                    try {
                        videoDecoder?.stop()
                        val cfgFormat = configuredFormat
                        if (cfgFormat != null) {
                            configureAndStartDecoder(cfgFormat)
                        }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalArgumentException) {
                        e.printStackTrace()
                        stopping = true
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESET)
                    }
                }

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    LimeLog.warning("Trying to reset decoder after CodecException")
                    try {
                        videoDecoder?.reset()
                        val cfgFormat = configuredFormat
                        if (cfgFormat != null) {
                            configureAndStartDecoder(cfgFormat)
                        }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalArgumentException) {
                        e.printStackTrace()
                        stopping = true
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                    }
                }

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    LimeLog.warning("Trying to recreate decoder after CodecException")
                    videoDecoder?.release()

                    try {
                        val err = initializeDecoder(true)
                        if (err != 0) {
                            throw IllegalStateException("Decoder reset failed: $err")
                        }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalArgumentException) {
                        e.printStackTrace()
                        stopping = true
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        if (!reportedCrash) {
                            reportedCrash = true
                            crashListener.notifyCrash(e)
                        }
                        throw RendererException(this, e)
                    }
                }

                codecRecoveryThreadQuiescedFlags = 0
                (codecRecoveryMonitor as java.lang.Object).notifyAll()
            } else {
                while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    try {
                        LimeLog.info("Waiting to quiesce decoder threads: $codecRecoveryThreadQuiescedFlags")
                        (codecRecoveryMonitor as java.lang.Object).wait(1000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        return true
    }

    private fun handleDecoderException(e: IllegalStateException): Boolean {
        if (stopping) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && e is CodecException) {
            val codecExc = e

            if (codecExc.isTransient) {
                LimeLog.warning(codecExc.diagnosticInfo)
                return true
            }

            LimeLog.severe(codecExc.diagnosticInfo)

            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                if (codecExc.isRecoverable) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder requires restart for recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder flush promoted to restart for recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET && codecRecoveryType.get() != CR_RECOVERY_TYPE_RESTART) {
                        throw IllegalStateException("Unexpected codec recovery type: ${codecRecoveryType.get()}")
                    }
                } else {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder requires reset for non-recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder flush promoted to reset for non-recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder restart promoted to reset for non-recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                        throw IllegalStateException("Unexpected codec recovery type: ${codecRecoveryType.get()}")
                    }
                }
                return false
            }
        } else {
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder requires reset for IllegalStateException")
                    e.printStackTrace()
                } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder flush promoted to reset for IllegalStateException")
                    e.printStackTrace()
                } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder restart promoted to reset for IllegalStateException")
                    e.printStackTrace()
                } else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                    throw IllegalStateException("Unexpected codec recovery type: ${codecRecoveryType.get()}")
                }
                return false
            }
        }

        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            val initial = initialException
            if (initial != null) {
                if (SystemClock.uptimeMillis() - initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
                    if (!reportedCrash) {
                        reportedCrash = true
                        crashListener.notifyCrash(initial)
                    }
                    throw initial
                }
            } else {
                initialException = RendererException(this, e)
                initialExceptionTimestamp = SystemClock.uptimeMillis()
            }
        }

        return false
    }

    override fun doFrame(frameTimeNanosParam: Long) {
        var frameTimeNanos = frameTimeNanosParam
        if (stopping) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            frameTimeNanos -= activity.windowManager.defaultDisplay.appVsyncOffsetNanos
        }

        val actualFrameTimeDeltaNs = frameTimeNanos - lastRenderedFrameTimeNanos
        val expectedFrameTimeDeltaNs = 800000000L / refreshRate
        if (actualFrameTimeDeltaNs >= expectedFrameTimeDeltaNs) {
            val nextOutputBuffer = outputBufferQueue.poll()
            if (nextOutputBuffer != null) {
                try {
                    val decoder = videoDecoder
                    if (decoder != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            decoder.releaseOutputBuffer(nextOutputBuffer, frameTimeNanos)
                        } else {
                            decoder.releaseOutputBuffer(nextOutputBuffer, true)
                        }
                    }

                    lastRenderedFrameTimeNanos = frameTimeNanos
                    activeWindowVideoStats.totalFramesRendered++
                    timingController?.onFramePresented(frameTimeNanos)
                } catch (ignored: IllegalStateException) {
                    try {
                        videoDecoder?.releaseOutputBuffer(nextOutputBuffer, false)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                        handleDecoderException(e)
                    }
                }
            }
        }

        doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startChoreographerThread() {
        if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
            return
        }

        val thread = HandlerThread("Video - Choreographer", Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_MORE_FAVORABLE)
        choreographerHandlerThread = thread
        thread.start()

        val handler = Handler(thread.looper)
        choreographerHandler = handler
        handler.post {
            Choreographer.getInstance().postFrameCallback(this@MediaCodecDecoderRenderer)
        }
    }

    private fun startRendererThread() {
        val thread = object : Thread() {
            override fun run() {
                val info = BufferInfo()
                while (!stopping) {
                    try {
                        val decoder = videoDecoder ?: break
                        var outIndex = decoder.dequeueOutputBuffer(info, 50000)
                        if (outIndex >= 0) {
                            var presentationTimeUs = info.presentationTimeUs
                            var lastIndex = outIndex

                            numFramesOut++

                            if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
                                while (true) {
                                    val nextIndex = decoder.dequeueOutputBuffer(info, 0)
                                    if (nextIndex < 0) break
                                    decoder.releaseOutputBuffer(lastIndex, false)
                                    numFramesOut++
                                    lastIndex = nextIndex
                                    presentationTimeUs = info.presentationTimeUs
                                }

                                if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                                    prefs.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS
                                ) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        decoder.releaseOutputBuffer(lastIndex, 0)
                                    } else {
                                        decoder.releaseOutputBuffer(lastIndex, true)
                                    }
                                } else {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        decoder.releaseOutputBuffer(lastIndex, System.nanoTime())
                                    } else {
                                        decoder.releaseOutputBuffer(lastIndex, true)
                                    }
                                }

                                activeWindowVideoStats.totalFramesRendered++
                            } else {
                                if (outputBufferQueue.size == OUTPUT_BUFFER_QUEUE_LIMIT) {
                                    try {
                                        decoder.releaseOutputBuffer(outputBufferQueue.take(), false)
                                    } catch (e: InterruptedException) {
                                        return
                                    }
                                }
                                outputBufferQueue.add(lastIndex)
                            }

                            val delta = SystemClock.uptimeMillis() - (presentationTimeUs / 1000)
                            if (delta in 0 until 1000) {
                                activeWindowVideoStats.decoderTimeMs += delta
                                if (!USE_FRAME_RENDER_TIME) {
                                    activeWindowVideoStats.totalTimeMs += delta
                                }
                            }
                        } else {
                            when (outIndex) {
                                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    LimeLog.info("Output format changed")
                                    outputFormat = decoder.outputFormat
                                    LimeLog.info("New output format: $outputFormat")
                                }
                                else -> {}
                            }
                        }
                    } catch (e: IllegalStateException) {
                        handleDecoderException(e)
                    } finally {
                        doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD)
                    }
                }
            }
        }
        thread.name = "Video - Renderer (MediaCodec)"
        thread.priority = Thread.NORM_PRIORITY + 2
        thread.start()
        rendererThread = thread
    }

    private fun fetchNextInputBuffer(): Boolean {
        if (nextInputBuffer != null) {
            return true
        }

        val startTime = SystemClock.uptimeMillis()
        val codecRecovered: Boolean

        try {
            val decoder = videoDecoder ?: return false
            while (nextInputBufferIndex < 0 && !stopping) {
                nextInputBufferIndex = decoder.dequeueInputBuffer(10000)
            }

            if (nextInputBufferIndex >= 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    nextInputBuffer = decoder.getInputBuffer(nextInputBufferIndex)
                    if (nextInputBuffer == null) {
                        nextInputBufferIndex = -1
                    }
                } else {
                    val buffers = legacyInputBuffers
                    if (buffers != null && nextInputBufferIndex < buffers.size) {
                        val buf = buffers[nextInputBufferIndex]
                        buf.clear()
                        nextInputBuffer = buf
                    }
                }
            }
        } catch (e: IllegalStateException) {
            handleDecoderException(e)
            return false
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
        }

        if (codecRecovered) {
            return false
        }

        val deltaMs = (SystemClock.uptimeMillis() - startTime).toInt()
        if (deltaMs >= 20) {
            LimeLog.warning("Dequeue input buffer ran long: $deltaMs ms")
        }

        if (nextInputBuffer == null) {
            if (deltaMs >= 5000 && initialException == null) {
                val decoderHungException = DecoderHungException(deltaMs)
                if (!reportedCrash) {
                    reportedCrash = true
                    crashListener.notifyCrash(decoderHungException)
                }
                throw RendererException(this, decoderHungException)
            }
            return false
        }

        return true
    }

    override fun start() {
        startRendererThread()
        startChoreographerThread()
    }

    fun prepareForStop() {
        stopping = true

        rendererThread?.interrupt()

        synchronized(codecRecoveryMonitor) {
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
            (codecRecoveryMonitor as java.lang.Object).notifyAll()
        }

        choreographerHandler?.post {
            choreographerHandlerThread?.quit()
            Choreographer.getInstance().removeFrameCallback(this@MediaCodecDecoderRenderer)
        }
    }

    override fun stop() {
        prepareForStop()

        choreographerHandlerThread?.let {
            try {
                it.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
                Thread.currentThread().interrupt()
            }
        }

        rendererThread?.let {
            try {
                it.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun cleanup() {
        videoDecoder?.release()
    }

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val currentHdr = currentHdrMetadata
            if (currentHdr != null && (!enabled || hdrMetadata == null)) {
                currentHdrMetadata = null
            } else if (enabled && hdrMetadata != null && !Arrays.equals(currentHdr, hdrMetadata)) {
                currentHdrMetadata = hdrMetadata
            } else {
                return
            }

            codecRecoveryAttempts = 0
            if (!codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)
            }
        }
    }

    private fun queueNextInputBuffer(timestampUs: Long, codecFlags: Int): Boolean {
        val codecRecovered: Boolean

        try {
            val decoder = videoDecoder ?: return false
            val buffer = nextInputBuffer ?: return false
            decoder.queueInputBuffer(
                nextInputBufferIndex,
                0,
                buffer.position(),
                timestampUs,
                codecFlags
            )
            nextInputBufferIndex = -1
            nextInputBuffer = null
        } catch (e: IllegalStateException) {
            if (handleDecoderException(e)) {
                nextInputBuffer?.clear()
            } else {
                nextInputBufferIndex = -1
                nextInputBuffer = null
            }
            return false
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
        }

        if (codecRecovered) {
            return false
        }

        return fetchNextInputBuffer()
    }

    private fun doProfileSpecificSpsPatching(sps: SeqParameterSet) {
        if (sps.profileIdc == 100 && constrainedHighProfile) {
            LimeLog.info("Setting constraint set flags for constrained high profile")
            sps.constraintSet4Flag = true
            sps.constraintSet5Flag = true
        } else {
            sps.constraintSet4Flag = false
            sps.constraintSet5Flag = false
        }
    }

    override fun submitDecodeUnit(
        decodeUnitData: ByteArray,
        decodeUnitLength: Int,
        decodeUnitType: Int,
        frameNumber: Int,
        frameType: Int,
        frameHostProcessingLatency: Char,
        receiveTimeMs: Long,
        enqueueTimeMs: Long
    ): Int {
        if (stopping) {
            return MoonBridge.DR_OK
        }

        if (lastFrameNumber == 0) {
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis()
        } else if (frameNumber != lastFrameNumber && frameNumber != lastFrameNumber + 1) {
            activeWindowVideoStats.framesLost += frameNumber - lastFrameNumber - 1
            activeWindowVideoStats.totalFrames += frameNumber - lastFrameNumber - 1
            activeWindowVideoStats.frameLossEvents++
        }

        if (lastFrameNumber != frameNumber && frameType == MoonBridge.FRAME_TYPE_IDR) {
            vpsBuffers.clear()
            spsBuffers.clear()
            ppsBuffers.clear()
        }

        lastFrameNumber = frameNumber

        if (SystemClock.uptimeMillis() >= activeWindowVideoStats.measurementStartTimestamp + 1000) {
            if (prefs.enablePerfOverlay) {
                val lastTwo = VideoStats()
                lastTwo.add(lastWindowVideoStats)
                lastTwo.add(activeWindowVideoStats)
                val fps = lastTwo.fps
                val decoder: String = when {
                    (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0 -> avcDecoder?.name ?: "(unknown)"
                    (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0 -> hevcDecoder?.name ?: "(unknown)"
                    (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 -> av1Decoder?.name ?: "(unknown)"
                    else -> "(unknown)"
                }

                val decodeTimeMs = lastTwo.decoderTimeMs.toFloat() / lastTwo.totalFramesReceived
                val rttInfo = MoonBridge.getEstimatedRttInfo()
                val sb = StringBuilder()
                sb.append(context.getString(R.string.perf_overlay_streamdetails, "${initialWidth}x$initialHeight", fps.totalFps)).append('\n')
                sb.append(context.getString(R.string.perf_overlay_decoder, decoder)).append('\n')
                sb.append(context.getString(R.string.perf_overlay_incomingfps, fps.receivedFps)).append('\n')
                sb.append(context.getString(R.string.perf_overlay_renderingfps, fps.renderedFps)).append('\n')
                sb.append(context.getString(R.string.perf_overlay_netdrops, lastTwo.framesLost.toFloat() / lastTwo.totalFrames * 100)).append('\n')
                sb.append(context.getString(R.string.perf_overlay_netlatency, (rttInfo shr 32).toInt(), rttInfo.toInt())).append('\n')
                if (lastTwo.framesWithHostProcessingLatency > 0) {
                    sb.append(
                        context.getString(
                            R.string.perf_overlay_hostprocessinglatency,
                            lastTwo.minHostProcessingLatency.code.toFloat() / 10,
                            lastTwo.maxHostProcessingLatency.code.toFloat() / 10,
                            lastTwo.totalHostProcessingLatency.toFloat() / 10 / lastTwo.framesWithHostProcessingLatency
                        )
                    ).append('\n')
                }
                sb.append(context.getString(R.string.perf_overlay_dectime, decodeTimeMs))
                perfListener.onPerfUpdate(sb.toString())
            }

            globalVideoStats.add(activeWindowVideoStats)
            lastWindowVideoStats.copy(activeWindowVideoStats)
            activeWindowVideoStats.clear()
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis()
        }

        var csdSubmittedForThisFrame = false

        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS && (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                numSpsIn++

                val spsBuf = ByteBuffer.wrap(decodeUnitData)
                val startSeqLen = if (decodeUnitData[2].toInt() == 0x01) 3 else 4

                spsBuf.position(startSeqLen + 1)
                val sps = H264Utils.readSPS(spsBuf)

                if (!refFrameInvalidationActive) {
                    if (initialWidth <= 720 && initialHeight <= 480 && refreshRate <= 60) {
                        LimeLog.info("Patching level_idc to 31")
                        sps.levelIdc = 31
                    } else if (initialWidth <= 1280 && initialHeight <= 720 && refreshRate <= 60) {
                        LimeLog.info("Patching level_idc to 32")
                        sps.levelIdc = 32
                    } else if (initialWidth <= 1920 && initialHeight <= 1080 && refreshRate <= 60) {
                        LimeLog.info("Patching level_idc to 42")
                        sps.levelIdc = 42
                    }
                }

                if (!refFrameInvalidationActive) {
                    LimeLog.info("Patching num_ref_frames in SPS")
                    sps.numRefFrames = 1
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
                    sps.vuiParams != null &&
                    hevcDecoder == null &&
                    av1Decoder == null
                ) {
                    sps.vuiParams.videoSignalTypePresentFlag = false
                    sps.vuiParams.colourDescriptionPresentFlag = false
                    sps.vuiParams.chromaLocInfoPresentFlag = false
                }

                if (needsSpsBitstreamFixup || isExynos4 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (sps.vuiParams == null) {
                        LimeLog.info("Adding VUI parameters")
                        sps.vuiParams = VUIParameters()
                    }

                    if (sps.vuiParams.bitstreamRestriction == null) {
                        LimeLog.info("Adding bitstream restrictions")
                        sps.vuiParams.bitstreamRestriction = VUIParameters.BitstreamRestriction().apply {
                            motionVectorsOverPicBoundariesFlag = true
                            maxBytesPerPicDenom = 2
                            maxBitsPerMbDenom = 1
                            log2MaxMvLengthHorizontal = 16
                            log2MaxMvLengthVertical = 16
                            numReorderFrames = 0
                        }
                    } else {
                        LimeLog.info("Patching bitstream restrictions")
                    }

                    sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering = sps.numRefFrames

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2
                        sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1
                    }
                } else if (sps.vuiParams != null) {
                    sps.vuiParams.bitstreamRestriction = null
                }

                if (needsBaselineSpsHack) {
                    LimeLog.info("Hacking SPS to baseline")
                    sps.profileIdc = 66
                    savedSps = sps
                }

                doProfileSpecificSpsPatching(sps)

                val escapedNalu = H264Utils.writeSPS(sps, decodeUnitLength)
                val naluBuffer = ByteArray(startSeqLen + 1 + escapedNalu.limit())
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, startSeqLen + 1)
                escapedNalu.get(naluBuffer, startSeqLen + 1, escapedNalu.limit())

                spsBuffers.add(naluBuffer)
                return MoonBridge.DR_OK
            } else if (decodeUnitType == MoonBridge.BUFFER_TYPE_VPS) {
                numVpsIn++
                val naluBuffer = ByteArray(decodeUnitLength)
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength)
                vpsBuffers.add(naluBuffer)
                return MoonBridge.DR_OK
            } else if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS) {
                numSpsIn++
                val naluBuffer = ByteArray(decodeUnitLength)
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength)
                spsBuffers.add(naluBuffer)
                return MoonBridge.DR_OK
            } else if (decodeUnitType == MoonBridge.BUFFER_TYPE_PPS) {
                numPpsIn++
                val naluBuffer = ByteArray(decodeUnitLength)
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength)
                ppsBuffers.add(naluBuffer)
                return MoonBridge.DR_OK
            } else if ((videoFormat and (MoonBridge.VIDEO_FORMAT_MASK_H264 or MoonBridge.VIDEO_FORMAT_MASK_H265)) != 0) {
                if (!submittedCsd || !fusedIdrFrame) {
                    if (!fetchNextInputBuffer()) {
                        return MoonBridge.DR_NEED_IDR
                    }

                    val buffer = nextInputBuffer ?: return MoonBridge.DR_NEED_IDR
                    for (vpsBuffer in vpsBuffers) {
                        buffer.put(vpsBuffer)
                    }
                    for (spsBuffer in spsBuffers) {
                        buffer.put(spsBuffer)
                    }
                    for (ppsBuffer in ppsBuffers) {
                        buffer.put(ppsBuffer)
                    }

                    if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        return MoonBridge.DR_NEED_IDR
                    }

                    csdSubmittedForThisFrame = true
                    submittedCsd = true

                    if (needsBaselineSpsHack) {
                        needsBaselineSpsHack = false
                        if (!replaySps()) {
                            return MoonBridge.DR_NEED_IDR
                        }
                        LimeLog.info("SPS replay complete")
                    }
                }
            }
        }

        val latencyCode = frameHostProcessingLatency.code
        if (latencyCode != 0) {
            if (activeWindowVideoStats.minHostProcessingLatency.code != 0) {
                activeWindowVideoStats.minHostProcessingLatency = Math.min(
                    activeWindowVideoStats.minHostProcessingLatency.code,
                    latencyCode
                ).toChar()
            } else {
                activeWindowVideoStats.minHostProcessingLatency = frameHostProcessingLatency
            }
            activeWindowVideoStats.framesWithHostProcessingLatency += 1
        }
        activeWindowVideoStats.maxHostProcessingLatency = Math.max(
            activeWindowVideoStats.maxHostProcessingLatency.code,
            latencyCode
        ).toChar()
        activeWindowVideoStats.totalHostProcessingLatency += latencyCode

        activeWindowVideoStats.totalFramesReceived++
        activeWindowVideoStats.totalFrames++

        if (!FRAME_RENDER_TIME_ONLY) {
            activeWindowVideoStats.totalTimeMs += enqueueTimeMs - receiveTimeMs
        }

        if (!fetchNextInputBuffer()) {
            return MoonBridge.DR_NEED_IDR
        }

        var codecFlags = 0
        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_SYNC_FRAME

            if (fusedIdrFrame && !csdSubmittedForThisFrame) {
                val buffer = nextInputBuffer
                if (buffer != null) {
                    for (vpsBuffer in vpsBuffers) {
                        buffer.put(vpsBuffer)
                    }
                    for (spsBuffer in spsBuffers) {
                        buffer.put(spsBuffer)
                    }
                    for (ppsBuffer in ppsBuffers) {
                        buffer.put(ppsBuffer)
                    }
                }
            }
        }

        var timestampUs = enqueueTimeMs * 1000
        if (timestampUs <= lastTimestampUs) {
            timestampUs = lastTimestampUs + 1
        }
        lastTimestampUs = timestampUs

        numFramesIn++

        val inputBuf = nextInputBuffer
        if (inputBuf != null && decodeUnitLength > inputBuf.limit() - inputBuf.position()) {
            val exception = IllegalArgumentException(
                "Decode unit length $decodeUnitLength too large for input buffer ${inputBuf.limit()}"
            )
            if (!reportedCrash) {
                reportedCrash = true
                crashListener.notifyCrash(exception)
            }
            throw RendererException(this, exception)
        }

        inputBuf?.put(decodeUnitData, 0, decodeUnitLength)

        if (!queueNextInputBuffer(timestampUs, codecFlags)) {
            return MoonBridge.DR_NEED_IDR
        }

        return MoonBridge.DR_OK
    }

    private fun replaySps(): Boolean {
        if (!fetchNextInputBuffer()) {
            return false
        }

        val sps = savedSps ?: return false
        val buffer = nextInputBuffer ?: return false

        buffer.put(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67))
        sps.profileIdc = 100
        doProfileSpecificSpsPatching(sps)

        val escapedNalu = H264Utils.writeSPS(sps, 128)
        buffer.put(escapedNalu)

        savedSps = null
        return queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
    }

    override fun getCapabilities(): Int {
        var capabilities = 0
        capabilities = capabilities or MoonBridge.CAPABILITY_SLICES_PER_FRAME(optimalSlicesPerFrame)

        if (refFrameInvalidationAvc) {
            capabilities = capabilities or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC
        }
        if (refFrameInvalidationHevc) {
            capabilities = capabilities or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC
        }
        if (refFrameInvalidationAv1) {
            capabilities = capabilities or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1
        }
        if (directSubmit) {
            capabilities = capabilities or MoonBridge.CAPABILITY_DIRECT_SUBMIT
        }

        return capabilities
    }

    val averageEndToEndLatency: Int
        get() {
            if (globalVideoStats.totalFramesReceived == 0) {
                return 0
            }
            return (globalVideoStats.totalTimeMs / globalVideoStats.totalFramesReceived).toInt()
        }

    val averageDecoderLatency: Int
        get() {
            if (globalVideoStats.totalFramesReceived == 0) {
                return 0
            }
            return (globalVideoStats.decoderTimeMs / globalVideoStats.totalFramesReceived).toInt()
        }

    val totalFramesRendered: Int
        get() = globalVideoStats.totalFramesRendered + activeWindowVideoStats.totalFramesRendered

    class DecoderHungException(private val hangTimeMs: Int) : RuntimeException() {
        override fun toString(): String {
            return "Hang time: $hangTimeMs ms${RendererException.DELIMITER}${super.toString()}"
        }
    }

    class RendererException(renderer: MediaCodecDecoderRenderer, e: Exception) : RuntimeException() {
        companion object {
            private const val serialVersionUID = 8985937536997012406L
            @JvmField
            val DELIMITER: String = if (BuildConfig.DEBUG) "\n" else " | "
        }

        private val text: String = generateText(renderer, e)

        override fun toString(): String = text

        private fun generateText(renderer: MediaCodecDecoderRenderer, originalException: Exception): String {
            val sb = java.lang.StringBuilder()

            val prefix = when {
                renderer.numVpsIn == 0 && renderer.numSpsIn == 0 && renderer.numPpsIn == 0 -> "PreSPSError"
                renderer.numSpsIn > 0 && renderer.numPpsIn == 0 -> "PrePPSError"
                renderer.numPpsIn > 0 && renderer.numFramesIn == 0 -> "PreIFrameError"
                renderer.numFramesIn > 0 && renderer.outputFormat == null -> "PreOutputConfigError"
                renderer.outputFormat != null && renderer.numFramesOut == 0 -> "PreOutputError"
                renderer.numFramesOut <= renderer.refreshRate * 30 -> "EarlyOutputError"
                else -> "ErrorWhileStreaming"
            }
            sb.append(prefix)

            sb.append("Format: ").append(String.format("%x", renderer.videoFormat)).append(DELIMITER)
            sb.append("AVC Decoder: ").append(renderer.avcDecoder?.name ?: "(none)").append(DELIMITER)
            sb.append("HEVC Decoder: ").append(renderer.hevcDecoder?.name ?: "(none)").append(DELIMITER)
            sb.append("AV1 Decoder: ").append(renderer.av1Decoder?.name ?: "(none)").append(DELIMITER)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.avcDecoder != null) {
                val avcWidthRange = renderer.avcDecoder!!.getCapabilitiesForType("video/avc").videoCapabilities.supportedWidths
                sb.append("AVC supported width range: ").append(avcWidthRange).append(DELIMITER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val avcFpsRange = renderer.avcDecoder!!.getCapabilitiesForType("video/avc").videoCapabilities.getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight)
                        sb.append("AVC achievable FPS range: ").append(avcFpsRange).append(DELIMITER)
                    } catch (e: IllegalArgumentException) {
                        sb.append("AVC achievable FPS range: UNSUPPORTED!").append(DELIMITER)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.hevcDecoder != null) {
                val hevcWidthRange = renderer.hevcDecoder!!.getCapabilitiesForType("video/hevc").videoCapabilities.supportedWidths
                sb.append("HEVC supported width range: ").append(hevcWidthRange).append(DELIMITER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val hevcFpsRange = renderer.hevcDecoder!!.getCapabilitiesForType("video/hevc").videoCapabilities.getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight)
                        sb.append("HEVC achievable FPS range: ").append(hevcFpsRange).append(DELIMITER)
                    } catch (e: IllegalArgumentException) {
                        sb.append("HEVC achievable FPS range: UNSUPPORTED!").append(DELIMITER)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.av1Decoder != null) {
                val av1WidthRange = renderer.av1Decoder!!.getCapabilitiesForType("video/av01").videoCapabilities.supportedWidths
                sb.append("AV1 supported width range: ").append(av1WidthRange).append(DELIMITER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val av1FpsRange = renderer.av1Decoder!!.getCapabilitiesForType("video/av01").videoCapabilities.getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight)
                        sb.append("AV1 achievable FPS range: ").append(av1FpsRange).append(DELIMITER)
                    } catch (e: IllegalArgumentException) {
                        sb.append("AV1 achievable FPS range: UNSUPPORTED!").append(DELIMITER)
                    }
                }
            }

            sb.append("Configured format: ").append(renderer.configuredFormat).append(DELIMITER)
            sb.append("Input format: ").append(renderer.inputFormat).append(DELIMITER)
            sb.append("Output format: ").append(renderer.outputFormat).append(DELIMITER)
            sb.append("Adaptive playback: ").append(renderer.adaptivePlayback).append(DELIMITER)
            sb.append("GL Renderer: ").append(renderer.glRenderer).append(DELIMITER)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sb.append("SOC: ").append(Build.SOC_MANUFACTURER).append(" - ").append(Build.SOC_MODEL).append(DELIMITER)
                sb.append("Performance class: ").append(Build.VERSION.MEDIA_PERFORMANCE_CLASS).append(DELIMITER)
            }

            sb.append("Consecutive crashes: ").append(renderer.consecutiveCrashCount).append(DELIMITER)
            sb.append("RFI active: ").append(renderer.refFrameInvalidationActive).append(DELIMITER)
            sb.append("Using modern SPS patching: ").append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O).append(DELIMITER)
            sb.append("Fused IDR frames: ").append(renderer.fusedIdrFrame).append(DELIMITER)
            sb.append("Video dimensions: ").append("${renderer.initialWidth}x${renderer.initialHeight}").append(DELIMITER)
            sb.append("FPS target: ").append(renderer.refreshRate).append(DELIMITER)
            sb.append("Bitrate: ").append(renderer.prefs.bitrate).append(" Kbps").append(DELIMITER)
            sb.append("CSD stats: ").append("${renderer.numVpsIn}, ${renderer.numSpsIn}, ${renderer.numPpsIn}").append(DELIMITER)
            sb.append("Frames in-out: ").append("${renderer.numFramesIn}, ${renderer.numFramesOut}").append(DELIMITER)
            sb.append("Total frames received: ").append(renderer.globalVideoStats.totalFramesReceived).append(DELIMITER)
            sb.append("Total frames rendered: ").append(renderer.globalVideoStats.totalFramesRendered).append(DELIMITER)
            sb.append("Frame losses: ").append("${renderer.globalVideoStats.framesLost} in ${renderer.globalVideoStats.frameLossEvents} loss events").append(DELIMITER)
            sb.append("Average end-to-end client latency: ").append(renderer.averageEndToEndLatency).append("ms").append(DELIMITER)
            sb.append("Average hardware decoder latency: ").append(renderer.averageDecoderLatency).append("ms").append(DELIMITER)
            sb.append("Frame pacing mode: ").append(renderer.prefs.framePacing).append(DELIMITER)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && originalException is CodecException) {
                val ce = originalException
                sb.append("Diagnostic Info: ").append(ce.diagnosticInfo).append(DELIMITER)
                sb.append("Recoverable: ").append(ce.isRecoverable).append(DELIMITER)
                sb.append("Transient: ").append(ce.isTransient).append(DELIMITER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    sb.append("Codec Error Code: ").append(ce.errorCode).append(DELIMITER)
                }
            }

            sb.append(originalException.toString())
            return sb.toString()
        }
    }
}
