import SwiftUI

/// Tarjeta de equipo SmartDisplay AI para iOS (Material 3 Desaturado sin solapamientos).
///
/// Qué hace: renderiza la información de cada equipo en una tarjeta jerárquica de dos niveles:
/// - Fila superior: icono de PC, nombre limpio (sin IP redundante entre paréntesis) y pastilla de estado.
/// - Fila inferior: acciones auxiliares (Wake-on-LAN, eliminar) y botón principal 'Conectar'.
/// Por qué: garantiza que en cualquier pantalla de iPhone el nombre nunca se divida en sílabas verticales.
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

    /// Extrae un nombre amigable sin la dirección IP repetida entre paréntesis.
    private var cleanDisplayName: String {
        let raw = host.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let parenIdx = raw.firstIndex(of: "(") {
            let prefix = String(raw[..<parenIdx]).trimmingCharacters(in: .whitespaces)
            return prefix.isEmpty ? raw : prefix
        }
        return raw.isEmpty ? "PC Desconocido" : raw
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Fila superior: Icono + Información de Host + Estado
            HStack(alignment: .center, spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(isOnline ? DesignTokens.statusOnline.opacity(0.12) : DesignTokens.surfaceHigh)
                        .frame(width: 44, height: 44)

                    Image(systemName: "desktopcomputer")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(isOnline ? DesignTokens.statusOnline : DesignTokens.textSecondary)
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text(cleanDisplayName)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(DesignTokens.textPrimary)
                        .lineLimit(1)
                        .truncationMode(.tail)

                    Text("\(host.address):\(host.companionPort)")
                        .font(DesignTokens.monoFont)
                        .foregroundStyle(DesignTokens.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .layoutPriority(1)

                // Pastilla de estado 'En línea' / 'Sin señal'
                HStack(spacing: 5) {
                    Circle()
                        .fill(isOnline ? DesignTokens.statusOnline : DesignTokens.statusError)
                        .frame(width: 7, height: 7)

                    Text(isOnline ? "En línea" : "Sin señal")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(isOnline ? DesignTokens.statusOnline : DesignTokens.statusError)
                }
                .padding(.horizontal, 9)
                .padding(.vertical, 4)
                .background(isOnline ? DesignTokens.statusOnlineBg : DesignTokens.statusErrorBg, in: Capsule())
                .fixedSize(horizontal: true, vertical: true)
            }

            Divider()
                .overlay(DesignTokens.border.opacity(0.4))

            // Fila inferior: Acciones de encendido, borrado y conexión
            HStack(spacing: 10) {
                if let mac = host.macAddress, !mac.isEmpty {
                    Button {
                        container.wakeOnLanService.sendWakePacket(macAddress: mac) { ok in
                            let msg = ok ? "Wake-on-LAN enviado a \(cleanDisplayName)." : "MAC inválida."
                            onWakeFeedback(msg)
                        }
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "power")
                            Text("Encender")
                        }
                        .font(.system(size: 12, weight: .medium))
                    }
                    .buttonStyle(.bordered)
                }

                if isSaved {
                    Button(role: .destructive) {
                        onDelete()
                    } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 13, weight: .medium))
                    }
                    .buttonStyle(.bordered)
                }

                NavigationLink {
                    HostAppsView(host: host)
                } label: {
                    Image(systemName: "square.grid.2x2")
                        .font(.system(size: 13, weight: .medium))
                }
                .buttonStyle(.bordered)

                Spacer()

                Button {
                    IosHaptics.tap(enabled: container.settings.hapticsEnabled)
                    onConnect()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "play.fill")
                            .font(.system(size: 11, weight: .bold))
                        Text("Conectar")
                            .font(.system(size: 14, weight: .semibold))
                    }
                    .foregroundStyle(DesignTokens.onAccent)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(DesignTokens.accent, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(IosPressButtonStyle())
            }
        }
        .padding(14)
        .background(DesignTokens.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(isOnline ? DesignTokens.accent.opacity(0.35) : DesignTokens.border, lineWidth: 1)
        )
    }
}
