# Development

The Gradle wrapper, dependency locks, verification metadata and `tools/build-readiness/droide-build-bootstrap.json` define the build inputs. Do not update a pinned artifact or its checksum solely to bypass a failed validation.

The Android application targets JDK 17 and SDK/Build Tools 36. The host test runtime may have additional requirements from its pinned dependencies; a host-only configuration is not an installable APK or Android execution proof.

```sh
python3 tools/build-readiness/prepare_build.py source
python3 tools/build-readiness/prepare_build.py environment --android-sdk-root "$ANDROID_SDK_ROOT"
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Run the applicable source/dependency/native checks in a clean checkout. `tools/verify_release_source.sh` rejects generated artifacts, credentials and invalid source or dependency provenance. Build caches in a working checkout are not release-source contents.

Native terminal source, ordinary packaged AARs, host-only test artifacts and explicit private derivative modes have distinct provenance. The mode selection and required verification tasks are in `app/build.gradle.kts`. Do not describe a private derivative or host JAR as a verified ordinary release artifact.

Tools used to build Droide are separate from toolchains users install inside its Ubuntu workstation. Keep user projects and installed workstation data outside destructive build cleanup. Never package credentials, local signing keys or test output as application source.
