package com.limelight.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.PathInterpolator
import kotlin.math.hypot

/**
 * OverlayAnimationController — Responsabilidad Única: gestión de todas las animaciones del menú radial.
 *
 * Controla:
 *  - Expansión (cascade) e implosión (reverse cascade) de los ítems radiales.
 *  - Circular reveal / hide del scrim.
 *  - Pulso del halo (fabHaloPulse).
 *  - Anillo orbital (fabOrbitRing).
 */
class OverlayAnimationController(
    private val overlayRoot: View,
    private val fabMainWrapper: View?,
    private val fabScrim: View?,
    private val fabHaloPulse: View?,
    private val fabHaloGlow: View?,
    private val fabOrbitRing: View?,
    var effectsEnabled: Boolean = true
) {
    companion object {
        private const val DUR_EXPAND_ITEM = 250
        private const val DUR_COLLAPSE_ITEM = 180
        private const val STAGGER_MS = 25
        private const val DUR_HALO_PULSE = 1400
        private const val HALO_PULSE_GAP = 500L
        private const val DUR_SCRIM_SHOW = 350L
        private const val DUR_SCRIM_HIDE = 220L

        private val EASING_EMPHASIZED = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)
        private val EASING_EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
        private val EASING_EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0.0f, 0.8f, 0.15f)
    }

    var radialRingView: RadialRingView? = null
    var performanceMode: Boolean = false
    var onCollapseAnimationFinished: Runnable? = null

    private var pulsing = false
    private val pulseRunnable = Runnable { runPulseCycle() }
    private val glowRunnable = Runnable { runGlowFlash() }

    fun setOnCollapseFinished(r: Runnable?) {
        this.onCollapseAnimationFinished = r
    }

    // ── Expansión del menú ────────────────────────────────────────────────────

    /** Anima el anillo orbital al abrirse, expandiéndolo al radio real de los ítems. */
    fun animateOrbitRingExpand(adaptiveRadius: Float, density: Float) {
        val ring = fabOrbitRing ?: return
        val ringBaseDp = 116f
        val targetScale = (adaptiveRadius * 2f + 12f * density) / (ringBaseDp * density)

        if (effectsEnabled) {
            ring.animate()
                .scaleX(targetScale).scaleY(targetScale)
                .alpha(0.22f)
                .setDuration(250)
                .setInterpolator(EASING_EMPHASIZED)
                .start()
        } else {
            ring.scaleX = targetScale
            ring.scaleY = targetScale
            ring.alpha = 0.22f
        }
    }

    /**
     * Hace aparecer los ítems activos del menú con un cascade escalonado.
     */
    fun animateExpansion(
        radialItems: Array<View?>,
        activeCount: Int,
        targetPositions: Array<FloatArray>,
        angles: DoubleArray
    ) {
        if (radialRingView != null && fabMainWrapper != null && activeCount > 0 && targetPositions.isNotEmpty()) {
            val cx = fabMainWrapper.x + fabMainWrapper.width / 2f
            val cy = fabMainWrapper.y + fabMainWrapper.height / 2f
            val density = overlayRoot.context.resources.displayMetrics.density
            val itemHalf = 24f * density
            val dx = (targetPositions[0][0] + itemHalf) - cx
            val dy = (targetPositions[0][1] + itemHalf) - cy
            val radius = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            radialRingView?.update(cx, cy, radius, angles, true)
        }

        for (i in 0 until activeCount) {
            val item = radialItems.getOrNull(i) ?: continue
            val pos = targetPositions[i]
            val delay = (i * STAGGER_MS).toLong()

            if (effectsEnabled) {
                item.alpha = 0f
                item.scaleX = 0.7f
                item.scaleY = 0.7f
                item.visibility = View.VISIBLE

                item.postDelayed({
                    item.animate()
                        .x(pos[0]).y(pos[1])
                        .alpha(1f)
                        .scaleX(1f).scaleY(1f)
                        .setDuration(DUR_EXPAND_ITEM.toLong())
                        .setInterpolator(EASING_EMPHASIZED)
                        .setListener(null)
                        .start()
                }, delay)
            } else {
                item.x = pos[0]
                item.y = pos[1]
                item.alpha = 1f
                item.scaleX = 1f
                item.scaleY = 1f
                item.visibility = View.VISIBLE
            }
        }
    }

    // ── Colapso del menú ─────────────────────────────────────────────────────

    /** Retrae el anillo orbital a su tamaño original. */
    fun animateOrbitRingCollapse() {
        val ring = fabOrbitRing ?: return
        if (effectsEnabled) {
            ring.animate()
                .scaleX(1.0f).scaleY(1.0f)
                .alpha(0.35f)
                .setDuration(220)
                .setInterpolator(EASING_EMPHASIZED_ACCELERATE)
                .start()
        } else {
            ring.scaleX = 1.0f
            ring.scaleY = 1.0f
            ring.alpha = 0.35f
        }
    }

    /**
     * Anima el colapso (reverse cascade) de los ítems radiales al centro del FAB.
     */
    fun animateCollapse(radialItems: Array<View?>, fabCenterX: Float, fabCenterY: Float) {
        val totalItems = radialItems.size
        for (i in totalItems - 1 downTo 0) {
            val item = radialItems.getOrNull(i) ?: continue
            val cx = fabCenterX - 36f
            val cy = fabCenterY - 36f
            val delay = ((totalItems - 1 - i) * STAGGER_MS).toLong()

            if (effectsEnabled) {
                item.animate().cancel()
                item.postDelayed({
                    item.animate().cancel()
                    item.animate()
                        .x(cx).y(cy)
                        .alpha(0f)
                        .scaleX(0.2f).scaleY(0.2f)
                        .setDuration(DUR_COLLAPSE_ITEM.toLong())
                        .setInterpolator(EASING_EMPHASIZED_ACCELERATE)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                item.visibility = View.INVISIBLE
                                item.animate().setListener(null)
                            }
                        })
                        .start()
                }, delay)
            } else {
                item.x = cx
                item.y = cy
                item.visibility = View.INVISIBLE
            }
        }

        radialRingView?.update(0f, 0f, 0f, null, false)

        if (effectsEnabled && onCollapseAnimationFinished != null && fabScrim != null) {
            val haloDelay = DUR_SCRIM_HIDE + (totalItems * STAGGER_MS).toLong() + 80L
            fabScrim.postDelayed(onCollapseAnimationFinished, haloDelay)
        }
    }

    /** Colapso instantáneo sin animaciones (usado durante drag). */
    fun collapseInstant(radialItems: Array<View?>) {
        pulsing = false
        radialRingView?.update(0f, 0f, 0f, null, false)
        fabHaloPulse?.apply {
            animate().cancel()
            visibility = View.INVISIBLE
        }
        fabOrbitRing?.apply {
            animate().cancel()
            scaleX = 1.0f
            scaleY = 1.0f
            alpha = 0.25f
        }
        for (item in radialItems) {
            if (item != null) {
                item.animate().cancel()
                item.alpha = 0f
                item.scaleX = 1f
                item.scaleY = 1f
                item.visibility = View.INVISIBLE
            }
        }
    }

    // ── Scrim ─────────────────────────────────────────────────────────────────

    fun showScrim(fabCenterX: Int, fabCenterY: Int) {
        val scrim = fabScrim ?: return
        if (effectsEnabled) {
            val maxRadius = hypot(overlayRoot.width.toDouble(), overlayRoot.height.toDouble()).toFloat()
            scrim.visibility = View.VISIBLE
            val reveal = ViewAnimationUtils.createCircularReveal(scrim, fabCenterX, fabCenterY, 0f, maxRadius)
            reveal.duration = DUR_SCRIM_SHOW
            reveal.interpolator = EASING_EMPHASIZED_DECELERATE
            reveal.start()
        } else {
            scrim.visibility = View.VISIBLE
            scrim.alpha = 1f
        }
    }

    fun hideScrim(fabCenterX: Int, fabCenterY: Int) {
        val scrim = fabScrim ?: return
        if (scrim.visibility != View.VISIBLE) return
        if (effectsEnabled) {
            val maxRadius = hypot(overlayRoot.width.toDouble(), overlayRoot.height.toDouble()).toFloat()
            val reveal = ViewAnimationUtils.createCircularReveal(scrim, fabCenterX, fabCenterY, maxRadius, 0f)
            reveal.duration = DUR_SCRIM_HIDE
            reveal.interpolator = EASING_EMPHASIZED_ACCELERATE
            reveal.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scrim.visibility = View.GONE
                }
            })
            reveal.start()
        } else {
            scrim.visibility = View.GONE
        }
    }

    // ── Pulso del Halo ────────────────────────────────────────────────────────

    fun startHaloPulse() {
        if (!effectsEnabled || fabHaloPulse == null || performanceMode) return
        pulsing = true
        fabHaloPulse.visibility = View.VISIBLE
        runPulseCycle()
    }

    fun stopHaloPulse() {
        pulsing = false
        fabHaloPulse?.apply {
            animate().cancel()
            visibility = View.INVISIBLE
        }
        fabHaloGlow?.apply {
            animate().cancel()
            alpha = 0.4f
        }
        fabScrim?.removeCallbacks(pulseRunnable)
        fabScrim?.removeCallbacks(glowRunnable)
    }

    private fun runPulseCycle() {
        if (!effectsEnabled || !pulsing || fabHaloPulse == null || performanceMode) return
        val halo = fabHaloPulse
        halo.scaleX = 1f
        halo.scaleY = 1f
        halo.alpha = 0.7f
        halo.animate()
            .scaleX(1.8f).scaleY(1.8f).alpha(0f)
            .rotationBy(90f)
            .setDuration(DUR_HALO_PULSE.toLong())
            .setInterpolator(EASING_EMPHASIZED_DECELERATE)
            .withEndAction {
                if (effectsEnabled && pulsing && fabHaloPulse != null && fabScrim != null) {
                    fabScrim.postDelayed(pulseRunnable, HALO_PULSE_GAP)
                }
            }
            .start()

        halo.postDelayed(glowRunnable, (DUR_HALO_PULSE / 2).toLong())
    }

    private fun runGlowFlash() {
        if (!effectsEnabled || !pulsing || fabHaloGlow == null) return
        fabHaloGlow.animate()
            .alpha(0.7f).setDuration(120)
            .withEndAction {
                fabHaloGlow.animate().alpha(0.4f).setDuration(350).start()
            }
            .start()
    }

    // ── Ciclo de vida ────────────────────────────────────────────────────────

    fun release() {
        pulsing = false
        fabHaloPulse?.apply {
            animate().cancel()
            visibility = View.INVISIBLE
        }
        fabOrbitRing?.animate()?.cancel()
        fabHaloGlow?.animate()?.cancel()
        fabScrim?.apply {
            animate().cancel()
            removeCallbacks(pulseRunnable)
            removeCallbacks(glowRunnable)
        }
    }
}
