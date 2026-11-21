import SwiftUI

/// Pantalla principal de SmartDisplay AI para iOS con diseño Flutter Material Expressive 3 adaptativo.
///
/// Qué hace: muestra los equipos PC disponibles con chips de filtrado, hero de conexión rápida y layout 2 columnas en landscape.
/// Cómo funciona: detecta `verticalSizeClass` para reconfigurar el layout en grid en landscape, previniendo solapamientos.
/// Por qué existe: cumple SRP orquestando el descubrimiento y navegación sin dejar timers ni procesos zombis.
struct HostListView: View {

    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @EnvironmentObject private var container: AppContainer

    @State private var discoveredHosts: [HostDevice] = []
    @State private var savedHosts: [HostDevice] = []
    @State private var selectedHost: HostDevice?
    @State private var showAddSheet = false
    @State private var selectedFilter: HostFilter = .all
    @State private var feedbackMessage: String?

    enum HostFilter: String, CaseIterable, Identifiable {
        case all = "Todos", online = "En línea"
        var id: String { rawValue }
    }

    private var allHosts: [HostDevice] {
        let savedIps = Set(savedHosts.map(\.address))
        let combined = savedHosts + discoveredHosts.filter { !savedIps.contains($0.address) }
        return selectedFilter == .all ? combined : combined.filter { $0.status == .online }
    }

    private var primaryHost: HostDevice? {
        allHosts.first(where: { $0.status == .online }) ?? allHosts.first
    }

    private var isLandscape: Bool { verticalSizeClass == .compact }

    var body: some View {
        NavigationStack {
            Group {
                if isLandscape {
                    HStack(alignment: .top, spacing: DesignTokens.spacingMD) {
                        ScrollView { quickStartHeroCompact.padding(.top, 4) }.frame(maxWidth: 280)
                        ScrollView { hostListContent.padding(.top, 4) }
                    }
                    .padding(.horizontal, DesignTokens.spacingMD)
                } else {
                    ScrollView {
                        VStack(spacing: DesignTokens.spacingMD) {
                            quickStartHero
                            hostListContent
                        }
                        .padding(DesignTokens.spacingMD).frame(maxWidth: DesignTokens.maxReadableWidth).frame(maxWidth: .infinity)
                    }
                }
            }
            .background(DesignTokens.background).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    HStack(spacing: 8) {
                        Image("BrandLogo").resizable().scaledToFit().frame(width: 24, height: 24)
                            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                        Text("SmartDisplay").font(.system(size: 18, weight: .bold)).foregroundStyle(DesignTokens.textPrimary)
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        IosHaptics.tap(enabled: container.settings.hapticsEnabled); showAddSheet = true
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
                AddHostSheet { newHost in savedHosts = container.hostRepository.saveHost(newHost); probeSavedHosts() }
            }
            .fullScreenCover(item: $selectedHost) { host in
                StreamSessionView(host: host).environmentObject(container)
            }
            .onAppear {
                savedHosts = container.hostRepository.loadSavedHosts(); probeSavedHosts()
                container.discoveryService.startDiscovery { hosts in discoveredHosts = hosts }
            }
            .refreshable { probeSavedHosts() }
            .onDisappear { container.discoveryService.stopDiscovery() }
        }
    }

    /// Contenido de lista de equipos con chips de filtrado Material 3
    private var hostListContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Equipos (\(allHosts.count))").font(DesignTokens.sectionFont).foregroundStyle(DesignTokens.textPrimary)
                Spacer()
                HStack(spacing: 6) {
                    ForEach(HostFilter.allCases) { filter in
                        Button(filter.rawValue) {
                            IosHaptics.selection(enabled: container.settings.hapticsEnabled); selectedFilter = filter
                        }
                        .font(.system(size: 12, weight: selectedFilter == filter ? .semibold : .regular))
                        .foregroundStyle(selectedFilter == filter ? DesignTokens.onAccent : DesignTokens.textSecondary)
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(selectedFilter == filter ? DesignTokens.accent : DesignTokens.surfaceHigh, in: Capsule())
                    }
                }
            }

            if allHosts.isEmpty { emptyStateView } else {
                ForEach(allHosts) { host in
                    HostRowView(
                        host: host, isSaved: savedHosts.contains { $0.id == host.id },
                        onWakeFeedback: { msg in feedbackMessage = msg },
                        onDelete: { savedHosts = container.hostRepository.removeHost(id: host.id) },
                        onConnect: { selectedHost = host }
                    )
                }
            }
        }
    }

    /// Botón hero circular 84pt en modo vertical
    private var quickStartHero: some View {
        VStack(spacing: 8) {
            heroButton(size: 84)
            Text(primaryHost != nil ? "Conectar Escritorio" : "Añadir Equipo")
                .font(.system(size: 16, weight: .semibold)).foregroundStyle(DesignTokens.textPrimary)
            Text(primarySubtitle).font(DesignTokens.captionFont).foregroundStyle(DesignTokens.textSecondary)
                .lineLimit(1).truncationMode(.tail)
        }
        .padding(.vertical, 6)
    }

    /// Botón hero compacto para modo horizontal (Landscape)
    private var quickStartHeroCompact: some View {
        VStack(spacing: 8) {
            heroButton(size: 64)
            Text(primaryHost != nil ? "Conectar" : "Añadir")
                .font(.system(size: 14, weight: .semibold)).foregroundStyle(DesignTokens.textPrimary)
            Text(primarySubtitle).font(DesignTokens.captionFont).foregroundStyle(DesignTokens.textSecondary)
                .lineLimit(1).truncationMode(.tail)
        }
        .padding(12).frame(maxWidth: .infinity)
        .background(DesignTokens.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func heroButton(size: CGFloat) -> some View {
        Button {
            IosHaptics.success(enabled: container.settings.hapticsEnabled)
            if let target = primaryHost { selectedHost = target } else { showAddSheet = true }
        } label: {
            ZStack {
                Circle().fill(DesignTokens.accent).frame(width: size, height: size)
                Image(systemName: primaryHost != nil ? "play.fill" : "plus")
                    .font(.system(size: size * 0.35, weight: .semibold))
                    .foregroundStyle(DesignTokens.onAccent).offset(x: primaryHost != nil ? 2 : 0)
            }
        }
        .buttonStyle(IosPressButtonStyle())
    }

    private var primarySubtitle: String {
        guard let host = primaryHost else { return "Detecta tu PC para comenzar" }
        let raw = host.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let idx = raw.firstIndex(of: "(") { return "Listo en \(raw[..<idx].trimmingCharacters(in: .whitespaces))" }
        return "Listo en \(raw)"
    }

    private var emptyStateView: some View {
        VStack(spacing: 8) {
            Image(systemName: "wifi").font(.title).foregroundStyle(DesignTokens.textSecondary)
            Text("Buscando equipos en tu Wi-Fi").font(DesignTokens.sectionFont)
            Text("Inicia SmartDisplay en tu PC o pulsa +").font(DesignTokens.captionFont).foregroundStyle(DesignTokens.textSecondary)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 20).enterpriseSurface()
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
