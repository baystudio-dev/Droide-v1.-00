# R46 Java producer and actual-AAR validation

Freshly executed on 2026-10-17. All results are host/build-only.

## Actual compilation and admission

The preliminary inspection genuinely compiled the nine source families using the
pinned JDK/API36 inputs. Independent review read back the candidate AAR and javap
difference before the final class pin was set. Only `iconst_0; istore_3` after
`ReaderBackend.queued()` and required control-flow/exception-offset adjustments
appear in `readUntilClosed`; `writeChunk` is unchanged.

After pinning the inspected class, the new producer invoked javac twice again in
separate fresh roots. Full AAR bytes match:

- AAR: `e78fd1508f55a43d7e0a8de6a8b5fa4ce165b34c053d2c442b19d72d4c07463d`
- classes.jar: `2525048ffeb4e6729480d8e9628f33a55793b83caf02dd12fff91e741e72058a`
- TerminalPtyIo.class: `a862712e743f276925bfdc480e00dabd9e31a4cc54c68aae0af38eb0e84a3e1f`
- Unchanged JNI.class: `0f46033d6a7753e9bf7fa4784811c430a04b897462759b7df281553a6608782a`

`build/receipt.json` binds all current inputs, both genuine compiler outputs,
commands and logs, and the fixed publication. The full original baseline gate and
new derivative gate passed after publication and again after host regressions.
The 34-class candidate has exactly one changed class; all other class/resource,
source/native/JNI and historical evidence identities remain as pinned.

## Finite mutation suite

`python3 -B -m unittest discover -s tools/tests -p test_termux_pty_derivative.py -v`
passed **37 tests**, using actual generated AAR/classes. Raw output is in
`mutation-tests.stderr.log` and `mutation-tests.stdout.log`.

Coverage includes all 16 native artifacts and four runtime executables; old
source/receipt/gate mutations; missing/extra ABI; source/patch/producer/verifier
and complete JDK trust-root changes; false release/device/native-runtime claims;
fixed artifact selection; stale final/independent AARs; raw output/evidence and
before/after-input inconsistencies, including repinned mutated evidence; relaxed
compiler options/environment; unexpected class/resource/native entries; ZIP
symlinks/traversal/duplicates; actual compiled field/method/class modifiers and
constants; unrelated class changes; read-bytecode tampering; writer-code changes;
existing-publication refusal; and unchanged old-private/default-release rejection.

## Actual-AAR regression fixtures

`tools/tests/run_pty_aar_regressions.py` copied the three preserved fixtures without
changing their bytes and added a separate provenance/measurement wrapper. Fixture
compilation listed only those four fixture sources, disabled annotation processing
and implicit sources, and used an empty sourcepath. Every runtime classpath begins
with its extracted actual AAR classes.jar. Fixture output was checked to contain
no production classes. The same JVM records CodeSource, resource URL and SHA-256
for the actual loaded PTY/owner/queue classes before executing tests.

Fresh `host-regressions/results.json` and raw per-stage commands/logs show:

- Baseline: 25 tests, exactly the two expected progress failures
- Actual primary AAR: 25 tests, zero failures
- Actual independent AAR: 25 tests, zero failures
- All preserved retry-storm, cancellation, HUP-tail, backpressure, no-replay and
  writer-absolute-deadline controls retained
- Four preserved stdout-pipe trials per stage, plus one measured 4 MiB trial
- Every measured 4 MiB run: exact ordered bytes, 1,025 reads, payload SHA-256
  `ceb45b1f82c716b98b0e046907911c93cba2afd4c4c0e122c6493f5a4bfa7722`
- Baseline: 64 requested backoffs / 640 ms; both candidates: 0 / 0 ms

Single measured host observations (not a benchmark): baseline reader-thread CPU
16,083,884 ns, primary 41,030,241 ns, independent 60,521,713 ns. Wall times were
678,759,292 ns, 69,819,994 ns and 121,882,695 ns respectively. These observations do
**not** establish lower CPU use, real-device throughput, native PTY behavior, or a
fix for curl/APT slowness. Backpressure/cancellation assertions are deterministic
host controls, not measured Android cancel latency.

No APK, full Gradle/Android aggregate, JNI/native runtime or device execution was
performed by these producers/tests. Final DEX binding, packaging/lint, independent
integration review and device validation remain separate gates.
