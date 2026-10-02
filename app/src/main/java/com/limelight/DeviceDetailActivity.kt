package com.limelight

import androidx.appcompat.app.AppCompatActivity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.preference.PreferenceManager
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limelight.binding.PlatformBinding
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.preferences.StreamSettings
import com.limelight.smartdisplay.bus.SmartDisplayBus
import com.limelight.ui.glass.LiquidGlassCircleLayout
import com.limelight.utils.ServerHelper
import com.limelight.utils.ThemeManager
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Pantalla de detalle por PC (diseÃ±o "Emma PC"): encabezado con estado real,
 * tarjetas de acciÃ³n (Escritorio Â· Control remoto y Steam), Control de energÃ­a
 * (Encender WOL + Suspender/Reiniciar/Apagar reales vÃ­a bus) y Estado rÃ¡pido con
 * Ping, CPU y RAM reales. NavegaciÃ³n inferior propia (Inicio / Ajustes).
 */
class DeviceDetailActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var uuidString: String? = null
    private var pcName: String? = null
    private var computer: ComputerDetails? = null
    private var statsRequested = false
    private var polling = false

    private lateinit var statusText: TextView
    private var statusDot: View? = null
    private lateinit var statPing: TextView
    private lateinit var statCpu: TextView
    private lateinit var statRam: TextView

    private var inForeground = true

    private val computerListener = ComputerManagerListener { details ->
        if (details == null || uuidString == null ||
            !uuidString.equals(details.uuid, ignoreCase = true)
        ) {
            return@ComputerManagerListener
        }
        computer = details
        mainHandler.post {
            refreshStatus()
            if (details.state == ComputerDetails.State.ONLINE && !statsRequested) {
                statsRequested = true
                measurePingAsync()
                fetchStatsAsync()
            }
        }
    }

    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? ComputerManagerService.ComputerManagerBinder ?: return
            Thread {
                localBinder.waitForReady()
                managerBinder = localBinder
                computer = localBinder.getComputer(uuidString)
                mainHandler.post {
                    refreshStatus()
                    startComputerUpdates()
                }
            }.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            managerBinder = null
        }
    }

    private fun startComputerUpdates() {
        if (managerBinder == null || polling || !inForeground) {
            return
        }
        managerBinder?.startPolling(computerListener)
        polling = true
    }

    private fun stopComputerUpdates() {
        if (managerBinder != null && polling) {
            try {
                managerBinder?.stopPolling()
            } catch (ignored: Exception) {
            }
        }
        polling = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_detail)

        uuidString = intent.getStringExtra(AppView.UUID_EXTRA)
        pcName = intent.getStringExtra(AppView.NAME_EXTRA)

        findViewById<View>(R.id.detail_back).setOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressed()
        }

        findViewById<View>(R.id.detail_more)?.setOnClickListener { showMoreMenu(it) }

        findViewById<TextView>(R.id.detail_title).text = pcName ?: "SmartDisplay"
        statusText = findViewById(R.id.detail_status_text)
        statusDot = findViewById(R.id.detail_status_dot)
        statPing = findViewById(R.id.stat_ping)
        statCpu = findViewById(R.id.stat_cpu)
        statRam = findViewById(R.id.stat_ram)

        val dash = getString(R.string.detail_stat_empty)
        statPing.text = dash
        statCpu.text = dash
        statRam.text = dash

        findViewById<View>(R.id.card_desktop).setOnClickListener { launchDesktop() }
        findViewById<View>(R.id.card_steam).setOnClickListener { launchSteam() }

        findViewById<View>(R.id.power_on).setOnClickListener { doWol() }
        findViewById<View>(R.id.power_suspend).setOnClickListener { sendPowerCommand("suspend") }
        findViewById<View>(R.id.power_restart).setOnClickListener { sendPowerCommand("restart") }
        findViewById<View>(R.id.power_off).setOnClickListener { sendPowerCommand("shutdown") }

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection, Context.BIND_AUTO_CREATE
        )

        runEntranceAnimation()
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        val hero = findViewById<LiquidGlassCircleLayout>(R.id.deviceHeroOrb)
        hero?.startBreathing(3.5f, 3000L)
        statsRequested = false
        refreshStatus()
        startComputerUpdates()
    }

    override fun onPause() {
        inForeground = false
        val hero = findViewById<LiquidGlassCircleLayout>(R.id.deviceHeroOrb)
        hero?.stopBreathing()
        stopComputerUpdates()
        super.onPause()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_fade_enter, R.anim.activity_slide_out_right)
    }

    override fun onDestroy() {
        stopComputerUpdates()
        val hero = findViewById<LiquidGlassCircleLayout>(R.id.deviceHeroOrb)
        hero?.stopBreathing()
        try {
            unbindService(serviceConnection)
        } catch (ignored: Exception) {
        }
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refreshStatus() {
        val c = computer
        val online = c != null && c.state == ComputerDetails.State.ONLINE
        val paired = online && c?.pairState == PairingManager.PairState.PAIRED

        val colorRes: Int
        if (!online) {
            statusText.setText(R.string.detail_status_offline)
            colorRes = R.color.status_offline
        } else if (paired) {
            statusText.setText(R.string.detail_status_connected)
            colorRes = R.color.status_online
        } else {
            statusText.setText(R.string.detail_status_pairing)
            colorRes = R.color.status_pairing
        }
        statusDot?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, colorRes)
        )

        setActionEnabled(R.id.card_desktop, online)
        setActionEnabled(R.id.card_steam, online)
        setActionEnabled(R.id.power_suspend, online)
        setActionEnabled(R.id.power_restart, online)
        setActionEnabled(R.id.power_off, online)
    }

    private fun setActionEnabled(viewId: Int, enabled: Boolean) {
        val v = findViewById<View>(viewId) ?: return
        v.isEnabled = enabled
        v.isClickable = enabled
        v.alpha = if (enabled) 1f else 0.4f
    }

    /** Mide el ping real al PC (conexiÃ³n TCP a su direcciÃ³n activa). */
    private fun measurePingAsync() {
        val c = computer
        val addr = c?.activeAddress ?: return
        val host = addr.address
        val port = addr.port
        Thread {
            var ms: Long = -1
            try {
                Socket().use { socket ->
                    val start = System.currentTimeMillis()
                    socket.connect(InetSocketAddress(host, port), 1500)
                    ms = System.currentTimeMillis() - start
                }
            } catch (ignored: Exception) {
            }
            val result = ms
            mainHandler.post {
                if (result >= 0) {
                    statPing.text = "$result ms"
                }
            }
        }.start()
    }

    /** Pide CPU/RAM reales al companion por el bus (respuesta {"type":"stats",...}). */
    private fun fetchStatsAsync() {
        val c = computer
        val addr = c?.activeAddress ?: return
        if (c.state != ComputerDetails.State.ONLINE) {
            return
        }
        val host = addr.address
        @Suppress("DEPRECATION")
        val pin = PreferenceManager.getDefaultSharedPreferences(this).getString("companion_pin", "") ?: ""

        val ref = arrayOfNulls<SmartDisplayBus>(1)
        val bus = SmartDisplayBus(host, object : SmartDisplayBus.MessageListener {
            override fun onConnected() {
                try {
                    val req = JSONObject().apply {
                        put("type", "get_stats")
                    }
                    ref[0]?.sendMessage(req)
                } catch (ignored: Exception) {
                }
            }

            override fun onMessageReceived(json: JSONObject) {
                if ("stats" != json.optString("type")) {
                    return
                }
                val cpu = json.optInt("cpu", -1)
                val ram = json.optInt("ram", -1)
                mainHandler.post {
                    if (cpu >= 0) statCpu.text = "$cpu%"
                    if (ram >= 0) statRam.text = "$ram%"
                }
                ref[0]?.stop()
            }

            override fun onDisconnected() {}
        }, pin)
        ref[0] = bus
        bus.start()
        mainHandler.postDelayed({ bus.stop() }, 5000)

        // Si seguimos en primer plano, programa la siguiente mediciÃ³n en 3s
        if (inForeground && c.state == ComputerDetails.State.ONLINE) {
            mainHandler.postDelayed({
                if (inForeground && computer?.state == ComputerDetails.State.ONLINE) {
                    measurePingAsync()
                    fetchStatsAsync()
                }
            }, 3000)
        }
    }

    // â”€â”€ Acciones â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun launchDesktop() {
        launchApp(null) // null = escritorio remoto
    }

    private fun launchSteam() {
        launchApp("steam")
    }

    private var lastLaunchTime: Long = 0

    private fun launchApp(targetName: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchTime < 1000) {
            return
        }
        lastLaunchTime = now

        val binder = managerBinder
        val c = computer
        if (c == null || c.state != ComputerDetails.State.ONLINE || c.activeAddress == null) {
            Toast.makeText(this, R.string.detail_power_offline, Toast.LENGTH_SHORT).show()
            return
        }
        if (binder == null || c.pairState != PairingManager.PairState.PAIRED) {
            launchViaTrampoline(targetName)
            return
        }
        Toast.makeText(this, R.string.detail_connecting, Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val http = NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(c),
                    c.httpsPort,
                    binder.uniqueId,
                    c.serverCert,
                    PlatformBinding.getCryptoProvider(this@DeviceDetailActivity)
                )
                val apps = http.getAppList()
                val target = findApp(apps, targetName)
                mainHandler.post {
                    if (target == null) {
                        if (!targetName.isNullOrEmpty()) {
                            Toast.makeText(
                                this@DeviceDetailActivity,
                                getString(R.string.detail_app_not_found, targetName),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        openAppList()
                    } else {
                        ServerHelper.doStart(this@DeviceDetailActivity, target, c, binder)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { launchViaTrampoline(targetName) }
            }
        }.start()
    }

    private fun findApp(apps: List<NvApp>?, targetName: String?): NvApp? {
        if (apps == null || apps.isEmpty()) {
            return null
        }
        for (a in apps) {
            val n = a.appName?.lowercase() ?: ""
            if (!targetName.isNullOrEmpty()) {
                if (n.contains(targetName.lowercase())) {
                    return a
                }
            } else if (n.contains("desktop") || n.contains("escritorio") || n.contains("mstsc")) {
                return a
            }
        }
        return if (targetName.isNullOrEmpty()) apps[0] else null
    }

    private fun launchViaTrampoline(targetName: String?) {
        val i = Intent(this, ShortcutTrampoline::class.java).apply {
            putExtra(AppView.NAME_EXTRA, pcName)
            putExtra(AppView.UUID_EXTRA, uuidString)
            putExtra("AUTO_CONNECT", true)
            if (!targetName.isNullOrEmpty()) {
                putExtra("TARGET_APP_NAME", targetName)
            }
            action = Intent.ACTION_DEFAULT
        }
        startActivity(i)
    }

    private fun openAppList() {
        val i = Intent(this, AppView::class.java).apply {
            putExtra(AppView.NAME_EXTRA, pcName)
            putExtra(AppView.UUID_EXTRA, uuidString)
        }
        startActivity(i)
    }

    // â”€â”€ Control de energÃ­a â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun doWol() {
        val target = computer ?: return
        Toast.makeText(this, R.string.detail_wol_sent, Toast.LENGTH_SHORT).show()
        Thread {
            try {
                WakeOnLanSender.sendWolPacket(target)
            } catch (ignored: Exception) {
            }
        }.start()
    }

    private fun sendPowerCommand(action: String) {
        val c = computer
        if (c == null || c.activeAddress == null || c.state != ComputerDetails.State.ONLINE) {
            Toast.makeText(this, R.string.detail_power_offline, Toast.LENGTH_SHORT).show()
            return
        }
        val name = pcName ?: "PC"
        if ("shutdown" == action || "restart" == action) {
            val msg = if ("shutdown" == action) {
                R.string.detail_power_confirm_shutdown
            } else {
                R.string.detail_power_confirm_restart
            }
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(msg, name))
                .setPositiveButton(R.string.detail_power_confirm_ok) { _, _ -> doPowerCommand(action) }
                .setNegativeButton(R.string.detail_power_cancel, null)
                .show()
        } else {
            doPowerCommand(action)
        }
    }

    private fun doPowerCommand(action: String) {
        val c = computer ?: return
        val addr = c.activeAddress ?: return
        val host = addr.address
        @Suppress("DEPRECATION")
        val pin = PreferenceManager.getDefaultSharedPreferences(this).getString("companion_pin", "") ?: ""

        val ref = arrayOfNulls<SmartDisplayBus>(1)
        val bus = SmartDisplayBus(host, object : SmartDisplayBus.MessageListener {
            override fun onConnected() {
                try {
                    val j = JSONObject().apply {
                        put("type", "power")
                        put("action", action)
                    }
                    ref[0]?.sendMessage(j)
                } catch (ignored: Exception) {
                }
                ref[0]?.stop()
            }

            override fun onMessageReceived(json: JSONObject) {}
            override fun onDisconnected() {}
        }, pin)
        ref[0] = bus
        bus.start()
        mainHandler.postDelayed({ bus.stop() }, 4000)

        val msg = when (action) {
            "suspend" -> R.string.detail_power_suspending
            "restart" -> R.string.detail_power_restarting
            else -> R.string.detail_power_shutting
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.applist_refresh_title)
        popup.menu.add(0, 2, 1, R.string.settings)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    openAppList()
                    true
                }
                2 -> {
                    startActivity(Intent(this, StreamSettings::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun runEntranceAnimation() {
        val header = findViewById<View>(R.id.header)
        val heroOrb = findViewById<LiquidGlassCircleLayout>(R.id.deviceHeroOrb)
        val systemMetrics = findViewById<View>(R.id.systemMetrics)
        val desktopAction = findViewById<View>(R.id.card_desktop)
        val steamAction = findViewById<View>(R.id.card_steam)
        val powerOn = findViewById<View>(R.id.power_on)
        val powerSuspend = findViewById<View>(R.id.power_suspend)
        val powerRestart = findViewById<View>(R.id.power_restart)
        val powerOff = findViewById<View>(R.id.power_off)

        val decel = DecelerateInterpolator(1.8f)
        val overshoot = OvershootInterpolator(1.25f)

        header?.apply {
            alpha = 0f
            translationY = -dpToPx(35f)
            animate().alpha(1f).translationY(0f).setDuration(320L).setInterpolator(decel).start()
        }

        heroOrb?.apply {
            alpha = 0f
            scaleX = 0.78f
            scaleY = 0.78f
            translationY = dpToPx(40f)
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(420L)
                .setInterpolator(overshoot)
                .withEndAction {
                    if (inForeground) {
                        heroOrb.startBreathing(3.5f, 3000L)
                    }
                }
                .start()
        }

        systemMetrics?.apply {
            alpha = 0f
            translationY = dpToPx(30f)
            animate().alpha(1f).translationY(0f).setStartDelay(80L).setDuration(340L).setInterpolator(decel).start()
        }

        desktopAction?.apply {
            alpha = 0f
            scaleX = 0.80f
            scaleY = 0.80f
            translationY = dpToPx(25f)
            animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(140L).setDuration(360L).setInterpolator(overshoot).start()
        }

        steamAction?.apply {
            alpha = 0f
            scaleX = 0.80f
            scaleY = 0.80f
            translationY = dpToPx(25f)
            animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(190L).setDuration(360L).setInterpolator(overshoot).start()
        }

        val powerButtons = arrayOf(powerOn, powerSuspend, powerRestart, powerOff)
        var powerDelay = 240L
        for (btn in powerButtons) {
            btn?.apply {
                alpha = 0f
                scaleX = 0.65f
                scaleY = 0.65f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(powerDelay).setDuration(300L).setInterpolator(overshoot).start()
                powerDelay += 45L
            }
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}

