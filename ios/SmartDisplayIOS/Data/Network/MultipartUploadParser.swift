import Foundation

/// Analizador real de subidas `multipart/form-data` (PC → iPhone/iPad) con defensa Path Traversal.
///
/// Qué hace: extrae cada archivo binario enviado desde el navegador del PC (`POST /up`)
/// y lo escribe de forma segura dentro de `Documents/SmartDisplay`.
/// Cómo funciona: divide el cuerpo binario por el delimitador `--<boundary>`, extrae el
/// `filename="..."` de cada parte, sanitiza caracteres prohibidos (`..`, `/`, `\`) y verifica
/// contención canónica (`standardizedFileURL`) antes de escribir en disco.
/// Por qué: mantiene `WifiFileTransferServer` por debajo de 200 líneas aplicando SRP.
enum MultipartUploadParser {

    /// Extrae el parámetro `boundary` de la cabecera HTTP `Content-Type`.
    static func extractBoundary(from contentType: String) -> String? {
        guard let range = contentType.range(of: "boundary=", options: .caseInsensitive) else {
            return nil
        }
        var value = String(contentType[range.upperBound...])
            .split(separator: ";").first.map(String.init)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if value.hasPrefix("\"") && value.hasSuffix("\"") && value.count >= 2 {
            value = String(value.dropFirst().dropLast())
        }
        return value.isEmpty ? nil : value
    }

    /// Procesa el cuerpo `multipart/form-data`, guarda cada archivo válido y retorna sus URLs.
    static func saveUploadedFiles(
        body: Data,
        boundary: String,
        destinationDir: URL
    ) -> [URL] {
        let delimiter = Data("--\(boundary)".utf8)
        let headerSeparator = Data("\r\n\r\n".utf8)
        let canonicalRoot = destinationDir.standardizedFileURL.path
        var savedUrls: [URL] = []

        var searchStart = body.startIndex
        while let partStart = body.range(of: delimiter, in: searchStart..<body.endIndex) {
            let afterDelimiter = partStart.upperBound
            guard afterDelimiter < body.endIndex else { break }

            // Fin de multipart (`--boundary--`)
            if body[afterDelimiter...].starts(with: Data("--".utf8)) { break }

            let nextBoundary = body.range(of: delimiter, in: afterDelimiter..<body.endIndex)
            let partEnd = nextBoundary?.lowerBound ?? body.endIndex

            if let sep = body.range(of: headerSeparator, in: afterDelimiter..<partEnd),
               let headersText = String(data: body[afterDelimiter..<sep.lowerBound], encoding: .utf8),
               let rawName = extractFilename(from: headersText) {

                // Elimina el `\r\n` final que precede al siguiente boundary
                var fileEnd = partEnd
                if fileEnd >= sep.upperBound + 2,
                   body[(fileEnd - 2)..<fileEnd] == Data("\r\n".utf8) {
                    fileEnd -= 2
                }
                let fileBytes = body[sep.upperBound..<fileEnd]
                let safeName = WifiFileTransferServer.sanitizeFilename(rawName)
                let destUrl = uniqueDestination(dir: destinationDir, filename: safeName)

                // Contención canónica obligatoria: bloquea cualquier fuga fuera de SmartDisplay/
                if destUrl.standardizedFileURL.path.hasPrefix(canonicalRoot + "/") {
                    if (try? fileBytes.write(to: destUrl, options: .atomic)) != nil {
                        savedUrls.append(destUrl)
                    }
                }
            }
            guard let next = nextBoundary else { break }
            searchStart = next.lowerBound
        }
        return savedUrls
    }

    /// Extrae el nombre de archivo de la cabecera `Content-Disposition` de una parte.
    private static func extractFilename(from headers: String) -> String? {
        guard let fnRange = headers.range(of: "filename=\"", options: .caseInsensitive) else {
            return nil
        }
        let rest = headers[fnRange.upperBound...]
        guard let closeQuote = rest.firstIndex(of: "\"") else { return nil }
        let raw = String(rest[..<closeQuote])
        let decoded = raw.removingPercentEncoding ?? raw
        let base = (decoded as NSString).lastPathComponent
        return base.isEmpty ? nil : base
    }

    /// Genera un nombre único (`archivo(1).ext`) si ya existe un archivo con el mismo nombre.
    private static func uniqueDestination(dir: URL, filename: String) -> URL {
        let initial = dir.appendingPathComponent(filename)
        if !FileManager.default.fileExists(atPath: initial.path) { return initial }
        let ext = initial.pathExtension
        let stem = initial.deletingPathExtension().lastPathComponent
        for index in 1..<1000 {
            let candidateName = ext.isEmpty ? "\(stem)(\(index))" : "\(stem)(\(index)).\(ext)"
            let candidate = dir.appendingPathComponent(candidateName)
            if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
        }
        return initial
    }
}
