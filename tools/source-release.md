# Build a current-source release candidate

Use this path to compile the current application, current native C sources and the reviewed Java terminal overlay into the Android **release** variant. R8 minification, resource shrinking and release lint remain enabled. It does not modify historical Termux certification receipts or claim Android device validation.

## Requirements

Gradle 8.13, Android SDK 36, Build Tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1 and the pinned JDK 17 installation/vendor archive described by `terminal-engine-v2-jdk.json`. Set `JAVA_HOME` and `ANDROID_HOME`. Keep the terminal engine output outside the source, SDK, JDK and vendor archive directories.

```sh
./gradlew :app:assembleSourceRelease \
  -PDROIDE_SOURCE_RELEASE=true \
  -PDROIDE_SOURCE_RELEASE_ENGINE_DIR=/absolute/path/terminal-engine-output \
  -PDROIDE_SOURCE_RELEASE_JDK_ARCHIVE=/absolute/path/vendor/jdk.tar.gz \
  -Pandroid.builder.sdkDownload=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false \
  '-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  --dependency-verification=strict --no-daemon --max-workers=1 \
  --no-configuration-cache --no-build-cache
```

Add `--offline` when all dependencies are cached. Dependency checksums and lockfiles are inputs, not files to rewrite to bypass verification. Do not exclude tasks. A completed engine directory is verified on repeat builds. Preserve incomplete outputs for diagnosis and use a new directory if an engine build failed.

## Signing and outputs

Provide all four existing signing settings through private environment configuration: `DROIDE_KEYSTORE_PATH`, `DROIDE_KEYSTORE_PASSWORD`, `DROIDE_KEY_ALIAS`, `DROIDE_KEY_PASSWORD`. Do not put passwords or private keystores into source, command logs or backups. The build rejects incomplete signing configuration and a debug certificate. Without any signing configuration it produces an **unsigned** release APK, which cannot be installed directly.

APK: `app/build-source-release/outputs/apk/release/`. Verification receipt: `app/build-source-release/outputs/source-release-verification.json`. Lint reports: `app/build-source-release/reports/`. Preserve the matching `outputs/mapping/release/mapping.txt` privately for deobfuscating crash reports.

Verification checks the independent Java engine builds; JNI methods in the R8 APK; current native registration/exports; exact CMake output bytes; bundled runtime libraries, legal texts and other assets; non-debuggable manifest; signing state and ZIP alignment. Historical native receipts remain unchanged. A successful build records `deviceValidated: false` and `publicationApproved: false`. Run the release variant on supported Android devices and validate lifecycle/Exit, terminal cleanup, editor recovery and browser behavior before distribution. Build verification is not device certification.

The native-declaration verifier has integration tests against the real optimized
APK, including corrupted checksums, duplicate classes and a native method whose
static flag was removed while its checksums were repaired:

```sh
DROIDE_SOURCE_RELEASE_APK=/absolute/path/app-release.apk \
  python3 -B -m unittest discover -s tools/tests -p test_source_release_verification.py -v
```
