import SwiftUI

/// Pantalla principal de SmartDisplay AI para iOS — Paridad 1:1 con Android `PcView`.
///
/// Muestra el logotipo `BrandLogo`, el botón circular rápido de inicio (92pt plano Material 3),
/// los equipos descubiertos y los persistidos en `HostRepository` con sonda TCP en vivo y WOL.
struct HostListView: View {

    @EnvironmentObject private var container: AppContainer
    @State private var discoveredHosts: [HostDevice] = []
    @State private var savedHosts: [HostDevice] = []
    @State private var selectedHost: HostDevice?
    @State private var showAddSheet = false
    @State private var feedbackMessage: String?

    private var allHosts: [HostDevice] {
        let savedIps = Set(savedHosts.map(\.address))
        return savedHosts + discoveredHosts.filter { !savedIps.contains($0.address) }
    }

    private var primaryHost: HostDevice? {
        allHosts.first(where: { $0.status == .online }) ?? allHosts.first
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: DesignTokens.spacingLG) {
                    quickStartHero

                    if let message = feedbackMessage {
                        Text(message)
                            .font(DesignTokens.captionFont)
                            .foregroundStyle(DesignTokens.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .enterpriseSurface()
                    }

                    VStack(alignment: .leading, spacing: DesignTokens.spacingSM) {
                        Text("Equipos disponibles (\(allHosts.count))")
                            .font(DesignTokens.sectionFont)
                            .foregroundStyle(DesignTokens.textPrimary)

                        if allHosts.isEmpty {
                            emptyStateView
                        } else {
                            ForEach(allHosts) { host in
                                HostRowView(
                                    host: host,
                                    isSaved: savedHosts.contains { $0.id == host.id },
                                    onWakeFeedback: { msg in
                                        feedbackMessage = msg
                                        container.logStore.log(msg)
                                    },
                                    onDelete: { savedHosts = container.hostRepository.removeHost(id: host.id) },
                                    onConnect: { selectedHost = host }
                                )
                            }
                        }
                    }
                }
                .padding(DesignTokens.spacingMD)
                .frame(maxWidth: DesignTokens.maxReadableWidth)
                .frame(maxWidth: .infinity)
            }
            .background(DesignTokens.background)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    HStack(spacing: 8) {
                        Image("BrandLogo")
                            .resizable().scaledToFit().frame(width: 24, height: 24)
                            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                        Text("SmartDisplay")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(DesignTokens.textPrimary)
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        IosHaptics.tap(enabled: container.settings.hapticsEnabled)
                        showAddSheet = true
                    } label: { Image(systemName: "plus").font(.system(size: 15, weight: .semibold)) }

                    Menu {
                        NavigationLink { FileTransferView() } label: { Label("Archivos Wi-Fi", systemImage: "arrow.up.arrow.down.circle") }
                        NavigationLink { DiagnosticsView() } label: { Label("Diagnóstico", systemImage: "waveform.path.ecg") }
                        NavigationLink { ManualView() } label: { Label("Manual", systemImage: "book") }
                        NavigationLink { SettingsView() } label: { Label("Ajustes", systemImage: "gearshape") }
                    } label: { Image(systemName: "ellipsis.circle").font(.system(size: 16, weight: .medium)) }
                }
            }
            .sheet(isPresented: $showAddSheet) {
                AddHostSheet { newHost in
                    savedHosts = container.hostRepository.saveHost(newHost)
                    container.logStore.log("Equipo añadido: \(newHost.name) (\(newHost.address))")
                    probeSavedHosts()
                }
            }
            .fullScreenCover(item: $selectedHost) { host in
                StreamSessionView(host: host).environmentObject(container)
            }
            .onAppear {
                savedHosts = container.hostRepository.loadSavedHosts()
                probeSavedHosts()
                container.discoveryService.startDiscovery { hosts in discoveredHosts = hosts }
            }
            .refreshable { probeSavedHosts() }
            .onDisappear { container.discoveryService.stopDiscovery() }
        }
    }

    /// Botón circular 92pt Material 3 con icono centrado y etiqueta exterior sin solapamiento.
    private var quickStartHero: some View {
        VStack(spacing: 8) {
            Button {
                IosHaptics.success(enabled: container.settings.hapticsEnabled)
                if let target = primaryHost {
                    container.logStore.log("Conectando rápido a \(target.name)")
                    selectedHost = target
                } else { showAddSheet = true }
            } label: {
                ZStack {
                    Circle().fill(DesignTokens.accent).frame(width: 92, height: 92)
                    Image(systemName: primaryHost != nil ? "play.fill" : "plus")
                        .font(.system(size: 32, weight: .semibold))
                        .foregroundStyle(DesignTokens.onAccent)
                        .offset(x: primaryHost != nil ? 3 : 0)
                }
            }
            .buttonStyle(IosPressButtonStyle())

            Text(primaryHost != nil ? "Conectar Escritorio" : "Añadir Equipo")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(DesignTokens.textPrimary)

            Text(primaryTargetSubtitle)
                .font(DesignTokens.captionFont)
                .foregroundStyle(DesignTokens.textSecondary)
                .lineLimit(1).truncationMode(.tail)
        }
        .padding(.top, DesignTokens.spacingSM)
    }

    private var primaryTargetSubtitle: String {
        guard let host = primaryHost else { return "Agrega o detecta tu PC para comenzar" }
        let raw = host.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let parenIdx = raw.firstIndex(of: "(") {
            let prefix = String(raw[..<parenIdx]).trimmingCharacters(in: .whitespaces)
            return "Listo para proyectar en \(prefix.isEmpty ? raw : prefix)"
        }
        return "Listo para proyectar en \(raw)"
    }

    private var emptyStateView: some View {
        VStack(spacing: DesignTokens.spacingSM) {
            Image(systemName: "wifi").font(.largeTitle).foregroundStyle(DesignTokens.textSecondary)
            Text("Buscando equipos SmartDisplay en tu Wi-Fi").font(DesignTokens.sectionFont)
            Text("Asegúrate de tener SmartDisplay Host activo en tu PC o pulsa + para añadir la IP.")
                .font(DesignTokens.bodyFont).foregroundStyle(DesignTokens.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 28).enterpriseSurface()
    }

    private func probeSavedHosts() {
        let port = container.settings.companionPort > 0 ? container.settings.companionPort : 47991
        for host in savedHosts {
            container.wakeOnLanService.probeTcpReachability(host: host.address, port: port) { online in
                if let pos = savedHosts.firstIndex(where: { $0.id == host.id }) {
                    withAnimation(IosMotion.snappy) { savedHosts[pos].status = online ? .online : .offline }
                }
            }
        }
    }
}
