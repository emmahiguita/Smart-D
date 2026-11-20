import Combine
import SwiftUI

/// Contenedor de Inyección de Dependencias de SmartDisplay AI (Principio DIP - SOLID).
///
/// Qué hace: instancia todos los servicios reales y propaga `settings.objectWillChange`
/// hacia `container.objectWillChange` para que cualquier cambio en Ajustes (como Tema
/// Claro/Oscuro) refresque inmediatamente el árbol raíz de SwiftUI.
/// Por qué: corrige el bug clásico de `ObservableObject` anidados en SwiftUI.
final class AppContainer: ObservableObject {
    let settings = SettingsStore()
    let logStore = AppLogStore()
    let networkMonitor = NetworkPathMonitor()
    let hostRepository: HostRepositoryProtocol
    let hostApiClient = HostApiClient()
    let sunshinePairService = SunshinePairService()
    let discoveryService: HostDiscoveryServing
    let wakeOnLanService: WakeOnLanService
    let busClient: SmartDisplayBusClient
    let videoRenderer: VideoToolboxRenderer
    let inputDispatcher: SmartDisplayInputDispatcher
    let fileTransferServer: WifiFileTransferServer
    let voiceService: VoiceUdpService

    private var settingsCancellable: AnyCancellable?

    init() {
        let bus = SmartDisplayBusClient()
        self.hostRepository = HostRepository()
        self.discoveryService = MdnsDiscoveryService()
        self.wakeOnLanService = WakeOnLanService()
        self.busClient = bus
        self.videoRenderer = VideoToolboxRenderer()
        self.inputDispatcher = SmartDisplayInputDispatcher(busClient: bus)
        self.fileTransferServer = WifiFileTransferServer()
        self.voiceService = VoiceUdpService()

        // Reenvía cambios de SettingsStore al contenedor para refrescar `.preferredColorScheme` en vivo
        self.settingsCancellable = settings.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async { self?.objectWillChange.send() }
        }
    }
}

/// Punto de entrada principal de SmartDisplay AI para iOS (iPhone e iPad).
@main
struct SmartDisplayApp: App {

    @StateObject private var container = AppContainer()

    var body: some Scene {
        WindowGroup {
            HostListView()
                .environmentObject(container)
                .environmentObject(container.settings)
                .environmentObject(container.logStore)
                .tint(DesignTokens.accent)
                .preferredColorScheme(container.settings.appearance.colorScheme)
        }
    }
}
