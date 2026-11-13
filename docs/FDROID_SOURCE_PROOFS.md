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
| `tools/fdroid_termux_view_source_build.py` | Seven pinned View Java files, generated release BuildConfig and non-final resource symbols; source-rebuilt emulator; the verified JDK, API 36 and existing annotation-jvm 1.10.0 | Two clean Java compilations and deterministic proof-only AAR packaging; exact nineteen-class inventory, annotation-aware non-private API and actual resource field accesses are separate gates. Raw and exact-pin metadata-adapted artifacts are distinctly identified; no copied Termux bytecode, resource linking, output execution, APK substitution or device claim. |
| `tools/fdroid_termux_view_compat.py` | Two immutable raw View class preimages and a source-controlled metadata recipe; no reference binary input | Changes exactly two constructor MethodParameters payloads and removes one exact Signature; append-only constant pool and all complete Code attributes/unrelated structure are independently verified unchanged from raw. This is an explicit artifact transformation, not comparator normalization or proof of historical method-body equivalence. |

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
- The first remote proof run stopped before compilation: bwrap's user namespace
  mapped host root rather than the checkout owner and could not traverse the
  private runner home. The second setup returned to the caller's host identity
  before bwrap, but stopped at `setting up uid map: Permission denied`. Neither
  run is evidence of a compiler or application failure. The exact LSM/kernel
  cause is not established by that error alone.
- The repository integrity guard also correctly rejected six new View source
  files absent from its manifest. Their measured SHA-256 values were added to
  the existing manifest, along with a regression test for coverage; the guard
  itself was not changed. At that checkpoint, these setup fixes still required
  a fresh remote run before any successful compilation claim.
- With those setup and manifest regression checks, the local isolated test
  runner passed **117 tests**. This checks command construction, not execution
  of the CI-only sudo branch.
- After the mapping-helper, final-probe and integration checks were added, the
  local runner passed **191 tests** and the actual final probe reported
  `SOURCE_PROOF_SANDBOX_READY`. The probe observed UID/GID 65534, empty capability
  sets, no-new-privileges, read-only inputs, 512 MiB disposable scratch and only
  loopback networking. At that checkpoint the CI-only mapping helper had only
  mocked coverage; the actual remote result is recorded below.
- The registry comparison helper passed **33 pure/mock tests** and verified the
  eight current local pins offline. No registry download, source compilation or
  application equivalence is implied by those local-only checks.
- The documentation guard was separately corrected to distinguish an explicit
  Android NDK release or archive name from an internal development milestone.
  Its 15 mutation tests and the real repository check passed locally; unrelated
  milestone tokens on the same line remain rejected. This does not change any
  native source, compiler version, binary, or checksum.
- Tests ran with source/toolchain read-only, a clean environment, no external
  network, disposable scratch (64 MiB for initial fixture runs, 512 MiB for the
  dedicated runner), and explicit resource and time limits. No generated target
  files were retained.
- The existing host CLI behavioral test compiled the C host shim and passed its
  help/bare-command case, but nine local socket cases were **blocked by the
  execution environment** (`socket(AF_UNIX)` returned `EPERM`). This is neither
  a nine-test code regression nor a nine-test pass; the restriction was not
  relaxed. This does not rerun PRoot's separate seccomp regression.

### Actual remote checkpoint

[Source-proof run 43367069641](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43367069641)
completed all six jobs successfully on 2026-11-13. It tested PR merge
`b8c59e3b6fbf17ae88566dd0d4ae14a55a12b6cc`, incorporating head
`2ba0111b2cd9d8eae49f0eb084b19af633854bba`. The results below come from the
actual diagnostic receipts and compiler logs, not only job badges.

