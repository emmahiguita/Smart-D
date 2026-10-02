package com.limelight.nvstream

import com.limelight.nvstream.http.ComputerDetails
import java.security.cert.X509Certificate
import javax.crypto.SecretKey

class ConnectionContext {
    lateinit var serverAddress: ComputerDetails.AddressTuple
    var httpsPort = 0
    var isNvidiaServerSoftware = false
    var serverCert: X509Certificate? = null
    lateinit var streamConfig: StreamConfiguration
    lateinit var connListener: NvConnectionListener
    lateinit var riKey: SecretKey
    var riKeyId = 0

    var serverAppVersion: String? = null
    var serverGfeVersion: String? = null
    var serverCodecModeSupport = 0

    var rtspSessionUrl: String? = null

    var negotiatedWidth = 0
    var negotiatedHeight = 0
    var negotiatedHdr = false

    var negotiatedRemoteStreaming = 0
    var negotiatedPacketSize = 0

    var videoCapabilities = 0
}
