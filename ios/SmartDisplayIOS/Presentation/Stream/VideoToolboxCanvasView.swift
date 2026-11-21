import AVFoundation
import SwiftUI
import UIKit

/// Vista puente SwiftUI que aloja `AVSampleBufferDisplayLayer` para decodificación de video en GPU.
///
/// Qué hace: encapsula una vista UIKit (`UIView`) cuyo layer raíz contiene `AVSampleBufferDisplayLayer`.
/// Cómo funciona: en `layoutSubviews`, ajusta el frame del layer al tamaño exacto de la vista para redimensionamiento fluido.
/// Por qué existe: cumple el Principio de Responsabilidad Única (SRP) separando el hosting de UI del decodificador H.264.
struct VideoToolboxCanvasView: UIViewRepresentable {
    let renderer: VideoToolboxRenderer

    func makeUIView(context: Context) -> VideoLayerHostView {
        VideoLayerHostView(displayLayer: renderer.displayLayer)
    }

    func updateUIView(_ uiView: VideoLayerHostView, context: Context) {}
}

/// Contenedor UIKit que gestiona la geometría del layer de video nativo.
final class VideoLayerHostView: UIView {
    private let displayLayer: AVSampleBufferDisplayLayer

    init(displayLayer: AVSampleBufferDisplayLayer) {
        self.displayLayer = displayLayer
        super.init(frame: .zero)
        // Permite que los gestos SwiftUI (tap, drag, zoom) pasen sin ser bloqueados por UIKit.
        self.isUserInteractionEnabled = false
        layer.addSublayer(displayLayer)
    }

    required init?(coder: NSCoder) { fatalError("No soportado") }

    override func layoutSubviews() {
        super.layoutSubviews()
        displayLayer.frame = bounds
    }
}
