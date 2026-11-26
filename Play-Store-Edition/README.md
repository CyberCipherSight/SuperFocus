# FocusBro — Play Store Edition

Independent Play-only Flutter/Android source, based on version 1.0.3 (code 5) with the project repository link and privacy cleanup. This snapshot is not byte-identical to the currently distributed Play release.

Minimum Android: 9 (API 28). Timer-only, reminders and selected-app blocking have different optional permissions. Users can end sessions, revoke access, clear limits and uninstall.

## Build

Install Flutter and the Android SDK. Run `flutter pub get`, `flutter analyze`, and `flutter test`. For a release bundle, run `flutter build appbundle --release`. The official Play upload requires the registered signing identity, supplied separately by the maintainer; no signing credentials are included. An unsigned local bundle is not a Play-ready upload.

## Privacy

App usage and settings are processed locally. No developer-defined personal allowlist is included. The project link opens an external browser only on a user tap. The release manifest has no Internet permission or Device Owner receiver.

See [Android permission compatibility](docs/android-permissions-v4.md) for relevant platform differences and validation limitations.

Flutter analysis: clean. Flutter tests: 40 passed. Independent release AAB: built and bundletool-validated.
