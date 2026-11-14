# Stopped-session screen

The approved design replaces the plain stopped-state column in `MainActivity`:
the original Droide logo, a neutral **Session stopped** chip, **Ready when you are**
heading, concise saved-work message and one **Open Droide** button. The button's
label remains centered independently of its trailing, RTL-aware arrow.

## Presentation and ownership

`WorkspaceStoppedScreen` receives only `canOpen`, `failure` and `onOpen`. It has no
startup job, timer, progress indicator or automatic callback. `MainActivity`
continues to select this screen only when the interface epoch is absent.

The existing open/exit guards are unchanged: the button is disabled during an
unfinished exit and on the retiring Activity after exit completes. The callback
also retains its completed-exit guard. Only an explicit allowed click calls
`appOwner.openFromUser()`, immediately rearms `finishWhenExitCompletes`, attaches
the interface and registers its work owner. Saved files, recovery drafts, task
restart policy and manual CPU-lock behavior are not modified by this UI change.

An open failure replaces the normal reassurance with the actual failure text,
announced as an accessible error/live region. The existing exception handler and
failure state remain authoritative. Long messages can scroll without hiding the
Open action permanently or overlapping the footer.

## Shared layout

`DroideSessionDesign` owns the common colors, scrollable centered content, footer
and sharp original vector logo for stopped and loading screens. The welcome
screen retains its existing sizes, wording, real-stage captions and indeterminate
progress. Its existing runtime/error guard is unchanged; no extra welcome hold
or replay is introduced.

The stopped branch now uses the same `DroideWindowInsetsHost` as the startup and
workspace branches, respecting system bars and display cutouts. Short windows,
landscape and enlarged text can scroll the main content above a separate footer.
The button has a minimum height rather than a fixed height, so its text can grow.
The glow is static and cached; stopped state adds no perpetual animation.

## Verification scope

Targeted host compilation covers the exact current Activity, both session screens
and their shared design component. UI tests cover explicit opening, disabled
clicks, failure announcements/retry access, centered button text, large fonts and
RTL/landscape layout on Robolectric API 29 and 35. Existing welcome tests guard
the shared-layout extraction. The existing exit source contracts and selected
real-Activity test cover opening the stopped screen after a completed exit and
recreating that Activity without automatically restarting the workspace.

Host-rendered screenshots are for visual inspection, not device/frame-rate
certification. No full Gradle/APK/native application build is part of this change.
On a device, verify explicit Exit followed by reopening the app, opening Droide
from this screen, the failure/retry path, rotation and large system text.
