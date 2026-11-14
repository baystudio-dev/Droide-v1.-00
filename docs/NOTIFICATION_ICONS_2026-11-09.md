# Notification icon follow-up — 2026-11-09

The reported notification shade on iQOO Z9 displayed an Android placeholder,
while the other Droide logos were correct. The change applies through standard
Android APIs to all supported devices; it contains no OEM/model checks. The
user requested local work only: do not upload this increment to Dropbox yet.

## Findings and change

The baseline already used the correct Droide monochrome `smallIcon` in all four
notification builders, and the application manifest/adaptive/legacy icons were
correct. A static resource check alone therefore did not reproduce the reported
placeholder. The screenshot does not prove whether the installed APK, retained
notification or SystemUI's app-icon cache caused that specific placeholder.

One missing part of the notification payload was the full-color `largeIcon`:
the app left it unset and depended on the system skin's choice of app branding.
`withDroideIcons` now supplies both slots explicitly from the installed app:

- `smallIcon`: existing transparent white `ic_stat_droide`, for system tinting
  and status-bar use.
- `largeIcon`: existing `ic_launcher` via `Icon.createWithResource`, for the
  notification card. This supports the adaptive resource and avoids treating a
  vector/adaptive drawable as a bitmap through `BitmapFactory.decodeResource`.

Resource-backed icons are carried through the notification parcel; the app does
not decode or allocate a logo bitmap on every progress update. The same helper
covers developer sessions, export, individual transfers and aggregate transfers.
Channel IDs, actions, progress, content and service lifecycle are unchanged.
All existing logo image/vector/manifest bytes remain unchanged.

## Acode reference

Downloaded the latest main available during this audit from
`https://github.com/Acode-Foundation/Acode` using a shallow sparse checkout.
Commit: `568286d5186ebf37334a86d24a93eb36c1f6daac`, dated 2026-11-09.

[TerminalService.java](https://github.com/Acode-Foundation/Acode/blob/568286d5186ebf37334a86d24a93eb36c1f6daac/src/plugins/terminal/src/android/TerminalService.java#L381)
resolves `ic_notification`, then `ic_launcher_foreground`, then `ic_launcher`
in drawable resources and passes the resource ID to `setSmallIcon`. Its last
fallback is `android.R.drawable.sym_def_app_icon`. Its launcher foreground is a
vector. Acode does not set a large icon here and does not use a manufacturer
branch. That implementation confirms that ordinary Android resource icons are
the appropriate baseline; it is not evidence that every SystemUI skin will
render Droide the same way. Droide keeps direct compile-time resource references
and does not copy Acode's generic Android fallback.

## Verification limits

The focused checks compile/link the current branding resources with AAPT2,
compile the real shared notification helper, and run native-rendering
Robolectric tests on API 29 and 35. Tests cover alpha-only small icons, adaptive
resources, unchanged logo pixels, both notification slots, parcel round trips
and preservation of controls/content. The source check covers all four callers.
This is resource/helper verification, not a full app/APK build or an OEM
notification-shade screenshot test.

After a normal build/install, create a fresh developer-session notification and
check it collapsed and expanded, light/dark, together with export and transfer
notifications. Test on both standard Android and OEM skins. SystemUI controls
layout and icon placement; the precise placeholder reported on the phone still
needs this device confirmation. If it persists, inspect the installed APK and
posted notification payload before changing already-correct logo artwork.
