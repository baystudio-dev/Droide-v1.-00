#!/usr/bin/env python3
"""Proof-only rebuild of the two retained PRoot loaders; never executes them.

Run through run_fdroid_source_proofs.sh with a separately provisioned, trusted
NDK r29. Recorded installation identities are not authentication of the vendor
ZIP. This experiment does not run the historical Termux ELF cleaner, build the
PRoot engine, replace APK payloads, or establish Android/F-Droid acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shlex
import stat
import subprocess
import tarfile

from fdroid_proot_loader_elf import LoaderElfError, inspect_loader


ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = "third_party/native-engines/proot/proot-openminis-8cf13e99-source.tar.gz"
ARCHIVE_SHA256 = "6c77863bbdddbaa2d8043a901fee61f0e40492bb3bebdc6cb31390ac71ffca47"
ARCHIVE_ROOT = "proot-8cf13e997cdc9472997aae19df8050c073c9a86c"
SOURCE_BLOBS = {
    "src/loader/loader.c": "512c6bae628ac5791f7996936a8d9ad953086bac",
    "src/loader/assembly.S": "8e4b086d327efa898d3272e907955cceb8af1d99",
    "src/loader/assembly-arm.h": "6848d4dd1d024ca1a1ccc585ecf427ce8449667c",
    "src/loader/assembly-arm64.h": "4a79db3e8121f101d67d30f54fd4884fd9a80ff1",
    "src/loader/script.h": "6ae762132522ff8a1cf947b9a3614e8d59b1c574",
    "src/loader/loader-info.awk": "578c84374569624a9926daeba25464eb94fd0b7e",
    "src/arch.h": "06b7f1b037e292420d9df53657eac693d8965a83",
    "src/compat.h": "f69bc3f6342e1f5b6bf7f5b0576493a1f04287fa",
    "src/attribute.h": "f772954236da771aa2519bb0e7308d3e35cd83b5",
    "src/GNUmakefile": "568502363dc50e9eb3a5767d58753cb5d61e6f84",
}
LOADERS = {
    "arm64": ("libproot-loader.so", "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04"),
    "arm32": ("libproot-loader32.so", "25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34"),
}
NATIVE = "app/src/main/jniLibs/arm64-v8a"
NDK_REVISION = "29.0.14206865"
HISTORICAL_NDK_ZIP_SHA256 = "4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf"
TERMUX_PREFIX = "/data/data/com.termux/files/usr"
COMMON_CFLAGS = "-fstack-protector-strong -Oz"
COMMON_CPPFLAGS = ("-isystem" + TERMUX_PREFIX + "/include/c++/v1 -isystem" +
                   TERMUX_PREFIX + "/include -DARG_MAX=131072")
MAX_ARCHIVE = 4 * 1024 * 1024
MAX_FILE = 1024 * 1024
MAX_EXPANDED = 16 * 1024 * 1024
MAX_MEMBERS = 4096
HOST_TOOLS = {name: Path("/usr/bin") / name for name in ("make", "gawk", "readelf", "ld")}


class SourceBuildError(RuntimeError):
    """A pinned input, build, or exact-output proof failed."""


def require(condition, message):
    if not condition:
        raise SourceBuildError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def git_blob(raw):
    return hashlib.sha1(b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest()


def read(path, bound=MAX_FILE):
    path = Path(path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and not path.is_symlink() and info.st_size <= bound,
            "Expected bounded regular non-symlink input: " + str(path))
    with path.open("rb") as stream:
        raw = stream.read(bound + 1)
    require(len(raw) == info.st_size and len(raw) <= bound, "Input size changed: " + str(path))
    return raw


def archive_sources(raw):
    """Read bounded data, not tar extraction directives; stage only src/ files.

    The unchanged makefile scans its full engine object list at parse time even
    for loader-only goals. Thus all regular src/ inputs, not engine outputs or
    archive scripts outside src/, are copied into each disposable build.
    """
    require(len(raw) <= MAX_ARCHIVE, "Source archive exceeds compressed bound")
    sources, seen, expanded = {}, set(), 0
    try:
        with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as archive:
            for index, member in enumerate(archive):
                require(index < MAX_MEMBERS, "Source archive member bound exceeded")
                name = member.name.rstrip("/") if member.isdir() else member.name
                path = PurePosixPath(name)
                require(name and name == path.as_posix() and not path.is_absolute() and
                        ".." not in path.parts and "\\" not in name and
                        all(ord(c) >= 32 and ord(c) != 127 for c in name) and
                        path.parts[0] == ARCHIVE_ROOT, "Noncanonical source archive path")
                require(name not in seen, "Duplicate source archive member")
                seen.add(name)
                require(member.isdir() or member.isfile(), "Archive links or special files are forbidden")
                require(not member.issparse(), "Sparse archive entries are forbidden")
                if member.isdir():
                    require(member.size == 0, "Directory contains unexpected archive data")
                    continue
                require(0 <= member.size <= MAX_FILE, "Source archive file bound exceeded")
                expanded += member.size
                require(expanded <= MAX_EXPANDED, "Source archive expansion bound exceeded")
                if len(path.parts) < 3 or path.parts[1] != "src":
                    continue
                stream = archive.extractfile(member)
                require(stream is not None, "Missing source archive payload")
                with stream:
                    data = stream.read(MAX_FILE + 1)
                require(len(data) == member.size, "Truncated source archive payload")
                relative = PurePosixPath(*path.parts[1:]).as_posix()
                sources[relative] = data
    except (tarfile.TarError, EOFError, OSError) as error:
        raise SourceBuildError("Cannot read bounded source archive: " + str(error)) from error
    require(sources, "Source archive has no src/ files")
    return sources


def read_inputs(root):
    archive = read(root / ARCHIVE, MAX_ARCHIVE)
    require(sha(archive) == ARCHIVE_SHA256, "PRoot source archive checksum mismatch")
    sources = archive_sources(archive)
    for name, digest in SOURCE_BLOBS.items():
        require(name in sources and git_blob(sources[name]) == digest,
                "Reviewed source Git-blob mismatch: " + name)
    baselines = {}
    inputs = {ARCHIVE: archive}
    for name in ("tools/fdroid_proot_loader_source_build.py", "tools/fdroid_proot_loader_elf.py"):
        inputs[name] = read(root / name)
    for abi, (name, digest) in LOADERS.items():
        relative = NATIVE + "/" + name
        raw = read(root / relative)
        require(sha(raw) == digest, "Retained loader baseline checksum mismatch: " + name)
        inspect_loader(raw, abi)
        baselines[abi] = raw
        inputs[relative] = raw
    return sources, baselines, inputs


def tool_identity(path, inside=None):
    resolved = path.resolve(strict=True)
    require(inside is None or inside in resolved.parents, "Tool resolves outside the NDK: " + str(path))
    info = resolved.stat()
    require(stat.S_ISREG(info.st_mode) and os.access(resolved, os.X_OK) and
            0 < info.st_size <= 512 * 1024 * 1024, "Missing/bounded executable tool: " + str(path))
    digest = hashlib.sha256()
    with resolved.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    require(resolved.stat().st_size == info.st_size, "Tool size changed during identity read")
    return {"path": str(path), "resolvedPath": str(resolved), "bytes": info.st_size,
            "sha256": digest.hexdigest()}


def ndk_tools(ndk_path, root):
    require(platform.system() == "Linux" and platform.machine().lower() in ("amd64", "x86_64"),
            "Loader proof requires a Linux x86_64 host")
    ndk = ndk_path.resolve(strict=True)
    require(ndk != root and root not in ndk.parents, "Use a trusted NDK outside the source tree")
    properties = read(ndk / "source.properties", 65536)
    revisions = re.findall(rb"^\s*Pkg\.Revision\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    require(revisions == [NDK_REVISION.encode()], "Expected exact pinned Android NDK " + NDK_REVISION)
    directory = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    tools = {"cc": directory / "aarch64-linux-android24-clang", "clang": directory / "clang",
             "linker": directory / "ld.lld", "strip": directory / "llvm-strip", **HOST_TOOLS}
    identities = {name: tool_identity(path, ndk if name not in HOST_TOOLS else None)
                  for name, path in tools.items()}
    return ndk, properties, tools, identities


def output_path(output, root, ndk):
    require(output.is_absolute() and not output.exists() and not output.is_symlink(),
            "Output must be an explicit new absolute directory")
    resolved = output.resolve()
    require(output == resolved, "Output path must be canonical and contain no symlink ancestors")
    require(resolved.parent.is_dir(), "Output parent must already exist")
    for protected in (root, ndk):
        require(resolved != protected and protected not in resolved.parents and resolved not in protected.parents,
                "Output overlaps protected source/tool input")
    return resolved


def build_environment(tools, temporary):
    return {"PATH": str(tools["cc"].parent) + ":/usr/bin:/bin", "HOME": str(temporary),
            "TMPDIR": str(temporary), "XDG_CACHE_HOME": str(temporary), "LANG": "C", "LC_ALL": "C",
            "TZ": "UTC", "SOURCE_DATE_EPOCH": "0", "CC": str(tools["cc"]),
            # Environment values allow ordinary GNUmakefile += appends. Do not
            # move these into command-line make assignments (which suppress +=).
            "CFLAGS": COMMON_CFLAGS, "CPPFLAGS": COMMON_CPPFLAGS,
            "PROOT_UNBUNDLE_LOADER": TERMUX_PREFIX + "/libexec/proot"}


def run_tool(command, cwd, environment, log, *, input_bytes=None, timeout=60):
    try:
        with log.open("xb") as stream:
            result = subprocess.run([str(item) for item in command], cwd=cwd, env=environment,
                                    input=input_bytes, stdin=subprocess.DEVNULL if input_bytes is None else None,
                                    stdout=stream, stderr=subprocess.STDOUT, timeout=timeout, check=False)
        raw = read(log)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise SourceBuildError("Bounded tool invocation failed: " + str(error)) from error
    require(result.returncode == 0, "Tool exited " + str(result.returncode) + ": " +
            str(command[0]) + "; " + raw[-4000:].decode("utf-8", "replace"))
    return raw


def make_command(tools):
    return [str(tools["make"]), "--no-print-directory", "-j2", "V=1", "GIT=false",
            "BUILD_ID_NONE=,--build-id=none", "loader/loader", "loader/loader-m32"]


def architecture_macros(raw, abi):
    text = raw.decode("ascii")
    expected = {"ARCH_ARM64": "1", "HAS_LOADER_32BIT": "true", "HAS_POKEDATA_WORKAROUND": "true",
                "LOADER_ADDRESS": "0x2000000000"} if abi == "arm64" else {
                    "ARCH_ARM_EABI": "1", "LOADER_ADDRESS": "0x20000000"}
    fields = {}
    for line in text.splitlines():
        match = re.fullmatch(r"#define ([A-Za-z_][A-Za-z_0-9]*) (.*)", line)
        if match and match[1] in set(expected) | {"ARCH_ARM64", "ARCH_ARM_EABI", "LOADER_ARCH_CFLAGS"}:
            require(match[1] not in fields, "Duplicate architecture macro")
            fields[match[1]] = match[2]
    require(fields == expected, "Unexpected compiler architecture/header contract: " + abi)
    return fields


def dependency_closure(raw, source_dir, ndk):
    require(len(raw) <= 65536, "Compiler dependency file exceeds bound")
    text = raw.decode("utf-8").replace("\\\n", " ")
    require(text.count(":") == 1, "Unexpected compiler dependency target syntax")
    target, _, names = text.partition(":")
    require(target.strip() and names.strip(), "Empty compiler dependency target/inputs")
    items = shlex.split(names)
    require(1 <= len(items) <= 128, "Compiler dependency count outside bound")
    result = {}
    for name in items:
        path = Path(name)
        if not path.is_absolute():
            path = source_dir / path
        resolved = path.resolve(strict=True)
        if source_dir in resolved.parents:
            identity = "source/" + resolved.relative_to(source_dir).as_posix()
        elif ndk in resolved.parents:
            identity = "ndk/" + resolved.relative_to(ndk).as_posix()
        else:
            raise SourceBuildError("Compiler read undeclared header outside source/NDK: " + name)
        require(identity not in result, "Duplicate compiler dependency")
        result[identity] = {"sha256": sha(read(resolved)), "path": str(resolved)}
    return result


def parse_pokedata(raw):
    match = re.fullmatch(rb"#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=([0-9]+);\n", raw)
    require(match is not None, "Unexpected GNU awk loader-info output")
    value = int(match[1])
    require(value > 0 and value % 4 == 0 and value <= MAX_FILE, "Invalid generated pokedata offset")
    return value


def write_new(path, raw):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(raw)
    path.chmod(0o600)


def save_json(path, value):
    write_new(path, (json.dumps(value, indent=2) + "\n").encode("utf-8"))


def save_summary(path, value):
    raw = (json.dumps(value, separators=(",", ":")) + "\n").encode("utf-8")
    require(len(raw) <= 12 * 1024, "Critical loader summary exceeds diagnostic bound")
    write_new(path, raw)


def compact_elf(report):
    return {key: report[key] for key in ("class", "type", "machine", "entryPoint", "flags", "static",
            "nonExecutableStack", "minimumLoadAlignment", "loadableSegmentsSha256", "textSha256",
            "pokedataOffset", "pokedataStubVerified")}


def build_proof(ndk_path, output_dir, root=ROOT):
    root = root.resolve()
    sources, baselines, inputs = read_inputs(root)
    ndk, properties, tools, identities = ndk_tools(ndk_path, root)
    output = output_path(output_dir, root, ndk)
    output.mkdir(mode=0o700)
    temporary = output / "tmp"
    temporary.mkdir(mode=0o700)
    environment = build_environment(tools, temporary)
    summary = {"schemaVersion": 1, "status": "failed", "proofOnly": True,
               "sourceBuildSucceeded": False, "rawReproducible": False, "postStripReproducible": False,
               "matchesBundledBytes": False, "inputsAndToolsUnchanged": False,
               "packagedPayloadsChanged": False, "targetOutputExecuted": False,
               "androidRuntimeCompatibilityVerified": False, "fdroidAcceptanceVerified": False,
               "ndkArchiveAuthenticated": False, "elfCleanerExecuted": False,
               "historicalPostprocessingReplayed": False, "postprocessingIncomplete": True,
               "ndkRevision": NDK_REVISION, "androidApi": 24,
               "sourceArchiveSha256": ARCHIVE_SHA256, "verifiedGitBlobs": SOURCE_BLOBS,
               "proofCodeSha256": {name: sha(raw) for name, raw in inputs.items() if name.startswith("tools/")},
               "installedToolSha256": {name: item["sha256"] for name, item in identities.items()},
               "ndkPropertiesSha256": sha(properties),
               "toolchainTrust": "Trusted separately provisioned SDK-manager installation; no archive authentication.",
               "recipeLimits": "No Termux prefix headers/patched sysroot; actual dependencies recorded. Cleaner not run.",
               "builds": []}
    snapshots = []
    source_copies = []
    dependency_inputs = {}
    invocation = 0

    def run(command, cwd=output, input_bytes=None):
        nonlocal invocation
        invocation += 1
        log = output / ("command-%03d.log" % invocation)
        data = run_tool(command, cwd, environment, log, input_bytes=input_bytes)
        commands.append({"command": [str(item) for item in command], "cwd": str(cwd),
                         "log": log.name, "logSha256": sha(data)})
        snapshots.append((log, data))
        return data

    def unchanged():
        for relative, data in inputs.items():
            require(read(root / relative, MAX_ARCHIVE) == data, "Pinned repository input changed: " + relative)
        require(read(ndk / "source.properties", 65536) == properties, "NDK revision metadata changed")
        for name, path in tools.items():
            require(tool_identity(path, ndk if name not in HOST_TOOLS else None) == identities[name],
                    "Tool identity changed: " + name)
        for directory in source_copies:
            for relative, data in sources.items():
                require(read(directory / relative) == data, "Staged source changed: " + relative)
        for path, digest in dependency_inputs.items():
            require(sha(read(Path(path))) == digest, "Compiler header input changed: " + path)
        for path, data in snapshots:
            require(read(path) == data, "Proof output changed after inspection: " + str(path))

    commands, details, versions = [], [], {}
    try:
        for name in ("cc", "linker", "strip", "make", "gawk", "readelf"):
            versions[name] = run([tools[name], "--version"]).decode("utf-8", "replace")
        require("clang version 21.0.0" in versions["cc"] and "r563880c" in versions["cc"],
                "NDK compiler version does not match the reviewed r29 generation")
        require("GNU Awk" in versions["gawk"], "GNU awk is required for strtonum/word boundaries")
        summary["compilerGeneration"] = versions["cc"].splitlines()[0][:512]
        summary["linkerGeneration"] = versions["linker"].splitlines()[0][:256]
        # Match and explicitly freeze the successful historical host-ld probe;
        # make still appends its ordinary C/CPP flags, but cannot vary build-id.
        run([tools["ld"], "--build-id=none", "--version"])
        pairs = []
        for label in ("first", "second"):
            unchanged()
            work = output / label
            work.mkdir(mode=0o700)
            for relative, data in sources.items():
                write_new(work / relative, data)
            source_copies.append(work)
            source_dir = work / "src"
            macros = {}
            for abi in LOADERS:
                selector = [] if abi == "arm64" else ["-m32"]
                raw = run([tools["cc"], *selector, "-E", "-dM", "-DNO_LIBC_HEADER", "arch.h"], source_dir)
                macros[abi] = architecture_macros(raw, abi)
            command = make_command(tools)
            run(command, source_dir)
            images, reports, dependencies = {}, {}, {}
            for abi, (baseline_name, _) in LOADERS.items():
                suffix = "" if abi == "arm64" else "-m32"
                selector = [] if abi == "arm64" else ["-m32"]
                raw_path = source_dir / ("loader/loader" + suffix)
                raw_path.chmod(0o600)
                raw = read(raw_path)
                raw_elf = inspect_loader(raw, abi, require_symbols=abi == "arm64")
                snapshots.append((raw_path, raw))
                # -### is compiler-driver diagnostics, not output execution.
                compile_flags = [*shlex.split(COMMON_CPPFLAGS), "-D_FILE_OFFSET_BITS=64", "-D_GNU_SOURCE", "-I.", "-I./",
                                 *shlex.split(COMMON_CFLAGS), "-Wall", "-Wextra", "-O2",
                                 '-DPROOT_UNBUNDLE_LOADER="' + TERMUX_PREFIX + '/libexec/proot"',
                                 "-MD", "-c", "./loader/loader.c", "-o", "loader/loader" + suffix + ".o",
                                 *selector, "-fPIC", "-ffreestanding"]
                compile_expansion = run([tools["cc"], "-###", *compile_flags], source_dir)
                link_expansion = run([tools["cc"], "-###", *selector, "-o", "loader/loader" + suffix,
                                     "loader/loader" + suffix + ".o", "loader/assembly" + suffix + ".o", "-static", "-nostdlib",
                                     "-Wl,--build-id=none,-Ttext=" + macros[abi]["LOADER_ADDRESS"] + ",--rosegment,-z,noexecstack"], source_dir)
                dependencies[abi] = {}
                for stem in ("loader", "assembly"):
                    dep_path = source_dir / ("loader/" + stem + suffix + ".d")
                    dep_raw = read(dep_path, 65536)
                    snapshots.append((dep_path, dep_raw))
                    closure = dependency_closure(dep_raw, source_dir, ndk)
                    dependencies[abi][stem] = closure
                    for item in closure.values():
                        require(item["path"] not in dependency_inputs or dependency_inputs[item["path"]] == item["sha256"],
                                "Compiler header changed between dependency observations")
                        dependency_inputs[item["path"]] = item["sha256"]
                if abi == "arm64":
                    symbols = run([tools["readelf"], "--wide", "--syms", raw_path], source_dir)
                    generated = run([tools["gawk"], "-f", source_dir / "loader/loader-info.awk"],
                                    source_dir, input_bytes=symbols)
                    offset = parse_pokedata(generated)
                    require(offset == raw_elf["pokedataOffset"], "GNU awk offset differs from independently resolved ELF symbols")
                    generated_path = work / "generated-loader-info.c"
                    write_new(generated_path, generated)
                    snapshots.append((generated_path, generated))
                stripped_path = work / ("post-strip-" + baseline_name)
                write_new(stripped_path, raw)
                run([tools["strip"], "--strip-unneeded", "--preserve-dates", stripped_path], source_dir)
                stripped_path.chmod(0o600)
                stripped = read(stripped_path)
                stripped_elf = inspect_loader(stripped, abi)
                snapshots.append((stripped_path, stripped))
                images[abi] = {"raw": raw, "postStrip": stripped}
                reports[abi] = {"rawSha256": sha(raw), "postStripSha256": sha(stripped),
                                "rawBytes": len(raw), "postStripBytes": len(stripped),
                                "baselineSha256": sha(baselines[abi]), "matchesBundledBytes": stripped == baselines[abi],
                                "textUnchangedByStrip": raw_elf["textSha256"] == stripped_elf["textSha256"],
                                "compileDriverExpansionSha256": sha(compile_expansion),
                                "linkDriverExpansionSha256": sha(link_expansion),
                                "rawElf": raw_elf, "postStripElf": stripped_elf}
                require(reports[abi]["textUnchangedByStrip"], "Strip unexpectedly changed executable .text")
            pairs.append(images)
            details.append({"name": label, "architectureMacros": macros, "compilerDependencies": dependencies,
                            "loaders": reports})
            summary["builds"].append({"name": label, "loaders": {
                abi: {key: value for key, value in item.items() if key not in ("rawElf", "postStripElf")}
                for abi, item in reports.items()}})
            if label == "first":
                summary["elfEvidence"] = {abi: {"raw": compact_elf(item["rawElf"]),
                                                   "postStrip": compact_elf(item["postStripElf"])}
                                          for abi, item in reports.items()}
                summary["headerClosureSha256"] = {identity: item["sha256"] for groups in dependencies.values()
                                                    for closure in groups.values() for identity, item in closure.items()}
                summary["arm64PokedataOffset"] = reports["arm64"]["rawElf"]["pokedataOffset"]
                summary["gawkOffsetMatchesIndependentSymbols"] = True
                summary["architectureMacros"] = macros
            unchanged()
        summary["sourceBuildSucceeded"] = True
        summary["rawReproducible"] = all(pairs[0][abi]["raw"] == pairs[1][abi]["raw"] for abi in LOADERS)
        summary["postStripReproducible"] = all(pairs[0][abi]["postStrip"] == pairs[1][abi]["postStrip"] for abi in LOADERS)
        summary["matchesBundledBytes"] = all(pair[abi]["postStrip"] == baselines[abi]
                                              for pair in pairs for abi in LOADERS)
        summary["postprocessingIncomplete"] = not summary["matchesBundledBytes"]
        unchanged()
        summary["inputsAndToolsUnchanged"] = True
        if summary["rawReproducible"] and summary["postStripReproducible"] and summary["matchesBundledBytes"]:
            summary["status"] = "source-built-both-retained-loader-bytes-reproduced"
        save_json(output / "receipt.json", {
            "summary": summary,
            "toolIdentities": identities, "toolVersions": versions, "ndkPropertiesSha256": sha(properties),
            "builds": details,
            "historicalNdkZipSha256NotDownloadedOrAuthenticated": HISTORICAL_NDK_ZIP_SHA256,
            "environment": environment, "commands": commands,
            "sourceArchiveSha256": ARCHIVE_SHA256, "sourceGitBlobs": SOURCE_BLOBS,
            "stagedSourceFileCount": len(sources), "stagedSourceSha256": {k: sha(v) for k, v in sources.items()},
            "trustLimits": ["Trusted sdkmanager installation; recorded identities are not archive authentication.",
                            "No Termux prefix headers or patched Termux sysroot supplied; actual dependencies are recorded.",
                            "Historical ELF cleaner v3.0.1 was not executed; no claim it is a no-op.",
                            "Only retained final-byte equality can close this experiment without that cleaner.",
                            "No PRoot engine, talloc, APK, installed payload, feature, or dependency was changed.",
                            "No loader execution, Android device QA, or F-Droid acceptance is demonstrated."]})
        require(summary["rawReproducible"] and summary["postStripReproducible"],
                "Two isolated loader builds were not byte-identical")
        require(summary["matchesBundledBytes"],
                "Post-strip loader differs from retained baseline; historical postprocessing remains incomplete")
        summary["status"] = "source-built-both-retained-loader-bytes-reproduced"
    except (SourceBuildError, LoaderElfError, OSError, ValueError) as error:
        summary["status"] = "failed"
        summary["error"] = str(error)[-2000:]
        save_summary(output / "SUMMARY.json", summary)
        raise SourceBuildError(str(error)) from error
    save_summary(output / "SUMMARY.json", summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = build_proof(args.ndk, args.output_dir)
    except (SourceBuildError, LoaderElfError, OSError, ValueError) as error:
        print("PROOT_LOADER_SOURCE_PROOF_FAILED: " + str(error))
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
