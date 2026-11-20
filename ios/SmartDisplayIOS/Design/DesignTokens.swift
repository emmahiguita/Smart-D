import SwiftUI

/// Tokens de diseño empresariales universales (Claro / Oscuro) para SmartDisplay iOS.
///
/// Qué hace: centraliza colores neutros, espaciados, radios y tipografía en un solo lugar.
/// Cómo funciona: delega en colores semánticos del sistema (`Color(.systemBackground)`, etc.)
/// para adaptarse automáticamente a Modo Claro, Modo Oscuro y Alto Contraste sin bordes neón.
/// Por qué: aplica el principio DRY y garantiza consistencia visual idéntica a la versión Android.
enum DesignTokens {

    // MARK: - Paleta Neutra Universal (Sin colores saturados ni cajas anidadas)

    /// Fondo base de cada pantalla (blanco limpio en claro, negro/gris profundo en oscuro).
    static let background = Color(.systemBackground)

    /// Superficie secundaria plana para barras, filas y contenedores sin elevación agresiva.
    static let surface = Color(.secondarySystemBackground)

    /// Borde hairline de 1px para separar bloques sin saturación visual.
    static let border = Color(.separator)

    /// Texto principal con contraste WCAG AA/AAA garantizado por iOS.
    static let textPrimary = Color(.label)

    /// Texto secundario para metadatos (IP, puerto, estado).
    static let textSecondary = Color(.secondaryLabel)

    /// Acento sobrio empresarial para acciones primarias y estados activos.
    static let accent = Color(red: 0.16, green: 0.42, blue: 0.78)

    /// Estado en línea sobrio (verde desaturado).
    static let statusOnline = Color(red: 0.18, green: 0.56, blue: 0.36)

    /// Estado de error o desconexión (rojo sobrio).
    static let statusError = Color(red: 0.75, green: 0.24, blue: 0.24)

    // MARK: - Espaciado y Geometría

    static let spacingXS: CGFloat = 6
    static let spacingSM: CGFloat = 10
    static let spacingMD: CGFloat = 16
    static let spacingLG: CGFloat = 24

    static let radiusSM: CGFloat = 8
    static let radiusMD: CGFloat = 12
    static let maxReadableWidth: CGFloat = 560

    // MARK: - Tipografía Profesional (Dynamic Type nativo de Apple SF Pro)

    static let titleFont = Font.system(.title3, design: .default).weight(.semibold)
    static let sectionFont = Font.system(.headline, design: .default).weight(.semibold)
    static let bodyFont = Font.system(.subheadline, design: .default)
    static let captionFont = Font.system(.caption, design: .default)
    static let monoFont = Font.system(.footnote, design: .monospaced)
}

/// Modificador reutilizable para contenedores planos empresariales.
///
/// Qué hace: aplica fondo neutro, esquinas suaves y borde hairline sin sombras pesadas.
/// Por qué: evita "tarjetas dentro de tarjetas" y mantiene la jerarquía visual limpia.
struct EnterpriseRowSurface: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(DesignTokens.spacingMD)
            .background(DesignTokens.surface)
            .clipShape(RoundedRectangle(cornerRadius: DesignTokens.radiusMD, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: DesignTokens.radiusMD, style: .continuous)
                    .stroke(DesignTokens.border.opacity(0.45), lineWidth: 0.75)
            )
    }
}

extension View {
    /// Aplica el estilo de superficie plana empresarial a cualquier vista SwiftUI.
    func enterpriseSurface() -> some View {
        modifier(EnterpriseRowSurface())
    }
}
