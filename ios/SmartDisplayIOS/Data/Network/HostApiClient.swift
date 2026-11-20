import Foundation

/// Cliente HTTP/XML nativo de SmartDisplay AI para consultar y controlar el PC anfitrión (puerto 47989).
///
/// Qué hace: implementa en Swift puro (`URLSession` + `XMLParser`) el protocolo estándar de
/// descubrimiento de estado (`/serverinfo`), listado de aplicaciones (`/applist`) y cierre
/// de sesión remota (`/cancel`), obteniendo automáticamente el hostname real y la MAC para WoL.
/// Cómo funciona: genera un `uniqueid` persistente del dispositivo iOS, realiza peticiones
/// asíncronas con timeout corto (4s) y parsea el XML sin dependencias externas.
/// Por qué: aporta lógica real de control del host con 100% código propio y SOLID.
final class HostApiClient {

    static let defaultHttpPort = 47989

    private let session: URLSession
    private let clientUniqueId: String

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 4.0
        config.timeoutIntervalForResource = 6.0
        self.session = URLSession(configuration: config)

        let key = "sd.client.uniqueid"
        if let saved = UserDefaults.standard.string(forKey: key) {
            self.clientUniqueId = saved
        } else {
            let created = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16)).lowercased()
            UserDefaults.standard.set(created, forKey: key)
            self.clientUniqueId = created
        }
    }

    /// Consulta `/serverinfo` en el PC anfitrión para obtener hostname, MAC, estado y app activa.
    func fetchServerInfo(host: String, port: Int = defaultHttpPort) async throws -> HostServerInfo {
        let data = try await request(host: host, port: port, path: "/serverinfo")
        let tags = SimpleXmlTagParser.parseFlatTags(data: data)
        return HostServerInfo(
            hostname: tags["hostname"] ?? host,
            uniqueId: tags["uniqueid"] ?? "",
            macAddress: normalizedMac(tags["mac"]),
            httpsPort: Int(tags["HttpsPort"] ?? "") ?? 47984,
            isPaired: tags["PairStatus"] == "1",
            currentAppId: Int(tags["currentgame"] ?? "") ?? 0
        )
    }

    /// Consulta `/applist` para listar las aplicaciones/escritorios disponibles en el PC.
    func fetchAppList(host: String, port: Int = defaultHttpPort) async throws -> [RemoteAppItem] {
        let data = try await request(host: host, port: port, path: "/applist")
        return AppListXmlParser.parseApps(data: data)
    }

    /// Solicita cancelar la sesión remota activa en el PC (`/cancel`).
    func cancelRemoteSession(host: String, port: Int = defaultHttpPort) async -> Bool {
        (try? await request(host: host, port: port, path: "/cancel")) != nil
    }

    /// Ejecuta una petición GET segura (compatible IPv4/IPv6) con `uniqueid` y `uuid`.
    private func request(host: String, port: Int, path: String) async throws -> Data {
        let formattedHost = (host.contains(":") && !host.hasPrefix("[")) ? "[\(host)]" : host
        let urlStr = "http://\(formattedHost):\(port)\(path)?uniqueid=\(clientUniqueId)&uuid=\(UUID().uuidString)"
        guard let url = URL(string: urlStr) else { throw URLError(.badURL) }
        let (data, response) = try await session.data(from: url)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return data
    }

    /// Filtra direcciones MAC vacías o nulas (`00:00:00:00:00:00`).
    private func normalizedMac(_ raw: String?) -> String? {
        guard let mac = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !mac.isEmpty, mac != "00:00:00:00:00:00" else { return nil }
        return mac
    }
}

/// Parser XML ligero para etiquetas planas de `/serverinfo`.
private final class SimpleXmlTagParser: NSObject, XMLParserDelegate {
    private var currentTag = ""
    private var buffer = ""
    private var values: [String: String] = [:]

    static func parseFlatTags(data: Data) -> [String: String] {
        let delegate = SimpleXmlTagParser()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        return delegate.values
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        currentTag = elementName
        buffer = ""
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        buffer += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let clean = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
        if !clean.isEmpty { values[elementName] = clean }
        currentTag = ""
    }
}

/// Parser XML específico para elementos `<App>` devueltos por `/applist`.
private final class AppListXmlParser: NSObject, XMLParserDelegate {
    private var apps: [RemoteAppItem] = []
    private var inApp = false
    private var currentTag = ""
    private var buffer = ""
    private var appId = 0
    private var appTitle = ""
    private var hdr = false

    static func parseApps(data: Data) -> [RemoteAppItem] {
        let delegate = AppListXmlParser()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        return delegate.apps
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        if elementName == "App" {
            inApp = true
            appId = 0; appTitle = ""; hdr = false
        }
        currentTag = elementName
        buffer = ""
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inApp { buffer += string }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let val = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
        if inApp {
            if elementName == "ID" { appId = Int(val) ?? 0 }
            else if elementName == "AppTitle" { appTitle = val }
            else if elementName == "IsHdrSupported" { hdr = (val == "1") }
            else if elementName == "App" {
                if !appTitle.isEmpty {
                    apps.append(RemoteAppItem(id: appId, title: appTitle, hdrSupported: hdr))
                }
                inApp = false
            }
        }
    }
}
