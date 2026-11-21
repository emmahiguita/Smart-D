/// ============================================================================
/// ARCHIVO: discovery_service.dart
/// QUÉ HACE: Verifica estado en línea de PCs guardados y descubre nuevos equipos
///           SmartDisplay activos en la red Wi-Fi local (puerto 47991).
/// CÓMO FUNCIONA: Obtiene las interfaces IPv4 locales con `NetworkInterface`
///                y lanza sondas TCP paralelas acotadas (350ms) por lotes.
/// POR QUÉ EXISTE: Descubre hosts en < 1 segundo sin dependencias nativas mDNS.
/// ============================================================================
library;

import 'dart:async';
import 'dart:io';
import '../models/host_device.dart';

class DiscoveryService {
  static Future<bool> probeTcp(String host, int port, {int timeoutMs = 450}) async {
    try {
      final sock = await Socket.connect(host, port, timeout: Duration(milliseconds: timeoutMs));
      sock.destroy();
      return true;
    } catch (_) {
      return false;
    }
  }

  /// Sondea en paralelo todos los hosts guardados para actualizar su estado real.
  static Future<List<HostDevice>> probeSaved(List<HostDevice> hosts, int defaultPort) async {
    return Future.wait(hosts.map((h) async {
      final p = h.companionPort > 0 ? h.companionPort : defaultPort;
      final online = await probeTcp(h.address, p, timeoutMs: 550);
      return h.copyWith(status: online ? HostStatus.online : HostStatus.offline);
    }));
  }

  /// Escanea la subred Wi-Fi local (/24) buscando servidores SmartDisplay activos.
  static Future<List<HostDevice>> scanLocalSubnet(int port) async {
    final subnets = <String>{};
    try {
      final ifaces = await NetworkInterface.list(type: InternetAddressType.IPv4);
      for (final iface in ifaces) {
        for (final addr in iface.addresses) {
          if (addr.isLoopback) continue;
          final parts = addr.address.split('.');
          if (parts.length == 4) subnets.add('${parts[0]}.${parts[1]}.${parts[2]}');
        }
      }
    } catch (_) {}

    final found = <HostDevice>[];
    for (final prefix in subnets) {
      // Escaneo por lotes de 64 IPs para no saturar descriptores de archivo en iOS
      for (var start = 1; start <= 254; start += 64) {
        final end = (start + 63).clamp(1, 254);
        final batch = <Future<void>>[];
        for (var i = start; i <= end; i++) {
          final ip = '$prefix.$i';
          batch.add(() async {
            if (await probeTcp(ip, port, timeoutMs: 320)) {
              found.add(HostDevice(
                id: ip,
                name: 'SmartDisplay PC',
                address: ip,
                companionPort: port,
                status: HostStatus.online,
              ));
            }
          }());
        }
        await Future.wait(batch);
      }
    }
    return found;
  }
}
