import 'dart:io';
import 'package:flutter/services.dart';

/// One permission FocusBro asks for, and the plain-language case for it.
///
/// Every entry states what it does, why this app needs it, and what breaks
/// without it (ARCHITECTURE.md §5). None of these decide whether the lock
/// *holds* — the OS holds that — which the copy says rather than implying the
/// lock is at risk.
class PermissionInfo {
  const PermissionInfo({
    required this.id,
    required this.title,
    required this.why,
    required this.breaks,
    this.critical = false,
    this.fallbackOnly = false,
  });

  final String id;
  final String title;
  final String why;
  final String breaks;

  /// Without this one there is no lock at all.
  final bool critical;

  /// Only relevant on devices that cannot be device owner — the screen-pinning
  /// fallback needs these; the device-owner path does not.
  final bool fallbackOnly;
}

const kPermissions = <PermissionInfo>[
  PermissionInfo(
    id: 'deviceOwner',
    title: 'Device owner',
    why:
        'This is what actually locks your phone. Set once over ADB, on a '
        'device with no accounts signed in.',
    breaks:
        'Everything. Without it FocusBro is just a timer you can walk away '
        'from.',
    critical: true,
  ),
  PermissionInfo(
    id: 'notifications',
    title: 'Notifications',
    why: 'Shows the running timer, and tells you the moment a session ends.',
    breaks:
        'No end-of-session alert. The timer notification is required by '
        'Android either way — it just stays silent.',
  ),
  PermissionInfo(
    id: 'batteryExempt',
    title: 'Ignore battery optimisation',
    why:
        'Stops your phone pausing FocusBro mid-session, which would freeze '
        'the countdown.',
    breaks:
        'On aggressive skins the countdown can stall and a session runs '
        'long — never past the three-hour cap.',
  ),
  PermissionInfo(
    id: 'exactAlarms',
    title: 'Exact alarms',
    why:
        'Ends a session at the exact minute, and starts scheduled routines on '
        'time instead of whenever Android gets around to it.',
    breaks:
        'Sessions can overrun and routines fire late. Manual sessions still '
        'work.',
  ),
  PermissionInfo(
    id: 'accessibility',
    title: 'Accessibility service',
    why:
        'Without device owner, this is what blocks other apps. FocusBro sees '
        'the name of the app that just opened, and sends you home if it is not '
        'one you allowed. It reads the app name only — it cannot see your '
        'screen, what you type, or what you send. Outside a session it does '
        'Night Guard sends you home from browsers, Reddit, Telegram, Facebook, '
        'Instagram and Snapchat from 21:00 to 08:00, and other social apps '
        'from 22:30 to 08:00. It also enforces individual app limits and breaks.',
    breaks:
        'Nothing blocks distracting apps on this device; sessions become a '
        'timer you can ignore.',
    fallbackOnly: true,
  ),
  PermissionInfo(
    id: 'screenPinning',
    title: 'Screen pinning',
    why:
        'A weaker fallback if you would rather not grant accessibility. It '
        'pins FocusBro to the screen, which also means no payment or emergency '
        'apps for the duration. Turn on "Ask for PIN before unpinning" so '
        'leaving early costs something.',
    breaks:
        'Nothing, if the accessibility service is on — that is the better '
        'of the two.',
    fallbackOnly: true,
  ),
  PermissionInfo(
    id: 'overlay',
    title: 'Display over other apps',
    why:
        'Lets a scheduled routine bring the session screen up by itself. '
        'Android blocks apps from launching screens in the background unless '
        'they hold this. FocusBro draws no overlays — it only uses the '
        'exemption.',
    breaks:
        'Routines post a notification you have to tap instead of starting '
        'on their own.',
    fallbackOnly: true,
  ),
  PermissionInfo(
    id: 'usageStats',
    title: 'Usage access',
    why:
        'Two jobs. It shows where your time went in the charts, and it is how '
        'blocking restarts itself after a reboot — the accessibility service '
        'only sees apps opened after it reconnects, so this catches the rest. '
        'Durations only, never what you typed, saw, or sent.',
    breaks:
        'Charts stay empty, and after a reboot blocking only resumes once '
        'you next open an app. On device owner, nothing.',
  ),
];

