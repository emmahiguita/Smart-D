import SwiftUI

/// Manual de uso limpio y profesional (paridad visual con `ManualActivity.kt` en Android).
///
/// Qué hace: presenta las instrucciones reales de conexión, gestos de zoom/pan,
/// transferencia Wi-Fi y audio UDP en bloques planos legibles en Modo Claro y Oscuro.
/// Por qué: limita el ancho de lectura a 560pt para verse equilibrado en iPhone horizontal e iPad.
struct ManualView: View {

    private let sections: [(icon: String, title: String, detail: String)] = [
        (
            "wifi",
            "1. Conexión rápida en red local",
            "Asegúrate de que tu iPhone/iPad y tu PC estén en la misma red Wi-Fi con SmartDisplay Host activo. El equipo aparecerá automáticamente en tu red local o puedes añadir su IP con el botón +."
        ),
        (
            "hand.draw",
            "2. Control libre del lienzo (Zoom y Pan)",
            "Pellizca con 2 dedos para acercar hasta 400% o alejar hasta 50%. Activa el icono de mano en la barra superior para arrastrar la pantalla con 1 dedo y pulsa Ajustar para volver a 100%."
        ),
        (
            "arrow.up.arrow.down.circle",
            "3. Transferencia de archivos Wi-Fi",
            "Abre Transferir archivos, comparte los documentos desde tu dispositivo y abre la dirección http://IP:8080 en el navegador de tu PC para descargarlos sin cables."
        ),
        (
            "mic",
            "4. Voz bidireccional segura",
            "Pulsa el icono de micrófono dentro de la sesión para transmitir audio de baja latencia por UDP (puerto 48999) con supresión de ruido y autenticación SHA-256."
        )
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DesignTokens.spacingMD) {
                ForEach(sections, id: \.title) { item in
                    HStack(alignment: .top, spacing: DesignTokens.spacingMD) {
                        Image(systemName: item.icon)
                            .font(.title3)
                            .foregroundStyle(DesignTokens.accent)
                            .frame(width: 28)

                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.title)
                                .font(DesignTokens.sectionFont)
                                .foregroundStyle(DesignTokens.textPrimary)
                            Text(item.detail)
                                .font(DesignTokens.bodyFont)
                                .foregroundStyle(DesignTokens.textSecondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .enterpriseSurface()
                }
            }
            .padding(DesignTokens.spacingMD)
            .frame(maxWidth: DesignTokens.maxReadableWidth)
            .frame(maxWidth: .infinity)
        }
        .background(DesignTokens.background)
        .navigationTitle("Manual de uso")
        .navigationBarTitleDisplayMode(.inline)
    }
}
