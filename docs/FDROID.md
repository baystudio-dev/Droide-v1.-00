# F-Droid distribution readiness

Droide is **not yet published on the official F-Droid repository**. The upstream
Fastlane metadata is now maintained under `fastlane/metadata/android/`. This is a
listing improvement, **not evidence that the F-Droid build is acceptable**.

## Upstream listing

- English (`en-US`) and Indonesian (`id-ID`) titles, descriptions, and changelogs.
- The current public `versionCode` is `44`; the matching changelog files are `44.txt`.
- The English icon and five phone screenshots reuse existing project-owned source
  assets from `resources/` without modification.
- Run `python3 -B tools/verify_fdroid_metadata.py` to validate the metadata.

The [F-Droid upstream metadata guide](https://fdroid.gitlab.io/jekyll-fdroid/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
documents the layout. Fastlane itself does not need to be installed to publish
these text and image files.

## Separate Ubuntu opt-in packaging candidate

An experimental packaging mode isolates the F-Droid executable download policy
without modifying the regular Droide APK or trimming any app dependency:

```sh
./gradlew :app:assembleSourceRelease \
  -PDROIDE_SOURCE_RELEASE=true \
  -PDROIDE_FDROID_BUILD=true \
  -PDROIDE_SOURCE_RELEASE_ENGINE_DIR=/absolute/path/to/current-source-terminal-engine \
  -PDROIDE_SOURCE_RELEASE_JDK_ARCHIVE=/absolute/path/to/pinned-vendor-jdk.tar.gz \
  --dependency-verification=strict
```

The same validated current-source release pipeline is used; the *only* excluded
APK asset is the bundled Ubuntu Base `.tar.xz`. All other assets and existing
native libraries are preserved. `verify_source_release.py --fdroid-build`
checks the omission and the byte identity of every remaining asset. The F-Droid
binary sets `BuildConfig.FDROID_DISTRIBUTION=true` for the alternate bootstrap.

When an Ubuntu guest is absent, the core manager blocks installation unless the
user accepts an explicit first-use notice in the Linux terminal. The notice
explains that executable code is obtained outside F-Droid review. The existing
`TrustedArtifactDownloader` fetches Canonical's pinned Ubuntu Base 24.04.5 ARM64
archive using HTTPS and verifies the exact compressed archive length and SHA-256;
the extractor additionally verifies the decompressed TAR's length and SHA-256
against the same baseline as the bundled XZ edition. Any mismatch fails closed.
The F-Droid candidate CI separately downloads the official Canonical gzip and
uses `tools/verify_fdroid_ubuntu_archive.py` to prove that its decompressed TAR
has the **exact same size and SHA-256** as the bundled XZ image.
The existing guest is never replaced as a side effect. The regular build keeps
the bundled Ubuntu and needs neither network download nor consent.

This is a **candidate architecture, not a declaration of F-Droid acceptance**.
The other scanner findings (including prebuilts in source, native ELF provenance,
and dependency repositories) remain unresolved. The variant still requires a
successful independent build, F-Droid scanner/lint review, runtime tests on an
actual Android device, and a human packaging decision.

## Outstanding source-build blockers

The [automated packaging report for request #4776](https://gitlab.com/fdroid/rfp/-/work_items/4776#note_4209909038)
(GitLab CI job `17929292799`, 2026-11-11) identified the missing metadata and
33 scanner errors for `versionCode` 44. The scanner findings are not malware
verdicts; they require a documented source-build and dependency review.

| Reported category | Count | Next work |
| --- | ---: | --- |
| Maven repository declarations the scanner cannot classify | 3 | Verify R8 provenance and make local repository resolution recognizable and auditable. |
| Tracked JAR artifacts | 18 | Identify which are only archival and which must be rebuilt from source or fetched from an allowed, version-pinned source. |
| Tracked AAR artifacts | 6 | Rebuild Droide/Termux derivatives and resolve permitted third-party libraries through an accepted repository. |
| Tracked ARM64 `.so` libraries | 4 | Build PRoot and CLI libraries from reviewed sources; account for both prebuilt PRoot loaders and their provenance. |
| Tracked compressed source archives | 2 | Review scanner handling and preserve the source material required for reproducible PRoot builds. |

The scanner also marked `io.opencensus:opencensus-api:0.31.0` and
`io.opencensus:opencensus-proto:0.2.0` as *Tracker* candidates. Current
`app/gradle.lockfile` entries place them in internal Android testing tooling,
not in `releaseRuntimeClasspath`. Confirm this against the resolved Gradle graph
before concluding whether they are packaged in the release APK.

For the full per-artifact classification, including source-only archives versus
active runtime dependencies and explicit native loader blockers, see
[F-Droid scanner triage](FDROID_SCANNER_MATRIX.md).

In particular, `app/src/main/cpp/CMakeLists.txt` compiles `libtermux.so` and
`libdroide_files.so`, but the four ARM64 libraries under
`app/src/main/jniLibs/` are currently bundled byte-for-byte and checked by
`tools/verify_source_release.py`. Removing or suppressing those binaries without
replacing their runtime behavior would break Droide and would not establish an
F-Droid-compliant build. The PRoot build recipe is under
`third_party/native-engines/proot/`; the CLI source is in
`app/src/main/cpp/droide_cli_client.c`.

## Release gate

1. Provide an isolated F-Droid build recipe that obtains each packaged binary
   from reviewable sources or explicitly allowed provenance. Keep toolchain
   versions, Gradle locks, and dependency verification intact or update them
   through the established maintenance process.
2. Run `fdroid scanner`, `fdroid lint`, and an actual independent source build.
   Record exceptions for any genuine scanner false positives with evidence.
3. Check the resulting APK's features, native libraries, hashes, and signing
   arrangement; perform device regression testing for PRoot, terminal, editor,
   Git, file handling, and external agents.
4. Create a new version code and release tag only for a tested application
   change. Preserve the existing `v1.00` release, APK, and signing key.
5. Once the blockers are resolved, update the packaging request with the build
   recipe and links to successful verification evidence.

The [F-Droid inclusion policy](https://fdroid.gitlab.io/jekyll-fdroid/en/docs/Inclusion_Policy/)
requires source transparency and describes which prebuilt FLOSS components may
be accepted from trusted sources. Metadata alone does not meet that requirement.
