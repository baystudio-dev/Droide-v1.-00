# Baseline-free native compilation, with separate comparison proofs

This change separates source compilation from the retained-binary comparison
used by the earlier proof experiments. The goal is to demonstrate that all four
native APK inputs can be rebuilt when their retained copies are inaccessible.
It does **not** yet promote a generated file into an APK, remove a production
input, change a feature/dependency, change Gradle packaging, or claim F-Droid
acceptance. Existing retained-byte proof modes remain available separately.

## Fixed outputs and unchanged recipes

| APK input | Bytes | Required SHA-256 |
| --- | ---: | --- |
| `libdroide_cli.so` | 4,528 | `913b313398b30115416acd20959b1c449eab0563c15c83230862b03e44e7cfee` |
| `libdroide_proot.so` | 265,648 | `6a7babd8480adea03afba45a3cabd89a1387040a0cdc205a90f37185cb4f882b` |
| `libproot-loader.so` | 18,136 | `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04` |
| `libproot-loader32.so` | 6,244 | `25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34` |

The existing C source, source archives, Droide seccomp-result patch, talloc
shim, PRoot native-offload source, compiler flags, NDK revisions, and ELF
contracts are unchanged. CLI and engine compilation use NDK `28.2.13676358`;
both loaders use NDK `29.0.14206865`. Textual size/digest constants are output
requirements, not binary inputs and not command-line overrides.

Each core builds twice in fresh directories, validates generated ELF data
without loading it, requires both outputs to match, and requires the frozen
size/digest. Source, builder, tool, header, staged-source and output rereads
remain fail-closed. The engine builds both loader pairs once and embeds the
corresponding pair in each engine; it preserves the exact engine-strip wrapper
certificates and the source-derived workaround offset. No baseline bytes are
accepted as a core argument, read from the native tree, copied as a fallback,
or used to repair a mismatching result.

## API and evidence separation

`build_sources(...)` is the source-only API of the CLI, loader and engine
helpers. Their `build_proof(...)` wrappers independently read the retained
comparison inputs and compare every generated output from both builds.
Missing comparison inputs are not silently skipped in proof mode.

Source-only summaries record empty `retainedArtifactInputs` and false
`baselineComparisonPerformed`. Their pinned-identity success does not claim
that the wrapper performed an actual retained-byte comparison. Proof receipts
record that comparison separately, including all four loader outputs in the
engine wrapper. The ordinary command-line entry points still select proof
mode; the new fixed native orchestrator calls only source-only APIs.

CLI completion uses `source-build-manifest.json`; loader/engine completion
uses `source-build-summary.json`. Loader/engine detailed receipts are explicitly
pending evidence bound to the final summary hash, not independent completion
markers. Both pending files are written, flushed, synced and closed before
publishing the final summary. Final publication uses no-overwrite hard links;
no fallible cleanup follows success. Pending diagnostic links are retained.
All outputs remain disposable scratch data, not admitted release artifacts.

The engine proof's compact summary references four duplicated provenance maps
in its exact, hash-checked source-only summary. The complete maps remain in
that source-only summary and the detailed scratch receipt; CI's bounded final
summary log is not an export of the complete scratch evidence bundle.

## Real absent-baseline CI lane

The additional `native` proof mode uses the existing bounded, offline,
read-only, capability-free sandbox:

```bash
bash tools/run_fdroid_source_proofs.sh native \
  --ndk /absolute/trusted/ndk/28.2.13676358 \
  --loader-ndk /absolute/trusted/ndk/29.0.14206865
```

Trusted provisioning happens before entering the sandbox. In CI the existing
reviewed `--sudo` namespace-setup path is used; its final target remains UID
65534 with no capabilities. No host policy, global permissions, credentials,
network access, writable source bind, target-output execution or artifact
export is added.

Only native mode overlays `app/src/main/jniLibs/arm64-v8a` with a fresh empty
4,096-byte tmpfs explicitly remounted read-only after the source mount. The
namespace validator requires the exact native target and probe wrapper, both
read-only non-overlapping NDKs, and the fixed mask size/order/read-only state.
All other modes reject that mask. The checkout's original files are untouched.

The orchestrator requires the native directory to be empty before compilation,
between cores and before completion. It checks every one of the four outputs
in **both** builds, revalidates their ELF structure and fixed identity, and
rereads source inputs, core completion evidence and all outputs. An executable,
symlinked, hard-linked, missing, mismatching or later-mutated result fails.
The single final `SUMMARY.json` is at most 12 KiB and is published last. No
binary survives the sandbox or enters a release through this lane.

## Validation and remaining work

The enforced offline, read-only sandbox passed all **580 source-proof
regression tests**. A separate check against this real checkout with the
native directory masked empty validated all 12 source/text inputs and
confirmed that the retained-byte proof readers fail without their baseline
files. That check did not invoke a compiler. Independent static reviews of
the core/proof separation, final publication and fixed mask integration found
no blocking issue.

The actual new native compilation CI result is still pending. Synthetic/mock
unit tests and a real source-input check do not prove that a real compiler
builds with the native inputs masked. Actual evidence must record the
head/tree, tested merge, run/job, successful final sandbox probe and complete
summary for all eight generated outputs.

Before this can resolve the four native scanner findings, a separately
reviewed source-only candidate preparation/build integration must exclude the
retained files before scanning, run these cores, admit only the exact four
verified outputs, and wire their generation before both release-input checks
and JNI merging. No source-copy fallback or skipped release verification is
permitted. Optimized-APK, strict dependency, application and physical-device
regressions, actual F-Droid lint/scanner/build and maintainer review are still
required. Other derivative dependencies and the R8 repository issue are
separate outstanding work, not solved by native byte identity.

See [earlier PRoot proof](FDROID_PROOT_ENGINE_SOURCE_PROOF.md) and the
[actual R8 endpoint failure](FDROID_R8_REGISTRY_PARITY.md).
