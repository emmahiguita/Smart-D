/// ============================================================================
/// ARCHIVO: stream_keyboard_dock.dart
/// QUÉ HACE: Teclado virtual inteligente con escritura en vivo tecla a tecla,
///           envío de párrafos largos y barra completa de atajos Windows.
/// CÓMO FUNCIONA: En modo En Vivo usa un carácter centinela (`\u200B`) dentro
///                del `TextField` para detectar Backspace, caracteres Unicode y
///                Enter en tiempo real sin perder pulsaciones.
/// POR QUÉ EXISTE: Provee escritura fluida desde iPhone hacia cualquier app PC.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import '../../services/input_dispatcher.dart';

class StreamKeyboardDock extends StatefulWidget {
  final InputDispatcher dispatcher;
  final VoidCallback onClose;

  const StreamKeyboardDock({super.key, required this.dispatcher, required this.onClose});

  @override
  State<StreamKeyboardDock> createState() => _StreamKeyboardDockState();
}

class _StreamKeyboardDockState extends State<StreamKeyboardDock> {
  static const _sentinel = '\u200B';
  final _ctrl = TextEditingController(text: _sentinel);
  final _focus = FocusNode();
  bool _paragraphMode = false;
  String _prevText = _sentinel;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _focus.requestFocus());
  }

  @override
  void dispose() {
    _ctrl.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _toggleMode() {
    setState(() {
      _paragraphMode = !_paragraphMode;
      _prevText = _paragraphMode ? '' : _sentinel;
      _ctrl.text = _prevText;
      if (!_paragraphMode) {
        _ctrl.selection = const TextSelection.collapsed(offset: 1);
      }
    });
    _focus.requestFocus();
  }

  void _onLiveChanged(String val) {
    if (_paragraphMode) return;
    if (val.length < _prevText.length) {
      final count = _prevText.length - val.length;
      for (var i = 0; i < count; i++) {
        widget.dispatcher.sendSpecialKey('backspace');
      }
    } else if (val.length > _prevText.length) {
      final added = val.substring(_prevText.length).replaceAll(_sentinel, '');
      for (final rune in added.runes) {
        widget.dispatcher.sendChar(String.fromCharCode(rune));
      }
    }
    // Restaura centinela para mantener detección continua de Backspace
    if (val != _sentinel) {
      _prevText = _sentinel;
      _ctrl.value = const TextEditingValue(
        text: _sentinel,
        selection: TextSelection.collapsed(offset: 1),
      );
    }
  }

  void _sendParagraph() {
    final txt = _ctrl.text.replaceAll(_sentinel, '');
    if (txt.isEmpty) return;
    widget.dispatcher.sendParagraph(txt);
    _ctrl.clear();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    const shortcuts = [
      ('Esc', 'escape'), ('Tab', 'tab'), ('Ctrl+C', 'ctrl_c'), ('Ctrl+V', 'ctrl_v'),
      ('Ctrl+A', 'ctrl_a'), ('Ctrl+Z', 'ctrl_z'), ('Ctrl+Y', 'ctrl_y'), ('Ctrl+S', 'ctrl_s'),
      ('Win+D', 'win_d'), ('Alt+Tab', 'alt_tab'), ('Supr', 'delete'),
      ('↑', 'up'), ('↓', 'down'), ('←', 'left'), ('→', 'right'),
    ];

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12),
      color: cs.surfaceContainerHigh.withValues(alpha: 0.94),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SizedBox(
              height: 34,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: shortcuts.length,
                separatorBuilder: (_, _) => const SizedBox(width: 6),
                itemBuilder: (_, i) {
                  final (label, key) = shortcuts[i];
                  return ActionChip(
                    label: Text(label, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
                    padding: EdgeInsets.zero,
                    visualDensity: VisualDensity.compact,
                    onPressed: () => widget.dispatcher.sendSpecialKey(key),
                  );
                },
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                IconButton.filledTonal(
                  onPressed: _toggleMode,
                  icon: Icon(_paragraphMode ? Icons.bolt_rounded : Icons.notes_rounded, size: 18),
                  tooltip: _paragraphMode ? 'Cambiar a escritura en vivo' : 'Cambiar a modo párrafo',
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _ctrl,
                    focusNode: _focus,
                    maxLines: _paragraphMode ? 3 : 1,
                    minLines: 1,
                    textInputAction: _paragraphMode ? TextInputAction.newline : TextInputAction.send,
                    decoration: InputDecoration(
                      hintText: _paragraphMode ? 'Pega o escribe un párrafo completo...' : 'Escribe aquí (envío en vivo)...',
                      isDense: true,
                    ),
                    onChanged: _onLiveChanged,
                    onSubmitted: (_) {
                      if (_paragraphMode) {
                        _sendParagraph();
                      } else {
                        widget.dispatcher.sendSpecialKey('enter');
                      }
                    },
                  ),
                ),
                const SizedBox(width: 6),
                if (_paragraphMode)
                  IconButton.filled(onPressed: _sendParagraph, icon: const Icon(Icons.send_rounded, size: 18))
                else
                  IconButton.filledTonal(
                    onPressed: () => widget.dispatcher.sendSpecialKey('enter'),
                    icon: const Icon(Icons.keyboard_return_rounded, size: 18),
                  ),
                IconButton(onPressed: widget.onClose, icon: const Icon(Icons.keyboard_hide_rounded, size: 20)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
