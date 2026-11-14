# Filesystem-native-v1: isolated build-only evidence

This additive lane does not edit historical Termux/R46/R63 receipts, CMake,
production sources, Gradle profiles, packaged libraries or APK gates. Do not copy
its output over private-debug payloads. Successful output remains
`BUILD_ONLY_DEVICE_UNVALIDATED`; Android/device, PRoot, full API semantics,
app-side DEX binding and release admission remain separate.

## Commands

Run from this source checkout using Python 3.11+:

```sh
python3 -m unittest discover -s tools/tests -p test_filesystem_native_v1.py -v
python3 tools/filesystem_native_v1.py build --root "$PWD" --sdk /absolute/android-sdk --output /absolute/new-filesystem-native-v1-run
python3 tools/filesystem_native_v1.py verify --root "$PWD" --sdk /absolute/android-sdk --output /absolute/new-filesystem-native-v1-run
```

Source, SDK and output roots must be disjoint and contain no symlink components.
Paths are restricted to ASCII letters, digits, underscore, dot, slash and hyphen
so CMake flag paths cannot introduce shell metacharacters. Output must not already exist. SDK must already contain NDK 28.2.13676358 and
CMake 3.22.1. No downloads, installs or native execution occur. Each ABI is built
from current source with API29 in separate primary/independent roots, using only
CMake target `droide_files`; old Termux artifacts are untouched. Compiler-force-
included JNI declarations bind the frozen Kotlin descriptors to C types.

Full installed NDK/CMake trees are measured before/after; internal toolchain
symlinks are recorded and must resolve within their own tree. Named compiler,
linker, CMake/Ninja and inspection tools must match existing historical binary
hashes. This measures an installed toolchain; it does **not** invent vendor
archive provenance. Snapshotting the full SDK packages can take minutes.

The verifier remeasures pinned source, contract/tool script, complete installed
input trees, generated header, exact command records, compiler logs, compile
commands and actual ELF outputs. It checks four ABI identities, exact 17 defined
function exports, a bounded libc import/dependency set, SONAME, NX stack, nonempty RELRO program-header presence,
BIND_NOW, no text relocations/RPATH/RUNPATH and 16KiB LOAD alignment. Loader PT_DYNAMIC and table pointers must map to the
inspected tables; JNI functions must reside in executable LOAD/section ranges.
The emitted Android identity note must declare API29, r28c, build13676358. Both full
unstripped binaries must match. Unknown imports or divergent reproducibility
fail closed for review; do not expand allowlists simply to force PASS.

## Trust and limits

These are local build receipts, not signed remote attestations. They detect
ordinary stale/tampered inputs and outputs against this reviewed contract; an
attacker who can replace the verifier, contract, source and receipts together is
outside the trust boundary. Keep an externally retained source/tooling manifest
when transferring the run. Re-running `verify` uses the selected source's script
and requires paths/recorded commands from the original executor; it is not a
portable path-remapping receipt format.

Unit tests exercise binary parsing, source/header contracts, symlink/overlap
rejection and receipt status tampering without an NDK. They are not evidence of
successful four-ABI compilation. Host publication tests covering two legacy JNI
methods do not certify all 17 APIs. Later packaging requires a separately
reviewed composite current-source gate and actual APK-byte checks; intentional
R63 app-binding denial remains unchanged.

This is not a general hostile-ELF loader validator: GNU hash lookup and symbol
version graph semantics are not independently emulated. Admission is limited to
fixed-source, pinned-compiler outputs and the explicitly listed structural checks.

RELRO verification is limited to nonempty program-header presence; it does not
prove complete GOT/dynamic coverage or page-rounded runtime protection. The
__open_2/__openat_2 import entries are the specifically reviewed API29 bionic
FORTIFY wrappers, not permission for arbitrary near-named symbols.
