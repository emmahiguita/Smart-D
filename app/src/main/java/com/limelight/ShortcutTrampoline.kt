package com.limelight

import android.app.Activity
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.limelight.binding.PlatformBinding
import com.limelight.computers.ComputerDatabaseManager
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.smartdisplay.host.SunshinePairHelper
import com.limelight.utils.CacheHelper
import com.limelight.utils.Dialog
import com.limelight.utils.ServerHelper
import com.limelight.utils.SpinnerDialog
import com.limelight.utils.UiHelper
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader
import java.util.ArrayList
import java.util.UUID

class ShortcutTrampoline : Activity() {
    private var uuidString: String? = null
    private var app: NvApp? = null
    private val intentStack = ArrayList<Intent>()

    private var wakeHostTries = 10
    private var computer: ComputerDetails? = null
    private var blockingLoadSpinner: SpinnerDialog? = null

    @Volatile
    private var isPairing = false

    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? ComputerManagerService.ComputerManagerBinder ?: return

            // Wait in a separate thread to avoid stalling the UI
            Thread {
                // Wait for the binder to be ready
                localBinder.waitForReady()

                // Now make the binder visible
                managerBinder = localBinder

                // Get the computer object
                val currentUuid = uuidString
                computer = localBinder.getComputer(currentUuid)
                val currentComputer = computer

                if (currentComputer == null) {
                    Dialog.displayDialog(
                        this@ShortcutTrampoline,
                        resources.getString(R.string.conn_error_title),
                        resources.getString(R.string.scut_pc_not_found),
                        true
                    )

                    blockingLoadSpinner?.dismiss()
                    blockingLoadSpinner = null

                    if (managerBinder != null) {
                        unbindService(this)
                        managerBinder = null
                    }
                    return@Thread
                }

                // Force CMS to repoll this machine
                localBinder.invalidateStateForComputer(currentComputer.uuid)

                // Start polling
                localBinder.startPolling(object : ComputerManagerListener {
                    override fun notifyComputerUpdated(details: ComputerDetails) {
                        // Don't care about other computers
                        if (!details.uuid.equals(uuidString, ignoreCase = true)) {
                            return
                        }

                        // Try to wake the target PC if it's offline (up to some retry limit)
                        if (details.state == ComputerDetails.State.OFFLINE && details.macAddress != null && --wakeHostTries >= 0) {
                            try {
                                // Make a best effort attempt to wake the target PC
                                WakeOnLanSender.sendWolPacket(currentComputer)

                                // If we sent at least one WoL packet, reset the computer state
                                // to force ComputerManager to poll it again.
                                localBinder.invalidateStateForComputer(currentComputer.uuid)
                                return
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                        }

                        if (details.state != ComputerDetails.State.UNKNOWN) {
                            runOnUiThread {
                                // Stop showing the spinner
                                blockingLoadSpinner?.dismiss()
                                blockingLoadSpinner = null

                                // If the managerBinder was destroyed before this callback,
                                // just finish the activity.
                                val binderLocal = managerBinder
                                if (binderLocal == null) {
                                    finish()
                                    return@runOnUiThread
                                }

                                if (details.state == ComputerDetails.State.ONLINE && details.pairState == PairingManager.PairState.PAIRED) {
                                    val currentApp = app
                                    if (currentApp != null) {
                                        if (details.runningGameId == 0 || details.runningGameId == currentApp.appId) {
                                            intentStack.add(ServerHelper.createStartIntent(this@ShortcutTrampoline, currentApp, details, binderLocal))

                                            finish()
                                            startActivities(intentStack.toTypedArray())
                                        } else {
                                            val startIntent = ServerHelper.createStartIntent(this@ShortcutTrampoline, currentApp, details, binderLocal)

                                            UiHelper.displayQuitConfirmationDialog(
                                                this@ShortcutTrampoline,
                                                {
                                                    intentStack.add(startIntent)
                                                    finish()
                                                    startActivities(intentStack.toTypedArray())
                                                },
                                                {
                                                    finish()
                                                }
                                            )
                                        }
                                    } else if (intent.getBooleanExtra("AUTO_CONNECT", false)) {
                                        Thread {
                                            try {
                                                val b = managerBinder
                                                if (b == null) {
                                                    isTrampolining = false
                                                    runOnUiThread {
                                                        Dialog.displayDialog(
                                                            this@ShortcutTrampoline,
                                                            resources.getString(R.string.conn_error_title),
                                                            "El servicio no está listo. Vuelve a intentarlo.",
                                                            true
                                                        )
                                                    }
                                                    return@Thread
                                                }

                                                val connectAddress = details.activeAddress
                                                    ?: details.manualAddress
                                                    ?: details.localAddress
                                                if (connectAddress == null) return@Thread

                                                val http = NvHTTP(
                                                    connectAddress,
                                                    details.httpsPort,
                                                    b.uniqueId,
                                                    details.serverCert,
                                                    PlatformBinding.getCryptoProvider(this@ShortcutTrampoline)
                                                )

                                                Log.i("ShortcutTrampoline", "Fetching app list from ${connectAddress.address}")
                                                val appList = http.getAppList()
                                                if (!appList.isNullOrEmpty()) {
                                                    val requestedName = intent.getStringExtra("TARGET_APP_NAME")
                                                    var targetApp: NvApp? = null
                                                    for (a in appList) {
                                                        val n = a.appName?.lowercase() ?: ""
                                                        if (!requestedName.isNullOrEmpty()) {
                                                            if (n.contains(requestedName.lowercase())) {
                                                                targetApp = a
                                                                break
                                                            }
                                                        } else if (n.contains("desktop") ||
                                                            n.contains("escritorio") ||
                                                            n.contains("mstsc")
                                                        ) {
                                                            targetApp = a
                                                            break
                                                        }
                                                    }
                                                    if (targetApp == null && requestedName.isNullOrEmpty()) {
                                                        targetApp = appList[0]
                                                    }
                                                    if (targetApp == null) {
                                                        val missing = requestedName
                                                        isTrampolining = false
                                                        runOnUiThread {
                                                            Dialog.displayDialog(
                                                                this@ShortcutTrampoline,
                                                                "Error",
                                                                "No se encontró la aplicación \"$missing\" en el PC.",
                                                                true
                                                            )
                                                        }
                                                        return@Thread
                                                    }

                                                    Log.i("ShortcutTrampoline", "Auto-starting app: ${targetApp.appName}")
                                                    val startIntent = ServerHelper.createStartIntent(this@ShortcutTrampoline, targetApp, details, b).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                                    }

                                                    isTrampolining = false
                                                    runOnUiThread {
                                                        finish()
                                                        startActivity(startIntent)
                                                    }
                                                } else {
                                                    Log.e("ShortcutTrampoline", "App list is empty or null, cannot auto-connect.")
                                                    isTrampolining = false
                                                    runOnUiThread {
                                                        Dialog.displayDialog(
                                                            this@ShortcutTrampoline,
                                                            "Error",
                                                            "No se encontraron aplicaciones (Desktop) en Sunshine.",
                                                            true
                                                        )
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                Log.e("ShortcutTrampoline", "Error fetching app list: ${e.message}")
                                                isTrampolining = false
                                                runOnUiThread {
                                                    Dialog.displayDialog(
                                                        this@ShortcutTrampoline,
                                                        "Error de Conexión",
                                                        "No se pudo iniciar la transmisión: ${e.message}",
                                                        true
                                                    )
                                                }
                                            }
                                        }.start()
                                    } else {
                                        finish()

                                        val pcIntent = Intent(this@ShortcutTrampoline, PcView::class.java).apply {
                                            action = Intent.ACTION_MAIN
                                            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        intentStack.add(pcIntent)

                                        val appIntent = Intent(intent).apply {
                                            setClass(this@ShortcutTrampoline, AppView::class.java)
                                        }
                                        intentStack.add(appIntent)

                                        if (details.runningGameId != 0) {
                                            intentStack.add(
                                                ServerHelper.createStartIntent(
                                                    this@ShortcutTrampoline,
                                                    NvApp("", details.runningGameId, false),
                                                    details,
                                                    binderLocal
                                                )
                                            )
                                        }

                                        startActivities(intentStack.toTypedArray())
                                    }
                                } else if (details.state == ComputerDetails.State.OFFLINE) {
                                    Dialog.displayDialog(
                                        this@ShortcutTrampoline,
                                        resources.getString(R.string.conn_error_title),
                                        resources.getString(R.string.error_pc_offline),
                                        true
                                    )
                                } else if (details.pairState != PairingManager.PairState.PAIRED) {
                                    if (intent.getBooleanExtra("AUTO_CONNECT", false)) {
                                        if (isPairing) {
                                            Log.i("ShortcutTrampoline", "Already pairing, ignoring duplicate callback")
                                            return@runOnUiThread
                                        }
                                        isPairing = true

                                        val pinStr = PairingManager.generatePinString()
                                        Log.i("ShortcutTrampoline", "Auto-pairing started")

                                        val pairAddress = details.activeAddress
                                            ?: details.manualAddress
                                            ?: details.localAddress
                                        if (pairAddress == null) return@runOnUiThread

                                        Log.i("ShortcutTrampoline", "Auto-pairing using address: $pairAddress (httpsPort=${details.httpsPort})")

                                        binderLocal.stopPolling()
                                        Log.i("ShortcutTrampoline", "Stopped polling to avoid interference with pairing")

                                        Dialog.displayDialog(
                                            this@ShortcutTrampoline,
                                            "Emparejando...",
                                            "Ingresa este PIN en Sunshine (tu PC):\n\n$pinStr\n\nConectando a: ${pairAddress.address}",
                                            false
                                        )

                                        Thread {
                                            var lastError = ""
                                            for (attempt in 1..3) {
                                                try {
                                                    Log.i("ShortcutTrampoline", "Pairing attempt $attempt/3 to $pairAddress")
                                                    val httpConn = NvHTTP(
                                                        pairAddress,
                                                        details.httpsPort,
                                                        binderLocal.uniqueId,
                                                        details.serverCert,
                                                        PlatformBinding.getCryptoProvider(this@ShortcutTrampoline)
                                                    )

                                                    val serverInfo = httpConn.getServerInfo(true)
                                                    Log.i("ShortcutTrampoline", "Got serverInfo OK, proceeding to pair")

                                                    SunshinePairHelper.submitPinAsync(
                                                        this@ShortcutTrampoline,
                                                        pairAddress.address,
                                                        pinStr,
                                                        "SmartDisplay"
                                                    )

                                                    val pm = httpConn.pairingManager
                                                    val pairState = pm.pair(serverInfo, pinStr)

                                                    Log.i("ShortcutTrampoline", "Pair result: $pairState")

                                                    if (pairState == PairingManager.PairState.PAIRED) {
                                                        val paired = binderLocal.getComputer(details.uuid)
                                                        if (paired != null) {
                                                            paired.serverCert = pm.getPairedCert()
                                                        }
                                                        val db = ComputerDatabaseManager(this@ShortcutTrampoline)
                                                        val dbComp = db.getComputerByUUID(details.uuid)
                                                        if (dbComp != null) {
                                                            dbComp.serverCert = pm.getPairedCert()
                                                            db.updateComputer(dbComp)
                                                        }
                                                        db.close()
                                                        binderLocal.invalidateStateForComputer(details.uuid)

                                                        isPairing = false
                                                        runOnUiThread {
                                                            Dialog.closeDialogs()
                                                            val retryIntent = Intent(this@ShortcutTrampoline.intent)
                                                            finish()
                                                            startActivity(retryIntent)
                                                        }
                                                        return@Thread
                                                    } else if (pairState == PairingManager.PairState.ALREADY_IN_PROGRESS) {
                                                        lastError = "Otro dispositivo ya está emparejando"
                                                        Thread.sleep(5000)
                                                    } else if (pairState == PairingManager.PairState.PIN_WRONG) {
                                                        lastError = "PIN rechazado - ingresa $pinStr en Sunshine"
                                                        Thread.sleep(5000)
                                                    } else {
                                                        lastError = "Estado: $pairState"
                                                        break
                                                    }
                                                } catch (ie: InterruptedException) {
                                                    Thread.currentThread().interrupt()
                                                    isPairing = false
                                                    return@Thread
                                                } catch (e: Exception) {
                                                    lastError = e.message ?: e.javaClass.simpleName
                                                    Log.e("ShortcutTrampoline", "Pairing exception attempt $attempt: $lastError")
                                                    e.printStackTrace()
                                                    if (attempt < 3) {
                                                        try {
                                                            Thread.sleep(2000)
                                                        } catch (ie2: InterruptedException) {
                                                            break
                                                        }
                                                    }
                                                }
                                            }

                                            isPairing = false
                                            val finalError = lastError
                                            runOnUiThread {
                                                Dialog.closeDialogs()
                                                Dialog.displayDialog(
                                                    this@ShortcutTrampoline,
                                                    "Error de Emparejamiento",
                                                    "Dirección: ${pairAddress.address}:${pairAddress.port}\n" +
                                                            "Error: $finalError\n\n" +
                                                            "Verifica:\n1. Sunshine está corriendo\n2. Ingresaste el PIN $pinStr en Sunshine\n3. El firewall permite el puerto ${pairAddress.port}",
                                                    true
                                                )
                                            }
                                        }.start()

                                        return@runOnUiThread
                                    } else {
                                        Dialog.displayDialog(
                                            this@ShortcutTrampoline,
                                            resources.getString(R.string.conn_error_title),
                                            resources.getString(R.string.scut_not_paired),
                                            true
                                        )
                                    }
                                }

                                if (managerBinder != null) {
                                    binderLocal.stopPolling()
                                    unbindService(this@ShortcutTrampoline.serviceConnection)
                                    managerBinder = null
                                }
                            }
                        }
                    }
                })
            }.start()
        }

        override fun onServiceDisconnected(className: ComponentName?) {
            managerBinder = null
        }
    }

    protected fun validateInput(uuidString: String?, appIdString: String?, nameString: String?): Boolean {
        // Validate PC UUID/Name
        if (uuidString == null && nameString == null) {
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.scut_invalid_uuid),
                true
            )
            return false
        }

