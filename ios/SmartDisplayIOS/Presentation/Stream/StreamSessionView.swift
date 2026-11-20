import SwiftUI

/// Sesión remota completa de SmartDisplay AI para iOS con aceleración por hardware GPU (VideoToolbox),
/// entrada táctil/ratón/teclado, físicas de resorte interactivas, telemetría y voz full-duplex UDP.
///
/// Qué hace: gestiona el ciclo de vida del bus WebSocket (47991), conmuta automáticamente entre
/// el flujo H.264 acelerado por hardware y el flujo interactivo JPEG de alta definición, traduce
/// gestos táctiles (clic primario, clic secundario por pulsación prolongada, movimiento relativo y scroll)
/// y orquesta las barras flotantes `StreamTopBar` y `StreamKeyboardBar`.
/// Por qué: cumple el principio de Responsabilidad Única (SRP) orquestando la sesión sin sobrecargar la vista.
struct StreamSessionView: View {

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var container: AppContainer

    let host: HostDevice

    @State private var engine = ViewportEngine()
    @State private var baseScale: CGFloat = 1.0
    @State private var lastDrag: CGSize = .zero
    @State private var lastTouchLocation: CGPoint = .zero
    @State private var busConnected = false
    @State private var networkAvailable = true
    @State private var hasVideoFrames = false
    @State private var voiceActive = false
    @State private var showKeyboardBar = false
    @State private var controlsVisible = true
    @State private var telemetry = PcTelemetrySnapshot()
    @State private var liveDesktopImage: UIImage?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()

                // Lienzo híbrido GPU: VideoToolbox H.264 + Flujo interactivo en vivo del Companion 47991
                ZStack {
                    VideoToolboxCanvasView(renderer: container.videoRenderer).ignoresSafeArea()
                    if let liveImg = liveDesktopImage {
                        Image(uiImage: liveImg)
                            .resizable()
                            .scaledToFit()
                            .ignoresSafeArea()
                    }
                    if !hasVideoFrames {
                        StreamWaitingPlaceholder(hostName: host.name, hostAddress: host.address, busConnected: busConnected)
                    }
                }
                .scaleEffect(engine.state.scale)
                .offset(x: engine.state.offsetX, y: engine.state.offsetY)
                .contentShape(Rectangle())
                .onTapGesture { location in
                    guard !engine.state.isMoveModeEnabled else { return }
                    lastTouchLocation = location
                    container.inputDispatcher.sendTap(at: location, in: geo.size)
                }
                .onLongPressGesture(minimumDuration: 0.45) {
                    guard !engine.state.isMoveModeEnabled else { return }
                    IosHaptics.success(enabled: container.settings.hapticsEnabled)
                    container.inputDispatcher.sendRightClick(at: lastTouchLocation, in: geo.size)
                }
                .gesture(
                    MagnificationGesture()
                        .onChanged { value in engine.applyScale(baseScale * value, viewportSize: geo.size) }
                        .onEnded { _ in baseScale = engine.state.scale }
                )
                .simultaneousGesture(
                    DragGesture()
                        .onChanged { value in
                            lastTouchLocation = value.location
                            if engine.state.isMoveModeEnabled {
                                let dx = value.translation.width - lastDrag.width
                                let dy = value.translation.height - lastDrag.height
                                lastDrag = value.translation
                                engine.pan(deltaX: dx, deltaY: dy, viewportSize: geo.size)
                            } else {
                                container.inputDispatcher.sendPointerMove(to: value.location, in: geo.size)
                            }
                        }
                        .onEnded { _ in lastDrag = .zero }
                )

