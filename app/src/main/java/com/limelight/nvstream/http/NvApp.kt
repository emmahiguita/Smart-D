package com.limelight.nvstream.http

import com.limelight.LimeLog

class NvApp {
    var appName: String = ""
    var appId: Int = 0
        private set
    var isInitialized: Boolean = false
        private set
    var isHdrSupported: Boolean = false

    constructor()

    constructor(appName: String) {
        this.appName = appName
    }

    constructor(appName: String, appId: Int, hdrSupported: Boolean) {
        this.appName = appName
        this.appId = appId
        this.isHdrSupported = hdrSupported
        this.isInitialized = true
    }

    fun setAppId(appIdStr: String) {
        try {
            this.appId = appIdStr.toInt()
            this.isInitialized = true
        } catch (e: NumberFormatException) {
            LimeLog.warning("Malformed app ID: $appIdStr")
        }
    }

    fun setAppId(appId: Int) {
        this.appId = appId
        this.isInitialized = true
    }

    

    override fun toString(): String {
        val str = StringBuilder()
        str.append("Name: ").append(appName).append("\n")
        str.append("HDR Supported: ").append(if (isHdrSupported) "Yes" else "Unknown").append("\n")
        str.append("ID: ").append(appId).append("\n")
        return str.toString()
    }
}
