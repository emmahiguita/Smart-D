package com.limelight.nvstream.http

import com.limelight.BuildConfig
import com.limelight.LimeLog
import com.limelight.nvstream.ConnectionContext
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.nvstream.jni.MoonBridge
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.security.KeyManagementException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.HashSet
import java.util.LinkedList
import java.util.Stack
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

class NvHTTP(
    address: ComputerDetails.AddressTuple,
    httpsPort: Int,
    uniqueId: String?,
    serverCert: X509Certificate?,
    cryptoProvider: LimelightCryptoProvider
) {
    companion object {
        private val activeCalls = Collections.synchronizedSet(HashSet<Call>())

        @JvmStatic
        fun cancelAllActiveCalls() {
            synchronized(activeCalls) {
                for (call in activeCalls) {
                    try {
                        call.cancel()
                    } catch (ignored: Exception) {
                    }
                }
                activeCalls.clear()
            }
        }

        private const val DEFAULT_HTTPS_PORT = 47984
        const val DEFAULT_HTTP_PORT = 47989
        const val SHORT_CONNECTION_TIMEOUT = 3000
        const val LONG_CONNECTION_TIMEOUT = 5000
        const val READ_TIMEOUT = 7000

        // Print URL and content to logcat on debug builds
        private val verbose = BuildConfig.DEBUG

        private val hexArray = "0123456789ABCDEF".toCharArray()

        private fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }

        private fun getDefaultTrustManager(): X509TrustManager {
            try {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)

                for (tm in tmf.trustManagers) {
                    if (tm is X509TrustManager) {
                        return tm
                    }
                }
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            } catch (e: KeyStoreException) {
                throw RuntimeException(e)
            }

            throw IllegalStateException("No X509 trust manager found")
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlString(r: Reader, tagname: String, throwIfMissing: Boolean): String? {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()

            xpp.setInput(r)
            var eventType = xpp.eventType
            val currentTag = Stack<String>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (xpp.name == "root") {
                            verifyResponseStatus(xpp)
                        }
                        currentTag.push(xpp.name)
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag.pop()
                    }
                    XmlPullParser.TEXT -> {
                        if (currentTag.peek() == tagname) {
                            return xpp.text
                        }
                    }
                }
                eventType = xpp.next()
            }

            if (throwIfMissing) {
                throw XmlPullParserException("Missing mandatory field in host response: $tagname")
            }

            return null
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlString(str: String, tagname: String, throwIfMissing: Boolean): String? {
            return getXmlString(StringReader(str), tagname, throwIfMissing)
        }

        @Throws(HostHttpResponseException::class)
        private fun verifyResponseStatus(xpp: XmlPullParser) {
            val statusCodeRaw = xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_code")
            var statusCode = statusCodeRaw.toLong().toInt()
            if (statusCode != 200) {
                var statusMsg = xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_message")
                if (statusCode == -1 && "Invalid" == statusMsg) {
                    statusCode = 418
                    statusMsg = "Missing audio capture device. Reinstall GeForce Experience."
                }
                throw HostHttpResponseException(statusCode, statusMsg)
            }
        }

        private fun makeTuple(address: String?, port: Int): ComputerDetails.AddressTuple? {
            return if (address == null) null else ComputerDetails.AddressTuple(address, port)
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getAppListByReader(r: Reader): LinkedList<NvApp> {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()

            xpp.setInput(r)
            var eventType = xpp.eventType
            val appList = LinkedList<NvApp>()
            val currentTag = Stack<String>()
            var rootTerminated = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (xpp.name == "root") {
                            verifyResponseStatus(xpp)
                        }
                        currentTag.push(xpp.name)
                        if (xpp.name == "App") {
                            appList.addLast(NvApp())
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag.pop()
                        if (xpp.name == "root") {
                            rootTerminated = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val app = appList.last
                        when (currentTag.peek()) {
                            "AppTitle" -> app.appName = xpp.text
                            "ID" -> app.setAppId(xpp.text)
                            "IsHdrSupported" -> app.isHdrSupported = xpp.text == "1"
                        }
                    }
                }
                eventType = xpp.next()
            }

            if (!rootTerminated) {
                throw XmlPullParserException("Malformed XML: Root tag was not terminated")
            }

            val i = appList.listIterator()
            while (i.hasNext()) {
                val app = i.next()
                if (!app.isInitialized) {
                    LimeLog.warning("GFE returned incomplete app: ${app.appId} ${app.appName}")
                    i.remove()
                }
            }

            return appList
        }
    }

    private var uniqueId: String = "0123456789ABCDEF"
    var pairingManager: PairingManager

    private var baseUrlHttp: HttpUrl
    private var httpsPort: Int = 0

    private lateinit var httpClientLongConnectTimeout: OkHttpClient
    private lateinit var httpClientLongConnectNoReadTimeout: OkHttpClient
    private lateinit var httpClientShortConnectTimeout: OkHttpClient

    private lateinit var defaultTrustManager: X509TrustManager
    private lateinit var trustManager: X509TrustManager
    private lateinit var keyManager: X509KeyManager
    private var serverCert: X509Certificate? = null

    init {
        this.serverCert = serverCert
        initializeHttpState(cryptoProvider)
        this.httpsPort = httpsPort

        try {
            var addressString = address.address
            if (addressString.contains(":") && addressString.contains(".")) {
                val addr = InetAddress.getByName(addressString)
                if (addr is Inet4Address) {
                    addressString = addr.hostAddress
                }
            }

            this.baseUrlHttp = HttpUrl.Builder()
                .scheme("http")
                .host(addressString)
                .port(address.port)
                .build()
        } catch (e: IllegalArgumentException) {
            throw IOException(e)
        }

        this.pairingManager = PairingManager(this, cryptoProvider)
    }

    fun setServerCert(serverCert: X509Certificate?) {
        this.serverCert = serverCert
    }

    private fun initializeHttpState(cryptoProvider: LimelightCryptoProvider) {
        keyManager = object : X509KeyManager {
            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String {
                return "Limelight-RSA"
            }

            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                val cert = cryptoProvider.getClientCertificate()
                return if (cert != null) arrayOf(cert) else null
            }

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

            override fun getPrivateKey(alias: String?): PrivateKey? {
                return cryptoProvider.getClientPrivateKey()
            }

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        }

        defaultTrustManager = getDefaultTrustManager()
        trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

            override fun checkClientTrusted(certs: Array<out X509Certificate>?, authType: String?) {
                throw IllegalStateException("Should never be called")
            }

            override fun checkServerTrusted(certs: Array<out X509Certificate>?, authType: String?) {
                try {
                    defaultTrustManager.checkServerTrusted(certs, authType)
                } catch (e: CertificateException) {
                    if (certs != null && certs.size == 1 && this@NvHTTP.serverCert != null) {
                        if (certs[0] != this@NvHTTP.serverCert) {
                            throw CertificateException("Certificate mismatch")
                        }
                    } else {
                        throw e
                    }
                }
            }
        }

        val hv = HostnameVerifier { hostname, session ->
            try {
                val certificates = session.peerCertificates
                if (certificates.size == 1 && certificates[0] == this@NvHTTP.serverCert) {
                    return@HostnameVerifier true
                }
            } catch (e: SSLPeerUnverifiedException) {
                e.printStackTrace()
            }
            HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
        }

        httpClientLongConnectTimeout = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .hostnameVerifier(hv)
            .readTimeout(READ_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(LONG_CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(true)
            .build()

        httpClientShortConnectTimeout = httpClientLongConnectTimeout.newBuilder()
            .connectTimeout(SHORT_CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .build()

        httpClientLongConnectNoReadTimeout = httpClientLongConnectTimeout.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @Throws(IOException::class)
    fun getHttpsUrl(likelyOnline: Boolean): HttpUrl {
        if (httpsPort == 0) {
            httpsPort = getHttpsPort(
                openHttpConnectionToString(
                    if (likelyOnline) httpClientLongConnectTimeout else httpClientShortConnectTimeout,
                    baseUrlHttp, "serverinfo"
                )
            )
        }

        return HttpUrl.Builder().scheme("https").host(baseUrlHttp.host).port(httpsPort).build()
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getServerInfo(likelyOnline: Boolean): String {
        val resp: String
        val client = if (likelyOnline) httpClientLongConnectTimeout else httpClientShortConnectTimeout

        if (serverCert != null) {
            try {
                try {
                    resp = openHttpConnectionToString(client, getHttpsUrl(likelyOnline), "serverinfo")
                } catch (e: SSLHandshakeException) {
                    if (e.cause is CertificateException) {
                        throw HostHttpResponseException(401, "Server certificate mismatch")
                    } else {
                        throw e
                    }
                }
                getServerVersion(resp)
            } catch (e: HostHttpResponseException) {
                if (e.errorCode == 401) {
                    return openHttpConnectionToString(client, baseUrlHttp, "serverinfo")
                }
                throw e
            }
            return resp
        } else {
            return openHttpConnectionToString(client, baseUrlHttp, "serverinfo")
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getComputerDetails(serverInfo: String): ComputerDetails {
        val details = ComputerDetails()

        details.name = getXmlString(serverInfo, "hostname", false) ?: ""
        if (details.name.isNullOrEmpty()) {
            details.name = "UNKNOWN"
        }

        details.uuid = getXmlString(serverInfo, "uniqueid", true) ?: ""
        details.httpsPort = getHttpsPort(serverInfo)
        details.macAddress = getXmlString(serverInfo, "mac", false) ?: ""
        details.localAddress = makeTuple(getXmlString(serverInfo, "LocalIP", false), baseUrlHttp.port)
        details.externalPort = getExternalPort(serverInfo)
        details.remoteAddress = makeTuple(getXmlString(serverInfo, "ExternalIP", false), details.externalPort)
        details.pairState = getPairState(serverInfo)
        details.runningGameId = getCurrentGame(serverInfo)
        details.nvidiaServer = getXmlString(serverInfo, "state", true)?.contains("MJOLNIR") == true
        details.state = ComputerDetails.State.ONLINE

        return details
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getComputerDetails(likelyOnline: Boolean): ComputerDetails {
        return getComputerDetails(getServerInfo(likelyOnline))
    }

    private fun performAndroidTlsHack(client: OkHttpClient): OkHttpClient {
        return try {
            val sc = SSLContext.getInstance("TLS")
            sc.init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trustManager), SecureRandom())
            client.newBuilder().sslSocketFactory(sc.socketFactory, trustManager).build()
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        } catch (e: KeyManagementException) {
            throw RuntimeException(e)
        }
    }

    private fun getCompleteUrl(baseUrl: HttpUrl, path: String, query: String?): HttpUrl {
        return baseUrl.newBuilder()
            .addPathSegment(path)
            .query(query)
            .addQueryParameter("uniqueid", uniqueId)
            .addQueryParameter("uuid", UUID.randomUUID().toString())
            .build()
    }

    @Throws(IOException::class)
    private fun openHttpConnection(client: OkHttpClient, baseUrl: HttpUrl, path: String): ResponseBody? {
        return openHttpConnection(client, baseUrl, path, null)
    }

    @Throws(IOException::class)
    private fun openHttpConnection(client: OkHttpClient, baseUrl: HttpUrl, path: String, query: String?): ResponseBody? {
        val completeUrl = getCompleteUrl(baseUrl, path, query)
        val request = Request.Builder()
            .url(completeUrl)
            .header("Connection", "close")
            .get()
            .build()
        val call = performAndroidTlsHack(client).newCall(request)
        activeCalls.add(call)
        try {
            val response = call.execute()
            val body = response.body
            if (response.isSuccessful) {
                return body
            }

            body?.close()

            if (response.code == 404) {
                throw FileNotFoundException(completeUrl.toString())
            } else {
                throw HostHttpResponseException(response.code, response.message)
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    @Throws(IOException::class)
    private fun openHttpConnectionToString(client: OkHttpClient, baseUrl: HttpUrl, path: String): String {
        return openHttpConnectionToString(client, baseUrl, path, null)
    }

    @Throws(IOException::class)
    private fun openHttpConnectionToString(client: OkHttpClient, baseUrl: HttpUrl, path: String, query: String?): String {
        try {
            val resp = openHttpConnection(client, baseUrl, path, query) ?: throw IOException("Empty response body")
            val respString = resp.string()
            resp.close()

            if (verbose && path != "serverinfo" && path != "pair") {
                LimeLog.info("${getCompleteUrl(baseUrl, path, query)} -> $respString")
            }

            return respString
        } catch (e: IOException) {
            if (verbose && path != "serverinfo" && path != "pair") {
                LimeLog.warning("${getCompleteUrl(baseUrl, path, query)} -> ${e.message}")
                e.printStackTrace()
            }
            throw e
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerVersion(serverInfo: String): String? {
        return getXmlString(serverInfo, "appversion", true)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getPairState(): PairState {
        return getPairState(getServerInfo(true))
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getPairState(serverInfo: String): PairState {
        return if (getXmlString(serverInfo, "PairStatus", true) == "1") {
            PairState.PAIRED
        } else {
            PairState.NOT_PAIRED
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getMaxLumaPixelsH264(serverInfo: String): Long {
        val str = getXmlString(serverInfo, "MaxLumaPixelsH264", false)
        return str?.toLongOrNull() ?: 0L
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getMaxLumaPixelsHEVC(serverInfo: String): Long {
        val str = getXmlString(serverInfo, "MaxLumaPixelsHEVC", false)
        return str?.toLongOrNull() ?: 0L
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerCodecModeSupport(serverInfo: String): Long {
        val str = getXmlString(serverInfo, "ServerCodecModeSupport", false)
        return str?.toLongOrNull() ?: 0L
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getGpuType(serverInfo: String): String? {
        return getXmlString(serverInfo, "gputype", false)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getGfeVersion(serverInfo: String): String? {
        return getXmlString(serverInfo, "GfeVersion", false)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun supports4K(serverInfo: String): Boolean {
        val gfeVersionStr = getXmlString(serverInfo, "GfeVersion", false)
        return !(gfeVersionStr == null || gfeVersionStr.startsWith("2."))
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getCurrentGame(serverInfo: String): Int {
        return if (getXmlString(serverInfo, "state", true)?.endsWith("_SERVER_BUSY") == true) {
            getXmlString(serverInfo, "currentgame", true)?.toIntOrNull() ?: 0
        } else {
            0
        }
    }

    fun getHttpsPort(serverInfo: String): Int {
        return try {
            getXmlString(serverInfo, "HttpsPort", true)?.toInt() ?: DEFAULT_HTTPS_PORT
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            DEFAULT_HTTPS_PORT
        } catch (e: IOException) {
            e.printStackTrace()
            DEFAULT_HTTPS_PORT
        }
    }

    fun getExternalPort(serverInfo: String): Int {
        return try {
            getXmlString(serverInfo, "ExternalPort", true)?.toInt() ?: baseUrlHttp.port
        } catch (e: XmlPullParserException) {
            baseUrlHttp.port
        } catch (e: IOException) {
            e.printStackTrace()
            baseUrlHttp.port
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getAppById(appId: Int): NvApp? {
        val appList = getAppList()
        for (appFromList in appList) {
            if (appFromList.appId == appId) {
                return appFromList
            }
        }
        return null
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getAppByName(appName: String): NvApp? {
        val appList = getAppList()
        for (appFromList in appList) {
            if (appFromList.appName.equals(appName, ignoreCase = true)) {
                return appFromList
            }
        }
        return null
    }

    
    @Throws(IOException::class)
    fun getAppListRaw(): String {
        return openHttpConnectionToString(httpClientLongConnectTimeout, getHttpsUrl(true), "applist")
    }

    @Throws(HostHttpResponseException::class, IOException::class, XmlPullParserException::class)
    fun getAppList(): LinkedList<NvApp> {
        return if (verbose) {
            getAppListByReader(StringReader(getAppListRaw()))
        } else {
            val resp = openHttpConnection(httpClientLongConnectTimeout, getHttpsUrl(true), "applist")
                ?: throw IOException("Empty applist response")
            resp.use {
                getAppListByReader(InputStreamReader(it.byteStream()))
            }
        }
    }

    @Throws(HostHttpResponseException::class, IOException::class)
    fun executePairingCommand(additionalArguments: String, enableReadTimeout: Boolean): String {
        return openHttpConnectionToString(
            if (enableReadTimeout) httpClientLongConnectTimeout else httpClientLongConnectNoReadTimeout,
            baseUrlHttp, "pair", "devicename=roth&updateState=1&$additionalArguments"
        )
    }

    @Throws(HostHttpResponseException::class, IOException::class)
    fun executePairingChallenge(): String {
        return openHttpConnectionToString(
            httpClientLongConnectTimeout, getHttpsUrl(true),
            "pair", "devicename=roth&updateState=1&phrase=pairchallenge"
        )
    }

    @Throws(IOException::class)
    fun unpair() {
        openHttpConnectionToString(httpClientLongConnectTimeout, baseUrlHttp, "unpair")
    }

    @Throws(IOException::class)
    fun getBoxArt(app: NvApp): InputStream {
        val resp = openHttpConnection(
            httpClientLongConnectTimeout, getHttpsUrl(true),
            "appasset", "appid=" + app.appId + "&AssetType=2&AssetIdx=0"
        ) ?: throw IOException("Empty box art response")
        return resp.byteStream()
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerMajorVersion(serverInfo: String): Int {
        return getServerAppVersionQuad(serverInfo)[0]
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerAppVersionQuad(serverInfo: String): IntArray {
        val serverVersion = getServerVersion(serverInfo)
            ?: throw IllegalArgumentException("Missing server version field")
        val serverVersionSplit = serverVersion.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        require(serverVersionSplit.size == 4) { "Malformed server version field: $serverVersion" }
        val ret = IntArray(serverVersionSplit.size)
        for (i in ret.indices) {
            ret[i] = serverVersionSplit[i].toInt()
        }
        return ret
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun launchApp(context: ConnectionContext, verb: String, appId: Int, enableHdr: Boolean): Boolean {
        val fps = if (context.isNvidiaServerSoftware && context.streamConfig.launchRefreshRate > 60) {
            0
        } else {
            context.streamConfig.launchRefreshRate
        }

        var enableSops = context.streamConfig.sops
        if (context.isNvidiaServerSoftware) {
            if (context.negotiatedWidth * context.negotiatedHeight > 1280 * 720 &&
                context.negotiatedWidth * context.negotiatedHeight != 1920 * 1080 &&
                context.negotiatedWidth * context.negotiatedHeight != 3840 * 2160
            ) {
                LimeLog.info("Disabling SOPS due to non-standard resolution: ${context.negotiatedWidth}x${context.negotiatedHeight}")
                enableSops = false
            }
        }

        val xmlStr = openHttpConnectionToString(
            httpClientLongConnectNoReadTimeout, getHttpsUrl(true), verb,
            "appid=" + appId +
                    "&mode=" + context.negotiatedWidth + "x" + context.negotiatedHeight + "x" + fps +
                    "&additionalStates=1&sops=" + (if (enableSops) 1 else 0) +
                    "&rikey=" + bytesToHex(context.riKey.encoded) +
                    "&rikeyid=" + context.riKeyId +
                    (if (!enableHdr) "" else "&hdrMode=1&clientHdrCapVersion=0&clientHdrCapSupportedFlagsInUint32=0&clientHdrCapMetaDataId=NV_STATIC_METADATA_TYPE_1&clientHdrCapDisplayData=0x0x0x0x0x0x0x0x0x0x0") +
                    "&localAudioPlayMode=" + (if (context.streamConfig.playLocalAudio) 1 else 0) +
                    "&surroundAudioInfo=" + context.streamConfig.audioConfiguration.surroundAudioInfo +
                    "&remoteControllersBitmap=" + context.streamConfig.attachedGamepadMask +
                    "&gcmap=" + context.streamConfig.attachedGamepadMask +
                    "&gcpersist=" + (if (context.streamConfig.persistGamepadsAfterDisconnect) 1 else 0) +
                    MoonBridge.getLaunchUrlQueryParameters()
        )

        if ((verb == "launch" && getXmlString(xmlStr, "gamesession", true) != "0") ||
            (verb == "resume" && getXmlString(xmlStr, "resume", true) != "0")
        ) {
            context.rtspSessionUrl = getXmlString(xmlStr, "sessionUrl0", false)
            return true
        } else {
            return false
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun quitApp(): Boolean {
        val xmlStr = openHttpConnectionToString(httpClientLongConnectNoReadTimeout, getHttpsUrl(true), "cancel")
        if (getXmlString(xmlStr, "cancel", true) == "0") {
            return false
        }

        if (getCurrentGame(getServerInfo(true)) != 0) {
            throw HostHttpResponseException(599, "")
        }

        return true
    }
}
