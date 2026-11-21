/// ============================================================================
/// ARCHIVO: host_card.dart
/// QUÉ HACE: Tarjeta Flutter Material 3 para cada PC anfitrión disponible.
/// CÓMO FUNCIONA: Estructura jerárquica de dos filas dentro de `Card` M3
///                (Icono tonal + Nombre con ellipsis + Chip de estado arriba;
///                botón eliminar + FilledButton "Conectar" abajo).
/// POR QUÉ EXISTE: Evita el corte vertical de texto y refleja diseño Flutter real.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/app_theme.dart';
import '../../models/host_device.dart';

class HostCard extends StatelessWidget {
  final HostDevice host;
  final bool hapticsEnabled;
  final VoidCallback onConnect;
  final VoidCallback? onDelete;

  const HostCard({
    super.key,
    required this.host,
    required this.hapticsEnabled,
    required this.onConnect,
    this.onDelete,
  });

  bool get _isOnline => host.status == HostStatus.online;

  String get _cleanName {
    final idx = host.name.indexOf('(');
    final raw = idx > 0 ? host.name.substring(0, idx).trim() : host.name.trim();
    return raw.isEmpty ? 'SmartDisplay PC' : raw;
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final statusColor = _isOnline ? AppTheme.onlineColor : AppTheme.errorColor;

    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 44,
                  height: 44,
                  decoration: BoxDecoration(
                    color: _isOnline ? AppTheme.onlineColor.withValues(alpha: 0.14) : cs.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(Icons.desktop_windows_rounded, color: _isOnline ? AppTheme.onlineColor : cs.onSurfaceVariant),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(_cleanName, maxLines: 1, overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                      const SizedBox(height: 2),
                      Text('${host.address}:${host.companionPort}', maxLines: 1, overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                fontFamily: 'monospace',
                                color: cs.onSurfaceVariant,
                              )),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                  decoration: BoxDecoration(
                    color: statusColor.withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(width: 7, height: 7, decoration: BoxDecoration(color: statusColor, shape: BoxShape.circle)),
                      const SizedBox(width: 6),
                      Text(_isOnline ? 'En línea' : 'Sin señal',
                          style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: statusColor)),
                    ],
                  ),
                ),
              ],
            ),
            const Divider(height: 20),
            Row(
              children: [
                if (onDelete != null)
                  IconButton.outlined(
                    onPressed: onDelete,
                    icon: const Icon(Icons.delete_outline_rounded, size: 18),
                    tooltip: 'Eliminar equipo',
                  ),
                const Spacer(),
                FilledButton.icon(
                  onPressed: () {
                    if (hapticsEnabled) HapticFeedback.lightImpact();
                    onConnect();
                  },
                  icon: const Icon(Icons.play_arrow_rounded, size: 18),
                  label: const Text('Conectar'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
