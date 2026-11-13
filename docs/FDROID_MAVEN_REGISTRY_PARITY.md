# Ordinary runtime mirrors: official-registry byte parity

## Scope and current evidence

`tools/fdroid_maven_registry_parity.py` is an isolated comparison tool for eight
existing runtime artifacts. It does not alter dependency declarations, versions,
Gradle repositories, packaged files, signing, or the stable release. It does not
build the app, unpack archives, or execute any downloaded code.

The tool's default is an **offline input check**, not a registry comparison.
Adding this tool or passing its mocked tests is not evidence that a download has
matched. Only a successful authorized `--download-and-compare` run, with its
`PARITY.json` receipt, can establish the byte-parity claim below. The actual
remote checkpoint is recorded here separately from mocked test coverage.

On 2026-11-13, [job 131693882351 in run 43367069641](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43367069641/job/131693882351)
reported `ALL_EIGHT_REGISTRY_BYTES_MATCH_PINS` from the actual JSON receipt.
All eight exact payload hashes in the table below matched, with no redirects
or errors and 12,952,218 downloaded bytes in total. Local input receipts before
and after were identical. The checked-out PR merge was
`b8c59e3b6fbf17ae88566dd0d4ae14a55a12b6cc`, containing head
`2ba0111b2cd9d8eae49f0eb084b19af633854bba`. The job log records the outer
`timeout 540s` command; the helper itself still does not claim to verify that
supervisor. The downloaded files were not promoted to the application or
published as artifacts.

This establishes only the eight tested binary payloads' registry byte parity.
It does not establish source compilation, POM/transitive dependency parity,
R8 provenance, a source-only APK, Android device behavior or F-Droid acceptance.

The five ordinary upstream JAR mirrors, two AAR mirrors, and the direct XZ JAR
are deliberately separate from Droide's custom compatibility artifacts,
Termux overlays, native engines, rootfs payloads, and build-time R8 override.

## Fixed artifact inventory

The seven mirrored artifact paths begin with `third_party/maven/` and then use
the Maven path in the table. XZ instead resides at `app/libs/xz-1.12.jar`.
The tool contains the complete canonical HTTPS URL for each artifact; it accepts
no arbitrary URL, version, digest, or fallback mirror at the command line.

| Coordinate | Registry and exact artifact path | Existing SHA-256 |
| --- | --- | --- |
| `com.squareup.okio:okio-jvm:3.17.0` | Central: `com/squareup/okio/okio-jvm/3.17.0/okio-jvm-3.17.0.jar` | `3b13dcc3e1573047c2d507fc4f7fcb0bb0cbcd9d395da21d20e09406e2cd6a73` |
| `org.bouncycastle:bcpkix-jdk18on:1.84` | Central: `org/bouncycastle/bcpkix-jdk18on/1.84/bcpkix-jdk18on-1.84.jar` | `c87f16ed9e5ec61bc94151e9f3646ac44e50cd448121ce84367fa4b7ec7ec1bb` |
| `org.bouncycastle:bcprov-jdk18on:1.84` | Central: `org/bouncycastle/bcprov-jdk18on/1.84/bcprov-jdk18on-1.84.jar` | `64d6c5a6121fcd927152dd182cbed39afe0fda641a970d9bcc0c9cb1858b2731` |
| `org.bouncycastle:bcutil-jdk18on:1.84` | Central: `org/bouncycastle/bcutil-jdk18on/1.84/bcutil-jdk18on-1.84.jar` | `b374e16963421fb9cfb01cc20d7ad8fd2f8b8188e3eef0ec0a8965e245f7619a` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0` | Central: `org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.jar` | `d1d75aa01dffbb4d1c520e67e4c4e7f5f6174718e7cb4632412503f2f0e604fa` |
| `androidx.documentfile:documentfile:1.1.0` | Google: `androidx/documentfile/documentfile/1.1.0/documentfile-1.1.0.aar` | `684e0ffcb21a660c939d1bb814521f42c4bd74816f4a117291fe6a101cf3a822` |
| `org.lsposed.hiddenapibypass:hiddenapibypass:6.1` | Central: `org/lsposed/hiddenapibypass/hiddenapibypass/6.1/hiddenapibypass-6.1.aar` | `e3161dd21c97a4540b1698a33f7062aeaa1450008e1e2176070e5380f7a6324c` |
| `org.tukaani:xz:1.12` | Central: `org/tukaani/xz/1.12/xz-1.12.jar` | `3e158a87bd73d8afb4b6e8239c013b7d049c48563f45860ce99cd2e448cf4a6b` |

Registry roots are exactly `https://repo.maven.apache.org/maven2/` and
`https://dl.google.com/dl/android/maven2/`.

Pin cross-checks:

- The seven Maven mirrors must match both
  `third_party/kadb/EMBEDDED_SHA256SUMS.txt` and their exact coordinate/filename
  entries in `gradle/verification-metadata.xml`.
- XZ is currently a direct file dependency, not a Maven-resolved dependency.
  Its version and JAR hash must match `third_party/xz/PROVENANCE.json`.
- Every local artifact must match the corresponding fixed SHA-256 above.
- The three pin metadata files and all eight artifact hashes/sizes are checked
  before and after a download run. A changed input or metadata file prevents a
  success report, even if every downloaded artifact matched.

These local pins are the comparison targets, not independent proof of registry
origin. The explicit download supplies that additional, narrowly scoped evidence.

## Running the checks

Offline and read-only (also the behavior with no mode argument):

```sh
python3 -I tools/fdroid_maven_registry_parity.py --check-inputs
```

