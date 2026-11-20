# SmartDisplay AI — Módulo iOS (100% Propio · SwiftUI + VideoToolbox + Network.framework)

Arquitectura multiplataforma iOS (iPhone e iPad, iOS 16+) 100% propiedad de **SmartDisplay AI**, con lógica real de extremo a extremo, principios SOLID y todos los archivos por debajo de 200 líneas.

## Módulos inyectados (100% lógica real)
- **Diseño:** `Design/DesignTokens.swift` (Claro/Oscuro neutro automático, SF Pro, sin tarjetas anidadas).
- **Dominio:** `Domain/Models/HostDevice.swift`, `Domain/Viewport/ViewportEngine.swift` (Zoom `0.5x–4.0x` + Pan).
- **Persistencia:** `Data/Storage/HostRepository.swift` (persistencia JSON en `UserDefaults`).
- **Video GPU:** `Data/Video/VideoToolboxRenderer.swift` (decodificador H.264 Annex-B → `AVSampleBufferDisplayLayer`).
- **Entrada remota:** `Data/Input/SmartDisplayInputDispatcher.swift` (clic, movimiento de puntero, texto UTF-8/párrafos, teclas).
- **Red y Bus:** `MdnsDiscoveryService.swift` (Bonjour), `WakeOnLanService.swift` (UDP 9 + sonda TCP), `SmartDisplayBusClient.swift` (WebSocket JSON + tramas binarias H.264, ping 15s, backoff 30s).
- **Archivos Wi-Fi bidireccionales:** `WifiFileTransferServer.swift` (`GET /dl` + `POST /up`) + `MultipartUploadParser.swift` (protección Path Traversal).
- **Voz full-duplex UDP 48999:** `VoiceUdpService.swift` (`AVAudioEngine` micrófono + `AVAudioPlayerNode` retorno verificados con `CryptoKit.SHA256`).
- **Vistas SwiftUI:** `SmartDisplayApp`, `HostListView`, `StreamSessionView`, `FileTransferView`, `ManualView`.

## Compilación en macOS / Xcode
> Este entorno corre en Windows; para generar y compilar el binario iOS ejecuta en macOS (Xcode 15+):

```bash
brew install xcodegen
cd ios
xcodegen generate
open SmartDisplayIOS.xcodeproj
```
