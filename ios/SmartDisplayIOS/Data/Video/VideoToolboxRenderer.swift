import AVFoundation
import CoreMedia
import SwiftUI
import UIKit

/// Decodificador H.264 por hardware de SmartDisplay usando `AVSampleBufferDisplayLayer` (VideoToolbox).
///
/// Qué hace: recibe tramas H.264 Annex-B (SPS, PPS e I/P frames) desde el motor de red,
/// construye `CMVideoFormatDescription` y `CMSampleBuffer` en formato AVCC y las entrega
/// directamente a la GPU de iOS para renderizado de latencia ultrabaja.
/// Cómo funciona: extrae NALUs separados por `00 00 00 01` o `00 00 01`, actualiza SPS/PPS
/// cuando llegan y encola los frames de video en `displayLayer.enqueue(...)`.
/// Por qué: aprovecha la aceleración por hardware nativa de Apple sin copias de CPU innecesarias.
final class VideoToolboxRenderer {

    let displayLayer = AVSampleBufferDisplayLayer()

    private var spsData: Data?
    private var ppsData: Data?
    private var formatDescription: CMVideoFormatDescription?

    init() {
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = UIColor.black.cgColor
    }

    /// Procesa un paquete binario H.264 Annex-B y lo decodifica en pantalla.
    func enqueueAnnexBFrame(_ annexBData: Data) {
        let nalus = splitAnnexBNalus(annexBData)
        var videoNalus: [Data] = []

        for nalu in nalus where !nalu.isEmpty {
            let naluType = nalu[nalu.startIndex] & 0x1F
            switch naluType {
            case 7: // SPS
                spsData = nalu
                rebuildFormatDescription()
            case 8: // PPS
                ppsData = nalu
                rebuildFormatDescription()
            case 1, 5: // Non-IDR o IDR Slice (video real)
                videoNalus.append(nalu)
            default:
                break
            }
        }

        guard !videoNalus.isEmpty, let formatDesc = formatDescription else { return }
        enqueueVideoNalus(videoNalus, formatDescription: formatDesc)
    }

    /// Limpia la cola de decodificación al reconectar o cerrar la sesión.
    func flush() {
        DispatchQueue.main.async { [weak self] in
            self?.displayLayer.flushAndRemoveImage()
        }
    }

    /// Construye `CMVideoFormatDescription` a partir de los parámetros SPS y PPS activos.
    private func rebuildFormatDescription() {
        guard let sps = spsData, let pps = ppsData else { return }
        var newDesc: CMVideoFormatDescription?
        let status: OSStatus = sps.withUnsafeBytes { spsBuf in
            pps.withUnsafeBytes { ppsBuf in
                guard let spsBase = spsBuf.baseAddress?.assumingMemoryBound(to: UInt8.self),
                      let ppsBase = ppsBuf.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                    return -1
                }
                let ptrs: [UnsafePointer<UInt8>] = [spsBase, ppsBase]
                let sizes: [Int] = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: ptrs,
                    parameterSetSizes: sizes,
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &newDesc
                )
            }
        }
        if status == noErr { formatDescription = newDesc }
    }

    /// Empaqueta NALUs en formato AVCC (longitud big-endian 4 bytes) y los encola en GPU.
    private func enqueueVideoNalus(_ nalus: [Data], formatDescription: CMVideoFormatDescription) {
        var avccData = Data()
        for nalu in nalus {
            var length = UInt32(nalu.count).bigEndian
            withUnsafeBytes(of: &length) { avccData.append(contentsOf: $0) }
            avccData.append(nalu)
        }

        var blockBuffer: CMBlockBuffer?
        let byteCount = avccData.count
        let status = avccData.withUnsafeBytes { rawBuf -> OSStatus in
            guard let base = rawBuf.baseAddress else { return -1 }
            var tempBlock: CMBlockBuffer?
            let createErr = CMBlockBufferCreateWithMemoryBlock(
                allocator: kCFAllocatorDefault,
                memoryBlock: nil,
                blockLength: byteCount,
                blockAllocator: kCFAllocatorDefault,
                customBlockSource: nil,
                offsetToData: 0,
                dataLength: byteCount,
                flags: 0,
                blockBufferOut: &tempBlock
            )
            guard createErr == noErr, let block = tempBlock else { return createErr }
            let copyErr = CMBlockBufferReplaceDataBytes(
                with: base, blockBuffer: block, offsetIntoDestination: 0, dataLength: byteCount
            )
            blockBuffer = block
            return copyErr
        }
        guard status == noErr, let validBlock = blockBuffer else { return }

        var sampleBuffer: CMSampleBuffer?
        var sampleSize = avccData.count
        let sbStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: validBlock,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 0,
            sampleTimingArray: nil,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard sbStatus == noErr, let sample = sampleBuffer else { return }

        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dict,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
        }

        DispatchQueue.main.async { [weak self] in
            guard let layer = self?.displayLayer else { return }
            if layer.status == .failed { layer.flush() }
            layer.enqueue(sample)
        }
    }

    /// Divide un flujo Annex-B en NALUs individuales detectando start-codes de 3 y 4 bytes.
    private func splitAnnexBNalus(_ data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var starts: [Int] = []
        var i = 0
        while i + 3 < bytes.count {
            if bytes[i] == 0 && bytes[i + 1] == 0 {
                if bytes[i + 2] == 1 {
                    starts.append(i + 3)
                    i += 3
                    continue
                } else if i + 3 < bytes.count && bytes[i + 2] == 0 && bytes[i + 3] == 1 {
                    starts.append(i + 4)
                    i += 4
                    continue
                }
            }
            i += 1
        }
        guard !starts.isEmpty else { return [data] }
        var result: [Data] = []
        for idx in 0..<starts.count {
            let begin = starts[idx]
            var end = (idx + 1 < starts.count) ? starts[idx + 1] - 3 : bytes.count
            if end > begin && end - 1 < bytes.count && bytes[end - 1] == 0 { end -= 1 }
            if begin < end { result.append(Data(bytes[begin..<end])) }
        }
        return result
    }
}

/// Vista SwiftUI que aloja `AVSampleBufferDisplayLayer` con redimensionamiento automático.
struct VideoToolboxCanvasView: UIViewRepresentable {
    let renderer: VideoToolboxRenderer

    func makeUIView(context: Context) -> VideoLayerHostView {
        VideoLayerHostView(displayLayer: renderer.displayLayer)
    }

    func updateUIView(_ uiView: VideoLayerHostView, context: Context) {}
}

final class VideoLayerHostView: UIView {
    private let displayLayer: AVSampleBufferDisplayLayer

    init(displayLayer: AVSampleBufferDisplayLayer) {
        self.displayLayer = displayLayer
        super.init(frame: .zero)
        layer.addSublayer(displayLayer)
    }

    required init?(coder: NSCoder) { fatalError("No soportado") }

    override func layoutSubviews() {
        super.layoutSubviews()
        displayLayer.frame = bounds
    }
}
