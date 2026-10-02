package com.limelight.smartdisplay.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject

/**
 * ClipboardSyncManager — Single Responsibility & Open/Closed Principle (SOLID)
 *
 * Sincroniza el portapapeles bidireccionalmente entre el dispositivo Android y el PC.
 * Evita bucles de retroalimentación (echo feedback) usando un flag de aplicación remota.
 */
class ClipboardSyncManager(
    private val context: Context,
    private val sendToBus: (JSONObject) -> Unit
) {
    private val clipboardManager: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    @Volatile private var applyingRemoteClip: Boolean = false
    @Volatile private var lastClipText: String? = null

    /** Inicia la escucha de cambios en el portapapeles local. */
    fun start() {
        if (clipboardManager == null || clipListener != null) return
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            if (applyingRemoteClip) return@OnPrimaryClipChangedListener
            val clip = clipboardManager.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
            val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return@OnPrimaryClipChangedListener
            if (text.isEmpty() || text == lastClipText) return@OnPrimaryClipChangedListener
            lastClipText = text

            try {
                val json = JSONObject().apply {
                    put("type", "clipboard")
                    put("text", text)
                }
                sendToBus(json)
            } catch (_: Exception) {}
        }
        clipboardManager.addPrimaryClipChangedListener(clipListener)
    }

    /** Aplica en el portapapeles local el texto copiado en el PC host. */
    fun applyRemoteClipboard(text: String?) {
        if (text == null || text == lastClipText || clipboardManager == null) return
        applyingRemoteClip = true
        try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("SmartDisplay", text))
            lastClipText = text
        } finally {
            applyingRemoteClip = false
        }
    }

    /** Desregistra el listener y libera recursos. */
    fun release() {
        if (clipboardManager != null && clipListener != null) {
            try {
                clipboardManager.removePrimaryClipChangedListener(clipListener)
            } catch (_: Exception) {}
            clipListener = null
        }
    }
}
