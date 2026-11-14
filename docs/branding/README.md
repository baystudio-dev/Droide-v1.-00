# Droide Android branding

The source is the user's second supplied image, retained byte-for-byte as
`droide-logo-source.png`. It is not generated or replaced with a different logo.
`source-provenance.json` records its SHA-256, the traced silhouette and conversion
parameters. `droide-logo.svg` is the reusable transparent vector master.

The blue/white boundary was traced deterministically at subpixel tolerance. The
ring, detached upper cap and central arrow remain three separate components.
The median original blue is `#076BFD`; the original background texture is removed
from the vector, retaining its shape rather than rendering a white square.
Silhouette intersection-over-union with the source mask is 99.834%. No generative
image model was used. The full source image remains the authority for future edits.

## Resource choices

* `mipmap-anydpi-v26`: opaque white background plus transparent blue foreground.
  Each layer uses a 108dp canvas. The entire logo fits within the centered 66dp
  safe circle, including the upper-right cap. Android applies the shape mask;
  there is no baked-in mask or shadow on either adaptive layer.
* `mipmap-anydpi-v33`: adds a white, alpha-only monochrome layer with exactly the
  same outline and placement as the foreground. Android/launcher chooses the
  actual theme colors. Both launcher and round launcher entries have both forms.
* Density-specific launcher and round PNGs: 48/72/96/144/192px fallback assets.
  They are useful to consumers requesting legacy bitmaps. They do not lower
  Droide's existing minimum supported Android version (API 29).
* `drawable/ic_stat_droide`: a 24dp transparent VectorDrawable containing a 20dp
  white silhouette. This is deliberately separate from the adaptive launcher
  drawable. Passing an opaque launcher bitmap as a small icon produces a square.

`AndroidManifest.xml` sets both application icon references. The four existing
notification builders use `withDroideIcons`: development session, project export,
individual package transfer and aggregate package transfers. This supplies
`R.drawable.ic_stat_droide` as the small icon and a resource-backed
`R.mipmap.ic_launcher` as the full-color large icon. Both come from the installed
app's resources; no system-default icon fallback or manufacturer check is used.
No notification channels, text, IDs, permission flows or actions changed.
No `setColor` or `setColorized` override was introduced, so system tint remains
available rather than forcing brand blue onto every notification surface.

## Android behavior and limits

Adaptive icons are supported from API 26. On this app's API 29–32 devices, the
ordinary blue adaptive icon is used. On API 33+, a supporting launcher can use
the monochrome layer when the user enables themed icons. Android 16 QPR2 also
offers automatic theming for apps without a custom monochrome layer, but this
app provides its own to avoid relying on an inferred shape. This does not force
themed icons on unsupported launchers or theme every Settings/OEM surface.
[Android adaptive-icon documentation](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)
and [official safe-circle codelab](https://developer.android.com/codelabs/basic-android-kotlin-compose-training-change-app-icon).

The small notification symbol appears in monochrome in the status bar. On
Android 12+, leaving notification color unset lets the system use the theme
color; earlier releases and OEM UI can differ. The large icon explicitly supplies
the Droide logo to notification cards instead of leaving this slot empty and
depending on the skin's app-icon fallback. Android controls its placement and
may use different layouts for small and large icons. Logo changes cannot override blocked channels,
notification permission or OS foreground-service behavior.
[Official notification design](https://developer.android.com/design/ui/mobile/guides/home-screen/notifications)
and [notification builder reference](https://developer.android.com/reference/android/app/Notification.Builder#setSmallIcon(int)).

Legacy launchers and density folders follow
[Android Studio asset guidance](https://developer.android.com/studio/write/create-app-icons).
This is an APK icon set, not a claim of Play Store listing publication.

## Verification

Run `python3 tools/verify_droide_icons.py` for resource geometry, PNG dimensions,
source hash, manifest and all notification-branding callsite checks. Run
`:app:testDebugUnitTest --tests '*DroideIconResourcesTest' --tests '*DroideNotificationIconsTest'` for native Android
VectorDrawable rendering at API 29 and 35, resource selection, safe-circle
coverage, white/transparent notification pixels and light/dark tint behavior.
The notification tests also check both icon slots after a notification parcel
round trip and preserve notification content, progress, channel and controls.
These host tests do not replace launcher/device acceptance.

On a device, verify launcher/app drawer/app-info icons, circle and squircle
masks where available, themed icons on/off, light/dark wallpaper themes, status
bar and notification shade for all four notification paths. Check small-icon
legibility at actual size. Launchers may cache the previous icon until refreshed.
