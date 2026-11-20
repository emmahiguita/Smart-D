import SwiftUI

/// Pantalla principal de equipos anfitriones de SmartDisplay AI para iOS.
///
/// Qué hace: combina los equipos persistidos en `HostRepository` con los descubiertos en vivo
/// por Bonjour (`MdnsDiscoveryService`), permitiendo conectar, despertar vía Wake-on-LAN,
/// eliminar equipos guardados y navegar a Transferencia Wi-Fi o Manual.
struct HostListView: View {

    @EnvironmentObject private var container: AppContainer
    @State private var discoveredHosts: [HostDevice] = []
    @State private var savedHosts: [HostDevice] = []
    @State private var selectedHost: HostDevice?
    @State private var showAddSheet = false
    @State private var feedbackMessage: String?

    /// Combina equipos guardados y descubiertos en vivo sin duplicar por IP.
    private var allHosts: [HostDevice] {
        let savedIps = Set(savedHosts.map(\.address))
        let uniqueDiscovered = discoveredHosts.filter { !savedIps.contains($0.address) }
        return savedHosts + uniqueDiscovered
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: DesignTokens.spacingMD) {
                    if let message = feedbackMessage {
                        Text(message)
                            .font(DesignTokens.captionFont)
                            .foregroundStyle(DesignTokens.textSecondary)
                            .enterpriseSurface()
                    }

                    if allHosts.isEmpty {
                        emptyStateView
                    } else {
                        ForEach(allHosts) { host in
                            hostRow(host)
                        }
                    }
                }
                .padding(DesignTokens.spacingMD)
                .frame(maxWidth: DesignTokens.maxReadableWidth)
                .frame(maxWidth: .infinity)
            }
            .background(DesignTokens.background)
            .navigationTitle("SmartDisplay AI")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    NavigationLink { FileTransferView() } label: {
                        Image(systemName: "arrow.up.arrow.down.circle")
                    }
                    NavigationLink { ManualView() } label: {
                        Image(systemName: "book")
                    }
                    NavigationLink { SettingsView() } label: {
                        Image(systemName: "gearshape")
                    }
                    Button {
                        IosHaptics.tap(enabled: container.settings.hapticsEnabled)
                        showAddSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showAddSheet) {
                AddHostSheet { newHost in
                    savedHosts = container.hostRepository.saveHost(newHost)
                }
            }
            .fullScreenCover(item: $selectedHost) { host in
                StreamSessionView(host: host)
                    .environmentObject(container)
            }
            .onAppear {
                savedHosts = container.hostRepository.loadSavedHosts()
                probeSavedHosts()
                container.discoveryService.startDiscovery { hosts in
                    discoveredHosts = hosts
                }
            }
            .refreshable {
                probeSavedHosts()
            }
            .onDisappear {
                container.discoveryService.stopDiscovery()
            }
        }
    }

    /// Comprueba por sonda TCP real si cada equipo guardado responde en su puerto Companion.
    private func probeSavedHosts() {
        let port = container.settings.companionPort > 0 ? container.settings.companionPort : 3001
        for idx in savedHosts.indices {
            let host = savedHosts[idx]
            container.wakeOnLanService.probeTcpReachability(host: host.address, port: port) { online in
                if let pos = savedHosts.firstIndex(where: { $0.id == host.id }) {
                    withAnimation(IosMotion.snappy) {
                        savedHosts[pos].status = online ? .online : .offline
                    }
                }
            }
        }
    }

    /// Fila plana empresarial para cada PC disponible (con WoL, Conectar y Eliminar si está guardado).
    private func hostRow(_ host: HostDevice) -> some View {
        let isSaved = savedHosts.contains { $0.id == host.id }
        return HStack(spacing: DesignTokens.spacingSM) {
            Image(systemName: "desktopcomputer")
                .font(.title2)
                .foregroundStyle(host.status == .online ? DesignTokens.statusOnline : DesignTokens.textSecondary)

            VStack(alignment: .leading, spacing: 2) {
                Text(host.name)
                    .font(DesignTokens.sectionFont)
                    .foregroundStyle(DesignTokens.textPrimary)
                Text("\(host.address):\(host.companionPort)")
                    .font(DesignTokens.monoFont)
                    .foregroundStyle(DesignTokens.textSecondary)
            }

            Spacer()

            if let mac = host.macAddress, !mac.isEmpty {
                Button {
                    container.wakeOnLanService.sendWakePacket(macAddress: mac) { ok in
                        feedbackMessage = ok ? "Wake-on-LAN enviado a \(host.name)." : "MAC inválida."
                    }
                } label: {
                    Image(systemName: "power")
                }
                .buttonStyle(.bordered)
            }

            if isSaved {
                Button(role: .destructive) {
                    savedHosts = container.hostRepository.removeHost(id: host.id)
                } label: {
                    Image(systemName: "trash")
                }
                .buttonStyle(.bordered)
            }

            Button("Conectar") {
                selectedHost = host
            }
            .buttonStyle(.borderedProminent)
        }
        .enterpriseSurface()
    }

    private var emptyStateView: some View {
        VStack(spacing: DesignTokens.spacingSM) {
            Image(systemName: "wifi")
                .font(.largeTitle)
                .foregroundStyle(DesignTokens.textSecondary)
            Text("Buscando equipos SmartDisplay en tu Wi-Fi")
                .font(DesignTokens.sectionFont)
            Text("Asegúrate de tener SmartDisplay Host activo en tu PC o pulsa + para añadir la dirección IP.")
                .font(DesignTokens.bodyFont)
                .foregroundStyle(DesignTokens.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
        .enterpriseSurface()
    }
}

/// Hoja modal limpia para registrar un equipo SmartDisplay con persistencia automática.
private struct AddHostSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var address = ""
    @State private var macAddress = ""

    let onSave: (HostDevice) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("Datos del equipo SmartDisplay") {
                    TextField("Nombre (ej. PC Principal)", text: $name)
                    TextField("Dirección IP (ej. 192.168.0.10)", text: $address)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                    TextField("MAC para Wake-on-LAN (opcional)", text: $macAddress)
                        .autocorrectionDisabled()
                }
            }
            .navigationTitle("Añadir equipo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        let cleanIp = address.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !cleanIp.isEmpty else { return }
                        let finalName = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        onSave(HostDevice(
                            name: finalName.isEmpty ? cleanIp : finalName,
                            address: cleanIp,
                            macAddress: macAddress.isEmpty ? nil : macAddress
                        ))
                        dismiss()
                    }
                    .disabled(address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
