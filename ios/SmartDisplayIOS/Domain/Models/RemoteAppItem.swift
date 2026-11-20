import Foundation

/// Metadatos reales devueltos por el endpoint `/serverinfo` del PC anfitrión (puerto 47989).
///
/// Qué hace: encapsula identidad, dirección MAC (para Wake-on-LAN automático), estado de
/// emparejamiento y aplicación actualmente en ejecución en el PC.
struct HostServerInfo: Sendable {
    let hostname: String
    let uniqueId: String
    let macAddress: String?
    let httpsPort: Int
    let isPaired: Bool
    let currentAppId: Int
}

/// Aplicación remota publicada por el PC anfitrión (respuesta real de `/applist`).
struct RemoteAppItem: Identifiable, Hashable, Sendable {
    let id: Int
    let title: String
    let hdrSupported: Bool
}

/// Instantánea de telemetría en vivo del PC recibida vía `SmartDisplayBus` (`get_stats`).
struct PcTelemetrySnapshot: Equatable, Sendable {
    var cpuPercent: Int = 0
    var ramPercent: Int = 0
    var gpuPercent: Int = 0
    var hasData: Bool = false
}
