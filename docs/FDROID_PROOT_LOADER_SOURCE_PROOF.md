# Isolated PRoot loader source proof

This is a **proof-only experiment**, not a production loader replacement,
source-only APK, device-validation result, or F-Droid acceptance. The stable
loader pair, engine builder, application features, dependency graph, signing
configuration and release assets remain unchanged.

The immutable source and historical recipe investigation is recorded in
[FDROID_PROOT_LOADER_PROVENANCE.md](FDROID_PROOT_LOADER_PROVENANCE.md).
The independent Terminal View checkpoint is recorded in
[FDROID_SOURCE_PROOFS.md](FDROID_SOURCE_PROOFS.md). Passing either proof does
not resolve the original 33 scanner findings by itself.

## Purpose

Build both standalone loader targets from source twice, inspect their ELF
structure without executing them, and compare separately the unstripped and
stripped candidates. The strict success gate requires the final stripped
outputs to be byte-identical to **both** retained loader baselines. A successful
compilation with a differing baseline remains a failing compatibility proof;
the diagnostic must identify the stage rather than silently updating a hash.

| Loader | Retained SHA-256 | ABI contract |
| --- | --- | --- |
| `libproot-loader.so` | `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04` | ELF64, AArch64, ET_EXEC, entry `0x2000000000`, 16 KiB PT_LOAD alignment |
| `libproot-loader32.so` | `25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34` | ELF32, ARM EABI5 soft-float, ET_EXEC, entry `0x20000000`, 4 KiB PT_LOAD alignment |

Both require static linkage with no interpreter/dynamic program headers and a
non-executable GNU stack. The 32-bit loader is retained; it is not deleted or
silently converted to an AArch64 shared object to satisfy a generic checker.
The existing CLI ELF validator is not weakened to accommodate it.

## Inputs and recipe boundaries

- The existing OpenMinis source archive is pinned to SHA-256
  `6c77863bbdddbaa2d8043a901fee61f0e40492bb3bebdc6cb31390ac71ffca47`.
  The nine loader/header Git-blob identities and unchanged OpenMinis
  `src/GNUmakefile` are additionally checked against the provenance record.
- Only regular, canonical, bounded source members are staged into disposable
  scratch. The original makefile also inspects engine source paths during
  evaluation, so staging those source files does not mean compiling the engine.
  No baseline loader is copied into a candidate build directory.
- The separate experiment uses **Android NDK r29 (`29.0.14206865`)**, API 24,
  with `-m32` applied to the same aarch64 compiler for the compatibility target.
  The existing production/CLI NDK `28.2.13676358` is unchanged.
- The NDK is provisioned through the trusted SDK setup before sandbox entry.
  Exact revision checks, tool hashes, versions and unchanged-input checks record
  the installed toolchain identity. These checks do **not** independently
  authenticate the historical NDK ZIP. The historical ZIP hash in the
  provenance document is not relabeled as a freshly verified download.
- Historical C flags enter Make through an explicit clean environment, so the
  makefile's `-Wall -Wextra -O2` additions remain effective. General engine
  `LDFLAGS`, talloc, the seccomp patch and production prebuilt-loader copying
  are not part of this loader-only recipe.
- Compiler driver expansions, architecture macros and actual compiler header
  dependencies are evidence for the executed recipe. The complete historical
  Termux prefix/sysroot setup is not claimed to have been recreated merely
  because a nominal compiler version matches.
- GNU gawk is a trusted host recipe utility, provisioned separately from target
  execution. The makefile's loader-info calculation is checked against an
  independent bounded ELF symbol/stub inspection before symbols are stripped.

## Stripping and the unperformed cleaner stage

Record pre-strip and post-strip bytes independently. The historical recipe
used `llvm-strip --strip-unneeded --preserve-dates`, followed by
`termux-elf-cleaner --api-level 24`.

This first experiment does **not** download or execute an unverified cleaner.
If either stripped candidate differs from its baseline, cleaner/postprocessing
investigation remains incomplete and the proof fails. If both stripped
candidates exactly equal the complete retained baseline bytes, this establishes
that those final bytes can be reproduced by this source recipe without running
the cleaner; it does not claim the historical cleaner was executed or is a
no-op for arbitrary binaries.

The unstripped ARM64 loader must have unique defined `_start` and
`pokedata_workaround` symbols. Its entry must equal `_start`, and the
non-negative aligned offset must address the reviewed store/trap stub
`410000f900a0f0f7` in executable file-backed memory. This is checked before
stripping; a missing symbol is not treated as offset zero. No engine is paired
with a changed loader or stale generated offset by this experiment.

## Execution boundary

The fixed invocation is:

```sh
bash tools/run_fdroid_source_proofs.sh loaders --ndk /absolute/trusted/ndk/29.0.14206865
```

