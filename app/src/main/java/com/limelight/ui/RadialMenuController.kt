package com.limelight.ui

import android.view.View
import android.widget.ImageView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * RadialMenuController — Responsabilidad Única: gestión del pool de botones radiales.
 *
 * Centraliza:
 *  - El estado del menú activo (MAIN / TOOLS).
 *  - La asignación de entradas (iconos, etiquetas, acciones) a los contenedores físicos.
 *  - El CACHÉ de geometría (ángulos y radio), recalculado solo cuando el FAB cambia de posición.
 */
class RadialMenuController(
    private val overlayRoot: View,
    private val radialItems: Array<View?>,
    private val iconViews: Array<ImageView?>,
    private val mainEntries: Array<Entry>,
    private val toolsEntries: Array<Entry>
) {
    companion object {
        const val A_KEYBOARD: Int = 0
        const val A_ZOOM: Int = 1
        const val A_MOUSE: Int = 2
        const val A_FILES: Int = 3
        const val A_VOICE: Int = 4
        const val A_PIP: Int = 5
        const val A_SCREEN: Int = 6
        const val A_EXIT: Int = 9
        const val A_TOOLS_OPEN: Int = 100
        const val A_ZOOM_IN: Int = 101
        const val A_ZOOM_OUT: Int = 102
        const val A_ZOOM_FIT: Int = 103
        const val A_CENTER: Int = 104
        const val A_MOVE_MODE: Int = 105

        private const val RADIAL_RADIUS_DP: Float = 140f
        private const val RADIAL_RADIUS_MIN_DP: Float = 60f
        const val POOL_SIZE: Int = 8
    }

    enum class MenuState { MAIN, TOOLS }

    data class Entry(
        val icon: Int,
        val label: String,
        val action: Int
    )

    var currentEntries: Array<Entry> = mainEntries
        private set

    var activeCount: Int = 0
        private set

    var menuState: MenuState = MenuState.MAIN
        private set

    // Geometry cache
    private var cachedAngles: DoubleArray = DoubleArray(0)
    private var cachedRadius: Float = 0f
    private var geometryCached: Boolean = false

    private val density: Float = overlayRoot.context.resources.displayMetrics.density

    init {
        renderMenu(MenuState.MAIN)
    }

    /** Renderiza (asigna iconos) los botones del menú activo. Oculta los sobrantes. */
    fun renderMenu(state: MenuState) {
        menuState = state
        currentEntries = if (state == MenuState.TOOLS) toolsEntries else mainEntries
        activeCount = min(currentEntries.size, POOL_SIZE)

        for (i in 0 until POOL_SIZE) {
            val item = radialItems.getOrNull(i) ?: continue
            if (i < activeCount) {
                iconViews.getOrNull(i)?.setImageResource(currentEntries[i].icon)
            } else {
                item.animate().cancel()
                item.visibility = View.INVISIBLE
                item.alpha = 0f
            }
        }
    }

    /**
     * Invalida el caché de geometría para que se recalcule en el próximo acceso.
     * Se llama cuando el FAB termina de moverse o la pantalla cambia de orientación.
     */
    fun invalidateGeometry() {
        geometryCached = false
    }

    /** Devuelve el radio adaptativo, recalculando si el caché está inválido. */
    fun getAdaptiveRadius(fabWrapper: View?): Float {
        if (!geometryCached) recomputeGeometry(fabWrapper)
        return cachedRadius
    }

    /** Devuelve los ángulos distribuidos, recalculando si el caché está inválido. */
    fun getDynamicAngles(fabWrapper: View?): DoubleArray {
        if (!geometryCached) recomputeGeometry(fabWrapper)
        return cachedAngles
    }

    /**
     * Recalcula ángulos y radio en un solo paso y los guarda en caché.
     * Este es el único punto donde ocurren los cálculos trigonométricos.
     */
    private fun recomputeGeometry(fabWrapper: View?) {
        val rootW = overlayRoot.width.toFloat()
        val rootH = overlayRoot.height.toFloat()

        if (rootW <= 0 || rootH <= 0 || fabWrapper == null) {
            cachedAngles = DoubleArray(activeCount)
            cachedRadius = RADIAL_RADIUS_MIN_DP * density
            geometryCached = true
            return
        }

        val cx = fabWrapper.x + fabWrapper.width / 2f
        val cy = fabWrapper.y + fabWrapper.height / 2f

        // 1. Calcular ángulo central y arco según posición del FAB
        val nx = cx / rootW
        val ny = cy / rootH

        val centerAngle: Double = when {
            nx > 0.6f && ny > 0.6f -> -135.0
            nx < 0.4f && ny > 0.6f -> -45.0
            nx > 0.6f && ny < 0.4f -> 135.0
            nx < 0.4f && ny < 0.4f -> 45.0
            ny > 0.65f             -> -90.0
            ny < 0.35f             -> 90.0
            nx > 0.6f              -> 180.0
            nx < 0.4f              -> 0.0
            else                   -> -90.0
        }

        val arcSpan: Float = when {
            (nx < 0.25f || nx > 0.75f) && (ny < 0.25f || ny > 0.75f) -> {
                if (activeCount >= 5) 120f else 100f
            }
            nx < 0.25f || nx > 0.75f || ny < 0.25f || ny > 0.75f -> 180f
            else -> 360f
        }

        // 2. Calcular radio seguro
        val itemSz = 48f * density
        val pad = 8f * density
        val fabSz = if (fabWrapper.width > 0) fabWrapper.width.toFloat() else 56f * density
        val minSafe = (fabSz / 2f) + (itemSz / 2f) + 14f * density

        val maxR = min(RADIAL_RADIUS_DP * density, min(rootW, rootH) * 0.34f)
        val minR = max(RADIAL_RADIUS_MIN_DP * density, minSafe)

        var radius = max(minR, maxR)
        val halfSpan = arcSpan / 2.0
        val n = max(1, activeCount)
        val step = if (arcSpan == 360f) (arcSpan / n.toDouble()) else (arcSpan / max(1, n - 1).toDouble())

        // Verificación de límites de pantalla
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (i in 0 until activeCount) {
            val a = Math.toRadians(centerAngle - halfSpan + i * step)
            val bx = cx + (radius * cos(a)).toFloat()
            val by = cy + (radius * sin(a)).toFloat()
            minX = min(minX, bx - itemSz / 2f)
            minY = min(minY, by - itemSz / 2f)
            maxX = max(maxX, bx + itemSz / 2f)
            maxY = max(maxY, by + itemSz / 2f)
        }

        // Si algún botón sale de pantalla, escalar el radio manteniendo minSafe
        if (minX < pad || minY < pad || maxX > rootW - pad || maxY > rootH - pad) {
            val availableW = rootW - 2 * pad - itemSz
            val availableH = rootH - 2 * pad - itemSz
            val currentW = max(1f, maxX - minX)
            val currentH = max(1f, maxY - minY)
            val scaleRatio = min(availableW / currentW, availableH / currentH)
            radius = max(minSafe, radius * scaleRatio)
        }
        cachedRadius = max(minSafe, min(maxR, radius))

        // 3. Computar array de ángulos finales
        cachedAngles = DoubleArray(activeCount) { i ->
            centerAngle - halfSpan + i * step
        }

        geometryCached = true
    }

    /** Calcula la posición de pantalla [x, y] del ítem de índice dado asegurando límites. */
    fun computeExpandedPosition(index: Int, fabWrapper: View?): FloatArray {
        val rootW = overlayRoot.width.toFloat()
        val rootH = overlayRoot.height.toFloat()

        val cx = if (fabWrapper != null) fabWrapper.x + fabWrapper.width / 2f else 0f
        val cy = if (fabWrapper != null) fabWrapper.y + fabWrapper.height / 2f else 0f

        val angles = getDynamicAngles(fabWrapper)
        val radius = getAdaptiveRadius(fabWrapper)

        val rad = Math.toRadians(angles.getOrElse(index) { 0.0 })
        val itemSz = 48f * density
        val pad = 6f * density

        var x = cx + (radius * cos(rad)).toFloat() - itemSz / 2f
        var y = cy + (radius * sin(rad)).toFloat() - itemSz / 2f

        // Clamp estricto a los límites de pantalla
        if (rootW > 0) x = max(pad, min(rootW - itemSz - pad, x))
        if (rootH > 0) y = max(pad, min(rootH - itemSz - pad, y))

        return floatArrayOf(x, y)
    }

    /** Reposiciona todos los ítems activos en el centro del FAB (estado colapsado). */
    fun syncAllToFabCenter(fabWrapper: View?) {
        if (fabWrapper == null) return
        val cx = fabWrapper.x + fabWrapper.width / 2f
        val cy = fabWrapper.y + fabWrapper.height / 2f
        val itemSz = 48f * density
        for (item in radialItems) {
            if (item == null) continue
            item.x = cx - itemSz / 2f
            item.y = cy - itemSz / 2f
        }
    }

    fun getRadialItems(): Array<View?> = radialItems
}
