/// ============================================================================
/// ARCHIVO: stream_canvas.dart
/// QUÉ HACE: Lienzo interactivo de video remoto con gestos de ratón, zoom y pan.
/// CÓMO FUNCIONA: Renderiza `Image.memory` con `gaplessPlayback: true` bajo
///                `Transform`, capturando taps, doble tap, long-press y arrastre.
/// POR QUÉ EXISTE: Aplica SRP separando el lienzo táctil de `stream_screen.dart`.
/// ============================================================================
library;

import 'dart:typed_data';
import 'package:flutter/material.dart';

class StreamCanvas extends StatelessWidget {
  final Size viewport;
  final Uint8List? frame;
  final bool connected;
  final String hostAddress;
  final double scale;
  final Offset offset;
  final ValueChanged<Offset> onTap;
  final ValueChanged<Offset> onDoubleTap;
  final ValueChanged<Offset> onLongPress;
  final GestureScaleStartCallback onScaleStart;
  final GestureScaleUpdateCallback onScaleUpdate;

  const StreamCanvas({
    super.key,
    required this.viewport,
    required this.frame,
    required this.connected,
    required this.hostAddress,
    required this.scale,
    required this.offset,
    required this.onTap,
    required this.onDoubleTap,
    required this.onLongPress,
    required this.onScaleStart,
    required this.onScaleUpdate,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapUp: (d) => onTap(d.localPosition),
      onDoubleTapDown: (d) => onDoubleTap(d.localPosition),
      onLongPressStart: (d) => onLongPress(d.localPosition),
      onScaleStart: onScaleStart,
      onScaleUpdate: onScaleUpdate,
      child: ClipRect(
        child: Transform(
          alignment: Alignment.center,
          transform: Matrix4.identity()
            ..translateByDouble(offset.dx, offset.dy, 0.0, 1.0)
            ..scaleByDouble(scale, scale, 1.0, 1.0),
          child: Center(
            child: frame != null
                ? Image.memory(frame!, gaplessPlayback: true, fit: BoxFit.contain, width: viewport.width, height: viewport.height)
                : Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const CircularProgressIndicator(),
                      const SizedBox(height: 16),
                      Text(
                        connected ? 'Recibiendo escritorio...' : 'Conectando a $hostAddress...',
                        style: const TextStyle(color: Colors.white70),
                      ),
                    ],
                  ),
          ),
        ),
      ),
    );
  }
}
