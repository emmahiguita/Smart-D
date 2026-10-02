package com.limelight

import android.app.Application
import com.limelight.utils.ThemeManager

/**
 * Punto de arranque único del proceso.
 * Restaura el modo día/noche (AppCompatDelegate) ANTES de que cualquier
 * Activity infle su layout, garantizando que el modo Claro Expressive
 * o el modo Oscuro Neón se aplique en toda la app de forma coherente.
 */
class SmartDisplayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Restaurar el modo noche guardado por el usuario.
        // Sin esto, cada reinicio de proceso usa MODE_NIGHT_FOLLOW_SYSTEM
        // y el tema claro no persiste aunque ThemeManager.apply() lo aplique por Activity.
        ThemeManager.restoreNightMode(this)
    }
}
