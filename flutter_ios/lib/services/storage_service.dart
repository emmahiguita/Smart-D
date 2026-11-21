/// ============================================================================
/// ARCHIVO: storage_service.dart
/// QUÉ HACE: Persiste en disco la lista de PCs guardados y ajustes de usuario.
/// CÓMO FUNCIONA: Escribe JSON directo en `$HOME/Documents` del sandbox iOS
///                usando `dart:io`, sin requerir plugins nativos ni CocoaPods.
/// POR QUÉ EXISTE: Garantiza almacenamiento local real y compilación ligera.
/// ============================================================================
library;

import 'dart:convert';
import 'dart:io';
import '../models/host_device.dart';

class StorageService {
  static Directory _docsDir() {
    if (Platform.isAndroid) {
      final dir = Directory('/data/user/0/com.limelight.smartdisplay.smartdisplay_ios/files');
      try {
        if (!dir.existsSync()) dir.createSync(recursive: true);
        return dir;
      } catch (_) {
        return Directory.systemTemp;
      }
    }
    final home = Platform.environment['HOME'] ?? Directory.systemTemp.path;
    final dir = Directory('$home/Documents');
    if (!dir.existsSync()) dir.createSync(recursive: true);
    return dir;
  }

  File get _hostsFile => File('${_docsDir().path}/sd_hosts.json');
  File get _settingsFile => File('${_docsDir().path}/sd_settings.json');

  Future<List<HostDevice>> loadHosts() async {
    try {
      if (!await _hostsFile.exists()) return [];
      final raw = jsonDecode(await _hostsFile.readAsString());
      if (raw is! List) return [];
      return raw.whereType<Map<String, dynamic>>().map(HostDevice.fromJson).toList();
    } catch (_) {
      return [];
    }
  }

  Future<void> saveHosts(List<HostDevice> hosts) async {
    try {
      await _hostsFile.writeAsString(jsonEncode(hosts.map((h) => h.toJson()).toList()));
    } catch (_) {}
  }

  Future<AppSettings> loadSettings() async {
    try {
      if (!await _settingsFile.exists()) return const AppSettings();
      final raw = jsonDecode(await _settingsFile.readAsString());
      return raw is Map<String, dynamic> ? AppSettings.fromJson(raw) : const AppSettings();
    } catch (_) {
      return const AppSettings();
    }
  }

  Future<void> saveSettings(AppSettings settings) async {
    try {
      await _settingsFile.writeAsString(jsonEncode(settings.toJson()));
    } catch (_) {}
  }
}
