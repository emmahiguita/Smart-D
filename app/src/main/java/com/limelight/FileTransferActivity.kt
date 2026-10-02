package com.limelight

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limelight.smartdisplay.files.FileTransferServer
import com.limelight.ui.glass.AirDropRadarView
import com.limelight.ui.glass.IosGlassCardView
import com.limelight.utils.NotificationHelper
import com.limelight.utils.ThemeManager
import com.limelight.utils.UiHelper
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de Transferencia de Archivos WiFi (SmartDisplay Files / Continuity AirDrop).
 *
 * Arquitectura y Principios SOLID:
 *  • Inyección de Tema Universal en tiempo real vía [ThemeManager.applyAmbientBackground].
 *  • Radar de transmisión de radiofrecuencia continuo libre de fugas de memoria o hilos zombi.
 *  • Físicas hápticas táctiles y retroalimentación instantánea al portapapeles.
 *  • Renderizado dinámico de tarjetas de cristal iOS de alto contraste OLED para archivos.
 */
class FileTransferActivity : Activity(), FileTransferServer.Listener {

    private var server: FileTransferServer? = null
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var sharedList: LinearLayout
    private lateinit var receivedText: TextView
    private lateinit var receivedList: LinearLayout

    // Nuevos controles iOS
    private var radarView: AirDropRadarView? = null
    private var btnCopyUrl: MaterialButton? = null
    private var sharedBadge: TextView? = null
    private var receivedBadge: TextView? = null
    private var emptyReceivedView: View? = null

    private var currentDirectUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        UiHelper.setLocale(this)
        setContentView(R.layout.activity_file_transfer)

        // Inyección universal real de lienzo ambiental OLED según el tema
        val root = findViewById<View>(R.id.ft_root)
        ThemeManager.applyAmbientBackground(root)