/// Permissions FocusBro deliberately does not request. Shown in Setup so the
/// user can verify the privacy claim rather than take it on faith (§16).
const kNotRequested = <String>[
  'Location',
  'Contacts or call logs',
  'Microphone or camera',
  'Read SMS or notifications',
];

/// What the network is used for, stated where the old "no internet" claim
/// used to be, so the privacy copy is checkable rather than aspirational.
const kNetworkUse =
    'FocusBro uses network access only so Android can validate the configured '
    'family DNS provider. It does not route your traffic through FocusBro.';

class Diagnostics {
  static const _owner = MethodChannel('focusbro/owner');

  bool get supported => Platform.isAndroid;

  Future<Map<String, bool>> health() async {
    if (!supported) return const {};
    final raw = await _owner.invokeMapMethod<String, dynamic>('health');
    return {for (final e in (raw ?? {}).entries) e.key: e.value == true};
  }

  Future<void> fix(String id) => _owner.invokeMethod('fix', {'id': id});

  /// Hands device ownership back so FocusBro can be uninstalled again.
  Future<bool> relinquish() async {
    if (!supported) return false;
    return await _owner.invokeMethod<bool>('relinquish') ?? false;
  }

  /// Opens Android's uninstall confirmation after ownership is relinquished.
  Future<bool> openUninstall() async {
    if (!supported) return false;
    return await _owner.invokeMethod<bool>('openUninstall') ?? false;
  }

  /// Bluetooth, Wi-Fi and mobile data state, plus whether mobile data can be
  /// toggled at all (it needs root — there is no device-owner path to it).
  Future<Map<String, bool>> radios() async {
    if (!supported) return const {};
    final raw = await _owner.invokeMapMethod<String, dynamic>('radios');
    return {for (final e in (raw ?? {}).entries) e.key: e.value == true};
  }

  /// Turns one radio on or off during a session, where Settings and the
  /// status bar are both out of reach. [kind] is bluetooth, wifi or
  /// mobileData. False means the platform refused it.
  Future<bool> setRadio(String kind, bool on) async {
    if (!supported) return false;
    return await _owner.invokeMethod<bool>('setRadio', {
          'kind': kind,
          'on': on,
        }) ??
        false;
  }

  /// Torch state and availability, and the current screen brightness (0..1).
  Future<Map<String, dynamic>> screen() async {
    if (!supported) return const {};
    return await _owner.invokeMapMethod<String, dynamic>('screen') ?? const {};
  }

  Future<bool> setTorch(bool on) async {
    if (!supported) return false;
    return await _owner.invokeMethod<bool>('setTorch', {'on': on}) ?? false;
  }

  /// Brightness of the session screen, 0..1. Window-level only: it needs no
  /// permission and the session screen is the whole display while locked.
  Future<void> setBrightness(double value) async {
    if (!supported) return;
    await _owner.invokeMethod('setBrightness', {'value': value});
  }

  /// Tethering state and the nightly cutoff (§23.3).
  Future<TetherStatus> tetherStatus() async {
    if (!supported) return const TetherStatus.unsupported();
    final raw = await _owner.invokeMapMethod<String, dynamic>('tetherStatus');
    if (raw == null) return const TetherStatus.unsupported();
    return TetherStatus(
      enforceable: raw['enforceable'] == true,
      restricted: raw['restricted'] == true,
      usbActive: raw['usbActive'] == true,
      hotspotActive: raw['hotspotActive'] == true,
      testingConnectivity: raw['testingConnectivity'] == true,
      nextChange: (raw['nextChangeUtc'] as num).toInt() == 0
          ? null
          : DateTime.fromMillisecondsSinceEpoch(
              (raw['nextChangeUtc'] as num).toInt(),
            ),
      cutoffMinute: (raw['cutoffMinute'] as num).toInt(),
      releaseMinute: (raw['releaseMinute'] as num).toInt(),
    );
  }

