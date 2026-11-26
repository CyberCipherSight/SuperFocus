import '../platform/build_variant.dart';
import 'dart:async';
import 'package:flutter/material.dart';
import '../platform/diagnostics.dart';
import '../session.dart';
import 'setup/lock_readiness_card.dart';
import 'setup/play_setup.dart';
import 'design/companion.dart';

/// The LOCKED screen (§4). Back is frozen here in Dart for phase 1; phase 3
/// replaces this with the native no-op OnBackPressedCallback, which is what
/// actually holds once lock-task mode is engaged.
class SessionScreen extends StatelessWidget {
  const SessionScreen({super.key, required this.controller});
  final SessionController controller;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final arming = controller.phase == SessionPhase.arming;

    return PopScope(
      canPop: kPlayEdition,
      child: Scaffold(
        body: SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: ListView(
              children: [
                const SizedBox(height: 24),
                Center(
                  child: Companion(
                    mood: arming ? CompanionMood.welcome : CompanionMood.rest,
                    size: arming ? 160 : 140,
                  ),
                ),
                Text(
                  arming ? 'Getting ready' : 'Stay with your intention.',
                  style: theme.textTheme.titleMedium?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                    letterSpacing: 2,
                  ),
                ),
                const SizedBox(height: 8),
                Semantics(
                  // Announce at intervals, not every second (§12).
                  liveRegion: !arming,
                  label: arming
                      ? 'Locking in ${controller.remaining.inSeconds} seconds'
                      : '${controller.remaining.inMinutes} minutes remaining',
                  child: ExcludeSemantics(
                    child: FittedBox(
                      fit: BoxFit.scaleDown,
                      alignment: Alignment.centerLeft,
                      child: Text(
                        arming
                            ? '${controller.remaining.inSeconds + 1}'
                            : formatCountdown(controller.remaining),
                        style: theme.textTheme.displayLarge?.copyWith(
                          fontFeatures: const [FontFeature.tabularFigures()],
                          fontWeight: FontWeight.w300,
                        ),
                      ),
                    ),
                  ),
                ),
                if (!arming && kPlayEdition) const PlayModeCard(active: true),
                if (!arming)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'Session ends at ${TimeOfDay.fromDateTime(DateTime.now().add(controller.remaining)).format(context)}',
                      style: theme.textTheme.bodyLarge,
                    ),
                  ),
                const SizedBox(height: 24),
                if (arming)
                  // Fitts's Law inverted: the escape is small and deliberate.
                  TextButton(
                    onPressed: controller.cancelArming,
                    child: const Text('Cancel'),
                  )
                else ...[
                  if (controller.level == EnforcementLevel.degraded)
                    const _PinnedNote()
                  else ...[
                    _EmergencyDock(controller: controller),
                    const SizedBox(height: 12),
                  ],
                  const SizedBox(height: 12),
                  if (kPlayEdition)
                    OutlinedButton(
                      onPressed: controller.endPlaySession,
                      child: const Text("End focus"),
                    ),
                  if (!kPlayEdition) const LockReadinessCard(),
                  const SizedBox(height: 12),
                  if (!kPlayEdition)
                    Card(
                      child: ExpansionTile(
                        title: const Text('Connection & screen'),
                        leading: const Icon(Icons.tune_rounded),
                        childrenPadding: const EdgeInsets.all(12),
                        children: const [
                          _TetherCard(),
                          SizedBox(height: 12),
                          _RadiosCard(),
                          SizedBox(height: 12),
                          _ScreenCard(),
                        ],
                      ),
                    ),
                ],
                const SizedBox(height: 32),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Gestalt: one visually bounded group, clearly separate from the timer, so
/// "these still work" reads instantly under stress (§3, §12).
class _EmergencyDock extends StatelessWidget {
  const _EmergencyDock({required this.controller});
  final SessionController controller;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 16),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        children: [
          Text('Calls & allowed apps', style: theme.textTheme.titleMedium),
          const SizedBox(height: 12),
          Wrap(
            alignment: WrapAlignment.center,
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final app in [...controller.dock, ...controller.allowedApps])
                SizedBox(
                  width: 84,
                  child: Semantics(
                    button: app.available,
                    label: app.available
                        ? app.label
                        : '${app.label}, not available on this device',
                    child: InkWell(
                      onTap: app.available
                          ? () => controller.launchDockApp(app)
                          : null,
                      borderRadius: BorderRadius.circular(12),
                      child: Opacity(
                        opacity: app.available ? 1 : 0.38,
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 10),
                          child: Column(
                            children: [
                              Icon(app.icon, size: 28),
                              const SizedBox(height: 6),
                              Text(
                                app.label,
                                style: theme.textTheme.labelSmall,
                                textAlign: TextAlign.center,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Under screen pinning the emergency dock cannot work: pinning allows exactly
/// one task, so launching the camera would break the pin rather than open the
/// camera. Saying so plainly beats showing six buttons that do nothing.
class _PinnedNote extends StatelessWidget {
  const _PinnedNote();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        children: [
          Text('This screen is pinned', style: theme.textTheme.labelLarge),
          const SizedBox(height: 8),
          Text(
            'Other apps stay shut while it holds. To leave, hold Back and '
            'Recents together. Emergency calls: press and hold power.',
            textAlign: TextAlign.center,
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}

/// Bluetooth and internet during a session, both directions.
///
/// Turning a radio off is not a way out of the lock — the allowlist decides
/// what may run either way — so these switch freely. Mobile data only appears
/// where it can actually be driven: there is no device-owner API for it, so
/// without root the row would be a switch that silently does nothing.
class _RadiosCard extends StatefulWidget {
  const _RadiosCard();

  @override
  State<_RadiosCard> createState() => _RadiosCardState();
}

class _RadiosCardState extends State<_RadiosCard> {
  final _diagnostics = Diagnostics();
  Map<String, bool> _state = const {};
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _refresh();
    // The radios can change from outside the app (headphones powering on, the
    // network dropping), so poll rather than trust the last write.
    _poll = Timer.periodic(const Duration(seconds: 5), (_) => _refresh());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final state = await _diagnostics.radios();
    if (!mounted) return;
    setState(() => _state = state);
  }

  Future<void> _toggle(String kind, bool on) async {
    final ok = await _diagnostics.setRadio(kind, on);
    if (!ok && mounted) {
      companionNotice(context, 'Your phone could not change that setting.');
    }
    // The adapters report the new state a moment after the call returns.
    await Future<void>.delayed(const Duration(seconds: 2));
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        children: [
          _RadioRow(
            icon: Icons.bluetooth,
            label: 'Bluetooth',
            value: _state['bluetooth'] ?? false,
            onChanged: (on) => _toggle('bluetooth', on),
          ),
          _RadioRow(
            icon: Icons.wifi,
            label: 'Wi-Fi',
            value: _state['wifi'] ?? false,
            onChanged: (on) => _toggle('wifi', on),
          ),
          if (_state['mobileDataControllable'] ?? false)
            _RadioRow(
              icon: Icons.signal_cellular_alt,
              label: 'Mobile data',
              value: _state['mobileData'] ?? false,
              onChanged: (on) => _toggle('mobileData', on),
            ),
        ],
      ),
    );
  }
}

class _RadioRow extends StatelessWidget {
  const _RadioRow({
    required this.icon,
    required this.label,
    required this.value,
    required this.onChanged,
  });

  final IconData icon;
  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      children: [
        Icon(icon, size: 22, color: theme.colorScheme.onSurface),
        const SizedBox(width: 12),
        Expanded(child: Text(label, style: theme.textTheme.bodyMedium)),
        Switch(value: value, onChanged: onChanged),
      ],
    );
  }
}

/// Torch and brightness: the two quick-settings tiles a locked phone still
/// needs at night. Neither loosens the lock.
class _ScreenCard extends StatefulWidget {
  const _ScreenCard();

  @override
  State<_ScreenCard> createState() => _ScreenCardState();
}

class _ScreenCardState extends State<_ScreenCard> {
  final _diagnostics = Diagnostics();
  bool _torchAvailable = false;
  bool _torch = false;
  double _brightness = 0.5;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final s = await _diagnostics.screen();
    if (!mounted) return;
    setState(() {
      _torchAvailable = s['torchAvailable'] == true;
      _torch = s['torch'] == true;
      _brightness = (s['brightness'] as num?)?.toDouble() ?? 0.5;
    });
  }

  Future<void> _toggleTorch(bool on) async {
    final ok = await _diagnostics.setTorch(on);
    if (!ok && mounted) {
      companionNotice(context, 'Your phone could not change that setting.');
    }
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        children: [
          if (_torchAvailable)
            _RadioRow(
              icon: _torch ? Icons.flashlight_on : Icons.flashlight_off,
              label: 'Torch',
              value: _torch,
              onChanged: _toggleTorch,
            ),
          Row(
            children: [
              Icon(
                Icons.brightness_6,
                size: 22,
                color: theme.colorScheme.onSurface,
              ),
              Expanded(
                child: Semantics(
                  label: 'Brightness',
                  child: Slider(
                    value: _brightness.clamp(0.05, 1.0),
                    // Never fully dark: 0 on some panels is an unreadable screen.
                    min: 0.05,
                    onChanged: (v) {
                      setState(() => _brightness = v);
                      _diagnostics.setBrightness(v);
                    },
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// USB tethering during a session (§23.3).
///
/// Honest about the two paths: with root the toggle works from here; without
/// it there is no public API to start tethering, so the card says to switch it
/// on beforehand — it persists once running. Either way the nightly cutoff
/// applies and is shown.
class _TetherCard extends StatefulWidget {
  const _TetherCard();

  @override
  State<_TetherCard> createState() => _TetherCardState();
}

class _TetherCardState extends State<_TetherCard> {
  final _diagnostics = Diagnostics();
  TetherStatus _status = const TetherStatus.unsupported();
  bool _root = false;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _refresh();
    // Interface state is read from the kernel, so a cheap poll is the only way
    // to notice the laptop being plugged in or unplugged.
    _poll = Timer.periodic(const Duration(seconds: 5), (_) => _refresh());
  }

  Future<void> _refresh() async {
    final status = await _diagnostics.tetherStatus();
    final health = await _diagnostics.health();
    if (!mounted) return;
    setState(() {
      _status = status;
      _root = health['root'] ?? false;
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _toggle() async {
    final ok = await _diagnostics.setUsbTethering(!_status.usbActive);
    if (!ok && mounted) {
      companionNotice(context, 'Needs root to toggle from here.');
    }
    await Future<void>.delayed(const Duration(seconds: 2));
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    if (!_status.enforceable) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final s = _status;

    final String line = s.testingConnectivity
        ? s.restricted
              ? 'Android still reports tethering restricted. Re-check it in Setup.'
              : s.usbActive
              ? 'USB tethering is on. This testing build has no hotspot or tethering cutoff.'
              : 'Hotspot and USB tethering are allowed at all hours. Enable them before a session, or use root/ADB.'
        : switch ((s.restricted, s.usbActive, _root)) {
            (true, _, _) =>
              'Off until ${s.releaseLabel}. Tethering is cut every '
                  'night at ${s.cutoffLabel}.',
            (false, true, _) =>
              'USB tethering is on. It stays on for the session '
                  'and stops at ${s.cutoffLabel}.',
            (false, false, true) =>
              'USB tethering is off. Cut nightly at '
                  '${s.cutoffLabel}.',
            (false, false, false) =>
              'USB tethering is off. Turn it on before a '
                  'session starts and it stays on. Cut nightly at ${s.cutoffLabel}.',
          };

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        children: [
          Icon(
            s.usbActive ? Icons.usb : Icons.usb_off,
            size: 22,
            color: s.restricted
                ? theme.colorScheme.onSurfaceVariant
                : theme.colorScheme.onSurface,
          ),
          const SizedBox(width: 12),
          Expanded(child: Text(line, style: theme.textTheme.bodySmall)),
          if (_root && !s.restricted)
            Switch(value: s.usbActive, onChanged: (_) => _toggle()),
        ],
      ),
    );
  }
}
