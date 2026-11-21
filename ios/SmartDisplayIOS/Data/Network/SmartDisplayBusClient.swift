import Foundation

/// Evento JSON recibido desde el Companion Server de escritorio vía WebSocket.
/// Por qué: tipado fuerte evita acceso a claves inexistentes con String literal.
struct BusMessage: Sendable {
    let type: String
    let payload: [String: String]
}

/// Cliente WebSocket persistente para el Bus SmartDisplay (paridad con Android).
///
/// Qué hace: mantiene ws://host:47991 activo con ping cada 15s y backoff hasta 30s.
/// Cómo funciona: `URLSessionWebSocketTask` nativo. `onConnectionChanged(true)` se
/// dispara SOLO cuando el primer frame llega, NO al llamar `.resume()`, evitando el
/// bug de "conectado" prematuro que mostraba verde aunque el servidor rechazara la conexión.
/// Por qué: garantiza que el indicador de estado refleje la realidad del servidor.
final class SmartDisplayBusClient {

    private let session: URLSession
    private var task: URLSessionWebSocketTask?
    private var pingTimer: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "com.limelight.smartdisplay.bus", qos: .utility)

    private var host: String = ""
    private var port: Int = 47991
    private var token: String = ""
    private var isStopped = true
    private var retryAttempt = 0
    private var didNotifyConnected = false   // ← flag: fired connected(true) exactly once

    var onConnectionChanged: ((Bool) -> Void)?
    var onMessage: ((BusMessage) -> Void)?
    var onBinaryFrame: ((Data) -> Void)?

    init() {
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = false
        config.timeoutIntervalForRequest = 8
        self.session = URLSession(configuration: config)
    }

    /// Inicia la conexión hacia el PC anfitrión. El estado pasa a `connected` solo
    /// tras recibir el primer frame, no al llamar `.resume()`.
    func connect(host: String, port: Int = 47991, token: String = "") {
        queue.async { [weak self] in
            guard let self else { return }
            self.host = host; self.port = port; self.token = token
            self.isStopped = false; self.retryAttempt = 0
            self.openSocket()
        }
    }

    /// Fuerza reconexión inmediata (útil tras recuperar Wi-Fi).
    func reconnectNow() {
        queue.async { [weak self] in
            guard let self, !self.isStopped, !self.host.isEmpty else { return }
            self.retryAttempt = 0; self.openSocket()
        }
    }

    /// Envía un mensaje JSON al PC si el socket está activo.
    func send(type: String, fields: [String: String] = [:]) {
        queue.async { [weak self] in
            guard let self, let activeTask = self.task else { return }
            var dict = fields; dict["type"] = type
            guard let data = try? JSONSerialization.data(withJSONObject: dict),
                  let text = String(data: data, encoding: .utf8) else { return }
            activeTask.send(.string(text)) { _ in }
        }
    }

    /// Detiene el bus limpiamente: cancela timer, cierra socket, notifica desconexión.
    func disconnect() {
        queue.async { [weak self] in
            guard let self else { return }
            self.isStopped = true; self.didNotifyConnected = false
            self.stopPingTimer()
            self.task?.cancel(with: .normalClosure, reason: nil); self.task = nil
            DispatchQueue.main.async { self.onConnectionChanged?(false) }
        }
    }

    // ── Privado ────────────────────────────────────────────────────────────────

    /// Abre el socket WebSocket con cabecera `Authorization: Bearer <PIN>` si hay token.
    private func openSocket() {
        stopPingTimer(); task?.cancel(with: .goingAway, reason: nil)
        didNotifyConnected = false
        let fmtHost = (host.contains(":") && !host.hasPrefix("[")) ? "[\(host)]" : host
        guard let url = URL(string: "ws://\(fmtHost):\(port)") else { return }
        var req = URLRequest(url: url)
        if !token.isEmpty { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let newTask = session.webSocketTask(with: req)
        task = newTask
        newTask.resume()
        startPingTimer(for: newTask)
        receiveLoop(for: newTask)
        // NO disparar onConnectionChanged(true) aquí — se dispara al primer mensaje real
    }

    /// Bucle de lectura con verificación de identidad de tarea para evitar zombis.
    private func receiveLoop(for currentTask: URLSessionWebSocketTask) {
        currentTask.receive { [weak self] result in
            guard let self else { return }
            self.queue.async {
                guard self.task === currentTask, !self.isStopped else { return }
                switch result {
                case .success(let message):
                    // Primer frame recibido = handshake WS completado exitosamente
                    if !self.didNotifyConnected {
                        self.didNotifyConnected = true
                        self.retryAttempt = 0
                        DispatchQueue.main.async { [weak self] in self?.onConnectionChanged?(true) }
                    }
                    self.handleIncoming(message)
                    self.receiveLoop(for: currentTask)
                case .failure:
                    self.handleDisconnect(for: currentTask)
                }
            }
        }
    }

    /// Decodifica JSON o entrega frames binarios H.264 al decodificador de video.
    private func handleIncoming(_ message: URLSessionWebSocketTask.Message) {
        switch message {
        case .data(let binaryData): onBinaryFrame?(binaryData)
        case .string(let text):
            guard let data = text.data(using: .utf8),
                  let raw = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let type = raw["type"] as? String else { return }
            var fields: [String: String] = [:]
            for (k, v) in raw where k != "type" { fields[k] = "\(v)" }
            let msg = BusMessage(type: type, payload: fields)
            DispatchQueue.main.async { [weak self] in self?.onMessage?(msg) }
        @unknown default: break
        }
    }

    /// Backoff exponencial acotado a 30s para reconexión automática.
    private func handleDisconnect(for failedTask: URLSessionWebSocketTask) {
        guard task === failedTask else { return }
        stopPingTimer(); task = nil; didNotifyConnected = false
        DispatchQueue.main.async { [weak self] in self?.onConnectionChanged?(false) }
        guard !isStopped else { return }
        let delay = min(30.0, pow(2.0, Double(min(retryAttempt, 5))))
        retryAttempt += 1
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, !self.isStopped else { return }
            self.openSocket()
        }
    }

    /// Ping cada 15s para detectar conexiones muertas en Wi-Fi inestable.
    private func startPingTimer(for currentTask: URLSessionWebSocketTask) {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 15, repeating: 15)
        timer.setEventHandler { [weak self] in
            guard let self, self.task === currentTask else { return }
            currentTask.sendPing { error in
                if error != nil { self.queue.async { self.handleDisconnect(for: currentTask) } }
            }
        }
        timer.resume(); pingTimer = timer
    }

    private func stopPingTimer() { pingTimer?.cancel(); pingTimer = nil }

    deinit { disconnect(); session.invalidateAndCancel() }
}
