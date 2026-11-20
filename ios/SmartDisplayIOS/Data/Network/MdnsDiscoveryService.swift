import Foundation
import Network

/// Contrato de descubrimiento de equipos PC en la red local (Inversión de Dependencias).
protocol HostDiscoveryServing: AnyObject {
    func startDiscovery(onUpdate: @escaping ([HostDevice]) -> Void)
    func stopDiscovery()
}

/// Servicio real de descubrimiento híbrido: Bonjour (`_nvstream._tcp`) + Escáner TCP directo (`47991`/`47989`).
///
/// Qué hace: escucha anuncios Bonjour en la LAN y, en paralelo, sondea de forma asíncrona
/// las subredes activas del iPhone (Hotspot USB/Wi-Fi `172.20.10.x`, LAN `192.168.0.x`, Tailscale
/// y la subred `/24` de `en0`) en el puerto `47991` (SmartDisplay Bus) y `47989` (Sunshine).
/// Por qué: garantiza que el PC anfitrión aparezca automáticamente en < 1 segundo incluso cuando
/// Windows o el Hotspot personal bloquean paquetes multicast mDNS.
final class MdnsDiscoveryService: HostDiscoveryServing {

    private let queue = DispatchQueue(label: "com.limelight.smartdisplay.mdns", qos: .utility)
    private var browser: NWBrowser?
    private var discoveredByName: [String: HostDevice] = [:]
    private var activeResolvers: [String: NWConnection] = [:]
    private var onUpdate: (([HostDevice]) -> Void)?

    /// Inicia la exploración Bonjour + el escaneo directo de puertos SmartDisplay (`47991`/`47989`).
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

        queue.async { [weak self] in
            self?.runDirectHostScan()
        }
    }

    /// Detiene el explorador y cancela cualquier sonda pendiente (cero procesos zombi).
    func stopDiscovery() {
        browser?.cancel()
        browser = nil
        queue.async { [weak self] in
            guard let self else { return }
            self.activeResolvers.values.forEach { $0.cancel() }
            self.activeResolvers.removeAll()
        }
    }

    /// Sondea en paralelo candidatos prioritarios y la subred local en los puertos 47991 y 47989.
    private func runDirectHostScan() {
        var candidates: [String] = [
            "172.20.10.3", "172.20.10.2", "172.20.10.4",
            "192.168.0.2", "192.168.0.10", "192.168.1.2",
            "100.66.176.36"
        ]
        if let localIp = WifiFileTransferServer.localWifiIPv4Address() {
            let parts = localIp.split(separator: ".")
            if parts.count == 4 {
                let prefix = "\(parts[0]).\(parts[1]).\(parts[2])"
                for hostNum in 1...25 {
                    let ip = "\(prefix).\(hostNum)"
                    if ip != localIp && !candidates.contains(ip) {
                        candidates.append(ip)
                    }
                }
            }
        }

        for ip in candidates {
            probeDirectHost(ip: ip, port: 47991, label: "SmartDisplay PC")
            probeDirectHost(ip: ip, port: 47989, label: "SmartDisplay Host")
        }
    }

    /// Abre una sonda TCP de 1.5s hacia `ip:port`; si responde, registra el PC como `.online`.
    private func probeDirectHost(ip: String, port: UInt16, label: String) {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return }
        let key = "\(ip):\(port)"
        let conn = NWConnection(host: NWEndpoint.Host(ip), port: nwPort, using: .tcp)
        activeResolvers[key] = conn

        var completed = false
        let finish: (Bool) -> Void = { [weak self] ok in
            guard let self, !completed else { return }
            completed = true
            conn.cancel()
            self.activeResolvers.removeValue(forKey: key)
            if ok {
                let hostKey = "host-\(ip)"
                if self.discoveredByName[hostKey] == nil {
                    self.discoveredByName[hostKey] = HostDevice(
                        name: "\(label) (\(ip))",
                        address: ip,
                        port: 47989,
                        companionPort: 47991,
                        status: .online
                    )
                    self.emitSnapshot()
                }
            }
        }

        conn.stateUpdateHandler = { state in
            if case .ready = state { finish(true) }
            if case .failed = state { finish(false) }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 1.5) { finish(false) }
    }

    private func handleBrowseResults(_ results: Set<NWBrowser.Result>) {
        for result in results {
            guard case let .service(name, _, _, _) = result.endpoint else { continue }
            if discoveredByName[name] == nil {
                resolveEndpoint(result.endpoint, serviceName: name)
            }
        }
        emitSnapshot()
    }

    private func resolveEndpoint(_ endpoint: NWEndpoint, serviceName: String) {
        let conn = NWConnection(to: endpoint, using: .tcp)
        activeResolvers[serviceName] = conn
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            if case .ready = state {
                if let remote = conn.currentPath?.remoteEndpoint,
                   case let .hostPort(host, port) = remote {
                    let rawIp = "\(host)".split(separator: "%").first.map(String.init) ?? "\(host)"
                    self.discoveredByName["host-\(rawIp)"] = HostDevice(
                        name: serviceName,
                        address: rawIp,
                        port: Int(port.rawValue),
                        companionPort: 47991,
                        status: .online
                    )
                    self.emitSnapshot()
                }
                conn.cancel()
                self.activeResolvers.removeValue(forKey: serviceName)
            } else if case .failed = state {
                self.activeResolvers.removeValue(forKey: serviceName)
            }
        }
        conn.start(queue: queue)
    }

    private func emitSnapshot() {
        let list = discoveredByName.values.sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
        DispatchQueue.main.async { [weak self] in self?.onUpdate?(list) }
    }

    deinit { stopDiscovery() }
}
