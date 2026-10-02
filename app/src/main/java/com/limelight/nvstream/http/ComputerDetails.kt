package com.limelight.nvstream.http

import java.security.cert.X509Certificate
import java.util.Objects

class ComputerDetails {
    enum class State {
        ONLINE, OFFLINE, UNKNOWN
    }

    class AddressTuple(address: String?, port: Int) {
        @JvmField
        var address: String
        @JvmField
        var port: Int

        init {
            requireNotNull(address) { "Address cannot be null" }
            require(port > 0) { "Invalid port" }

            var addr = address
            // If this was an escaped IPv6 address, remove the brackets
            if (addr.startsWith("[") && addr.endsWith("]")) {
                addr = addr.substring(1, addr.length - 1)
            }

            this.address = addr
            this.port = port
        }

        override fun hashCode(): Int {
            return Objects.hash(address, port)
        }

        override fun equals(other: Any?): Boolean {
            if (other !is AddressTuple) {
                return false
            }
            return address == other.address && port == other.port
        }

        override fun toString(): String {
            return if (address.contains(":")) {
                // IPv6
                "[$address]:$port"
            } else {
                // IPv4 and hostnames
                "$address:$port"
            }
        }
    }

    // Persistent attributes
    @JvmField
    var uuid: String = ""
    @JvmField
    var name: String = ""
    @JvmField
    var localAddress: AddressTuple? = null
    @JvmField
    var remoteAddress: AddressTuple? = null
    @JvmField
    var manualAddress: AddressTuple? = null
    @JvmField
    var ipv6Address: AddressTuple? = null
    @JvmField
    var macAddress: String = ""
    @JvmField
    var serverCert: X509Certificate? = null

    // Transient attributes
    @JvmField
    var state: State = State.UNKNOWN
    @JvmField
    var activeAddress: AddressTuple? = null
    @JvmField
    var httpsPort: Int = 0
    @JvmField
    var externalPort: Int = 0
    @JvmField
    var pairState: PairingManager.PairState? = null
    @JvmField
    var runningGameId: Int = 0
    @JvmField
    var rawAppList: String? = null
    @JvmField
    var nvidiaServer: Boolean = false

    constructor() {
        state = State.UNKNOWN
    }

    constructor(details: ComputerDetails) {
        update(details)
    }

    fun guessExternalPort(): Int {
        return if (externalPort != 0) {
            externalPort
        } else if (remoteAddress != null) {
            remoteAddress!!.port
        } else if (activeAddress != null) {
            activeAddress!!.port
        } else if (ipv6Address != null) {
            ipv6Address!!.port
        } else if (localAddress != null) {
            localAddress!!.port
        } else {
            NvHTTP.DEFAULT_HTTP_PORT
        }
    }

    fun update(details: ComputerDetails) {
        this.state = details.state
        this.name = details.name
        this.uuid = details.uuid
        if (details.activeAddress != null) {
            this.activeAddress = details.activeAddress
        }
        // We can get IPv4 loopback addresses with GS IPv6 Forwarder
        if (details.localAddress != null && !details.localAddress!!.address.startsWith("127.")) {
            this.localAddress = details.localAddress
        }
        if (details.remoteAddress != null) {
            this.remoteAddress = details.remoteAddress
        } else if (this.remoteAddress != null && details.externalPort != 0) {
            this.remoteAddress!!.port = details.externalPort
        }
        if (details.manualAddress != null) {
            this.manualAddress = details.manualAddress
        }
        if (details.ipv6Address != null) {
            this.ipv6Address = details.ipv6Address
        }
        if (details.macAddress != null && details.macAddress != "00:00:00:00:00:00") {
            this.macAddress = details.macAddress
        }
        if (details.serverCert != null) {
            this.serverCert = details.serverCert
        }
        this.externalPort = details.externalPort
        this.httpsPort = details.httpsPort
        this.pairState = details.pairState
        this.runningGameId = details.runningGameId
        this.nvidiaServer = details.nvidiaServer
        this.rawAppList = details.rawAppList
    }

    override fun toString(): String {
        val str = StringBuilder()
        str.append("Name: ").append(name).append("\n")
        str.append("State: ").append(state).append("\n")
        str.append("Active Address: ").append(activeAddress).append("\n")
        str.append("UUID: ").append(uuid).append("\n")
        str.append("Local Address: ").append(localAddress).append("\n")
        str.append("Remote Address: ").append(remoteAddress).append("\n")
        str.append("IPv6 Address: ").append(ipv6Address).append("\n")
        str.append("Manual Address: ").append(manualAddress).append("\n")
        str.append("MAC Address: ").append(macAddress).append("\n")
        str.append("Pair State: ").append(pairState).append("\n")
        str.append("Running Game ID: ").append(runningGameId).append("\n")
        str.append("HTTPS Port: ").append(httpsPort).append("\n")
        return str.toString()
    }
}
