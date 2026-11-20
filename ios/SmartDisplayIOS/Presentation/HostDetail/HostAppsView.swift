import SwiftUI

/// Pantalla de detalle del PC y aplicaciones remotas (paridad con `DeviceDetailActivity` + `AppView` Android).
///
/// Qué hace: consulta en vivo `/serverinfo` y `/applist` del PC anfitrión vía `HostApiClient`,
/// actualiza automáticamente la MAC para Wake-on-LAN, muestra estado de emparejamiento y permite
/// iniciar proyección, lanzar una app o cancelar una sesión remota activa.
struct HostAppsView: View {

    @EnvironmentObject private var container: AppContainer
    let host: HostDevice

    @State private var serverInfo: HostServerInfo?
    @State private var apps: [RemoteAppItem] = []
    @State private var isLoading = true
    @State private var statusMessage: String?
    @State private var showStream = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DesignTokens.spacingMD) {
                hostSummaryCard
                appsSection
            }
            .padding(DesignTokens.spacingMD)
            .frame(maxWidth: DesignTokens.maxReadableWidth)
            .frame(maxWidth: .infinity)
        }
        .background(DesignTokens.background)
        .navigationTitle(serverInfo?.hostname ?? host.name)
        .navigationBarTitleDisplayMode(.inline)
        .fullScreenCover(isPresented: $showStream) {
            StreamSessionView(host: host)
                .environmentObject(container)
        }
        .task { await loadHostData() }
        .refreshable { await loadHostData() }
    }

    /// Bloque de metadatos reales del PC anfitrión y acciones rápidas (Proyectar / WoL / Cancelar).
    private var hostSummaryCard: some View {
        VStack(alignment: .leading, spacing: DesignTokens.spacingSM) {
            HStack {
                Image(systemName: "desktopcomputer")
                    .font(.title2)
                    .foregroundStyle(serverInfo != nil ? DesignTokens.statusOnline : DesignTokens.textSecondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(serverInfo?.hostname ?? host.name).font(DesignTokens.sectionFont)
                    Text("\(host.address) · HTTP \(host.port) · Bus \(container.settings.companionPort)")
                        .font(DesignTokens.monoFont)
                        .foregroundStyle(DesignTokens.textSecondary)
                }
                Spacer()
            }

            if let mac = serverInfo?.macAddress ?? host.macAddress {
                Text("MAC: \(mac)")
                    .font(DesignTokens.monoFont)
                    .foregroundStyle(DesignTokens.textSecondary)
            }

            if let msg = statusMessage {
                Text(msg).font(DesignTokens.captionFont).foregroundStyle(DesignTokens.textSecondary)
            }

            HStack(spacing: DesignTokens.spacingSM) {
                Button("Proyectar ahora") {
                    IosHaptics.success(enabled: container.settings.hapticsEnabled)
                    showStream = true
                }
                .buttonStyle(.borderedProminent)

                if let mac = serverInfo?.macAddress ?? host.macAddress, !mac.isEmpty {
                    Button("Encender (WoL)") {
                        container.wakeOnLanService.sendWakePacket(macAddress: mac) { ok in
                            statusMessage = ok ? "Paquete Wake-on-LAN enviado." : "Error enviando WoL."
                        }
                    }
                    .buttonStyle(.bordered)
                }

                Button("Vincular PIN") {
                    let pin = container.settings.voicePin.isEmpty ? "9999" : container.settings.voicePin
                    Task {
                        let ok = await container.sunshinePairService.submitPin(host: host.address, pin: pin)
                        statusMessage = ok ? "PIN \(pin) vinculado en puerto 47990." : "Sin sesión pendiente en 47990."
                    }
                }
                .buttonStyle(.bordered)

                if (serverInfo?.currentAppId ?? 0) != 0 {
                    Button("Cerrar app", role: .destructive) {
                        Task {
                            let ok = await container.hostApiClient.cancelRemoteSession(host: host.address, port: host.port)
                            statusMessage = ok ? "Sesión remota cerrada." : "No se pudo cerrar la sesión."
                        }
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .enterpriseSurface()
    }

    /// Lista de aplicaciones reales obtenidas desde `/applist` del PC anfitrión.
    private var appsSection: some View {
        VStack(alignment: .leading, spacing: DesignTokens.spacingSM) {
            Text("Aplicaciones remotas (\(apps.count))").font(DesignTokens.sectionFont)

            if isLoading {
                ProgressView("Consultando PC anfitrión...")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                    .enterpriseSurface()
            } else if apps.isEmpty {
                Text("No se recibieron aplicaciones vía HTTP \(host.port). Puedes proyectar directamente usando el botón \"Proyectar ahora\".")
                    .font(DesignTokens.bodyFont)
                    .foregroundStyle(DesignTokens.textSecondary)
                    .enterpriseSurface()
            } else {
                ForEach(apps) { app in
                    Button {
                        IosHaptics.tap(enabled: container.settings.hapticsEnabled)
                        container.logStore.log("Lanzando app remota: \(app.title)")
                        showStream = true
                    } label: {
                        HStack {
                            Image(systemName: "app.dashed").foregroundStyle(DesignTokens.accent)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(app.title).font(DesignTokens.bodyFont).foregroundStyle(DesignTokens.textPrimary)
                                if app.hdrSupported {
                                    Text("HDR compatible").font(DesignTokens.captionFont).foregroundStyle(DesignTokens.textSecondary)
                                }
                            }
                            Spacer()
                            Image(systemName: "play.circle.fill").foregroundStyle(DesignTokens.accent)
                        }
                        .enterpriseSurface()
                    }
                    .buttonStyle(IosPressButtonStyle())
                }
            }
        }
    }

    /// Consulta `/serverinfo` y `/applist` en paralelo y persiste la MAC descubierta automáticamente.
    private func loadHostData() async {
        isLoading = true
        defer { isLoading = false }

        do {
            let info = try await container.hostApiClient.fetchServerInfo(host: host.address, port: host.port)
            serverInfo = info
            container.logStore.log("ServerInfo OK: \(info.hostname) (MAC: \(info.macAddress ?? "n/a"))")

            // Auto-enriquece el equipo guardado con la MAC real para Wake-on-LAN futuro
            if let mac = info.macAddress, host.macAddress == nil {
                var enriched = host
                enriched.name = info.hostname
                enriched.macAddress = mac
                _ = container.hostRepository.saveHost(enriched)
            }

            if let list = try? await container.hostApiClient.fetchAppList(host: host.address, port: host.port) {
                apps = list
            }
        } catch {
            statusMessage = "Host HTTP \(host.port) sin respuesta directa; puedes conectar vía Bus \(container.settings.companionPort)."
        }
    }
}
