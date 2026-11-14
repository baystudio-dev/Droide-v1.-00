# Current debug APK verification lane

This lane is opt-in and independent of historical private/R46/R63 admission. It
never publishes an APK, uses release credentials, relaxes historical pins, or
claims Android runtime certification.

## What it does

`build_current_debug_apk.py` freezes the selected source/build trees, verifies the
current composite and exact retained Gradle vendor distribution, and compiles two
new source copies sequentially. Each receives a fresh Gradle home seeded only
with the already available dependency artifact cache. Both builds are offline,
strictly dependency-verified, cache-disabled, and use the same SDK/JDK, Kotlin
in-process profile and newly generated debug signing key.

The primary uses `:app:verifyCurrentAppRuntimeContracts`: it first assembles an
**UNADMITTED** intermediate, then requires the exact 71-case additional app/engine
cohort using actual APK-producing classes and fresh engine AAR. The independent
reference uses `:app:assembleCurrentDebugCandidate` without that test profile.
Both compile identical production sources. The outer verifier then checks:

- complete register-exact canonical DEX classes from the independent clean build;
- exact binary manifest, resources and other non-signature APK entries;
- actual manifest application/launcher, SDKs, label and unvalidated metadata;
- 12 composite-owned providers plus 8 fixed AndroidX providers derived from
  resolved dependency AAR bytes (no generic native-library allowlist);
- signature identity and ZIP alignment for both signed APKs;
- separately recorded ELF LOAD alignment for every provider. A legacy 4-KiB
  loader is preserved and reported; ZIP alignment is not device compatibility;
- unchanged source, tools, dependencies and consumer-verified composite identity.

Only then is a locally named `Droide-current-debug-device-unvalidated.apk` and
source-bound receipt created. The original engine keeps its retained binary-base
qualification. Source-to-package fidelity is not a proof that source behavior is
correct. The 71-case host cohort is additional to, not a replacement for, the
full suite. It observes original class resources inside Robolectric, not identical
transformed execution bytes. Android device tests remain separate.

## Inputs

Run from the selected source's `tools/` script, using absolute paths:

```
python3 -B -E -s tools/build_current_debug_apk.py \
  --root /path/to/frozen-source \
  --output /path/to/new-independent-build-evidence \
  --composite /path/to/consumer-verified-composite \
  --java-home /path/to/composite-selected-vendor-jdk \
  --sdk /path/to/composite-selected-sdk \
  --gradle /path/to/gradle-8.13/bin/gradle \
  --gradle-archive /path/to/retained-gradle-8.13-bin.zip \
  --dependency-cache /path/to/immutable-modules-2 \
  --robolectric-dir /path/to/pinned-offline-sdk29-sdk35-jars
```

There is no download fallback. Output must not exist or overlap an input.
Installed Gradle must exactly match archive bytes and modes; a mismatch fails,
never normalizes the shared installation. SDK inventory is measured identity,
not an assertion of vendor-authenticated provenance.

The aggregate composite consumer can run up to five bounded 1,800-second child
verifications. Its fixed outer timeout includes inventory overhead; all partial
output is retained on failure. Builds can be expensive: coordinate memory with
other work and never run the two app stages concurrently.

## Evidence and privacy

Every command/result and source/tool manifest is retained. Generated
`debug.keystore` is a local build credential and MUST NOT be included in a source
or evidence export. Only the public `debug-cert.der` and signer fingerprint are
shareable evidence. Failed/intermediate artifacts remain unadmitted.

`DROIDE_CURRENT_APP_RUNTIME_CONTRACTS=true` selects a separately guarded actual
app/engine test profile when that profile is present and reviewed. It does not
broaden the historical host test guard, and its results must not be confused with
the earlier full host suite using TEST-ONLY Termux inputs.

Each orchestrated command starts in its own new process session. A timeout sends
TERM, then KILL if necessary, only to that owned process group and waits for the
direct child. Cleanup status and partial output are retained; automatic retry is
blocked. Descendants that deliberately escape that session are not tracked or
claimed reaped. No global Gradle-daemon termination is performed.

The explicit offline Robolectric directory contains only the two pinned SDK29/35
instrumented framework JARs. No ambient Maven resolver or online fallback is used;
original framework JAR identity is checked before/after and inside the sandbox.

## Installation safety

This debug lane retains package ID `com.baystudio.droide` and uses its own newly
generated debug key. It may not be signature-compatible with an existing install.
Use a disposable test device/profile or independently verified compatible signing.
Never uninstall, clear app data, or remove user files to bypass a signature error.
The package ID is not changed merely to avoid that conflict. Device validation
requires the separately documented gates and does not follow from this receipt.
