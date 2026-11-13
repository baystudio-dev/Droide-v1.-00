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
| `tools/fdroid_proot_loader_source_build.py` | The pinned OpenMinis archive and reviewed loader/Makefile blobs; separately provisioned NDK 29.0.14206865 at API 24 | Two independent standalone ARM64/ARM32 loader builds, bounded ELF/symbol/stub inspection, header/driver evidence and separate raw/post-strip comparisons. Success requires both complete retained baseline bytes; no engine build, historical ELF-cleaner execution, APK substitution or device claim. |
| `tools/fdroid_proot_engine_source_build.py` | The unchanged patched PRoot/talloc production recipe, NDK 28.2.13676358 at API 29 and two independently source-built loader pairs | Two full engine builds; symbol/relocation-backed offset and embedded-loader coupling; raw/post-strip reproducibility and exact retained engine bytes required. Separate dynamic ELF inspector; no target execution, production substitution or acceptance claim. |
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

The compatibility extension retains that raw failure and adds a separately
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
promotion. Fixture tests are not a fresh compiler/adaptation result; the
subsequent actual execution is recorded in the dated PR11 checkpoint below.

The extension's local enforced-sandbox checkpoint passed **297 tests**: the
earlier 265 plus six constructor-binding tests, four binding/gate receipt tests,
twelve exact metadata-transform tests, seven compatibility-pipeline tests and
three mocked full-build orchestration tests. These include additional/missing
delta rejection, constant-pool overflow, raw Code/debug preservation, independent
output failure/nondeterminism and retention of the raw failing verdict. A
separate static peer review found no concrete blocker. Those local results do
not substitute for the actual compiler/adaptation CI evidence recorded below.

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
it provision trusted compiler tools and run the seven independent contract,
CLI, PRoot-loader, PRoot-engine, SPAKE2, emulator and View jobs. A separate data-only registry parity job downloads
the eight fixed ordinary Maven artifacts under external process supervision;
it never extracts, executes, or substitutes them into an application build.
See [registry parity scope](FDROID_MAVEN_REGISTRY_PARITY.md).
The [baseline-free native lane](FDROID_NATIVE_SOURCE_CORE.md) separately masks
the four retained native APK inputs with an empty read-only directory and calls
source-only CLI/loader/engine cores. Retained-byte comparison remains in the
original proof wrappers. This lane still discards every generated artifact;
it is not production source-only APK integration or scanner acceptance.
The separate [R8 registry comparison](FDROID_R8_REGISTRY_PARITY.md) checks only
the existing R8 JAR/POM pins at their fixed Google Maven endpoints. Its mocked
network and XML contracts run in the offline source-proof test suite before
the explicit supervised two-file comparison. Neither registry check is a
source-build result or a production dependency substitution.

The loader experiment adds no sandbox mount or authority. It uses the same
read-only NDK input and resource limits as the CLI proof, while provisioning
its historical compiler generation separately. See the
[loader recipe and evidence boundaries](FDROID_PROOT_LOADER_SOURCE_PROOF.md).

The separate full-engine experiment adds exactly one allowlisted read-only
mount for its source-loader NDK. Both toolchains are external and disjoint;
the original sandbox limits and privileges are unchanged. It preserves the
patched engine recipe and uses freshly source-built loader pairs as compiler
inputs. See [engine proof and coupling gates](FDROID_PROOT_ENGINE_SOURCE_PROOF.md).

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

1. Investigate the remaining View class-byte differences and complete Android
   resource/link/runtime checks before production integration. The isolated
   raw/adapted proof results below are not blanket runtime-equivalence results.
2. Complete production-ready source closure/integration for terminal-view,
   Kadb mDNS, JGit and the PRoot engine/loader pair; the isolated loader byte
   reproduction below does not complete engine integration. Retain all current
   runtime capabilities.
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

## PR11 actual isolated-proof checkpoint: 2026-11-13

[Run 43372378186](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43372378186)
completed **7/7 jobs successfully**: sandbox readiness, registry parity,
source contracts, CLI, SPAKE2, emulator and View. The actual merge checkout was
`1a379ca95b8478bfb1c4649d436d94026fa4f36b`, incorporating PR11 head
`426c79ce38c193cc7114c579a6eb68efb40caea0`. The readiness and source-contract
jobs each reported **297 tests, OK**: two executions of the same suite, not
594 unique tests. Their final probes and the View probe verified the required
offline, read-only, unprivileged sandbox controls.

[View job 131711037711](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43372378186/job/131711037711)
completed both full emulator/View source rebuilds and both independent
metadata adaptations. Raw outputs reproduced the earlier failing hashes and
retained `rawAllAccessibleApiMatched=false` with three metadata paths. The
separate adapted outputs were identical across runs:

| Adapted proof output | SHA-256 |
| --- | --- |
| JAR | `d7bcf81c7fc03c27ec28d4acced3f708a5f0d64f7e07beb679002c79f8a974ce` |
| AAR | `f2ab04a6ac29b5a3effc7dfded488a1b174124d4c60e772577b8357781aea5a7` |