| Check | Observed result | Not established |
| --- | --- | --- |
| CI namespace and final probe | Actual setup succeeded; UID/GID 65534, all capability sets empty, no-new-privileges, read-only inputs, scratch size 512 MiB, no external network route. All 191 source contracts passed. | Compatibility with arbitrary host security policies. |
| CLI | Two builds produced exactly 4,528 bytes each, SHA-256 `913b313398b30115416acd20959b1c449eab0563c15c83230862b03e44e7cfee`; exact existing bundled CLI and installer pin match. | ARM64 runtime/device tests; this is not the PRoot engine or loaders. |
| SPAKE2 | Two builds identical; all four classes and all entry payloads match the reference; public/protected API and retained compatibility vector pass. | Whole-JAR wrapping is different; no independent cryptographic audit or physical-device interoperability test. |
| Full emulator Java | Eighteen sources compiled into forty classes; all thirty-three current overlay class hashes and all accessible APIs match; independent-build reproducibility gate passed. | Seven retained class bytes differ. Method-body or runtime equivalence is not inferred from API equality. |
| Official registry byte parity | All eight fixed artifact downloads match existing SHA-256 pins; 12,952,218 bytes total, no redirects/errors, local before/after pins unchanged. | Source builds, custom derivatives, R8, POM/transitive graph parity or F-Droid acceptance. |

The seven differing retained emulator classes are `BuildConfig`, `KeyHandler`,
`TerminalColorScheme`, `TerminalColors`, `TerminalOutput`, `TextStyle` and
`WcWidth`. Do not label their differences "only debug metadata" without an
independent instruction-level comparison. No result from this run was copied
into a production artifact, dependency mirror or release.

The added View proof is a separate, later checkpoint. At its initial local
checkpoint, its compiler, Android resource linker and physical-device tests had
not run. Its compact summary must distinguish source compilation, byte differences,
API/annotation parity, resource accesses and untested Android behavior.
Its local source-contract suite passed **265 tests** in the same enforced
sandbox: the prior 191 plus four runner tests, thirty-nine View builder tests
and thirty-one metadata/bytecode tests. The real nineteen-class reference also
passed data-only self-comparison with exactly five actual GETSTATIC resource
reads. This validates the proof machinery and fixed input closure, not Java
compiler or APK behavior.

### Actual raw View result and compatibility extension

[Run 43369448300](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43369448300)
tested PR merge `233a52ed10fdd446bac7ddbd00880515dad91a87`, incorporating
head `72d0e47f28db4aa6fefea8a239bfb17ffb004111`. Six jobs passed. The View
compiler succeeded twice, producing all nineteen expected classes and identical
raw JAR/AAR bytes, but its **strict metadata comparison failed**. The narrower
declaration ABI and five actual resource GETSTATIC reads matched; inputs were
unchanged. The exact three metadata paths in two constructors and the raw
artifact hashes are recorded in the
[compiler metadata note](FDROID_TERMUX_COMPILER_METADATA.md).

All nineteen raw class byte hashes differ from the retained reference. The
three metadata deltas are consistent with specific official javac changes, but
neither those causes nor matching API/resources explain every class-byte
difference. The exact historical JDK vendor/update is not established.

The next proof implementation retains that raw failure and adds a separately
named, exact-pin metadata compatibility artifact. Before adaptation it requires
the fixed observed raw JAR/AAR and class hashes, precisely the reviewed full API
differences, unchanged declaration ABI/resource references, and matching resolved
instructions/bindings in the two affected constructors. The source-controlled
helper never reads a reference AAR or copies its bytes. Only the intended
parameter-name payloads and one Signature removal are eligible; existing
constant-pool bytes/indices, every full Code attribute and all unrelated class
structure must remain unchanged. The other seventeen classes remain identical
to the raw source rebuild.

Both raw builds are adapted independently into fresh `compat-run-*` outputs.
The same strict metadata comparator must pass on those adapted classes; the
proof additionally requires raw/adapted reproducibility, byte-preservation
certificates and final input/output integrity checks. Receipts and compact
summaries distinguish raw metadata failure from adapted metadata parity, and
never claim historical method-body equivalence, device validation or production
promotion. Fixture tests are not a fresh compiler/adaptation result; actual
execution of this extension must be recorded independently.

The extension's local enforced-sandbox checkpoint passed **297 tests**: the
earlier 265 plus six constructor-binding tests, four binding/gate receipt tests,
twelve exact metadata-transform tests, seven compatibility-pipeline tests and
three mocked full-build orchestration tests. These include additional/missing
delta rejection, constant-pool overflow, raw Code/debug preservation, independent
output failure/nondeterminism and retention of the raw failing verdict. A
separate static peer review found no concrete blocker. None of these results
substitutes for the pending actual compiler/adaptation CI run.

### Android application checkpoint, separate from isolated View proof

