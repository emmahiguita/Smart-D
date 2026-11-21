/// ============================================================================
/// ARCHIVO: stream_top_bar.dart
/// QUÉ HACE: Barra superior flotante de dos islas Material 3 para el streaming.
/// CÓMO FUNCIONA: Separa telemetría en vivo (CPU/Zoom + indicador de enlace)
///                de los controles compactos de teclado, ratón, zoom y salida.
/// POR QUÉ EXISTE: Mantiene la pantalla despejada sin bloques que tapen el video.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import '../../core/app_theme.dart';
import '../../models/host_device.dart';

class StreamTopBar extends StatelessWidget {
  final bool connected;
  final TelemetrySnapshot telemetry;
  final int zoomPercent;
  final bool isMoveMode;
  final bool isKeyboardOpen;
  final bool isMouseOpen;
  final VoidCallback onToggleKeyboard;
  final VoidCallback onToggleMouse;
  final VoidCallback onToggleMove;
  final VoidCallback onZoomIn;
  final VoidCallback onZoomOut;
  final VoidCallback onResetZoom;
  final VoidCallback onCollapse;
  final VoidCallback onClose;

  const StreamTopBar({
    super.key,
    required this.connected,
    required this.telemetry,
    required this.zoomPercent,
    required this.isMoveMode,
    required this.isKeyboardOpen,
    required this.isMouseOpen,
    required this.onToggleKeyboard,
    required this.onToggleMouse,
    required this.onToggleMove,
    required this.onZoomIn,
    required this.onZoomOut,
    required this.onResetZoom,
    required this.onCollapse,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    final pillBg = Colors.black.withValues(alpha: 0.65);
    final label = telemetry.hasData ? 'CPU ${telemetry.cpuPercent}% · $zoomPercent%' : '$zoomPercent%';

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: Row(
        children: [
          Container(
            height: 36,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(color: pillBg, borderRadius: BorderRadius.circular(20)),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(
                    color: connected ? AppTheme.onlineColor : AppTheme.errorColor,
                    shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: 8),
                Text(label, style: const TextStyle(color: Colors.white, fontSize: 12, fontFamily: 'monospace')),
              ],
            ),
          ),
          const Spacer(),
          Container(
            height: 36,
            padding: const EdgeInsets.symmetric(horizontal: 4),
            decoration: BoxDecoration(color: pillBg, borderRadius: BorderRadius.circular(20)),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _iconBtn(isKeyboardOpen ? Icons.keyboard_hide_rounded : Icons.keyboard_rounded, onToggleKeyboard, active: isKeyboardOpen),
                _iconBtn(Icons.mouse_rounded, onToggleMouse, active: isMouseOpen),
                PopupMenuButton<String>(
                  icon: const Icon(Icons.more_horiz_rounded, color: Colors.white, size: 20),
                  padding: EdgeInsets.zero,
                  onSelected: (v) {
                    if (v == 'move') onToggleMove();
                    if (v == 'in') onZoomIn();
                    if (v == 'out') onZoomOut();
                    if (v == 'reset') onResetZoom();
                  },
                  itemBuilder: (_) => [
                    PopupMenuItem(value: 'move', child: Text(isMoveMode ? 'Desactivar modo mover' : 'Modo mover pantalla')),
                    const PopupMenuItem(value: 'in', child: Text('Acercar (+)')),
                    const PopupMenuItem(value: 'out', child: Text('Alejar (-)')),
                    const PopupMenuItem(value: 'reset', child: Text('Restablecer zoom (100%)')),
                  ],
                ),
                _iconBtn(Icons.keyboard_arrow_up_rounded, onCollapse),
                _iconBtn(Icons.close_rounded, onClose),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _iconBtn(IconData icon, VoidCallback onPressed, {bool active = false}) {
    return IconButton(
      onPressed: onPressed,
      padding: EdgeInsets.zero,
      constraints: const BoxConstraints(minWidth: 34, minHeight: 34),
      icon: Icon(icon, size: 18, color: active ? const Color(0xFF94A8C4) : Colors.white),
    );
  }
}
