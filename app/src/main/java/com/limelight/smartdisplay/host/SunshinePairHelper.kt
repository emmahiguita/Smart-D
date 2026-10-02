package com.limelight.smartdisplay.host

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.limelight.LimeLog
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * SmartDisplay AI – Host Layer: emparejamiento automático con Sunshine.
 *
 * En lugar de obligar al usuario a abrir la web de Sunshine y teclear el PIN,
 * la app lo envía sola a la API de Sunshine:
 *   POST https://<host>:47990/api/pin   (Basic Auth: admin / clave)
 *   body {"pin":"9999","name":"SmartDisplay"}
 *
 * Se ejecuta en paralelo al handshake de Moonlight: la petición getservercert
 * queda esperando el PIN, y este POST lo entrega. Reintenta unas veces para
 * ganar la carrera con el registro de la sesión de emparejamiento.
 *
 * NO toca el core de streaming: solo habla con el endpoint de configuración web.
 */
object SunshinePairHelper {

    private const val TAG = "SD_SunshinePair"
    const val WEB_PORT = 47990

    private const val PREFS = "sd_sunshine"
    private const val KEY_USER = "web_user"
    private const val KEY_PASS = "web_pass"
    private const val DEFAULT_USER = "admin"
    private const val DEFAULT_PASS = "admin1234"

    private const val MAX_ATTEMPTS = 6
    private const val FIRST_DELAY_MS = 700L
    private const val RETRY_DELAY_MS = 800L

    /** Lee las credenciales web de Sunshine (con valores por defecto). */
    @JvmStatic
    fun getUser(ctx: Context): String {
        return prefs(ctx).getString(KEY_USER, DEFAULT_USER) ?: DEFAULT_USER
    }

    @JvmStatic
    fun getPass(ctx: Context): String {
        return prefs(ctx).getString(KEY_PASS, DEFAULT_PASS) ?: DEFAULT_PASS
    }

    @JvmStatic
    fun setCredentials(ctx: Context, user: String, pass: String) {
        prefs(ctx).edit().putString(KEY_USER, user).putString(KEY_PASS, pass).apply()
    }

    private fun prefs(ctx: Context): SharedPreferences {
        return ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Envía el PIN a Sunshine de forma asíncrona (en su propio hilo), con
     * reintentos. Llamar JUSTO al iniciar el emparejamiento.
     */
    @JvmStatic
    fun submitPinAsync(
        ctx: Context,
        host: String,
        pin: String,
        deviceName: String?
    ) {
        val user = getUser(ctx)
        val pass = getPass(ctx)
        Thread {
            for (attempt in 0 until MAX_ATTEMPTS) {
                try {
                    Thread.sleep(if (attempt == 0) FIRST_DELAY_MS else RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (postPin(host, pin, user, pass, deviceName)) {
                    LimeLog.info("$TAG: PIN enviado a Sunshine automáticamente (intento ${attempt + 1})")
                    return@Thread
                }
            }
            LimeLog.warning("$TAG: no se pudo auto-enviar el PIN; el usuario puede teclearlo en Sunshine")
        }.start()
    }

    private fun postPin(
        host: String,
        pin: String,
        user: String,
        pass: String,
        deviceName: String?
    ): Boolean {
        var conn: HttpsURLConnection? = null
        return try {
            val url = URL("https://$host:$WEB_PORT/api/pin")
            conn = url.openConnection() as HttpsURLConnection
            conn.sslSocketFactory = trustAllFactory()
            conn.setHostnameVerifier { _, _ -> true }
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.doOutput = true

            val credentials = "$user:$pass"
            val auth = Base64.encodeToString(credentials.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("Content-Type", "application/json")

            val safeName = deviceName?.replace("\"", "") ?: "SmartDisplay"
            val body = "{\"pin\":\"$pin\",\"name\":\"$safeName\"}"
            conn.outputStream.use { os: OutputStream ->
                os.write(body.toByteArray(StandardCharsets.UTF_8))
            }

            val code = conn.responseCode
            val resp = readBody(conn)
            val ok = code in 200..299 && resp != null && resp.contains("true")
            if (!ok) {
                LimeLog.info("$TAG: respuesta /api/pin code=$code body=$resp")
            }
            ok
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun readBody(conn: HttpsURLConnection): String? {
        return try {
            val `is` = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
                ?: return null
            val bo = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            var n: Int
            while (`is`.read(buf).also { n = it } != -1) {
                bo.write(buf, 0, n)
            }
            `is`.close()
            bo.toString("UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    private fun trustAllFactory(): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        return ctx.socketFactory
    }
}
