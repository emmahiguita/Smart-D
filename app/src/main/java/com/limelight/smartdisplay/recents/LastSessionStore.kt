package com.limelight.smartdisplay.recents

import android.content.Context
import android.content.SharedPreferences

/**
 * SmartDisplay AI � UX Layer (FASE 2): �ltima sesi�n
 *
 * Almac�n ligero y persistente del �ltimo stream lanzado, usado para mostrar
 * la tarjeta "�ltima sesi�n" en la pantalla principal y permitir relanzarlo
 * con un toque.
 *
 * A diferencia de [SessionRecoveryManager] (que se limpia al cerrar
 * voluntariamente), este registro persiste hasta que se lanza otra sesi�n,
 * de modo que el acceso r�pido siga disponible tras un cierre normal.
 *
 * No interact�a con Moonlight: solo lee/escribe SharedPreferences.
 */
class LastSessionStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(
        pcUuid: String?,
        pcName: String?,
        appName: String?,
        appId: Int,
        appHdr: Boolean
    ) {
        if (pcUuid.isNullOrEmpty()) {
            return
        }
        prefs.edit()
            .putString(KEY_PC_UUID, pcUuid)
            .putString(KEY_PC_NAME, pcName ?: "")
            .putString(KEY_APP_NAME, appName ?: "")
            .putInt(KEY_APP_ID, appId)
            .putBoolean(KEY_APP_HDR, appHdr)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .putBoolean(KEY_HAS, true)
            .apply()
    }

    fun has(): Boolean = prefs.getBoolean(KEY_HAS, false)
    fun getPcUuid(): String = prefs.getString(KEY_PC_UUID, "") ?: ""
    fun getPcName(): String = prefs.getString(KEY_PC_NAME, "") ?: ""
    val appName: String get() = prefs.getString(KEY_APP_NAME, "") ?: ""
    val appId: Int get() = prefs.getInt(KEY_APP_ID, -1)
    fun getAppHdr(): Boolean = prefs.getBoolean(KEY_APP_HDR, false)

    fun clear() {
        prefs.edit().putBoolean(KEY_HAS, false).apply()
    }

    companion object {
        private const val PREFS_NAME = "sd_last_session"
        private const val KEY_PC_UUID = "pc_uuid"
        private const val KEY_PC_NAME = "pc_name"
        private const val KEY_APP_NAME = "app_name"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_APP_HDR = "app_hdr"
        private const val KEY_SAVED_AT = "saved_at"
        private const val KEY_HAS = "has_last"
    }
}

