package com.limelight.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import com.limelight.Game
import com.limelight.R
import com.limelight.nvstream.input.KeyboardPacket

/**
 * AudioHudController — Widget flotante para control rápido de audio y micrófonos:
 *  - Mute/Unmute universal de micrófono (Ctrl+Shift+M para Teams/Discord/Zoom)
 *  - Volumen Arriba / Abajo / Mute multimedia
 *  - Aceptar llamada / Play-Pause
 *  - Arrastre táctil suave por pantalla
 */
class AudioHudController(
    private val context: Context,
    private val parentView: ViewGroup,
    private val hardwareKeySender: (Short, Byte) -> Unit
) {

    /** Constructor secundario para compatibilidad directa con Game */
    constructor(gameContext: Game, parentView: ViewGroup) : this(
        gameContext,
        parentView,
        { keyMap, keyDirection -> gameContext.sendHardwareKey(keyMap, keyDirection) }
    )

    private var root: View? = null
    private var dX = 0f
    private var dY = 0f
    private var isDragging = false

    init {
        initView()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.audio_hud_widget, parentView, false)
        root = view

        // Posicionamiento inicial en el borde derecho
        view.x = (parentView.width - 200).coerceAtLeast(0).toFloat()
        view.y = 200f

        val handle = view.findViewById<View>(R.id.audioHudHandle)
        handle?.setOnTouchListener { v, event -> handleTouchDrag(v, event) }

        val btnMic = view.findViewById<ImageButton>(R.id.btnHudMic)
        val btnVolUp = view.findViewById<ImageButton>(R.id.btnHudVolUp)
        val btnVolDown = view.findViewById<ImageButton>(R.id.btnHudVolDown)
        val btnMute = view.findViewById<ImageButton>(R.id.btnHudMute)
        val btnCall = view.findViewById<ImageButton>(R.id.btnHudCall)

        btnMic?.setOnClickListener { sendMacroMicMute() }
        btnVolUp?.setOnClickListener { sendMacroVolUp() }
        btnVolDown?.setOnClickListener { sendMacroVolDown() }
        btnMute?.setOnClickListener { sendMacroMute() }
        btnCall?.setOnClickListener { sendMacroAcceptCall() }

        parentView.addView(view)
        hide()
    }

    fun show() {
        root?.let {
            it.visibility = View.VISIBLE
            it.animate().alpha(1f).setDuration(200).start()
        }
    }

    fun hide() {
        root?.let {
            it.animate().alpha(0f).setDuration(200)
                .withEndAction { it.visibility = View.GONE }
                .start()
        }
    }

    fun toggle() {
        if (root?.visibility == View.VISIBLE) {
            hide()
        } else {
            show()
        }
    }

    private fun handleTouchDrag(view: View, event: MotionEvent): Boolean {
        val target = root ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dX = target.x - event.rawX
                dY = target.y - event.rawY
                isDragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    target.animate().x(event.rawX + dX).y(event.rawY + dY).setDuration(0).start()
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return false
    }

    // --- MACROS PARA PC ---

    /** Ctrl + Shift + M (Teams / Discord / Zoom Universal Mic Mute) */
    private fun sendMacroMicMute() {
        val ctrl = ((0x80 shl 8) or 0xA2).toShort() // L-CTRL
        val shift = ((0x80 shl 8) or 0xA0).toShort() // L-SHIFT
        val m = ((0x80 shl 8) or 0x4D).toShort() // M

        hardwareKeySender(ctrl, KeyboardPacket.KEY_DOWN)
        hardwareKeySender(shift, KeyboardPacket.KEY_DOWN)
        hardwareKeySender(m, KeyboardPacket.KEY_DOWN)

        hardwareKeySender(m, KeyboardPacket.KEY_UP)
        hardwareKeySender(shift, KeyboardPacket.KEY_UP)
        hardwareKeySender(ctrl, KeyboardPacket.KEY_UP)
    }

    /** VK_VOLUME_UP (0xAF) */
    private fun sendMacroVolUp() {
        val volUp = ((0x80 shl 8) or 0xAF).toShort()
        hardwareKeySender(volUp, KeyboardPacket.KEY_DOWN)
        hardwareKeySender(volUp, KeyboardPacket.KEY_UP)
    }

    /** VK_VOLUME_DOWN (0xAE) */
    private fun sendMacroVolDown() {
        val volDown = ((0x80 shl 8) or 0xAE).toShort()
        hardwareKeySender(volDown, KeyboardPacket.KEY_DOWN)
        hardwareKeySender(volDown, KeyboardPacket.KEY_UP)
    }

    /** VK_VOLUME_MUTE (0xAD) */
    private fun sendMacroMute() {
        val mute = ((0x80 shl 8) or 0xAD).toShort()
        hardwareKeySender(mute, KeyboardPacket.KEY_DOWN)
        hardwareKeySender(mute, KeyboardPacket.KEY_UP)
    }

    /** VK_MEDIA_PLAY_PAUSE (0xB3) */
    private fun sendMacroAcceptCall() {
        val playPause = ((0x80 shl 8) or 0xB3).toShort()
        hardwareKeySender(playPause, KeyboardPacket.KEY_DOWN)
        hardwareKeySender(playPause, KeyboardPacket.KEY_UP)
    }
}
