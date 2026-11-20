import SwiftUI

/// Pantalla nativa de ajustes de SmartDisplay AI (Apple HIG Grouped Form).
///
/// Qué hace: observa directamente `@EnvironmentObject var settings: SettingsStore` para
/// garantizar reactividad inmediata en el selector de Tema Claro/Oscuro/Sistema, háptica,
/// auto-reconexión Wi-Fi, PIN UDP SHA-256 y puertos LAN.
struct SettingsView: View {

    @EnvironmentObject private var settings: SettingsStore

    var body: some View {
        Form {
            Section("Apariencia universal") {
                Picker("Tema", selection: Binding(
                    get: { settings.appearance },
                    set: {
                        IosHaptics.selection(enabled: settings.hapticsEnabled)
                        settings.appearance = $0
                    }
                )) {
                    ForEach(AppAppearanceMode.allCases) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
            }

            Section("Experiencia e interacción") {
                Toggle("Respuesta háptica táctil", isOn: $settings.hapticsEnabled)
                Toggle("Reconexión automática Wi-Fi", isOn: $settings.autoReconnect)
            }

            Section("Red y seguridad LAN") {
                HStack {
                    Text("Puerto Bus")
                    Spacer()
                    TextField("47991", value: $settings.companionPort, format: .number)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 90)
                }

                HStack {
                    Text("Puerto Archivos Wi-Fi")
                    Spacer()
                    TextField("8080", value: $settings.fileTransferPort, format: .number)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 90)
                }

                SecureField("PIN de voz UDP (opcional, SHA-256)", text: $settings.voicePin)
                    .autocorrectionDisabled()
            }

            Section("Acerca de") {
                HStack {
                    Text("SmartDisplay AI para iOS")
                    Spacer()
                    Text("v12.1").foregroundStyle(DesignTokens.textSecondary)
                }
            }
        }
        .navigationTitle("Ajustes")
        .navigationBarTitleDisplayMode(.inline)
    }
}
