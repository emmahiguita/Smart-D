package com.limelight

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.limelight.utils.SpinnerDialog
import com.limelight.utils.ThemeManager

class HelpActivity : Activity() {

    private var loadingDialog: SpinnerDialog? = null
    private lateinit var webView: WebView

    private var backCallbackRegistered = false
    private var onBackInvokedCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedCallback = OnBackInvokedCallback {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
            }
        }

        webView = WebView(this)
        setContentView(webView)

        // These allow the user to zoom the page
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        // This sets the view to display the whole page by default
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true

        // This allows the links to places on the same page to work
        @Suppress("SetJavaScriptEnabled")
        webView.settings.javaScriptEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (loadingDialog == null) {
                    loadingDialog = SpinnerDialog.displayDialog(
                        this@HelpActivity,
                        resources.getString(R.string.help_loading_title),
                        resources.getString(R.string.help_loading_msg),
                        false
                    )
                }

                refreshBackDispatchState()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loadingDialog?.dismiss()
                loadingDialog = null

                refreshBackDispatchState()
            }
        }

        intent.data?.let {
            webView.loadUrl(it.toString())
        }
    }

    private fun refreshBackDispatchState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = onBackInvokedCallback ?: return
            if (webView.canGoBack() && !backCallbackRegistered) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
                )
                backCallbackRegistered = true
            } else if (!webView.canGoBack() && backCallbackRegistered) {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
                backCallbackRegistered = false
            }
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (backCallbackRegistered) {
                onBackInvokedCallback?.let {
                    onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
                }
            }
        }

        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
