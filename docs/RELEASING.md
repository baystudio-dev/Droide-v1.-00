# Releasing

## Prepare

- Start from a reviewed source revision and run the repository checks.
- Increase `versionCode` for an update and set the intended `versionName` in `app/build.gradle.kts`.
- Review dependency, license, permissions, and native compatibility changes.
- Configure signing through private environment configuration, using the established release key.

## Validate

Run the release build described in [BUILDING.md](BUILDING.md), the automated checks in [TESTING.md](TESTING.md), and the applicable Android device checks. Preserve the complete result, including warnings and test limitations.

Exercise startup and recovery, editing and saving, import/export, Exit, terminal and external process cleanup, agent execution, and browser lifecycle. Test an update over the previous signed release. A new signing certificate cannot replace an existing installation signed with a different key through a normal Android update.

## Preserve artifacts

Keep the source revision, APK, source archive, checksum manifest, signature fingerprint, build/test reports, and matching R8 mapping. Store signing material separately with restricted access. Use the mapping from the exact build when deobfuscating a crash report.

## Distribute

Complete [RELEASE_AND_COMPLIANCE.md](../RELEASE_AND_COMPLIANCE.md) before distribution. Publish only the intended public artifacts. Do not publish private keys, signing credentials, environment files, or developer workspace snapshots.

The APK build workflow does not itself publish an application or produce a store listing. Store-specific formats and signing arrangements require their own validated distribution workflow.

The manually triggered `Upload verified release assets` workflow can transfer verified public artifacts into an existing draft release. Its manifest supplies each filename, byte count, SHA-256, and either a temporary approved download URL or base64 content. It checks the expected source commit and every artifact before upload, rejects conflicting assets, and leaves final publication to the maintainer. It does not accept signing keystores or private certificate keys.
