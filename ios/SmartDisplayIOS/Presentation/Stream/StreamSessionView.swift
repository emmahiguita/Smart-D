import SwiftUI

/// Sesión remota de SmartDisplay AI para iOS con aceleración GPU (VideoToolbox),
/// mapeo de ratón 1:1 con corrección de escala/pan, entrada táctil y teclado virtual.
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

                // Lienzo GPU con ratio 16:9 preservado
                ZStack {
                    VideoToolboxCanvasView(renderer: container.videoRenderer)
                    if let liveImg = liveDesktopImage { Image(uiImage: liveImg).resizable().scaledToFit() }
                    if !hasVideoFrames { StreamWaitingPlaceholder(hostName: host.name, hostAddress: host.address, busConnected: busConnected) }
                }
                .scaleEffect(engine.state.scale)
                .offset(x: engine.state.offsetX, y: engine.state.offsetY)
                .clipped().contentShape(Rectangle())
                .onTapGesture { loc in
                    guard !engine.state.isMoveModeEnabled else { return }
                    lastTouchLocation = loc
                    container.inputDispatcher.sendTap(at: mapToContentPoint(loc, viewportSize: geo.size), in: geo.size)
                }
                .onLongPressGesture(minimumDuration: 0.45) {
                    guard !engine.state.isMoveModeEnabled else { return }
                    IosHaptics.success(enabled: container.settings.hapticsEnabled)
                    container.inputDispatcher.sendRightClick(at: mapToContentPoint(lastTouchLocation, viewportSize: geo.size), in: geo.size)
                }
                .gesture(
                    MagnificationGesture()
                        .onChanged { v in engine.applyScale(baseScale * v, viewportSize: geo.size) }
                        .onEnded { _ in baseScale = engine.state.scale }
                )
                .simultaneousGesture(
                    DragGesture()
                        .onChanged { v in
                            lastTouchLocation = v.location
                            if engine.state.isMoveModeEnabled {
                                let dx = v.translation.width - lastDrag.width
                                let dy = v.translation.height - lastDrag.height
                                lastDrag = v.translation
                                engine.pan(deltaX: dx, deltaY: dy, viewportSize: geo.size)
                            } else {
                                container.inputDispatcher.sendPointerMove(to: mapToContentPoint(v.location, viewportSize: geo.size), in: geo.size)
                            }
                        }
                        .onEnded { _ in lastDrag = .zero }
                )

                // Capa de controles flotantes anclada al borde superior
                VStack(spacing: DesignTokens.spacingSM) {
                    if controlsVisible {
                        StreamTopBar(
                            busConnected: busConnected, telemetry: telemetry,
                            zoomPercent: engine.state.zoomPercent, isMoveMode: engine.state.isMoveModeEnabled,
                            isKeyboardOpen: showKeyboardBar, isVoiceActive: voiceActive,
                            onZoomIn: { withAnimation(IosMotion.smooth) { engine.zoomIn(viewportSize: geo.size); baseScale = engine.state.scale } },
                            onZoomOut: { withAnimation(IosMotion.smooth) { engine.zoomOut(viewportSize: geo.size); baseScale = engine.state.scale } },
                            onResetZoom: { withAnimation(IosMotion.smooth) { engine.reset(); baseScale = 1.0 } },
                            onToggleMoveMode: { withAnimation(IosMotion.snappy) { engine.setMoveMode(!engine.state.isMoveModeEnabled) } },
                            onToggleKeyboard: { withAnimation(IosMotion.snappy) { showKeyboardBar.toggle() } },
                            onToggleVoice: { toggleVoice() },
                            onCollapse: { withAnimation(IosMotion.snappy) { controlsVisible = false } },
                            onClose: { dismiss() }
                        )
                        .padding(.horizontal, DesignTokens.spacingMD).padding(.top, 4)
                        .transition(.move(edge: .top).combined(with: .opacity))
                    } else {
                        HStack {
                            Spacer()
                            Button {
                                IosHaptics.tap(enabled: container.settings.hapticsEnabled)
                                withAnimation(IosMotion.snappy) { controlsVisible = true }
                            } label: {
                                Image(systemName: "chevron.down").font(.caption.weight(.semibold))
                                    .foregroundStyle(.white).padding(8).background(.ultraThinMaterial, in: Circle())
                            }
                        }
                        .padding(.horizontal, DesignTokens.spacingMD).padding(.top, 4).transition(.opacity)
                    }

                    if !networkAvailable {
                        StreamStatusPill(text: "Sin conexión Wi-Fi · Esperando red...").transition(.scale.combined(with: .opacity))
                    }

                    Spacer()

                    if engine.state.isMoveModeEnabled {
                        StreamStatusPill(text: "Modo mover pantalla activo · Arrastra con 1 dedo").transition(.move(edge: .bottom).combined(with: .opacity))
                    }

                    if showKeyboardBar {
                        StreamKeyboardBar { withAnimation(IosMotion.snappy) { showKeyboardBar = false } }.transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .padding(.bottom, DesignTokens.spacingMD)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
            .statusBarHidden(true)
            .onAppear { startSession() }
            .onDisappear { endSession() }
        }
    }

    /// Transforma coordenadas de pantalla a espacio de contenido compensando escala y desplazamiento.
    private func mapToContentPoint(_ screenPoint: CGPoint, viewportSize: CGSize) -> CGPoint {
        let scale = engine.state.scale
        guard scale > 0 else { return screenPoint }
        let cx = viewportSize.width / 2.0
        let cy = viewportSize.height / 2.0
        let x = (screenPoint.x - cx - engine.state.offsetX) / scale + cx
        let y = (screenPoint.y - cy - engine.state.offsetY) / scale + cy
        return CGPoint(x: x, y: y)
    }

    private func startSession() {
        let port = container.settings.companionPort > 0 ? container.settings.companionPort : host.companionPort
        container.busClient.onConnectionChanged = { connected in
            withAnimation(IosMotion.snappy) { busConnected = connected }
            if connected { container.inputDispatcher.startDesktopStream(); container.inputDispatcher.requestSystemStats() }
        }
        container.busClient.onMessage = { msg in
            guard msg.type == "stats" else { return }
            let cpu = Int(Double(msg.payload["cpu"] ?? "") ?? 0)
            let ram = Int(Double(msg.payload["ram"] ?? "") ?? 0)
            let gpu = Int(Double(msg.payload["gpu"] ?? "") ?? 0)
            withAnimation(IosMotion.snappy) { telemetry = PcTelemetrySnapshot(cpuPercent: cpu, ramPercent: ram, gpuPercent: gpu, hasData: true) }
        }
        container.busClient.onBinaryFrame = { frameData in
            guard frameData.count > 2 else { return }
            let isJpeg = (frameData[frameData.startIndex] == 0xFF && frameData[frameData.startIndex + 1] == 0xD8)
            if isJpeg, let decoded = UIImage(data: frameData) {
                DispatchQueue.main.async { liveDesktopImage = decoded; if !hasVideoFrames { hasVideoFrames = true } }
            } else if !isJpeg {
                if !hasVideoFrames { DispatchQueue.main.async { hasVideoFrames = true } }
                container.videoRenderer.enqueueAnnexBFrame(frameData)
            }
        }
        container.networkMonitor.onNetworkStatusChanged = { available in withAnimation(IosMotion.snappy) { networkAvailable = available } }
        container.networkMonitor.onNetworkRestored = { if container.settings.autoReconnect { container.busClient.reconnectNow() } }
        container.networkMonitor.start()
        container.logStore.log("Conectando Bus ws://\(host.address):\(port)")
        container.busClient.connect(host: host.address, port: port, token: container.settings.voicePin)
    }

    private func endSession() {
        container.inputDispatcher.stopDesktopStream()
        container.networkMonitor.stop()
        container.voiceService.stop()
        voiceActive = false; liveDesktopImage = nil
        container.busClient.onBinaryFrame = nil; container.busClient.disconnect()
        container.videoRenderer.flush()
    }

    private func toggleVoice() {
        if voiceActive { container.voiceService.stop(); voiceActive = false } else {
            container.voiceService.start(hostIp: host.address, pin: container.settings.voicePin) { ok in
                withAnimation(IosMotion.snappy) { voiceActive = ok }
            }
        }
    }
}
