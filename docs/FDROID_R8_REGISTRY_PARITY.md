# R8 official registry byte parity

This opt-in, data-only check addresses one narrowly scoped question: can the
existing R8 compiler dependency be obtained from the standard Google Maven
repository without changing either of its current byte pins? It does not
upgrade R8, execute it, change a build repository, or claim F-Droid acceptance.
The [official R8 project documentation](https://r8.googlesource.com/r8/)
identifies Google Maven as its preferred distribution for stable and development
releases, while also documenting the raw-storage Maven repository. That general
distribution guidance does not establish this version's availability or hashes;
the explicit comparison below must do so.

## Fixed inventory

The only coordinate is `com.android.tools:r8:9.1.29`. The existing Gradle
verification metadata must contain exactly one such component, with exactly
these two artifacts and one reviewed SHA-256 per artifact. Duplicate
components, alternative checksum trust and unexpected artifact entries fail.

| Artifact | Existing SHA-256 | Download bound |
| --- | --- | ---: |
| `r8-9.1.29.jar` | `9a7d1e9e4c2d3ee05482b9695df5f4d8f92c22ebb74603d6be4eab0505467f1a` | 64 MiB |
| `r8-9.1.29.pom` | `136af50c91e46198fc439f2e233094edd39c5d68df88f33045526b872a4a6e06` | 64 KiB |

Fixed endpoints:

- [R8 JAR](https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.1.29/r8-9.1.29.jar)
- [R8 POM](https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.1.29/r8-9.1.29.pom)

The expected identities come from the pre-existing repository pins, not from
the downloads. No local or cached R8 binary is required for this comparison.
This separate two-file inventory does not modify the eight ordinary artifacts
in `tools/fdroid_maven_registry_parity.py`.

## Offline default and explicit comparison

Read and validate only local metadata, without network or output writes:

```bash
python3 -I -B tools/fdroid_r8_registry_parity.py
```

An online comparison requires an explicit flag, a new absolute directory
outside the checkout, and external process supervision:

```bash
/usr/bin/timeout --signal=TERM --kill-after=5s 180s \
  /usr/bin/python3 -I -B tools/fdroid_r8_registry_parity.py \
    --download-and-compare \
    --output-dir /absolute/new/r8-comparison
```

The output parent must already exist. There are no retries, fallback URLs,
arbitrary coordinates or digests. Every redirect, including a self-redirect,
fails. TLS verification and the environment's configured network transport
remain enabled; the helper does not discover account credentials or attach
cookies or authorization headers.

The new private output directory and checkout root are held by directory file
descriptors throughout the comparison. Metadata reads, artifact creation and
readback, and receipt publication use relative no-follow operations through
those anchors. Reopened paths only check identity; replacing a path with a
symlink cannot redirect writes into another directory or the checkout.

The response must be HTTP 200 with exactly one positive canonical
Content-Length, identity content encoding and no Transfer-Encoding. Streaming
reads are at most 64 KiB; file and cumulative limits are enforced before and
during reads. A streamed SHA-256 and a separate output readback must agree
with the unchanged pin. The local XML is verified again after success or
failure, and even whitespace-only pin-file drift fails the comparison.

The socket timeout is 15 seconds and the per-artifact signal timer is 60
seconds. Python signal handling is not an OS hard deadline during all
blocking C/DNS calls; the required outer 180-second supervisor is a separate
boundary, and the receipt does not pretend to verify its own supervisor.

Downloaded files remain opaque non-executable data. The JAR is never unpacked
or run, and the downloaded POM is never parsed. Partial failures preserve
which request and how many streamed bytes completed without claiming that
both artifacts matched. A failed receipt publication cannot return success.
The final `PARITY.json` name is linked without overwrite only after its
exclusive `PARITY.pending.json` has been flushed, synced and closed
successfully. The pending name remains for diagnosis; consumers must not treat
it as a published result. No fallible cleanup occurs after final publication.

## CI and interpretation

The isolated source-proof workflow has a separate R8 comparison job after
sandbox readiness. Local contract tests use synthetic XML and mocked HTTP in
the same offline, read-only, capability-free test sandbox as the source proof
regressions. They are not actual registry availability or byte-parity evidence.
The direct isolated-Python CLI and symlink-invocation regressions ensure the
fixed utility import cannot be redirected to an adjacent shadow module.
The import also temporarily suppresses bytecode-cache writes, restoring the
caller's prior setting afterward; the offline default does not depend on
`-B` to avoid creating a sibling cache directory.

Actual online evidence must record the tested commit, run, job and complete
two-file receipt before claiming registry parity. At initial implementation,
that check is pending. An HTTP error or digest mismatch must remain failed;
it is not permission to change the version, pin, endpoint or scanner policy.

Local validation of this implementation passed 493 source-proof contract
tests, including all 46 R8 cases and the fixed CI-job contract, in the enforced
offline, read-only sandbox. All 15 repository-policy tests and the real
repository verifier also passed. The actual offline R8 invocation verified
the unchanged 256,832-byte Gradle pin metadata, SHA-256
`ee785fe0326dbdb96d8912608281f580df9f479955322050699f3229756456f3`,
without attempting network access. An independent read-only review checked
the directory-anchoring, canonical import and receipt-failure regressions.
These are local implementation checks, not a substitute for the pending
official registry comparison.

Even a successful result establishes only those two official registry byte
identities. Moving the candidate build to standard `google()` resolution
still requires a separate reviewed configuration change, strict clean-cache
dependency verification, optimized-APK and application regressions, and an
actual F-Droid scanner/build result. The original scanner inventory remains
unresolved, and the ordinary release's dependencies and functionality remain
unchanged by this check.
