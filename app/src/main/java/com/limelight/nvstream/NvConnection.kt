package com.limelight.nvstream

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.av.video.VideoDecoderRenderer
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.nvstream.http.LimelightCryptoProvider
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Semaphore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class NvConnection(
    private val appContext: Context,
    host: ComputerDetails.AddressTuple,
    httpsPort: Int,
    private val uniqueId: String,
    config: StreamConfiguration,
    private val cryptoProvider: LimelightCryptoProvider,
    serverCert: X509Certificate?
) {
    private val context = ConnectionContext()
    private val isMonkey: Boolean

    init {
        context.serverAddress = host
        context.httpsPort = httpsPort
        context.streamConfig = config
        context.serverCert = serverCert

        context.riKey = generateRiAesKey()
        context.riKeyId = generateRiKeyId()

        isMonkey = ActivityManager.isUserAMonkey()
    }

    fun stop() {
        MoonBridge.interruptConnection()
        synchronized(MoonBridge::class.java) {
            MoonBridge.stopConnection()
            MoonBridge.cleanupBridge()
        }
        connectionAllowed.release()
    }

    @Throws(IOException::class)
    private fun resolveServerAddress(): InetAddress {
        val addrs = InetAddress.getAllByName(context.serverAddress.address)
        for (addr in addrs) {
            try {
                Socket().use { s ->
                    s.setSoLinger(true, 0)
                    s.connect(InetSocketAddress(addr, context.serverAddress.port), 1000)
                    return addr
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        if (addrs.isNotEmpty()) {
            return addrs[0]
        } else {
            throw IOException("No addresses found for " + context.serverAddress)
        }
    }

    private fun detectServerConnectionType(): Int {
        val connMgr = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connMgr.activeNetwork
            if (activeNetwork != null) {
                val netCaps = connMgr.getNetworkCapabilities(activeNetwork)
                if (netCaps != null) {
                    if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    ) {
                        return StreamConfiguration.STREAM_CFG_REMOTE
                    } else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return StreamConfiguration.STREAM_CFG_REMOTE
                    }
                }

                val linkProperties = connMgr.getLinkProperties(activeNetwork)
                if (linkProperties != null) {
                    val serverAddress: InetAddress
                    try {
                        serverAddress = resolveServerAddress()
                    } catch (e: IOException) {
                        e.printStackTrace()
                        return StreamConfiguration.STREAM_CFG_AUTO
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val nat64Prefix = linkProperties.nat64Prefix
                        if (nat64Prefix != null && nat64Prefix.contains(serverAddress)) {
                            return StreamConfiguration.STREAM_CFG_REMOTE
                        }
                    }

                    for (route in linkProperties.routes) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && route.type != RouteInfo.RTN_UNICAST) {
                            continue
                        }
                        if (route.matches(serverAddress)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                if (!route.hasGateway()) {
                                    return StreamConfiguration.STREAM_CFG_LOCAL
                                }
                            } else {
                                val gateway = route.gateway
                                if (gateway == null || gateway.isAnyLocalAddress) {
                                    return StreamConfiguration.STREAM_CFG_LOCAL
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val activeNetworkInfo = connMgr.activeNetworkInfo
            if (activeNetworkInfo != null) {
                when (activeNetworkInfo.type) {
                    ConnectivityManager.TYPE_VPN,
                    ConnectivityManager.TYPE_MOBILE,
                    ConnectivityManager.TYPE_MOBILE_DUN,
                    ConnectivityManager.TYPE_MOBILE_HIPRI,
                    ConnectivityManager.TYPE_MOBILE_MMS,
                    ConnectivityManager.TYPE_MOBILE_SUPL,
                    ConnectivityManager.TYPE_WIMAX -> return StreamConfiguration.STREAM_CFG_REMOTE
                }
            }
        }
        return StreamConfiguration.STREAM_CFG_AUTO
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun startApp(): Boolean {
        val h = NvHTTP(
            context.serverAddress,
            context.httpsPort,
            uniqueId,
            context.serverCert,
            cryptoProvider
        )

        val serverInfo = h.getServerInfo(true)

        context.serverAppVersion = h.getServerVersion(serverInfo)
        if (context.serverAppVersion == null) {
            context.connListener?.displayMessage("Versión del servidor no válida")
            return false
        }

        val details = h.getComputerDetails(serverInfo)
        context.isNvidiaServerSoftware = details.nvidiaServer

        context.serverGfeVersion = h.getGfeVersion(serverInfo)

        if (h.getPairState(serverInfo) != PairingManager.PairState.PAIRED) {
            context.connListener?.displayMessage("El dispositivo no está emparejado con el ordenador")
            return false
        }

        context.serverCodecModeSupport = h.getServerCodecModeSupport(serverInfo).toInt()

        context.negotiatedHdr = (context.streamConfig.supportedVideoFormats and MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0
        if ((context.serverCodecModeSupport and 0x20200) == 0 && context.negotiatedHdr) {
            context.connListener?.displayTransientMessage("La GPU de tu PC no soporta streaming HDR. La transmisión será SDR.")
            context.negotiatedHdr = false
        }

        if ((context.streamConfig.width > 4096 || context.streamConfig.height > 4096) &&
            (h.getServerCodecModeSupport(serverInfo).toInt() and 0x200) == 0 && context.isNvidiaServerSoftware
        ) {
            context.connListener?.displayMessage("Tu PC no soporta streaming a resoluciones superiores a 4K.")
            return false
        } else if ((context.streamConfig.width > 4096 || context.streamConfig.height > 4096) &&
            (context.streamConfig.supportedVideoFormats and MoonBridge.VIDEO_FORMAT_MASK_H264.inv()) == 0
        ) {
            context.connListener?.displayMessage("Tu dispositivo debe soportar HEVC o AV1 para transmitir a resoluciones superiores a 4K.")
            return false
        } else if (context.streamConfig.height >= 2160 && !h.supports4K(serverInfo)) {
            context.connListener?.displayTransientMessage("Debes actualizar GeForce Experience para transmitir en 4K. La transmisión será de 1080p.")
            context.negotiatedWidth = 1920
            context.negotiatedHeight = 1080
        } else {
            context.negotiatedWidth = context.streamConfig.width
            context.negotiatedHeight = context.streamConfig.height
        }

        if (context.streamConfig.remote == StreamConfiguration.STREAM_CFG_AUTO) {
            context.negotiatedRemoteStreaming = detectServerConnectionType()
            context.negotiatedPacketSize = if (context.negotiatedRemoteStreaming == StreamConfiguration.STREAM_CFG_REMOTE) {
                1024
            } else {
                context.streamConfig.maxPacketSize
            }
        } else {
            context.negotiatedRemoteStreaming = context.streamConfig.remote
            context.negotiatedPacketSize = context.streamConfig.maxPacketSize
        }

        var app = context.streamConfig.app

        if (!context.streamConfig.app.isInitialized) {
            LimeLog.info("Using deprecated app lookup method - Please specify an app ID in your StreamConfiguration instead")
            val lookedUpApp = h.getAppByName(context.streamConfig.app.appName)
            if (lookedUpApp == null) {
                context.connListener?.displayMessage("La aplicación " + context.streamConfig.app.appName + " no está en la lista de GFE")
                return false
            }
            app = lookedUpApp
        }

        if (h.getCurrentGame(serverInfo) != 0) {
            try {
                if (h.getCurrentGame(serverInfo) == app.appId) {
                    if (!h.launchApp(context, "resume", app.appId, context.negotiatedHdr)) {
                        context.connListener?.displayMessage("Error al reanudar la sesión existente")
                        return false
                    }
                } else {
                    return quitAndLaunch(h, context)
                }
            } catch (e: HostHttpResponseException) {
                if (e.errorCode == 470) {
                    context.connListener?.displayMessage(
                        "Esta sesión no fue iniciada por este dispositivo," +
                                " por lo que no puede reanudarse. Finaliza la transmisión en el dispositivo " +
                                "original o en el PC e intenta de nuevo. (Código de error: " + e.errorCode + ")"
                    )
                    return false
                } else if (e.errorCode == 525) {
                    context.connListener?.displayMessage(
                        "La aplicación está minimizada. Reanúdala manualmente en el PC o " +
                                "finaliza la sesión y vuelve a iniciar la transmisión."
                    )
                    return false
                } else {
                    throw e
                }
            }
            LimeLog.info("Resumed existing game session")
            return true
        } else {
            return launchNotRunningApp(h, context)
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    protected fun quitAndLaunch(h: NvHTTP, context: ConnectionContext): Boolean {
        try {
            if (!h.quitApp()) {
                context.connListener?.displayMessage("Error al cerrar la sesión anterior. Debes cerrarla manualmente")
                return false
            }
        } catch (e: HostHttpResponseException) {
            if (e.errorCode == 599) {
                context.connListener?.displayMessage(
                    "Esta sesión no fue iniciada por este dispositivo," +
                            " por lo que no puede cerrarse. Finaliza la transmisión en el dispositivo " +
                            "original o en el PC. (Código de error: " + e.errorCode + ")"
                )
                return false
            } else {
                throw e
            }
        }

        return launchNotRunningApp(h, context)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    private fun launchNotRunningApp(h: NvHTTP, context: ConnectionContext): Boolean {
        if (!h.launchApp(context, "launch", context.streamConfig.app.appId, context.negotiatedHdr)) {
            context.connListener?.displayMessage("Error al iniciar la aplicación")
            return false
        }
        LimeLog.info("Launched new game session")
        return true
    }

    fun start(
        audioRenderer: AudioRenderer,
        videoDecoderRenderer: VideoDecoderRenderer,
        connectionListener: NvConnectionListener
    ) {
        Thread {
            context.connListener = connectionListener
            context.videoCapabilities = videoDecoderRenderer.getCapabilities()

            val appName = context.streamConfig.app.appName

            context.connListener?.stageStarting(appName)

            try {
                if (!startApp()) {
                    context.connListener?.stageFailed(appName, 0, 0)
                    return@Thread
                }
                context.connListener?.stageComplete(appName)
            } catch (e: HostHttpResponseException) {
                e.printStackTrace()
                context.connListener?.displayMessage(e.message ?: "Unknown error")
                context.connListener?.stageFailed(appName, 0, e.errorCode)
                return@Thread
            } catch (e: Exception) {
                e.printStackTrace()
                context.connListener?.displayMessage(e.message ?: "Unknown error")
                context.connListener?.stageFailed(
                    appName,
                    MoonBridge.ML_PORT_FLAG_TCP_47984 or MoonBridge.ML_PORT_FLAG_TCP_47989,
                    0
                )
                return@Thread
            }

            val ib = ByteBuffer.allocate(16)
            ib.putInt(context.riKeyId)

            try {
                connectionAllowed.acquire()
            } catch (e: InterruptedException) {
                context.connListener?.displayMessage(e.message ?: "Interrupted")
                context.connListener?.stageFailed(appName, 0, 0)
                return@Thread
            }

            synchronized(MoonBridge::class.java) {
                MoonBridge.setupBridge(videoDecoderRenderer, audioRenderer, connectionListener)
                val ret = MoonBridge.startConnection(
                    context.serverAddress.address,
                    context.serverAppVersion,
                    context.serverGfeVersion,
                    context.rtspSessionUrl,
                    context.serverCodecModeSupport,
                    context.negotiatedWidth,
                    context.negotiatedHeight,
                    context.streamConfig.refreshRate,
                    context.streamConfig.bitrate,
                    context.negotiatedPacketSize,
                    context.negotiatedRemoteStreaming,
                    context.streamConfig.audioConfiguration.toInt(),
                    context.streamConfig.supportedVideoFormats,
                    context.streamConfig.clientRefreshRateX100,
                    context.riKey.encoded,
                    ib.array(),
                    context.videoCapabilities,
                    context.streamConfig.colorSpace,
                    context.streamConfig.colorRange
                )
                if (ret != 0) {
                    connectionAllowed.release()
                    return@Thread
                }
            }
        }.start()
    }

    fun sendMouseMove(deltaX: Short, deltaY: Short) {
        if (!isMonkey) {
            MoonBridge.sendMouseMove(deltaX, deltaY)
        }
    }

    fun sendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short) {
        if (!isMonkey) {
            MoonBridge.sendMousePosition(x, y, referenceWidth, referenceHeight)
        }
    }

    fun sendMouseMoveAsMousePosition(deltaX: Short, deltaY: Short, referenceWidth: Short, referenceHeight: Short) {
        if (!isMonkey) {
            MoonBridge.sendMouseMoveAsMousePosition(deltaX, deltaY, referenceWidth, referenceHeight)
        }
    }

    fun sendMouseButtonDown(mouseButton: Byte) {
        if (!isMonkey) {
            MoonBridge.sendMouseButton(MouseButtonPacket.PRESS_EVENT, mouseButton)
        }
    }

    fun sendMouseButtonUp(mouseButton: Byte) {
        if (!isMonkey) {
            MoonBridge.sendMouseButton(MouseButtonPacket.RELEASE_EVENT, mouseButton)
        }
    }

    fun sendControllerInput(
        controllerNumber: Short,
        activeGamepadMask: Short,
        buttonFlags: Int,
        leftTrigger: Byte,
        rightTrigger: Byte,
        leftStickX: Short,
        leftStickY: Short,
        rightStickX: Short,
        rightStickY: Short
    ) {
        if (!isMonkey) {
            MoonBridge.sendMultiControllerInput(
                controllerNumber, activeGamepadMask, buttonFlags,
                leftTrigger, rightTrigger, leftStickX, leftStickY, rightStickX, rightStickY
            )
        }
    }

    fun sendKeyboardInput(keyMap: Short, keyDirection: Byte, modifier: Byte, flags: Byte) {
        if (!isMonkey) {
            MoonBridge.sendKeyboardInput(keyMap, keyDirection, modifier, flags)
        }
    }

    fun sendMouseScroll(scrollClicks: Byte) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResScroll((scrollClicks * 120).toShort())
        }
    }

    fun sendMouseHScroll(scrollClicks: Byte) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResHScroll((scrollClicks * 120).toShort())
        }
    }

    fun sendMouseHighResScroll(scrollAmount: Short) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResScroll(scrollAmount)
        }
    }

    fun sendMouseHighResHScroll(scrollAmount: Short) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResHScroll(scrollAmount)
        }
    }

    fun sendTouchEvent(
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        contactAreaMajor: Float,
        contactAreaMinor: Float,
        rotation: Short
    ): Int {
        return if (!isMonkey) {
            MoonBridge.sendTouchEvent(
                eventType, pointerId, x, y, pressureOrDistance,
                contactAreaMajor, contactAreaMinor, rotation
            )
        } else {
            MoonBridge.LI_ERR_UNSUPPORTED
        }
    }

    fun sendPenEvent(
        eventType: Byte, toolType: Byte, penButtons: Byte, x: Float, y: Float,
        pressureOrDistance: Float, contactAreaMajor: Float, contactAreaMinor: Float,
        rotation: Short, tilt: Byte
    ): Int {
        return if (!isMonkey) {
            MoonBridge.sendPenEvent(
                eventType, toolType, penButtons, x, y, pressureOrDistance,
                contactAreaMajor, contactAreaMinor, rotation, tilt
            )
        } else {
            MoonBridge.LI_ERR_UNSUPPORTED
        }
    }

    fun sendControllerArrivalEvent(
        controllerNumber: Byte,
        activeGamepadMask: Short,
        type: Byte,
        supportedButtonFlags: Int,
        capabilities: Short
    ): Int {
        return MoonBridge.sendControllerArrivalEvent(
            controllerNumber,
            activeGamepadMask,
            type,
            supportedButtonFlags,
            capabilities
        )
    }

    fun sendControllerTouchEvent(
        controllerNumber: Byte,
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float
    ): Int {
        return if (!isMonkey) {
            MoonBridge.sendControllerTouchEvent(controllerNumber, eventType, pointerId, x, y, pressure)
        } else {
            MoonBridge.LI_ERR_UNSUPPORTED
        }
    }

    fun sendControllerMotionEvent(
        controllerNumber: Byte,
        motionType: Byte,
        x: Float,
        y: Float,
        z: Float
    ): Int {
        return if (!isMonkey) {
            MoonBridge.sendControllerMotionEvent(controllerNumber, motionType, x, y, z)
        } else {
            MoonBridge.LI_ERR_UNSUPPORTED
        }
    }

    fun sendControllerBatteryEvent(controllerNumber: Byte, batteryState: Byte, batteryPercentage: Byte) {
        MoonBridge.sendControllerBatteryEvent(controllerNumber, batteryState, batteryPercentage)
    }

    fun sendUtf8Text(text: String) {
        if (!isMonkey) {
            MoonBridge.sendUtf8Text(text)
        }
    }

    companion object {
        private val connectionAllowed = Semaphore(1)

        private fun generateRiAesKey(): SecretKey {
            return try {
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(128)
                keyGen.generateKey()
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }

        private fun generateRiKeyId(): Int {
            return SecureRandom().nextInt()
        }

        @JvmStatic
        fun findExternalAddressForMdns(stunHostname: String, stunPort: Int): String? {
            return MoonBridge.findExternalAddressIP4(stunHostname, stunPort)
        }
    }
}
