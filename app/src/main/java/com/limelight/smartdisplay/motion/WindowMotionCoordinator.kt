package com.limelight.smartdisplay.motion

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * WindowMotionCoordinator — Facade & Orchestrator (SOLID Architecture)
 *
 * Punto único de coordinación del subsistema de movimiento de ventanas.
 *
 * Responsabilidades:
 * 1. Conectar la vista overlay [WindowOverlayView] con el animador a 60 FPS [LocalWindowAnimator].
 * 2. Orquestar la reconciliación de coordenadas provenientes del host PC.
 * 3. Gestionar el ciclo de vida de los componentes de animación y gestos.
 * 4. Proveer a la UI y actividades cliente una API limpia y desacoplada de la infraestructura.
 */
class WindowMotionCoordinator(
    private val transport: WindowMotionTransport,
    private val streamView: View,
    private val context: Context,
    pcWidth: Int = 1920,
    pcHeight: Int = 1080
) {
    private val transformer: CoordinateTransformer = ViewCoordinateTransformer(streamView, pcWidth, pcHeight)
    private val overlayView: WindowOverlayView     = WindowOverlayView(context)

    private val animator: LocalWindowAnimator = LocalWindowAnimator { rects ->
        overlayView.update(rects)
    }

    private val dragHandler: WindowDragGestureHandler = WindowDragGestureHandler(
        transport,
        transformer,
        animator
    )

    /**
     * Vincula el overlay de renderizado dentro del contenedor visual del stream.
     */
    fun attachToContainer(container: ViewGroup?) {
        if (container == null) return
        if (overlayView.parent != null) {
            (overlayView.parent as? ViewGroup)?.removeView(overlayView)
        }
        container.addView(
            overlayView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    /**
     * Procesa eventos táctiles de la pantalla.
     * @return true si el gesto de arrastre de ventana capturó el evento.
     */
    fun handleTouchEvent(event: MotionEvent): Boolean {
        return dragHandler.onTouchEvent(
            event,
            streamView.width.toFloat(),
            streamView.height.toFloat()
        )
    }

    /** Alias compatible con convenios de Android onTouchEvent */
    fun onTouchEvent(event: MotionEvent): Boolean = handleTouchEvent(event)

    /**
     * Reconciliación reactiva invocada al recibir ACK `window_moved` del PC host.
     * Convierte las coordenadas del PC a la vista local e informa al animador.
     */
    fun onWindowMoved(hwnd: Int, x: Float, y: Float, w: Float, h: Float) {
        val (localX, localY) = transformer.pcToAndroid(x, y)
        val (localW, localH) = transformer.pcToAndroid(w, h)
        animator.reconcile(hwnd, localX, localY, localW, localH)
    }

    /**
     * Inicia el modo de arrastre interactivo con el dedo para una ventana específica.
     */
    fun startDrag(hwnd: Int, winX: Float, winY: Float, winW: Float, winH: Float) {
        dragHandler.startDrag(hwnd, winX, winY, winW, winH)
    }

    /**
     * Solicita acoplar una ventana a una zona magnética predefinida.
     */
    fun snapWindow(hwnd: Int, zone: String) {
        transport.sendSnapWindow(hwnd, zone)
    }

    /**
     * Actualiza la resolución conocida del PC tras un cambio de configuración o rotación.
     */
    fun updateResolution(width: Int, height: Int) {
        transformer.updatePcResolution(width, height)
    }

    /**
     * Libera los recursos del motor al finalizar la sesión.
     */
    fun release() {
        dragHandler.cancel()
        animator.clear()
        overlayView.overlayActive = false
        if (overlayView.parent != null) {
            (overlayView.parent as? ViewGroup)?.removeView(overlayView)
        }
    }
}
