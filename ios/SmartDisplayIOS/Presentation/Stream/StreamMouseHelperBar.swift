import SwiftUI

/// Barra flotante de herramientas de ratón para SmartDisplay AI en iOS (Control remoto desktop-class).
///
/// Qué hace: ofrece accesos rápidos para clic primario, clic secundario, doble clic,
/// bloqueo de arrastre (drag-lock para selección de texto o mover ventanas) y rueda de scroll.
/// Por qué: permite control preciso en Windows sin depender exclusivamente de gestos táctiles.
struct StreamMouseHelperBar: View {

    @EnvironmentObject private var container: AppContainer

    @Binding var isDragLocked: Bool
    let currentPoint: CGPoint
    let viewportSize: CGSize
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            // Clic primario (izquierdo)
            mouseActionBtn(icon: "cursorarrow.click", title: "Clic") {
                container.inputDispatcher.sendTap(at: currentPoint, in: viewportSize)
            }

            // Clic secundario (derecho)
            mouseActionBtn(icon: "contextualmenu.and.cursor", title: "Der") {
                container.inputDispatcher.sendRightClick(at: currentPoint, in: viewportSize)
            }

            // Doble clic
            mouseActionBtn(icon: "cursorarrow.rays", title: "2x") {
                container.inputDispatcher.sendDoubleTap(at: currentPoint, in: viewportSize)
            }

            // Bloqueo de arrastre (MouseDown / MouseUp)
            Button {
                IosHaptics.selection(enabled: container.settings.hapticsEnabled)
                isDragLocked.toggle()
                if isDragLocked {
                    container.inputDispatcher.sendMouseDown(at: currentPoint, in: viewportSize)
                } else {
                    container.inputDispatcher.sendMouseUp(at: currentPoint, in: viewportSize)
                }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: isDragLocked ? "lock.fill" : "hand.draw")
                        .font(.system(size: 13, weight: .semibold))
                    Text(isDragLocked ? "Soltar" : "Arrastrar")
                        .font(.system(size: 11, weight: .medium))
                }
                .foregroundStyle(isDragLocked ? DesignTokens.onAccent : .white)
                .padding(.horizontal, 8)
                .frame(height: 28)
                .background(isDragLocked ? DesignTokens.accent : Color.white.opacity(0.18), in: Capsule())
            }
            .buttonStyle(IosPressButtonStyle())

            // Rueda de desplazamiento (Scroll)
            HStack(spacing: 2) {
                Button {
                    IosHaptics.tap(enabled: container.settings.hapticsEnabled)
                    container.inputDispatcher.sendScroll(deltaY: 120)
                } label: {
                    Image(systemName: "chevron.up")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 26, height: 28)
                }

                Button {
                    IosHaptics.tap(enabled: container.settings.hapticsEnabled)
                    container.inputDispatcher.sendScroll(deltaY: -120)
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 26, height: 28)
                }
            }
            .background(Color.white.opacity(0.15), in: Capsule())

            // Cerrar barra
            Button {
                IosHaptics.selection(enabled: container.settings.hapticsEnabled)
                if isDragLocked {
                    isDragLocked = false
                    container.inputDispatcher.sendMouseUp(at: currentPoint, in: viewportSize)
                }
                onDismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.85))
                    .frame(width: 24, height: 28)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .frame(height: 38)
        .background(.ultraThinMaterial, in: Capsule())
    }

    private func mouseActionBtn(icon: String, title: String, action: @escaping () -> Void) -> some View {
        Button {
            IosHaptics.tap(enabled: container.settings.hapticsEnabled)
            action()
        } label: {
            HStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .semibold))
                Text(title)
                    .font(.system(size: 11, weight: .medium))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .frame(height: 28)
            .background(Color.white.opacity(0.18), in: Capsule())
        }
        .buttonStyle(IosPressButtonStyle())
    }
}
