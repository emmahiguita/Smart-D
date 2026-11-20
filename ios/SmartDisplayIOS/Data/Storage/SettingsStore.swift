import SwiftUI

/// Preferencia universal de tema visual (Sistema, Claro u Oscuro).
enum AppAppearanceMode: String, CaseIterable, Identifiable {
    case system = "Sistema"
    case light = "Claro"
    case dark = "Oscuro"

    var id: String { rawValue }

    /// Convierte la preferencia a `ColorScheme?` nativo de SwiftUI (`nil` sigue al sistema).
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

/// Almacén reactivo y persistente de preferencias reales de SmartDisplay AI para iOS.
///
/// Qué hace: guarda en `UserDefaults` el tema visual, PIN de voz SHA-256, puertos de red,
/// respuesta háptica y reconexión automática al recuperar Wi-Fi.
/// Por qué: centraliza la configuración en una sola fuente de verdad (`ObservableObject`).
final class SettingsStore: ObservableObject {

    @AppStorage("sd.appearance") var appearanceRaw: String = AppAppearanceMode.system.rawValue
    @AppStorage("sd.hapticsEnabled") var hapticsEnabled: Bool = true
    @AppStorage("sd.autoReconnect") var autoReconnect: Bool = true
    @AppStorage("sd.voicePin") var voicePin: String = ""
    @AppStorage("sd.companionPort") var companionPort: Int = 3001
    @AppStorage("sd.fileTransferPort") var fileTransferPort: Int = 8080

    /// Modo de apariencia tipado para enlazar directamente con `Picker` y `.preferredColorScheme`.
    var appearance: AppAppearanceMode {
        get { AppAppearanceMode(rawValue: appearanceRaw) ?? .system }
        set {
            appearanceRaw = newValue.rawValue
            objectWillChange.send()
        }
    }
}
