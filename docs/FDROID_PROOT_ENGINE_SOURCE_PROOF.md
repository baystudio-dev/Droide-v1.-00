# PRoot engine proof with source-built ARM64 and ARM32 loaders

This is an isolated, proof-only build. It does not alter the stable engine,
packaged loader pair, application features, dependencies, signing, or release.
The existing production builder remains unchanged. The original 33 F-Droid
scanner findings are not resolved merely by adding or passing this experiment.

## Purpose and preserved implementation

The previous [standalone loader checkpoint](FDROID_PROOT_LOADER_SOURCE_PROOF.md)
reproduced both retained loaders exactly from source in two independent builds.
This next experiment supplies those freshly source-built outputs to the complete
patched PRoot build, rather than copying retained binaries into its compiler
inputs. Both engine builds must match the original complete engine bytes.

The engine still uses the existing OpenMinis source, Droide seccomp patch,
talloc 2.4.2 source and replacement-header shim, native-offload extension, both
embedded loaders, API 29, original compilation/link flags and 16 KiB load
alignment. There is no unbundled-loader switch, substitute engine, disabled
extension, feature removal, or dependency version change.

| Input | Fixed identity |
| --- | --- |
| PRoot archive | `proot-openminis-8cf13e99-source.tar.gz`, SHA-256 `6c77863bbdddbaa2d8043a901fee61f0e40492bb3bebdc6cb31390ac71ffca47` |
| talloc archive | `talloc-2.4.2-source.tar.gz`, SHA-256 `85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6` |
| Existing production builder | SHA-256 `a61f1068a0b5e36a945d92136a60aa9cd73cad6c22fbcb8ee56a3bda309fe12f` |
| Existing seccomp patch | SHA-256 `6a823fe58eee9110a69f411be3fd62ac657b299878a878cf51fd9b5df4d819f6` |
| Original `src/tracee/event.c` | SHA-256 `5b6c52b10124c2652f92951b0fbbf98b7067dbfe3034a7079918ac329486653b` |
| Patched `src/tracee/event.c` | SHA-256 `5b6748648b751ab084c4d4a566cd327a455ea5231fae1eaab2f46b962bd3ca9b` |
| Retained complete engine | 265,648 bytes, SHA-256 `6a7babd8480adea03afba45a3cabd89a1387040a0cdc205a90f37185cb4f882b` |
| Engine compiler | Android NDK `28.2.13676358`, AArch64 API 29 |
| Loader compiler | Android NDK `29.0.14206865`, API 24; both ARM64 and ARM32 |

The pinned production builder is read as data to recover its one literal
`SHIM`; it is not imported or executed. The source archives have bounded member
counts, sizes and canonical paths; links, special files, duplicates and sparse
members are rejected. Only the talloc C/header pair and the unchanged literal
shim are staged for that library. The seccomp patch must apply with zero fuzz,
produce the fixed post-patch hash, and leave every unrelated source unchanged.

## Source-loader coupling and distinct gates

`tools/fdroid_proot_engine_source_build.py` invokes the standalone loader proof
once in a fresh child output directory. That proof independently builds both
ABIs twice. The first engine receives the first source-built pair and the
second engine receives the second pair. Retained native binaries are comparison
data only; they are never staged as engine compiler inputs.

The ARM64 pokedata workaround offset comes from the unstripped source loader,
with independent symbol/stub inspection and agreement with the pinned GNU awk
recipe. A missing symbol cannot silently become offset zero. The generated
`loader-info.c` retains the production format.

The original engine Makefile still copies and strips each loader with the
engine NDK before wrapping it into an object. Those intermediate `.exe` files
are explicit make goals so they can be inspected without changing a compile
or link rule. Their bytes must remain exactly equal to the corresponding
source-built loader. Both wrapper object hashes are recorded.

The separate `tools/fdroid_proot_engine_elf.py` inspects the engine as data. It
does not weaken the standalone static-loader parser or execute ARM code. Its
engine contract covers ELF64/AArch64 ET_DYN, Android's `/system/bin/linker64`,
the existing dynamic-library dependencies, bounded headers/sections/tables,
non-executable stack, no writable-executable load segment and 16 KiB load
alignment. For unstripped builds it additionally verifies the unique expected
offset constant and the symbol-backed start/end/size spans of both embedded
loaders, including the expected bytes. Relocations may not overwrite the
protected constant or embedded-loader spans.

