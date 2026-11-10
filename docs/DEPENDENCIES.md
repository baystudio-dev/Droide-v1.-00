# Dependency maintenance

Application dependency versions are declared in Gradle and locked in `app/gradle.lockfile`. Checksums are enforced by `gradle/verification-metadata.xml`. Local compatibility components are resolved from the exclusive repository under `third_party/maven/`.

The host test Android runtime is pinned separately in `tools/host-test-runtime.json`, including its publisher URL, size, and SHA-512 checksum. It is downloaded only for tests and is never packaged in the APK. Review runtime updates together with the Robolectric version and selected test SDKs.

## Updating a dependency

1. Identify the direct and transitive changes, supported Android versions, and native ABI implications.
2. Update declarations and resolve the intended configurations with dependency verification enabled.
3. Review changes to lockfiles and checksum metadata against the publisher's artifacts. Do not accept checksum changes solely to make a failing build pass.
4. Update the license and provenance metadata when the shipped dependency graph changes.
5. Run dependency policy tests, license/catalog checks, relevant application tests, and the release build.
6. Inspect the resulting APK and validate affected workflows on Android.

## Local components

Maintain the original upstream archives, licenses, corresponding source, and reviewed changes for local compatibility components. Keep generated build outputs outside their source directories. A source or artifact identity change must be reviewed and rebuilt through its maintained build tooling.

## Toolchains and extensions

The catalogs under `app/src/main/assets/` describe separately installed packages, including `managed-package-catalog.json`, `droide-package-catalog-v1.json`, `android-toolchain-catalog.json` and `android-local-component-catalog.json`. Package versions, publisher metadata, checksums, architecture support, and update behavior must remain consistent. Installed packages do not automatically become application runtime dependencies.
