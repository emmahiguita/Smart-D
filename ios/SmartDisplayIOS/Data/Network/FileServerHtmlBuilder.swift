import Darwin
import Foundation

/// Generador de plantillas HTML y utilidades seguras para el servidor HTTP de archivos Wi-Fi de SmartDisplay.
///
/// Qué hace: construye el portal web interactivo que ve el PC al abrir `http://<ip-iphone>:8080`,
/// sanitiza nombres de archivos recibidos para blindar el sistema de ficheros contra Path Traversal
/// y resuelve la dirección IPv4 activa de la interfaz Wi-Fi (`en0`) mediante las APIs POSIX del kernel Darwin.
/// Por qué: cumple el principio de Responsabilidad Única (SRP) aislando la lógica de presentación HTML del servidor TCP.
enum FileServerHtmlBuilder {

    /// Construye la página HTML del portal web de transferencia de archivos.
    static func buildIndexHtml(sharedItems: [TransferFileItem]) -> String {
        let list = sharedItems.enumerated().map { idx, item in
            "<li><a href=\"/dl?i=\(idx)\">\(escapeHtml(item.name))</a> (\(item.formattedSize))</li>"
        }.joined()

        return """
        <!DOCTYPE html>
        <html lang="es">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>SmartDisplay Files</title>
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; max-width: 680px; margin: 32px auto; padding: 0 20px; background: #0B0D10; color: #E1E3E8; }
                h1 { font-size: 24px; font-weight: 700; color: #FFFFFF; border-bottom: 1px solid #1E232B; padding-bottom: 12px; }
                h2 { font-size: 16px; font-weight: 600; color: #8C93A0; margin-top: 28px; text-transform: uppercase; letter-spacing: 0.5px; }
                .card { background: #16191F; border: 1px solid #1E232B; border-radius: 12px; padding: 18px; margin-top: 12px; }
                input[type=file] { color: #8C93A0; font-size: 14px; }
                button { padding: 9px 20px; border: 0; border-radius: 8px; background: #296BC7; color: #FFFFFF; font-weight: 600; cursor: pointer; }
                button:hover { background: #1E55A3; }
                ul { list-style: none; padding: 0; margin: 0; }
                li { padding: 10px 0; border-bottom: 1px solid #1E232B; font-size: 14px; }
                li:last-child { border-bottom: none; }
                a { color: #60A5FA; text-decoration: none; font-weight: 500; }
                a:hover { text-decoration: underline; }
                .empty { color: #5A6270; font-style: italic; font-size: 14px; }
            </style>
        </head>
        <body>
            <h1>SmartDisplay Files</h1>
            <h2>Subir archivos al iPhone (PC → Móvil)</h2>
            <div class="card">
                <form method="post" action="/up" enctype="multipart/form-data">
                    <input type="file" name="f" multiple>
                    <button type="submit">Enviar al iPhone</button>
                </form>
            </div>
            <h2>Archivos compartidos desde el iPhone (Móvil → PC)</h2>
            <div class="card">
                \(list.isEmpty ? "<p class=\"empty\">No hay archivos compartidos desde el móvil.</p>" : "<ul>\(list)</ul>")
            </div>
        </body>
        </html>
        """
    }

    /// Sanitiza nombres de archivo impidiendo Path Traversal (`..`, `/`, `\`, caracteres nulos).
    static func sanitizeFilename(_ name: String) -> String {
        let bad = CharacterSet(charactersIn: "\\/:*?\"<>|\r\n\0")
        let cleaned = name.components(separatedBy: bad).joined(separator: "_").trimmingCharacters(in: .whitespaces)
        return (cleaned.isEmpty || cleaned == "." || cleaned == ".." || cleaned.hasPrefix("..")) ? "archivo" : cleaned
    }

    /// Escapa entidades HTML para mitigar vulnerabilidades de XSS en la interfaz web.
    static func escapeHtml(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
         .replacingOccurrences(of: "<", with: "&lt;")
         .replacingOccurrences(of: ">", with: "&gt;")
         .replacingOccurrences(of: "\"", with: "&quot;")
    }

    /// Resuelve la dirección IPv4 activa de la interfaz Wi-Fi (`en0`) mediante `getifaddrs`.
    static func localWifiIPv4Address() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = ptr {
            let addr = cur.pointee.ifa_addr.pointee
            if addr.sa_family == UInt8(AF_INET) && String(cString: cur.pointee.ifa_name) == "en0" {
                var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                var mutableAddr = cur.pointee.ifa_addr.pointee
                getnameinfo(&mutableAddr, socklen_t(cur.pointee.ifa_addr.pointee.sa_len), &buf, socklen_t(buf.count), nil, 0, NI_NUMERICHOST)
                let ip = String(cString: buf)
                if !ip.isEmpty && ip != "127.0.0.1" { return ip }
            }
            ptr = cur.pointee.ifa_next
        }
        return nil
    }
}
