import SwiftUI

/// Barra de entrada de texto y teclas de control especiales para la sesión remota de SmartDisplay iOS.
///
/// Qué hace: permite escribir texto libre (con inyección instantánea en el PC anfitrión)
/// y disparar teclas físicas de control (`Esc`, `Tab`, `Del`, `Enter`, `Ctrl+C`, `Ctrl+V`, `Win`, flechas).
/// Integra foco automático (`@FocusState`) para desplegar el teclado táctil nativo de iOS al abrirse.
struct StreamKeyboardBar: View {

    @EnvironmentObject private var container: AppContainer
    @State private var draftText = ""
    @FocusState private var isFieldFocused: Bool

    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: DesignTokens.spacingXS) {
            // Fila de teclas especiales de navegación y control
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: DesignTokens.spacingXS) {
                    specialKey("Esc", code: "escape")
                    specialKey("Tab", code: "tab")
                    specialKey("Del", code: "delete")
                    specialKey("Ctrl+C", code: "ctrl_c")
                    specialKey("Ctrl+V", code: "ctrl_v")
                    specialKey("Win", code: "win")
                    specialKey("Alt", code: "alt")
                    specialKey("↑", code: "up")
                    specialKey("↓", code: "down")
                    specialKey("←", code: "left")
                    specialKey("→", code: "right")
                    specialKey("⌫", code: "backspace")
                    specialKey("↵", code: "enter")
                }
            }

            // Fila de entrada de texto con foco automático nativo
            HStack(spacing: DesignTokens.spacingSM) {
                TextField("Escribe texto en el PC...", text: $draftText)
                    .textFieldStyle(.roundedBorder)
                    .focused($isFieldFocused)
                    .submitLabel(.send)
                    .onSubmit { sendText() }

                Button("Enviar") { sendText() }
                    .buttonStyle(.borderedProminent)
                    .tint(DesignTokens.accent)
                    .disabled(draftText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                Button {
                    isFieldFocused = false
                    IosHaptics.selection(enabled: container.settings.hapticsEnabled)
                    onDismiss()
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(8)
                        .background(Color.white.opacity(0.16), in: Circle())
                }
            }
        }
        .padding(10)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                isFieldFocused = true
            }
        }
    }

    private func specialKey(_ title: String, code: String) -> some View {
        Button(title) {
            IosHaptics.tap(enabled: container.settings.hapticsEnabled)
            container.inputDispatcher.sendSpecialKey(code)
        }
        .font(DesignTokens.captionFont.weight(.medium))
        .foregroundStyle(.white)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.white.opacity(0.18), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
        .buttonStyle(IosPressButtonStyle())
    }

    private func sendText() {
        let trimmed = draftText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        container.inputDispatcher.sendText(trimmed)
        IosHaptics.success(enabled: container.settings.hapticsEnabled)
        draftText = ""
    }
}
