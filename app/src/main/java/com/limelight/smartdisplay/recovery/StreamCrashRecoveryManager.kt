package com.limelight.smartdisplay.recovery

import android.content.Context
import android.content.SharedPreferences
import com.limelight.LimeLog
import com.limelight.binding.video.CrashListener
import java.lang.Exception

/**
 * Clean Architecture manager for decoder crash tracking, tombstone counters,
 * and crash recovery state.
 */
class StreamCrashRecoveryManager(
    private val context: Context,
    private val tombstonePrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
) : CrashListener {

    companion object {
        const val PREFS_NAME = "DecoderTombstone"
        const val KEY_CRASH_COUNT = "CrashCount"
        const val KEY_LAST_NOTIFIED = "LastNotifiedCrashCount"
    }

    var reportedCrash: Boolean = false
        private set

    fun getCrashCount(): Int {
        return tombstonePrefs.getInt(KEY_CRASH_COUNT, 0)
    }

    override fun notifyCrash(e: Exception?) {
        LimeLog.severe("MediaCodecDecoderRenderer reported crash: ${e?.message}")
        // We must use commit because the app will crash when we return from this function
        val count = getCrashCount()
        tombstonePrefs.edit().putInt(KEY_CRASH_COUNT, count + 1).commit()
        reportedCrash = true
    }

    /**
     * Clears the tombstone crash count if the session terminated normally without crashes.
     */
    fun onNormalTermination() {
        if (!reportedCrash && getCrashCount() != 0) {
            tombstonePrefs.edit()
                .putInt(KEY_CRASH_COUNT, 0)
                .putInt(KEY_LAST_NOTIFIED, 0)
                .apply()
        }
    }
}
