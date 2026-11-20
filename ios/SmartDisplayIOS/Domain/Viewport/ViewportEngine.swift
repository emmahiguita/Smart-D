import CoreGraphics

/// Motor matemático puro de Zoom y Pan del lienzo de streaming (paridad 1:1 con Android).
///
/// Qué hace: calcula la escala válida (`0.5x` a `4.0x`) y restringe el desplazamiento
/// para que el escritorio remoto nunca se pierda fuera de pantalla.
/// Cómo funciona: es una estructura sin dependencias de UIKit/SwiftUI, lo que garantiza
/// cero fugas de memoria y comportamiento idéntico en orientación vertical y horizontal.
/// Por qué: separa la matemática del viewport de la vista (Principio de Responsabilidad Única).
struct ViewportEngine {

    static let minScale: CGFloat = 0.5
    static let fitScale: CGFloat = 1.0
    static let maxScale: CGFloat = 4.0
    static let stepFactor: CGFloat = 1.25

    private(set) var state = ViewportState()

    /// Aplica un factor de escala relativo (por ejemplo desde un gesto de pellizco).
    mutating func applyScale(_ newScale: CGFloat, viewportSize: CGSize) {
        state.scale = clamp(newScale, min: Self.minScale, max: Self.maxScale)
        clampOffsets(viewportSize: viewportSize)
    }

    /// Acerca el zoom en un paso discreto (+25%) para los controles rápidos de la barra.
    mutating func zoomIn(viewportSize: CGSize) {
        applyScale(state.scale * Self.stepFactor, viewportSize: viewportSize)
    }

    /// Aleja el zoom en un paso discreto (-20%) permitiendo incluso vista reducida hasta 0.5x.
    mutating func zoomOut(viewportSize: CGSize) {
        applyScale(state.scale / Self.stepFactor, viewportSize: viewportSize)
    }

    /// Aplica desplazamiento incremental en píxeles (2 dedos o 1 dedo en modo Mover).
    mutating func pan(deltaX: CGFloat, deltaY: CGFloat, viewportSize: CGSize) {
        state.offsetX += deltaX
        state.offsetY += deltaY
        clampOffsets(viewportSize: viewportSize)
    }

    /// Restaura la vista a escala exacta 1.0x centrada y desactiva el modo Mover.
    mutating func reset() {
        state.scale = Self.fitScale
        state.offsetX = 0
        state.offsetY = 0
        state.isMoveModeEnabled = false
    }

    /// Activa o desactiva el modo Mover con 1 dedo (sin interferir con el clic remoto).
    mutating func setMoveMode(_ enabled: Bool) {
        state.isMoveModeEnabled = enabled
    }

    /// Limita los desplazamientos X/Y proporcionalmente al tamaño de la ventana actual.
    /// Incluso con escala < 1.0 permite mover libremente dentro de un margen seguro.
    private mutating func clampOffsets(viewportSize: CGSize) {
        guard viewportSize.width > 0, viewportSize.height > 0 else { return }
        let extraW = max(0, (viewportSize.width * state.scale - viewportSize.width) / 2.0)
        let extraH = max(0, (viewportSize.height * state.scale - viewportSize.height) / 2.0)
        // Margen flexible de 35% para facilitar reposicionamiento en modo reducido
        let maxX = max(extraW, viewportSize.width * 0.35)
        let maxY = max(extraH, viewportSize.height * 0.35)
        state.offsetX = clamp(state.offsetX, min: -maxX, max: maxX)
        state.offsetY = clamp(state.offsetY, min: -maxY, max: maxY)
    }

    /// Acota un valor numérico al intervalo cerrado `[min, max]`.
    private func clamp(_ value: CGFloat, min low: CGFloat, max high: CGFloat) -> CGFloat {
        Swift.min(Swift.max(value, low), high)
    }
}
