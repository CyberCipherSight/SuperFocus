import 'package:flutter/material.dart';
import '../../platform/build_variant.dart';
import '../backup/backup_screen.dart';
import '../design/companion.dart';
import '../setup/setup_screen.dart';
import 'appearance_screen.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});
  void _open(BuildContext context, Widget screen) => Navigator.of(
    context,
  ).push(MaterialPageRoute<void>(builder: (_) => screen));
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Settings')),
    body: ListView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
      children: [
        const CompanionBanner(
          title: 'Make room for you.',
          message: 'Your protection, your rhythm, your style.',
          mood: CompanionMood.help,
        ),
        const SectionLabel('Protection & setup'),
        _Setting(
          icon: Icons.verified_user_outlined,
          title: 'Permissions & setup',
          subtitle: 'Check readiness and fix missing permissions.',
          onTap: () => _open(
            context,
            const SetupScreen(section: SetupSection.permissions),
          ),
        ),
        if (!kPlayEdition)
          _Setting(
            icon: Icons.monitor_heart_outlined,
            title: 'Advanced diagnostics',
            subtitle: 'Background checks and phone-specific guidance.',
            onTap: () => _open(
              context,
              const SetupScreen(section: SetupSection.diagnostics),
            ),
          ),
        const SectionLabel('Personalize'),
        _Setting(
          icon: Icons.palette_outlined,
          title: 'Appearance & motion',
          subtitle: 'Midnight Dojo, light mode and reduced motion.',
          onTap: () => _open(context, const AppearanceScreen()),
        ),
        _Setting(
          icon: Icons.auto_awesome_outlined,
          title: 'Request a custom look',
          subtitle: 'An appearance inspired by your personality.',
          onTap: () => showDialog<void>(
            context: context,
            builder: (_) => CompanionDialog(
              title: const Text('A little more you'),
              content: const Text(
                'A custom look can draw inspiration from the characters, stories, colors and moods you love. Contact the person who gave you FocusBro to discuss a personal design. Direct contact details will be added here when available.',
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text('Got it'),
                ),
              ],
            ),
          ),
        ),
        const SectionLabel('Your data'),
        _Setting(
          icon: Icons.backup_outlined,
          title: 'Backup & restore',
          subtitle: 'Export routines and focus history.',
          onTap: () => _open(context, const BackupScreen()),
        ),
        const SectionLabel('Experimental'),
        _Setting(
          icon: Icons.science_outlined,
          title: 'Custom appearance import',
          subtitle: 'Preview the upcoming creator-issued theme experience.',
          onTap: () => _open(context, const ThemeImportScreen()),
        ),
        const SectionLabel('About'),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  kAppTitle,
                  style: TextStyle(fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 8),
                Text(
                  kPlayEdition
                      ? 'Focus sessions block only the apps you select. To stop blocking, end your session, clear an app’s limits, or disable FocusBro in Android Accessibility settings.'
                      : kConnectivityTesting
                      ? 'Connectivity testing edition · Hotspot and tethering have no nightly cutoff. App protection still applies.'
                      : 'Advanced preview · Optional device administration and user-configured focus sessions.',
                ),
                const SizedBox(height: 8),
                const Text(
                  kPlayEdition
                      ? 'FocusBro works offline. App identifiers, usage durations, schedules and focus history are processed on this device. FocusBro has no accounts, analytics, advertising or uploads. You can export a backup to a location you choose, clear focus history from Progress, or remove all local data in Android Settings.'
                      : 'Focus history and routines stay on this device. Automatic DNS, VPN, night and root policies are disabled in this preview.',
                ),
              ],
            ),
          ),
        ),
      ],
    ),
  );
}

class _Setting extends StatelessWidget {
  const _Setting({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Card(
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 18,
          vertical: 10,
        ),
        leading: Icon(icon, color: Theme.of(context).colorScheme.primary),
        title: Text(title),
        subtitle: Text(subtitle),
        trailing: const Icon(Icons.chevron_right),
        onTap: onTap,
      ),
    ),
  );
}
