import Foundation

/// Estado del ciclo de vida de un equipo anfitrión (PC) descubierto o registrado.
///
/// Qué hace: representa si el PC está disponible, desconectado o en proceso de conexión.
/// Por qué: evita usar booleanos sueltos que pueden generar estados contradictorios.
enum HostStatus: String, Codable, Sendable {
    case online
    case offline
    case connecting
    case unknown
}

/// Entidad de dominio que representa un PC anfitrión de SmartDisplay / Sunshine.
///
/// Qué hace: almacena identidad, dirección de red, puerto y dirección MAC para Wake-on-LAN.
/// Cómo funciona: conforma `Identifiable`, `Codable` y `Sendable` para ser seguro entre hilos
/// y persistible directamente en `UserDefaults` o almacenamiento local.
struct HostDevice: Identifiable, Codable, Hashable, Sendable {
    let id: UUID
    var name: String
    var address: String
    var port: Int
    var macAddress: String?
    var companionPort: Int
    var status: HostStatus

    init(
        id: UUID = UUID(),
        name: String,
        address: String,
        port: Int = 47989,
        macAddress: String? = nil,
        companionPort: Int = 3001,
        status: HostStatus = .unknown
    ) {
        self.id = id
        self.name = name
        self.address = address
        self.port = port
        self.macAddress = macAddress
        self.companionPort = companionPort
        self.status = status
    }
}

/// Estado inmutable de transformación del lienzo de escritorio remoto (Zoom + Pan).
///
/// Qué hace: guarda escala actual, desplazamiento X/Y y si el modo "Mover pantalla" está activo.
/// Por qué: permite renderizar y animar la vista de streaming de forma determinista.
struct ViewportState: Equatable, Sendable {
    var scale: CGFloat = 1.0
    var offsetX: CGFloat = 0.0
    var offsetY: CGFloat = 0.0
    var isMoveModeEnabled: Bool = false

    /// Porcentaje entero listo para mostrar en el indicador de zoom (ej. "150%").
    var zoomPercent: Int {
        Int((scale * 100.0).rounded())
    }
}

/// Archivo compartido o recibido mediante el servidor Wi-Fi local.
///
/// Qué hace: encapsula metadatos de archivo para mostrar en la lista de transferencia.
struct TransferFileItem: Identifiable, Hashable, Sendable {
    let id: UUID
    let name: String
    let url: URL
    let byteCount: Int64
    let modifiedAt: Date

    init(
        id: UUID = UUID(),
        name: String,
        url: URL,
        byteCount: Int64,
        modifiedAt: Date = Date()
    ) {
        self.id = id
        self.name = name
        self.url = url
        self.byteCount = byteCount
        self.modifiedAt = modifiedAt
    }

    /// Formatea el tamaño en bytes a formato legible (KB, MB, GB).
    var formattedSize: String {
        ByteCountFormatter.string(fromByteCount: byteCount, countStyle: .file)
    }
}