        val btnClose = findViewById<View>(R.id.btnClose)
        btnClose?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            finish()
        }

        statusText = findViewById(R.id.ft_status)
        urlText = findViewById(R.id.ft_url)
        sharedList = findViewById(R.id.ft_shared_list)
        receivedText = findViewById(R.id.ft_received)
        receivedList = findViewById(R.id.ft_received_list)

        radarView = findViewById(R.id.airdrop_radar)
        btnCopyUrl = findViewById(R.id.btn_copy_url)
        sharedBadge = findViewById(R.id.ft_shared_badge)
        receivedBadge = findViewById(R.id.ft_received_badge)
        emptyReceivedView = findViewById(R.id.ft_empty_received)

        val pick = findViewById<Button>(R.id.ft_btn_share)
        pick.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            pickFiles()
        }

        val openReceived = findViewById<Button>(R.id.ft_btn_received)
        openReceived.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            refreshReceived()
        }

        btnCopyUrl?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            copyUrlToClipboard()
        }

        server = FileTransferServer(this, FileTransferServer.DEFAULT_PORT).apply {
            listener = this@FileTransferActivity
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_fade_enter, R.anim.activity_slide_out_right)
    }

    override fun onResume() {
        super.onResume()
        if (server?.isRunning == false) {
            server?.start()
        }
        updateUi()
        refreshReceived()
    }

    override fun onDestroy() {
        super.onDestroy()
        radarView?.stopEmitting()
        server?.stop()
    }

    // ── Portapapeles & Feedback Háptico ─────────────────────────────────────────

    private fun copyUrlToClipboard() {
        if (currentDirectUrl.isEmpty()) {
            Toast.makeText(this, "Dirección no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SmartDisplay URL", currentDirectUrl)
            clipboard.setPrimaryClip(clip)

            btnCopyUrl?.let { btn ->
                val originalText = btn.text.toString()
                btn.text = "¡Enlace Copiado!"
                btn.setIconResource(R.drawable.ic_verified)
                btn.postDelayed({
                    btn.text = originalText
                    btn.setIconResource(R.drawable.ic_copy)
                }, 1800L)
            }
            Toast.makeText(this, "Enlace copiado al portapapeles", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al copiar enlace", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Elegir archivos del móvil para compartir (Móvil → Laptop) ───────────────

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            Toast.makeText(this, "No hay selector de archivos disponible", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return

        val clip = data.clipData
        if (clip != null) {
            val n = clip.itemCount
            for (k in 0 until n) {
                addSharedUri(clip.getItemAt(k).uri)
            }
        } else if (data.data != null) {
            addSharedUri(data.data)
        }
        updateUi()
    }

    private fun addSharedUri(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ignored: Exception) {
        }
        var name = "archivo"
        var size: Long = 0
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (ignored: Exception) {
        }
        server?.addShared(FileTransferServer.SharedItem(uri, name, size))
    }

    // ── Actualización de Interfaz Reactiva ───────────────────────────────────────

    private fun updateUi() {
        val running = server?.isRunning == true
        val accent = ThemeManager.accentColor(this)

        radarView?.setServerActive(running)

        statusText.text = if (running) "● Servidor activo · Listo para transferir" else "○ Servidor detenido"
        statusText.setTextColor(if (running) accent else 0xFF94A3B8.toInt())

        if (running) {
            val ip = getWifiIpAddress()
            if (ip != null) {
                currentDirectUrl = "http://$ip:${server?.port}"
                urlText.text = currentDirectUrl
                btnCopyUrl?.isEnabled = true
                btnCopyUrl?.alpha = 1f
            } else {
                currentDirectUrl = ""
                urlText.text = "Conéctate a la misma red Wi-Fi que tu PC."
                btnCopyUrl?.isEnabled = false
                btnCopyUrl?.alpha = 0.5f
            }
        } else {
            currentDirectUrl = ""
            urlText.text = "El servidor se encuentra inactivo."
            btnCopyUrl?.isEnabled = false
            btnCopyUrl?.alpha = 0.5f
        }

        // Renderizado de lista de archivos compartidos (Móvil -> PC)
        sharedList.removeAllViews()
        val s = server
        val sharedCount = s?.shared?.size ?: 0
        sharedBadge?.text = "$sharedCount ${if (sharedCount == 1) "archivo" else "archivos"}"

        if (s != null && s.shared.isNotEmpty()) {
            val density = resources.displayMetrics.density
            for (idx in s.shared.indices) {
                val it = s.shared[idx]
                val card = buildFileCard(
                    title = it.name,
                    subtitle = if (it.size > 0) Formatter.formatShortFileSize(this, it.size) else "Tamaño desconocido",
                    iconRes = getIconForFilename(it.name),
                    accentColor = accent,
                    actionText = "Quitar",
                    onAction = {
                        s.shared.removeAt(idx)
                        updateUi()
                    }
                )
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (8 * density).toInt()
                }
                sharedList.addView(card, lp)
            }
        }
    }

    private fun refreshReceived() {
        val s = server ?: return
        val dir = s.getReceivedDir()
        val files = dir.listFiles()?.filter { !it.isDirectory } ?: emptyList()
        receivedList.removeAllViews()

        val count = files.size
        receivedBadge?.text = "$count ${if (count == 1) "recibido" else "recibidos"}"

        if (files.isEmpty()) {
            receivedText.text = ""
            emptyReceivedView?.visibility = View.VISIBLE
            return
        }

        emptyReceivedView?.visibility = View.GONE
        receivedText.text = "Toca un archivo para ver opciones rápidas:"
        val accent = ThemeManager.accentColor(this)
        val density = resources.displayMetrics.density
        val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

        for (f in files) {
            val sizeStr = Formatter.formatShortFileSize(this, f.length())
            val dateStr = sdf.format(Date(f.lastModified()))
            val card = buildFileCard(
                title = f.name,
                subtitle = "$sizeStr · $dateStr",
                iconRes = getIconForFilename(f.name),
                accentColor = accent,
                actionText = "Opciones",
                onAction = { showFileOptions(f) },
                onCardClick = { openReceived(f) }
            )
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            }
            receivedList.addView(card, lp)
        }
    }

    /**
     * Construye un componente de tarjeta frosted glass interactivo para archivos.
     */
    private fun buildFileCard(
        title: String,
        subtitle: String,
        iconRes: Int,
        accentColor: Int,
        actionText: String,
        onAction: () -> Unit,
        onCardClick: (() -> Unit)? = null
    ): View {
        val density = resources.displayMetrics.density

        val card = IosGlassCardView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (onCardClick != null) {
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onCardClick()
                }
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()).apply {
                marginEnd = (12 * density).toInt()
            }
            setBackgroundResource(R.drawable.bg_status_chip)
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            setImageResource(iconRes)
            setColorFilter(accentColor)
        }
        row.addView(icon)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(0xFFF8FAFC.toInt())
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        textCol.addView(tvTitle)

        val tvSub = TextView(this).apply {
            text = subtitle
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11.5f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        textCol.addView(tvSub)

        row.addView(textCol)

        val actionBtn = TextView(this).apply {
            text = actionText
            setTextColor(accentColor)
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_status_chip)
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onAction()
            }
        }
        row.addView(actionBtn)

        card.addView(row)
        return card
    }

    private fun getIconForFilename(name: String): Int {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp" -> R.drawable.ic_palette
            "mp4", "mkv", "mov", "avi", "webm" -> R.drawable.ic_play
            "mp3", "wav", "flac", "aac", "ogg" -> R.drawable.ic_mic_overlay
            "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_tools_overlay
            "txt", "pdf", "doc", "docx", "xls", "xlsx" -> R.drawable.ic_files_overlay
            else -> R.drawable.ic_lan
        }
    }

    /** Diálogo Abrir / Compartir para un archivo recibido. */
    private fun showFileOptions(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setItems(arrayOf("Abrir archivo", "Compartir", "Eliminar")) { _, which ->
                when (which) {
                    0 -> openReceived(file)
                    1 -> shareReceived(file)
                    2 -> {
                        if (file.delete()) {
                            Toast.makeText(this, "Archivo eliminado", Toast.LENGTH_SHORT).show()
                            refreshReceived()
                        }
                    }
                }
            }
            .show()
    }

    private fun providerUri(f: File): Uri {
        return FileProvider.getUriForFile(
            this, "$packageName.fileprovider", f
        )
    }

    private fun mimeOf(f: File): String {
        val name = f.name
        val dot = name.lastIndexOf('.')
        if (dot >= 0 && dot < name.length - 1) {
            val ext = name.substring(dot + 1).lowercase()
            val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (m != null) return m
        }
        return "*/*"
    }

    private fun openReceived(f: File) {
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(providerUri(f), mimeOf(f))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Abrir con"))
        } catch (e: Exception) {
            Toast.makeText(this, "No hay app para abrir este archivo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareReceived(f: File) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(f)
                putExtra(Intent.EXTRA_STREAM, providerUri(f))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Compartir"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo compartir", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStateChanged(running: Boolean) {
        runOnUiThread { updateUi() }
    }

    override fun onFileReceived(file: File) {
        runOnUiThread {
            Toast.makeText(this, "Recibido: ${file.name}", Toast.LENGTH_SHORT).show()
            NotificationHelper.notifyFileTransfer(this, file.name, false)
            refreshReceived()
        }
    }

    /** Devuelve la IPv4 de la interfaz WiFi (o cualquier IPv4 no-loopback). */
    private fun getWifiIpAddress(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                val wifiLike = name.contains("wlan") || name.contains("ap") || name.contains("eth")
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        if (wifiLike) return addr.hostAddress
                    }
                }
            }
            // Fallback: cualquier IPv4 no loopback
            val ifaces2 = NetworkInterface.getNetworkInterfaces()
            while (ifaces2.hasMoreElements()) {
                val addrs = ifaces2.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return null
    }

    companion object {
        private const val REQ_PICK = 4001
    }
}
