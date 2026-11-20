import Foundation

/// Registro en memoria de eventos técnicos en vivo (paridad con `LimeLog` / `dialog_logs` en Android).
///
/// Qué hace: almacena hasta 120 eventos recientes con marca de tiempo para inspección
/// inmediata en `DiagnosticsView` sin consumir almacenamiento ni bloquear la UI.
final class AppLogStore: ObservableObject {

    @Published private(set) var entries: [String] = []
    private let maxEntries = 120
    private let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    /// Registra un evento con hora local de forma segura en el hilo principal.
    func log(_ message: String) {
        let stamped = "[\(formatter.string(from: Date()))] \(message)"
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.entries.append(stamped)
            if self.entries.count > self.maxEntries {
                self.entries.removeFirst(self.entries.count - self.maxEntries)
            }
        }
    }

    /// Limpia el historial en memoria.
    func clear() {
        DispatchQueue.main.async { [weak self] in self?.entries.removeAll() }
    }
}
