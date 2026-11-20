import SwiftUI

/// Pantalla principal de SmartDisplay AI para iOS — Paridad visual y funcional 1:1 con Android `PcView`.
///
/// Qué hace: muestra el logotipo oficial `BrandLogo`, el botón circular rápido de inicio
/// (92pt Material 3), los equipos descubiertos por Bonjour y los persistidos en `HostRepository`
/// con sonda TCP en vivo, Wake-on-LAN y acceso a Archivos, Diagnóstico, Manual y Ajustes.
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
                                    onDelete: {
                                        savedHosts = container.hostRepository.removeHost(id: host.id)
                                    },
                                    onConnect: {
                                        selectedHost = host
                                    }
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
                            .resizable()
                            .scaledToFit()
                            .frame(width: 26, height: 26)
                            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                        Text("SmartDisplay AI")
                            .font(DesignTokens.titleFont)
                            .foregroundStyle(DesignTokens.textPrimary)
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    NavigationLink { FileTransferView() } label: { Image(systemName: "arrow.up.arrow.down.circle") }
                    NavigationLink { DiagnosticsView() } label: { Image(systemName: "waveform.path.ecg") }
                    NavigationLink { ManualView() } label: { Image(systemName: "book") }
                    NavigationLink { SettingsView() } label: { Image(systemName: "gearshape") }
                    Button {
                        IosHaptics.tap(enabled: container.settings.hapticsEnabled)
                        showAddSheet = true
                    } label: { Image(systemName: "plus") }
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
                StreamSessionView(host: host)
                    .environmentObject(container)
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

    /// Botón central circular 92pt (paridad 1:1 con `LiquidStartButton` plano Material 3 de Android).
    private var quickStartHero: some View {
        VStack(spacing: DesignTokens.spacingSM) {
            Button {
                IosHaptics.success(enabled: container.settings.hapticsEnabled)
                if let target = primaryHost {
                    container.logStore.log("Conectando rápido a \(target.name)")
                    selectedHost = target
                } else {
                    showAddSheet = true
                }
            } label: {
                VStack(spacing: 4) {
                    Image(systemName: primaryHost != nil ? "play.fill" : "plus")
                        .font(.title2.weight(.semibold))
                    Text(primaryHost != nil ? "INICIAR" : "AÑADIR")
                        .font(.system(size: 11, weight: .bold))
                        .tracking(0.8)
                }
                .foregroundStyle(DesignTokens.onAccent)
                .frame(width: 92, height: 92)
                .background(DesignTokens.accent, in: Circle())
            }
            .buttonStyle(IosPressButtonStyle())

            Text(primaryHost.map { "Listo para proyectar en \($0.name)" } ?? "Agrega o detecta tu PC para comenzar")
                .font(DesignTokens.captionFont)
                .foregroundStyle(DesignTokens.textSecondary)
        }
        .padding(.top, DesignTokens.spacingSM)
    }

    private var emptyStateView: some View {
        VStack(spacing: DesignTokens.spacingSM) {
            Image(systemName: "wifi").font(.largeTitle).foregroundStyle(DesignTokens.textSecondary)
            Text("Buscando equipos SmartDisplay en tu Wi-Fi").font(DesignTokens.sectionFont)
            Text("Asegúrate de tener SmartDisplay Host activo en tu PC o pulsa + para añadir la IP.")
                .font(DesignTokens.bodyFont)
                .foregroundStyle(DesignTokens.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .enterpriseSurface()
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
