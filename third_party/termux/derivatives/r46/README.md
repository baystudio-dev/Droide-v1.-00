# R46 private Java PTY derivative

This additive, offline route leaves every historical source, patch, AAR, native
artifact, ABI contract, receipt and verifier unchanged. It does not enable any
release build, device-validation claim or native rebuild. The existing private
route continues to reject substitution of the derivative at historical paths.

## Reviewed change

Only `TerminalPtyIo.readUntilClosed` changes: after successful enqueue and
`backend.queued()`, reset `immediate = 0`. The baseline source SHA-256 is
`c306cd096e1bf181f9f2c93616df5cc1f72811c33024d0e75943142fa309bbef`;
the candidate source SHA-256 is
`01c470e72e74106426d494849f61d42d9fdb456546b506b393895316148f48c4`.
The adjacent patch is an exact unified diff, not a replacement historical patch.

## Trust roots and production

`BASELINE.json` pins 97 original files and exact protected file sets. Its complete
SHA-256 is embedded in the new verifier. `JDK17.json` pins all 455 files and modes
of the restored Temurin JDK 17.0.20.1+1, including launchers, compiler modules and
runtime libraries. Its digest is embedded too. The verifier fixes the candidate
source, patch, builder and inspected compiled PTY implementation identities.
No caller-supplied receipt, AAR, compiler shim or reference changes those roots.

Run from the repository root, with the exact restored local toolchain paths:

    python3 -B tools/build_termux_pty_derivative.py --java-home /absolute/pinned/jdk --android-jar /absolute/api36/android.jar

The builder refuses an existing `build` directory. It first runs the untouched
original private provenance gate on the actual historical paths. It then creates
a unique sibling staging directory, invokes the real compiler twice in separate
fresh roots, compares the complete outputs, verifies the complete publication,
and atomically renames it to `build` with kernel-enforced no-replacement. Failed
staging directories are retained as failure evidence; they never authorize use.

Each compilation retains copied inputs, before/after repository and copied-input
manifests, complete pinned JDK identities, actual command/environment/exit and raw
stdout/stderr, compiled class files and the deterministic AAR. Compilation uses
Java 8 output, `-proc:none`, `-implicit:none` and an empty explicit sourcepath.
Only nine reviewed source files are listed. The process receives an allowlisted
environment; no network, Gradle, annotation processor, implicit source, APK, or
native compilation is involved.

## Admission and consumer contract

    python3 -B tools/verify_termux_pty_derivative.py
    python3 -B -m unittest discover -s tools/tests -p test_termux_pty_derivative.py

The public function `verify_derivative_inputs(root)` always checks the original
baseline gate first and then the fixed publication
`third_party/termux/derivatives/r46/build/terminal-emulator-r46-private.aar`.
It returns the measured output/entry hashes, original private native receipt,
all 16 native build identities, and the four unchanged runtime identities.
A successful baseline check alone never admits the derivative.

The parser never initializes classes. It checks exactly twelve static native JNI
declarations against the original ABI/native-table evidence, exact AAR/JAR entry
sets, unchanged non-PTY class/resource bytes, and byte-identical constant pools,
class/field/method modifiers, interfaces, descriptors, constants and non-Code
attributes. Only `readUntilClosed` Code may differ; its entire class must also
match the reviewed actual compiler output. The two retained compiled trees and
AARs must agree exactly. ZIP duplicates, traversal, symlinks, unexpected payloads,
receipt promotion, stale outputs, changed inputs and altered tool identities fail.

The mutation suite requires the genuine generated AARs and uses their extracted
classes. It cannot replace the separate PTY behavior harness: that harness must
compile fixtures only, use an empty sourcepath with processors/implicit compilation
disabled, load each actual AAR's extracted classes first, and record the loaded
class SHA-256 and CodeSource. It must retain the two expected baseline progress
failures and all 25 passing candidate cases. No host-only test establishes Android
kernel/PTY behavior, CPU impact or curl/APT throughput.

Final DEX implementation binding, packaging/lint, and device validation are
separate mandatory gates. This producer never creates an APK and its receipt
always has `releaseCompatible=false`, `deviceValidated=false`,
`nativeBuildPerformed=false` and `executionEvidence=null`.
