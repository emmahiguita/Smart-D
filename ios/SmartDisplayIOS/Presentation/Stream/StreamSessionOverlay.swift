import SwiftUI

/// Elementos visuales auxiliares para la sesión remota de SmartDisplay iOS (Placeholder y Pills de estado).
///
/// Qué hace: renderiza el indicador de espera mientras se establece el enlace WebSocket/video H.264
/// y las pastillas translúcidas flotantes (`StreamStatusPill`) para alertas de red o modo mover pantalla.
/// Por qué: cumple el principio de Responsabilidad Única (SRP) reduciendo las líneas de `StreamSessionView`.
struct StreamWaitingPlaceholder: View {
    let hostName: String
    let hostAddress: String
    let busConnected: Bool

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: busConnected ? "display" : "wifi.exclamationmark")
                .font(.largeTitle)
                .foregroundStyle(.white.opacity(0.85))
            Text(hostName)
                .font(DesignTokens.sectionFont)
                .foregroundStyle(.white)
            Text(busConnected ? "Conectado · Esperando video..." : "Conectando con \(hostAddress)...")
                .font(DesignTokens.monoFont)
                .foregroundStyle(.white.opacity(0.65))
        }
    }
}

/// Pastilla flotante con material ultra delgado para mensajes de estado rápidos.
struct StreamStatusPill: View {
    let text: String

    var body: some View {
        Text(text)
            .font(DesignTokens.captionFont)
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(.ultraThinMaterial, in: Capsule())
    }
}
