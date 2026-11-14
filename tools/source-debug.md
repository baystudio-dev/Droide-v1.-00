# Build a current source debug APK

This explicit developer path builds current native JNI sources with Android CMake and the pinned Java terminal engine with two independent compilations. It does not update or satisfy historical release/private receipts and does not certify behavior on Android hardware.

Requirements: the pinned JDK 17 vendor archive described in `terminal-engine-v2-jdk.json`, its matching extracted JDK, Android SDK 36, build tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1 and Gradle 8.13. The engine verifier checks the vendor archive and installed JDK. Keep the engine output outside the source, SDK, JDK and vendor archive directories.

From the project root, with `JAVA_HOME` and `ANDROID_HOME` set:

```sh
./gradlew :app:assembleSourceDebug \
  -PDROIDE_SOURCE_DEBUG=true \
  -PDROIDE_SOURCE_DEBUG_ENGINE_DIR=/absolute/path/engine-output \
  -PDROIDE_SOURCE_DEBUG_JDK_ARCHIVE=/absolute/path/vendor/jdk.tar.gz \
  -Pandroid.builder.sdkDownload=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false \
  '-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  --dependency-verification=strict --no-daemon --max-workers=1 \
  --no-configuration-cache --no-build-cache
```

Add `--offline` when dependencies are already cached. Do not rewrite checksum metadata or locks to ignore a dependency error. A completed engine output is verified on subsequent builds; a failed partial engine output should be preserved for diagnosis and a new output directory selected.

The full Kotlin compilation needs substantial memory. The command above uses a single compiler process with a 4 GiB heap. Leave additional memory for Gradle/JVM overhead and Android packaging, and keep large caches and temporary archives on disk when the build runs inside a memory-limited container.

Output: `app/build-source-debug/outputs/apk/debug/app-debug.apk`. The final task verifies APK signing, ZIP alignment, Java/native registration agreement, current filesystem JNI exports, exact freshly built CMake library bytes and unchanged bundled native providers. Its receipt is `app/build-source-debug/outputs/source-debug-verification.json`.

This APK uses debug signing and the existing application ID. Physical device testing and release signing remain separate. Private signing keys and generated build/cache directories are not source backup inputs.
