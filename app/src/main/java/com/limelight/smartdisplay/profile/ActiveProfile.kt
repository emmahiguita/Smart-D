package com.limelight.smartdisplay.profile

/**
 * SmartDisplay AI – Touch Engine 2.0 (FASE 3)
 *
 * Estado activo del perfil de interacción, accesible de forma ligera desde la
 * capa de interpretación táctil (TouchContext) sin acoplarla a SharedPreferences
 * ni cambiar sus constructores.
 *
 * Los campos son volátiles porque se escriben desde el hilo UI (al seleccionar
 * perfil o al iniciar la sesión) y se leen desde el hilo de toques.
 *
 * Valor por defecto neutro (1.0): comportamiento idéntico al original mientras
 * no se aplique un perfil.
 */
object ActiveProfile {

    @Volatile
    @JvmStatic
    var current: InteractionProfile = InteractionProfile.AUTO
        private set

    @Volatile
    @JvmStatic
    var pointerSensitivity: Float = 1.0f
        private set

    @Volatile
    @JvmStatic
    var scrollSensitivity: Float = 1.0f
        private set

    @Volatile
    @JvmStatic
    @get:JvmName("isNaturalScroll")
    var isNaturalScroll: Boolean = false
        private set

    @JvmStatic
    fun set(profile: InteractionProfile?) {
        val p = profile ?: InteractionProfile.AUTO
        current = p
        pointerSensitivity = p.pointerSensitivity
        scrollSensitivity = p.scrollSensitivity
        isNaturalScroll = p.isNaturalScroll
    }
}
