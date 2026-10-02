package com.limelight.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.limelight.HelpActivity

object HelpLauncher {
    @JvmStatic
    fun launchUrl(context: Context, url: String) {
        // Try to launch the default browser
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
            }

            // Several Android TV devices will lie and say they do have a browser even though the OS
            // just shows an error dialog if we try to use it. We'll just always use the WebView on Android TV.
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                context.startActivity(i)
                return
            }
        } catch (e: Exception) {
            // Fall through
        }

        // This platform has no browser (possibly a leanback device)
        // We'll launch our WebView activity
        val i = Intent(context, HelpActivity::class.java).apply {
            data = Uri.parse(url)
        }
        context.startActivity(i)
    }

    @JvmStatic
    fun launchSetupGuide(context: Context) {
        launchUrl(context, "https://github.com/moonlight-stream/moonlight-docs/wiki/Setup-Guide")
    }

    @JvmStatic
    fun launchTroubleshooting(context: Context) {
        launchUrl(context, "https://github.com/moonlight-stream/moonlight-docs/wiki/Troubleshooting")
    }
}
