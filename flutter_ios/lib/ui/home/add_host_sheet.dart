/// ============================================================================
/// ARCHIVO: add_host_sheet.dart
/// QUÉ HACE: Hoja modal Material 3 (`showModalBottomSheet`) para agregar un PC
///           manualmente mediante Nombre, dirección IP y puerto Companion.
/// CÓMO FUNCIONA: Valida los campos con `TextEditingController`, construye un
///                `HostDevice` y lo devuelve al llamador al confirmar.
/// POR QUÉ EXISTE: Aísla el formulario modal de la pantalla principal (SRP).
/// ============================================================================
library;

import 'package:flutter/material.dart';
import '../../models/host_device.dart';

class AddHostSheet extends StatefulWidget {
  final int defaultPort;

  const AddHostSheet({super.key, required this.defaultPort});

  @override
  State<AddHostSheet> createState() => _AddHostSheetState();
}

class _AddHostSheetState extends State<AddHostSheet> {
  final _nameCtrl = TextEditingController(text: 'SmartDisplay PC');
  final _ipCtrl = TextEditingController();
  late final _portCtrl = TextEditingController(text: '${widget.defaultPort}');

  @override
  void dispose() {
    _nameCtrl.dispose();
    _ipCtrl.dispose();
    _portCtrl.dispose();
    super.dispose();
  }

  void _submit() {
    final ip = _ipCtrl.text.trim();
    if (ip.isEmpty) return;
    final name = _nameCtrl.text.trim().isEmpty ? 'SmartDisplay PC' : _nameCtrl.text.trim();
    final port = int.tryParse(_portCtrl.text.trim()) ?? widget.defaultPort;
    Navigator.pop(context, HostDevice(id: ip, name: name, address: ip, companionPort: port));
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.of(context).viewInsets.bottom;
    return Padding(
      padding: EdgeInsets.fromLTRB(20, 12, 20, bottom + 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 36,
              height: 4,
              margin: const EdgeInsets.only(bottom: 16),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.outlineVariant,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          Text('Añadir equipo Windows', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 16),
          TextField(controller: _nameCtrl, decoration: const InputDecoration(labelText: 'Nombre del PC', prefixIcon: Icon(Icons.label_outline_rounded))),
          const SizedBox(height: 12),
          TextField(
            controller: _ipCtrl,
            keyboardType: TextInputType.url,
            autofocus: true,
            decoration: const InputDecoration(labelText: 'Dirección IP (ej. 192.168.0.19)', prefixIcon: Icon(Icons.wifi_rounded)),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _portCtrl,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: 'Puerto Companion', prefixIcon: Icon(Icons.settings_ethernet_rounded)),
          ),
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: _submit,
            icon: const Icon(Icons.add_rounded),
            label: const Text('Guardar equipo'),
          ),
        ],
      ),
    );
  }
}