  /// Re-applies the tethering window from the clock and returns the result.
  Future<TetherStatus> tetherSync() async {
    if (!supported) return const TetherStatus.unsupported();
    await _owner.invokeMethod('tetherSync');
    return tetherStatus();
  }

  /// USB tethering on or off. Root only; false means nothing happened.
  Future<bool> setUsbTethering(bool on) async {
    if (!supported) return false;
    return await _owner.invokeMethod<bool>('setUsbTethering', {'on': on}) ??
        false;
  }

  /// Grants every special-access permission at once through root. False when
  /// root is absent or the user declines the prompt.
  Future<bool> rootGrantAll() async {
    if (!supported) return false;
    return await _owner.invokeMethod<bool>('rootGrantAll') ?? false;
  }

  Future<Map<String, dynamic>> oem() async {
    if (!supported) return const {};
    final raw = await _owner.invokeMapMethod<String, dynamic>('oem');
    return raw ?? const {};
  }

  Future<void> openOemSettings() => _owner.invokeMethod('openOemSettings');

  Future<void> startProbe(int minutes) =>
      _owner.invokeMethod('startProbe', {'minutes': minutes});

  Future<Map<String, dynamic>> probeResult() async {
    if (!supported) return const {'state': 'none'};
    final raw = await _owner.invokeMapMethod<String, dynamic>('probeResult');
    return raw ?? const {'state': 'none'};
  }

  // --- Adult Shield / Night Guard (§24) ---

  /// The full device-owner + VPN + DNS picture. Keys mirror the native
  /// ShieldPolicy.status map; the card reads only what it shows.
  Future<Map<String, dynamic>> shieldStatus() async {
    if (!supported) return const {};
    return await _owner.invokeMapMethod<String, dynamic>('shieldStatus') ??
        const {};
  }

  Future<Map<String, dynamic>> nightStatus() async {
    if (!supported) return const {};
    return await _owner.invokeMapMethod<String, dynamic>('nightStatus') ??
        const {};
  }

  /// Re-derives both policies from the clock and installed packages.
  Future<void> protectionSync() => _owner.invokeMethod('protectionSync');

  Future<bool> nightPackage(String package, {bool add = true}) async {
    if (!supported) return false;
    return await _owner.invokeMethod<bool>('nightPackage', {
          'package': package,
          'add': add,
        }) ??
        false;
  }
}

class TetherStatus {
  const TetherStatus({
    required this.enforceable,
    required this.restricted,
    required this.usbActive,
    required this.hotspotActive,
    required this.nextChange,
    required this.cutoffMinute,
    required this.releaseMinute,
    this.testingConnectivity = false,
  });

  const TetherStatus.unsupported()
    : enforceable = false,
      restricted = false,
      usbActive = false,
      hotspotActive = false,
      nextChange = null,
      testingConnectivity = false,
      cutoffMinute = 22 * 60 + 30,
      releaseMinute = 6 * 60;

  /// False without device owner — the restriction is a DPM primitive.
  final bool enforceable;

  /// Inside the nightly window: tethering is off and cannot be switched on.
  final bool restricted;
  final bool usbActive;
  final bool hotspotActive;
  final bool testingConnectivity;
  final DateTime? nextChange;
  final int cutoffMinute;
  final int releaseMinute;

  String get cutoffLabel => _clock(cutoffMinute);
  String get releaseLabel => _clock(releaseMinute);

  static String _clock(int minute) =>
      '${(minute ~/ 60).toString().padLeft(2, '0')}:'
      '${(minute % 60).toString().padLeft(2, '0')}';
}
