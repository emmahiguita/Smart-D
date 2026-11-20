import Foundation
import Network

/// Contrato de descubrimiento de equipos PC en la red local (Inversión de Dependencias).
protocol HostDiscoveryServing: AnyObject {
    func startDiscovery(onUpdate: @escaping ([HostDevice]) -> Void)
    func stopDiscovery()
}

/// Servicio real de descubrimiento mDNS / Bonjour para Sunshine y Moonlight (`_nvstream._tcp`).
///
/// Qué hace: escucha anuncios Bonjour en la LAN usando Apple `Network.framework` (`NWBrowser`)
/// y resuelve cada servicio a su dirección IP real mediante una conexión efímera.
/// Cómo funciona: opera en una cola serial dedicada (`DispatchQueue`) para no bloquear el hilo UI
/// y entrega siempre la lista actualizada en `DispatchQueue.main`.
/// Por qué: evita dependencias externas y respeta las restricciones de privacidad de iOS 16+.
final class MdnsDiscoveryService: HostDiscoveryServing {

    private let queue = DispatchQueue(label: "com.limelight.smartdisplay.mdns", qos: .utility)
    private var browser: NWBrowser?
    private var discoveredByName: [String: HostDevice] = [:]
    private var activeResolvers: [String: NWConnection] = [:]
    private var onUpdate: (([HostDevice]) -> Void)?

    /// Inicia la exploración Bonjour de servicios `_nvstream._tcp` en la red local.
    func startDiscovery(onUpdate: @escaping ([HostDevice]) -> Void) {
        stopDiscovery()
        self.onUpdate = onUpdate

        let descriptor = NWBrowser.Descriptor.bonjour(type: "_nvstream._tcp", domain: nil)
        let params = NWParameters()
        params.includePeerToPeer = true

        let newBrowser = NWBrowser(for: descriptor, using: params)
        newBrowser.browseResultsChangedHandler = { [weak self] results, _ in
            self?.handleBrowseResults(results)
        }
        newBrowser.start(queue: queue)
        browser = newBrowser
    }

    /// Detiene el explorador y cancela cualquier resolución pendiente (cero procesos zombi).
    func stopDiscovery() {
        browser?.cancel()
        browser = nil
        queue.async { [weak self] in
            guard let self else { return }
            self.activeResolvers.values.forEach { $0.cancel() }
            self.activeResolvers.removeAll()
        }
    }

    /// Procesa el conjunto actual de resultados mDNS y resuelve la IP de cada servicio nuevo.
    private func handleBrowseResults(_ results: Set<NWBrowser.Result>) {
        let currentNames = Set(results.compactMap { result -> String? in
            if case let .service(name, _, _, _) = result.endpoint { return name }
            return nil
        })

        // Elimina equipos que dejaron de anunciarse en la red
        discoveredByName = discoveredByName.filter { currentNames.contains($0.key) }

        for result in results {
            guard case let .service(name, _, _, _) = result.endpoint else { continue }
            if discoveredByName[name] == nil {
                resolveEndpoint(result.endpoint, serviceName: name)
            }
        }
        emitSnapshot()
    }

    /// Resuelve un `NWEndpoint.service` a su dirección IP real y cierra la conexión al instante.
    private func resolveEndpoint(_ endpoint: NWEndpoint, serviceName: String) {
        let conn = NWConnection(to: endpoint, using: .tcp)
        activeResolvers[serviceName] = conn

        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                if let remote = conn.currentPath?.remoteEndpoint,
                   case let .hostPort(host, port) = remote {
                    let rawIp = Self.cleanHostString("\(host)")
                    let device = HostDevice(
                        name: serviceName,
                        address: rawIp,
                        port: Int(port.rawValue),
                        status: .online
                    )
                    self.discoveredByName[serviceName] = device
                    self.emitSnapshot()
                }
                conn.cancel()
                self.activeResolvers.removeValue(forKey: serviceName)
            case .failed, .cancelled:
                self.activeResolvers.removeValue(forKey: serviceName)
            default:
                break
            }
        }
        conn.start(queue: queue)
    }

    /// Elimina sufijos de interfaz IPv6 (ej. `%en0`) para obtener una IP limpia y reutilizable.
    private static func cleanHostString(_ raw: String) -> String {
        raw.split(separator: "%").first.map(String.init) ?? raw
    }

    /// Publica una instantánea ordenada alfabéticamente en el hilo principal de UI.
    private func emitSnapshot() {
        let list = discoveredByName.values.sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
        DispatchQueue.main.async { [weak self] in
            self?.onUpdate?(list)
        }
    }

    deinit {
        stopDiscovery()
    }
}
