import AVFoundation
import CryptoKit
import Foundation
import Network

/// Servicio real de voz bidireccional UDP (puerto 48999) con autenticación SHA-256.
///
/// Qué hace: captura el micrófono con `AVAudioEngine` (compuerta RMS + prefijo SHA-256)
/// y simultáneamente recibe audio PCM 16-bit de retorno desde el PC anfitrión, verificando
/// el prefijo SHA-256 antes de reproducirlo en `AVAudioPlayerNode`.
/// Por qué: logra comunicación full-duplex segura en LAN sin interferir con el video.
final class VoiceUdpService {

    static let hostPort: UInt16 = 48999
    private static let sampleRate: Double = 48_000
    private static let noiseGateRms: Float = 0.0045

    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let playbackFormat = AVAudioFormat(
        commonFormat: .pcmFormatInt16, sampleRate: sampleRate, channels: 1, interleaved: true
    )!

    private let queue = DispatchQueue(label: "com.limelight.smartdisplay.voice", qos: .userInteractive)
    private var connection: NWConnection?
    private var authPrefix = Data()
    private(set) var isRecording = false

    /// Calcula los primeros 8 bytes del SHA-256 del PIN usando `CryptoKit`.
    private static func computeAuthPrefix(pin: String) -> Data {
        guard !pin.isEmpty else { return Data() }
        return Data(SHA256.hash(data: Data(pin.utf8)).prefix(8))
    }

    /// Solicita permiso de micrófono y arranca captura + retorno UDP hacia `hostIp:48999`.
    func start(hostIp: String, pin: String = "", completion: @escaping (Bool) -> Void) {
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
            guard let self, granted else {
                DispatchQueue.main.async { completion(false) }
                return
            }
            self.queue.async {
                let ok = self.startInternal(hostIp: hostIp, pin: pin)
                DispatchQueue.main.async { completion(ok) }
            }
        }
    }

    private func startInternal(hostIp: String, pin: String) -> Bool {
        guard !isRecording, let port = NWEndpoint.Port(rawValue: Self.hostPort) else { return false }

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setPreferredSampleRate(Self.sampleRate)
            try session.setActive(true)

            authPrefix = Self.computeAuthPrefix(pin: pin)
            let udpConn = NWConnection(host: NWEndpoint.Host(hostIp), port: port, using: .udp)
            udpConn.start(queue: queue)
            connection = udpConn

            if !engine.attachedNodes.contains(playerNode) {
                engine.attach(playerNode)
                engine.connect(playerNode, to: engine.mainMixerNode, format: playbackFormat)
            }

            let inputNode = engine.inputNode
            let hwFormat = inputNode.outputFormat(forBus: 0)
            inputNode.removeTap(onBus: 0)
            inputNode.installTap(onBus: 0, bufferSize: 960, format: hwFormat) { [weak self] buffer, _ in
                self?.processAndSend(buffer: buffer)
            }

            engine.prepare()
            try engine.start()
            playerNode.play()
            isRecording = true
            receiveReturnAudioLoop(on: udpConn)
            return true
        } catch {
            stopInternal()
            return false
        }
    }

    /// Convierte muestras Float32 del micrófono a PCM16, aplica Noise Gate RMS y envía por UDP.
    private func processAndSend(buffer: AVAudioPCMBuffer) {
        guard isRecording, let channelData = buffer.floatChannelData?[0] else { return }
        let frames = Int(buffer.frameLength)
        guard frames > 0 else { return }

        var sumSq: Float = 0
        for i in 0..<frames { sumSq += channelData[i] * channelData[i] }
        let rms = sqrt(sumSq / Float(frames))

        var payload = Data(capacity: authPrefix.count + frames * 2)
        payload.append(authPrefix)
        if rms < Self.noiseGateRms {
            payload.append(Data(count: frames * 2))
        } else {
            for i in 0..<frames {
                var pcm16 = Int16(max(-1.0, min(1.0, channelData[i])) * Float(Int16.max)).littleEndian
                withUnsafeBytes(of: &pcm16) { payload.append(contentsOf: $0) }
            }
        }
        connection?.send(content: payload, completion: .idempotent)
    }

    /// Escucha paquetes UDP de retorno del PC, verifica el prefijo SHA-256 y reproduce en `playerNode`.
    private func receiveReturnAudioLoop(on conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, _, error in
            guard let self, self.isRecording, self.connection === conn, error == nil else { return }
            if let packet = data { self.playVerifiedPacket(packet) }
            self.receiveReturnAudioLoop(on: conn)
        }
    }

    /// Valida el prefijo de autenticación y programa el buffer PCM16 en `AVAudioPlayerNode`.
    private func playVerifiedPacket(_ packet: Data) {
        let prefixLen = authPrefix.count
        guard packet.count > prefixLen else { return }
        if prefixLen > 0 && packet.prefix(prefixLen) != authPrefix { return }

        let pcmData = packet.dropFirst(prefixLen)
        let frameCount = AVAudioFrameCount(pcmData.count / 2)
        guard frameCount > 0,
              let audioBuf = AVAudioPCMBuffer(pcmFormat: playbackFormat, frameCapacity: frameCount),
              let dst = audioBuf.int16ChannelData?[0] else { return }

        audioBuf.frameLength = frameCount
        pcmData.withUnsafeBytes { raw in
            if let src = raw.baseAddress { memcpy(dst, src, Int(frameCount) * 2) }
        }
        playerNode.scheduleBuffer(audioBuf, completionHandler: nil)
    }

    /// Detiene micrófono, reproductor de retorno y socket UDP liberando la sesión de audio.
    func stop() {
        queue.async { [weak self] in self?.stopInternal() }
    }

    private func stopInternal() {
        isRecording = false
        engine.inputNode.removeTap(onBus: 0)
        if playerNode.isPlaying { playerNode.stop() }
        if engine.isRunning { engine.stop() }
        connection?.cancel()
        connection = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    deinit { stopInternal() }
}
