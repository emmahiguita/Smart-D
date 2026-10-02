package com.limelight.preferences

import android.app.Activity
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import com.limelight.R
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.ui.glass.LiquidGlassCircleLayout
import com.limelight.utils.Dialog
import com.limelight.utils.ServerHelper
import com.limelight.utils.SpinnerDialog
import com.limelight.utils.ThemeManager
import com.limelight.utils.UiHelper
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URISyntaxException
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue

class AddComputerManually : Activity() {
    private var hostText: TextView? = null
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private val computersToAdd = LinkedBlockingQueue<String>()
    private var addThread: Thread? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, binder: IBinder?) {
            managerBinder = binder as? ComputerManagerService.ComputerManagerBinder
            startAddThread()
        }

        override fun onServiceDisconnected(className: ComponentName?) {
            joinAddThread()
            managerBinder = null
        }
    }

    private fun isWrongSubnetSiteLocalAddress(address: String): Boolean {
        return try {
            val targetAddress = InetAddress.getByName(address)
            if (targetAddress !is Inet4Address || !targetAddress.isSiteLocalAddress) {
                return false
            }

            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (addr in iface.interfaceAddresses) {
                    if (addr.address !is Inet4Address || !addr.address.isSiteLocalAddress) {
                        continue
                    }

                    val targetAddrBytes = targetAddress.address
                    val ifaceAddrBytes = addr.address.address

                    var addressMatches = true
                    for (i in 0 until addr.networkPrefixLength) {
                        if ((ifaceAddrBytes[i / 8].toInt() and (1 shl (i % 8))) != (targetAddrBytes[i / 8].toInt() and (1 shl (i % 8)))) {
                            addressMatches = false
                            break
                        }
                    }

                    if (addressMatches) {
                        return false
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun parseRawUserInputToUri(rawUserInput: String?): URI? {
        if (rawUserInput == null) return null
        var clean = rawUserInput.trim()
        if (clean.startsWith("http://")) clean = clean.substring(7)
        else if (clean.startsWith("https://")) clean = clean.substring(8)
        else if (clean.startsWith("moonlight://")) clean = clean.substring(12)
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length - 1)
        }
        if (clean.isEmpty()) return null

        try {
            val uri = URI("moonlight://$clean")
            if (!uri.host.isNullOrEmpty()) {
                return uri
            }
        } catch (ignored: URISyntaxException) {}

        try {
            val uri = URI("moonlight://[$clean]")
            if (!uri.host.isNullOrEmpty()) {
                return uri
            }
        } catch (ignored: URISyntaxException) {}

        return null
    }

    @Throws(InterruptedException::class)
    private fun doAddPc(rawUserInput: String) {
        var wrongSiteLocal = false
        var invalidInput = false
        var success = false
        val portTestResult: Int

        val dialog = SpinnerDialog.displayDialog(
            this,
            resources.getString(R.string.title_add_pc),
            resources.getString(R.string.msg_add_pc),
            false
        )

        try {
            val details = ComputerDetails()
            val uri = parseRawUserInputToUri(rawUserInput)
            if (uri != null && !uri.host.isNullOrEmpty()) {
                val host = uri.host
                var port = uri.port
                if (port == -1) {
                    port = NvHTTP.DEFAULT_HTTP_PORT
                }

                details.manualAddress = ComputerDetails.AddressTuple(host, port)
                val binder = managerBinder
                success = binder?.addComputerBlocking(details) ?: false
                if (!success) {
                    wrongSiteLocal = isWrongSubnetSiteLocalAddress(host)
                }
            } else {
                success = false
                invalidInput = true
            }
        } catch (e: InterruptedException) {
            dialog.dismiss()
            throw e
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            success = false
            invalidInput = true
        }

        if (!success && !wrongSiteLocal && !invalidInput) {
            portTestResult = MoonBridge.testClientConnectivity(
                ServerHelper.CONNECTION_TEST_SERVER, 443,
                MoonBridge.ML_PORT_FLAG_TCP_47984 or MoonBridge.ML_PORT_FLAG_TCP_47989
            )
        } else {
            portTestResult = MoonBridge.ML_TEST_RESULT_INCONCLUSIVE
        }

        dialog.dismiss()

        if (invalidInput) {
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.addpc_unknown_host),
                false
            )
        } else if (wrongSiteLocal) {
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.addpc_wrong_sitelocal),
                false
            )
        } else if (!success) {
            val dialogText = if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                resources.getString(R.string.nettest_text_blocked)
            } else {
                resources.getString(R.string.addpc_fail)
            }
            Dialog.displayDialog(this, resources.getString(R.string.conn_error_title), dialogText, false)
        } else {
            runOnUiThread {
                Toast.makeText(this@AddComputerManually, resources.getString(R.string.addpc_success), Toast.LENGTH_LONG).show()
                if (!isFinishing) {
                    finish()
                }
            }
        }
    }

    private fun startAddThread() {
        addThread = object : Thread("UI - AddComputerManually") {
            override fun run() {
                while (!isInterrupted) {
                    try {
                        val computer = computersToAdd.take()
                        doAddPc(computer)
                    } catch (e: InterruptedException) {
                        return
                    }
                }
            }
        }.also { it.start() }
    }

    private fun joinAddThread() {
        addThread?.let { t ->
            t.interrupt()
            try {
                t.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
                Thread.currentThread().interrupt()
            }
            addThread = null
        }
    }

    override fun onStop() {
        super.onStop()
        Dialog.closeDialogs()
        SpinnerDialog.closeDialogs(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (managerBinder != null) {
            joinAddThread()
            unbindService(serviceConnection)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        UiHelper.setLocale(this)
        setContentView(R.layout.activity_add_computer_manually)
        UiHelper.notifyNewRootView(this)

        val hostTv: TextView = findViewById(R.id.hostTextView)
        this.hostText = hostTv
        hostTv.imeOptions = EditorInfo.IME_ACTION_DONE
        hostTv.setOnEditorActionListener { _, actionId, keyEvent ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                handleDoneEvent()
            } else if (actionId == EditorInfo.IME_ACTION_PREVIOUS) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(hostTv.windowToken, 0)
                false
            } else {
                false
            }
        }

        findViewById<View>(R.id.btnConnect)?.setOnClickListener {
            handleDoneEvent()
        }

        findViewById<View>(R.id.btnClose)?.setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            finish()
        }

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE
        )

        runEntranceAnimation()
    }

    override fun onResume() {
        super.onResume()
        findViewById<LiquidGlassCircleLayout>(R.id.heroAddPcOrb)?.startBreathing(3.5f, 3000L)
    }

    override fun onPause() {
        findViewById<LiquidGlassCircleLayout>(R.id.heroAddPcOrb)?.stopBreathing()
        super.onPause()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_fade_enter, R.anim.activity_slide_out_right)
    }

    private fun handleDoneEvent(): Boolean {
        val hostAddress = hostText?.text?.toString()?.trim() ?: ""

        if (hostAddress.isEmpty()) {
            Toast.makeText(this, resources.getString(R.string.addpc_enter_ip), Toast.LENGTH_LONG).show()
            return true
        }

        computersToAdd.add(hostAddress)
        return false
    }

    private fun runEntranceAnimation() {
        val header = findViewById<View>(R.id.header)
        val heroOrb = findViewById<LiquidGlassCircleLayout>(R.id.heroAddPcOrb)
        val txtTitle = findViewById<View>(R.id.txtAddPcTitle)
        val txtDesc = findViewById<View>(R.id.txtAddPcDesc)
        val inputCard = findViewById<View>(R.id.cardInputContainer)
        val btnConnect = findViewById<View>(R.id.btnConnect)
        val btnCancel = findViewById<View>(R.id.btnCancel)

        val decel = DecelerateInterpolator(1.8f)
        val overshoot = OvershootInterpolator(1.25f)
        val density = resources.displayMetrics.density

        header?.apply {
            alpha = 0f
            translationY = -30f * density
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300L)
                .setInterpolator(decel)
                .start()
        }

        heroOrb?.apply {
            alpha = 0f
            scaleX = 0.78f
            scaleY = 0.78f
            translationY = 35f * density
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(380L)
                .setInterpolator(overshoot)
                .withEndAction { heroOrb.startBreathing(3.5f, 3000L) }
                .start()
        }

        txtTitle?.apply {
            alpha = 0f
            translationY = 20f * density
            animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(80L)
                .setDuration(300L)
                .setInterpolator(decel)
                .start()
        }

        txtDesc?.apply {
            alpha = 0f
            translationY = 20f * density
            animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(110L)
                .setDuration(300L)
                .setInterpolator(decel)
                .start()
        }

        inputCard?.apply {
            alpha = 0f
            translationY = 25f * density
            animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(150L)
                .setDuration(320L)
                .setInterpolator(decel)
                .start()
        }

        btnConnect?.apply {
            alpha = 0f
            scaleX = 0.75f
            scaleY = 0.75f
            translationY = 25f * density
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setStartDelay(200L)
                .setDuration(360L)
                .setInterpolator(overshoot)
                .start()
        }

        btnCancel?.apply {
            alpha = 0f
            animate()
                .alpha(1f)
                .setStartDelay(250L)
                .setDuration(280L)
                .start()
        }
    }
}
