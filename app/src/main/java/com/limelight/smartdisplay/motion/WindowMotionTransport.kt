package com.limelight.smartdisplay.motion

/**
 * WindowMotionTransport — Interface Segregation & Dependency Inversion Principle (SOLID)
 *
 * Contrato de comunicación que abstrae el canal de transporte (WebSocket, IPC, etc.)
 * entre el cliente Android y el host PC. Desacopla la lógica de movimiento de ventanas
 * de la implementación concreta del bus de red.
 */
interface WindowMotionTransport {
    fun sendMoveWindow(hwnd: Int, x: Int, y: Int, w: Int, h: Int, mode: String = "snap"): Boolean
    fun sendWindowDragEnd(hwnd: Int): Boolean
    fun sendSnapWindow(hwnd: Int, zone: String): Boolean
    fun sendSwapWindows(windows: List<WindowRectDto>): Boolean
    fun sendCancelWindowMotion(hwnd: Int): Boolean
    fun sendWindowAtPoint(pcX: Int, pcY: Int): Boolean
}

/** DTO inmutable para intercambiar rectángulos de ventanas sin dependencias de plataforma. */
data class WindowRectDto(val hwnd: Int, val x: Int, val y: Int, val w: Int, val h: Int)
