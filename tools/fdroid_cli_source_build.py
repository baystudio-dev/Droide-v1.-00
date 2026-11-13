#!/usr/bin/env python3
"""Baseline-free CLI compilation with a separate retained-byte proof wrapper.

This does not replace an APK payload, change the installer hash, build PRoot,
or execute the resulting ARM64 program. A trusted, preinstalled Android NDK
28.2.13676358 is required; its revision metadata alone is not authentication.
The output directory must be explicit, absolute, new, and outside the source
tree and NDK. The caller must enforce offline, read-only-input, bounded scratch
execution. Neither API promotes artifacts or grants APK/distribution admission.
``build_sources`` never reads a retained ELF; the unchanged command-line entry
point calls ``build_proof`` and independently compares the retained CLI bytes.
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
OUTPUT_NAME = "libdroide_cli.so"
# Frozen textual identity of the existing reviewed output, not a build input.
# The size is independently recorded by the two-build CLI source proof.
OUTPUT_SHA256 = "913b313398b30115416acd20959b1c449eab0563c15c83230862b03e44e7cfee"
OUTPUT_BYTES = 4528
BUNDLED_RELATIVE = Path("app/src/main/jniLibs/arm64-v8a") / OUTPUT_NAME
INSTALLER_RELATIVE = Path("app/src/main/java/com/baystudio/droide/core/DroideCliClientInstaller.kt")
MAX_INPUT_BYTES = 1024 * 1024
MAX_MANIFEST_BYTES = 128 * 1024
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


def read_regular(path: Path, bound: int = MAX_INPUT_BYTES) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise SourceBuildError(f"Expected a regular, non-symlink file: {path}")
    with path.open("rb") as stream:
        data = stream.read(bound + 1)
    if len(data) > bound:
        raise SourceBuildError(f"File exceeds its {bound}-byte input bound: {path}")
    return data


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
        if output == protected or protected in output.parents or output in protected.parents:
            raise SourceBuildError("--output-dir must be outside the repository and NDK")
    if not output.parent.is_dir():
        raise SourceBuildError("The output parent directory must already exist")
    return output


def ndk_tools(ndk_path: Path, root: Path) -> tuple[Path, Path, Path]:
    ndk = ndk_path.resolve(strict=True)
    if ndk == root.resolve() or root.resolve() in ndk.parents or ndk in root.resolve().parents:
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


def read_source_inputs(root: Path) -> dict[Path, bytes]:
    """Read only reviewed source/text inputs; no retained ELF path is consulted."""
    inputs = {path: read_regular(root / path) for path in (SOURCE_RELATIVE, INSTALLER_RELATIVE)}
    if sha256(inputs[SOURCE_RELATIVE]) != SOURCE_SHA256:
        raise SourceBuildError("CLI source checksum differs from the reviewed source pin")
    installer = inputs[INSTALLER_RELATIVE].decode("utf-8")
    pins = re.findall(r'const val SHA256 = "([0-9a-f]{64})"', installer)
    if pins != [OUTPUT_SHA256]:
        raise SourceBuildError("Existing installer integrity pin differs from the frozen CLI output identity")
    return inputs


def require_output_identity(data: bytes) -> None:
    if len(data) != OUTPUT_BYTES or sha256(data) != OUTPUT_SHA256:
        raise SourceBuildError(
            f"CLI output differs from the pinned output identity: expected {OUTPUT_BYTES} bytes / "
            f"{OUTPUT_SHA256}, observed {len(data)} bytes / {sha256(data)}")


def write_manifest(path: Path, report: dict) -> None:
    """Publish only a fully written/closed receipt, without replacing any leaf."""
    payload = (json.dumps(report, indent=2) + "\n").encode("utf-8")
    if len(payload) > MAX_MANIFEST_BYTES:
        raise SourceBuildError("CLI manifest exceeds its bounded diagnostic size")
    pending = path.with_name(path.name + ".pending")
    created = False
    try:
        with pending.open("xb") as stream:
            created = True
            if stream.write(payload) != len(payload):
                raise OSError("Incomplete CLI manifest write")
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        if created:
            pending.unlink()
        raise
    # link() is the final fallible filesystem operation and cannot replace a
    # destination. Retain the complete pending diagnostic link, even on a final
    # collision: cleanup after publication must never turn published success
    # into failure. Neither receipt link is a promoted APK artifact.
    os.link(pending, path, follow_symlinks=False)


def build_sources(ndk_path: Path, output_dir: Path, root: Path = ROOT) -> dict:
    """Compile twice from source only; exact text pins are mandatory, never overrides.

    Generated files remain unpromoted scratch data. No retained-artifact path,
    retained bytes, comparator callback, or fallback is accepted by this API.
    """
    root = root.resolve()
    inputs = read_source_inputs(root)
    source = inputs[SOURCE_RELATIVE]
    builder_path = Path(__file__).resolve()
    builder_source = read_regular(builder_path)
    ndk, compiler, linker = ndk_tools(ndk_path, root)
    initial_tools = {str(tool): tool_identity(ndk, tool) for tool in (compiler, linker)}
    ndk_properties = read_regular(ndk / "source.properties")
    source_copies: list[Path] = []
    output_copies: list[tuple[Path, bytes]] = []

    def check_provenance() -> None:
        for relative, original in inputs.items():
            if read_regular(root / relative) != original:
                raise SourceBuildError(f"CLI source/text input changed during the build: {relative}")
        if read_regular(builder_path) != builder_source:
            raise SourceBuildError("CLI builder source changed during the build")
        if read_regular(ndk / "source.properties") != ndk_properties:
            raise SourceBuildError("Toolchain revision metadata changed during the build")
        for tool in (compiler, linker):
            if tool_identity(ndk, tool) != initial_tools[str(tool)]:
                raise SourceBuildError(f"Toolchain compiler/linker changed during the build: {tool.name}")
        for copy in source_copies:
            if read_regular(copy) != source:
                raise SourceBuildError(f"Isolated compiler source copy changed during the build: {copy}")
        for path, original in output_copies:
            if read_regular(path, MAX_BINARY_BYTES) != original:
                raise SourceBuildError(f"Built CLI output changed during the build: {path}")

    output = checked_output(output_dir, root, ndk)
    output.mkdir(exist_ok=False)
    temporary_dir = output / "tmp"
    temporary_dir.mkdir()
    environment = build_environment(compiler.parent, temporary_dir)
    check_provenance()
    compiler_version = run_tool([str(compiler), "--version"], output, environment, timeout=15)
    linker_version = run_tool([str(linker), "--version"], output, environment, timeout=15)
    builds = []
    results = []
    for name in ("first", "second"):
        build_dir = output / name
        build_dir.mkdir()
        source_copy = build_dir / SOURCE_RELATIVE.name
        with source_copy.open("xb") as stream:
            stream.write(source)
        source_copies.append(source_copy)
        binary = build_dir / OUTPUT_NAME
        command = [str(compiler), *BUILD_FLAGS, "-o", str(binary), str(source_copy)]
        check_provenance()
        compiler_output = run_tool(command, build_dir, environment)
        check_provenance()
        data = read_regular(binary, MAX_BINARY_BYTES)
        # The proof output stays data-only even on a matching-architecture host.
        binary.chmod(0o644)
        elf = validate_elf(data)
        results.append(data)
        output_copies.append((binary, data))
        builds.append({"name": name, "directory": str(build_dir), "command": command,
                       "sha256": sha256(data), "bytes": len(data), "elf": elf,
                       "compilerOutput": compiler_output})
    if results[0] != results[1]:
        raise SourceBuildError("Two isolated builds were not byte-identical; source build failed")
    for data in results:
        require_output_identity(data)
    check_provenance()
    report = {
        "schemaVersion": 1,
        "status": "isolated-source-build-only",
        "source": {"path": SOURCE_RELATIVE.as_posix(), "sha256": sha256(source)},
        "sourceInputs": {path.as_posix(): sha256(data) for path, data in inputs.items()},
        "builderSha256": sha256(builder_source),
        "toolchain": {"ndkRevision": NDK_REVISION, "host": "linux-x86_64",
                      "compiler": str(compiler), "compilerSha256": initial_tools[str(compiler)]["sha256"],
                      "compilerVersion": compiler_version, "linker": str(linker),
                      "linkerSha256": initial_tools[str(linker)]["sha256"], "linkerVersion": linker_version,
                      "initialToolIdentities": initial_tools, "unchangedBeforeAndAfterEachBuild": True,
                      "ndkPropertiesSha256": sha256(ndk_properties)},
        "builds": builds,
        "reproducibleAcrossTwoBuildDirectories": True,
        "expectedOutput": {"name": OUTPUT_NAME, "sha256": OUTPUT_SHA256, "bytes": OUTPUT_BYTES},
        "matchesPinnedOutputIdentity": True,
        "inputsAndToolsUnchanged": True,
        "outputFilesUnchanged": True,
        "retainedArtifactInputs": [],
        "baselineComparisonPerformed": False,
        "packagedPayloadsChanged": False,
        "publicationApproved": False,
        "arm64OutputExecuted": False,
        "androidRuntimeCompatibilityVerified": False,
        "fdroidSourceBuildAcceptanceVerified": False,
        "notes": ["No packaged payload, installer pin, Gradle packaging, or PRoot loader was changed.",
                  "The core reads source/text inputs and trusted tools, never a retained CLI ELF.",
                  "Outputs are unpromoted scratch data; a separate trusted admission boundary is required.",
                  "Deterministic compiler output and ELF structure do not prove Android runtime compatibility.",
                  "A differing output identity is rejected; no reference-copy fallback exists.",
                  "NDK revision metadata and recorded tool hashes do not independently authenticate the NDK installation."],
    }
    write_manifest(output / "source-build-manifest.json", report)
    return report


def build_proof(ndk_path: Path, output_dir: Path, root: Path = ROOT) -> dict:
    """Independently compare both source-only results with the retained CLI."""
    root = root.resolve()
    original_inputs = read_source_inputs(root)
    bundled = read_regular(root / BUNDLED_RELATIVE, MAX_BINARY_BYTES)
    validate_elf(bundled)
    require_output_identity(bundled)
    core = build_sources(ndk_path, output_dir, root)
    output = output_dir.resolve()
    for name in ("first", "second"):
        data = read_regular(output / name / OUTPUT_NAME, MAX_BINARY_BYTES)
        validate_elf(data)
        if data != bundled:
            raise SourceBuildError(f"Retained CLI byte comparison failed for the {name} source build")
        require_output_identity(data)
    if read_regular(root / BUNDLED_RELATIVE, MAX_BINARY_BYTES) != bundled:
        raise SourceBuildError("Retained CLI baseline changed during the proof")
    if read_source_inputs(root) != original_inputs:
        raise SourceBuildError("CLI source/text inputs changed during the retained-byte proof")
    if sha256(read_regular(Path(__file__).resolve())) != core["builderSha256"]:
        raise SourceBuildError("CLI builder source changed during the retained-byte proof")
    core_manifest = read_regular(output / "source-build-manifest.json", MAX_MANIFEST_BYTES)
    if core_manifest != (json.dumps(core, indent=2) + "\n").encode("utf-8"):
        raise SourceBuildError("Source-only CLI manifest changed before the retained-byte proof completed")
    report = {**core,
              "status": "isolated-source-build-proof-only",
              "retainedArtifactInputs": [BUNDLED_RELATIVE.as_posix()],
              "baselineComparisonPerformed": True,
              "bundledBaseline": {"path": BUNDLED_RELATIVE.as_posix(), "sha256": sha256(bundled),
                                  "bytes": len(bundled), "installerSha256": OUTPUT_SHA256},
              "matchesBundledBytes": True,
              "sourceBuildManifestSha256": sha256(core_manifest),
              "notes": core["notes"] + ["The proof wrapper separately compared both generated files with the retained CLI."]}
    write_manifest(output / "build-manifest.json", report)
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
