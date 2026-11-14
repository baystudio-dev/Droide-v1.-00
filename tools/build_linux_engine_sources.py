#!/usr/bin/env python3
"""Compile PRoot, both Termux loaders and the CLI from pinned source; do not replace release inputs."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def build(root, ndk, output):
    require(not output.exists(), "Use a new output directory")
    source = root / "third_party/native-engines/source-build"
    manifest = json.loads((source / "manifest.json").read_text())
    inputs = dict(manifest["sourceSha256"])
    for name, expected in inputs.items():
        require(sha(root / name) == expected, "Native source changed: " + name)
    properties = dict(line.split("=", 1) for line in (ndk / "source.properties").read_text().splitlines() if "=" in line)
    revision = next(v.strip() for k, v in properties.items() if k.strip() == "Pkg.Revision")
    require(revision == manifest["ndk"], "Use the pinned Android NDK")
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    cc = tools / "aarch64-linux-android29-clang"
    require(cc.is_file(), "The Android ARM64 compiler is unavailable")
    legacy_path = root / "third_party/native-engines/proot/build_droide_proot.py"
    spec = importlib.util.spec_from_file_location("droide_proot_source_helpers", legacy_path)
    legacy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(legacy)
    patch = root / "third_party/native-engines/proot" / legacy.PATCH
    cli = root / "app/src/main/cpp/droide_cli_client.c"
    for path in (source / "manifest.json", legacy_path, patch, cli, root / "tools/build_linux_engine_sources.py"):
        inputs[str(path.relative_to(root))] = sha(path)
    require((source / "proot/src/loader/script.h").read_bytes() ==
            (source / "termux-proot-r70/src/loader/script.h").read_bytes(), "PRoot and Termux loader protocols differ")
    products = []
    with tempfile.TemporaryDirectory(prefix="droide-linux-source-") as temporary:
        for attempt in range(2):
            work = Path(temporary) / str(attempt)
            work.mkdir()
            proot, talloc = work / "proot", work / "talloc"
            shutil.copytree(source / "proot", proot)
            shutil.copytree(source / "talloc", talloc)
            environment = dict(os.environ, SOURCE_DATE_EPOCH="0", LC_ALL="C", TZ="UTC")
            log = []

            def run(command, cwd=work, stdin=None):
                result = subprocess.run(list(map(str, command)), cwd=cwd, env=environment, input=stdin,
                                        capture_output=True, timeout=180)
                log.append(result.stdout + result.stderr)
                require(result.returncode == 0, result.stdout.decode(errors="replace") + result.stderr.decode(errors="replace"))

            mapping = "-ffile-prefix-map=" + str(work) + "=."
            run(["patch", "-p1", "--fuzz=0", "--batch"], proot, patch.read_bytes())
            (talloc / "replace.h").write_text(legacy.SHIM)
            run([cc, "-c", talloc / "talloc.c", "-o", work / "talloc.o", "-I" + str(talloc), "-fPIC", "-O2",
                 "-std=gnu99", mapping, "-DHAVE_STDARG_H=1", "-DHAVE_VA_COPY=1", "-DHAVE_UNISTD_H=1", "-DHAVE_INTPTR_T=1"])
            run([tools / "llvm-ar", "rcs", work / "libtalloc.a", work / "talloc.o"])
            loader_source = source / "termux-proot-r70/src"
            loaders = {}
            for target, address, filename, make_name in (
                ("aarch64-linux-android29", "0x2000000000", "libproot-loader.so", "loader"),
                ("armv7a-linux-androideabi29", "0x20000000", "libproot-loader32.so", "loader-m32"),
            ):
                compiler = tools / (target + "-clang")
                objects = []
                for name in ("loader.c", "assembly.S"):
                    obj = work / (make_name + "-" + name + ".o")
                    run([compiler, "-O2", "-fPIC", "-ffreestanding", "-fno-stack-protector", "-D_GNU_SOURCE",
                         mapping, "-I" + str(loader_source), "-c", loader_source / "loader" / name, "-o", obj])
                    objects.append(obj)
                binary = proot / "src/loader" / make_name
                run([compiler, *objects, "-o", binary, "-static", "-nostdlib",
                     "-Wl,--build-id=none,-Ttext=" + address + ",--rosegment,-z,noexecstack,-z,max-page-size=16384,-z,common-page-size=16384"])
                run([tools / "llvm-strip", binary])
                loaders[filename] = binary.read_bytes()
            offset = legacy.loader_pokedata_offset(proot / "src/loader/loader")
            (proot / "src/loader/loader-info.c").write_text(
                "#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=" + str(offset) + ";\n")
            run(["make", "-j2", "-o", "loader/loader", "-o", "loader/loader-m32", "-o", "loader/loader-info.c", "GIT=false",
                 "CC=" + str(cc), "STRIP=" + str(tools / "llvm-strip"), "OBJCOPY=" + str(tools / "llvm-objcopy"),
                 "OBJDUMP=" + str(tools / "llvm-objdump"),
                 "CPPFLAGS=-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -DARG_MAX=131072 -I" + str(talloc),
                 "CFLAGS=-O2 -Wall -Wextra -fPIE " + mapping,
                 "LDFLAGS=-pie -L" + str(work) + " -ltalloc -Wl,-z,noexecstack,-z,max-page-size=16384,-z,common-page-size=16384,--build-id=sha1"], proot / "src")
            run([tools / "llvm-strip", proot / "src/proot"])
            cli_output = work / "libdroide_cli.so"
            run([tools / "clang", "--target=aarch64-linux-gnu", "-nostdlib", "-static", "-ffreestanding", "-fno-builtin",
                 "-fno-stack-protector", "-fuse-ld=lld", "-Os", "-Wall", "-Wextra", "-Werror", mapping,
                 "-Wl,--build-id=none,-z,max-page-size=16384,-z,common-page-size=16384,-e,_start", "-o", cli_output, cli])
            products.append({**loaders, "libdroide_proot.so": (proot / "src/proot").read_bytes(), "libdroide_cli.so": cli_output.read_bytes()})
    require(products[0] == products[1], "Independent native source builds differ")
    output.mkdir(parents=True)
    native = output / "jniLibs/arm64-v8a"
    native.mkdir(parents=True)
    for name, data in products[0].items():
        (native / name).write_bytes(data)
    receipt = {"schema": 1, "kind": "complete-linux-engine-source", "fullSourceRebuild": True, "binaryBaseRetained": False,
               "deviceValidated": False, "independentBuilds": 2, "ndkRevision": revision, "sourceInputs": inputs,
               "tools": {name: sha(tools / name) for name in ("clang-19", "ld.lld", "llvm-ar", "llvm-objcopy", "llvm-strip")},
               "arm64LoaderPokedataOffset": offset, "nativeLibraries": {name: hashlib.sha256(data).hexdigest() for name, data in products[0].items()}}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "ndk", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        recorded = json.loads((output / "receipt.json").read_text())
        with tempfile.TemporaryDirectory(prefix="droide-linux-recheck-") as temporary:
            receipt = build(args.root.resolve(), args.ndk.resolve(), Path(temporary) / "rebuilt")
            require(receipt == recorded, "Native receipt is stale; use a new output directory")
            for name, expected in receipt["nativeLibraries"].items():
                require(sha(output / "jniLibs/arm64-v8a" / name) == expected, "Native output differs: " + name)
    else:
        receipt = build(args.root.resolve(), args.ndk.resolve(), output)
    print(json.dumps({"nativeLibraries": receipt["nativeLibraries"], "independentBuilds": 2, "deviceValidated": False}, indent=2))
