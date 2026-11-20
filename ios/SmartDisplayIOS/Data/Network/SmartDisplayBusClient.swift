import Foundation

/// Evento JSON recibido desde el Companion Server de escritorio vía WebSocket.
struct BusMessage: Sendable {
    let type: String
    let payload: [String: String]
}

/// Cliente WebSocket persistente para el bus de eventos SmartDisplay (paridad con Android).
///
/// Qué hace: mantiene una conexión WebSocket contra `ws://<host>:<port>` para recibir
/// cambios de foco de texto, comandos de ventana y telemetría en tiempo real.
/// Cómo funciona: utiliza `URLSessionWebSocketTask` nativo de Apple con ping cada 15s,
/// backoff exponencial acotado a 30s (evita tormentas de reconexión) y validación de identidad
/// de tarea para ignorar cierres obsoletos.
/// Por qué: garantiza reconexión limpia sin fugas de memoria ni tareas zombi.
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

    var onConnectionChanged: ((Bool) -> Void)?
    var onMessage: ((BusMessage) -> Void)?
    var onBinaryFrame: ((Data) -> Void)?

    init() {
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = false
        config.timeoutIntervalForRequest = 5
        self.session = URLSession(configuration: config)
    }

    /// Inicia la conexión WebSocket hacia el PC anfitrión (puerto 47991 por defecto, IPv4/IPv6).
    func connect(host: String, port: Int = 47991, token: String = "") {
        queue.async { [weak self] in
            guard let self else { return }
            self.host = host
            self.port = port
            self.token = token
            self.isStopped = false
            self.retryAttempt = 0
            self.openSocket()
        }
    }

    /// Fuerza una reconexión inmediata (útil cuando iOS recupera la red Wi-Fi).
    func reconnectNow() {
        queue.async { [weak self] in
            guard let self, !self.isStopped, !self.host.isEmpty else { return }
            self.retryAttempt = 0
            self.openSocket()
        }
    }

    /// Envía un objeto JSON simple al Companion de escritorio si el socket está activo.
    func send(type: String, fields: [String: String] = [:]) {
        queue.async { [weak self] in
            guard let self, let activeTask = self.task else { return }
            var dict = fields
            dict["type"] = type
            guard let data = try? JSONSerialization.data(withJSONObject: dict),
                  let text = String(data: data, encoding: .utf8) else { return }
            activeTask.send(.string(text)) { _ in }
        }
    }

    /// Detiene el bus, cancela el timer de ping y cierra el WebSocket sin dejar hilos activos.
    func disconnect() {
        queue.async { [weak self] in
            guard let self else { return }
            self.isStopped = true
            self.stopPingTimer()
            self.task?.cancel(with: .normalClosure, reason: nil)
            self.task = nil
            DispatchQueue.main.async { self.onConnectionChanged?(false) }
        }
    }

    /// Construye la URL IPv6-safe con cabecera `Authorization: Bearer <PIN>` (paridad con Android).
    private func openSocket() {
        stopPingTimer()
        task?.cancel(with: .goingAway, reason: nil)

        let formattedHost = (host.contains(":") && !host.hasPrefix("[")) ? "[\(host)]" : host
        guard let url = URL(string: "ws://\(formattedHost):\(port)") else { return }

        var request = URLRequest(url: url)
        if !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let newTask = session.webSocketTask(with: request)
        task = newTask
        newTask.resume()
        retryAttempt = 0
        startPingTimer(for: newTask)
        DispatchQueue.main.async { [weak self] in self?.onConnectionChanged?(true) }
        receiveLoop(for: newTask)
    }

    /// Bucle recursivo de lectura protegido por verificación de identidad (`currentTask === task`).
    private func receiveLoop(for currentTask: URLSessionWebSocketTask) {
        currentTask.receive { [weak self] result in
            guard let self else { return }
            self.queue.async {
                guard self.task === currentTask, !self.isStopped else { return }
                switch result {
                case .success(let message):
                    self.handleIncoming(message)
                    self.receiveLoop(for: currentTask)
                case .failure:
                    self.handleDisconnect(for: currentTask)
                }
            }
        }
    }

    /// Decodifica tramas de texto JSON o entrega frames binarios H.264 al decodificador VideoToolbox.
    private func handleIncoming(_ message: URLSessionWebSocketTask.Message) {
        switch message {
        case .data(let binaryData):
            onBinaryFrame?(binaryData)
        case .string(let text):
            guard let data = text.data(using: .utf8),
                  let raw = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let type = raw["type"] as? String else { return }
            var stringFields: [String: String] = [:]
            for (key, value) in raw where key != "type" {
                stringFields[key] = "\(value)"
            }
            let parsed = BusMessage(type: type, payload: stringFields)
            DispatchQueue.main.async { [weak self] in self?.onMessage?(parsed) }
        @unknown default:
            break
        }
    }

    /// Programa reconexión con backoff exponencial acotado a un máximo de 30 segundos.
    private func handleDisconnect(for failedTask: URLSessionWebSocketTask) {
        guard task === failedTask else { return }
        stopPingTimer()
        task = nil
        DispatchQueue.main.async { [weak self] in self?.onConnectionChanged?(false) }
        guard !isStopped else { return }

        let delay = min(30.0, pow(2.0, Double(min(retryAttempt, 5))))
        retryAttempt += 1
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, !self.isStopped else { return }
            self.openSocket()
        }
    }

    /// Envía pings cada 15s para detectar conexiones zombi en redes Wi-Fi inestables.
    private func startPingTimer(for currentTask: URLSessionWebSocketTask) {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 15, repeating: 15)
        timer.setEventHandler { [weak self] in
            guard let self, self.task === currentTask else { return }
            currentTask.sendPing { error in
                if error != nil {
                    self.queue.async { self.handleDisconnect(for: currentTask) }
                }
            }
        }
        timer.resume()
        pingTimer = timer
    }

    private func stopPingTimer() {
        pingTimer?.cancel()
        pingTimer = nil
    }

    deinit {
        disconnect()
        session.invalidateAndCancel()
    }
}
