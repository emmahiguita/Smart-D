import SwiftUI
import UIKit

/// Tokens de diseño Material 3 Desaturado — Paridad 1:1 con SmartDisplay Android (`colors.xml`).
///
/// Qué hace: define los mismos colores exactos de Modo Claro (`#F6F7F9` / `#3B5578`) y Modo
/// Oscuro (`#0B0D10` / `#94A8C4`) de Android usando `UIColor(dynamicProvider:)`, garantizando
/// identidad visual idéntica en Android e iOS sin colores neón ni tarjetas anidadas.
enum DesignTokens {

    /// Construye un `Color` adaptativo exacto para Modo Claro y Modo Oscuro.
    private static func adaptive(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { trait in
            let hex = (trait.userInterfaceStyle == .dark) ? dark : light
            return UIColor(
                red: CGFloat((hex >> 16) & 0xFF) / 255.0,
                green: CGFloat((hex >> 8) & 0xFF) / 255.0,
                blue: CGFloat(hex & 0xFF) / 255.0,
                alpha: 1.0
            )
        })
    }

    // MARK: - Paleta Material 3 SmartDisplay (Idéntica a Android values/colors.xml)

    static let background = adaptive(light: 0xF6F7F9, dark: 0x0B0D10)
    static let surface = adaptive(light: 0xFFFFFF, dark: 0x13161B)
    static let surfaceHigh = adaptive(light: 0xEEF1F4, dark: 0x1C2027)
    static let border = adaptive(light: 0xDCE1E7, dark: 0x262C35)

    static let textPrimary = adaptive(light: 0x111418, dark: 0xECEFF2)
    static let textSecondary = adaptive(light: 0x525B66, dark: 0x949CA6)

    static let accent = adaptive(light: 0x3B5578, dark: 0x94A8C4)
    static let onAccent = adaptive(light: 0xFFFFFF, dark: 0x0F141B)
    static let accentContainer = adaptive(light: 0xE2E8F0, dark: 0x232C38)

    static let statusOnline = adaptive(light: 0x3B7A5E, dark: 0x639E82)
    static let statusOnlineBg = adaptive(light: 0xE6F0EB, dark: 0x1A2C24)

    static let statusError = adaptive(light: 0xB43C3C, dark: 0xCF7A7A)
    static let statusErrorBg = adaptive(light: 0xF6E4E4, dark: 0x361E1E)

    // MARK: - Espaciado y Geometría

    static let spacingXS: CGFloat = 6
    static let spacingSM: CGFloat = 10
    static let spacingMD: CGFloat = 16
    static let spacingLG: CGFloat = 24

    static let radiusSM: CGFloat = 8
    static let radiusMD: CGFloat = 14
    static let maxReadableWidth: CGFloat = 560

    // MARK: - Tipografía Profesional (SF Pro Dynamic Type)

    static let titleFont = Font.system(.title3, design: .default).weight(.semibold)
    static let sectionFont = Font.system(.headline, design: .default).weight(.semibold)
    static let bodyFont = Font.system(.subheadline, design: .default)
    static let captionFont = Font.system(.caption, design: .default)
    static let monoFont = Font.system(.footnote, design: .monospaced)
}

/// Superficie plana empresarial Material 3 con borde hairline (sin tarjetas anidadas).
struct EnterpriseRowSurface: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(DesignTokens.spacingMD)
            .background(DesignTokens.surface)
            .clipShape(RoundedRectangle(cornerRadius: DesignTokens.radiusMD, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: DesignTokens.radiusMD, style: .continuous)
                    .stroke(DesignTokens.border, lineWidth: 0.75)
            )
    }
}

extension View {
    func enterpriseSurface() -> some View {
        modifier(EnterpriseRowSurface())
    }
}
