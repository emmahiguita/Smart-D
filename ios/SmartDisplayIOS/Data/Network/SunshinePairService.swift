import Foundation

/// Servicio de emparejamiento automático Zero-Touch con Sunshine (`https://<host>:47990/api/pin`).
///
/// Qué hace: consulta la sesión de emparejamiento pendiente en el puerto web 47990 de Sunshine
/// y vincula automáticamente el PIN sin requerir abrir el navegador en el PC.
/// Cómo funciona: utiliza un `URLSessionDelegate` dedicado que acepta el certificado
/// autofirmado de Sunshine en la red local (LAN) y autentica con Basic Auth.
/// Por qué: replica la lógica real de `SunshinePairHelper.kt` con código Swift nativo y SOLID.
final class SunshinePairService: NSObject, URLSessionDelegate {

    static let webPort = 47990
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 3.0
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    /// Acepta el certificado TLS autofirmado del servidor Sunshine en la red local.
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        if let trust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }

    /// Vincula un PIN de 4 dígitos con la sesión pendiente en `https://<host>:47990/api/pin`.
    func submitPin(
        host: String,
        pin: String,
        username: String = "admin",
        password: String = "admin1234"
    ) async -> Bool {
        let formattedHost = (host.contains(":") && !host.hasPrefix("[")) ? "[\(host)]" : host
        guard let url = URL(string: "https://\(formattedHost):\(Self.webPort)/api/pin") else {
            return false
        }
        let basicAuth = Data("\(username):\(password)".utf8).base64EncodedString()

        // 1. Obtener pairing_id pendiente (si Sunshine lo expone)
        var getReq = URLRequest(url: url)
        getReq.httpMethod = "GET"
        getReq.setValue("Basic \(basicAuth)", forHTTPHeaderField: "Authorization")

        var pairingId: String?
        if let (data, resp) = try? await session.data(for: getReq),
           let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let pairings = json["pairings"] as? [[String: Any]],
           let firstId = pairings.first?["id"] as? String, !firstId.isEmpty {
            pairingId = firstId
        }

        // 2. Enviar POST con el PIN y nombre del dispositivo iOS
        var postReq = URLRequest(url: url)
        postReq.httpMethod = "POST"
        postReq.setValue("Basic \(basicAuth)", forHTTPHeaderField: "Authorization")
        postReq.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")

        var payload: [String: String] = ["pin": pin, "name": "SmartDisplay iOS"]
        if let pairingId { payload["pairing_id"] = pairingId }
        postReq.httpBody = try? JSONSerialization.data(withJSONObject: payload)

        guard let (_, postResp) = try? await session.data(for: postReq),
              let httpPost = postResp as? HTTPURLResponse else {
            return false
        }
        return (200...299).contains(httpPost.statusCode)
    }
}
