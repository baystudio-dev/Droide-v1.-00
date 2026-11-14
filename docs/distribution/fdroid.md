# F-Droid distribution candidate

This distribution is derived from the latest working source, including the environment
recovery audit. Version 1.01 (45) is prepared for packaging review; it does not imply
acceptance by F-Droid.

## Environment setup

The first launch offers **Install environment** and **Not now**. Ubuntu Base is fetched
directly from Canonical only after explicit consent. The existing editor remains available
when installation is deferred. Using a feature that needs Ubuntu presents setup again.

Installation belongs to the application process, not an Activity or a Compose observer.
Repeated taps, rotation and additional terminal/agent requests share one operation.
The installer verifies the compressed archive and the uncompressed TAR before extraction,
uses private staging, checks the guest, and atomically publishes it. Progress reflects the
download and actual installation phases; no artificial delay or percentage is used.

Completed environments are reused across launches and app updates. User-installed packages,
HOME and recovery trees are preserved. A preference alone never proves installation.
An interrupted installation requires a new user action; cancellation waits for cleanup.
Within the current process, a cancelled or failed activation remains unconfirmed even if
the guest directory was published. Retry runs the installer's health validation before
reporting completion; opening setup again cannot clear that failure. Bootstrap recovery
rejects symlinked guest/staging/backup paths before cleanup or restore, preserving their targets.
Unhealthy existing environments are preserved for repair, never silently replaced.
Clearing application data or explicitly resetting the environment requires installation again.

This changes first-install delivery: fresh Ubuntu installation requires internet access.
An already installed environment continues to work offline. Ubuntu currently requires an
ARM64 Android process, as in the regular distribution. There is no bundled compiler.

## Preserved capabilities

The same editor, terminal/PTY, Run/Build, LSP, debugger, Git, ADB, browser, export, MCP,
skills, media tools, AI providers and external CLI adapters remain available. No provider
or extension is hidden to pass a scan. Managed executable downloads disclose their external
source and require consent covering the selected installation plan. Generic agent approval
does not substitute for this consent. Ordinary terminal input does not acquire per-command prompts.

## Source and dependency closure

| Component | Candidate build |
| --- | --- |
| Terminal emulator and view | Complete Termux v0.118.0 plaintext source plus the existing Droide overlays |
| PRoot and talloc | Pinned OpenMinis source, existing seccomp patch and static talloc |
| PRoot loaders | Pinned Termux PRoot 5.1.107 revision 70 source; ARM64 and ARM32 loaders |
| Droide CLI, terminal JNI, file JNI | C source with Android NDK 28.2.13676358 |
| JGit Android | Complete upstream source with the existing four Android adaptation families |
| Kadb mDNS and SPAKE2 | Plaintext Kotlin/Java source compiled with the app |
| Other libraries | Locked, SHA-256-verified Google Maven/Maven Central artifacts |
| Ubuntu Base | Optional Canonical download, exact compressed and TAR identities |

Each standalone source builder performs two independent compilations and compares output.
Receipts bind sources, compiler inputs and products. This proves those measured compilations,
not cross-machine APK reproducibility or Android device behavior. Runtime native hash/ELF
admission remains enabled, using generated identities tied to the freshly compiled products.

The F-Droid lane uses R8 9.1.31 from Google Maven. Compared with the regular lane's 9.1.29,
upstream changes are `Fix exclude_api_database on Windows` and `Fix KeepForApi import`.
The regular lane remains on its original optimizer and dependency lock. The candidate has
a separate lockfile and distribution-specific legal inventory; complete license texts remain
available offline, including licenses for optional Ubuntu packages.

## Build

Provision JDK 17, Gradle 8.13, Android platform/build-tools 36, CMake 3.22.1,
NDK 28.2.13676358, Python 3.11+, GNU make and patch externally. Do not put toolchain binaries
or signing secrets in the source distribution. The Android SDK platform identity is checked
by the terminal source manifest. Dependency verification is strict.

