import SwiftUI

/// Componente de fila de equipo SmartDisplay (paridad visual y funcional 1:1 con `HostItemView` de Android).
///
/// Qué hace: renderiza la tarjeta del PC con su estado en vivo (`.online` / `.offline`),
/// dirección IP y puerto del Bus, botón Wake-on-LAN para arranque remoto, botón de borrado
/// y botón prominente 'Conectar' con respuesta háptica.
/// Por qué: cumple el principio de Responsabilidad Única (SRP) reduciendo la complejidad de `HostListView`.
struct HostRowView: View {

    @EnvironmentObject private var container: AppContainer

    let host: HostDevice
    let isSaved: Bool
    let onWakeFeedback: (String) -> Void
    let onDelete: () -> Void
    let onConnect: () -> Void

    private var isOnline: Bool {
        host.status == .online
    }

    var body: some View {
        HStack(spacing: DesignTokens.spacingSM) {
            NavigationLink {
                HostAppsView(host: host)
            } label: {
                HStack(spacing: DesignTokens.spacingSM) {
                    Image(systemName: "desktopcomputer")
                        .font(.title2)
                        .foregroundStyle(isOnline ? DesignTokens.statusOnline : DesignTokens.textSecondary)

                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text(host.name)
                                .font(DesignTokens.sectionFont)
                                .foregroundStyle(DesignTokens.textPrimary)

                            Text(isOnline ? "En línea" : "Sin señal")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(isOnline ? DesignTokens.statusOnline : DesignTokens.statusError)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(isOnline ? DesignTokens.statusOnlineBg : DesignTokens.statusErrorBg, in: Capsule())
                        }

                        Text("\(host.address):\(host.companionPort)")
                            .font(DesignTokens.monoFont)
                            .foregroundStyle(DesignTokens.textSecondary)
                    }
                }
            }
            .buttonStyle(.plain)

            Spacer()

            if let mac = host.macAddress, !mac.isEmpty {
                Button {
                    container.wakeOnLanService.sendWakePacket(macAddress: mac) { ok in
                        let msg = ok ? "Wake-on-LAN enviado a \(host.name)." : "MAC inválida."
                        onWakeFeedback(msg)
                    }
                } label: {
                    Image(systemName: "power")
                }
                .buttonStyle(.bordered)
            }

            if isSaved {
                Button(role: .destructive) {
                    onDelete()
                } label: {
                    Image(systemName: "trash")
                }
                .buttonStyle(.bordered)
            }

            Button("Conectar") {
                IosHaptics.tap(enabled: container.settings.hapticsEnabled)
                onConnect()
            }
            .buttonStyle(.borderedProminent)
        }
        .enterpriseSurface()
    }
}
