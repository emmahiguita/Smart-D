/// ============================================================================
/// ARCHIVO: focus_client.dart
/// QUÉ HACE: Cliente WebSocket para el monitor de foco de Windows (puerto 8765).
/// CÓMO FUNCIONA: Escucha eventos `focus_changed` y `focus_cleared` enviados por
///                UIAutomation en el PC para abrir el teclado automáticamente.
/// POR QUÉ EXISTE: Aplica paridad 1:1 de comportamiento inteligente iOS ↔ Android.
/// ============================================================================
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

class FocusClient {
  WebSocket? _ws;
  StreamSubscription? _sub;
  bool _stopped = true;
  final _focusCtrl = StreamController<bool>.broadcast();

  Stream<bool> get onTextFocusChanged => _focusCtrl.stream;

  Future<void> connect({required String host, int port = 8765, String pin = ''}) async {
    _stopped = false;
    final fmtHost = (host.contains(':') && !host.startsWith('[')) ? '[$host]' : host;
    final headers = pin.isNotEmpty ? {'Authorization': 'Bearer $pin'} : null;
    try {
      final ws = await WebSocket.connect('ws://$fmtHost:$port', headers: headers)
          .timeout(const Duration(seconds: 5));
      if (_stopped) {
        await ws.close();
        return;
      }
      _ws = ws;
      _sub = ws.listen(
        (raw) {
          if (raw is! String) return;
          try {
            final msg = jsonDecode(raw);
            if (msg is! Map<String, dynamic>) return;
            if (msg['type'] == 'focus_changed' && msg['is_text_field'] == true) {
              _focusCtrl.add(true);
            } else if (msg['type'] == 'focus_cleared') {
              _focusCtrl.add(false);
            }
          } catch (_) {}
        },
        onError: (_) {},
        onDone: () {},
        cancelOnError: true,
      );
    } catch (_) {}
  }

  void dispose() {
    _stopped = true;
    _sub?.cancel();
    try {
      _ws?.close();
    } catch (_) {}
    _focusCtrl.close();
  }
}
