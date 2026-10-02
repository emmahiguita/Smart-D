package com.limelight.ui

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * TaskbarEdgeTabHelper — Maneja la pestaña de borde desplegable del Taskbar
 * en pantalla para abrir/cerrar de forma fluida la barra de tareas remota.
 */
object TaskbarEdgeTabHelper {

    @JvmStatic
    fun setup(
        btnTaskbar: View?,
        edgeTab: View?,
        smartTaskbar: SmartTaskbarController?,
        accentColor: Int
    ) {
        if (btnTaskbar == null) return

        val bg = btnTaskbar.background
        if (bg is GradientDrawable) {
            val soft = (accentColor and 0x00FFFFFF) or 0x99000000.toInt()
            bg.mutate()
            (bg as GradientDrawable).setStroke(
                Math.round(btnTaskbar.resources.displayMetrics.density),
                soft
            )
        }

        val density = btnTaskbar.resources.displayMetrics.density
        val hiddenTx = -(54f * density)
        val visibleTx = 0f
        val animDur = 220L
        val autoHideMs = 2500L

        val edgeHideHandler = Handler(Looper.getMainLooper())
        val hideEdgeBtn = Runnable {
            btnTaskbar.animate()
                .translationX(hiddenTx)
                .setDuration(animDur)
                .setInterpolator(AccelerateInterpolator())
                .start()
        }

        val showEdgeBtn = Runnable {
            edgeHideHandler.removeCallbacks(hideEdgeBtn)
            btnTaskbar.animate()
                .translationX(visibleTx)
                .setDuration(animDur)
                .setInterpolator(DecelerateInterpolator())
                .start()
            edgeHideHandler.postDelayed(hideEdgeBtn, autoHideMs)
        }

        edgeTab?.let { tab ->
            tab.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
                    if (abs(btnTaskbar.translationX - visibleTx) > 4) {
                        showEdgeBtn.run()
                    }
                }
                false
            }
            tab.setOnClickListener { showEdgeBtn.run() }
        }

        btnTaskbar.setOnClickListener {
            smartTaskbar?.toggle()
            edgeHideHandler.removeCallbacks(hideEdgeBtn)
            edgeHideHandler.postDelayed(hideEdgeBtn, autoHideMs)
        }
    }
}
