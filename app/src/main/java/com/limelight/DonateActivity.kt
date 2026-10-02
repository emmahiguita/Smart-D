package com.limelight

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import com.limelight.ui.glass.LiquidGlassCircleLayout
import com.limelight.utils.ThemeManager

/**
 * Pantalla de donaciones premium (Material 3): explica el apoyo, muestra el QR
 * de PayPal y ofrece Donar / Compartir / Copiar enlace, con animación de entrada,
 * feedback por Snackbar y manejo de sin-conexión. Respeta el tema claro/oscuro.
 */
class DonateActivity : Activity() {

    private var awaitingReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_donate)

        val btnClose = findViewById<View>(R.id.btnClose)
        btnClose?.setOnClickListener { finish() }

        // Versión real de la app.
        try {
            val v = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.donate_version)?.text = v
        } catch (ignored: Exception) {
        }

        findViewById<View>(R.id.donate_paypal_btn)?.setOnClickListener { donate() }
        findViewById<View>(R.id.donate_share_btn)?.setOnClickListener { share() }
        findViewById<View>(R.id.donate_copy_btn)?.setOnClickListener { copyLink() }

        // Animación de entrada: fade + scale (Material 3 Expressive).
        val content = findViewById<View>(R.id.donate_content)
        content?.apply {
            alpha = 0f
            scaleX = 0.95f
            scaleY = 0.95f
            animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(320)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_fade_enter, R.anim.activity_slide_out_right)
    }

    override fun onResume() {
        super.onResume()
        val hero = findViewById<LiquidGlassCircleLayout>(R.id.donateHeroOrb)
        hero?.startBreathing(3f, 2800L)

        // Al volver de PayPal, agradecer con un Snackbar elegante.
        if (awaitingReturn) {
            awaitingReturn = false
            snack(getString(R.string.donate_thanks))
        }
    }

    override fun onPause() {
        val hero = findViewById<LiquidGlassCircleLayout>(R.id.donateHeroOrb)
        hero?.stopBreathing()
        super.onPause()
    }

    private fun payPalUrl(): String {
        return getString(R.string.donate_paypal_url)
    }

    private fun donate() {
        if (!isOnline()) {
            snack(getString(R.string.donate_offline))
            return
        }
        Toast.makeText(this, R.string.donate_opening, Toast.LENGTH_SHORT).show()
        try {
            // ACTION_VIEW abre la app de PayPal si está instalada, o el navegador.
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(payPalUrl())))
            awaitingReturn = true
        } catch (e: Exception) {
            snack(getString(R.string.donate_no_paypal))
        }
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.donate_title) + "\n" + payPalUrl())
        }
        startActivity(Intent.createChooser(send, getString(R.string.donate_share)))
    }

    private fun copyLink() {
        val cb = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        if (cb != null) {
            cb.setPrimaryClip(ClipData.newPlainText("PayPal", payPalUrl()))
            snack(getString(R.string.donate_copied))
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        @Suppress("DEPRECATION")
        val net = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        return net != null && net.isConnected
    }

    private fun snack(msg: String) {
        findViewById<View>(R.id.donate_root)?.let {
            Snackbar.make(it, msg, Snackbar.LENGTH_SHORT).show()
        }
    }
}