Export to a new directory outside the working source:

```sh
python3 tools/export_fdroid_source.py --root "$PWD" --output /absolute/path/Droide-fdroid
```

The exporter selects the trusted-repository settings and removes only generated/local state,
unused legacy binary artifacts, superseded source archives and the optional rootfs payload.
It writes a manifest with every copied and excluded source path and its hash. The original
working source remains intact. The exported distribution contains the required plaintext
corresponding source; it is not a generic source archive for the regular binary-backed lane.

Build from that exported directory, supplying absolute paths:

```sh
python3 tools/build_fdroid_candidate.py \
  --sdk /absolute/path/android-sdk \
  --java-home /absolute/path/jdk-17 \
  --gradle /absolute/path/gradle-8.13/bin/gradle \
  --dependencies /absolute/path/fdroid-source-products
```

Compilation must run in F-Droid's **build** phase, after source scanning, not its prebuild
phase. Gradle resolves declared dependencies from trusted repositories; the source builders
do not fetch executable artifacts. Use a new product directory when sources/toolchains change;
an existing mismatched receipt fails rather than reusing a stale binary.

Release output is `app/build-source-release-fdroid-candidate/outputs/apk/release/`.
The verification receipt is under the same build directory's `outputs/`. `--debug` builds a
separate debug candidate. The release target also runs release lint, APK/native/DEX integrity,
asset, alignment and unsigned-output checks. No private release key is required or uploaded.

## Submission and update boundary

Before publication, assign a new versionCode/tag from the final public source, run the current
F-Droid source and binary scanner, and submit a recipe for that exact commit. Review the
app's optional proprietary services/add-ons/assets for appropriate `NonFreeNet`, `NonFreeAdd`
and `NonFreeAssets` declarations; labels do not grant redistribution rights.

Perform physical ARM64 testing of fresh setup, cancellation/offline retry, existing guest/HOME
reuse, SSH/VPN, long-running PTY, TUI/Unicode, Git/ADB, Run/Build/LSP, agent tooling and Exit.
Newly compiled native providers require device validation even when ABI and hash checks pass.
Keep the current release available until this candidate passes those checks.

F-Droid acceptance and update signing are separate decisions. APKs with different signing keys
cannot update one another. Validate reproducible-build/upstream-signature support before promising
cross-channel updates; never provide a private keystore or password to the build repository.

### Candidate 1.01

The public source-only branch is `fdroid/release-1.01`; the proposed immutable tag is
`v1.01-fdroid`. The regular development tree retains both distribution lanes. The exporter
selects the F-Droid repositories, README and CI workflow for the source-only branch.

`tools/fdroid/com.baystudio.droide.yml` is a proposed fdroiddata recipe. It builds after
source scanning, uses the buildserver Gradle selected by the retained wrapper properties,
and declares optional proprietary network services, add-ons and third-party marks.
It does not use `scanignore`, `scandelete`, upstream APKs or private signing credentials.
The NDK revision and Android build inputs are pinned. Maintainer validation in the actual
F-Droid buildserver remains required; a local build or GitHub CI is not that validation.

Auto-update checks select only `v<version>-fdroid` tags. Every update must increase the
Android version code and retain the corresponding source, build recipe and checksums.
The owner reported the preceding CP04 F-Droid APK stable on a physical Android device.
The subsequent recovery audit passed 255 tests; the new 1.01 APK is a separate artifact.
Record any further device testing against its SHA-256 rather than the preceding build.

## References

- [F-Droid inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/)
- [Build metadata](https://f-droid.org/en/docs/Build_Metadata_Reference/)
- [Anti-features](https://f-droid.org/en/docs/Anti-Features/)
- [R8 patch range](https://r8.googlesource.com/r8/+log/refs/tags/9.1.29..refs/tags/9.1.31)
- [Termux PRoot source](https://github.com/termux/proot/tree/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f)
