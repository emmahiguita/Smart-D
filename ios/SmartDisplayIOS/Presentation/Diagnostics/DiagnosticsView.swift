import SwiftUI

/// Pantalla de Diagnóstico y Logs en vivo (paridad con `dialog_diagnostics` y `dialog_logs` Android).
///
/// Qué hace: muestra estado real de la red Wi-Fi (`en0`), puertos activos y el registro
/// cronológico de eventos (`AppLogStore`) con opción de copiar o limpiar.
struct DiagnosticsView: View {

    @EnvironmentObject private var container: AppContainer
    @EnvironmentObject private var logStore: AppLogStore
    @State private var wifiIp = WifiFileTransferServer.localWifiIPv4Address() ?? "Sin Wi-Fi"
    @State private var copied = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DesignTokens.spacingMD) {
                VStack(alignment: .leading, spacing: DesignTokens.spacingSM) {
                    Text("Estado del sistema").font(DesignTokens.sectionFont)
                    metricRow("IP Wi-Fi local (en0)", value: wifiIp)
                    metricRow("Puerto Bus Companion", value: "\(container.settings.companionPort)")
                    metricRow("Puerto Archivos Wi-Fi", value: "\(container.settings.fileTransferPort)")
                    metricRow("Puerto Voz UDP", value: "\(VoiceUdpService.hostPort)")
                    metricRow("Equipos guardados", value: "\(container.hostRepository.loadSavedHosts().count)")
                }
                .enterpriseSurface()

                VStack(alignment: .leading, spacing: DesignTokens.spacingSM) {
                    HStack {
                        Text("Registro de eventos (\(logStore.entries.count))")
                            .font(DesignTokens.sectionFont)
                        Spacer()
                        Button(copied ? "Copiado" : "Copiar") {
                            UIPasteboard.general.string = logStore.entries.joined(separator: "\n")
                            IosHaptics.success(enabled: container.settings.hapticsEnabled)
                            copied = true
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
                        }
                        .font(DesignTokens.captionFont)
                        Button("Limpiar") { logStore.clear() }
                            .font(DesignTokens.captionFont)
                            .foregroundStyle(DesignTokens.statusError)
                    }

                    if logStore.entries.isEmpty {
                        Text("Sin eventos registrados todavía.")
                            .font(DesignTokens.captionFont)
                            .foregroundStyle(DesignTokens.textSecondary)
                    } else {
                        ForEach(Array(logStore.entries.reversed().enumerated()), id: \.offset) { _, line in
                            Text(line)
                                .font(DesignTokens.monoFont)
                                .foregroundStyle(DesignTokens.textSecondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                .enterpriseSurface()
            }
            .padding(DesignTokens.spacingMD)
            .frame(maxWidth: DesignTokens.maxReadableWidth)
            .frame(maxWidth: .infinity)
        }
        .background(DesignTokens.background)
        .navigationTitle("Diagnóstico")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { wifiIp = WifiFileTransferServer.localWifiIPv4Address() ?? "Sin Wi-Fi" }
    }

    private func metricRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label).font(DesignTokens.bodyFont).foregroundStyle(DesignTokens.textSecondary)
            Spacer()
            Text(value).font(DesignTokens.monoFont).foregroundStyle(DesignTokens.textPrimary)
        }
    }
}
