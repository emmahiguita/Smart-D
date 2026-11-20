import Darwin
import Foundation
import Network

/// Servidor HTTP bidireccional en red Wi-Fi local (SmartDisplay Files iOS ↔ PC).
///
/// Qué hace: levanta un `NWListener` TCP (puerto 8080) que permite descargar archivos desde
/// iOS (`GET /dl?i=N`) y subir archivos desde el navegador del PC (`POST /up` multipart).
/// Cómo funciona: delega el parseo multipart a `MultipartUploadParser`, sanitiza nombres
/// contra Path Traversal y expone la IP Wi-Fi local (`en0`) mediante `getifaddrs`.
/// Por qué: transferencia local sin cables, sin nube y sin dependencias externas.
final class WifiFileTransferServer {

    static let defaultPort: UInt16 = 8080
    private static let maxUploadBytes: Int64 = 512 * 1024 * 1024

    private let queue = DispatchQueue(label: "com.limelight.smartdisplay.fileserver", qos: .utility)
    private var listener: NWListener?
    private(set) var isRunning = false
    private(set) var sharedItems: [TransferFileItem] = []

    var onStateChanged: ((Bool) -> Void)?
    var onFileReceived: ((URL) -> Void)?

    /// Carpeta `Documents/SmartDisplay` visible en la app Archivos de iOS.
    func receivedDirectory() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("SmartDisplay", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Lista los archivos reales recibidos en `Documents/SmartDisplay` ordenados por fecha.
    func listReceivedFiles() -> [TransferFileItem] {
        let dir = receivedDirectory()
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey, .isRegularFileKey]
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]
        ) else { return [] }

        return urls.compactMap { url -> TransferFileItem? in
            guard let vals = try? url.resourceValues(forKeys: Set(keys)),
                  vals.isRegularFile == true else { return nil }
            return TransferFileItem(
                name: url.lastPathComponent,
                url: url,
                byteCount: Int64(vals.fileSize ?? 0),
                modifiedAt: vals.contentModificationDate ?? Date()
            )
        }.sorted { $0.modifiedAt > $1.modifiedAt }
    }

    /// Agrega un archivo elegido desde iOS para descarga desde el PC.
    func addSharedItem(_ item: TransferFileItem) {
        queue.async { [weak self] in self?.sharedItems.append(item) }
    }

    /// Inicia el servidor HTTP en la red Wi-Fi local.
    func start(port: UInt16 = defaultPort) {
        queue.async { [weak self] in
            guard let self, !self.isRunning, let nwPort = NWEndpoint.Port(rawValue: port) else { return }
            do {
                let newListener = try NWListener(using: .tcp, on: nwPort)
                newListener.newConnectionHandler = { [weak self] conn in self?.handleConnection(conn) }
                newListener.stateUpdateHandler = { [weak self] state in
                    guard let self else { return }
                    if case .ready = state {
                        self.isRunning = true
                        DispatchQueue.main.async { self.onStateChanged?(true) }
                    } else if case .failed = state {
                        self.stop()
                    }
                }
                newListener.start(queue: self.queue)
                self.listener = newListener
            } catch {
                self.isRunning = false
                DispatchQueue.main.async { self.onStateChanged?(false) }
            }
        }
    }

    /// Detiene el listener TCP y libera el puerto inmediatamente.
    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.listener?.cancel()
            self.listener = nil
            self.isRunning = false
            DispatchQueue.main.async { self.onStateChanged?(false) }
        }
    }

    /// Acumula cabeceras y cuerpo HTTP hasta `Content-Length` y enruta la petición.
    private func handleConnection(_ conn: NWConnection) {
        conn.start(queue: queue)
        receiveRequest(conn, accumulated: Data())
    }

    private func receiveRequest(_ conn: NWConnection, accumulated: Data) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] chunk, _, isComplete, _ in
            guard let self else { conn.cancel(); return }
            var buffer = accumulated
            if let chunk { buffer.append(chunk) }

            let headerSep = Data("\r\n\r\n".utf8)
            guard let sepRange = buffer.range(of: headerSep) else {
                if isComplete || buffer.count > 32 * 1024 { conn.cancel(); return }
                self.receiveRequest(conn, accumulated: buffer)
                return
            }

            let headerText = String(data: buffer[..<sepRange.lowerBound], encoding: .utf8) ?? ""
            let lines = headerText.components(separatedBy: "\r\n")
            let parts = (lines.first ?? "").split(separator: " ")
            guard parts.count >= 2 else { conn.cancel(); return }

            let method = String(parts[0])
            let path = String(parts[1])
            let (contentLength, contentType) = self.parseHeaders(lines)
            let bodyStart = sepRange.upperBound
            let receivedBody = Int64(buffer.count - bodyStart)

            if method == "POST" && contentLength > 0 && contentLength <= Self.maxUploadBytes && receivedBody < contentLength && !isComplete {
                self.receiveRequest(conn, accumulated: buffer)
                return
            }

            let body = Data(buffer[bodyStart...])
            self.route(conn, method: method, path: path, contentType: contentType, body: body)
        }
    }

    private func route(_ conn: NWConnection, method: String, path: String, contentType: String, body: Data) {
        if method == "GET" && (path == "/" || path.hasPrefix("/?")) {
            let html = FileServerHtmlBuilder.buildIndexHtml(sharedItems: sharedItems)
            sendResponse(conn, status: "200 OK", contentType: "text/html; charset=UTF-8", body: Data(html.utf8))
        } else if method == "GET" && path.hasPrefix("/dl") {
            serveDownload(conn, path: path)
        } else if method == "POST" && path.hasPrefix("/up") {
            handleUpload(conn, contentType: contentType, body: body)
        } else {
            sendResponse(conn, status: "404 Not Found", contentType: "text/plain; charset=UTF-8", body: Data("404".utf8))
        }
    }

    private func handleUpload(_ conn: NWConnection, contentType: String, body: Data) {
        guard let boundary = MultipartUploadParser.extractBoundary(from: contentType) else {
            sendResponse(conn, status: "400 Bad Request", contentType: "text/plain", body: Data("Falta boundary".utf8))
            return
        }
        let saved = MultipartUploadParser.saveUploadedFiles(body: body, boundary: boundary, destinationDir: receivedDirectory())
        for url in saved {
            DispatchQueue.main.async { [weak self] in self?.onFileReceived?(url) }
        }
        let html = "<!DOCTYPE html><html><body style=\"font-family:-apple-system;background:#111317;color:#e5e7eb;padding:24px\">" +
            "<h2>\(saved.count) archivo(s) recibido(s) en iOS</h2><a style=\"color:#60a5fa\" href=\"/\">Volver</a></body></html>"
        sendResponse(conn, status: "200 OK", contentType: "text/html; charset=UTF-8", body: Data(html.utf8))
    }

    private func serveDownload(_ conn: NWConnection, path: String) {
        guard let range = path.range(of: "i="),
              let idx = Int(path[range.upperBound...].split(separator: "&").first ?? ""),
              idx >= 0, idx < sharedItems.count else {
            sendResponse(conn, status: "404 Not Found", contentType: "text/plain", body: Data("No disponible".utf8))
            return
        }
        let item = sharedItems[idx]
        let accessed = item.url.startAccessingSecurityScopedResource()
        defer { if accessed { item.url.stopAccessingSecurityScopedResource() } }
        guard let fileData = try? Data(contentsOf: item.url) else {
            sendResponse(conn, status: "500 Internal Server Error", contentType: "text/plain", body: Data("Error".utf8))
            return
        }
        let header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: \(Int64(fileData.count))\r\n" +
            "Content-Disposition: attachment; filename=\"\(FileServerHtmlBuilder.sanitizeFilename(item.name))\"\r\nConnection: close\r\n\r\n"
        var payload = Data(header.utf8)
        payload.append(fileData)
        conn.send(content: payload, completion: .contentProcessed { _ in conn.cancel() })
    }

    private func sendResponse(_ conn: NWConnection, status: String, contentType: String, body: Data) {
        let header = "HTTP/1.1 \(status)\r\nContent-Type: \(contentType)\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n"
        var payload = Data(header.utf8)
        payload.append(body)
        conn.send(content: payload, completion: .contentProcessed { _ in conn.cancel() })
    }

    private func parseHeaders(_ lines: [String]) -> (Int64, String) {
        var length: Int64 = 0
        var type = ""
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let val = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            if key == "content-length" { length = Int64(val) ?? 0 }
            if key == "content-type" { type = val }
        }
        return (length, type)
    }

    /// Sanitiza nombres de archivo impidiendo Path Traversal (`..`, `.`, separadores).
    static func sanitizeFilename(_ name: String) -> String {
        FileServerHtmlBuilder.sanitizeFilename(name)
    }

    /// Obtiene la dirección IPv4 activa de la interfaz Wi-Fi (`en0`).
    static func localWifiIPv4Address() -> String? {
        FileServerHtmlBuilder.localWifiIPv4Address()
    }

    deinit { stop() }
}
