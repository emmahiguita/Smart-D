/// ============================================================================
/// ARCHIVO: home_screen.dart
/// QUÉ HACE: Pantalla principal Flutter Material 3 con descubrimiento LAN,
///           filtros, hero de conexión rápida, ajustes y diseño adaptativo.
/// CÓMO FUNCIONA: Usa `LayoutBuilder` para cambiar automáticamente entre vista
///                vertical y 2 columnas en horizontal (Landscape) sin solaparse.
/// POR QUÉ EXISTE: Orquesta la detección y conexión de PCs en Flutter puro.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import '../../models/host_device.dart';
import '../../services/discovery_service.dart';
import '../../services/storage_service.dart';
import '../stream/stream_screen.dart';
import 'add_host_sheet.dart';
import 'host_card.dart';
import 'settings_dialog.dart';

class HomeScreen extends StatefulWidget {
  final StorageService storage;
  final AppSettings settings;
  final ValueChanged<AppSettings> onSettingsChanged;

  const HomeScreen({super.key, required this.storage, required this.settings, required this.onSettingsChanged});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  List<HostDevice> _saved = [];
  List<HostDevice> _discovered = [];
  bool _onlyOnline = false;
  bool _scanning = false;

  @override
  void initState() {
    super.initState();
    _loadAndScan();
  }

  List<HostDevice> get _allHosts {
    final savedIps = _saved.map((h) => h.address).toSet();
    final combined = [..._saved, ..._discovered.where((h) => !savedIps.contains(h.address))];
    return _onlyOnline ? combined.where((h) => h.status == HostStatus.online).toList() : combined;
  }

  HostDevice? get _primaryHost =>
      _allHosts.where((h) => h.status == HostStatus.online).firstOrNull ?? _allHosts.firstOrNull;

  Future<void> _loadAndScan() async {
    if (_scanning) return;
    setState(() => _scanning = true);
    final saved = await widget.storage.loadHosts();
    final probed = await DiscoveryService.probeSaved(saved, widget.settings.companionPort);
    final found = await DiscoveryService.scanLocalSubnet(widget.settings.companionPort);
    if (!mounted) return;
    setState(() {
      _saved = probed;
      _discovered = found;
      _scanning = false;
    });
  }

  Future<void> _addHost() async {
    final added = await showModalBottomSheet<HostDevice>(
      context: context,
      isScrollControlled: true,
      builder: (_) => AddHostSheet(defaultPort: widget.settings.companionPort),
    );
    if (added == null) return;
    final next = [added, ..._saved.where((h) => h.address != added.address)];
    await widget.storage.saveHosts(next);
    if (mounted) setState(() => _saved = next);
    _loadAndScan();
  }

  Future<void> _deleteHost(HostDevice host) async {
    final next = _saved.where((h) => h.id != host.id).toList();
    await widget.storage.saveHosts(next);
    if (mounted) setState(() => _saved = next);
  }

  void _openStream(HostDevice host) {
    Navigator.push(context, MaterialPageRoute(builder: (_) => StreamScreen(host: host, settings: widget.settings)));
  }

  Future<void> _openSettings() async {
    final next = await showSettingsDialog(context, widget.settings);
    if (next != null) widget.onSettingsChanged(next);
  }

  @override
  Widget build(BuildContext context) {
    final hosts = _allHosts;
    return Scaffold(
      appBar: AppBar(
        title: const Text('SmartDisplay AI', style: TextStyle(fontWeight: FontWeight.w700)),
        actions: [
          if (_scanning) const Padding(padding: EdgeInsets.only(right: 8), child: SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))),
          IconButton(onPressed: _loadAndScan, icon: const Icon(Icons.refresh_rounded), tooltip: 'Buscar PCs'),
          IconButton(onPressed: _openSettings, icon: const Icon(Icons.tune_rounded), tooltip: 'Ajustes'),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _addHost,
        icon: const Icon(Icons.add_rounded),
        label: const Text('Añadir PC'),
      ),
      body: LayoutBuilder(
        builder: (context, constraints) {
          final isLandscape = constraints.maxWidth > 620;
          if (isLandscape) {
            return Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(width: 280, child: SingleChildScrollView(padding: const EdgeInsets.all(16), child: _heroCard(context))),
                Expanded(child: _hostListView(hosts)),
              ],
            );
          }
          return RefreshIndicator(
            onRefresh: _loadAndScan,
            child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 88), children: [
              _heroCard(context), const SizedBox(height: 16),
              _filterRow(hosts.length), const SizedBox(height: 10),
              if (hosts.isEmpty) _emptyCard(context) else ...hosts.map(_buildCardItem),
            ]),
          );
        },
      ),
    );
  }

  Widget _hostListView(List<HostDevice> hosts) => RefreshIndicator(
        onRefresh: _loadAndScan,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(8, 16, 16, 88),
          children: [_filterRow(hosts.length), const SizedBox(height: 10), if (hosts.isEmpty) _emptyCard(context) else ...hosts.map(_buildCardItem)],
        ),
      );

  Widget _buildCardItem(HostDevice h) => Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: HostCard(
          host: h,
          hapticsEnabled: widget.settings.haptics,
          onConnect: () => _openStream(h),
          onDelete: _saved.any((s) => s.id == h.id) ? () => _deleteHost(h) : null,
        ),
      );

  Widget _filterRow(int count) => Row(
        children: [
          Text('Equipos ($count)', style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
          const Spacer(),
          FilterChip(label: const Text('Todos'), selected: !_onlyOnline, onSelected: (_) => setState(() => _onlyOnline = false)),
          const SizedBox(width: 6),
          FilterChip(label: const Text('En línea'), selected: _onlyOnline, onSelected: (_) => setState(() => _onlyOnline = true)),
        ],
      );

  Widget _heroCard(BuildContext context) {
    final target = _primaryHost;
    final cs = Theme.of(context).colorScheme;
    return Card(
      color: cs.primaryContainer.withValues(alpha: 0.45),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(children: [
          FilledButton(
            onPressed: target != null ? () => _openStream(target) : _addHost,
            style: FilledButton.styleFrom(shape: const CircleBorder(), padding: const EdgeInsets.all(22)),
            child: Icon(target != null ? Icons.play_arrow_rounded : Icons.add_rounded, size: 34),
          ),
          const SizedBox(height: 12),
          Text(target != null ? 'Conectar Escritorio' : 'Añadir Equipo', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
          const SizedBox(height: 4),
          Text(target != null ? 'Listo en ${target.address}' : 'Detecta tu PC en la misma red Wi-Fi',
              maxLines: 1, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant)),
        ]),
      ),
    );
  }

  Widget _emptyCard(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(children: [
            Icon(Icons.wifi_find_rounded, size: 34, color: Theme.of(context).colorScheme.onSurfaceVariant),
            const SizedBox(height: 8),
            const Text('Buscando equipos en tu Wi-Fi...', style: TextStyle(fontWeight: FontWeight.w600)),
            Text('Inicia SmartDisplay en tu PC o pulsa "Añadir PC".', textAlign: TextAlign.center, style: Theme.of(context).textTheme.bodySmall),
          ]),
        ),
      );
}
