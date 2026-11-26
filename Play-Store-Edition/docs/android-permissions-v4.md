# FocusBro Play edition — permission and reliability architecture

Scope: Play-only workspace, minSdk 28, targetSdk 36. Android 17/API 37 compatibility is planned and researched, not certified. Root and Device Owner editions are separate.

## Grants and capability probes

| Capability | Grant mechanism | Check |
|---|---|---|
| FOREGROUND_SERVICE | Normal manifest permission (28+) | checkSelfPermission + actual service promotion |
| FOREGROUND_SERVICE_SPECIAL_USE | Manifest normal permission (34+) and Play declaration | service promotion and policy review |
| RECEIVE_BOOT_COMPLETED | Normal manifest permission | checkSelfPermission; delivery is conditional |
| Accessibility | Explicit disclosure and system settings | enabled component in AccessibilityManager plus bound service |
| Usage Access | Explicit disclosure and system settings | AppOps checkOpNoThrow on 28; unsafeCheckOpNoThrow on 29+ |
| Overlay | Explicit disclosure and system settings | Settings.canDrawOverlays plus actual window creation |
| Notifications | No runtime prompt on 28–32; POST_NOTIFICATIONS on 33+ | runtime grant, areNotificationsEnabled, individual channel importance |
| Exact alarms | Built in before 31; SCHEDULE_EXACT_ALARM special access from 31 | AlarmManager.canScheduleExactAlarms |
| Battery | User-controlled settings, not automatic | PowerManager.isIgnoringBatteryOptimizations, ActivityManager.isBackgroundRestricted |
| OEM autostart | User-controlled, no universal API | Guidance and observed background test, not guessed status |

## Android version changes

| Android | API | Relevant difference |
|---|---:|---|
| 9 | 28 | FGS manifest permission and background restriction; use legacy AppOps check |
| 10 | 29 | Background activity launch restrictions; newer AppOps API |
| 11 | 30 | Package visibility, permission auto-reset, overlay settings may open list |
| 12 | 31 | Exact alarm special access; background FGS start restrictions; overlay touch security |
| 12L | 32 | Same permission model as 12 |
| 13 | 33 | Notification runtime request; foreground service task-manager stop; sideload restricted settings |
| 14 | 34 | Exact alarm access usually denied on fresh installs targeting 33+; FGS types and declaration |
| 15 | 35 | Force-stop cancels pending intents; visible overlay required for overlay-based FGS start exemption |
| 16 | 36 | Updated AppOps check semantics and job quotas; no new required grant for this offline design |
| 17 | 37 | Review new platform and target-37 behavior; local network permission not needed by offline FocusBro |

## Failure model

Consent, grant, service readiness and feature outcome are separate. The native snapshot returns permissionStates, selected/effective mode, device API, background restriction, session/limits service health and startup errors. Flutter rechecks on resume and every five seconds while its relevant card is visible. Notification requests use the runtime prompt when appropriate and settings after a persistent denial; missing settings routes fall back to app details. No permission is forced on or restored against user choice.

Strict requires consent + enabled and connected accessibility; otherwise reminder if usage and an alert channel are available; otherwise timer. Reminders also require a running monitoring service. Overlay failure may fall back to notifications. Exact alarm denial uses inexact scheduling; timestamps remain authoritative and delivery can be late. Expired temporary rules and stale daily limits without usage access must not block. Android force-stop, disabled services, task-manager stop and OEM background termination are intentional/unavoidable limitations, not states to bypass. Resume reconciles persisted rules and routine scheduling. OEM settings are advisory; no guarantee of reliable continuous operation.

## References

- https://developer.android.com/training/permissions/requesting-special
- https://developer.android.com/reference/android/app/AppOpsManager
- https://developer.android.com/develop/ui/views/notifications/notification-permission
- https://developer.android.com/develop/background-work/services/alarms
- https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- https://developer.android.com/develop/background-work/services/fgs/changes
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/about/versions/11/privacy/permissions
- https://developer.android.com/about/versions/12/behavior-changes-all
- https://developer.android.com/about/versions/15/behavior-changes-all
- https://developer.android.com/about/versions/17/summary
- https://support.google.com/googleplay/android-developer/answer/10964491

## Remaining device acceptance tests

Clean install, upgrade, denial, repeated denial, grant via settings, revoke during active session, disabled reminder channel, no overlay, no exact alarms, reboot, screen-off, background restricted, force-stop/relaunch, scheduled starts, repeated reminders, daily limit release, Xiaomi/HyperOS and other OEM settings. Test API 28/29/30/31/32/33/34/35/36/37 separately. Pure JVM policy tests are not physical device certification.
