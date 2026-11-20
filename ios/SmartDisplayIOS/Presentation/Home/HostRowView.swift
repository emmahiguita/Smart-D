import SwiftUI

/// Tarjeta de equipo SmartDisplay (Diseño Material 3 adaptativo sin solapamientos).
///
/// Qué hace: renderiza la información del equipo en un diseño jerárquico de dos niveles
/// (cabecera con icono, nombre, IP y pastilla de estado; y barra inferior de acciones con encendido,
/// borrado y botón principal 'Conectar').
/// Por qué: elimina completamente el solapamiento horizontal de iconos y textos en pantallas de iPhone.
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
        VStack(alignment: .leading, spacing: 14) {
            // Fila superior: Icono + Información del PC + Pastilla de Estado
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
                    Text(host.name)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(DesignTokens.textPrimary)
                        .lineLimit(1)

                    Text("\(host.address) · Puerto \(host.companionPort)")
                        .font(DesignTokens.monoFont)
                        .foregroundStyle(DesignTokens.textSecondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                // Pastilla de estado aislada a la derecha
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
            }

            // Separador sutil
            Divider()
                .overlay(DesignTokens.border.opacity(0.4))

            // Fila inferior: Barra de acciones claras sin interferencia
            HStack(spacing: 10) {
                if let mac = host.macAddress, !mac.isEmpty {
                    Button {
                        container.wakeOnLanService.sendWakePacket(macAddress: mac) { ok in
                            let msg = ok ? "Wake-on-LAN enviado a \(host.name)." : "MAC inválida."
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
