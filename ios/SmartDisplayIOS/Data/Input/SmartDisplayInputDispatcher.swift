import CoreGraphics
import Foundation

/// Despachador de entrada táctil, ratón y teclado hacia el PC anfitrión SmartDisplay.
///
/// Qué hace: traduce coordenadas de toque de iOS a coordenadas normalizadas (`0.0 ... 1.0`)
/// y las envía en tiempo real al Companion Server (`companion_server.py`) por WebSocket.
/// Soporta clics, doble clic, arrastre (down/up), scroll, escritura Unicode e inyección de atajos.
final class SmartDisplayInputDispatcher {

    private let busClient: SmartDisplayBusClient

    init(busClient: SmartDisplayBusClient) {
        self.busClient = busClient
    }

    /// Envía un clic primario (izquierdo) en la posición normalizada `(x, y)` del lienzo.
    func sendTap(at point: CGPoint, in viewportSize: CGSize) {
        guard let norm = normalize(point: point, in: viewportSize) else { return }
        busClient.send(type: "input_click", fields: [
            "x": format(norm.x), "y": format(norm.y), "button": "left"
        ])
    }

    /// Envía un doble clic primario en la posición normalizada `(x, y)`.
    func sendDoubleTap(at point: CGPoint, in viewportSize: CGSize) {
        guard let norm = normalize(point: point, in: viewportSize) else { return }
        busClient.send(type: "input_double_click", fields: [
            "x": format(norm.x), "y": format(norm.y)
        ])
    }

    /// Envía un clic secundario (derecho) en la posición normalizada `(x, y)`.
    func sendRightClick(at point: CGPoint, in viewportSize: CGSize) {
        guard let norm = normalize(point: point, in: viewportSize) else { return }
        busClient.send(type: "input_click", fields: [
            "x": format(norm.x), "y": format(norm.y), "button": "right"
        ])
    }

    /// Presiona el botón del ratón (inicia arrastre o selección).
    func sendMouseDown(at point: CGPoint, in viewportSize: CGSize, button: String = "left") {
        guard let norm = normalize(point: point, in: viewportSize) else { return }
        busClient.send(type: "input_mouse_down", fields: [
            "x": format(norm.x), "y": format(norm.y), "button": button
        ])
    }

    /// Libera el botón del ratón (finaliza arrastre).
    func sendMouseUp(at point: CGPoint, in viewportSize: CGSize, button: String = "left") {
        guard let norm = normalize(point: point, in: viewportSize) else { return }
        busClient.send(type: "input_mouse_up", fields: [
            "x": format(norm.x), "y": format(norm.y), "button": button
        ])
    }

    /// Envía desplazamiento de rueda de ratón (scroll vertical hacia arriba o abajo).
    func sendScroll(deltaY: Int) {
        busClient.send(type: "input_scroll", fields: ["delta_y": "\(deltaY)"])
    }

    /// Envía movimiento continuo del cursor mientras el usuario arrastra.
    func sendPointerMove(to point: CGPoint, in viewportSize: CGSize) {
        guard let norm = normalize(point: point, in: viewportSize) else { return }
        busClient.send(type: "input_move", fields: [
            "x": format(norm.x), "y": format(norm.y)
        ])
    }

    /// Envía un solo carácter Unicode directamente (escritura interactiva tecla a tecla).
    func sendChar(_ char: String) {
        guard !char.isEmpty else { return }
        busClient.send(type: "input_char", fields: ["char": char])
    }

    /// Envía texto completo sincronizando el portapapeles del PC y pegando en el foco activo.
    func sendText(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .newlines)
        guard !trimmed.isEmpty else { return }
        busClient.send(type: "clipboard", fields: ["text": trimmed])
        busClient.send(type: "input_text", fields: ["text": trimmed])
    }

    /// Envía una tecla especial (`enter`, `backspace`, `escape`, `tab`, `win`, `space`, etc.).
    func sendSpecialKey(_ key: String) {
        busClient.send(type: "input_key", fields: ["key": key])
    }

    func startDesktopStream() { busClient.send(type: "start_stream") }
    func stopDesktopStream() { busClient.send(type: "stop_stream") }
    func requestSystemStats() { busClient.send(type: "get_stats") }
    func restorePcAudio() { busClient.send(type: "restore_audio") }

    /// Normaliza un punto táctil compensando el letterbox/pillarbox 16:9 para precisión 1:1 con Windows.
    private func normalize(point: CGPoint, in size: CGSize) -> CGPoint? {
        guard size.width > 0, size.height > 0 else { return nil }
        let aspect: CGFloat = 16.0 / 9.0
        let viewportAspect = size.width / size.height
        var drawW = size.width, drawH = size.height
        var originX: CGFloat = 0, originY: CGFloat = 0

        if viewportAspect > aspect {
            drawW = size.height * aspect
            originX = (size.width - drawW) / 2.0
        } else {
            drawH = size.width / aspect
            originY = (size.height - drawH) / 2.0
        }

        let clampedX = min(max(point.x, originX), originX + drawW)
        let clampedY = min(max(point.y, originY), originY + drawH)
        let nx = drawW > 0 ? (clampedX - originX) / drawW : 0.5
        let ny = drawH > 0 ? (clampedY - originY) / drawH : 0.5
        return CGPoint(x: min(max(nx, 0.0), 1.0), y: min(max(ny, 0.0), 1.0))
    }

    private func format(_ value: CGFloat) -> String {
        String(format: "%.4f", Double(value))
    }
}
