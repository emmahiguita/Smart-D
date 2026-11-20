import SwiftUI

/// Barra de control superior flotante estilo cápsula (Material 3) para la sesión remota de SmartDisplay iOS.
///
/// Qué hace: muestra telemetría en vivo (CPU / RAM / Zoom), estado del Bus WebSocket,
/// y botones de acción rápida para zoom, modo de arrastre, teclado virtual, intercomunicador y cierre.
/// Cómo funciona: utiliza material translúcido `.ultraThinMaterial` y respuesta háptica en cada interacción.
/// Por qué: cumple el principio de Responsabilidad Única (SRP) simplificando el árbol de vistas de `StreamSessionView`.
struct StreamTopBar: View {

    @EnvironmentObject private var container: AppContainer

    let busConnected: Bool
    let telemetry: PcTelemetrySnapshot
    let zoomPercent: Int
    let isMoveMode: Bool
    let isKeyboardOpen: Bool
    let isVoiceActive: Bool

    let onZoomIn: () -> Void
    let onZoomOut: () -> Void
    let onResetZoom: () -> Void
    let onToggleMoveMode: () -> Void
    let onToggleKeyboard: () -> Void
    let onToggleVoice: () -> Void
    let onCollapse: () -> Void
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: DesignTokens.spacingXS) {
            Circle()
                .fill(busConnected ? DesignTokens.statusOnline : DesignTokens.statusError)
                .frame(width: 8, height: 8)

            Text(telemetryText)
                .font(DesignTokens.monoFont)
                .foregroundStyle(.white)

            Spacer()

            controlBtn("minus.magnifyingglass") { onZoomOut() }
            controlBtn("plus.magnifyingglass") { onZoomIn() }
            controlBtn("arrow.up.left.and.arrow.down.right") { onResetZoom() }
            controlBtn(isMoveMode ? "hand.draw.fill" : "hand.draw") { onToggleMoveMode() }
            controlBtn(isKeyboardOpen ? "keyboard.chevron.compact.down" : "keyboard") { onToggleKeyboard() }
            controlBtn(isVoiceActive ? "mic.fill" : "mic.slash") { onToggleVoice() }
            controlBtn("chevron.up") { onCollapse() }
            controlBtn("xmark") { onClose() }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial, in: Capsule())
    }

    private var telemetryText: String {
        if telemetry.hasData {
            return "CPU \(telemetry.cpuPercent)% · RAM \(telemetry.ramPercent)% · \(zoomPercent)%"
        } else {
            return "\(zoomPercent)%"
        }
    }

    private func controlBtn(_ icon: String, action: @escaping () -> Void) -> some View {
        Button {
            IosHaptics.selection(enabled: container.settings.hapticsEnabled)
            action()
        } label: {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
        }
        .buttonStyle(IosPressButtonStyle())
    }
}
