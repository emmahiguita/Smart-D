/// ============================================================================
/// ARCHIVO: bus_client.dart
/// QUÉ HACE: Cliente WebSocket persistente contra el SmartDisplayBus (puerto 47991).
/// CÓMO FUNCIONA: Mantiene conexión con `Authorization: Bearer <PIN>`, ping cada
///                15s, backoff exponencial hasta 30s, entrega frames JPEG binarios
///                y mensajes JSON sin fugas de memoria ni timers zombis.
/// POR QUÉ EXISTE: Canal en tiempo real para video, ratón, teclado y telemetría.
/// ============================================================================
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

class BusClient {
  WebSocket? _ws;
  StreamSubscription? _sub;
  Timer? _reconnectTimer;
  Timer? _pingTimer;

  String _host = '';
  int _port = 47991;
  String _pin = '';
  bool _stopped = true;
  int _retryAttempt = 0;

  final _connectedCtrl = StreamController<bool>.broadcast();
  final _frameCtrl = StreamController<Uint8List>.broadcast();
  final _msgCtrl = StreamController<Map<String, dynamic>>.broadcast();

  Stream<bool> get onConnected => _connectedCtrl.stream;
  Stream<Uint8List> get onFrame => _frameCtrl.stream;
  Stream<Map<String, dynamic>> get onMessage => _msgCtrl.stream;

  void connect({required String host, int port = 47991, String pin = ''}) {
    _host = host;
    _port = port;
    _pin = pin;
    _stopped = false;
    _retryAttempt = 0;
    _openSocket();
  }

  void send(String type, [Map<String, dynamic> fields = const {}]) {
    final ws = _ws;
    if (ws == null || ws.readyState != WebSocket.open) return;
    try {
      ws.add(jsonEncode({'type': type, ...fields}));
    } catch (_) {}
  }

  Future<void> _openSocket() async {
    _cleanupSocket();
    if (_stopped || _host.isEmpty) return;

    final fmtHost = (_host.contains(':') && !_host.startsWith('[')) ? '[$_host]' : _host;
    final uri = 'ws://$fmtHost:$_port';
    final headers = _pin.isNotEmpty ? {'Authorization': 'Bearer $_pin'} : null;

    try {
      final ws = await WebSocket.connect(uri, headers: headers).timeout(const Duration(seconds: 6));
      if (_stopped) {
        await ws.close();
        return;
      }
      _ws = ws;
      _retryAttempt = 0;
      _connectedCtrl.add(true);
      _startPing();

      _sub = ws.listen(
        (data) {
          if (data is List<int>) {
            _frameCtrl.add(Uint8List.fromList(data));
          } else if (data is String) {
            try {
              final decoded = jsonDecode(data);
              if (decoded is Map<String, dynamic>) _msgCtrl.add(decoded);
            } catch (_) {}
          }
        },
        onDone: _handleDrop,
        onError: (_) => _handleDrop(),
        cancelOnError: true,
      );
    } catch (_) {
      _handleDrop();
    }
  }

  void _startPing() {
    _pingTimer?.cancel();
    _pingTimer = Timer.periodic(const Duration(seconds: 15), (_) {
      send('get_stats');
    });
  }

  void _handleDrop() {
    _cleanupSocket();
    if (!_connectedCtrl.isClosed) _connectedCtrl.add(false);
    if (_stopped) return;

    final delaySec = min(30, pow(2, min(_retryAttempt, 5)).toInt());
    _retryAttempt++;
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(Duration(seconds: delaySec), _openSocket);
  }

  void _cleanupSocket() {
    _pingTimer?.cancel();
    _pingTimer = null;
    _sub?.cancel();
    _sub = null;
    try {
      _ws?.close();
    } catch (_) {}
    _ws = null;
  }

  void disconnect() {
    _stopped = true;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    _cleanupSocket();
    if (!_connectedCtrl.isClosed) _connectedCtrl.add(false);
  }

  void dispose() {
    disconnect();
    _connectedCtrl.close();
    _frameCtrl.close();
    _msgCtrl.close();
  }
}