The expected local-only status is `LOCAL_INPUTS_MATCH_PINS`, with
`registryParityVerified: false` and `networkAttempted: false`.

An authorized network comparison requires a POSIX main-thread Python process,
trusted external OS process supervision, and an explicit, new, absolute output
directory **outside the checkout**. The parent directory must already exist; do
not create the final output directory first. Use trusted GNU `timeout` outside
the checkout (or an equivalent CI process supervisor), with a 540-second process
timeout and forced termination five seconds after TERM if it remains alive:

```sh
timeout --signal=TERM --kill-after=5s 540s \
  python3 -I tools/fdroid_maven_registry_parity.py \
    --download-and-compare \
    --output-dir /tmp/droide-registry-parity-proof-unique
```

The Python helper does not launch or verify its external supervisor. Its report
explicitly states `externalProcessSupervisionRequired: true` and
`externalProcessSupervisionVerified: false`; the operator/CI must retain the
supervisor's exit status. Do not run the online mode unsupervised.

Each artifact is retained as `<original-filename>.download`; the receipt is
`PARITY.json`. Existing output files/directories are rejected rather than
overwritten. Created artifact/receipt files have no executable permission bits.
On handled failure, partial data and a failed receipt are retained when output
creation has already succeeded. External process termination may leave partial
files without a final receipt; a missing receipt is never success. Failures never
cause a pin update, version substitution, unverified fallback download, or copy
into the application build.

Download controls:

- HTTPS with normal certificate validation, standard managed proxy/CA behavior,
  no account credential lookup, authorization header, cookie jar, or `netrc` use.
- The exact canonical artifact endpoint is checked before a request and after
  any redirect. Only a redirect resolving to that **same exact URL** is accepted;
  another path, host, scheme, query string, fragment, or URL credential is rejected.
  A canonical redirect loop stops after three redirects. There is no host fallback.
- A 15-second socket timeout and 60-second per-artifact POSIX signal timer provide
  in-process timeout controls. **They are not an OS hard wall-time bound**:
  Python signal delivery can be delayed while DNS resolution or another blocking
  C call has not returned to the interpreter. The report therefore names the
  value `signalDeadlinePerArtifactSeconds` and sets
  `osHardPerArtifactDeadlineGuaranteed: false`. An existing real-time timer or a
  non-main-thread caller is rejected, not overwritten. The required external
  supervisor supplies independent process termination; there is no claim that
  the helper alone always finishes within eight minutes.
- A 32 MiB local/per-artifact ceiling and 64 MiB inventory ceiling. HTTP bodies
  are limited more tightly to the already-verified local file's exact size;
  one extra byte may be read to detect an overrun but is not written. A present
  Content-Length must equal that size; truncation also fails.
- HTTP status 200 and identity content encoding are required. Downloads are
  streamed and hashed as opaque bytes, with no ZIP/JAR/AAR interpretation.
- Network exception text is not copied to reports, so proxy configuration or
  unrelated credentials cannot accidentally appear in the receipt.

Only all eight successful comparisons with stable local inputs produce
`ALL_EIGHT_REGISTRY_BYTES_MATCH_PINS` and `registryParityVerified: true`.
Exit status is nonzero on failure. This says **the eight tested registry payloads
match the existing pinned bytes**. It does not establish full source compilation,
transitive dependency/POM/module parity, APK reproducibility, device behavior,
licensing compliance, repository policy acceptance, or F-Droid approval.

Pure/mock regression tests (no network or archive execution):

```sh
python3 -I -m unittest discover -s tools/tests -p 'test_fdroid_maven_registry_parity.py' -v
```

## R8 9.1.29: separate unresolved repository evidence

`settings.gradle.kts` currently requests `com.android.tools:r8:9.1.29` from the
restricted custom `https://storage.googleapis.com/r8-releases/raw` repository,
with Google Maven and Maven Central subsequently available. It is a build-time
override and is **not** one of the eight runtime artifacts above. This work
does not change its version, repository order, or pins.

Existing `gradle/verification-metadata.xml` pins:

| Artifact | SHA-256 |
| --- | --- |
| `r8-9.1.29.jar` | `9a7d1e9e4c2d3ee05482b9695df5f4d8f92c22ebb74603d6be4eab0505467f1a` |
| `r8-9.1.29.pom` | `136af50c91e46198fc439f2e233094edd39c5d68df88f33045526b872a4a6e06` |

Primary-source research checked on 2026-11-13:

- The [official R8 tag 9.1.29](https://r8.googlesource.com/r8/+/refs/tags/9.1.29)
  exists and targets commit `7130940ccb0c41990c77906b3d4f0a4412acb501`.
  Its README says stable versions shipped with Android Studio are published on
  Google Maven, and separately documents the Cloud Storage release repository.
  It also distinguishes processed `r8lib.jar` and unprocessed `r8.jar` binaries.
- The [official Android Kotlin compatibility table](https://developer.android.com/build/kotlin-support)
  names R8 9.1.29. This corroborates the version, not a particular registry's
  artifact bytes.
- The research tool could not retrieve the exact Google Maven POM or the group
  index; these were tool-access failures, **not observed HTTP 404 responses**.
  The [Google Maven web index](https://maven.google.com/web/index.html#com.android.tools:r8)
  exposed no usable version listing in that tool.

Consequently, exact Google Maven availability and byte equality for R8 9.1.29
remain **unverified**, not disproven. The candidate endpoint is
`https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.1.29/r8-9.1.29.pom`,
with the analogous `.jar` URL. A future authorized retrieval must compare both
actual files against the existing pins before proposing any repository change.
A matching version name or source tag cannot substitute for this byte evidence.
