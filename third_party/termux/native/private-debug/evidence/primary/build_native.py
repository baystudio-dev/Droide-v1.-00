#!/usr/bin/env python3
"""Standalone R40 native compilation; never runs Android output or edits source."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time

E = Path(__file__).resolve().parent
R = E.parents[3]
B = R / "build/R40/source/Droide-reconstructed-source"
O = R / "build/R40/native-primary"
SDK = R / "toolchain/android-sdk"
NDK = SDK / "ndk/28.2.13676358"
CM = SDK / "cmake/3.22.1"
LLVM = NDK / "toolchains/llvm/prebuilt/linux-x86_64/bin"
ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
ARCHIVE = "e767a89ace01569e1e0412b2bbd03de171194caa85c3989b770d4bfe9438d317"


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + "\n")


def tree_snapshot(root):
    result = {}
    for p in sorted(root.rglob("*")):
        if p.is_symlink():
            raise RuntimeError("Unexpected source symlink: " + str(p))
        if p.is_file():
            result[str(p.relative_to(root))] = {
                "sha256": digest(p), "sizeBytes": p.stat().st_size,
                "mode": oct(stat.S_IMODE(p.stat().st_mode)),
            }
    return result


def main():
    if sys.argv[1:] != ["--execute-authorized-build"]:
        raise SystemExit("Requires explicit coordinator toolchain-ready authorization.")
    if O.exists() and (list(O.iterdir()) != [O / "tmp"] or list((O / "tmp").iterdir())):
        raise RuntimeError("Nonempty build root exists; refusing silent reuse or overwrite.")
    props = (NDK / "source.properties").read_text()
    if not re.search(r"^Pkg.Revision\s*=\s*28\.2\.13676358\s*$", props, re.M):
        raise RuntimeError("NDK revision does not match pin")
    assert re.search(r"^Pkg.Revision\s*=\s*3\.22\.1\s*$", (CM / "source.properties").read_text(), re.M)
    source_receipt = json.loads((R / "build/R40/evidence/source-extraction-receipt.json").read_text())
    assert source_receipt["source_archive_sha256"] == ARCHIVE
    assert source_receipt["all_manifest_entries_verified"] is True
    static = json.loads((E / "static-source-inspection.json").read_text())
    assert static["contractStatusUnchanged"] == "UNBUILT"
    for p, v in static["sourceInputs"].items():
        assert digest(B / p) == v["sha256"], p
    before = tree_snapshot(B)
    write_json(E / "source-tree-before.json", before)
    O.mkdir(parents=True, exist_ok=True)
    (O / "tmp").mkdir(exist_ok=True)
    (E / "home").mkdir(exist_ok=True)
    env = {
        "PATH": f"{CM / 'bin'}:{LLVM}:/usr/bin:/bin",
        "HOME": str(E / "home"), "TMPDIR": str(O / "tmp"),
        "LC_ALL": "C", "LANG": "C", "TZ": "UTC",
    }
    mappings = [(B, "/droide/source"), (O, "/droide/native"),
                (NDK, "/droide/toolchain/ndk/28.2.13676358")]
    flags = " ".join(f"-{kind}={old}={new}" for old, new in mappings
                     for kind in ["ffile-prefix-map", "fdebug-prefix-map"])
    commands = []

    def run(args, label, cwd=O):
        args = list(map(str, args))
        log = E / (label + ".log")
        entry = {"argv": args, "cwd": str(cwd), "environment": env,
                 "log": str(log.relative_to(R)), "startedUnix": time.time()}
        with log.open("wb") as output:
            result = subprocess.run(args, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
        entry.update(returnCode=result.returncode, finishedUnix=time.time())
        commands.append(entry)
        write_json(E / "commands.json", commands)
        if result.returncode:
            write_json(E / "build-failure.json", {"failedCommand": entry,
                       "status": "BUILD_FAILED_NO_SOURCE_EDIT", "sourceArchiveSha256": ARCHIVE})
            print(log.read_text(), flush=True)
            raise RuntimeError(f"Command failed ({result.returncode}): {label}")
        return log.read_text()

    tools = {
        "cmake": CM / "bin/cmake", "ninja": CM / "bin/ninja",
        "clang": LLVM / "clang", "ld.lld": LLVM / "ld.lld",
        "llvm-readelf": LLVM / "llvm-readelf", "llvm-nm": LLVM / "llvm-nm",
    }
    tool_hashes = {}
    resolved_hashes = {}
    for name, p in tools.items():
        target = p.resolve(strict=True)
        if str(target) not in resolved_hashes:
            resolved_hashes[str(target)] = digest(target)
        tool_hashes[name] = {"path": str(p), "resolvedPath": str(target),
                            "sha256": resolved_hashes[str(target)], "sizeBytes": target.stat().st_size}
        version = run([p, "--version"], "tool-" + name.replace(".", "-"))
        tool_hashes[name]["versionOutput"] = version
    write_json(E / "tool-identities.json", tool_hashes)
    assert tool_hashes["cmake"]["versionOutput"].startswith("cmake version 3.22.1-g37088a8\n")
    cmake_inputs = {str(p.relative_to(NDK)): {"sha256": digest(p), "sizeBytes": p.stat().st_size}
                    for p in sorted((NDK / "build/cmake").rglob("*")) if p.is_file()}
    cmake_inputs["source.properties"] = {"sha256": digest(NDK / "source.properties")}
    write_json(E / "ndk-cmake-inputs.json", cmake_inputs)
    plan = {"sourceArchiveSha256": ARCHIVE, "sourceRoot": str(B), "buildRoot": str(O),
            "abis": ABIS, "androidPlatform": "android-29", "buildType": "Debug",
            "targets": ["termux", "droide_files"], "prefixMappings": [
                {"physical": str(old), "normalized": new} for old, new in mappings],
            "additionalCFlags": flags, "sourceUnchanged": True,
            "sourceTreeBeforeSha256": digest(E / "source-tree-before.json"),
            "sourceFileCount": len(before), "outputExecutionAuthorized": False}
    write_json(E / "build-plan.json", plan)
    for abi in ABIS:
        output = O / abi
        run([tools["cmake"], "-S", B / "app/src/main/cpp", "-B", output, "-G", "Ninja",
             f"-DCMAKE_MAKE_PROGRAM={tools['ninja']}",
             f"-DCMAKE_TOOLCHAIN_FILE={NDK / 'build/cmake/android.toolchain.cmake'}",
             f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-29", "-DCMAKE_BUILD_TYPE=Debug",
             "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON", f"-DCMAKE_C_FLAGS={flags}"], abi + "-configure")
        run([tools["cmake"], "--build", output, "--target", "termux", "droide_files",
             "--parallel", "2", "--verbose"], abi + "-build")
        run([tools["ninja"], "-C", output, "-t", "commands", "termux", "droide_files"], abi + "-actual-commands")
        print(abi + ": compiled both libraries; Android outputs not executed", flush=True)
    after = tree_snapshot(B)
    write_json(E / "source-tree-after.json", after)
    if before != after:
        raise RuntimeError("Source tree changed during native build; investigate before using artifacts")
    libraries = {f"{abi}/{name}": {"sha256": digest(O / abi / name),
                                  "sizeBytes": (O / abi / name).stat().st_size}
                 for abi in ABIS for name in ["libtermux.so", "libdroide_files.so"]}
    write_json(E / "compilation-result.json", {
        "status": "BUILD_ONLY_DEVICE_UNVALIDATED", "sourceArchiveSha256": ARCHIVE,
        "sourceTreeUnchanged": True, "sourceFileCount": len(after), "libraries": libraries,
        "executionEvidence": None, "releaseCompatible": False,
        "independentReproduction": "PENDING_SEPARATE_BUILD", "elfInspection": "PENDING",
    })
    print("All eight libraries compiled; static ELF inspection remains separate.", flush=True)


if __name__ == "__main__":
    main()
