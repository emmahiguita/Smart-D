package com.limelight.smartdisplay.input

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge

/**
 * Interface desacoplada para acciones que el manejador de teclado solicita a la actividad/sesión host.
 */
interface KeyboardSessionCallback {
    fun isInputGrabbed(): Boolean
    fun toggleInputGrab()
    fun finishSession()
    fun toggleCursorVisibility()
    fun isMouseNavButtonsEnabled(): Boolean
}

/**
 * StreamKeyboardHandler — Single Responsibility Principle (SRP)
 *
 * Encapsula la gestión completa del teclado de la sesión:
 * - Tracking de estados de teclas modificadoras (Ctrl, Alt, Shift, Meta).
 * - Shortcuts de control global de Moonlight (Ctrl+Alt+Shift+Z, Ctrl+Alt+Shift+Q, Ctrl+Alt+Shift+C).
 * - Detección y delegación de mandos de juego al ControllerHandler.
 * - Traducción de scancodes Android a scancodes del PC anfitrión vía KeyboardTranslator.
 * - Fallback de caracteres Unicode para teclados software y caracteres compuestos.
 */
class StreamKeyboardHandler(
    private val callback: KeyboardSessionCallback,
    private val keyboardTranslator: KeyboardTranslator,
    private var controllerHandler: ControllerHandler?,
    var connection: NvConnection? = null
) {
    var modifierFlags: Int = 0
        private set

    private var waitingForAllModifiersUp: Boolean = false
    private var specialKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN

    fun setControllerHandler(handler: ControllerHandler?) {
        this.controllerHandler = handler
    }

    /**
     * Resetea el estado de modificadores al perder o recuperar foco.
     */
    fun resetModifiers() {
        modifierFlags = 0
        waitingForAllModifiersUp = false
        specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
    }

    /**
     * Procesa combinaciones de teclas especiales de control de Moonlight.
     * Retorna true si la tecla fue consumida.
     */
    private fun handleSpecialKeys(androidKeyCode: Int, down: Boolean): Boolean {
        var modifierMask = 0
        var nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN

        when (androidKeyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_CTRL.toInt()
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_SHIFT.toInt()
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_ALT.toInt()
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_META.toInt()
            else ->
                nonModifierKeyCode = androidKeyCode
        }

        if (down) {
            modifierFlags = modifierFlags or modifierMask
        } else {
            modifierFlags = modifierFlags and modifierMask.inv()
        }

        // Manejo de combos especiales en el key up
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode == androidKeyCode) {
                return true
            } else if (modifierFlags != 0) {
                return down
            } else {
                when (specialKeyCode) {
                    KeyEvent.KEYCODE_Z -> callback.toggleInputGrab()
                    KeyEvent.KEYCODE_Q -> callback.finishSession()
                    KeyEvent.KEYCODE_C -> callback.toggleCursorVisibility()
                }
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
                waitingForAllModifiersUp = false
            }
        } else {
            val comboMask = KeyboardPacket.MODIFIER_CTRL.toInt() or
                    KeyboardPacket.MODIFIER_ALT.toInt() or
                    KeyboardPacket.MODIFIER_SHIFT.toInt()
            if ((modifierFlags and comboMask) == comboMask && (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)) {
                when (androidKeyCode) {
                    KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_C -> {
                        specialKeyCode = androidKeyCode
                        waitingForAllModifiersUp = true
                        return true
                    }
                    else -> return false
                }
            }
        }

        return false
    }

    fun getModifierState(event: KeyEvent): Byte {
        var modifier = getModifierState().toInt()
        if (event.isShiftPressed) modifier = modifier or KeyboardPacket.MODIFIER_SHIFT.toInt()
        if (event.isCtrlPressed) modifier = modifier or KeyboardPacket.MODIFIER_CTRL.toInt()
        if (event.isAltPressed) modifier = modifier or KeyboardPacket.MODIFIER_ALT.toInt()
        if (event.isMetaPressed) modifier = modifier or KeyboardPacket.MODIFIER_META.toInt()
        return modifier.toByte()
    }

    fun getModifierState(): Byte = modifierFlags.toByte()

    fun handleKeyDown(event: KeyEvent): Boolean {
        if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }

        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                    eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (!callback.isMouseNavButtonsEnabled()) {
                connection?.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
            }
            return true
        }

        var handled = false
        if (ControllerHandler.isGameControllerDevice(event.device)) {
            handled = controllerHandler?.handleButtonDown(event) ?: false
        }

        if (!handled) {
            if (handleSpecialKeys(event.keyCode, true)) {
                return true
            }

            if (!callback.isInputGrabbed()) {
                return false
            }

            val translated = keyboardTranslator.translate(event.keyCode, event.deviceId)
            if (translated.toInt() == 0) {
                val unicodeChar = event.unicodeChar
                if ((unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 &&
                    (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0
                ) {
                    connection?.sendUtf8Text("" + unicodeChar.toChar())
                    return true
                }
                return false
            }

            if (event.repeatCount > 0) {
                return true
            }

            val flags = if (keyboardTranslator.hasNormalizedMapping(event.keyCode, event.deviceId)) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
            connection?.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, getModifierState(event), flags)
        }

        return true
    }

    fun handleKeyUp(event: KeyEvent): Boolean {
        if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }

        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                    eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (!callback.isMouseNavButtonsEnabled()) {
                connection?.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            }
            return true
        }

        var handled = false
        if (ControllerHandler.isGameControllerDevice(event.device)) {
            handled = controllerHandler?.handleButtonUp(event) ?: false
        }

        if (!handled) {
            if (handleSpecialKeys(event.keyCode, false)) {
                return true
            }

            if (!callback.isInputGrabbed()) {
                return false
            }

            val translated = keyboardTranslator.translate(event.keyCode, event.deviceId)
            if (translated.toInt() == 0) {
                val unicodeChar = event.unicodeChar
                return (unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 &&
                        (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0
            }

            val flags = if (keyboardTranslator.hasNormalizedMapping(event.keyCode, event.deviceId)) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
            connection?.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, getModifierState(event), flags)
        }

        return true
    }

    fun handleKeyMultiple(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN || event.characters == null) {
            return false
        }
        connection?.sendUtf8Text(event.characters)
        return true
    }

    fun handleVirtualKeyEvent(keyCode: Short, buttonDown: Boolean): Boolean {
        val keyMap = keyboardTranslator.translate(keyCode.toInt(), -1)
        if (keyMap.toInt() != 0) {
            if (handleSpecialKeys(keyCode.toInt(), buttonDown)) {
                return true
            }
            val keyAction = if (buttonDown) KeyboardPacket.KEY_DOWN else KeyboardPacket.KEY_UP
            connection?.sendKeyboardInput(keyMap, keyAction, getModifierState(), 0)
            return true
        }
        return false
    }


    
}