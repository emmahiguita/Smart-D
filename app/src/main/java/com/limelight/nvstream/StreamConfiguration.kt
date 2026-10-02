package com.limelight.nvstream

import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.jni.MoonBridge

class StreamConfiguration private constructor() {

    var app: NvApp = NvApp("Steam", 0, false)
        private set
    var width = 1280
        private set
    var height = 720
        private set
    var refreshRate = 60
        private set
    var launchRefreshRate = 60
        private set
    var clientRefreshRateX100 = 0
        private set
    var bitrate = 10000
        private set
    var sops = true
        private set
    var adaptiveResolutionEnabled = false
        private set
    var playLocalAudio = false
        private set
    var maxPacketSize = 1024
        private set
    var remote = STREAM_CFG_AUTO
        private set
    var audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO
        private set
    var supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264
        private set
    var attachedGamepadMask = 0
        private set
    var encryptionFlags = 0
        private set
    var colorRange = 0
        private set
    var colorSpace = 0
        private set
    var persistGamepadsAfterDisconnect = false
        private set

    class Builder {
        private val config = StreamConfiguration()

        fun setApp(app: NvApp): Builder {
            config.app = app
            return this
        }

        fun setRemoteConfiguration(remote: Int): Builder {
            config.remote = remote
            return this
        }

        fun setResolution(width: Int, height: Int): Builder {
            config.width = width
            config.height = height
            return this
        }

        fun setRefreshRate(refreshRate: Int): Builder {
            config.refreshRate = refreshRate
            return this
        }

        fun setLaunchRefreshRate(refreshRate: Int): Builder {
            config.launchRefreshRate = refreshRate
            return this
        }

        fun setBitrate(bitrate: Int): Builder {
            config.bitrate = bitrate
            return this
        }

        fun setEnableSops(enable: Boolean): Builder {
            config.sops = enable
            return this
        }

        fun enableAdaptiveResolution(enable: Boolean): Builder {
            config.adaptiveResolutionEnabled = enable
            return this
        }

        fun enableLocalAudioPlayback(enable: Boolean): Builder {
            config.playLocalAudio = enable
            return this
        }

        fun setMaxPacketSize(maxPacketSize: Int): Builder {
            config.maxPacketSize = maxPacketSize
            return this
        }

        fun setAttachedGamepadMask(attachedGamepadMask: Int): Builder {
            config.attachedGamepadMask = attachedGamepadMask
            return this
        }

        fun setAttachedGamepadMaskByCount(gamepadCount: Int): Builder {
            config.attachedGamepadMask = 0
            for (i in 0 until 4) {
                if (gamepadCount > i) {
                    config.attachedGamepadMask = config.attachedGamepadMask or (1 shl i)
                }
            }
            return this
        }

        fun setPersistGamepadsAfterDisconnect(value: Boolean): Builder {
            config.persistGamepadsAfterDisconnect = value
            return this
        }

        fun setClientRefreshRateX100(refreshRateX100: Int): Builder {
            config.clientRefreshRateX100 = refreshRateX100
            return this
        }

        fun setAudioConfiguration(audioConfig: MoonBridge.AudioConfiguration): Builder {
            config.audioConfiguration = audioConfig
            return this
        }

        fun setSupportedVideoFormats(supportedVideoFormats: Int): Builder {
            config.supportedVideoFormats = supportedVideoFormats
            return this
        }

        fun setColorRange(colorRange: Int): Builder {
            config.colorRange = colorRange
            return this
        }

        fun setColorSpace(colorSpace: Int): Builder {
            config.colorSpace = colorSpace
            return this
        }

        fun build(): StreamConfiguration {
            return config
        }
    }

    companion object {
        const val INVALID_APP_ID = 0

        const val STREAM_CFG_LOCAL = 0
        const val STREAM_CFG_REMOTE = 1
        const val STREAM_CFG_AUTO = 2
    }
}
