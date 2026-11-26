# FocusBro Advanced — developer preview

Version 0.1.0; application ID `com.cybersight.hyperfocus.appblocker.advanced`. This is a reconstructed, privacy-cleaned source preview, not the personal edition or a reproduction of an archived APK.

## Status

The optional Android Device Owner tier, timed sessions, manual routines and app limits remain in the source. The preview has a visible End focus action, an owner deactivation option in Settings → Permissions & setup, and clear-app-limits controls. It does not activate automatic personal allowlists, repeating social breaks, root-based enforcement, DNS/VPN or nightly tethering restrictions. Inactive legacy policy code is retained for review.

Device-level deprovisioning and emergency-access tests are still pending. Do not provision your primary phone or anyone else's device. No release APK is approved for distribution yet.

## Build and test

Install Flutter and the Android SDK, then run `flutter pub get`, `flutter analyze` and `flutter test` from this directory. `flutter build apk --release` builds a local development-test artifact using the local debug signing identity; this is not a production distribution signature.

Flutter analysis: clean. Flutter tests: 38 passed. Android release APK: built successfully. No private signing materials or personal device data are included.

## Removal

End the focus session, then use Settings → Permissions & setup → Deactivate owner and uninstall. This is intended to clear FocusBro's own policies and open Android's uninstall prompt. Actual owner relinquishment must be verified on a disposable Android test device before production distribution.