The unchanged strict comparator reported zero adapted API/metadata differences,
nineteen classes, five matching actual resource GETSTATIC reads and two matching
constructor-binding checks. Exactly three metadata changes affected two
classes; seventeen classes were untouched. All complete Code attributes,
existing constant-pool entries and unrelated structure were preserved **relative
to the raw rebuild**. Inputs remained unchanged; no production output was
promoted and no reference bytecode was copied into a candidate.

Both raw and adapted outputs still have **nineteen complete class-byte
differences from the historical reference**. No general method-body equivalence,
Android resource link, device QA or F-Droid acceptance is established. The
bounded summary explicitly truncates individual constructor-instruction records;
its matched gate is not a complete printed instruction audit.

The separate
[Android run 43372378207](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43372378207)
subsequently completed **4/4 jobs successfully** on the same tested merge.
Its regular-release lane executed 112 critical application cases across
thirteen classes, built and verified the unsigned release, then passed
**552 Python tests without skips**, including twenty-one actual verified-engine
fixture checks and five optimized-APK native-binding checks. The earlier
repository-only lane's two fixture-availability skips are not passed artifact
tests; those fixtures were exercised in the later release lane.

The candidate lane passed its six consent/source guards, Canonical rootfs
parity and final APK verifier. Its unsigned APK SHA-256 was
`3587539a324327d524c70ca4fcfb56ae008349e8706df752ebb9996e713ae41a`,
while the ordinary unsigned release was
`777e0c108011ad5cae860fa3859c1a339d9e7ed68b07c72ba4e6456ca147d107`.
The distinct APK hashes are expected for the candidate packaging/BuildConfig;
the separate dependency-parity job verified an exact match of all 137 resolved
components, 759 edges and 103 artifact-file hashes. The candidate lane did not
separately execute the release lane's full Python artifact suite.

These application jobs still used the existing production integration, not
the isolated View/loader/engine proof outputs. Their results do not establish
source-only dependency substitution or device equivalence. The original
**33 scanner findings remain unresolved**.

## Subsequent loader-proof local checkpoint

The expanded enforced-sandbox suite passed **357 tests**: the previous 297,
three loader runner/workflow contracts, twenty-eight loader build/input
contracts and twenty-nine bounded ELF tests. Both existing stripped loaders
were parsed only as hash-pinned comparison data. Compiler orchestration used
mocked tools; actual NDK compilation was still pending at that local checkpoint
and is recorded separately below.
No fixture result is counted as a native compilation or F-Droid acceptance.

## PR12 actual isolated-loader checkpoint: 2026-11-13

[Run 43374627049](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43374627049)
completed **8/8 jobs successfully** on tested merge
`f41850f2cfb03b63af422408c94cb34bb00f0785`, incorporating PR12 head
`7d6a1a140d22579d1b7c1a453d0ef7151b589f87`. The readiness and source-contract
jobs each reported **357 tests, OK**: two executions of the same suite, not
714 unique tests. Their final probes and the loader probe passed the existing
unprivileged, read-only, offline sandbox controls.

[Loader job 131718212293](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43374627049/job/131718212293)
compiled the pinned ARM64 and ARM32 loader source twice. Its complete
7,882-character `SUMMARY.json` was independently retrieved from the actual
log. Both raw and stripped outputs reproduced independently, with these
identical values in each build:

| ABI and stage | Bytes | SHA-256 |
| --- | ---: | --- |
| ARM64, unstripped | 18,520 | `9851ac9137c3053926ccce7cfe90ec489a839543f261bb19f5269d6a9cbfdabb` |
| ARM64, stripped | 18,136 | `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04` |
| ARM32, unstripped | 6,504 | `4a1c0657c05577deee93ea1fde076828c5be0358f2477f006985141d677c9176` |
| ARM32, stripped | 6,244 | `25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34` |

The stripped outputs exactly match both unchanged bundled loader baselines in
both builds. `.text` remained unchanged by stripping; all observed input,
tool, staged-source, header and output integrity rereads passed. GNU gawk and
independent ELF inspection agreed on the ARM64 pokedata offset **1,120 bytes**,
including verification of the mapped store/trap stub before symbols were
stripped. The exact result is
`source-built-both-retained-loader-bytes-reproduced`.

The historical cleaner was **not executed**, and the installed NDK r29/API 24
toolchain was **not independently authenticated against its historical ZIP**.
Exact final-byte equality is the narrower result; it does not recreate every
historical Termux environment detail or assert that the cleaner is generally
a no-op. No loader output was executed, packaged or promoted, and no PRoot
engine build, Android runtime QA or F-Droid acceptance is established. See
[loader recipe and actual evidence](FDROID_PROOT_LOADER_SOURCE_PROOF.md).

The separate PR12 Android application workflow was still in progress at the
last recorded observation. These isolated results do not establish its
completion or a production source-only engine integration. The original
**33 scanner findings remain unresolved**.
