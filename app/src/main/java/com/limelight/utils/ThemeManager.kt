package com.limelight.utils

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.limelight.R

/**
 * Punto único para aplicar el tema seleccionado por el usuario. Debe llamarse
 * en onCreate() de CADA Activity antes de super.onCreate()/setContentView() para
 * que el tema neón (o el elegido) se aplique en toda la app sin excepción.
 */
object ThemeManager {

    private const val PREFS = "smartdisplay_theme"
    private const val KEY = "theme"
    private const val KEY_MODE = "mode"
    private const val DEFAULT = "universe"
    /** Modo por defecto: oscuro (coherente con la identidad neón del proyecto). */
    private const val DEFAULT_MODE = "dark"

    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    @JvmStatic
    fun getThemeKey(context: Context?): String {
        if (context == null) return DEFAULT
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY, DEFAULT) ?: DEFAULT
        } catch (e: Exception) {
            DEFAULT
        }
    }

    @JvmStatic
    fun setThemeKey(context: Context?, key: String?) {
        if (context == null) return
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, key).apply()
        } catch (ignored: Exception) {
        }
    }

    /** Modo actual: "light" u "dark". */
    @JvmStatic
    fun getMode(context: Context?): String {
        if (context == null) return DEFAULT_MODE
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, DEFAULT_MODE) ?: DEFAULT_MODE
        } catch (e: Exception) {
            DEFAULT_MODE
        }
    }

    @JvmStatic
    fun setMode(context: Context?, mode: String?) {
        if (context == null) return
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode).apply()
        } catch (ignored: Exception) {
        }
        if (MODE_LIGHT == mode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    /**
     * Restaura el modo noche desde SharedPreferences.
     * Llamar desde [android.app.Application.onCreate] ANTES de
     * que ninguna Activity se infle, para que el modo claro/oscuro sea
     * coherente en todo el proceso desde el primer frame.
     */
    @JvmStatic
    fun restoreNightMode(context: Context?) {
        val mode = getMode(context)
        if (MODE_LIGHT == mode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    @JvmStatic
    fun isLight(context: Context?): Boolean {
        return MODE_LIGHT == getMode(context)
    }

    /** Resuelve el estilo combinando acento y modo (claro/oscuro). */
    @JvmStatic
    fun resolveStyle(key: String?, light: Boolean): Int {
        val effectiveKey = key ?: DEFAULT
        return when (effectiveKey) {
            "pixel" -> if (light) R.style.Theme_SmartDisplayAI_Pixel_Light else R.style.Theme_SmartDisplayAI_Pixel
            "emerald" -> if (light) R.style.Theme_SmartDisplayAI_Emerald_Light else R.style.Theme_SmartDisplayAI_Emerald
            "amber" -> if (light) R.style.Theme_SmartDisplayAI_Amber_Light else R.style.Theme_SmartDisplayAI_Amber
            "graphite" -> if (light) R.style.Theme_SmartDisplayAI_Graphite_Light else R.style.Theme_SmartDisplayAI_Graphite
            "glass" -> if (light) R.style.Theme_SmartDisplayAI_Glass_Light else R.style.Theme_SmartDisplayAI_Glass
            "rain" -> if (light) R.style.Theme_SmartDisplayAI_Rain_Light else R.style.Theme_SmartDisplayAI_Rain
            "universe" -> if (light) R.style.Theme_SmartDisplayAI_Universe_Light else R.style.Theme_SmartDisplayAI_Universe
            else -> if (light) R.style.Theme_SmartDisplayAI_Universe_Light else R.style.Theme_SmartDisplayAI_Universe
        }
    }

    /** Aplica el tema (acento + modo) guardado a la Activity e inyecta universalmente el fondo dinámico. */
    @JvmStatic
    fun apply(activity: Activity) {
        val light = isLight(activity)
        activity.setTheme(resolveStyle(getThemeKey(activity), light))
        try {
            activity.window.decorView.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val root = content?.getChildAt(0)
                if (root != null) {
                    applyAmbientBackground(root)
                    if (root is androidx.drawerlayout.widget.DrawerLayout && root.childCount > 0) {
                        val mainChild = root.getChildAt(0)
                        if (mainChild != null) {
                            applyAmbientBackground(mainChild)
                        }
                    }
                } else {
                    activity.window.setBackgroundDrawable(createAmbientBackground(activity))
                }
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Color de acento (colorPrimary) del tema elegido por el usuario, resuelto
     * contra su estilo aunque la Activity actual no lo aplique (p. ej. el streaming).
     * Permite que los overlays sigan el tema como el resto de la app.
     */
    @JvmStatic
    fun accentColor(context: Context?): Int {
        val key = getThemeKey(context)
        return accentColorForKey(key)
    }

    @JvmStatic
    fun accentColorForKey(key: String?): Int {
        return when (key) {
            "pixel" -> 0xFF4285F4.toInt() // Pixel Blue
            "emerald" -> 0xFF10B981.toInt() // Emerald Green
            "amber" -> 0xFFF59E0B.toInt() // Amber Orange
            "graphite" -> 0xFF94A3B8.toInt() // Slate Graphite
            "glass" -> 0xFF06B6D4.toInt() // Glass Cyan
            "rain" -> 0xFF3B82F6.toInt() // Electric Rain Blue
            "universe" -> 0xFF8B5CF6.toInt() // Universe Purple
            else -> 0xFF8B5CF6.toInt()
        }
    }

    /**
     * Color complementario/secundario para crear degradados duotono vivos,
     * refracciones cáusticas y halos ambientales orgánicos según el tema activo.
     */
    @JvmStatic
    fun secondaryAccentColor(context: Context?): Int {
        val key = getThemeKey(context)
        return secondaryAccentColorForKey(key)
    }

    @JvmStatic
    fun secondaryAccentColorForKey(key: String?): Int {
        return when (key) {
            "pixel" -> 0xFF34A853.toInt() // Pixel Green
            "emerald" -> 0xFF06B6D4.toInt() // Emerald Cyan/Teal
            "amber" -> 0xFFEF4444.toInt() // Sunset Crimson
            "graphite" -> 0xFF64748B.toInt() // Slate Steel
            "glass" -> 0xFF38BDF8.toInt() // Sky Glass
            "rain" -> 0xFF8B5CF6.toInt() // Cobalt Violet
            "universe" -> 0xFFEC4899.toInt() // Cosmic Magenta
            else -> 0xFFEC4899.toInt()
        }
    }

    /**
     * Genera un lienzo ambiental OLED dinámico con orbes radiales reales
     * inyectados con los colores del tema activo del usuario.
     */
    @JvmStatic
    fun createAmbientBackground(context: Context): android.graphics.drawable.Drawable {
        val primary = accentColor(context)
        val secondary = secondaryAccentColor(context)
        val light = isLight(context)

        val density = context.resources.displayMetrics.density

        // Capa 0: Fondo base sólido (Negro OLED en oscuro, blanco perla en claro)
        val baseColor = if (light) 0xFFF1F5F9.toInt() else 0xFF030508.toInt()
        val baseDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(baseColor)
        }

        // Capa 1: Resplandor radial superior (Primario)
        val topAlphaStart = if (light) 0x1A else 0x33
        val topAlphaMid = if (light) 0x08 else 0x12
        val topStart = (topAlphaStart shl 24) or (primary and 0x00FFFFFF)
        val topMid = (topAlphaMid shl 24) or (primary and 0x00FFFFFF)
        val topEnd = 0x00000000

        val topRadial = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 380f * density
            setGradientCenter(0.5f, 0.05f)
            colors = intArrayOf(topStart, topMid, topEnd)
        }

        // Capa 2: Resplandor radial secundario lateral (Secundario)
        val secAlphaStart = if (light) 0x14 else 0x28
        val secAlphaMid = if (light) 0x05 else 0x0C
        val secStart = (secAlphaStart shl 24) or (secondary and 0x00FFFFFF)
        val secMid = (secAlphaMid shl 24) or (secondary and 0x00FFFFFF)
        val secEnd = 0x00000000

        val secRadial = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 350f * density
            setGradientCenter(0.88f, 0.42f)
            colors = intArrayOf(secStart, secMid, secEnd)
        }

        // Capa 3: Viñeta suave inferior
        val vignetteStart = 0x00000000
        val vignetteEnd = if (light) 0x15000000 else 0x55000000
        val vignette = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(vignetteStart, vignetteEnd)
        )

        return android.graphics.drawable.LayerDrawable(
            arrayOf(baseDrawable, topRadial, secRadial, vignette)
        )
    }

    /**
     * Inyecta de forma universal el fondo ambiental en tiempo real en cualquier vista o layout raíz.
     */
    @JvmStatic
    fun applyAmbientBackground(view: android.view.View?) {
        if (view == null) return
        view.background = createAmbientBackground(view.context)
    }
}
