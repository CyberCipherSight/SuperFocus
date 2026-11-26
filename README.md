# FocusBro

An Android focus and distraction-management project with two independent editions.

| Feature | Play Store Edition | Advanced Edition (developer preview) |
|---|---|---|
| Audience | General Android users | Developers evaluating optional device administration |
| Focus sessions and schedules | Yes | Yes |
| Daily app limits | Yes | Yes |
| Timer / reminder / selected-app blocking modes | Yes | Advanced session enforcement |
| Android Device Owner | No | Optional, for owner-authorized testing |
| Manual session exit | Yes | Yes |
| Personal app allowlist | None | None |
| Root and automatic DNS/VPN/night/tethering policies | Not available | Disabled in this preview |

## Source

- [Play-Store-Edition](Play-Store-Edition/) — independent Flutter/Android project, based on Play v1.0.3 (code 5), with privacy cleanup and the repository link.
- [Advanced-Edition](Advanced-Edition/) — independent Flutter/Android developer preview v0.1.0, with a separate application ID and a visible session exit and administration-removal path.

The Play build is distributed through [Google Play](https://play.google.com/store/apps/details?id=com.cybersight.hyperfocus.appblocker). Availability depends on the testing track.

The advanced preview is not a production-ready APK. Device administration is intended only for a device you own or administer with explicit consent. Test removal and emergency access on a disposable device before relying on it. Root integration and inherited automatic device-wide policies are disabled pending further review. Neither edition includes the developer's personal APKs, device settings or signing keys.

## Development and validation

Each folder has its own build instructions. Both target Android 9 or later; complete device-level testing is pending. Flutter analysis and tests passed (40 Play, 38 advanced), and independent Android builds succeeded. These snapshots are not byte-identical to the currently distributed Play release.

Report bugs through the repository's Issues tab. Do not include device identifiers, personal usage data, or credentials in reports.

## Licence

Source is publicly viewable. A project-wide redistribution licence has not yet been selected; public visibility does not itself grant permission to redistribute or relicense the code. Third-party notices retain their own terms.
