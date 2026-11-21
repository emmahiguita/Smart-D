/// ============================================================================
/// ARCHIVO: stream_mouse_dock.dart
/// QUÉ HACE: Dock flotante Material 3 con acciones rápidas de ratón (Clic Izq,
///           Doble Clic, Clic Der, Bloqueo de Arrastre y Rueda de Scroll).
/// CÓMO FUNCIONA: Envía comandos de ratón al punto actual mapeado del lienzo
///                y alterna `mouse_down`/`mouse_up` para arrastrar o seleccionar.
/// POR QUÉ EXISTE: Permite precisión de escritorio desde pantalla táctil (SRP).
/// ============================================================================
library;

import 'package:flutter/material.dart';
import '../../services/input_dispatcher.dart';

class StreamMouseDock extends StatelessWidget {
  final InputDispatcher dispatcher;
  final Offset currentPoint;
  final Size viewportSize;
  final bool isDragLocked;
  final ValueChanged<bool> onToggleDragLock;
  final VoidCallback onClose;

  const StreamMouseDock({
    super.key,
    required this.dispatcher,
    required this.currentPoint,
    required this.viewportSize,
    required this.isDragLocked,
    required this.onToggleDragLock,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12),
      color: cs.surfaceContainerHigh.withValues(alpha: 0.94),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            _btn(Icons.mouse_rounded, 'Clic', () => dispatcher.sendTap(currentPoint, viewportSize)),
            _btn(Icons.ads_click_rounded, 'Doble', () => dispatcher.sendDoubleTap(currentPoint, viewportSize)),
            _btn(Icons.menu_open_rounded, 'Der', () => dispatcher.sendRightClick(currentPoint, viewportSize)),
            const SizedBox(width: 4),
            FilterChip(
              selected: isDragLocked,
              label: Text(isDragLocked ? 'Soltar' : 'Arrastrar', style: const TextStyle(fontSize: 12)),
              avatar: Icon(isDragLocked ? Icons.lock_rounded : Icons.pan_tool_alt_rounded, size: 16),
              visualDensity: VisualDensity.compact,
              onSelected: (next) {
                if (next) {
                  dispatcher.sendMouseDown(currentPoint, viewportSize);
                } else {
                  dispatcher.sendMouseUp(currentPoint, viewportSize);
                }
                onToggleDragLock(next);
              },
            ),
            const SizedBox(width: 4),
            _btn(Icons.arrow_upward_rounded, 'Subir', () => dispatcher.sendScroll(240)),
            _btn(Icons.arrow_downward_rounded, 'Bajar', () => dispatcher.sendScroll(-240)),
            IconButton(onPressed: onClose, icon: const Icon(Icons.close_rounded, size: 18)),
          ],
        ),
      ),
    );
  }

  Widget _btn(IconData icon, String label, VoidCallback onTap) {
    return Padding(
      padding: const EdgeInsets.only(right: 4),
      child: ActionChip(
        avatar: Icon(icon, size: 16),
        label: Text(label, style: const TextStyle(fontSize: 12)),
        visualDensity: VisualDensity.compact,
        onPressed: onTap,
      ),
    );
  }
}
