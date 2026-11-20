import CoreGraphics
import Foundation

/// Despachador real de entrada táctil, puntero y teclado hacia el PC anfitrión SmartDisplay.
///
/// Qué hace: traduce coordenadas de toque de la pantalla iOS a coordenadas normalizadas
/// (`0.0 ... 1.0`) del escritorio remoto y las envía en tiempo real por `SmartDisplayBusClient`.
/// Cómo funciona: desacopla la geometría táctil de la vista (`StreamSessionView`) y permite
/// enviar clics, arrastres de ratón, texto UTF-8 y atajos de sistema sin acoplamiento circular.
/// Por qué: cumple SRP y permite inyectar comandos reales sin simulaciones.
final class SmartDisplayInputDispatcher {

    private let busClient: SmartDisplayBusClient

    init(busClient: SmartDisplayBusClient) {
        self.busClient = busClient
    }

    /// Envía un clic primario en la posición normalizada `(x, y)` del lienzo.
    func sendTap(at point: CGPoint, in viewportSize: CGSize) {
        guard let norm = normalize(point: point, in: viewportSize) else { return }
        busClient.send(type: "input_click", fields: [
            "x": format(norm.x),
            "y": format(norm.y),
            "button": "left"
        ])
    }

    /// Envía un clic secundario (clic derecho) en la posición normalizada `(x, y)`.
    func sendRightClick(at point: CGPoint, in viewportSize: CGSize) {
        guard let norm = normalize(point: point, in: viewportSize) else { return }
        busClient.send(type: "input_click", fields: [
            "x": format(norm.x),
            "y": format(norm.y),
            "button": "right"
        ])
    }

    /// Envía desplazamiento de rueda de ratón (scroll vertical hacia arriba o abajo).
    func sendScroll(deltaY: Int) {
        busClient.send(type: "input_scroll", fields: [
            "delta_y": "\(deltaY)"
        ])
    }

    /// Envía movimiento continuo del puntero mientras el usuario arrastra (modo ratón).
    func sendPointerMove(to point: CGPoint, in viewportSize: CGSize) {
        guard let norm = normalize(point: point, in: viewportSize) else { return }
        busClient.send(type: "input_move", fields: [
            "x": format(norm.x),
            "y": format(norm.y)
        ])
    }

    /// Envía texto UTF-8 (desde una palabra hasta párrafos) sincronizando también el portapapeles del PC.
    func sendText(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .newlines)
        guard !trimmed.isEmpty else { return }
        busClient.send(type: "clipboard", fields: ["text": trimmed])
        busClient.send(type: "input_text", fields: ["text": trimmed])
    }

    /// Envía una tecla especial de control (`enter`, `backspace`, `escape`, `tab`).
    func sendSpecialKey(_ key: String) {
        busClient.send(type: "input_key", fields: ["key": key])
    }

    /// Solicita iniciar el flujo de escritorio en vivo por el Bus 47991 (`start_stream`).
    func startDesktopStream() {
        busClient.send(type: "start_stream")
    }

    /// Solicita detener el flujo de escritorio al cerrar la sesión (`stop_stream`).
    func stopDesktopStream() {
        busClient.send(type: "stop_stream")
    }

    /// Solicita telemetría en vivo (CPU / RAM / GPU) al Companion Server (`companion_server.py`).
    func requestSystemStats() {
        busClient.send(type: "get_stats")
    }

    /// Restaura el dispositivo de audio del PC anfitrión (`restore_audio`).
    func restorePcAudio() {
        busClient.send(type: "restore_audio")
    }

    /// Normaliza un punto táctil compensando el letterbox/pillarbox 16:9 para precisión 1:1 con Windows.
    private func normalize(point: CGPoint, in size: CGSize) -> CGPoint? {
        guard size.width > 0, size.height > 0 else { return nil }
        let aspect: CGFloat = 16.0 / 9.0
        let viewportAspect = size.width / size.height
        var drawW = size.width
        var drawH = size.height
        var originX: CGFloat = 0
        var originY: CGFloat = 0

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
