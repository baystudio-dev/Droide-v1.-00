# Testing

Tests are organized by the behavior and environment they verify. Keep tests independent of source comments, formatting, or development-history labels.

## Repository and dependency checks

`tools/tests/` contains tests for repository policies, dependency verification, licensed artifacts, compiler/native inspection, and release packaging. Run the maintained release source checks before building a distribution artifact. Dependency changes must retain strict checksums and configuration locks.

These checks cover source hygiene, required documentation, accidental credential/build-output inclusion, dependency policy, license coverage, catalog consistency, and native/artifact verification. A failing integrity check needs investigation; updating an expected hash is a reviewed maintenance action.

Run the source gate on a clean checkout:

```sh
bash tools/verify_release_source.sh
```

The source-only gate explicitly skips tests requiring compiled terminal output or an APK. CI runs those tests again with real build artifacts after release assembly.

## Application tests

`app/src/test/` contains JVM and Robolectric tests for application behavior. Prioritize startup and Exit, draft preservation, safe file publication/export, process ownership, provider/agent protocols, credentials, and browser lifecycle when preparing a release.

The maintained critical suite is listed in `tools/release-test-suites.json`. Run it with `python3 tools/run_release_tests.py`; use `--offline` when the dependencies are cached. The runner rejects missing, failed, skipped or empty reports for every selected class.

The runner verifies the pinned API 35 Robolectric runtime in `tools/host-test-runtime.json` and compiles the current filesystem JNI source for Linux x86_64. Install a C compiler and set `JAVA_HOME` first. These fixtures remain under `app/build-host-jvm-tests/host-runtime/`; they are not application dependencies. `--offline` requires the verified runtime to be cached already. Interrupted or corrupt downloads are rejected.

Each selected class runs in a separate worker process so Robolectric sandboxes and ordinary JVM tests cannot conflict over JVM ownership of the native library.

Use the explicit host test mode for focused JVM tests. It does not package an application or establish native/device validation:

```sh
python3 tools/provision_test_runtime.py
./gradlew :app:testDebugUnitTest \
  --tests com.baystudio.droide.CompletedExitProcessTest \
  -PDROIDE_HOST_JVM_TESTS_ONLY=true \
  -PDROIDE_HOST_TEST_RUNTIME_DIR="$PWD/app/build-host-jvm-tests/host-runtime" \
  -Pandroid.builder.sdkDownload=false \
  --dependency-verification=strict --no-daemon --max-workers=1 \
  --no-configuration-cache --no-build-cache
```

The selected tests must actually run. An empty selection, failed process, or incomplete test report is a failure. Keep native/device-dependent tests separate from host-only fixtures.

## Android device tests

`app/src/androidTest/` contains platform and workbench integration checks. Run them on the intended Android variant and supported device configurations. A debug test cannot establish that the optimized release variant behaves correctly.

For release acceptance, exercise startup and recovery, editor input and saving, project import/export, Exit, terminal/process cleanup, agent tools, and browser lifecycle. Include orientation changes and keyboard interactions for workbench changes. Record the Android version, device/ABI, variant, and observed results.

## Artifact tests

Release verification checks the actual APK after optimization and packaging. Native binding tests also exercise invalid declarations and corrupted inputs:

```sh
DROIDE_SOURCE_RELEASE_APK=/absolute/path/app-release.apk \
  python3 -B -m unittest discover -s tools/tests -p test_source_release_verification.py -v
```

Keep the APK checksum, source revision, verification receipt, lint report, test reports, signature fingerprint, and matching R8 mapping together for a release. Document which checks ran and any missing device coverage.
