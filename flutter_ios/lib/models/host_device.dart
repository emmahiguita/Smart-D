/// ============================================================================
/// ARCHIVO: host_device.dart
/// QUÉ HACE: Define los modelos inmutables de dominio (PC anfitrión, telemetría
///           y preferencias de usuario).
/// CÓMO FUNCIONA: Usa clases tipadas con serialización JSON segura para lectura
///                y escritura directa sin dependencias externas.
/// POR QUÉ EXISTE: Separa los datos de la capa de red e interfaz gráfica (Clean).
/// ============================================================================
library;

import 'package:flutter/material.dart';

enum HostStatus { online, offline, unknown }

class HostDevice {
  final String id;
  final String name;
  final String address;
  final int companionPort;
  final HostStatus status;

  const HostDevice({
    required this.id,
    required this.name,
    required this.address,
    this.companionPort = 47991,
    this.status = HostStatus.unknown,
  });

  HostDevice copyWith({String? name, HostStatus? status}) => HostDevice(
        id: id,
        name: name ?? this.name,
        address: address,
        companionPort: companionPort,
        status: status ?? this.status,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'address': address,
        'companionPort': companionPort,
      };

  factory HostDevice.fromJson(Map<String, dynamic> j) => HostDevice(
        id: (j['id'] ?? j['address'] ?? '').toString(),
        name: (j['name'] ?? 'SmartDisplay PC').toString(),
        address: (j['address'] ?? '').toString(),
        companionPort: int.tryParse('${j['companionPort'] ?? 47991}') ?? 47991,
      );
}

class TelemetrySnapshot {
  final int cpuPercent;
  final int ramPercent;
  final bool hasData;

  const TelemetrySnapshot({this.cpuPercent = 0, this.ramPercent = 0, this.hasData = false});
}

class AppSettings {
  final ThemeMode themeMode;
  final int companionPort;
  final String pin;
  final bool haptics;

  const AppSettings({
    this.themeMode = ThemeMode.system,
    this.companionPort = 47991,
    this.pin = '',
    this.haptics = true,
  });

  AppSettings copyWith({ThemeMode? themeMode, int? companionPort, String? pin, bool? haptics}) => AppSettings(
        themeMode: themeMode ?? this.themeMode,
        companionPort: companionPort ?? this.companionPort,
        pin: pin ?? this.pin,
        haptics: haptics ?? this.haptics,
      );

  Map<String, dynamic> toJson() => {
        'themeMode': themeMode.name,
        'companionPort': companionPort,
        'pin': pin,
        'haptics': haptics,
      };

  factory AppSettings.fromJson(Map<String, dynamic> j) {
    final modeName = (j['themeMode'] ?? 'system').toString();
    final mode = ThemeMode.values.firstWhere((e) => e.name == modeName, orElse: () => ThemeMode.system);
    return AppSettings(
      themeMode: mode,
      companionPort: int.tryParse('${j['companionPort'] ?? 47991}') ?? 47991,
      pin: (j['pin'] ?? '').toString(),
      haptics: j['haptics'] != false,
    );
  }
}
