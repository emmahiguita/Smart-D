/// ============================================================================
/// ARCHIVO: settings_dialog.dart
/// QUÉ HACE: Diálogo Material 3 para ajustar tema (Auto/Claro/Oscuro) y PIN.
/// CÓMO FUNCIONA: Muestra un `AlertDialog` con `SegmentedButton<ThemeMode>` y
///                devuelve el nuevo `AppSettings` al guardar.
/// POR QUÉ EXISTE: Aplica SRP manteniendo `home_screen.dart` < 200 líneas.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import '../../models/host_device.dart';

Future<AppSettings?> showSettingsDialog(BuildContext context, AppSettings current) {
  final pinCtrl = TextEditingController(text: current.pin);
  var mode = current.themeMode;
  return showDialog<AppSettings>(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, setDlg) => AlertDialog(
        title: const Text('Ajustes SmartDisplay'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SegmentedButton<ThemeMode>(
              segments: const [
                ButtonSegment(value: ThemeMode.system, label: Text('Auto')),
                ButtonSegment(value: ThemeMode.light, label: Text('Claro')),
                ButtonSegment(value: ThemeMode.dark, label: Text('Oscuro')),
              ],
              selected: {mode},
              onSelectionChanged: (s) => setDlg(() => mode = s.first),
            ),
            const SizedBox(height: 14),
            TextField(
              controller: pinCtrl,
              decoration: const InputDecoration(labelText: 'PIN (opcional)', prefixIcon: Icon(Icons.lock_outline_rounded)),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancelar')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, current.copyWith(themeMode: mode, pin: pinCtrl.text.trim())),
            child: const Text('Guardar'),
          ),
        ],
      ),
    ),
  );
}
