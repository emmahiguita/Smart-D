package com.limelight.smartdisplay.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.text.format.Formatter
import com.limelight.LimeLog
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SmartDisplay AI – Files Layer: transferencia de archivos por WiFi.
 *
 * El móvil levanta un servidor HTTP minimalista (sin dependencias) en la red
 * local. Desde el navegador del laptop (http://IP-del-movil:PUERTO) se puede:
 *   - Móvil → Laptop: descargar los archivos que el usuario compartió.
 *   - Laptop → Móvil: subir archivos (multipart/form-data) que se guardan en el
 *     almacenamiento del móvil.
 */
class FileTransferServer(
    context: Context,
    val port: Int = DEFAULT_PORT
) {

    interface Listener {
        fun onStateChanged(running: Boolean)
        fun onFileReceived(file: File)
    }

    /** Archivo del móvil ofrecido para descarga (elegido vía SAF). */
    class SharedItem(
        @JvmField val uri: Uri,
        @JvmField val name: String,
        @JvmField val size: Long
    )

    private val ctx: Context = context.applicationContext
    val shared: MutableList<SharedItem> = CopyOnWriteArrayList()

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    var listener: Listener? = null

    val isRunning: Boolean
        get() = running

    fun addShared(item: SharedItem) {
        shared.add(item)
    }

    /** Carpeta donde se guardan los archivos recibidos del laptop. */
    fun getReceivedDir(): File {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SmartDisplay")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun start() {
        if (running) return
        try {
            serverSocket = ServerSocket(port)
            running = true
        } catch (e: Exception) {
            LimeLog.warning("$TAG: no se pudo abrir el puerto $port: ${e.message}")
            running = false
            notifyState()
            return
        }
        acceptThread = Thread(::acceptLoop, "SD-FileServer").apply {
            isDaemon = true
            start()
        }
        LimeLog.info("$TAG: servidor iniciado en puerto $port")
        notifyState()
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
        notifyState()
    }

    private fun notifyState() {
        listener?.onStateChanged(running)
    }

    private fun acceptLoop() {
        while (running) {
            var socket: Socket? = null
            try {
                socket = serverSocket?.accept() ?: break
                handle(socket)
            } catch (e: Exception) {
                if (running) LimeLog.info("$TAG: conexión cerrada (${e.message})")
            } finally {
                socket?.let { try { it.close() } catch (ignored: Exception) {} }
            }
        }
    }

    // ── Manejo de petición HTTP ────────────────────────────────────────────────

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val out = BufferedOutputStream(socket.getOutputStream())

        val headerBuf = ByteArrayOutputStream()
        var prev = -1
        var prev2 = -1
        var prev3 = -1
        var b: Int
        while (input.read().also { b = it } != -1) {
            headerBuf.write(b)
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev == '\r'.code && b == '\n'.code) break
            prev3 = prev2
            prev2 = prev
            prev = b
            if (headerBuf.size() > 32 * 1024) break
        }
        val headerText = String(headerBuf.toByteArray(), StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty() || lines[0].isEmpty()) return

        val reqLine = lines[0].split(" ")
        if (reqLine.size < 2) return
        val method = reqLine[0]
        val path = reqLine[1]

        var contentLength = 0
        var contentType = ""
        for (i in 1 until lines.size) {
            val line = lines[i]
            val c = line.indexOf(':')
            if (c <= 0) continue
            val key = line.substring(0, c).trim().lowercase()
            val value = line.substring(c + 1).trim()
            if (key == "content-length") {
                contentLength = value.toIntOrNull() ?: 0
            } else if (key == "content-type") {
                contentType = value
            }
        }

        if (method == "GET" && (path == "/" || path.startsWith("/?"))) {
            sendHtml(out, buildIndexHtml())
        } else if (method == "GET" && path.startsWith("/dl")) {
            serveDownload(out, path)
        } else if (method == "POST" && path.startsWith("/up")) {
            receiveUpload(input, out, contentType, contentLength)
        } else {
            sendText(out, "404 Not Found", "404", "text/plain")
        }
        out.flush()
    }

    // ── Móvil → Laptop: servir un archivo compartido ───────────────────────────

    private fun serveDownload(out: OutputStream, path: String) {
        var idx = -1
        val q = path.indexOf("i=")
        if (q >= 0) {
            var num = path.substring(q + 2)
            val amp = num.indexOf('&')
            if (amp >= 0) num = num.substring(0, amp)
            idx = num.toIntOrNull() ?: -1
        }
        if (idx < 0 || idx >= shared.size) {
            sendText(out, "404 Not Found", "Archivo no disponible", "text/plain")
            return
        }
        val item = shared[idx]
        val `is` = ctx.contentResolver.openInputStream(item.uri)
        if (`is` == null) {
            sendText(out, "500 Internal Server Error", "No se pudo abrir", "text/plain")
            return
        }
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            (if (item.size > 0) "Content-Length: ${item.size}\r\n" else "") +
            "Content-Disposition: attachment; filename=\"${sanitize(item.name)}\"\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        val buf = ByteArray(64 * 1024)
        var n: Int
        `is`.use { stream ->
            while (stream.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n)
            }
        }
    }

    // ── Laptop → Móvil: recibir subida multipart ───────────────────────────────

    private fun receiveUpload(
        input: InputStream,
        out: OutputStream,
        contentType: String,
        contentLength: Int
    ) {
        var boundary: String? = null
        val bi = contentType.lowercase().indexOf("boundary=")
        if (bi >= 0) {
            boundary = contentType.substring(bi + 9).trim()
            if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                boundary = boundary.substring(1, boundary.length - 1)
            }
        }
        if (boundary == null) {
            sendText(out, "400 Bad Request", "Falta boundary", "text/plain")
            return
        }

        val dashBoundary = ("--$boundary").toByteArray(StandardCharsets.ISO_8859_1)
        val reader = MultipartReader(input, contentLength.toLong(), dashBoundary)

        var received = 0
        if (reader.skipToFirstBoundary()) {
            while (!reader.atClosingBoundary()) {
                val partHeaders = reader.readPartHeaders() ?: break
                val filename = extractFilename(partHeaders)
                if (!filename.isNullOrEmpty()) {
                    val dest = uniqueFile(getReceivedDir(), sanitize(filename))
                    val fos = BufferedOutputStream(FileOutputStream(dest))
                    val written: Long
                    try {
                        written = reader.streamContentTo(fos)
                    } finally {
                        fos.close()
                    }
                    received++
                    LimeLog.info("$TAG: recibido ${dest.name} ($written bytes)")
                    listener?.onFileReceived(dest)
                } else {
                    reader.streamContentTo(null)
                }
            }
        }

        val msg = if (received > 0) {
            "<h2>OK</h2><p>$received archivo(s) recibido(s) en el movil.</p><p><a href=\"/\">Volver</a></p>"
        } else {
            "<h2>Sin archivos</h2><p><a href=\"/\">Volver</a></p>"
        }
        sendHtml(out, htmlPage(msg))
    }

    // ── Utilidades HTTP ─────────────────────────────────────────────────────────

    private fun sendHtml(out: OutputStream, html: String) {
        val data = html.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "Content-Length: ${data.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(data)
    }

    private fun sendText(out: OutputStream, status: String, text: String, type: String) {
        val data = text.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: $type; charset=UTF-8\r\n" +
            "Content-Length: ${data.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(data)
    }

    private fun buildIndexHtml(): String {
        val sb = StringBuilder()
        sb.append("<h1>SmartDisplay — Transferencia de archivos</h1>")
        sb.append("<h2>Subir al móvil (Laptop → Móvil)</h2>")
        sb.append("<form method=\"post\" action=\"/up\" enctype=\"multipart/form-data\">")
        sb.append("<input type=\"file\" name=\"f\" multiple> ")
        sb.append("<button type=\"submit\">Subir</button></form>")
        sb.append("<h2>Descargar del móvil (Móvil → Laptop)</h2>")
        if (shared.isEmpty()) {
            sb.append("<p>No hay archivos compartidos. Añádelos desde la app en el móvil.</p>")
        } else {
            sb.append("<ul>")
            for (i in shared.indices) {
                val it = shared[i]
                val sz = if (it.size > 0) " (${Formatter.formatShortFileSize(ctx, it.size)})" else ""
                sb.append("<li><a href=\"/dl?i=").append(i).append("\">")
                    .append(escapeHtml(it.name)).append("</a>").append(sz).append("</li>")
            }
            sb.append("</ul>")
        }
        return htmlPage(sb.toString())
    }

    private fun htmlPage(body: String): String {
        return "<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>SmartDisplay Files</title>" +
            "<style>body{font-family:sans-serif;max-width:680px;margin:24px auto;padding:0 16px;" +
            "background:#101418;color:#e2e2e5}h1{font-size:20px}h2{font-size:16px;margin-top:24px}" +
            "a{color:#7db0ff}button{padding:8px 16px;border:0;border-radius:8px;background:#4f80c8;color:#fff}" +
            "li{margin:6px 0}</style></head><body>$body</body></html>"
    }

    // ── Parser multipart en streaming ───────────────────────────────────────────

    private class MultipartReader(
        private val input: InputStream,
        contentLength: Long,
        dashBoundary: ByteArray
    ) {
        private val dash: ByteArray = dashBoundary
        private val dashLps: IntArray = buildLps(dashBoundary)
        private val term: ByteArray = ByteArray(dashBoundary.size + 2).apply {
            this[0] = '\r'.code.toByte()
            this[1] = '\n'.code.toByte()
            System.arraycopy(dashBoundary, 0, this, 2, dashBoundary.size)
        }
        private val termLps: IntArray = buildLps(term)
        private var remaining: Long = if (contentLength > 0) contentLength else -1L
        private val buf = ByteArray(64 * 1024)
        private var bufPos = 0
        private var bufLen = 0

        @Throws(IOException::class)
        private fun readByte(): Int {
            if (remaining == 0L) return -1
            if (bufPos >= bufLen) {
                bufLen = input.read(buf)
                bufPos = 0
                if (bufLen <= 0) return -1
            }
            val b = buf[bufPos++].toInt() and 0xff
            if (remaining > 0) remaining--
            return b
        }

        @Throws(IOException::class)
        fun skipToFirstBoundary(): Boolean {
            return scanTo(dash, dashLps, null) >= 0
        }

        @Throws(IOException::class)
        fun atClosingBoundary(): Boolean {
            val a = readByte()
            val b = readByte()
            return a == -1 || (a == '-'.code && b == '-'.code)
        }

        @Throws(IOException::class)
        fun readPartHeaders(): String? {
            val hb = ByteArrayOutputStream(256)
            var p3 = -1
            var p2 = -1
            var p1 = -1
            var c: Int
            while (readByte().also { c = it } != -1) {
                hb.write(c)
                if (p3 == '\r'.code && p2 == '\n'.code && p1 == '\r'.code && c == '\n'.code) {
                    val d = hb.toByteArray()
                    return String(d, 0, d.size - 4, StandardCharsets.ISO_8859_1)
                }
                p3 = p2
                p2 = p1
                p1 = c
                if (hb.size() > 16 * 1024) break
            }
            return null
        }

        @Throws(IOException::class)
        fun streamContentTo(out: OutputStream?): Long {
            val n = scanTo(term, termLps, out)
            return if (n < 0) -n - 1 else n
        }

        @Throws(IOException::class)
        private fun scanTo(pattern: ByteArray, lps: IntArray, out: OutputStream?): Long {
            var emitted = 0L
            var m = 0
            var c: Int
            while (readByte().also { c = it } != -1) {
                while (m > 0 && c != (pattern[m].toInt() and 0xff)) {
                    val k = lps[m - 1]
                    if (out != null) out.write(pattern, 0, m - k)
                    emitted += (m - k)
                    m = k
                }
                if (c == (pattern[m].toInt() and 0xff)) {
                    m++
                    if (m == pattern.size) return emitted
                } else {
                    out?.write(c)
                    emitted++
                }
            }
            if (m > 0 && out != null) out.write(pattern, 0, m)
            emitted += m
            return -emitted - 1
        }

        companion object {
            private fun buildLps(p: ByteArray): IntArray {
                val lps = IntArray(p.size)
                var len = 0
                var i = 1
                while (i < p.size) {
                    if (p[i] == p[len]) {
                        lps[i++] = ++len
                    } else if (len != 0) {
                        len = lps[len - 1]
                    } else {
                        lps[i++] = 0
                    }
                }
                return lps
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val TAG = "SD_FileTransfer"

        private fun extractFilename(headers: String): String? {
            val fi = headers.lowercase().indexOf("filename=")
            if (fi < 0) return null
            val q1 = headers.indexOf('"', fi)
            if (q1 < 0) return null
            val q2 = headers.indexOf('"', q1 + 1)
            if (q2 < 0) return null
            var name = headers.substring(q1 + 1, q2)
            try {
                name = URLDecoder.decode(name, "UTF-8")
            } catch (ignored: Exception) {}
            val slash = maxOf(name.lastIndexOf('/'), name.lastIndexOf('\\'))
            if (slash >= 0) name = name.substring(slash + 1)
            return name
        }

        private fun sanitize(name: String?): String {
            if (name == null) return "archivo"
            return name.replace(Regex("[\\\\/:*?\"<>|\r\n]"), "_")
        }

        private fun uniqueFile(dir: File, name: String): File {
            val f = File(dir, name)
            if (!f.exists()) return f
            var base = name
            var ext = ""
            val dot = name.lastIndexOf('.')
            if (dot > 0) {
                base = name.substring(0, dot)
                ext = name.substring(dot)
            }
            for (i in 1 until 1000) {
                val c = File(dir, "$base($i)$ext")
                if (!c.exists()) return c
            }
            return f
        }

        private fun escapeHtml(s: String?): String {
            if (s == null) return ""
            return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
        }
    }
}
