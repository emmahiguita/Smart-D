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

    /// Solicita telemetría en vivo (CPU / RAM / GPU) al Companion Server (`companion_server.py`).
    func requestSystemStats() {
        busClient.send(type: "get_stats")
    }

    /// Restaura el dispositivo de audio del PC anfitrión (`restore_audio`).
    func restorePcAudio() {
        busClient.send(type: "restore_audio")
    }

    /// Normaliza un punto en píxeles de pantalla al rango seguro `[0.0, 1.0]`.
    private func normalize(point: CGPoint, in size: CGSize) -> CGPoint? {
        guard size.width > 0, size.height > 0 else { return nil }
        let nx = min(max(point.x / size.width, 0.0), 1.0)
        let ny = min(max(point.y / size.height, 0.0), 1.0)
        return CGPoint(x: nx, y: ny)
    }

    private func format(_ value: CGFloat) -> String {
        String(format: "%.4f", Double(value))
    }
}
