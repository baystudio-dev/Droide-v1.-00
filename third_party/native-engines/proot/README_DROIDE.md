# Building Droide's PRoot engine

The shipped ARM64 engine is built from the preserved OpenMinis PRoot commit
`8cf13e997cdc9472997aae19df8050c073c9a86c` and talloc 2.4.2 archives, with
`patches/0001-preserve-voided-syscall-results-under-outer-seccomp.patch` applied.
The patch retains PRoot's emulated syscall results when an outer Android seccomp
filter traps the syscall avoider while `PROOT_NO_SECCOMP=1`. It preserves genuine
unsupported-syscall errors and does not remove the outer filter.

The event handling backport is based on
[Termux PRoot commit a179d3e8a4e045aaa1fb8cc3284f23509d96d353](https://github.com/termux/proot/blob/a179d3e8a4e045aaa1fb8cc3284f23509d96d353/src/tracee/event.c).
The original source archives and upstream `build_proot.sh` remain unchanged.
Use the Droide builder below for the shipped engine.

## Offline Android build

Prerequisites: Linux x86_64, Python 3.12 or later, GNU make, GNU patch, xxd, and
Android NDK `28.2.13676358`. All dependency source archives and reviewed loader
binaries are included in this source tree. No network download is performed.

From the source root, choose a new empty build directory outside the source tree:

```sh
python3 third_party/native-engines/proot/build_droide_proot.py \
  --ndk /path/to/android-sdk/ndk/28.2.13676358 \
  --build-dir /tmp/droide-proot-build \
  --output-dir /tmp/droide-proot-output
```

The builder verifies archived source and loader hashes, applies the patch without
fuzz, builds static talloc and the ARM64 Android API 29 PIE engine, and emits a
build manifest. ELF load segments use 16 KiB alignment. The two reviewed Termux
loaders remain unchanged and are embedded into the engine. Their executable
store/trap stub is found in the hash-pinned loader rather than relying on stripped
symbol names. Fork-built loaders must not replace this reviewed pair.

For the recorded release build, two independent build directories produced the
same 265648-byte engine with SHA-256
`6a7babd8480adea03afba45a3cabd89a1387040a0cdc205a90f37185cb4f882b`.
This describes the recorded build; a different toolchain can produce different
bytes. Intentional engine changes require updating the binary, the expected
hash in `PackagedLinuxEngine.kt`, and the generated legal inventory together.

## Native regression check

`tools/verify_proot_seccomp.py` compares original and patched Linux x86_64 PRoot
builds under a real additional kernel `SECCOMP_RET_TRAP` filter. It checks the raw
getcwd syscall, libc getcwd, fchdir, preserved error codes, interactive Bash
startup and the directory health command, including paths with spaces and
non-ASCII characters. The unfiltered original engine is the positive control.

```sh
python3 tools/verify_proot_seccomp.py \
  --original-proot /path/to/original/host/proot \
  --patched-proot /path/to/patched/host/proot \
  --talloc-library-dir /path/to/host/talloc/lib \
  --out /tmp/droide-proot-regressions
```

This check requires a Linux x86_64 host with gcc and ptrace access. Host
regressions and an ARM64 cross-build do not replace Android device testing.
After an application update, reopen Linux terminal sessions and retry interrupted
QEMU installation; the existing guest and project data do not need to be erased.