                // Capa de controles flotantes iOS con transiciones de resorte nativas
                VStack(spacing: DesignTokens.spacingSM) {
                    if controlsVisible {
                        StreamTopBar(
                            busConnected: busConnected,
                            telemetry: telemetry,
                            zoomPercent: engine.state.zoomPercent,
                            isMoveMode: engine.state.isMoveModeEnabled,
                            isKeyboardOpen: showKeyboardBar,
                            isVoiceActive: voiceActive,
                            onZoomIn: { withAnimation(IosMotion.smooth) { engine.zoomIn(viewportSize: geo.size); baseScale = engine.state.scale } },
                            onZoomOut: { withAnimation(IosMotion.smooth) { engine.zoomOut(viewportSize: geo.size); baseScale = engine.state.scale } },
                            onResetZoom: { withAnimation(IosMotion.smooth) { engine.reset(); baseScale = 1.0 } },
                            onToggleMoveMode: { withAnimation(IosMotion.snappy) { engine.setMoveMode(!engine.state.isMoveModeEnabled) } },
                            onToggleKeyboard: { withAnimation(IosMotion.snappy) { showKeyboardBar.toggle() } },
                            onToggleVoice: { toggleVoice() },
                            onCollapse: { withAnimation(IosMotion.snappy) { controlsVisible = false } },
                            onClose: { dismiss() }
                        )
                        .transition(.move(edge: .top).combined(with: .opacity))
                    } else {
                        HStack {
                            Spacer()
                            Button {
                                IosHaptics.tap(enabled: container.settings.hapticsEnabled)
                                withAnimation(IosMotion.snappy) { controlsVisible = true }
                            } label: {
                                Image(systemName: "chevron.down")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.white)
                                    .padding(8)
                                    .background(.ultraThinMaterial, in: Circle())
                            }
                        }
                        .transition(.opacity)
                    }

                    if !networkAvailable {
                        StreamStatusPill(text: "Sin conexión Wi-Fi · Esperando red...")
                            .transition(.scale.combined(with: .opacity))
                    }

                    Spacer()

                    if engine.state.isMoveModeEnabled {
                        StreamStatusPill(text: "Modo mover pantalla activo · Arrastra con 1 dedo")
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }

                    if showKeyboardBar {
                        StreamKeyboardBar {
                            withAnimation(IosMotion.snappy) { showKeyboardBar = false }
                        }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .padding(DesignTokens.spacingMD)
            }
            .statusBarHidden(true)
            .onAppear { startSession() }
            .onDisappear { endSession() }
        }
    }

    private func startSession() {
        let port = container.settings.companionPort > 0 ? container.settings.companionPort : host.companionPort
        container.busClient.onConnectionChanged = { connected in
            withAnimation(IosMotion.snappy) { busConnected = connected }
            if connected {
                container.inputDispatcher.startDesktopStream()
                container.inputDispatcher.requestSystemStats()
            }
        }
        container.busClient.onMessage = { msg in
            guard msg.type == "stats" else { return }
            let cpu = Int(Double(msg.payload["cpu"] ?? "") ?? 0)
            let ram = Int(Double(msg.payload["ram"] ?? "") ?? 0)
            let gpu = Int(Double(msg.payload["gpu"] ?? "") ?? 0)
            withAnimation(IosMotion.snappy) {
                telemetry = PcTelemetrySnapshot(cpuPercent: cpu, ramPercent: ram, gpuPercent: gpu, hasData: true)
            }
        }
        container.busClient.onBinaryFrame = { frameData in
            guard frameData.count > 2 else { return }
            let isJpeg = (frameData[frameData.startIndex] == 0xFF && frameData[frameData.startIndex + 1] == 0xD8)
            if isJpeg, let decoded = UIImage(data: frameData) {
                DispatchQueue.main.async {
                    liveDesktopImage = decoded
                    if !hasVideoFrames { hasVideoFrames = true }
                }
            } else if !isJpeg {
                if !hasVideoFrames { DispatchQueue.main.async { hasVideoFrames = true } }
                container.videoRenderer.enqueueAnnexBFrame(frameData)
            }
        }
        container.networkMonitor.onNetworkStatusChanged = { available in
            withAnimation(IosMotion.snappy) { networkAvailable = available }
        }
        container.networkMonitor.onNetworkRestored = {
            if container.settings.autoReconnect { container.busClient.reconnectNow() }
        }
        container.networkMonitor.start()
        container.logStore.log("Conectando Bus ws://\(host.address):\(port)")
        container.busClient.connect(host: host.address, port: port, token: container.settings.voicePin)
    }

    /// Libera red, video y voz limpiamente al salir de la pantalla remota.
    private func endSession() {
        container.inputDispatcher.stopDesktopStream()
        container.networkMonitor.stop()
        container.voiceService.stop()
        voiceActive = false
        liveDesktopImage = nil
        container.busClient.onBinaryFrame = nil
        container.busClient.disconnect()
        container.videoRenderer.flush()
    }

    private func toggleVoice() {
        if voiceActive {
            container.voiceService.stop()
            voiceActive = false
        } else {
            container.voiceService.start(hostIp: host.address, pin: container.settings.voicePin) { ok in
                withAnimation(IosMotion.snappy) { voiceActive = ok }
            }
        }
    }
}
