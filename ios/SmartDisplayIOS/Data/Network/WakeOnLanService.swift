import Foundation
import Network

/// Servicio real de encendido remoto Wake-on-LAN (Magic Packet UDP puerto 9) y sonda TCP.
///
/// Qué hace: construye un paquete mágico estándar (6 bytes `0xFF` seguidos de 16 repeticiones
/// de la dirección MAC de 6 bytes = 102 bytes) y lo envía por UDP al PC destino o broadcast.
/// Cómo funciona: utiliza `NWConnection` de `Network.framework` en una cola de fondo y libera
/// inmediatamente el descriptor al completar o vencer el timeout.
/// Por qué: permite despertar un PC suspendido antes de iniciar la sesión de streaming.
final class WakeOnLanService {

    private let queue = DispatchQueue(label: "com.limelight.smartdisplay.wol", qos: .utility)

    /// Construye el Magic Packet de 102 bytes a partir de una MAC (`AA:BB:CC:DD:EE:FF`).
    /// Retorna `nil` si el formato de la dirección MAC es inválido.
    func buildMagicPacket(macAddress: String) -> Data? {
        let cleaned = macAddress
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: "-", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        guard cleaned.count == 12 else { return nil }

        var macBytes: [UInt8] = []
        macBytes.reserveCapacity(6)
        var index = cleaned.startIndex
        for _ in 0..<6 {
            let next = cleaned.index(index, offsetBy: 2)
            guard let byte = UInt8(cleaned[index..<next], radix: 16) else { return nil }
            macBytes.append(byte)
            index = next
        }

        // Cabecera estándar WoL: 6 bytes 0xFF + 16 copias de la MAC
        var packet = Data(repeating: 0xFF, count: 6)
        packet.reserveCapacity(102)
        for _ in 0..<16 {
            packet.append(contentsOf: macBytes)
        }
        return packet
    }

    /// Envía el Magic Packet UDP al puerto 9 de la dirección destino (o broadcast LAN).
    func sendWakePacket(
        macAddress: String,
        targetHost: String = "255.255.255.255",
        completion: @escaping (Bool) -> Void
    ) {
        guard let payload = buildMagicPacket(macAddress: macAddress),
              let port = NWEndpoint.Port(rawValue: 9) else {
            DispatchQueue.main.async { completion(false) }
            return
        }

        let conn = NWConnection(host: NWEndpoint.Host(targetHost), port: port, using: .udp)
        conn.stateUpdateHandler = { state in
            switch state {
            case .ready:
                conn.send(content: payload, completion: .contentProcessed { error in
                    conn.cancel()
                    DispatchQueue.main.async { completion(error == nil) }
                })
            case .failed:
                conn.cancel()
                DispatchQueue.main.async { completion(false) }
            default:
                break
            }
        }
        conn.start(queue: queue)
    }

    /// Comprueba si un puerto TCP del PC (ej. Sunshine 47989) responde dentro de `timeoutSec`.
    func probeTcpReachability(
        host: String,
        port: Int,
        timeoutSec: Double = 2.0,
        completion: @escaping (Bool) -> Void
    ) {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
            DispatchQueue.main.async { completion(false) }
            return
        }

        let conn = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        var finished = false
        let finishOnce: (Bool) -> Void = { reachable in
            guard !finished else { return }
            finished = true
            conn.cancel()
            DispatchQueue.main.async { completion(reachable) }
        }

        conn.stateUpdateHandler = { state in
            if case .ready = state { finishOnce(true) }
            if case .failed = state { finishOnce(false) }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + timeoutSec) { finishOnce(false) }
    }
}
