# F-Droid scanner triage: Droide v1.00, versionCode 44

This document classifies **the 33 source-scan errors reported on the immutable
`v1.00` tag** in [RFP #4776](https://gitlab.com/fdroid/rfp/-/work_items/4776#note_4209909038).
These are not 33 malware detections. The scanner evaluates checked-in source
inputs and repositories; its findings are not an inventory of the final APK.
Run `python3 -B tools/verify_fdroid_scanner_inventory.py` to detect unexpected
changes to the unresolved baseline. A pass means the 33 findings are accounted
for, **not** that the scanner errors have been resolved.

**Do not delete, hide, or `scanignore` an active runtime component to force a
green scanner.** An acceptable F-Droid recipe must independently rebuild it or
document a specifically permitted trusted provenance. Any `scandelete` choice
for unused historical inputs must be justified against the actual build graph.

## The three Maven repository errors

| Declaration in `settings.gradle.kts` | Purpose | Next action |
| --- | --- | --- |
| `https://storage.googleapis.com/r8-releases/raw` | Standalone R8 `9.1.29` override of AGP's optimizer | Test a byte- and behavior-verified compatible version from Google's approved Maven repository, with refreshed checksums; do not silently change the optimizer. |
| `DroideLocalCompat` (`third_party/maven`) | Pin local Android derivatives for JGit, SPAKE2 and Kadb | Build required derivatives from reviewable source during the F-Droid build, then resolve only outputs of that build. |
| `DroideLocalSupport` (`third_party/maven`) | Offline copies of permitted ecosystem libraries | Resolve these through recognized, pinned upstream repositories in the F-Droid recipe after proving artifact equivalence. |

The F-Droid scanner's upstream `allowed_repos` list recognizes ordinary Google
Maven and Maven Central, but **not** arbitrary local filesystem Maven repositories
or this Google Cloud Storage path. Rewriting the URL string to hide it from the
scanner would not establish binary provenance.

## Eighteen JAR errors

| Files | Count | Current role and disposition |
| --- | ---: | --- |
| `third_party/kadb/sources/*-sources.jar` | 2 | Published upstream source archives; preserve verified source content but use plain source or reviewer-approved source archives for independent builds. |
| `third_party/termux/*-sources.jar` | 2 | Upstream terminal source snapshots; existing Terminal engine builder uses an AAR base, so its overlay is **not a full source rebuild**. |
| `third_party/xz/xz-1.12-sources.jar` | 1 | Archival source input for pinned XZ version; move F-Droid compilation to source or an accepted pinned repository artifact. |
| `third_party/jgit/upstream/*-sources.jar` | 1 | JGit upstream source archive for derivative provenance; current local reconstruction recompiles only overlay classes. |
| `third_party/maven/com/baystudio/compat/spake2-boringssl-compat/1.0.0/*-sources.jar` | 1 | Source archive; independent source compilation required for the runtime derivative. |
| `third_party/jgit/upstream/org.eclipse.jgit-6.10.1.202505221210-r.jar` | 1 | Original vendor binary used as an input to the reconstruction check, not a direct release dependency. Replace with upstream source-built equivalent for F-Droid. |
| `third_party/maven/com/baystudio/compat/*/*/*.jar` (excluding `*-sources.jar`) | 4 | Three runtime compatibility dependencies plus one Kadb reference artifact. Verify exact resolved graph; source-rebuild runtime inputs. |
| `third_party/maven/org/bouncycastle/**/*.jar`, `third_party/maven/com/squareup/okio/**/*.jar`, `third_party/maven/org/jetbrains/kotlinx/**/*.jar` | 5 | Offline cached upstream runtime libraries; prefer approved source/repository resolution with exact checksums. |
| `app/libs/xz-1.12.jar` | 1 | Direct release dependency; replacing it with another version or deleting it changes the build until a verified equivalent is wired in. |
| **Total** | **18** | |

The listed patterns are classification groups, not ready-to-paste `scanignore`
rules. Some include both published sources and binaries consumed by the existing
offline verification path.

## Six AAR errors

| Files | Count | Current role and disposition |
| --- | ---: | --- |
| `app/libs/terminal-view-v0.118.0.aar` | 1 | Direct runtime UI/view dependency; provide a complete independently compiled Termux View replacement, with API/rendering compatibility tests. |
| `app/libs/terminal-emulator-v0.118.0-java.aar` | 1 | Base AAR consumed by `tools/terminal_engine_v2.py`; its current `fullSourceRebuild` report is **false**, so rebuilding only the overlay is insufficient for F-Droid source provenance. |
| `app/libs/terminal-emulator-v0.118.0.aar`, `app/libs/terminal-emulator-v0.118.0-droide-input.aar` | 2 | Historical/provenance variants; determine whether the source verifier requires them before any safe build-specific cleanup. |
| `third_party/maven/androidx/documentfile/documentfile/1.1.0/documentfile-1.1.0.aar` | 1 | Pinned official AndroidX dependency mirror; F-Droid build should prefer an approved repository source. |
| `third_party/maven/org/lsposed/hiddenapibypass/hiddenapibypass/6.1/hiddenapibypass-6.1.aar` | 1 | Pinned third-party dependency mirror; review F-Droid acceptance and provenance before switching source. |
| **Total** | **6** | |

## Four native shared library errors

| Files under `app/src/main/jniLibs/arm64-v8a/` | Count | Source-build requirement |
| --- | ---: | --- |
| `libdroide_cli.so` | 1 | Source is `app/src/main/cpp/droide_cli_client.c`; compile with the pinned Android toolchain, test ABI and the same command protocol. |
| `libdroide_proot.so` | 1 | PRoot build recipe exists under `third_party/native-engines/proot/`, but the present recipe preserves prebuilt Termux loaders. |
| `libproot-loader.so`, `libproot-loader32.so` | 2 | **Hard blocker:** replace with independently source-built, behavior-verified loaders without breaking the PRoot ARM64 bootstrap. |
| **Total** | **4** | |

The existing release APK verifier intentionally checks that all four are
packaged unchanged. An F-Droid source-build variant will need a **new, equally
strict** verifier comparing its packaged outputs against the newly compiled
native source products, not an exemption that stops checking them.

## Two gzip errors

- `third_party/native-engines/proot/proot-openminis-8cf13e99-source.tar.gz`
- `third_party/native-engines/proot/talloc-2.4.2-source.tar.gz`

These two files contain PRoot/talloc **source archives**, not packaged executables.
The existing offline PRoot recipe verifies their SHA-256 and consumes them for
compilation. A narrowly justified F-Droid source-archive `scanignore` or a
plain-source checkout may be appropriate; deleting them before building PRoot
would break the current recipe.

## Separate tracker flags

`io.opencensus:opencensus-api:0.31.0` and
`io.opencensus:opencensus-proto:0.2.0` appear only in Gradle's
`_internal-unified-test-platform-*` configurations in `app/gradle.lockfile`.
They are **not listed under `releaseRuntimeClasspath`**. This is evidence about
the lockfile, not proof about final APK contents. Verify against a resolved
release graph and packaged DEX before calling the flags false positives.

## Acceptance gates and current evidence

1. [PR #8](https://github.com/baystudio-dev/Droide-v1.-00/pull/8): upstream Fastlane listing and artwork; repository and regular-release CI passed.
2. [PR #9](https://github.com/baystudio-dev/Droide-v1.-00/pull/9): optional Canonical Ubuntu install with consent; repository, F-Droid candidate build and regular-release CI passed at commit `96ee94c`.
3. The [candidate CI run 43245371617](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43245371617) independently downloaded Canonical's gzip and **passed** the decompressed TAR byte-identity check against the originally bundled XZ payload.
4. The next candidate CI additionally compares the complete `releaseRuntimeClasspath` Gradle dependency trees for regular versus F-Droid mode; a graph mismatch fails the job.
5. Neither passing workflow constitutes an independent F-Droid source build or device QA. No official F-Droid acceptance or new signed release is claimed.

**Next work order:** full source rebuild of Termux Base/View; source-derived
SPAKE2/Kadb/JGit artifacts; source-built native loader pair and PRoot/CLI;
approved upstream resolution for ordinary libraries; scanner/lint; physical
ARM64 regression; new release tag and maintainer review. Keep the regular
release and signing credentials untouched throughout.