[Android run 43367069506](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43367069506)
completed all four jobs for merge `b8c59e3b6fbf17ae88566dd0d4ae14a55a12b6cc`.
Regular and F-Droid candidate builds and verification succeeded. There were
112 executed critical application cases across thirteen classes and 446 Python
tests, including twenty-one current-engine and five actual optimized-APK/JNI
checks. Both resolved runtime manifests contain 137 components, 759 edges and
103 artifact file hashes, with an exact diff pass. The unsigned candidate APK
was not uploaded, and the stable signed release was not changed.

These application jobs used the existing production View dependency, not the
new isolated View proof output. Their success cannot be transferred to an
unintegrated source-built View AAR. The scanner inventory remains **33 known
unresolved findings**. See the [scanner matrix](FDROID_SCANNER_MATRIX.md).

## Safe execution

Use already verified toolchains. The helpers do not fetch or install anything.
Run builds in an OS-enforced isolated environment: no external network,
read-only source/toolchain mounts, an explicitly allowlisted environment,
scratch-local home/cache/temp, bounded writable scratch and resource/time
limits. Fresh outputs must be outside the source and toolchain trees. Do not
run an output merely because it was successfully compiled.

The isolated CI workflow `.github/workflows/fdroid-source-proofs.yml` first
requires a no-compilation sandbox readiness job. Only after that succeeds does
it provision trusted compiler tools and run the five independent contract,
CLI, SPAKE2, emulator and View jobs. A separate data-only registry parity job downloads
the eight fixed ordinary Maven artifacts under external process supervision;
it never extracts, executes, or substitutes them into an application build.
See [registry parity scope](FDROID_MAVEN_REGISTRY_PARITY.md).

View additionally provisions one existing, pinned AndroidX annotation compiler
dependency from a fixed Google Maven URL with SHA-256 verification, no redirect
following and explicit size/time bounds. Provisioning precedes sandbox entry;
the compiler cannot fetch anything. This adds no production dependency and
does not silently add Kotlin runtime support to the proof classpath.

The CI launcher uses a small reviewed setup helper to map exactly the non-root
checkout owner into an outer user namespace and create an empty network
namespace. A bounded two-way handshake keeps the child from executing bwrap
until the mapping parent has permanently dropped host privileges. Host root
is not mapped. No global AppArmor, sysctl, subordinate-ID or filesystem
permission changes are made. This helper is not a general sudo policy and
must not be granted privilege for unreviewed or concurrently modified source.
Its mocked tests cannot establish that a particular CI kernel permits setup.

Before any target test/compiler runs, a fixed probe must verify the final
unprivileged identity, empty capabilities, no-new-privileges state, read-only
inputs, bounded scratch, masked Git metadata, allowlisted environment and
isolated network tables. Setup or probe failure stops execution; no fallback
to a weaker environment is available. Diagnostic output contains only fixed
non-secret process/kernel metadata, not the inherited environment.

The runner uses a
512 MiB disposable scratch filesystem, a 6 GiB address-space ceiling (JVM heap
limits are smaller), 120 CPU seconds per process, 128 processes, 256 descriptors,
a 64 MiB per-file limit and a 300-second wall-clock limit.

The workflow builds proof outputs only. It must not upload those
outputs as application releases, invoke signing, change dependency pins, or
fall back to unsandboxed execution if isolation is unavailable.

## Remaining gates

1. Complete the later View proof and inspect all byte/API discrepancies before
   considering production integration. The earlier three proof builds passed
   with the scoped results above, not a blanket runtime-equivalence result.
2. Complete source reconstruction for terminal-view, Kadb mDNS, JGit and native
   PRoot/loaders; retain all current runtime capabilities.
3. Integrate the verified ordinary Maven payloads only through a separately
   reviewed source-only build path, retaining versions and integrity checks;
   still establish R8 and custom-derivative provenance.
4. Design a dedicated source-only build path and equally strict output verifier;
   remove only obsolete binary inputs from that build's staged source, not
   functionality from the application. Do not blanket-ignore scanner errors.
5. Run F-Droid lint/scanner/build, then physical ARM64 regression checks and
   review signing/update-channel compatibility before any new release tag.
6. Obtain F-Droid maintainer review. Successful project CI is not acceptance.

Authoritative policy references: [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/),
[developer FAQ](https://f-droid.org/docs/FAQ_-_App_Developers/), and
[Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/).
