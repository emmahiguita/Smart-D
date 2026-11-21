import SwiftUI

/// Barra de control superior flotante de dos islas para SmartDisplay iOS (Material 3).
///
/// Qué hace: separa telemetría/estado (isla izquierda) de los botones de interacción (isla derecha).
/// Cómo funciona: fija una altura exacta de 36pt con material translúcido `.ultraThinMaterial` en cápsula nativa,
/// evitando cualquier deformación elíptica en pantallas de cualquier tamaño y orientación.
/// Por qué existe: cumple el Principio de Responsabilidad Única (SRP) aislando la telemetría del resto de vistas.
struct StreamTopBar: View {

    @EnvironmentObject private var container: AppContainer

    let busConnected: Bool
    let telemetry: PcTelemetrySnapshot
    let zoomPercent: Int
    let isMoveMode: Bool
    let isKeyboardOpen: Bool
    let isMouseHelperOpen: Bool
    let isVoiceActive: Bool

    let onZoomIn: () -> Void
    let onZoomOut: () -> Void
    let onResetZoom: () -> Void
    let onToggleMoveMode: () -> Void
    let onToggleKeyboard: () -> Void
    let onToggleMouseHelper: () -> Void
    let onToggleVoice: () -> Void
    let onCollapse: () -> Void
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            // Isla izquierda: Telemetría de CPU y zoom en vivo
            HStack(spacing: 6) {
                Circle()
                    .fill(busConnected ? DesignTokens.statusOnline : DesignTokens.statusError)
                    .frame(width: 8, height: 8)

                Text(telemetryText)
                    .font(DesignTokens.monoFont)
                    .foregroundStyle(.white)
                    .lineLimit(1)
            }
            .padding(.horizontal, 10)
            .frame(height: 36)
            .background(.ultraThinMaterial, in: Capsule())

            Spacer(minLength: 4)

            // Isla derecha: Teclado, Micrófono, Menú de herramientas y Cerrar
            HStack(spacing: 2) {
                controlBtn(isKeyboardOpen ? "keyboard.chevron.compact.down" : "keyboard") {
                    onToggleKeyboard()
                }

                controlBtn(isVoiceActive ? "mic.fill" : "mic.slash") {
                    onToggleVoice()
                }

                Menu {
                    Button(isMouseHelperOpen ? "Ocultar barra de ratón" : "Barra de ratón y clics", systemImage: "cursorarrow.click") {
                        onToggleMouseHelper()
                    }
                    Button(isMoveMode ? "Desactivar modo mover" : "Activar modo mover pantalla", systemImage: "hand.draw") {
                        onToggleMoveMode()
                    }
                    Divider()
                    Button("Acercar (+)", systemImage: "plus.magnifyingglass") { onZoomIn() }
                    Button("Alejar (-)", systemImage: "minus.magnifyingglass") { onZoomOut() }
                    Button("Restablecer zoom (100%)", systemImage: "arrow.up.left.and.arrow.down.right") { onResetZoom() }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                }

                controlBtn("chevron.up") { onCollapse() }
                controlBtn("xmark") { onClose() }
            }
            .padding(.horizontal, 6)
            .frame(height: 36)
            .background(.ultraThinMaterial, in: Capsule())
        }
        .frame(height: 38)
    }

    private var telemetryText: String {
        telemetry.hasData ? "CPU \(telemetry.cpuPercent)% · \(zoomPercent)%" : "\(zoomPercent)%"
    }

    private func controlBtn(_ icon: String, action: @escaping () -> Void) -> some View {
        Button {
            IosHaptics.selection(enabled: container.settings.hapticsEnabled)
            action()
        } label: {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
        }
        .buttonStyle(IosPressButtonStyle())
    }
}
