import Foundation

/// Contrato de persistencia de equipos anfitriones SmartDisplay (Principio DIP - SOLID).
protocol HostRepositoryProtocol {
    func loadSavedHosts() -> [HostDevice]
    func saveHost(_ host: HostDevice) -> [HostDevice]
    func removeHost(id: UUID) -> [HostDevice]
}

/// Repositorio real de equipos guardados sobre `UserDefaults` en formato JSON.
///
/// Qué hace: guarda, carga y elimina los PCs registrados por el usuario para que
/// persistan al cerrar y volver a abrir la app SmartDisplay iOS.
/// Cómo funciona: codifica `[HostDevice]` con `JSONEncoder`/`JSONDecoder` evitando
/// duplicados por dirección IP y preservando el orden de registro.
/// Por qué: separa el almacenamiento de la UI (`HostListView`) cumpliendo SRP.
final class HostRepository: HostRepositoryProtocol {

    private let defaults: UserDefaults
    private let storageKey = "smartdisplay.saved_hosts.v1"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Lee la lista de equipos persistidos; inicializa con la PC local si es la primera apertura.
    func loadSavedHosts() -> [HostDevice] {
        if let data = defaults.data(forKey: storageKey),
           let hosts = try? JSONDecoder().decode([HostDevice].self, from: data),
           !hosts.isEmpty {
            return hosts
        }
        let defaultsHosts: [HostDevice] = [
            HostDevice(name: "PC Principal (Wi-Fi)", address: "192.168.0.2", port: 47989, companionPort: 47991, status: .offline),
            HostDevice(name: "PC Cable USB (Hotspot)", address: "172.20.10.3", port: 47989, companionPort: 47991, status: .offline)
        ]
        persist(defaultsHosts)
        return defaultsHosts
    }

    /// Inserta o actualiza un equipo (por `id` o `address`) y persiste la lista actualizada.
    func saveHost(_ host: HostDevice) -> [HostDevice] {
        var current = loadSavedHosts()
        if let idx = current.firstIndex(where: { $0.id == host.id || $0.address == host.address }) {
            current[idx] = host
        } else {
            current.append(host)
        }
        persist(current)
        return current
    }

    /// Elimina un equipo por su identificador único y persiste el cambio.
    func removeHost(id: UUID) -> [HostDevice] {
        var current = loadSavedHosts()
        current.removeAll { $0.id == id }
        persist(current)
        return current
    }

    /// Serializa el arreglo de hosts a JSON en `UserDefaults`.
    private func persist(_ hosts: [HostDevice]) {
        guard let data = try? JSONEncoder().encode(hosts) else { return }
        defaults.set(data, forKey: storageKey)
    }
}
