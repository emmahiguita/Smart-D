package com.limelight.grid.assets

import android.content.Context
import com.limelight.LimeLog
import com.limelight.binding.PlatformBinding
import com.limelight.nvstream.http.NvHTTP
import com.limelight.utils.ServerHelper
import java.io.IOException
import java.io.InputStream

class NetworkAssetLoader(private val context: Context, private val uniqueId: String) {

    fun getBitmapStream(tuple: CachedAppAssetLoader.LoaderTuple): InputStream? {
        var inStream: InputStream? = null
        try {
            val http = NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(tuple.computer),
                tuple.computer.httpsPort,
                uniqueId,
                tuple.computer.serverCert,
                PlatformBinding.getCryptoProvider(context)
            )
            inStream = http.getBoxArt(tuple.app)
        } catch (ignored: IOException) {
        }

        if (inStream != null) {
            LimeLog.info("Network asset load complete: $tuple")
        } else {
            LimeLog.info("Network asset load failed: $tuple")
        }

        return inStream
    }
}
