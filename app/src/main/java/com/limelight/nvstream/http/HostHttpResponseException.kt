package com.limelight.nvstream.http

import java.io.IOException

class HostHttpResponseException(
    val errorCode: Int,
    val errorMessage: String?
) : IOException("Host PC returned error: $errorMessage (Error code: $errorCode)") {

    companion object {
        private const val serialVersionUID = 1543508830807804222L
    }

    override val message: String
        get() = "Host PC returned error: $errorMessage (Error code: $errorCode)"
}
