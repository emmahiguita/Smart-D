package com.limelight.smartdisplay.profile

/**
 * SmartDisplay AI – Touch Engine 2.0 (FASE 3)
 *
 * Perfiles de interacción seleccionables desde los "Quick Actions" de la
 * pantalla principal. Cada perfil ajusta parámetros de la capa de interpretación
 * táctil (por encima del Input Base Engine / protocolo, que NO se tocan).
 *
 * Los valores por defecto del perfil AUTO reproducen exactamente el
 * comportamiento actual (sensibilidad 1.0), de modo que activar el sistema no
 * cambia nada hasta que el usuario elige un perfil concreto.
 */
enum class InteractionProfile(
    val id: String,
    val pointerSensitivity: Float,
    val scrollSensitivity: Float,
    @get:JvmName("isNaturalScroll")
    val isNaturalScroll: Boolean
) {
    AUTO("auto", 1.00f, 1.0f, false),
    GAMING("gaming", 1.35f, 1.0f, false),
    PRODUCTIVITY("productivity", 1.00f, 1.2f, false),
    MEDIA("media", 0.85f, 1.0f, true),
    OFFICE("office", 1.10f, 1.3f, false);

    companion object {
        /** Resuelve un perfil por su id persistido; devuelve AUTO si no coincide. */
        @JvmStatic
        fun fromId(id: String?): InteractionProfile {
            if (id != null) {
                for (p in values()) {
                    if (p.id == id) {
                        return p
                    }
                }
            }
            return AUTO
        }
    }
}
