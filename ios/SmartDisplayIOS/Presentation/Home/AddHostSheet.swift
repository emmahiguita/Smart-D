import SwiftUI

/// Hoja modal para añadir manualmente un equipo SmartDisplay por dirección IP o hostname.
///
/// Qué hace: valida la dirección IP o hostname introducido por el usuario, solicita opcionalmente
/// la dirección MAC para paquetes mágicos Wake-on-LAN y emite el nuevo `HostDevice` al guardar.
/// Cómo funciona: implementa validación reactiva con botón 'Guardar' desactivado si el campo está vacío,
/// y autocapitalización/autocorrección desactivadas para evitar errores tipográficos en direcciones IP.
/// Por qué: cumple el principio de Responsabilidad Única (SRP) extrayendo la vista modal de `HostListView`.
struct AddHostSheet: View {

    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var address = ""
    @State private var macAddress = ""

    let onSave: (HostDevice) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("Datos del equipo SmartDisplay") {
                    TextField("Nombre (ej. PC Principal)", text: $name)
                    TextField("Dirección IP (ej. 192.168.0.10)", text: $address)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField("MAC para Wake-on-LAN (opcional)", text: $macAddress)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
            }
            .navigationTitle("Añadir equipo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        let cleanIp = address.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !cleanIp.isEmpty else { return }
                        let finalName = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        let cleanMac = macAddress.trimmingCharacters(in: .whitespacesAndNewlines)
                        onSave(HostDevice(
                            name: finalName.isEmpty ? cleanIp : finalName,
                            address: cleanIp,
                            macAddress: cleanMac.isEmpty ? nil : cleanMac
                        ))
                        dismiss()
                    }
                    .disabled(address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
