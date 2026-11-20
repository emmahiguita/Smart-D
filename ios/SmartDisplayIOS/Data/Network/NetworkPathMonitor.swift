import Foundation
import Network

/// Monitor real de conectividad LAN/Wi-Fi usando `NWPathMonitor` (paridad con Android `NetworkMonitor`).
///
/// Qué hace: observa cambios reales de interfaz (Wi-Fi, Ethernet, Celular o pérdida de red)
/// y notifica cuando la red se recupera para disparar reconexión inmediata sin esperar backoff.
/// Cómo funciona: corre en una cola `utility` dedicada y solo publica cambios reales de estado
/// en `DispatchQueue.main` para evitar reconexiones falsas en handoffs de red.
final class NetworkPathMonitor {

    private let queue = DispatchQueue(label: "com.limelight.smartdisplay.netmonitor", qos: .utility)
    private var monitor: NWPathMonitor?
    private var wasSatisfied = true

    var onNetworkRestored: (() -> Void)?
    var onNetworkStatusChanged: ((Bool) -> Void)?

    /// Comienza a monitorear el estado de la red del dispositivo.
    func start() {
        stop()
        let newMonitor = NWPathMonitor()
        newMonitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let isSatisfied = (path.status == .satisfied)
            let restored = !self.wasSatisfied && isSatisfied
            self.wasSatisfied = isSatisfied

            DispatchQueue.main.async {
                self.onNetworkStatusChanged?(isSatisfied)
                if restored { self.onNetworkRestored?() }
            }
        }
        newMonitor.start(queue: queue)
        monitor = newMonitor
    }

    /// Cancela el monitor y libera los recursos del sistema (cero procesos zombi).
    func stop() {
        monitor?.cancel()
        monitor = nil
    }

    deinit {
        stop()
    }
}
