package com.limelight.smartdisplay.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * FileShareCoordinator — Single Responsibility Principle (SOLID)
 *
 * Encapsula la lógica de compartir archivos locales hacia el PC host:
 * 1. Servidor HTTP local embebido [FileTransferServer].
 * 2. Selección de archivos vía selector del sistema (SAF) o explorador interno.
 * 3. Notificación de URLs de descarga al companion server a través del bus.
 */
class FileShareCoordinator(
    private val activity: Activity,
    private val sendToBus: (JSONObject) -> Unit
) {
    companion object {
        const val REQ_FAB_FILE_SHARE = 4921
    }

    private var fileServer: FileTransferServer? = null

    /** Asegura que el servidor HTTP local esté en ejecución. */
    fun ensureServerStarted(): FileTransferServer {
        val server = fileServer ?: FileTransferServer(activity, FileTransferServer.DEFAULT_PORT).also {
            fileServer = it
        }
        if (!server.isRunning) {
            server.start()
        }
        return server
    }

    /** Abre el selector del sistema para elegir archivos y enviarlos al PC. */
    fun launchSystemFilePicker() {
        try {
            ensureServerStarted()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            activity.startActivityForResult(intent, REQ_FAB_FILE_SHARE)
        } catch (_: Exception) {
            Toast.makeText(activity, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show()
        }
    }

    /** Procesa los archivos seleccionados desde el explorador interno. */
    fun sendPickedFiles(files: List<File>?) {
        if (files.isNullOrEmpty()) return
        val server = ensureServerStarted()
        var added = 0
        for (f in files) {
            if (f.isFile) {
                server.addShared(FileTransferServer.SharedItem(Uri.fromFile(f), f.name, f.length()))
                added++
            }
        }
        notifyPcOfSharedFiles()
        Toast.makeText(activity, "$added archivo(s) enviados al PC", Toast.LENGTH_LONG).show()
    }

    /** Procesa el resultado de [REQ_FAB_FILE_SHARE] desde onActivityResult. */
    fun handleActivityResult(data: Intent?) {
        if (data == null) return
        ensureServerStarted()
        var added = 0
        val clip = data.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) {
                clip.getItemAt(k)?.uri?.let { if (addSharedUri(it)) added++ }
            }
        } else {
            data.data?.let { if (addSharedUri(it)) added++ }
        }
        notifyPcOfSharedFiles()
        val ip = getWifiIp()
        val msg = if (added > 0) {
            "$added archivo(s) listos. El PC los recibirá automáticamente" +
                    (if (ip != null) " (o http://$ip:${fileServer?.port})" else "")
        } else {
            "No se añadió ningún archivo"
        }
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }

    private fun addSharedUri(uri: Uri): Boolean {
        try {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        var name = "archivo"
        var size = 0L
        try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}

        ensureServerStarted().addShared(FileTransferServer.SharedItem(uri, name, size))
        return true
    }

    /** Notifica al PC host vía bus las URLs directas de descarga. */
    fun notifyPcOfSharedFiles() {
        val server = fileServer ?: return
        val ip = getWifiIp() ?: return
        try {
            val base = "http://$ip:${server.port}"
            val arr = JSONArray()
            val shared = server.shared
            for (idx in shared.indices) {
                val it = shared[idx]
                arr.put(JSONObject().apply {
                    put("name", it.name)
                    put("size", it.size)
                    put("url", "$base/dl?i=$idx")
                })
            }
            val msg = JSONObject().apply {
                put("type", "files_offer")
                put("url", base)
                put("files", arr)
            }
            sendToBus(msg)
        } catch (_: Exception) {}
    }

    fun getWifiIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** Libera y detiene el servidor HTTP embebido. */
    fun release() {
        fileServer?.stop()
        fileServer = null
    }
}