The report separately records:

1. Success of the nested source-loader proof and its summary identity.
2. Successful complete engine compilation, with source/header/tool identities.
3. Engine/loaders/offset coupling before stripping.
4. Raw and stripped engine hashes and two-build reproducibility.
5. Exact full stripped-byte equality to the original engine in **both** builds.
6. Unchanged executable `.text` during stripping and final integrity rereads.

A partial build, second-build mismatch, changed input, missing coupling proof,
or failure to write the required receipt is a failure, not a successful proof.
The compact `SUMMARY.json` is bounded to 12 KiB; the expanded receipt may be
truncated by the runner's fixed cumulative 16 KiB diagnostic display budget.

## Enforced execution boundary

Provision the two trusted SDK-manager NDK installations first. Then run:

```sh
bash tools/run_fdroid_source_proofs.sh proot \
  --ndk /absolute/trusted/ndk/28.2.13676358 \
  --loader-ndk /absolute/trusted/ndk/29.0.14206865
```

The CI-only `--sudo` setup remains the reviewed non-root mapping handshake.
The runner accepts only the fixed `proot` target and the two separate,
non-overlapping toolchain directories. The sole additional mount destination,
`/toolchain/loader-ndk`, is read-only and explicitly allowlisted. It does not
expose a host home, inherited credentials, or a writable toolchain. Other
proof modes cannot accept the new loader-NDK option.

The engine helper's direct API accepts only canonical absolute ASCII recipe
paths without whitespace or shell/make metacharacters. Operator-supplied
external paths may still contain spaces when using the launcher: the read-only
mounts remap them to the fixed safe in-sandbox paths before compilation.

The original limits remain unchanged: no external network; UID/GID 65534;
empty capabilities; no-new-privileges; read-only source, tools and root;
masked Git metadata; clean allowlisted environment; private home/cache/temp;
512 MiB disposable scratch; 6 GiB address-space limit; 120 CPU seconds per
process; 128 processes; 256 file descriptors; 64 MiB per file; 300 seconds
wall time. A failed setup/probe stops work without a weaker fallback. The
Makefile feature probes compile/link and check file existence; they do not
execute the generated ARM programs.

All target outputs disappear with scratch. No APK, native replacement, signing
key, package publication, or physical-device operation belongs in this job.
Installed tool hashes and exact `source.properties` revisions are recorded;
this does not independently authenticate the complete historical NDK ZIPs.

## Validation status and remaining integration

The local enforced-sandbox checkpoint passed **419 tests in 3.759 seconds**:
the previous 357 contracts, six added runner/namespace contracts, 31 engine
builder/input tests and 25 dynamic-ELF tests. A separate real repository-policy
check and its 15 mutation tests passed. The final sandbox probe passed before
each run. Native compilation in these orchestration tests is mocked.

Independent review led to two additional regression guards: a read-only
section label cannot conceal a writable LOAD for the offset constant, and
the diagnostic summary must still fit its fixed 12 KiB budget after a complete
two-build result plus a maximum-length failure message. Repeated symbol-name
and scope text is kept in the detailed receipt rather than duplicated in the
compact summary; byte/hash/span/offset/verdict evidence is retained there.

At implementation, actual engine compiler execution is pending. Local
fixture/mutation tests and successful standalone loader CI cannot be presented
as this complete engine build's result. Actual CI evidence must be recorded
with the head, tested merge, run and job, and the full compact summary before
claiming byte reproduction.

An enforced data-only inspection of the existing inputs verified all pins,
143 staged PRoot source files, the talloc source/header pair and a 69-object
engine inventory including native offload and the three loader-related
objects. The retained engine parser reports the original API 29 / NDK r28c
identity, only `libdl.so` and `libc.so`, and relocation counts of 651 relative,
four global-data and 125 jump-slot entries. Its stripped baseline correctly
does **not** claim the pre-strip symbol-backed coupling gate. These are input
checks, not a newly compiled engine or an executed Android test.

Even a successful engine proof remains separate from a source-only Android
build. That production path must stop requiring retained baseline binaries,
retain the reviewed source and output manifests, integrate all other source
dependencies, and pass F-Droid lint/scanner/build and physical-device
regressions. Signing/update-channel review and F-Droid maintainer acceptance
are additional gates. Project CI is not F-Droid acceptance.
