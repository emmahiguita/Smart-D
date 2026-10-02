package com.limelight

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.util.Linkify
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.limelight.utils.ThemeManager

/**
 * Manual de uso in-app. Pantalla nativa (no WebView) que explica, paso a paso,
 * cómo conectar SmartDisplay a un servidor y usar sus funciones. Se construye
 * mediante tarjetas temáticas para que respete el tema activo sin excepción.
 */
class ManualActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual)

        val btnClose = findViewById<View>(R.id.btnClose)
        btnClose?.setOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.manual_container)
        for (section in SECTIONS) {
            container.addView(buildSectionCard(section[0], section[1]))
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_fade_enter, R.anim.activity_slide_out_right)
    }

    private fun buildSectionCard(title: String, body: String): View {
        val pad = dp(18)
        val gap = dp(12)
        val accent = ThemeManager.accentColor(this)

        val card = com.limelight.ui.glass.IosGlassCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = gap
            }
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accent)
        }

        val bodyView = TextView(this).apply {
            text = body
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 13.5f
            setLineSpacing(dp(3).toFloat(), 1.15f)
            Linkify.addLinks(this, Linkify.WEB_URLS)
            setLinkTextColor(accent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        inner.addView(titleView)
        inner.addView(bodyView)
        card.addView(inner)
        return card
    }

    private fun dp(value: Int): Int {
        return Math.round(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                resources.displayMetrics
            )
        )
    }

    companion object {
        private val SECTIONS = arrayOf(
            arrayOf(
                "1 · ¿Qué es SmartDisplay?",
                "SmartDisplay convierte tu teléfono en una ventana hacia tu PC: transmite en vivo " +
                        "la pantalla del ordenador con baja latencia y te permite controlarlo como si " +
                        "estuvieras frente a él. La PC es la protagonista; la app solo asiste."
            ),
            arrayOf(
                "2 · Descargas en el PC",
                "Instala en tu computadora (Windows) el servidor de streaming y, si quieres jugar " +
                        "fuera de casa, la VPN Tailscale. Toca para descargar:\n\n" +
                        "• Sunshine (servidor de streaming):\n" +
                        "https://github.com/LizardByte/Sunshine/releases/latest/download/sunshine-windows-x64-setup.exe\n\n" +
                        "• Tailscale para PC (opcional, uso remoto):\n" +
                        "https://pkgs.tailscale.com/stable/tailscale-setup-latest.exe\n\n" +
                        "• Tailscale para Android (opcional):\n" +
                        "https://tailscale.com/download/android"
            ),
            arrayOf(
                "3 · Instalar Sunshine y su consola",
                "1. Ejecuta el instalador .exe de Sunshine en el PC.\n" +
                        "2. Abre la consola web escribiendo en el navegador del PC:\n" +
                        "http://localhost:47990\n" +
                        "3. En el primer acceso, Sunshine te obliga a crear un USUARIO y CONTRASEÑA para " +
                        "proteger la consola. Anótalos: los usarás en el emparejamiento automático.\n" +
                        "4. Ya verás el panel con el estado del servidor, la GPU y las sesiones activas."
            ),
            arrayOf(
                "4 · Aplicaciones y juegos en Sunshine",
                "En la pestaña «Applications» (https://localhost:47990/apps) verás lo que aparece " +
                        "en tu teléfono. Sunshine trae dos por defecto:\n" +
                        "• Desktop: transmite el escritorio completo de Windows.\n" +
                        "• Steam Big Picture: abre Steam en modo mando/pantalla táctil.\n\n" +
                        "Para AÑADIR un juego/programa:\n" +
                        "1. «Applications» → «+ Add New».\n" +
                        "2. Application Name: el nombre del juego.\n" +
                        "3. Command: la ruta del .exe (p. ej. C:\\...\\Cyberpunk2077.exe).\n" +
                        "4. Working Directory: la carpeta que contiene ese .exe.\n" +
                        "5. (Opcional) Image Path: una imagen .png de portada.\n" +
                        "6. «Save» y luego, arriba a la derecha, «Apply» para reiniciar el servicio y que " +
                        "tu celular detecte los cambios."
            ),
            arrayOf(
                "5 · Conectar el teléfono",
                "1. Abre el menú lateral (☰) y toca «Agregar equipo».\n" +
                        "2. Si el PC está en tu red, aparecerá automáticamente en «Tus equipos».\n" +
                        "3. Si no aparece, escribe la dirección IP del PC (o su IP de Tailscale 100.x.y.z).\n" +
                        "4. Toca el equipo para iniciar el emparejamiento."
            ),
            arrayOf(
                "6 · Emparejamiento por PIN",
                "Al conectar por primera vez, SmartDisplay genera un PIN.\n\n" +
                        "• Manual: abre la pestaña «PIN» de la consola de Sunshine " +
                        "(https://localhost:47990), escribe el PIN del teléfono y pulsa «Send».\n\n" +
                        "• Automático (recomendado): en Ajustes de SmartDisplay guarda el MISMO usuario y " +
                        "contraseña que creaste en Sunshine. La app enviará el PIN sola en segundo plano " +
                        "(POST https://IP:47990/api/pin) y el enlace será inmediato.\n\n" +
                        "Una vez emparejado, el equipo queda «Listo para conectar» y no vuelve a pedir PIN."
            ),
            arrayOf(
                "7 · Jugar fuera de casa (Tailscale)",
                "Para transmitir con datos móviles (4G/5G) u otra red Wi-Fi:\n" +
                        "1. Instala Tailscale en el PC y en el teléfono (enlaces en la sección Descargas).\n" +
                        "2. Inicia sesión en AMBOS con la MISMA cuenta (Gmail, Microsoft o GitHub).\n" +
                        "3. Cada dispositivo recibe una IP privada 100.x.y.z.\n" +
                        "4. Al abrir SmartDisplay, detectará tu PC por el túnel seguro y podrás transmitir " +
                        "estés donde estés."
            ),
            arrayOf(
                "8 · Iniciar el streaming",
                "Toca el botón de reproducir del equipo o selecciona una app/escritorio de la lista. " +
                        "La transmisión empieza en pantalla completa. Para terminar, usa el menú flotante " +
                        "y elige «Salir»."
            ),
            arrayOf(
                "9 · Barra flotante y herramientas",
                "Durante el streaming verás un botón flotante que puedes ARRASTRAR a cualquier lugar. " +
                        "Al tocarlo se despliega en abanico: Teclado, Modo Mouse, Zoom, Archivos, Voz, " +
                        "Barra PC (ventanas reales del PC), Dev (compilar/ejecutar) y PiP.\n" +
                        "Cuando no lo usas, se encoge para no estorbar."
            ),
            arrayOf(
                "10 · Transferencia de archivos",
                "Desde el menú lateral, abre «Archivos» para enviar o recibir documentos entre el " +
                        "teléfono y el PC mientras están conectados en la misma red."
            ),
            arrayOf(
                "11 · Temas y apariencia",
                "En «Diseño y temas» eliges entre Universo (neón), Pixel, Esmeralda, Ámbar, Grafito, " +
                        "Cristal (glassmorphism) y Lluvia (con animación). Además puedes alternar modo claro " +
                        "u oscuro. El tema se aplica a toda la app al instante."
            ),
            arrayOf(
                "12 · Ajustes recomendados",
                "• Resolución y FPS: ajústalos a tu red. 1080p/60 es un buen punto de partida.\n" +
                        "• Bitrate: súbelo si la imagen se ve borrosa; bájalo si hay cortes.\n" +
                        "• Companion PIN: escríbelo en Ajustes si tu companion del PC usa autenticación."
            ),
            arrayOf(
                "13 · Solución de problemas",
                "• No aparece el PC: verifica que Sunshine esté activo y ambos en la misma red " +
                        "(o Tailscale encendido en los dos).\n" +
                        "• Imagen entrecortada: baja el bitrate o la resolución, o acércate al router.\n" +
                        "• No empareja: revisa el firewall del PC y que el PIN/credenciales sean correctos.\n" +
                        "• Usa «Diagnóstico» en el menú lateral para probar la red."
            )
        )
    }
}
