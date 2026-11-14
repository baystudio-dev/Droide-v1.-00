# Current-source Termux native v1 — bounded build diagnostics

New lane, not a repair of R40/R46/R63 receipts. Never edits production, CMake,
Gradle, historical pins or packaged artifacts. Reuses frozen filesystem-native-v1
path, hashing, snapshot and deterministic CMake-command primitives by composition;
its ELF policy is separately scoped to Termux's sole JNI_OnLoad export.

```sh
python3 -m unittest discover -s tools/tests -p test_termux_native_v1.py -v
python3 tools/termux_native_v1.py build --root "$PWD" --sdk /absolute/sdk --output /absolute/new-termux-run
python3 tools/termux_native_v1.py verify --root "$PWD" --sdk /absolute/sdk --output /absolute/new-termux-run
```

Use Python3.11+, installed pinned NDK28.2.13676358/CMake3.22.1, disjoint nonsymlink
ASCII-safe paths and a nonexistent output. No downloads, installs or Android
execution. Builds only `termux`, API29, Debug, fourABIs in each of two fresh roots.
Full unstripped matching outputs, unchanged measured inputs and exact command
records are mandatory. Header prototypes generated from the12 frozen descriptors
are forceincluded so C function signatures cannot silently drift. Final compiled
Java class identity is **not** inferred from this source/prototype check.

Static inspection binds actual loader dynamic pointers, 16KiB LOADs, NX stack,
nonempty RELRO header, BIND_NOW, API29/r28c/build13676358 note, Termux SONAME,
exact measured perABI import sets and sole JNI_OnLoad export. It locates the
actual unstripped `owned_methods` symbol, reconstructs each of36 pointer slots
from loader REL/RELA relative relocations, checks12 ordered name/descriptor pairs
and exact local function symbols/executable bounds. Decoy strings alone cannot
pass. `JNI_OnLoad` disassembly is retained from the measured installed NDK.

These checks do not prove that the Android JNI_OnLoad control flow registers
that table/class/count. The receipt deliberately remains
`BUILD_ONLY_REGISTRATION_BINDING_PENDING`, `componentAdmitted=false`,
`registrationPathVerified=false`, `finalJavaBindingVerified=false`.
Separately reviewed unchanged-source JNI_OnLoad execution on Linux and final
selected JNI.class inspection may be composed later by a coordinator, without
claiming Android execution. Do not clear flags manually. Do not use the obsolete
numeric-PID native test runner for this owned-token ABI.

Inputs pin current termux.c/OWNED_PROCESS_ABI/JNI.java/CMake and referenced
filesystem C. Historical native REBUILD.json is recorded unchanged, including
its older source identity, not interpreted as current source proof. Named build
tools match historical binary pins; full installed NDK/CMake trees are rehashed.
This is not vendor-signed provenance or an externally signed build attestation.

Limits: only pinned-compiler structural ELF checks; no independent GNU hash or
symbol-version loader emulation, no complete RELRO coverage proof, no Android
runtime registration, PTY lifecycle or app DEX proof. Current private Gradle
still selects old native payloads. No new APK/provider wiring is performed.

The versioned JSON has a fixed SHA in its runner. It pins both imported helper
files, including the immutable filesystem-v1 utility; runtime module paths must
match the selected root. Loader JMPREL destinations join the unique relocation
map. Packed Android/RELR encodings are rejected rather than ignored; a PLT
relocation may not overwrite a registration pointer.

Supported relocation destinations must be pointer-aligned and wholly inside a
writable-at-load file-backed LOAD segment. With fixed pointer widths, unique
aligned destinations also reject partial pointer overlap (including slot+1).
