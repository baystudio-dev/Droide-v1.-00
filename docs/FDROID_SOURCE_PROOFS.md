# Independent source-build proofs: scope and release gates

These tools are a preparation step for F-Droid, not a production dependency
replacement or an accepted F-Droid build recipe. They do not modify the stable
release, packaged native files, dependency coordinates, installer checksums or
signing configuration. They must not be used to claim the 33 baseline scanner
findings have disappeared.

## What is being proved

| Helper | Compilation inputs | Evidence and limitations |
| --- | --- | --- |
| `tools/fdroid_cli_source_build.py` | The pinned existing C source and Android NDK 28.2.13676358 | Two independent builds; static ELF64/AArch64/ET_EXEC and 16 KiB layout checks; exact output comparison; separate comparison against the unchanged bundled CLI. Does not execute the ARM64 output or prove device compatibility. |
| `tools/fdroid_spake2_source_build.py` | Two pinned Java sources, notices, and the complete verified Temurin 17.0.20.1+1 JDK | Empty compiler classpath/sourcepath; deterministic reconstruction; exact class/entry comparison and separate API/whole-JAR comparison. Optional retained deterministic vector test is not a cryptographic audit or device-interoperability certification. |
| `tools/fdroid_termux_source_build.py` | Thirteen upstream Java families, four Droide families, generated BuildConfig, the pinned JDK and API 36 android.jar | Eighteen source files produce an expected forty classes; latest overlay stage wins; no precompiled Termux classes on compiler classpath. Exact existing overlay hashes, retained accessible APIs including package-private members, and the owned-process JNI contract are checked separately. Not a terminal-view/AAR/native/APK rebuild. |
| `tools/fdroid_termux_view_sources.py` | The original manifest, three XML resources and license from immutable Termux v0.118.0 source | Source-only provenance verification by default, with optional pinned-AAR comparison. Drawable bytes match; string XML is semantically equal; the built manifest adds only the verified SDK declaration. Does not compile the View or generate Android resources. |

The reference binary inputs are inspection data, never candidate compiler
inputs. The future production source-only path must not require those reference
binaries after source/API manifests have been independently reviewed. A matching
API is not equivalent to matching implementation bytes, and neither establishes
Android runtime behavior by itself. The tools report these distinctions rather
than changing an existing pin to make a comparison pass.

Whole ZIP/JAR bytes may differ because the proof uses canonical archive
packaging. Where relevant, class bytes, non-class entries and archive wrapping
are compared independently. No result is silently substituted into Gradle.

## Validation recorded so far

- At the initial implementation snapshot, **67 pure-Python contract/mutation
  tests passed**: 20 CLI, 20 SPAKE2, and 27 emulator tests. These tested the
  proof machinery and read-only input checks, not actual target compilation.
- After peer-review hardening and addition of the source-resource and runner
  checks, `bash tools/run_fdroid_source_proofs.sh tests` passed **115 tests**.
  The runner masks Git metadata and runs only source-proof tests. The existing
  scanner inventory and listing guards remain in Android CI, unchanged; their
  Git-index-dependent checks are not silently interpreted as source-proof tests.
- Tests ran with source/toolchain read-only, a clean environment, no external
  network, a 64 MiB disposable scratch filesystem, and explicit resource and
  time limits. No generated target files were retained.
- The existing host CLI behavioral test compiled the C host shim and passed its
  help/bare-command case, but nine local socket cases were **blocked by the
  execution environment** (`socket(AF_UNIX)` returned `EPERM`). This is neither
  a nine-test code regression nor a nine-test pass; the restriction was not
  relaxed. This does not rerun PRoot's separate seccomp regression.
- Actual source compilations, runtime vectors, device QA and F-Droid scanner
  acceptance remain pending until separately recorded results are available.

## Safe execution

Use already verified toolchains. The helpers do not fetch or install anything.
Run builds in an OS-enforced isolated environment: no external network,
read-only source/toolchain mounts, an explicitly allowlisted environment,
scratch-local home/cache/temp, bounded writable scratch and resource/time
limits. Fresh outputs must be outside the source and toolchain trees. Do not
run an output merely because it was successfully compiled.

The isolated CI workflow `.github/workflows/fdroid-source-proofs.yml` provisions
trusted compiler/sandbox tools before running the offline proofs. Its four jobs
cover the contracts, CLI, SPAKE2 and emulator independently. The runner uses a
512 MiB disposable scratch filesystem, a 6 GiB address-space ceiling (JVM heap
limits are smaller), 120 CPU seconds per process, 128 processes, 256 descriptors,
a 64 MiB per-file limit and a 300-second wall-clock limit.

The workflow builds proof outputs only. It must not upload those
outputs as application releases, invoke signing, change dependency pins, or
fall back to unsandboxed execution if isolation is unavailable.

## Remaining gates

1. Run the three actual proof builds with the exact toolchain and inspect any
   byte/API discrepancy before considering production integration.
2. Complete source reconstruction for terminal-view, Kadb mDNS, JGit and native
   PRoot/loaders; retain all current runtime capabilities.
3. Independently compare ordinary mirrored libraries against their official
   trusted Maven artifacts, retaining versions and integrity verification.
4. Design a dedicated source-only build path and equally strict output verifier;
   remove only obsolete binary inputs from that build's staged source, not
   functionality from the application. Do not blanket-ignore scanner errors.
5. Run F-Droid lint/scanner/build, then physical ARM64 regression checks and
   review signing/update-channel compatibility before any new release tag.
6. Obtain F-Droid maintainer review. Successful project CI is not acceptance.

Authoritative policy references: [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/),
[developer FAQ](https://f-droid.org/docs/FAQ_-_App_Developers/), and
[Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/).