        if (!uuidString.isNullOrEmpty()) {
            try {
                UUID.fromString(uuidString)
            } catch (ex: IllegalArgumentException) {
                Dialog.displayDialog(
                    this,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_invalid_uuid),
                    true
                )
                return false
            }
        } else {
            // UUID is null, so fallback to Name
            if (nameString.isNullOrEmpty()) {
                Dialog.displayDialog(
                    this,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_invalid_uuid),
                    true
                )
                return false
            }
        }

        // Validate App ID (if provided)
        if (!appIdString.isNullOrEmpty()) {
            try {
                appIdString.toInt()
            } catch (ex: NumberFormatException) {
                Dialog.displayDialog(
                    this,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_invalid_app_id),
                    true
                )
                return false
            }
        }

        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTrampolining = true

        UiHelper.notifyNewRootView(this)
        val dbManager = ComputerDatabaseManager(this)

        // PC arguments, both are optional, but at least one must be provided
        uuidString = intent.getStringExtra(AppView.UUID_EXTRA)
        val nameString = intent.getStringExtra(AppView.NAME_EXTRA)

        // App arguments, both are optional, but one must be provided in order to start an app
        val appIdString = intent.getStringExtra(Game.EXTRA_APP_ID)
        val appNameString = intent.getStringExtra(Game.EXTRA_APP_NAME)

        if (!validateInput(uuidString, appIdString, nameString)) {
            return
        }

        if (uuidString.isNullOrEmpty()) {
            val dbComp = dbManager.getComputerByName(nameString)
            if (dbComp == null) {
                Dialog.displayDialog(
                    this,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_pc_not_found),
                    true
                )
                return
            }

            uuidString = dbComp.uuid
            setIntent(Intent(intent).putExtra(AppView.UUID_EXTRA, uuidString))
        }

        if (!appIdString.isNullOrEmpty()) {
            app = NvApp(
                intent.getStringExtra(Game.EXTRA_APP_NAME) ?: "",
                appIdString.toInt(),
                intent.getBooleanExtra(Game.EXTRA_APP_HDR, false)
            )
        } else if (!appNameString.isNullOrEmpty()) {
            try {
                var appId = -1
                val rawAppList = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(cacheDir, "applist", uuidString)
                )

                if (rawAppList.isEmpty()) {
                    Dialog.displayDialog(
                        this,
                        resources.getString(R.string.conn_error_title),
                        resources.getString(R.string.scut_invalid_app_id),
                        true
                    )
                    return
                }
                val applist = NvHTTP.getAppListByReader(StringReader(rawAppList))

                for (a in applist) {
                    if (a.appName == appNameString) {
                        appId = a.appId
                        break
                    }
                }
                if (appId < 0) {
                    Dialog.displayDialog(
                        this,
                        resources.getString(R.string.conn_error_title),
                        resources.getString(R.string.scut_invalid_app_id),
                        true
                    )
                    return
                }
                setIntent(Intent(intent).putExtra(Game.EXTRA_APP_ID, appId))
                app = NvApp(
                    appNameString,
                    appId,
                    intent.getBooleanExtra(Game.EXTRA_APP_HDR, false)
                )
            } catch (e: Exception) {
                when (e) {
                    is IOException, is XmlPullParserException -> {
                        Dialog.displayDialog(
                            this,
                            resources.getString(R.string.conn_error_title),
                            resources.getString(R.string.scut_invalid_app_id),
                            true
                        )
                        return
                    }
                    else -> throw e
                }
            }
        }

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE
        )

        blockingLoadSpinner = SpinnerDialog.displayDialog(
            this,
            resources.getString(R.string.conn_establishing_title),
            resources.getString(R.string.applist_connect_msg),
            true
        )
    }

    override fun onStop() {
        super.onStop()

        blockingLoadSpinner?.dismiss()
        blockingLoadSpinner = null

        Dialog.closeDialogs()

        val binder = managerBinder
        if (binder != null) {
            binder.stopPolling()
            unbindService(serviceConnection)
            managerBinder = null
        }

        isTrampolining = false

        finish()
    }

    companion object {
        @JvmField
        var isTrampolining = false
    }
}
