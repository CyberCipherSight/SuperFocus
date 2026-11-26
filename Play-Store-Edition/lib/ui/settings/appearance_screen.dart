import 'package:flutter/material.dart';
import '../design/appearance.dart';
import '../design/companion.dart';

class AppearanceScreen extends StatelessWidget {
  const AppearanceScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final appearance = AppearanceScope.maybeOf(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Appearance & motion')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const CompanionBanner(
            title: 'Midnight Dojo',
            message:
                'A little energy. A clear intention.\nMeet your focus companion.',
          ),
          const SectionLabel('Color mode'),
          Card(
            child: Column(
              children: [
                for (final mode in ThemeMode.values)
                  ListTile(
                    title: Text(switch (mode) {
                      ThemeMode.system => 'Use device setting',
                      ThemeMode.light => 'Light',
                      ThemeMode.dark => 'Dark',
                    }),
                    leading: Icon(switch (mode) {
                      ThemeMode.system => Icons.brightness_auto_outlined,
                      ThemeMode.light => Icons.light_mode_outlined,
                      ThemeMode.dark => Icons.dark_mode_outlined,
                    }),
                    trailing: appearance?.mode == mode
                        ? const Icon(Icons.check_circle)
                        : null,
                    onTap: appearance == null
                        ? null
                        : () => appearance.update(themeMode: mode),
                  ),
              ],
            ),
          ),
          const SectionLabel('Motion'),
          Card(
            child: SwitchListTile(
              title: const Text('Reduce motion'),
              subtitle: const Text(
                'Keep character illustrations still. Device accessibility settings are also respected.',
              ),
              value: appearance?.reduceMotion ?? false,
              onChanged: appearance == null
                  ? null
                  : (v) => appearance.update(reducedMotion: v),
            ),
          ),
          const SectionLabel('Character expressions'),
          const Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              Companion(mood: CompanionMood.stop, size: 100),
              Companion(mood: CompanionMood.rest, size: 100),
              Companion(mood: CompanionMood.sleep, size: 100),
            ],
          ),
          const SizedBox(height: 20),
          OutlinedButton.icon(
            onPressed: appearance == null
                ? null
                : () => appearance.update(
                    themeMode: ThemeMode.dark,
                    reducedMotion: false,
                  ),
            icon: const Icon(Icons.restore),
            label: const Text('Restore default appearance'),
          ),
        ],
      ),
    );
  }
}

/// Deliberately a preview: no unverified pack parser or executable theme code.
class ThemeImportScreen extends StatelessWidget {
  const ThemeImportScreen({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Custom appearance import')),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        const CompanionBanner(
          title: 'Your next look.',
          message: 'A personal world, made for your focus.',
          mood: CompanionMood.help,
        ),
        const SectionLabel('Experimental · Preview'),
        const Card(
          child: Padding(
            padding: EdgeInsets.all(20),
            child: Text(
              'Custom appearance packs will be provided by the FocusBro creator. File import is not available in this version.',
            ),
          ),
        ),
        const SectionLabel('Current appearance'),
        const ListTile(
          contentPadding: EdgeInsets.zero,
          leading: Icon(Icons.palette_outlined),
          title: Text('Midnight Dojo'),
          subtitle: Text('Included with FocusBro'),
          trailing: Icon(Icons.check_circle_outline),
        ),
        const SectionLabel('How it will feel'),
        for (final step in const [
          (
            '1',
            'Choose a creator-issued pack',
            'Select the appearance file you received.',
          ),
          (
            '2',
            'Preview your look',
            'See the home screen, companion and block messages.',
          ),
          (
            '3',
            'Apply your appearance',
            'Return to the included appearance whenever you want.',
          ),
        ])
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Card(
              child: ListTile(
                contentPadding: const EdgeInsets.all(16),
                leading: CircleAvatar(child: Text(step.$1)),
                title: Text(step.$2),
                subtitle: Text(step.$3),
              ),
            ),
          ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: null,
          icon: const Icon(Icons.file_open_outlined),
          label: const Text('Import pack · Coming later'),
        ),
        const SizedBox(height: 12),
        const Text(
          'Appearance packs are separate from backups of your routines and history.',
        ),
      ],
    ),
  );
}