In CI, the already-reviewed `--sudo` namespace setup is used before dropping
to UID/GID 65534. The loader mode reuses the existing read-only NDK mount and
the identical CLI isolation controls: no external network, no capabilities,
no-new-privileges, masked Git metadata, read-only source/toolchain, an allowlisted
environment, private home/cache/temp, bounded process/memory/CPU/file/wall limits,
and a disposable 512 MiB tmpfs. It adds no mount destination or privilege.

The final sandbox probe must pass before the proof starts. Loader outputs are
never executed, uploaded, packaged, signed or installed. Only the bounded
diagnostic summary/receipt is printed before scratch disappears. Neither a
missing tool nor an unsupported namespace permits an unsandboxed fallback.

## Required evidence and outstanding work

The summary must distinguish successful source compilation, structural ELF
validity, independent-build reproducibility, baseline byte equality,
postprocessing completeness and unchanged inputs. A partial build or mismatch
is not a successful result, even if one ABI matches.

At initial implementation, actual NDK compiler execution was pending;
fixture tests were not a substitute. The subsequent actual result is recorded
below with its run, head and tested merge.

The local enforced-sandbox checkpoint passed **357 tests** in 2.756 seconds.
This comprises the earlier 297 source-proof contracts, three added runner
contracts, twenty-eight loader builder/input tests and twenty-nine ELF tests.
Both real stripped baseline files pass the data-only parser under their
unchanged SHA-256 pins. Mocked compiler/tool calls verify the orchestration and
failure paths; none of these tests execute the ARM outputs or demonstrate the
actual NDK compilation recorded separately below.

Even a byte-identical loader proof does not establish a complete source-only
PRoot/Android build. Engine coupling, source-only dependency integration,
resource linking, final APK checks, physical-device regressions, update/signing
review and F-Droid maintainer acceptance remain separate gates. Production
promotion is deliberately excluded from this proof.

## PR12 actual isolated-loader checkpoint: 2026-11-13

[Run 43374627049](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43374627049)
completed **8/8 jobs successfully**, including the separate registry-parity
job and all six source-contract/compiler matrix jobs after sandbox readiness.
The actual checkout was merge
`f41850f2cfb03b63af422408c94cb34bb00f0785`, incorporating PR12 head
`7d6a1a140d22579d1b7c1a453d0ef7151b589f87`. Independently retrieved logs
confirm **357 tests, OK** in each of readiness job `131718108321` and contract
job `131718212303` (2.121 and 2.497 seconds respectively). These are two
executions of the same suite, not 714 unique tests.

[Loader job 131718212293](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43374627049/job/131718212293)
actually compiled both loader ABIs from the pinned source in two fresh build
trees, using NDK `29.0.14206865` at API 24. Its complete **7,882-character
`SUMMARY.json`** was printed and independently read; this is actual compiler
evidence, not a fixture-only result. Both builds produced the same values:

| ABI and stage | Bytes | SHA-256 |
| --- | ---: | --- |
| ARM64, unstripped | 18,520 | `9851ac9137c3053926ccce7cfe90ec489a839543f261bb19f5269d6a9cbfdabb` |
| ARM64, stripped | 18,136 | `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04` |
| ARM32, unstripped | 6,504 | `4a1c0657c05577deee93ea1fde076828c5be0358f2477f006985141d677c9176` |
| ARM32, stripped | 6,244 | `25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34` |

Both stripped outputs exactly match their unchanged retained baselines in
**both builds**. The raw and post-strip reproducibility gates passed, `.text`
was unchanged by stripping, and the source/tool/header/output integrity
rereads passed. GNU gawk and the independent ELF symbol inspection agreed on
the ARM64 pokedata offset **1,120 bytes (`0x460`)**; the mapped eight-byte
store/trap stub was verified before stripping. The ELF64/ARM64 and
ELF32/ARM EABI5 structural contracts, static linkage, non-executable stacks and
respective 16 KiB/4 KiB load alignments passed without executing either output.

The final probe reported UID/GID 65534, empty capabilities,
no-new-privileges, read-only inputs, allowlisted environment, masked Git
metadata, a 512 MiB scratch limit and loopback-only networking with no external
route. The summary verdict is
`source-built-both-retained-loader-bytes-reproduced`.

`elfCleanerExecuted`, `historicalPostprocessingReplayed` and
`ndkArchiveAuthenticated` remain **false**. Exact final-byte equality closes
this loader reproduction experiment without running the cleaner; it does not
authenticate the historical NDK ZIP, recreate every historical Termux
environment detail, or prove that cleaner is generally a no-op. The expanded
receipt is subject to the fixed diagnostic budget; the complete compact
summary, not an untruncated full receipt, supplies the results above.

`packagedPayloadsChanged`, `targetOutputExecuted`,
`androidRuntimeCompatibilityVerified` and `fdroidAcceptanceVerified` are also
**false**. No PRoot engine or APK was built by this loader job, and no proof
output was promoted. The separate PR12 Android application workflow was still
in progress at the last recorded observation. Engine/source-only integration,
device QA and maintainer acceptance remain outstanding; the original **33
scanner findings remain unresolved**.
