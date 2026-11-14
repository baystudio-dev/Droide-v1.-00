# Workspace loading screen

The approved dark welcome design replaces the existing startup spinner in
`MainActivity`. `WorkspaceLoadingScreen` renders the existing Droide vector logo,
welcome message, slim blue loading bar, current startup stage and footer.

## Lifetime and progress

The existing workspace owner remains authoritative. The screen is composed only
in the existing branch where no runtime has been published and no initialization
error has been delivered. Exit/stopped UI retains precedence. An already available
runtime (including a warm Activity recreation) goes directly to the workspace.
Publishing a runtime immediately replaces welcome with the editor; no minimum
duration, delay, completion animation or extra initialization job was introduced.
Project switching continues to use the active runtime until its replacement is
committed, without showing this startup screen.

The stage caption observes `WorkspaceCoordinator.startup` only while welcome is
mounted. Initialization timing, cleanup, recovery, timeout and retry policy are
unchanged. A failed stage may still be cleaning up before the host delivers its
error; the existing failure UI and recovery actions take over on delivery.

The bar is **indeterminate**. Startup phases are not a measured fraction of the
total work, so the sample `68%` in the design is intentionally not a fabricated
runtime percentage. The Material indicator follows Android's animation scale.
The logo glow is static and the screen owns no background work.

## Layout and accessibility

The existing window inset host supplies status/navigation/cutout safe space.
The welcome group is centered when it fits and scrolls in a short/narrow window
or with enlarged text. The footer occupies a separate layout slot. Text wraps
without ellipsis. The title is an accessibility heading, stage updates are a
polite live region, and the indicator reports indeterminate progress.

The original `ic_droide_foreground` vector is reused inside a white rounded tile,
with a larger vector canvas clipped to the tile to compensate for its launcher
safe-zone padding without scaling a rasterized layer. Adaptive
launcher drawables are not passed to Compose's `painterResource`, which cannot
load them. Existing launcher and notification resources are unchanged.

## Verification scope

Targeted host compilation includes the exact current `MainActivity`, new screen,
and the unchanged project catalog dependencies required by the startup error UI.
`WorkspaceLoadingScreenTest` covers stage updates with indeterminate semantics,
stable layout, large text in a narrow/short window, landscape/RTL and disabled
system animations, on Robolectric API 29 and 35. `WorkspaceStartupRecoveryUiTest`
covers the existing error/recovery actions. An optional test property captures
the rendered welcome surface for visual review.

This is a host verification scope, not a full APK/native build or a device
startup/frame-rate measurement. On device, check a cold startup, warm return,
rotation while loading, a fast startup and the existing error/retry flow. There
must be no intentional welcome hold after readiness and no welcome replay on a
ready workspace.
