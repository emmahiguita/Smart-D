/// ============================================================================
/// ARCHIVO: stream_screen.dart
/// QUÉ HACE: Pantalla remota en tiempo real con proyección JPEG, mapeo inverso
///           de coordenadas 1:1 bajo zoom/pan, ratón completo y teclado en vivo.
/// CÓMO FUNCIONA: Escucha los streams de `BusClient`, dibuja frames con
///                `gaplessPlayback: true` y aplica la matriz inversa de escala
///                y desplazamiento antes de despachar clics o movimientos.
/// POR QUÉ EXISTE: Orquesta la sesión remota asegurando cierre sin zombis.
/// ============================================================================
library;

import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../models/host_device.dart';
import '../../services/bus_client.dart';
import '../../services/input_dispatcher.dart';
import 'stream_canvas.dart';
import 'stream_keyboard_dock.dart';
import 'stream_mouse_dock.dart';
import 'stream_top_bar.dart';

class StreamScreen extends StatefulWidget {
  final HostDevice host;
  final AppSettings settings;

  const StreamScreen({super.key, required this.host, required this.settings});

  @override
  State<StreamScreen> createState() => _StreamScreenState();
}

class _StreamScreenState extends State<StreamScreen> {
  final _bus = BusClient();
  late final _dispatcher = InputDispatcher(_bus);

  StreamSubscription<bool>? _connSub;
  StreamSubscription<Uint8List>? _frameSub;
  StreamSubscription<Map<String, dynamic>>? _msgSub;

  bool _connected = false;
  Uint8List? _latestFrame;
  TelemetrySnapshot _telemetry = const TelemetrySnapshot();

  double _scale = 1.0, _baseScale = 1.0;
  Offset _offset = Offset.zero, _lastFocal = Offset.zero;
  Offset _lastTouch = Offset.zero;
  bool _moveMode = false, _showKbd = false, _showMouse = false, _dragLocked = false, _controlsVisible = true;

  @override
  void initState() {
    super.initState();
    _connSub = _bus.onConnected.listen((ok) {
      if (!mounted) return;
      setState(() => _connected = ok);
      if (ok) {
        _dispatcher.startStream();
        _dispatcher.requestStats();
      }
    });
    _frameSub = _bus.onFrame.listen((bytes) {
      if (mounted) setState(() => _latestFrame = bytes);
    });
    _msgSub = _bus.onMessage.listen((msg) {
      if (!mounted || msg['type'] != 'stats') return;
      final cpu = (num.tryParse('${msg['cpu'] ?? 0}') ?? 0).round();
      final ram = (num.tryParse('${msg['ram'] ?? 0}') ?? 0).round();
      setState(() => _telemetry = TelemetrySnapshot(cpuPercent: cpu, ramPercent: ram, hasData: true));
    });
    final port = widget.settings.companionPort > 0 ? widget.settings.companionPort : widget.host.companionPort;
    _bus.connect(host: widget.host.address, port: port, pin: widget.settings.pin);
  }

  @override
  void dispose() {
    _dispatcher.stopStream();
    _connSub?.cancel();
    _frameSub?.cancel();
    _msgSub?.cancel();
    _bus.dispose();
    super.dispose();
  }

  Offset _mapToContent(Offset screenPt, Size vp) {
    if (_scale <= 0) return screenPt;
    final cx = vp.width / 2.0, cy = vp.height / 2.0;
    return Offset((screenPt.dx - cx - _offset.dx) / _scale + cx, (screenPt.dy - cy - _offset.dy) / _scale + cy);
  }

  Offset _clampOffset(Offset next, Size vp) {
    final maxX = (vp.width * (_scale - 1.0)) / 2.0;
    final maxY = (vp.height * (_scale - 1.0)) / 2.0;
    return Offset(next.dx.clamp(-maxX, maxX), next.dy.clamp(-maxY, maxY));
  }

  void _setZoom(double target, Size vp) {
    setState(() {
      _scale = target.clamp(1.0, 4.0);
      _offset = _clampOffset(_offset, vp);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final vp = constraints.biggest;
            return Stack(
              children: [
                StreamCanvas(
                  viewport: vp,
                  frame: _latestFrame,
                  connected: _connected,
                  hostAddress: widget.host.address,
                  scale: _scale,
                  offset: _offset,
                  onTap: (pt) {
                    if (_moveMode) return;
                    _lastTouch = pt;
                    _dispatcher.sendTap(_mapToContent(pt, vp), vp);
                  },
                  onDoubleTap: (pt) {
                    if (_moveMode) return;
                    _lastTouch = pt;
                    _dispatcher.sendDoubleTap(_mapToContent(pt, vp), vp);
                  },
                  onLongPress: (pt) {
                    if (_moveMode) return;
                    if (widget.settings.haptics) HapticFeedback.mediumImpact();
                    _lastTouch = pt;
                    _dispatcher.sendRightClick(_mapToContent(pt, vp), vp);
                  },
                  onScaleStart: (d) {
                    _baseScale = _scale;
                    _lastFocal = d.localFocalPoint;
                    _lastTouch = d.localFocalPoint;
                  },
                  onScaleUpdate: (d) {
                    final delta = d.localFocalPoint - _lastFocal;
                    _lastFocal = d.localFocalPoint;
                    _lastTouch = d.localFocalPoint;
                    if (d.scale != 1.0) {
                      _setZoom(_baseScale * d.scale, vp);
                    } else if (_moveMode) {
                      setState(() => _offset = _clampOffset(_offset + delta, vp));
                    } else {
                      _dispatcher.sendPointerMove(_mapToContent(d.localFocalPoint, vp), vp);
                    }
                  },
                ),
                Positioned(
                  top: 0, left: 0, right: 0,
                  child: _controlsVisible
                      ? StreamTopBar(
                          connected: _connected, telemetry: _telemetry, zoomPercent: (_scale * 100).round(),
                          isMoveMode: _moveMode, isKeyboardOpen: _showKbd, isMouseOpen: _showMouse,
                          onToggleKeyboard: () => setState(() => _showKbd = !_showKbd),
                          onToggleMouse: () => setState(() => _showMouse = !_showMouse),
                          onToggleMove: () => setState(() => _moveMode = !_moveMode),
                          onZoomIn: () => _setZoom(_scale + 0.5, vp), onZoomOut: () => _setZoom(_scale - 0.5, vp),
                          onResetZoom: () => setState(() { _scale = 1.0; _offset = Offset.zero; }),
                          onCollapse: () => setState(() => _controlsVisible = false), onClose: () => Navigator.pop(context),
                        )
                      : Align(
                          alignment: Alignment.topRight,
                          child: Padding(
                            padding: const EdgeInsets.all(8),
                            child: IconButton.filledTonal(onPressed: () => setState(() => _controlsVisible = true), icon: const Icon(Icons.keyboard_arrow_down_rounded)),
                          ),
                        ),
                ),
                Positioned(
                  bottom: 8, left: 0, right: 0,
                  child: Column(mainAxisSize: MainAxisSize.min, children: [
                    if (_showMouse)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 6),
                        child: StreamMouseDock(
                          dispatcher: _dispatcher, currentPoint: _mapToContent(_lastTouch, vp), viewportSize: vp,
                          isDragLocked: _dragLocked, onToggleDragLock: (v) => setState(() => _dragLocked = v),
                          onClose: () => setState(() => _showMouse = false),
                        ),
                      ),
                    if (_showKbd) StreamKeyboardDock(dispatcher: _dispatcher, onClose: () => setState(() => _showKbd = false)),
                  ]),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}
