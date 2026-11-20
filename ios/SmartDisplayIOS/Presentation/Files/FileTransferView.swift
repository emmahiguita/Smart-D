import SwiftUI
import UniformTypeIdentifiers

/// Pantalla bidireccional de transferencia de archivos Wi-Fi (SmartDisplay Files iOS ↔ PC).
///
/// Qué hace: enciende `WifiFileTransferServer` (puerto 8080), muestra el enlace local copiable,
/// permite compartir archivos desde iOS (`fileImporter`) y lista en tiempo real los archivos
/// recibidos desde el navegador del PC en `Documents/SmartDisplay`.
struct FileTransferView: View {

    @EnvironmentObject private var container: AppContainer
    @State private var isRunning = false
    @State private var wifiIp: String? = WifiFileTransferServer.localWifiIPv4Address()
    @State private var sharedFiles: [TransferFileItem] = []
    @State private var receivedFiles: [TransferFileItem] = []
    @State private var showImporter = false
    @State private var copiedFeedback = false
    @State private var radarPulse = false

    private var activePort: UInt16 {
        let configured = container.settings.fileTransferPort
        return (configured > 1024 && configured <= 65535) ? UInt16(configured) : WifiFileTransferServer.defaultPort
    }

    private var serverUrl: String {
        guard let ip = wifiIp, isRunning else { return "" }
        return "http://\(ip):\(activePort)"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DesignTokens.spacingMD) {
                serverStatusBlock
                fileListSection(
                    title: "Compartidos hacia PC (\(sharedFiles.count))",
                    emptyText: "Pulsa \"Compartir archivos\" para ofrecer documentos a tu PC.",
                    items: sharedFiles,
                    icon: "arrow.up.doc"
                )
                fileListSection(
                    title: "Recibidos desde PC (\(receivedFiles.count))",
                    emptyText: "Sube archivos desde \(serverUrl.isEmpty ? "el navegador de tu PC" : serverUrl) y aparecerán aquí.",
                    items: receivedFiles,
                    icon: "arrow.down.doc"
                )
            }
            .padding(DesignTokens.spacingMD)
            .frame(maxWidth: DesignTokens.maxReadableWidth)
            .frame(maxWidth: .infinity)
        }
        .background(DesignTokens.background)
        .navigationTitle("Transferir archivos")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            handleImportedFiles(result)
        }
        .onAppear {
            wifiIp = WifiFileTransferServer.localWifiIPv4Address()
            receivedFiles = container.fileTransferServer.listReceivedFiles()
            container.fileTransferServer.onStateChanged = { running in isRunning = running }
            container.fileTransferServer.onFileReceived = { _ in
                IosHaptics.success(enabled: container.settings.hapticsEnabled)
                withAnimation(IosMotion.snappy) {
                    receivedFiles = container.fileTransferServer.listReceivedFiles()
                }
            }
            container.fileTransferServer.start(port: activePort)
        }
        .onDisappear {
            container.fileTransferServer.stop()
        }
    }

    private var serverStatusBlock: some View {
        VStack(alignment: .leading, spacing: DesignTokens.spacingSM) {
            HStack(spacing: DesignTokens.spacingMD) {
                // Radar Wi-Fi monocromático sobrio (paridad con AirDropRadarView de Android)
                ZStack {
                    Circle()
                        .stroke(DesignTokens.accent.opacity(isRunning ? 0.35 : 0.12), lineWidth: 1.5)
                        .frame(width: 44, height: 44)
                        .scaleEffect(radarPulse && isRunning ? 1.28 : 0.92)
                        .opacity(radarPulse && isRunning ? 0.0 : 1.0)
                        .animation(
                            isRunning ? .easeOut(duration: 1.8).repeatForever(autoreverses: false) : .default,
                            value: radarPulse
                        )
                    Image(systemName: "wifi")
                        .font(.title3.weight(.medium))
                        .foregroundStyle(isRunning ? DesignTokens.accent : DesignTokens.textSecondary)
                        .frame(width: 38, height: 38)
                        .background(DesignTokens.surfaceHigh, in: Circle())
                }
                .onAppear { radarPulse = true }

                VStack(alignment: .leading, spacing: 2) {
                    Text(isRunning ? "Servidor activo en red Wi-Fi" : "Servidor detenido")
                        .font(DesignTokens.sectionFont)
                    Text(serverUrl.isEmpty ? "Conéctate a la misma red Wi-Fi que tu PC." : serverUrl)
                        .font(DesignTokens.monoFont)
                        .foregroundStyle(DesignTokens.textSecondary)
                }
            }

            HStack(spacing: DesignTokens.spacingSM) {
                Button(copiedFeedback ? "Enlace copiado" : "Copiar enlace") {
                    UIPasteboard.general.string = serverUrl
                    IosHaptics.success(enabled: container.settings.hapticsEnabled)
                    copiedFeedback = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { copiedFeedback = false }
                }
                .buttonStyle(.borderedProminent)
                .disabled(serverUrl.isEmpty)

                Button("Compartir archivos") { showImporter = true }
                    .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .enterpriseSurface()
    }

    private func fileListSection(title: String, emptyText: String, items: [TransferFileItem], icon: String) -> some View {
        VStack(alignment: .leading, spacing: DesignTokens.spacingSM) {
            Text(title).font(DesignTokens.sectionFont)
            if items.isEmpty {
                Text(emptyText)
                    .font(DesignTokens.bodyFont)
                    .foregroundStyle(DesignTokens.textSecondary)
                    .enterpriseSurface()
            } else {
                ForEach(items) { item in
                    HStack(spacing: DesignTokens.spacingSM) {
                        Image(systemName: icon).foregroundStyle(DesignTokens.textSecondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name).font(DesignTokens.bodyFont).lineLimit(1)
                            Text(item.formattedSize).font(DesignTokens.captionFont).foregroundStyle(DesignTokens.textSecondary)
                        }
                        Spacer()
                        ShareLink(item: item.url) {
                            Image(systemName: "square.and.arrow.up")
                                .foregroundStyle(DesignTokens.accent)
                        }
                    }
                    .enterpriseSurface()
                }
            }
        }
    }

    private func handleImportedFiles(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result else { return }
        for url in urls {
            let accessed = url.startAccessingSecurityScopedResource()
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if accessed { url.stopAccessingSecurityScopedResource() }
            let item = TransferFileItem(name: url.lastPathComponent, url: url, byteCount: Int64(size))
            container.fileTransferServer.addSharedItem(item)
            sharedFiles.append(item)
        }
    }
}
