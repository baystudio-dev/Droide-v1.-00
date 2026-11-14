# Exit, crash resilience, licenses and release audit — 2026-11-10

The source changes are verified by focused host tests. No release APK was produced.
The normal `:app:assembleRelease` command failed at `:app:verifyTermuxOverlay`:
`owned PTY ABI v2 native rebuild pending: source-only contract is UNBUILT`.
Release signing credentials are also not configured in this build environment.

## Backup and baseline

The exact pre-audit source was backed up to the connected Dropbox account
`zoviaaa777@gmail.com`, in `!!! DROIDE BACKUP SEBELUM AUDIT RELEASE - 2026-11-10 !!!`.
The archive `Droide-Full-Source-2026-11-10-PRE-RELEASE-AUDIT.zip` contains 4,516
source files. Its SHA-256 is
`f28b5294cabd2095d5b62351d7b67dba0a1b63d7aee9cb8e99b69bc879f1ff3d`.
Upload completion, archive CRC and all source entry hashes were checked.
The previously successful developer APK is retained; it is not the result of this audit.

## Exit and crash fixes

| Finding | Resulting behavior |
| --- | --- |
| Renderer loss/low-memory cleanup destroyed a WebView without removing its native ownership record. Exit could await a view whose pane had already cleared it. | Successful destruction now acknowledges the exact view. Failed destruction remains visible and does not falsely complete Exit. |
| Native browser retirement could depend on Compose disposal while the original Activity was stopped. | The controller retains the original pane's release callback and invokes it directly before waiting. Repeated release is idempotent. |
| History finalizers were joined before some terminal/process stop requests were initiated. | Independent owner stops start after input drafts are durable and before history is joined. Publication authority is retired after history completes. |
| Unchanged accumulated chat drafts were repeatedly encoded, encrypted and republished. | Existing authenticated contents are reused; file/directory durability is still confirmed on retry. Readback compares decoded records without another large JSON encoding. |
| A completed Exit removed the UI but left Android's main process alive. | After all sequence steps acknowledge completion, a guarded main-thread callback terminates only this process. It checks the sequence and session epoch and never runs on failed preservation/cleanup. |
| Large license documents were rendered in one text layout; catalog reading occurred while opening Settings. | Text layouts are bounded and lazy, and assets/catalog load on IO only when their legal page is requested. |

Existing editor recovery still captures final IME/native deltas, preserves unrestored
backups and verifies durability before release. No arbitrary deadline discards unsaved
work, and no process-name scan or unowned-process kill was introduced. Device/storage
failures can still prevent safe completion; the changes do not promise a universal exit
time or absence of all crashes.

VS Code reference: commit `e646868227c3e3a9e3f24277c8c7220be42c0be7`,
`workingCopyBackupTracker.ts` (reuse matching content-version backups) and
`lifecycleService.ts` (start independent shutdown joiners before awaiting them).
Exact paths/hashes are in `reference-sources.json`. Android ownership and service
shutdown remain platform-specific.

## License coverage

The offline browser now exposes 309 entries: direct components, all 136 locked
Gradle runtime modules, retained standalone notices, 91 original Ubuntu package
copyright records and their referenced common-license texts. The 102 runtime text
files are copied byte-for-byte from the hash-verified bundled Ubuntu archive.

Corrections include the direct SnakeYAML mapping, Commons Codec's actual version,
Commons LICENSE/NOTICE pairs, original Joni/JCodings/SLF4J/jsoup notices, the Termux
module exception, SPAKE2 compatibility/reference attribution, JGit derivative notices
and OkHttp's embedded Public Suffix List NOTICE plus MPL-2.0 text. Resolved entries
inherit applicable supplementary notices instead of showing only a generic license.
Upstream text and embedded-artifact provenance are recorded in the sibling JSON files.

`generate_legal_browser_catalog.py --check` validates deterministic catalog output,
asset availability and original runtime text bytes offline. No executable rootfs files
are extracted by this generator. The third-party integrity index was refreshed for
160 baseline additions and one changed baseline README; native/AAR receipts and
vendor binaries were not repinned or modified.

The release-source hygiene check now distinguishes actual internal milestones from
checksums, library names and CPython ABI tags. Original license texts are checked by
the license/integrity gates, not rewritten to satisfy a prose scanner. The functional
editor recovery test selector was renamed; historical manifests remain unchanged.

Catalog coverage is not a claim that public corresponding-source distribution,
source offers, trademark permissions or Play Store declarations have been fulfilled.
Historical Alpine/QEMU notices/source offers remain, while their retired runtime
assets remain absent from the APK asset tree.

## Android permissions

| Declared permission | Purpose and request behavior |
| --- | --- |
| INTERNET | Provider APIs, browser, remote tools and downloads; normal permission. |
| ACCESS_NETWORK_STATE | Connectivity-aware transfers; normal permission. |
| WAKE_LOCK | Explicit developer work/CPU leases; released on successful Exit. |
| FOREGROUND_SERVICE | Ongoing developer and transfer services. |
| FOREGROUND_SERVICE_SPECIAL_USE | User-started developer session service. |
| FOREGROUND_SERVICE_DATA_SYNC | Transfers and exports. |
| RUN_USER_INITIATED_JOBS | User-initiated transfers on supported Android versions. |
| POST_NOTIFICATIONS | Runtime request on Android 13+ for work notifications. |
| NEARBY_WIFI_DEVICES | Runtime request when Discover is used for wireless ADB on Android 13+; `neverForLocation`. |

The app targets API 36 and supports API 29+. Only the last two are runtime prompts.
Import/export use Android's document picker and URI grants. Broad storage access,
camera, microphone, location and package-install permissions are not required by the
current flows and were not added. Browser camera/microphone/location requests remain
denied. Job services retain `BIND_JOB_SERVICE` and are not publicly exported.

## Verification and remaining release work

- All 15 production Kotlin units changed since the last successful developer APK
  compiled, along with 11 focused test units, using the current dependency classpath.
- 101 JVM/Robolectric tests passed: editor checkpoints/startup recovery, draft
  persistence and retry, process ownership, browser lifecycle, offline license loading,
  bounded text layout, and completed/failed Exit process termination.
- Four source-hygiene regression tests passed. Linux JNI support was compiled from
  the current filesystem source for host integration; this is not Android ABI evidence.
- Direct/resolved/runtime license checks, legal-browser generation, third-party
  integrity, bundled-runtime and Android transfer manifest checks passed.
- The full release-source check reaches the same native terminal gate that blocks
  Gradle. No gate was disabled, no release status was forged, and no debug key was
  substituted for a release key.
- No Android device was connected. Physical-device cold-start, low-memory WebView,
  active build/CLI/terminal Exit, service retirement, keyboard composition and draft
  recovery still need device verification. No complete APK build or release lint run
  passed for this source revision.

Next: rebuild and verify the terminal AAR/native outputs from their exact current
sources; collect the required Android/kernel/ABI execution evidence and publish fresh
measured receipts through the normal native verification workflow. Then configure the
maintainer's release signing privately and run `:app:assembleRelease`, release lint
and the required release/device checks. The existing source-debug build receipt and
host tests cannot substitute for those release inputs.
