package com.limelight.smartdisplay.connection

import android.content.Context
import android.content.SharedPreferences
import com.limelight.smartdisplay.AppContainer

/**
 * CompanionConfig — Single Responsibility Principle (SOLID)
 *
 * Repositorio centralizado y fuertemente tipado para el acceso a la configuración
 * persistida del companion host y sus tokens de sesión.
 */
class CompanionConfig(
    private val prefs: SharedPreferences = AppContainer.instance.companionPrefs()
) {
    companion object {
        const val PREFS_NAME = "smartdisplay_companion"
        const val KEY_HOST   = "companion_host"
        const val KEY_TOKEN  = "companion_token"
    }

    fun getHost(): String? = prefs.getString(KEY_HOST, null)
    fun setHost(host: String) = prefs.edit().putString(KEY_HOST, host).apply()

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun setToken(token: String) = prefs.edit().putString(KEY_TOKEN, token).apply()
}
