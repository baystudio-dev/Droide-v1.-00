# Building

## Supported toolchain

Use Linux x86_64, Python 3.12 or newer, Bash, and the following pinned inputs. A machine with 16 GB RAM is recommended for compilation, optimization, and complete lint analysis.

| Component | Version |
| --- | --- |
| JDK | Eclipse Temurin 17.0.20.1+1 |
| Gradle wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin compiler and plugins | 2.4.0 |
| Android SDK platform | 36 |
| Build Tools | 36.0.0 |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 |

The application targets Android SDK 36 and requires Android SDK 29 or newer. Toolchain upgrades require review of the associated version, checksum, lock, and compatibility changes.

## Provision inputs

Install the Android SDK packages with the official command-line tools and complete the SDK license requirements:

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1' 'platform-tools'
sdkmanager --licenses
```

`tools/terminal-engine-v2-jdk.json` contains the exact JDK download URL, archive checksum, and extracted file manifest. Preserve both the original vendor archive and the extracted JDK. Set `JAVA_HOME` to that JDK and `ANDROID_HOME` to the SDK directory.

The source checkout and toolchain paths must be real directories. Choose a new, initially absent terminal engine output path outside the source, SDK, JDK, and vendor archive. The build creates it. A completed engine output can be verified and reused while its inputs remain unchanged; preserve failed output for diagnosis and choose a new path when rebuilding changed engine inputs.

## Release APK

The source build task compiles the application and current native sources, independently builds or verifies the terminal engine, runs full release lint, and verifies the resulting APK.

```sh
./gradlew :app:assembleSourceRelease \
  -PDROIDE_SOURCE_RELEASE=true \
  -PDROIDE_SOURCE_RELEASE_ENGINE_DIR=/absolute/path/droide-build/terminal-engine \
  -PDROIDE_SOURCE_RELEASE_JDK_ARCHIVE=/absolute/path/toolchains/temurin17.tar.gz \
  -Pandroid.builder.sdkDownload=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false \
  '-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  --dependency-verification=strict --no-daemon --max-workers=1 \
  --no-configuration-cache --no-build-cache
```

Use the documented source task rather than a plain variant assembly command. Add `--offline` once all dependencies are cached. Task exclusions and checksum/lock rewrites are not accepted in the release build invocation.

Outputs are under `app/build-source-release/`:

- `outputs/apk/release/`: APK and metadata.
- `outputs/source-release-verification.json`: artifact verification receipt.
- `outputs/mapping/release/`: R8 mapping and optimization reports.
- `reports/`: full lint reports.

## Signing

Provide all four settings through private environment configuration:

- `DROIDE_KEYSTORE_PATH`
- `DROIDE_KEYSTORE_PASSWORD`
- `DROIDE_KEY_ALIAS`
- `DROIDE_KEY_PASSWORD`

Keep private files outside the checkout. Do not place passwords in command arguments, Gradle files, logs, or issues. A partially configured signing setup is rejected. With no signing configuration, the build produces an unsigned APK for verification; it cannot be installed directly.

Use the established release key for updates and check the APK certificate with Android Build Tools `apksigner verify --verbose --print-certs`. Preserve the key and its recovery credentials in restricted storage. Changing a signing key requires a supported distribution/key-transition procedure.

## Debug APK

Use the equivalent explicit debug source path:

```sh
./gradlew :app:assembleSourceDebug \
  -PDROIDE_SOURCE_DEBUG=true \
  -PDROIDE_SOURCE_DEBUG_ENGINE_DIR=/absolute/path/droide-build/terminal-engine \
  -PDROIDE_SOURCE_DEBUG_JDK_ARCHIVE=/absolute/path/toolchains/temurin17.tar.gz \
  -Pandroid.builder.sdkDownload=false \
  --dependency-verification=strict --no-daemon --max-workers=1 \
  --no-configuration-cache --no-build-cache
```

Outputs are under `app/build-source-debug/`. Debug and release certificates differ. Export and verify application data before replacing an installation signed with a different certificate.

## Verification scope

The artifact verifier checks native bindings, packaged library and asset bytes, manifest properties, signature state, and ZIP alignment. Receipts retain `deviceValidated: false` until separate device validation is performed. Build verification does not publish an application or establish approval for distribution.
