import SwiftUI
import UIKit

/// Sistema de físicas y animaciones nativas de iOS (Apple HIG Spring Physics).
///
/// Qué hace: centraliza curvas de resorte suaves y predecibles para transiciones de
/// viewport, barras flotantes, banners y estados de conexión.
/// Por qué: evita animaciones lineales robóticas y mantiene coherencia táctil en toda la app.
enum IosMotion {

    /// Resorte ágil para controles interactivos (botones, toggles, badges).
    static let snappy = Animation.spring(response: 0.30, dampingFraction: 0.82, blendDuration: 0.1)

    /// Resorte suave para paneles, hojas y cambios de escala del lienzo remoto.
    static let smooth = Animation.spring(response: 0.42, dampingFraction: 0.86, blendDuration: 0.12)

    /// Resorte rápido para feedback inmediato al soltar gestos de pellizco o arrastre.
    static let interactive = Animation.interactiveSpring(response: 0.26, dampingFraction: 0.80)
}

/// Motor háptico real de iOS basado en `UIImpactFeedbackGenerator` y `UINotificationFeedbackGenerator`.
///
/// Qué hace: dispara vibraciones táctiles precisas respetando la preferencia del usuario.
/// Cómo funciona: prepara y dispara generadores ligeros en el hilo principal sin retener estado.
enum IosHaptics {

    /// Vibración sutil al pulsar controles de la barra o filas interactivas.
    static func tap(enabled: Bool = true) {
        guard enabled else { return }
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.prepare()
        generator.impactOccurred()
    }

    /// Vibración de confirmación al copiar enlaces, guardar equipos o conectar.
    static func success(enabled: Bool = true) {
        guard enabled else { return }
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(.success)
    }

    /// Vibración discreta al cambiar de estado (modo mover pantalla, micrófono, teclado).
    static func selection(enabled: Bool = true) {
        guard enabled else { return }
        let generator = UISelectionFeedbackGenerator()
        generator.prepare()
        generator.selectionChanged()
    }
}

/// Estilo de botón táctil nativo iOS con micro-compresión elástica al presionar.
///
/// Qué hace: reduce la escala al 96% con física de resorte al mantener presionado un control.
struct IosPressButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1.0)
            .opacity(configuration.isPressed ? 0.88 : 1.0)
            .animation(IosMotion.snappy, value: configuration.isPressed)
    }
}
