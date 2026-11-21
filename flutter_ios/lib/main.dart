/// ============================================================================
/// ARCHIVO: main.dart
/// QUÉ HACE: Punto de entrada principal de SmartDisplay AI en Flutter (Material 3).
/// CÓMO FUNCIONA: Carga ajustes persistidos con `StorageService` e instancia
///                `MaterialApp` con tema dinámico claro/oscuro/sistema.
/// POR QUÉ EXISTE: Reemplaza la interfaz anterior con Flutter Material 3 real.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'core/app_theme.dart';
import 'models/host_device.dart';
import 'services/storage_service.dart';
import 'ui/home/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SmartDisplayApp());
}

class SmartDisplayApp extends StatefulWidget {
  const SmartDisplayApp({super.key});

  @override
  State<SmartDisplayApp> createState() => _SmartDisplayAppState();
}

class _SmartDisplayAppState extends State<SmartDisplayApp> {
  final _storage = StorageService();
  AppSettings _settings = const AppSettings();

  @override
  void initState() {
    super.initState();
    _storage.loadSettings().then((s) {
      if (mounted) setState(() => _settings = s);
    });
  }

  void _updateSettings(AppSettings next) {
    setState(() => _settings = next);
    _storage.saveSettings(next);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SmartDisplay AI',
      debugShowCheckedModeBanner: false,
      themeMode: _settings.themeMode,
      theme: AppTheme.light(),
      darkTheme: AppTheme.dark(),
      home: HomeScreen(
        storage: _storage,
        settings: _settings,
        onSettingsChanged: _updateSettings,
      ),
    );
  }
}
