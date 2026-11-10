# Android JGit reconstruction

Source-only candidate prepared 2026-10-15. This is a newly reconstructed derivative,
not recovered historical output. There is deliberately no REBUILD.json or derivative
JAR until an independently reviewed, explicitly authorized JVM-only compilation runs.
No APK, AAR, native library, emulator or device operation belongs to this recipe.

## Identity and input provenance

- Upstream: `org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r`.
- Derivative: `com.baystudio.compat:jgit-android-compat:6.10.1.202505221210-r-droide-reconstruction1`.
- The original signed JAR and exact source JAR are retained under `upstream/`.
- Fixed upstream/source SHA256 pins are enforced by the offline verifier.
- SOURCE_COMMIT_VERIFICATION.json records all four source entries independently
  compared byte-for-byte with Eclipse's official Gitiles mirror at the JAR's commit.
  Actual decoded mirror responses are retained under upstream/commit-<commit>/ and
  must also match those source entries; the receipt records their response identity.
- TOOLCHAIN_PINS.json records the actual approved installed Temurin17, API36 and
  SLF4J1.7.36 files; all are checked before invoking javac.
- UPSTREAM_ABOUT.html includes complete EDL/BSD and bundled MIT terms; NOTICE.md
  identifies changed behavior and explicitly unsupported desktop optional APIs.

## Reviewed scope

Four source changes only: real Android PID in GC; no JMX registration/annotation;
no GSS Negotiate implementation. Basic/Digest, GC policy/locking and cache counters
remain. Recompile the complete nested families; remove closed Monitoring/GSS
families, invalid upstream signature files and root manifest digests. Unrelated
original file entries, legal notices and all translation resources remain byte exact.
The derivative manifest identifies Droide and retains upstream identity as attribution.
The full original manifest is included as provenance, not as active signed metadata.

## Explicit future commands

From the project root, supply the approved existing paths (no download/provisioning):

    python3 tools/build_jgit_android_compat.py --build --jdk "$JDK17" --android-jar "$ANDROID36_JAR" --slf4j-jar "$SLF4J_1_7_36_JAR"
    python3 tools/verify_jgit_android_compat.py
    python3 tools/build_jgit_android_compat.py --check --jdk "$JDK17" --android-jar "$ANDROID36_JAR" --slf4j-jar "$SLF4J_1_7_36_JAR"

`--build` invokes only pinned javac with Java11 target, disabled annotation processing,
empty source path, fixed direct classpath and sanitized environment. It writes the new
local Maven JAR/POM and REBUILD.json only after a temporary output inventory check.
`--check` independently compiles into temporary storage and demands byte-identical
JAR/receipt without rewriting published output. ZIP entries are sorted, stored and
have fixed timestamps/permissions. The default verifier is offline and never compiles.
`--source-only` checks only source inputs and must never replace the default build gate.

Receipts bind both scripts, every vendor input, class hashes, artifact hashes,
unchanged/replaced/added/removed inventories and exact toolchain pins. Changes to any
input invalidate the receipt. A compilation receipt explicitly does not certify runtime
behavior. Ordinary host tests and later minified Android evidence remain separate.
Offline receipt consistency is not proof that javac executed: compilation/reproducibility
claims require the actual --build and --check command results and measured output pins.

## Tests authored, not executed

- JGitAndroidCompatibilityTest (Robolectric): forced GC, Android PID lock, packed data,
  HEAD/worktree preservation, cleanup and translation loading.
- JGitAndroidAuthCompatibilityTest: synthetic Basic/Digest/Negotiate selection.
- GitTransportAuthTest / GitTransportAvailabilityTest: effective rewrites, push targets,
  URI-bound credentials, refused certificate trust, selected SSH/provider admission.
- GitCredentialTlsPolicyTest: inherited/scoped config, resets, guarded redirects,
  real TransportHttp cached-Basic redirect path using synthetic connections, Factory2
  delegation and unchanged anonymous factory/configuration.
- DroideTransportConfigTest: exact repository configuration and fail-closed load error.

Same-package helper/tests require the unsigned derivative at runtime; mixing them with
the original signed upstream JAR produces a signer mismatch. Do not include both JGit
coordinates, restore blanket keeps, suppress missing classes, or disable GC/R8.
