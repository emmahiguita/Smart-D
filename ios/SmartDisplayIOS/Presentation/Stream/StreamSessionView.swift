import SwiftUI

/// Sesión remota completa de SmartDisplay AI para iOS con físicas nativas, VideoToolbox,
/// entrada táctil/teclado, auto-reconexión de red (`NWPathMonitor`) y voz full-duplex UDP.
///
/// Qué hace: conecta el bus WebSocket, decodifica frames H.264 por GPU, traduce gestos
/// de pellizco/arrastre con animación de resorte iOS y oculta/muestra controles suavemente.
struct StreamSessionView: View {

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var container: AppContainer

    let host: HostDevice

    @State private var engine = ViewportEngine()
    @State private var baseScale: CGFloat = 1.0
    @State private var lastDrag: CGSize = .zero
    @State private var busConnected = false
    @State private var networkAvailable = true
    @State private var hasVideoFrames = false
    @State private var voiceActive = false
    @State private var showKeyboardBar = false
    @State private var controlsVisible = true
    @State private var draftText = ""

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()

                // Lienzo GPU (AVSampleBufferDisplayLayer) con físicas de resorte iOS
                ZStack {
                    VideoToolboxCanvasView(renderer: container.videoRenderer).ignoresSafeArea()
                    if !hasVideoFrames { waitingPlaceholder }
                }
                .scaleEffect(engine.state.scale)
                .offset(x: engine.state.offsetX, y: engine.state.offsetY)
                .contentShape(Rectangle())
                .onTapGesture { location in
                    guard !engine.state.isMoveModeEnabled else { return }
                    container.inputDispatcher.sendTap(at: location, in: geo.size)
                }
                .gesture(
                    MagnificationGesture()
                        .onChanged { value in engine.applyScale(baseScale * value, viewportSize: geo.size) }
                        .onEnded { _ in baseScale = engine.state.scale }
                )
                .simultaneousGesture(
                    DragGesture()
                        .onChanged { value in
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

                // Capa de controles flotantes iOS (transiciones elásticas con resorte)
                VStack(spacing: DesignTokens.spacingSM) {
                    if controlsVisible {
                        topControlBar(viewportSize: geo.size)
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
                        statusPill("Sin conexión Wi-Fi · Esperando red...")
                            .transition(.scale.combined(with: .opacity))
                    }

                    Spacer()

                    if engine.state.isMoveModeEnabled {
                        statusPill("Modo mover pantalla activo · Arrastra con 1 dedo")
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }

                    if showKeyboardBar {
                        keyboardInputBar
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

    private var waitingPlaceholder: some View {
        VStack(spacing: 8) {
            Image(systemName: busConnected ? "display" : "wifi.exclamationmark")
                .font(.largeTitle)
                .foregroundStyle(.white.opacity(0.85))
            Text(host.name).font(DesignTokens.sectionFont).foregroundStyle(.white)
            Text(busConnected ? "Conectado · Esperando video H.264..." : "Conectando con \(host.address)...")
                .font(DesignTokens.monoFont)
                .foregroundStyle(.white.opacity(0.65))
        }
    }

    private func topControlBar(viewportSize: CGSize) -> some View {
        HStack(spacing: DesignTokens.spacingXS) {
            Circle()
                .fill(busConnected ? DesignTokens.statusOnline : DesignTokens.statusError)
                .frame(width: 8, height: 8)
            Text("\(engine.state.zoomPercent)%").font(DesignTokens.monoFont).foregroundStyle(.white)
            Spacer()
            iconBtn("minus.magnifyingglass") { withAnimation(IosMotion.smooth) { engine.zoomOut(viewportSize: viewportSize); baseScale = engine.state.scale } }
            iconBtn("plus.magnifyingglass") { withAnimation(IosMotion.smooth) { engine.zoomIn(viewportSize: viewportSize); baseScale = engine.state.scale } }
            iconBtn("arrow.up.left.and.arrow.down.right") { withAnimation(IosMotion.smooth) { engine.reset(); baseScale = 1.0 } }
            iconBtn(engine.state.isMoveModeEnabled ? "hand.draw.fill" : "hand.draw") { withAnimation(IosMotion.snappy) { engine.setMoveMode(!engine.state.isMoveModeEnabled) } }
            iconBtn(showKeyboardBar ? "keyboard.chevron.compact.down" : "keyboard") { withAnimation(IosMotion.snappy) { showKeyboardBar.toggle() } }
            iconBtn(voiceActive ? "mic.fill" : "mic.slash") { toggleVoice() }
            iconBtn("chevron.up") { withAnimation(IosMotion.snappy) { controlsVisible = false } }
            iconBtn("xmark") { dismiss() }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial, in: Capsule())
    }

    private var keyboardInputBar: some View {
        VStack(spacing: DesignTokens.spacingXS) {
            // Teclas especiales rápidas conectadas a `SmartDisplayInputDispatcher.sendSpecialKey`
            HStack(spacing: DesignTokens.spacingXS) {
                specialKeyBtn("Esc", code: "escape")
                specialKeyBtn("Tab", code: "tab")
                specialKeyBtn("⌫", code: "backspace")
                specialKeyBtn("↵", code: "enter")
                Spacer()
            }
            HStack(spacing: DesignTokens.spacingSM) {
                TextField("Escribe texto o párrafo para el PC...", text: $draftText)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.send)
                    .onSubmit { sendDraftText() }
                Button("Enviar") { sendDraftText() }
                    .buttonStyle(.borderedProminent)
                    .disabled(draftText.isEmpty)
            }
        }
        .padding(10)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    private func specialKeyBtn(_ title: String, code: String) -> some View {
        Button(title) {
            IosHaptics.tap(enabled: container.settings.hapticsEnabled)
            container.inputDispatcher.sendSpecialKey(code)
        }
        .font(DesignTokens.captionFont.weight(.medium))
        .foregroundStyle(.white)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Color.white.opacity(0.16), in: RoundedRectangle(cornerRadius: 6))
        .buttonStyle(IosPressButtonStyle())
    }

    private func statusPill(_ text: String) -> some View {
        Text(text)
            .font(DesignTokens.captionFont)
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(.ultraThinMaterial, in: Capsule())
    }

    private func iconBtn(_ name: String, action: @escaping () -> Void) -> some View {
        Button {
            IosHaptics.selection(enabled: container.settings.hapticsEnabled)
            action()
        } label: {
            Image(systemName: name).font(.system(size: 15, weight: .medium)).foregroundStyle(.white).frame(width: 28, height: 28)
        }
        .buttonStyle(IosPressButtonStyle())
    }

    private func sendDraftText() {
        container.inputDispatcher.sendText(draftText)
        IosHaptics.tap(enabled: container.settings.hapticsEnabled)
        draftText = ""
    }

    /// Arranca bus WebSocket, enlace VideoToolbox y monitor de auto-reconexión de red.
    private func startSession() {
        let port = container.settings.companionPort > 0 ? container.settings.companionPort : host.companionPort
        container.busClient.onConnectionChanged = { connected in
            withAnimation(IosMotion.snappy) { busConnected = connected }
        }
        container.busClient.onBinaryFrame = { frameData in
            if !hasVideoFrames { DispatchQueue.main.async { withAnimation(IosMotion.smooth) { hasVideoFrames = true } } }
            container.videoRenderer.enqueueAnnexBFrame(frameData)
        }
        container.networkMonitor.onNetworkStatusChanged = { available in
            withAnimation(IosMotion.snappy) { networkAvailable = available }
        }
        container.networkMonitor.onNetworkRestored = {
            if container.settings.autoReconnect { container.busClient.reconnectNow() }
        }
        container.networkMonitor.start()
        container.busClient.connect(host: host.address, port: port)
    }

    /// Libera red, monitor, video y voz limpiamente al cerrar la sesión.
    private func endSession() {
        container.networkMonitor.stop()
        container.voiceService.stop()
        voiceActive = false
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
