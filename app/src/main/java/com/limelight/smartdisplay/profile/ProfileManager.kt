package com.limelight.smartdisplay.profile

import android.content.Context
import android.content.SharedPreferences

/**
 * SmartDisplay AI – Touch Engine 2.0 (FASE 3)
 *
 * Persiste el perfil de interacción elegido por el usuario (vía Quick Actions)
 * y lo publica en [ActiveProfile] para que la capa táctil lo aplique.
 */
class ProfileManager(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Perfil guardado (AUTO por defecto). */
    fun current(): InteractionProfile {
        return InteractionProfile.fromId(prefs.getString(KEY_PROFILE, InteractionProfile.AUTO.id))
    }

    /** Guarda el perfil y lo publica de inmediato en [ActiveProfile]. */
    fun select(profile: InteractionProfile?) {
        val p = profile ?: InteractionProfile.AUTO
        prefs.edit().putString(KEY_PROFILE, p.id).apply()
        ActiveProfile.set(p)
    }

    /**
     * Carga el perfil persistido en [ActiveProfile]. Llamar al iniciar la
     * sesión de streaming para que el perfil elegido se aplique a los toques.
     */
    fun applyToActive() {
        ActiveProfile.set(current())
    }

    companion object {
        private const val PREFS_NAME = "sd_interaction_profile"
        private const val KEY_PROFILE = "active_profile"
    }
}
