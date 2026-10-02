package com.limelight.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.limelight.AppView
import com.limelight.Game
import com.limelight.R
import com.limelight.ShortcutTrampoline
import com.limelight.binding.PlatformBinding
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.smartdisplay.recents.LastSessionStore
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import java.security.cert.CertificateEncodingException

object ServerHelper {
    const val CONNECTION_TEST_SERVER = "android.conntest.moonlight-stream.org"

    @Throws(IOException::class)
    @JvmStatic
    fun getCurrentAddressFromComputer(computer: ComputerDetails): ComputerDetails.AddressTuple {
        return computer.activeAddress ?: throw IOException("No active address for ${computer.name}")
    }

    @JvmStatic
    fun createPcShortcutIntent(parent: Activity, computer: ComputerDetails): Intent {
        val i = Intent(parent, ShortcutTrampoline::class.java)
        i.putExtra(AppView.NAME_EXTRA, computer.name)
        i.putExtra(AppView.UUID_EXTRA, computer.uuid)
        i.action = Intent.ACTION_DEFAULT
        return i
    }

    @JvmStatic
    fun createAppShortcutIntent(parent: Activity, computer: ComputerDetails, app: NvApp): Intent {
        val i = Intent(parent, ShortcutTrampoline::class.java)
        i.putExtra(AppView.NAME_EXTRA, computer.name)
        i.putExtra(AppView.UUID_EXTRA, computer.uuid)
        i.putExtra(Game.EXTRA_APP_NAME, app.appName)
        i.putExtra(Game.EXTRA_APP_ID, app.appId.toString())
        i.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported)
        i.action = Intent.ACTION_DEFAULT
        return i
    }

    @JvmStatic
    fun createStartIntent(
        parent: Activity, app: NvApp, computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder
    ): Intent {
        val intent = Intent(parent, Game::class.java)
        intent.putExtra(Game.EXTRA_HOST, computer.activeAddress!!.address)
        intent.putExtra(Game.EXTRA_PORT, computer.activeAddress!!.port)
        intent.putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort)
        intent.putExtra(Game.EXTRA_APP_NAME, app.appName)
        intent.putExtra(Game.EXTRA_APP_ID, app.appId)
        intent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported)
        intent.putExtra(Game.EXTRA_UNIQUEID, managerBinder.uniqueId)
        intent.putExtra(Game.EXTRA_PC_UUID, computer.uuid)
        intent.putExtra(Game.EXTRA_PC_NAME, computer.name)
        if (!computer.macAddress.isNullOrEmpty()) {
            intent.putExtra(Game.EXTRA_MAC, computer.macAddress)
        }
        try {
            if (computer.serverCert != null) {
                intent.putExtra(Game.EXTRA_SERVER_CERT, computer.serverCert!!.encoded)
            }
        } catch (e: CertificateEncodingException) {
            e.printStackTrace()
        }

        try {
            LastSessionStore(parent).record(
                computer.uuid, computer.name,
                app.appName, app.appId, app.isHdrSupported
            )
        } catch (e: Exception) {
        }

        return intent
    }

    @JvmStatic
    fun doStart(
        parent: Activity, app: NvApp, computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder
    ) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(parent, parent.resources.getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show()
            return
        }
        parent.startActivity(createStartIntent(parent, app, computer, managerBinder))
    }

    @JvmStatic
    fun doNetworkTest(parent: Activity?) {
        if (parent == null || parent.isFinishing) return

        val layout = LinearLayout(parent)
        layout.orientation = LinearLayout.HORIZONTAL
        layout.setPadding(60, 40, 60, 40)
        layout.gravity = Gravity.CENTER_VERTICAL

        val progress = CircularProgressIndicator(parent)
        progress.isIndeterminate = true
        progress.indicatorSize = 90
        progress.trackCornerRadius = 45
        layout.addView(progress)

        val textView = TextView(parent)
        textView.text = parent.resources.getString(R.string.nettest_text_waiting)
        textView.setPadding(40, 0, 0, 0)
        textView.textSize = 14f
        textView.setTextColor(parent.resources.getColor(android.R.color.white))
        layout.addView(textView)

        val progressDialog: AlertDialog = MaterialAlertDialogBuilder(parent)
            .setTitle(parent.resources.getString(R.string.nettest_title_waiting))
            .setView(layout)
            .setCancelable(false)
            .create()

        progressDialog.show()

        Thread {
            val netInfo = java.lang.StringBuilder()
            try {
                val cm = parent.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNet = cm.activeNetwork
                if (activeNet != null) {
                    val caps = cm.getNetworkCapabilities(activeNet)
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            netInfo.append("\u2022 Red: Wi-Fi\n")
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            netInfo.append("\u2022 Red: Ethernet\n")
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            netInfo.append("\u2022 Red: Datos M\u00f3viles\n")
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            netInfo.append("\u2022 Red: VPN / Tailscale\n")
                        }
                    }
                }
            } catch (ignored: Exception) {
            }

            val ret = MoonBridge.testClientConnectivity(CONNECTION_TEST_SERVER, 443, MoonBridge.ML_PORT_FLAG_ALL)

            val dialogSummary = java.lang.StringBuilder()
            if (netInfo.isNotEmpty()) {
                dialogSummary.append(netInfo).append("\n")
            }

            if (ret == 0) {
                dialogSummary.append("\u2713 ").append(parent.resources.getString(R.string.nettest_text_success))
            } else if (ret == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE) {
                dialogSummary.append("\u2139 Red local operativa.\n\n")
                    .append("La prueba con el servidor externo de diagn\u00f3stico no pudo completarse, pero la conexi\u00f3n local (LAN / Wi-Fi) est\u00e1 disponible para transmitir hacia tus equipos.")
            } else {
                dialogSummary.append("\u26a0 ").append(parent.resources.getString(R.string.nettest_text_failure))
                dialogSummary.append(MoonBridge.stringifyPortFlags(ret, "\n"))
            }

            parent.runOnUiThread {
                try {
                    if (progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                } catch (ignored: Exception) {
                }

                if (parent.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && parent.isDestroyed)) {
                    return@runOnUiThread
                }

                MaterialAlertDialogBuilder(parent)
                    .setTitle(parent.resources.getString(R.string.nettest_title_done))
                    .setMessage(dialogSummary.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.start()
    }

    @JvmStatic
    fun doQuit(
        parent: Activity,
        computer: ComputerDetails,
        app: NvApp,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        onComplete: Runnable?
    ) {
        Toast.makeText(parent, parent.resources.getString(R.string.applist_quit_app) + " " + app.appName + "...", Toast.LENGTH_SHORT).show()
        Thread {
            var message: String
            try {
                val httpConn = NvHTTP(
                    getCurrentAddressFromComputer(computer), computer.httpsPort,
                    managerBinder.uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(parent)
                )
                if (httpConn.quitApp()) {
                    message = parent.resources.getString(R.string.applist_quit_success) + " " + app.appName
                } else {
                    message = parent.resources.getString(R.string.applist_quit_fail) + " " + app.appName
                }
            } catch (e: HostHttpResponseException) {
                if (e.errorCode == 599) {
                    message = "This session wasn't started by this device, so it cannot be quit. End streaming on the original device or the PC itself. (Error code: ${e.errorCode})"
                } else {
                    message = e.message ?: "Unknown error"
                }
            } catch (e: UnknownHostException) {
                message = parent.resources.getString(R.string.error_unknown_host)
            } catch (e: FileNotFoundException) {
                message = parent.resources.getString(R.string.error_404)
            } catch (e: Exception) {
                message = e.message ?: "Unknown error"
                e.printStackTrace()
            } finally {
                onComplete?.run()
            }

            val toastMessage = message
            parent.runOnUiThread {
                Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
