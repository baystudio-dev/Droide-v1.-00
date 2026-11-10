#!/usr/bin/env python3
"""Offline rebuild of Droide's patched Android ARM64 PRoot executable.

The reviewed Termux loaders are verified and preserved. They are also embedded
in the rebuilt executable; the Android launcher supplies their APK-native paths.
Run with the pinned NDK and an empty build directory outside the source tree.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SOURCES = {
    "proot-openminis-8cf13e99-source.tar.gz": "6c77863bbdddbaa2d8043a901fee61f0e40492bb3bebdc6cb31390ac71ffca47",
    "talloc-2.4.2-source.tar.gz": "85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6",
}
LOADERS = {
    "libproot-loader.so": "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04",
    "libproot-loader32.so": "25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34",
}
PATCH = "patches/0001-preserve-voided-syscall-results-under-outer-seccomp.patch"
SHIM = r'''#ifndef REPLACE_H
#define REPLACE_H
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <stdbool.h>
#include <errno.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/auxv.h>
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2
#define HAVE_SYS_AUXV_H 1
#define HAVE_INTPTR_T 1
#define HAVE_VA_COPY 1
#define VALGRIND_MAKE_MEM_UNDEFINED(p,n) do { (void)(p); (void)(n); } while(0)
#define VALGRIND_MAKE_MEM_DEFINED(p,n) do { (void)(p); (void)(n); } while(0)
#define VALGRIND_MAKE_MEM_NOACCESS(p,n) do { (void)(p); (void)(n); } while(0)
#define ZERO_STRUCT(x) memset((char *)&(x),0,sizeof(x))
#define discard_const(ptr) ((void *)((uintptr_t)(ptr)))
#define MIN(a,b) ((a)<(b)?(a):(b))
#define MAX(a,b) ((a)>(b)?(a):(b))
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#endif
'''


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def loader_pokedata_offset(path):
    """Find the archived assembly.S store/trap stub in the stripped loader.

    The Termux loader is hash-pinned above. It has no symbol table; silently
    treating its missing symbols as zero would point the workaround at _start.
    Search only executable ELF load segments and require exactly one match.
    """
    data = path.read_bytes()
    entry = struct.unpack_from("<Q", data, 24)[0]
    headers = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    stub = bytes.fromhex("410000f900a0f0f7")  # str x1,[x2]; .word 0xf7f0a000
    matches = []
    for index in range(count):
        kind, flags, offset, address, _, length, _, _ = struct.unpack_from("<IIQQQQQQ", data, headers + index * size)
        if kind != 1 or not flags & 1:
            continue
        segment = data[offset:offset + length]
        position = segment.find(stub)
        while position >= 0:
            matches.append(address + position - entry)
            position = segment.find(stub, position + 1)
    if len(matches) != 1 or matches[0] < 0 or matches[0] % 4:
        raise RuntimeError("Reviewed ARM64 loader store/trap stub cannot be located")
    return matches[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--build-dir", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "app/src/main/jniLibs/arm64-v8a")
    args = parser.parse_args()
    build = args.build_dir.resolve()
    if build.exists() and any(build.iterdir()):
        parser.error("Build directory must be empty; existing files are never removed automatically")
    if build == ROOT or ROOT in build.parents:
        parser.error("Build outside the deliverable source tree")
    ndk = args.ndk.resolve()
    properties = dict(line.split("=", 1) for line in (ndk / "source.properties").read_text().splitlines() if "=" in line)
    revision = properties.get("Pkg.Revision ", properties.get("Pkg.Revision", "")).strip()
    if revision != "28.2.13676358":
        parser.error("Use pinned Android NDK 28.2.13676358")
    host = "linux-x86_64" if platform.system() == "Linux" else "darwin-x86_64"
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    cc = tools / "aarch64-linux-android29-clang"
    if not cc.is_file():
        parser.error("Pinned Android ARM64 compiler is unavailable")
    native = ROOT / "app/src/main/jniLibs/arm64-v8a"
    for name, expected in SOURCES.items():
        if sha(HERE / name) != expected:
            raise RuntimeError("Vendored source checksum mismatch: " + name)
    for name, expected in LOADERS.items():
        if sha(native / name) != expected:
            raise RuntimeError("Reviewed Termux loader checksum mismatch: " + name)
    build.mkdir(parents=True, exist_ok=True)
    for name in SOURCES:
        with tarfile.open(HERE / name) as archive:
            archive.extractall(build, filter="data")
    proot = build / "proot-8cf13e997cdc9472997aae19df8050c073c9a86c"
    talloc = build / "talloc-2.4.2"
    environment = dict(os.environ, SOURCE_DATE_EPOCH="0", LC_ALL="C")

    def run(command, cwd=build):
        subprocess.run([str(value) for value in command], cwd=cwd, env=environment, check=True)

    with (HERE / PATCH).open("rb") as patch:
        subprocess.run(["patch", "-p1", "--fuzz=0", "--batch"], cwd=proot, stdin=patch, env=environment, check=True)
    (talloc / "replace.h").write_text(SHIM)
    mapping = "-ffile-prefix-map=" + str(build) + "=."
    run([cc, "-c", talloc / "talloc.c", "-o", build / "talloc.o", "-I" + str(talloc),
         "-fPIC", "-O2", "-std=gnu99", mapping, "-DHAVE_STDARG_H=1", "-DHAVE_VA_COPY=1",
         "-DHAVE_UNISTD_H=1", "-DHAVE_INTPTR_T=1"])
    run([tools / "llvm-ar", "rcs", build / "libtalloc.a", build / "talloc.o"])
    run([tools / "llvm-ranlib", build / "libtalloc.a"])
    # Do not compile the fork's loaders: those are not the reviewed Android pair.
    # GNU make -o keeps these staged, hash-verified loader targets unchanged.
    shutil.copyfile(native / "libproot-loader.so", proot / "src/loader/loader")
    shutil.copyfile(native / "libproot-loader32.so", proot / "src/loader/loader-m32")
    stub_offset = loader_pokedata_offset(native / "libproot-loader.so")
    (proot / "src/loader/loader-info.c").write_text(
        "#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=" + str(stub_offset) + ";\n")
    cppflags = "-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -DARG_MAX=131072 -I" + str(talloc)
    cflags = "-O2 -Wall -Wextra -fPIE " + mapping
    ldflags = "-pie -L" + str(build) + " -ltalloc -Wl,-z,noexecstack,-z,max-page-size=16384,-z,common-page-size=16384,--build-id=sha1"
    run(["make", "-j2", "-o", "loader/loader", "-o", "loader/loader-m32", "-o", "loader/loader-info.c", "GIT=false",
         "CC=" + str(cc), "STRIP=" + str(tools / "llvm-strip"),
         "OBJCOPY=" + str(tools / "llvm-objcopy"), "OBJDUMP=" + str(tools / "llvm-objdump"),
         "CPPFLAGS=" + cppflags, "CFLAGS=" + cflags, "LDFLAGS=" + ldflags], proot / "src")
    binary = proot / "src/proot"
    run([tools / "llvm-strip", binary])
    header = binary.read_bytes()[:20]
    if header[:6] != b"\x7fELF\x02\x01" or header[18:20] != b"\xb7\x00":
        raise RuntimeError("Built runtime is not Android ARM64 ELF")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / "libdroide_proot.so"
    shutil.copyfile(binary, output)
    output.chmod(0o755)
    report = {
        "engineSha256": sha(output), "engineBytes": output.stat().st_size,
        "sourceArchives": SOURCES, "patchSha256": sha(HERE / PATCH),
        "builderSha256": sha(Path(__file__)), "ndkRevision": revision, "androidApi": 29,
        "loadersPreserved": {name: sha(native / name) for name in LOADERS},
        "arm64LoaderPokedataOffset": stub_offset,
        "patchedEventSha256": sha(proot / "src/tracee/event.c"),
    }
    (build / "build-manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
