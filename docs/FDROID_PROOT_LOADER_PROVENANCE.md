# PRoot loader provenance and source-rebuild research

Investigation date: 2026-11-13. Status: **source and historical recipe identified;
rebuild and Android runtime equivalence not yet demonstrated by this investigation**.

This is an evidence record, not permission to replace the release loaders. No
native compiler, target binary, installer, or build command was run to produce
this document. The existing loader hashes, release APK, dependency set, and
signing configuration were not changed.

## 1. Result and scope

The ARM64/ARM32 loader source files in Droide's pinned OpenMinis archive are
byte-identical, as Git blobs, to the loader files at the Termux PRoot commit
selected by the historical `proot 5.1.107-70` package recipe. That package
directory has no additional patch files. The compared GNUmakefiles differ only
by the OpenMinis `native_offload` object in the **engine** object list; the loader
rules are identical.

Consequently, the old explanation in `build_proot.sh` that the fork necessarily
lacks additional Android loader patches is not established for these exact
inputs. The historical device-regression warning still matters, but its alleged
cause must not be promoted to a newly verified finding. Compiler/toolchain
generation, effective flags, target selection, ELF layout, and postprocessing
must be reproduced and compared before changing the stable loader pair.

This finding does not prove which build invocation originally produced the
vendored bytes. The repository labels those bytes as Termux 5.1.107-70; their
compiler comments are consistent with the historical recipe's NDK generation.
Rebuilding and comparing them remains the independent provenance test.

## 2. Immutable inputs

| Input | Identity |
| --- | --- |
| Termux packaging commit | `0ff4d49dad73ed7a62375504cf96326bb835e132` |
| `packages/proot/build.sh` Git blob | `339f1417f8b2d5f65fb22e36f90a623970906172` |
| Version and package revision | `5.1.107`, revision `70` |
| PRoot commit selected by that recipe | `4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f` |
| Termux source ZIP SHA-256, as pinned in recipe | `a72668607184f981e44181bfa47f86ef52d52bb237b4012ee94f0dc89cb39211` |
| Droide/OpenMinis source commit | `8cf13e997cdc9472997aae19df8050c073c9a86c` |
| Local `proot-openminis-8cf13e99-source.tar.gz` measured SHA-256 | `6c77863bbdddbaa2d8043a901fee61f0e40492bb3bebdc6cb31390ac71ffca47` |
| Historical NDK version | `r29` |
| `android-ndk-r29-linux.zip` SHA-256, as pinned in Termux setup | `4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf` |
| Historical default Android API for the Termux recipe | `24` |

Primary sources:

