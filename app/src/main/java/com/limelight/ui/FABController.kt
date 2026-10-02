package com.limelight.ui

import android.content.Context
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.Toast
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.limelight.R

/**
 * FABController — Responsabilidad Única: gestión visual del FAB principal.
 *
 * Controla:
 *  - Icono, colores del tema y estado visual del FAB (reposado, expandido, inactivo).
 *  - Efecto de encogimiento/despertar automático por inactividad.
 *  - Toggle de efectos visuales.
 */
class FABController(
    private val fabMain: FloatingActionButton?,
    private val fabMainWrapperView: View?
) {
    companion object {
        private const val PREF_NAME = "fab_overlay_v5"
        private const val DUR_FAB_ROTATE = 250L

        private val EASING_EMPHASIZED = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)
        private val EASING_EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
        private val EASING_EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0.0f, 0.8f, 0.15f)
    }

    private val context: Context? = fabMain?.context
    private val density: Float = context?.resources?.displayMetrics?.density ?: 1f

    var isEffectsEnabled: Boolean = context?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        ?.getBoolean("fab_effects_enabled", true) ?: true
        private set

    private var shrunk: Boolean = false
    private val shrinkRunnable = Runnable { shrinkFab() }

    // ── Estado visual ─────────────────────────────────────────────────────────

    /** Transición animada hacia el estado Expandido: icono expandido sin rotación de pulpo. */
    fun transitionToExpanded() {
        val fab = fabMain ?: return
        if (isEffectsEnabled) {
            fab.animate()
                .scaleX(0.88f).scaleY(0.88f).alpha(0.75f)
                .setDuration(120)
                .setInterpolator(EASING_EMPHASIZED_ACCELERATE)
                .withEndAction {
                    fab.setImageResource(R.drawable.ic_close)
                    fab.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .rotation(0f)
                        .setDuration(DUR_FAB_ROTATE - 120)
                        .setInterpolator(EASING_EMPHASIZED)
                        .start()
                }.start()
        } else {
            fab.setImageResource(R.drawable.ic_close)
            fab.rotation = 0f
        }
    }

    /** Transición animada hacia el estado Cerrado: icono selector normal (ic_menu) sin rotación. */
    fun transitionToCollapsed() {
        val fab = fabMain ?: return
        if (isEffectsEnabled) {
            fab.animate()
                .scaleX(0.88f).scaleY(0.88f).alpha(0.75f)
                .setDuration(100)
                .setInterpolator(EASING_EMPHASIZED_ACCELERATE)
                .withEndAction {
                    fab.setImageResource(R.drawable.ic_menu)
                    fab.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .rotation(0f)
                        .setDuration(DUR_FAB_ROTATE - 100)
                        .setInterpolator(EASING_EMPHASIZED_ACCELERATE)
                        .start()
                }.start()
        } else {
            fab.setImageResource(R.drawable.ic_menu)
            fab.rotation = 0f
        }
    }

    /** Pone el icono de flecha de retorno (cuando el sub-menú Herramientas está activo). */
    fun showBackArrow() {
        val fab = fabMain ?: return
        fab.setImageResource(R.drawable.ic_back_arrow)
        fab.rotation = 0f
    }

    /** Restablece el estado visual del FAB a su estado de reposo. */
    fun resetInstant() {
        val fab = fabMain ?: return
        fab.setImageResource(R.drawable.ic_menu)
        fab.rotation = 0f
        fab.scaleX = 1f
        fab.scaleY = 1f
        fab.alpha = 1f
    }

    // ── Efectos (toggle) ─────────────────────────────────────────────────────

    fun toggleEffects() {
        isEffectsEnabled = !isEffectsEnabled
        context?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean("fab_effects_enabled", isEffectsEnabled)
            ?.apply()

        if (context != null) {
            Toast.makeText(
                context,
                if (isEffectsEnabled) "Efectos FAB: ACTIVADOS" else "Efectos FAB: DESACTIVADOS",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── Encogimiento por inactividad ─────────────────────────────────────────

    fun scheduleShrink() {
        // Desactivado: el FAB no se encoge por inactividad
    }

    fun wake() {
        val wrapper = fabMainWrapperView ?: return
        wrapper.removeCallbacks(shrinkRunnable)
        if (shrunk) {
            shrunk = false
            wrapper.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(200)
                .setInterpolator(EASING_EMPHASIZED_DECELERATE)
                .start()
        }
    }

    private fun shrinkFab() {
        // Desactivado: mantener escala y opacidad al 100%
    }

    // ── Ciclo de vida ────────────────────────────────────────────────────────

    fun release() {
        fabMainWrapperView?.animate()?.cancel()
        fabMainWrapperView?.removeCallbacks(shrinkRunnable)
        fabMain?.animate()?.cancel()
    }
}
