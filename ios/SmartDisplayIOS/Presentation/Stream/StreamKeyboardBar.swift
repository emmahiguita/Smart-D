import SwiftUI

/// Barra de entrada de texto y teclas de control especiales para la sesión remota de SmartDisplay iOS.
///
/// Qué hace: permite enviar texto libre (sincronizando el portapapeles del PC con Ctrl+V automático)
/// y disparar teclas físicas de control (`Esc`, `Tab`, `Backspace`, `Enter`, `Ctrl+C`, `Ctrl+V`, `Win`).
/// Cómo funciona: envía eventos JSON sobre el WebSocket `SmartDisplayBusClient` (`input_text` e `input_key`),
/// con soporte háptico nativo y campo de texto con botón de envío y tecla Intro interactiva.
/// Por qué: cumple el principio de Responsabilidad Única (SRP) aislando la interfaz de teclado del lienzo de video.
struct StreamKeyboardBar: View {

    @EnvironmentObject private var container: AppContainer

    @State private var draftText = ""

    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: DesignTokens.spacingXS) {
            // Fila de teclas especiales de control
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: DesignTokens.spacingXS) {
                    specialKey("Esc", code: "escape")
                    specialKey("Tab", code: "tab")
                    specialKey("⌫", code: "backspace")
                    specialKey("↵", code: "enter")
                    specialKey("Ctrl+C", code: "ctrl_c")
                    specialKey("Ctrl+V", code: "ctrl_v")
                    specialKey("Win", code: "win")
                }
            }

            // Fila de texto rápido
            HStack(spacing: DesignTokens.spacingSM) {
                TextField("Escribe texto para el PC...", text: $draftText)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.send)
                    .onSubmit { sendText() }

                Button("Enviar") { sendText() }
                    .buttonStyle(.borderedProminent)
                    .disabled(draftText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                Button {
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