- [Termux PRoot package recipe](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/packages/proot/build.sh).
- [Termux PRoot package directory](https://github.com/termux/termux-packages/tree/0ff4d49dad73ed7a62375504cf96326bb835e132/packages/proot), containing only `build.sh` and `termux-chroot`.
- [Termux build properties](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/scripts/properties.sh), Git blob `339ecfac6f32311571547bf10d9e4c6c10706e05`.
- [NDK archive selection and checksum](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/scripts/setup-android-sdk.sh), Git blob `24dd7c7ea8ebd49f3d8fceb985a7eae6c577c774`.
- [Build-variable defaults](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/scripts/build/termux_step_setup_variables.sh), Git blob `e6fccbc37542c03eed93a88db293778f92bcbc20`.

The historical Termux source ZIP and NDK ZIP checksums above were read from
their pinned recipes, not newly verified by downloading those archives during
this investigation. The local OpenMinis archive hash was measured directly.

## 3. Source equality

Each entry below was hashed from the local archive using `git hash-object
--stdin` and compared with the GitHub blob identity returned for the immutable
Termux PRoot commit. Every listed file matched.

| Source path | Matching Git blob SHA-1 |
| --- | --- |
| `src/loader/loader.c` | `512c6bae628ac5791f7996936a8d9ad953086bac` |
| `src/loader/assembly.S` | `8e4b086d327efa898d3272e907955cceb8af1d99` |
| `src/loader/assembly-arm.h` | `6848d4dd1d024ca1a1ccc585ecf427ce8449667c` |
| `src/loader/assembly-arm64.h` | `4a79db3e8121f101d67d30f54fd4884fd9a80ff1` |
| `src/loader/script.h` | `6ae762132522ff8a1cf947b9a3614e8d59b1c574` |
| `src/loader/loader-info.awk` | `578c84374569624a9926daeba25464eb94fd0b7e` |
| `src/arch.h` | `06b7f1b037e292420d9df53657eac693d8965a83` |
| `src/compat.h` | `f69bc3f6342e1f5b6bf7f5b0576493a1f04287fa` |
| `src/attribute.h` | `f772954236da771aa2519bb0e7308d3e35cd83b5` |

The upstream `src/GNUmakefile` blob is
`96035576e5b89dbfd14b1185685bce7202e7e523`; the OpenMinis copy is
`568502363dc50e9eb3a5767d58753cb5d61e6f84`. Removing only the added
`extension/native_offload/native_offload.o` entry and its preceding line
continuation from the OpenMinis copy makes its complete content equal to the
upstream file.

For the inspected ARM64/ARM EABI conditional paths, `loader.c` includes
`<stdbool.h>`, defines `NO_LIBC_HEADER`, and then includes the project headers
listed above. `NO_LIBC_HEADER` excludes the `sys/ptrace.h` and `linux/audit.h`
includes from `arch.h`. The loader assembly file has no includes. This makes a
small isolated source-rebuild experiment feasible; a compiler-generated
dependency file is still required to confirm the actual header closure used by
the candidate compiler invocation.

Sources: [loader directory](https://github.com/termux/proot/tree/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f/src/loader),
[architecture header](https://github.com/termux/proot/blob/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f/src/arch.h),
[GNUmakefile](https://github.com/termux/proot/blob/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f/src/GNUmakefile).

## 4. Historical loader build semantics

The following is a static derivation of the pinned scripts, **not a captured
successful build log**.

| Component | Derived behavior |
| --- | --- |
| Compiler | `aarch64-linux-android-clang`, copied by Termux from the NDK's `aarch64-linux-android24-clang` wrapper |
| Link driver | PRoot's GNUmakefile sets `LD = $(CC)` |
| Common Termux C flags | `-fstack-protector-strong -Oz` for the default non-debug aarch64 build |
| GNUmakefile appended C flags | `-Wall -Wextra -O2`; the later optimization selection is `-O2` |
| Loader-only C flags | `-fPIC -ffreestanding`; no additional `LOADER_ARCH_CFLAGS` is defined for ARM64 or ARM EABI |
| Common preprocessor flags | Termux system-include paths, package `-DARG_MAX=131072`, and GNUmakefile `-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$(VPATH)` |
| Unbundled loader selection | Package exports `PROOT_UNBUNDLE_LOADER=$TERMUX_PREFIX/libexec/proot`; GNUmakefile also adds the corresponding C macro |
| Source object order | `loader/loader[...].o`, then `loader/assembly[...].o` |
| Native loader selector | Empty selector; architecture header selects ARM64 |
| Compatibility loader selector | `-m32` passed to the **same compiler** for architecture discovery, compilation, and linking |
| ARM64 text address | `0x2000000000` |
| ARM32 text address | `0x20000000` |
| Loader link options | `-static -nostdlib -Wl[build-id option],-Ttext=[address],--rosegment,-z,noexecstack` |
| Optional build-id option | `,--build-id=none`, if a probe of the host `ld --build-id=none --version` succeeds |
| Generated engine coupling | `readelf -s loader/loader` piped through `loader-info.awk` generates `loader-info.c` before loader stripping |

Important details:

- The loader link rule does **not** expand general `LDFLAGS`. It therefore must
  not silently inherit the current engine builder's `-pie`, talloc linkage,
  explicit page-size flags, or build-id flags.
- Native aarch64 Termux C flags do not contain the separate `TERMUX_ARCH=arm`
  package flags `-march=armv7-a -mfpu=neon -mfloat-abi=softfp -mthumb`. The
  compatibility loader is selected by `-m32` inside the aarch64 package build.
  Replacing this with a hand-authored ARM target/flag set is not yet proven
  equivalent. Record the exact NDK r29 driver expansion with `-###`, predefined
  architecture macros, and dependency outputs during the isolated proof.
- Preserve how flags enter GNU Make. Setting `CFLAGS` on the Make command line
  can suppress ordinary makefile `+=` additions; the historical Termux scripts
  export flags into the environment and invoke `make -C src`.
- `BUILD_ID_NONE` probes the host `ld`, not the cross-link driver. Record or
  explicitly fix that decision for reproducibility instead of relying on an
  unrecorded host installation.

Primary recipe sources:

- [NDK r29 setup](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/scripts/build/toolchain/termux_setup_toolchain_29.sh), Git blob `cd17364b794a00ef86679642e9bd3dc2120f69e0`.
- [Make step](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/scripts/build/termux_step_make.sh), Git blob `36f44a70f461d05f82e9878535f2294527ef3ab2`.
- [PRoot GNUmakefile](https://github.com/termux/proot/blob/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f/src/GNUmakefile).

### Packaging postprocessing

The historical recipe installs the unbundled loader pair into `libexec/proot`.
For the default bionic, non-debug build, the package massage step first runs
`llvm-strip --strip-unneeded --preserve-dates`, then `termux-elf-cleaner
--api-level 24` over qualifying `libexec` files.

The host cleaner download is version `3.0.1`, pinned by the historical start
script to SHA-256
`59645fb25b84d11f108436e83d9df5e874ba4eb76ab62948869a23a3ee692fa7`.
Its corresponding upstream source archive is
`https://github.com/termux/termux-elf-cleaner/archive/refs/tags/v3.0.1.tar.gz`,
pinned to SHA-256
`1c3c6fb33ad8d1fdfe035eee3a5419f54442b93b1a97c4151b31b82c5626a06a`.
Whether the cleaner changes these particular static loader binaries remains to
be measured; do not assume the step is either necessary or a no-op.

Sources: [massage order](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/scripts/build/termux_step_massage.sh),
[strip behavior](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/scripts/build/termux_step_strip_elf_symbols.sh),
[cleaner invocation](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/scripts/build/termux_step_elf_cleaner.sh),
[cleaner host download](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/scripts/build/termux_step_start_build.sh),
[cleaner source pin](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/packages/termux-elf-cleaner/build.sh).

## 5. Measured baseline binary facts

These facts were obtained by read-only SHA-256 and `readelf` inspection of the
two files under `app/src/main/jniLibs/arm64-v8a/`. Neither file was executed.

| Property | `libproot-loader.so` | `libproot-loader32.so` |
| --- | --- | --- |
| SHA-256 | `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04` | `25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34` |
| ELF type / machine | `ET_EXEC`, AArch64 | `ET_EXEC`, ARM, EABI5 soft-float |
| Entry point | `0x2000000000` | `0x20000000` |
| `.text` size in executable load segment | `0x468` | `0x5a0` |
| `PT_LOAD` alignment | `0x4000` (16 KiB) | `0x1000` (4 KiB) |
| GNU stack permissions | Read/write, not executable | Read/write, not executable |

Both `.comment` sections report Android Clang `21.0.0`, Android build
`13989888`, based on `r563880c`, with LLVM revision
`5e96669f06077099aa41290cdb4c5e6fa0f59349`, and LLD `21.0.0`.

The current `build_droide_proot.py` instead requires NDK `28.2.13676358`, targets
Android API `29`, and deliberately copies/preserves both loaders while
rebuilding the engine. It is not a loader source-rebuild proof. Its safety
behavior must not be removed merely because the loader source was identified.

## 6. Required validation before promotion

1. Build only isolated loader candidates from hash-verified source with the
   historical NDK r29 recipe. Record NDK `source.properties`, compiler and linker
   versions, complete invocations, `-m32` driver expansion, dependency files,
   architecture macros, host build-id probe, and all input hashes.
2. Save pre-strip, post-strip, and post-cleaner hashes and ELF reports. Compare
   full bytes and separately ELF headers, program headers, entry points,
   architecture attributes, loadable segments, `.text`, and non-loadable metadata.
   Explain every mismatch; a different full-file hash alone does not identify
   the cause or prove a functional regression.
3. If candidates match the stable hashes exactly, record that result without
   treating it as blanket proof for the rest of the native build. If they do not
   match, retain them only as investigation artifacts; do not change hash guards
   to make the comparison pass.
4. Verify the engine's generated loader-info/pokedata offset against the exact
   loader being paired with it. Do not pair a new loader with stale generated
   offsets or strip away the needed symbols before generating the metadata.
5. Run approved Android device tests before promoting a non-identical candidate:
   first guest shell execution, child exec chains, command exit status, terminal
   and agent workflows, restart/reopen, and supported 32-bit guest execution.
   The historical custom-ROM regression warrants an appropriate device/ROM
   matrix; compilation or a single stock-device pass is not equivalent evidence.
6. Preserve the existing loaders and complete functionality until those gates
   pass. Then integrate a source-only release path with reviewed source/license
   provenance and the actual F-Droid build/review results. This document does not
   claim F-Droid acceptance, a complete source-only APK, or device QA completion.

The appropriate next action is an isolated, auditable source-rebuild proof, not
removing the compatibility loader, weakening tests, or pruning Droide features.
