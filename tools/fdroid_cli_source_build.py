#!/usr/bin/env python3
"""Isolated, non-packaged source-build proof for Droide's ARM64 CLI bridge.

This does not replace an APK payload, change the installer hash, build PRoot,
or execute the resulting ARM64 program. A trusted, preinstalled Android NDK
28.2.13676358 is required; its revision metadata alone is not authentication.
The output directory must be explicit, absolute, new, and outside the source
tree and NDK. Artifacts are retained on failure for inspection.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import struct
import subprocess


ROOT = Path(__file__).resolve().parents[1]
NDK_REVISION = "28.2.13676358"
SOURCE_RELATIVE = Path("app/src/main/cpp/droide_cli_client.c")
SOURCE_SHA256 = "f668eff99575d9a3ad1f68ce5727f6d51db4d7f8ebe811b4d800532084304830"
BUNDLED_RELATIVE = Path("app/src/main/jniLibs/arm64-v8a/libdroide_cli.so")
INSTALLER_RELATIVE = Path("app/src/main/java/com/baystudio/droide/core/DroideCliClientInstaller.kt")
MAX_BINARY_BYTES = 64 * 1024
MIN_LOAD_ALIGNMENT = 16 * 1024
# Preserve the existing freestanding Linux/AArch64 contract. In particular,
# this .so-named APK payload is ET_EXEC, not an Android shared object or PIE.
BUILD_FLAGS = (
    "--target=aarch64-linux-gnu", "-nostdlib", "-static", "-ffreestanding",
    "-fno-builtin", "-fno-stack-protector", "-fuse-ld=lld", "-Os",
    "-Wall", "-Wextra", "-Werror", "-Wl,--build-id=none",
    "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
    "-Wl,-e,_start",
)


class SourceBuildError(RuntimeError):
    """A proof precondition or verification failed."""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_regular(path: Path) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise SourceBuildError(f"Expected a regular, non-symlink file: {path}")
    return path.read_bytes()


def validate_elf(data: bytes) -> dict:
    """Validate structural/static/ABI constraints without loading the ELF."""
    if not 64 <= len(data) <= MAX_BINARY_BYTES:
        raise SourceBuildError("ELF size is outside the reviewed 64..65536-byte bound")
    if data[:7] != b"\x7fELF\x02\x01\x01":
        raise SourceBuildError("CLI must be ELF64 little-endian, version 1")
    elf_type, machine, version = struct.unpack_from("<HHI", data, 16)
    if (elf_type, machine, version) != (2, 183, 1):
        raise SourceBuildError("CLI must be ET_EXEC, AArch64, ELF version 1")
    entry, phoff = struct.unpack_from("<QQ", data, 24)
    ehsize, phentsize, phnum = struct.unpack_from("<HHH", data, 52)
    if ehsize != 64 or phoff < 64 or phentsize != 56 or not 1 <= phnum <= 32:
        raise SourceBuildError("Invalid ELF/program-header dimensions")
    if entry % 4:
        raise SourceBuildError("AArch64 entry point must be four-byte aligned")
    if phoff + phentsize * phnum > len(data):
        raise SourceBuildError("Truncated ELF program-header table")
    loads = []
    entry_is_executable = False
    for index in range(phnum):
        values = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phentsize)
        kind, flags, offset, address, _physical, file_size, memory_size, alignment = values
        if kind in (2, 3):
            raise SourceBuildError("PT_DYNAMIC/PT_INTERP are forbidden for the static CLI")
        if kind != 1:
            continue
        if alignment < MIN_LOAD_ALIGNMENT or alignment % MIN_LOAD_ALIGNMENT or alignment & (alignment - 1):
            raise SourceBuildError("PT_LOAD alignment must be a power of two compatible with 16 KiB")
        if address % alignment != offset % alignment:
            raise SourceBuildError("PT_LOAD file/address alignment is inconsistent")
        if file_size > memory_size or offset + file_size > len(data):
            raise SourceBuildError("PT_LOAD sizes are invalid or exceed the ELF file")
        if flags & 1 and address <= entry < address + file_size:
            entry_is_executable = True
        loads.append({"flags": flags, "offset": offset, "virtualAddress": address,
                      "fileBytes": file_size, "memoryBytes": memory_size, "alignment": alignment})
    if not loads or not entry_is_executable:
        raise SourceBuildError("ELF entry point is not inside a file-backed executable PT_LOAD")
    return {"class": "ELF64", "endianness": "little", "type": "ET_EXEC",
            "machine": "AArch64", "static": True, "entryPoint": entry,
            "loadSegments": loads, "minimumLoadAlignment": min(item["alignment"] for item in loads)}


def checked_output(output_dir: Path, root: Path, ndk: Path) -> Path:
    if not output_dir.is_absolute():
        raise SourceBuildError("--output-dir must be an absolute path")
    if output_dir.exists() or output_dir.is_symlink():
        raise SourceBuildError("--output-dir must be new; existing files/directories are never overwritten")
    output = output_dir.resolve()
    for protected in (root.resolve(), ndk.resolve()):
        if output == protected or protected in output.parents:
            raise SourceBuildError("--output-dir must be outside the repository and NDK")
    if not output.parent.is_dir():
        raise SourceBuildError("The output parent directory must already exist")
    return output


def ndk_tools(ndk_path: Path, root: Path) -> tuple[Path, Path, Path]:
    ndk = ndk_path.resolve(strict=True)
    if ndk == root.resolve() or root.resolve() in ndk.parents:
        raise SourceBuildError("Use a trusted NDK installation outside the source tree")
    if platform.system() != "Linux" or platform.machine().lower() not in ("x86_64", "amd64"):
        raise SourceBuildError("This proof currently requires a Linux x86_64 host")
    properties = read_regular(ndk / "source.properties").decode("utf-8")
    revisions = re.findall(r"^\s*Pkg\.Revision\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    if revisions != [NDK_REVISION]:
        raise SourceBuildError(f"Use pinned Android NDK {NDK_REVISION}; revision missing, duplicated, or different")
    tool_dir = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    compiler = tool_dir / "clang"
    linker = tool_dir / "ld.lld"
    for tool in (compiler, linker):
        resolved = tool.resolve(strict=True)
        if ndk not in resolved.parents or not resolved.is_file() or not os.access(resolved, os.X_OK):
            raise SourceBuildError(f"Expected an executable tool inside the pinned NDK: {tool}")
    return ndk, compiler, linker


def build_environment(tool_dir: Path, temporary_dir: Path) -> dict[str, str]:
    # Do not inherit credentials, compiler overrides, preload hooks, or user
    # configuration. No downloads, Gradle tasks, shell scripts, or ELF execution.
    return {"PATH": str(tool_dir) + os.pathsep + os.defpath,
            "LC_ALL": "C", "LANG": "C", "TZ": "UTC", "SOURCE_DATE_EPOCH": "0",
            "TMPDIR": str(temporary_dir)}


def tool_identity(ndk: Path, tool: Path) -> dict[str, str]:
    resolved = tool.resolve(strict=True)
    if ndk not in resolved.parents or not resolved.is_file() or not os.access(resolved, os.X_OK):
        raise SourceBuildError(f"Toolchain path is not an executable inside the pinned NDK: {tool}")
    digest = hashlib.sha256()
    with resolved.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return {"resolvedPath": str(resolved), "sha256": digest.hexdigest()}


def run_tool(command: list[str], cwd: Path, environment: dict[str, str], timeout: int = 120) -> str:
    try:
        result = subprocess.run(command, cwd=cwd, env=environment, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace", timeout=timeout,
                                check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise SourceBuildError(f"Compiler/tool invocation failed: {error}") from error
    if result.returncode:
        raise SourceBuildError(f"Compiler/tool exited {result.returncode}: {result.stdout[-8000:]}")
    return result.stdout.strip()


def build_proof(ndk_path: Path, output_dir: Path, root: Path = ROOT) -> dict:
    root = root.resolve()
    source = read_regular(root / SOURCE_RELATIVE)
    if sha256(source) != SOURCE_SHA256:
        raise SourceBuildError("CLI source checksum differs from the reviewed source pin")
    bundled = read_regular(root / BUNDLED_RELATIVE)
    installer = read_regular(root / INSTALLER_RELATIVE).decode("utf-8")
    pins = re.findall(r'const val SHA256 = "([0-9a-f]{64})"', installer)
    if len(pins) != 1 or sha256(bundled) != pins[0]:
        raise SourceBuildError("Bundled CLI does not match the existing installer integrity pin")
    validate_elf(bundled)
    ndk, compiler, linker = ndk_tools(ndk_path, root)
    initial_tools = {str(tool): tool_identity(ndk, tool) for tool in (compiler, linker)}
    ndk_properties = read_regular(ndk / "source.properties")

    def check_provenance(source_copies: tuple[Path, ...] = ()) -> None:
        if read_regular(root / SOURCE_RELATIVE) != source or read_regular(root / BUNDLED_RELATIVE) != bundled:
            raise SourceBuildError("Source or bundled baseline changed during the proof")
        if read_regular(root / INSTALLER_RELATIVE).decode("utf-8") != installer:
            raise SourceBuildError("Installer baseline changed during the proof")
        if read_regular(ndk / "source.properties") != ndk_properties:
            raise SourceBuildError("Toolchain revision metadata changed during the proof")
        for tool in (compiler, linker):
            if tool_identity(ndk, tool) != initial_tools[str(tool)]:
                raise SourceBuildError(f"Toolchain compiler/linker changed during the proof: {tool.name}")
        for copy in source_copies:
            if read_regular(copy) != source:
                raise SourceBuildError(f"Isolated compiler source copy changed during the proof: {copy}")

    output = checked_output(output_dir, root, ndk)
    output.mkdir(exist_ok=False)
    temporary_dir = output / "tmp"
    temporary_dir.mkdir()
    environment = build_environment(compiler.parent, temporary_dir)
    compiler_version = run_tool([str(compiler), "--version"], output, environment, timeout=15)
    linker_version = run_tool([str(linker), "--version"], output, environment, timeout=15)
    builds = []
    results = []
    source_copies = []
    for name in ("first", "second"):
        build_dir = output / name
        build_dir.mkdir()
        source_copy = build_dir / SOURCE_RELATIVE.name
        with source_copy.open("xb") as stream:
            stream.write(source)
        source_copies.append(source_copy)
        binary = build_dir / BUNDLED_RELATIVE.name
        command = [str(compiler), *BUILD_FLAGS, "-o", str(binary), str(source_copy)]
        check_provenance(tuple(source_copies))
        compiler_output = run_tool(command, build_dir, environment)
        check_provenance(tuple(source_copies))
        data = read_regular(binary)
        # The proof output stays data-only even on a matching-architecture host.
        binary.chmod(0o644)
        elf = validate_elf(data)
        results.append(data)
        builds.append({"name": name, "directory": str(build_dir), "command": command,
                       "sha256": sha256(data), "bytes": len(data), "elf": elf,
                       "compilerOutput": compiler_output})
    if results[0] != results[1]:
        raise SourceBuildError("Two isolated builds were not byte-identical; proof failed")
    check_provenance(tuple(source_copies))
    report = {
        "schemaVersion": 1,
        "status": "isolated-source-build-proof-only",
        "source": {"path": SOURCE_RELATIVE.as_posix(), "sha256": sha256(source)},
        "builderSha256": sha256(Path(__file__).read_bytes()),
        "toolchain": {"ndkRevision": NDK_REVISION, "host": "linux-x86_64",
                      "compiler": str(compiler), "compilerSha256": initial_tools[str(compiler)]["sha256"],
                      "compilerVersion": compiler_version, "linker": str(linker),
                      "linkerSha256": initial_tools[str(linker)]["sha256"], "linkerVersion": linker_version,
                      "initialToolIdentities": initial_tools, "unchangedBeforeAndAfterEachBuild": True,
                      "ndkPropertiesSha256": sha256(ndk_properties)},
        "builds": builds,
        "reproducibleAcrossTwoBuildDirectories": True,
        "bundledBaseline": {"path": BUNDLED_RELATIVE.as_posix(), "sha256": sha256(bundled),
                            "bytes": len(bundled), "installerSha256": pins[0]},
        "matchesBundledBytes": results[0] == bundled,
        "packagedPayloadsChanged": False,
        "arm64OutputExecuted": False,
        "androidRuntimeCompatibilityVerified": False,
        "fdroidSourceBuildAcceptanceVerified": False,
        "notes": ["No packaged payload, installer pin, Gradle packaging, or PRoot loader was changed.",
                  "Deterministic compiler output and ELF structure do not prove Android runtime compatibility.",
                  "A differing binary must not replace the bundled CLI without separate integration and device testing.",
                  "NDK revision metadata and recorded tool hashes do not independently authenticate the NDK installation."],
    }
    with (output / "build-manifest.json").open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path, help=f"Trusted installed Android NDK {NDK_REVISION}")
    parser.add_argument("--output-dir", required=True, type=Path,
                        help="Absolute fresh directory outside the repository and NDK; parent must exist")
    args = parser.parse_args(argv)
    try:
        report = build_proof(args.ndk, args.output_dir)
    except (SourceBuildError, OSError, UnicodeError) as error:
        parser.exit(1, f"DROIDE_CLI_SOURCE_BUILD_FAILED: {error}\n")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
