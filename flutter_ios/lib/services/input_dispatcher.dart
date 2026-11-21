/// ============================================================================
/// ARCHIVO: input_dispatcher.dart
/// QUÉ HACE: Traduce gestos táctiles, zoom/pan y pulsaciones de teclado de
///           Flutter a comandos normalizados para Windows (`companion_server`).
/// CÓMO FUNCIONA: Convierte coordenadas del viewport compensando la relación
///                de aspecto 16:9 (letterbox/pillarbox) a rango `0.0..1.0`.
/// POR QUÉ EXISTE: Garantiza precisión 1:1 de ratón y escritura Unicode directa.
/// ============================================================================
library;

import 'dart:ui';
import 'bus_client.dart';

class InputDispatcher {
  final BusClient bus;

  InputDispatcher(this.bus);

  void sendTap(Offset point, Size viewport) {
    final norm = _normalize(point, viewport);
    if (norm == null) return;
    bus.send('input_click', {'x': _fmt(norm.dx), 'y': _fmt(norm.dy), 'button': 'left'});
  }

  void sendDoubleTap(Offset point, Size viewport) {
    final norm = _normalize(point, viewport);
    if (norm == null) return;
    bus.send('input_double_click', {'x': _fmt(norm.dx), 'y': _fmt(norm.dy)});
  }

  void sendRightClick(Offset point, Size viewport) {
    final norm = _normalize(point, viewport);
    if (norm == null) return;
    bus.send('input_click', {'x': _fmt(norm.dx), 'y': _fmt(norm.dy), 'button': 'right'});
  }

  void sendPointerMove(Offset point, Size viewport) {
    final norm = _normalize(point, viewport);
    if (norm == null) return;
    bus.send('input_move', {'x': _fmt(norm.dx), 'y': _fmt(norm.dy)});
  }

  void sendMouseDown(Offset point, Size viewport) {
    final norm = _normalize(point, viewport);
    if (norm == null) return;
    bus.send('input_mouse_down', {'x': _fmt(norm.dx), 'y': _fmt(norm.dy), 'button': 'left'});
  }

  void sendMouseUp(Offset point, Size viewport) {
    final norm = _normalize(point, viewport);
    if (norm == null) return;
    bus.send('input_mouse_up', {'x': _fmt(norm.dx), 'y': _fmt(norm.dy), 'button': 'left'});
  }

  void sendScroll(int deltaY) => bus.send('input_scroll', {'delta_y': '$deltaY'});

  /// Envía un carácter Unicode en vivo (tecla a tecla, acentos, símbolos, emojis).
  void sendChar(String ch) {
    if (ch.isEmpty) return;
    bus.send('input_char', {'char': ch});
  }

  /// Envía un texto o párrafo completo al portapapeles de Windows y lo pega.
  void sendParagraph(String text) {
    final clean = text.trimRight();
    if (clean.isEmpty) return;
    bus.send('clipboard', {'text': clean});
    bus.send('input_text', {'text': clean});
  }

  /// Envía teclas especiales o combinaciones (`enter`, `backspace`, `ctrl_c`, etc.).
  void sendSpecialKey(String key) => bus.send('input_key', {'key': key});

  void startStream() => bus.send('start_stream');
  void stopStream() => bus.send('stop_stream');
  void requestStats() => bus.send('get_stats');

  Offset? _normalize(Offset point, Size size) {
    if (size.width <= 0 || size.height <= 0) return null;
    const aspect = 16.0 / 9.0;
    final viewAspect = size.width / size.height;
    var drawW = size.width, drawH = size.height;
    var ox = 0.0, oy = 0.0;

    if (viewAspect > aspect) {
      drawW = size.height * aspect;
      ox = (size.width - drawW) / 2.0;
    } else {
      drawH = size.width / aspect;
      oy = (size.height - drawH) / 2.0;
    }

    final cx = point.dx.clamp(ox, ox + drawW);
    final cy = point.dy.clamp(oy, oy + drawH);
    final nx = drawW > 0 ? ((cx - ox) / drawW).clamp(0.0, 1.0) : 0.5;
    final ny = drawH > 0 ? ((cy - oy) / drawH).clamp(0.0, 1.0) : 0.5;
    return Offset(nx, ny);
  }

  String _fmt(double v) => v.toStringAsFixed(4);
}
