package com.limelight.ui.glass

import android.graphics.Canvas
import android.graphics.RectF

/**
 * GlassContract — Contrato y principios SOLID para componentes de diseño Glassmorphism iOS.
 *
 * S - Single Responsibility: Cada renderizador maneja un aspecto óptico específico (Cuerpo, Bisel Especular, Halo).
 * O - Open/Closed: Extensible mediante composición de shaders sin modificar los widgets existentes.
 * L - Liskov Substitution: Cualquier renderizador que implemente [GlassSurfaceRenderer] puede intercambiarse.
 * I - Interface Segregation: Interfaces separadas para renderizado, interactividad táctil y animación.
 * D - Dependency Inversion: Los widgets dependen de abstracciones [GlassStyleConfig], no de valores fijos en código.
 */

/**
 * Configuración inmutable de propiedades ópticas y físicas para componentes de cristal iOS.
 */
data class GlassStyleConfig(
    val baseFillColor: Int = 0x3D141C2B,        // Transparencia zafiro ahumado
    val specularStrokeColor: Int = 0x59FFFFFF,   // Bisel superior especular
    val cornerRadiusDp: Float = 20f,             // Radio squircle Apple
    val strokeWidthDp: Float = 1.2f,             // Grosor de borde micrométrico
    val enableHaptics: Boolean = true,           // Respuesta táctil háptica
    val pressScale: Float = 0.965f               // Deformación física suave
)

/**
 * Interfaz para componentes que renderizan superficies de cristal multicapa.
 */
interface GlassSurfaceRenderer {
    fun updateBounds(bounds: RectF, cornerRadiusPx: Float)
    fun drawSurface(canvas: Canvas)
    fun drawSpecularBevel(canvas: Canvas)
}

/**
 * Interfaz para componentes con respuesta física y háptica estilo Apple Spring.
 */
interface GlassInteractiveComponent {
    fun onPointerDown(x: Float, y: Float)
    fun onPointerUp()
    fun onPointerCancel()
}
