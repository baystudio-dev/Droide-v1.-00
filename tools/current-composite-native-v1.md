# Current composite native v1 — build-only evidence

This additive consumer verifies independent current filesystem and Termux builds,
a fresh Java v2 overlay build, and fresh Linux-owned JNI/regression executions.
It copies exactly twelve native providers plus the exact fresh engine AAR into a
new directory. It does not change historical receipts, production sources,
packaged native libraries, Gradle gates, or old source pins.

## Commands

```sh
python3 -B -E -s tools/current_composite_native_v1.py assemble \
  --root SOURCE --sdk SDK --java-home EXACT_VENDOR_JDK \
  --android-jar ANDROID_JAR --vendor-archive VERIFIED_VENDOR_ARCHIVE \
  --filesystem-output FILESYSTEM_RUN --termux-output TERMUX_RUN \
  --engine-output JAVA_V2_RUN --host-output FRESH_HOST_RUN \
  --junit JUNIT_JAR --hamcrest HAMCREST_JAR --output NEW_COMPOSITE
python3 -B -E -s tools/current_composite_native_v1.py verify \
  --root SOURCE_OR_IDENTICAL_CONSUMER_COPY --output NEW_COMPOSITE
python3 -m unittest discover -s tools/tests -p test_current_composite_native_v1.py -v
```

All paths must remain accessible. Existing output is refused; partial failed
runs remain diagnostic evidence, never resumed or overwritten. The fixed
contract binds source/tool inputs and four auxiliary providers. Child verifiers
are executed using fixed argument vectors, not receipt-supplied commands.

## Output and lineage

- `engine/terminal-engine.aar`: fresh Java v2 bytes; retained upstream binary
  base with explicitly compiled overlays, not a full source rebuild.
- `native/<abi>/<library>`: eight freshly reproduced core libraries, namely
  `libdroide_files.so` and `libtermux.so` for each of four ABIs; four unchanged
  historical auxiliary inputs under `arm64-v8a`: CLI, PRoot, loader, loader32.
- `verification/`: consumer command records and logs.
- `receipt.json`: full output inventory, exact source/input/child identities,
  provider origins, measured ELF class/machine, actual JNI descriptor matching,
  and fresh host invocation linkage to the full Java child receipt and AAR.

Auxiliary binaries are explicitly **not newly rebuilt** by this lane. Their
existing provenance files and installer/source bindings are frozen; the additive current CLI static check is run. It deliberately versions the
historical frozen gate: every prior check is retained, while the exact current
ApplicationWorkAdmission.isOpen conjunct is required. The old gate is unchanged;
its stale exact predicate rejected run1, which remains preserved without receipt. The ARM32 loader is intentionally packaged in the ARM64
location; packaging location is not a claim about ELF architecture. No new
four-ABI support is claimed for those auxiliary execution paths.

Relocated consumer verification validates selected source/tool hashes in the
consumer copy and the recorded original root, then executes original child
verifiers against their original recorded paths. It never rewrites old receipts
or silently retargets their commands. Full application source/DEX/package
identity belongs to the separate APK verifier, not this component subset.

All source/provider hashes are checked again after copying. The complete output-file whitelist is enforced. Timeouts retain partial child
logs without writing a success receipt. Symlinks and special
entries in protected paths/evidence are rejected; extra native providers fail.
Unsigned receipts provide reproducible local evidence, not authenticated remote
attestation. External modification concurrent with verification is outside this
single-writer workflow; source and output roots must not be shared with writers.

## Explicit limits

`BUILD_ONLY_DEVICE_UNVALIDATED` is not release admission. Child Termux
`registrationPathVerified=false` remains unchanged. `compiledJavaAbiMatched`
means compiled Java descriptors match inspected Android tables;
`linuxOwnedJniVerified` means the fresh selected Java engine passed the retained
Linux JVM harness. Neither proves Android runtime RegisterNatives, JNI ownership
under Android, app startup, device lifecycle, or performance. Filesystem ELF
RELRO evidence checks header presence, not comprehensive relocation coverage.

`androidRegistrationVerified`, `deviceValidated`, `apkAdmitted`, and
`releaseCompatible` remain false. Exact APK provider/DEX binding, signing,
installation, device tests, and release eligibility remain independent gates.
