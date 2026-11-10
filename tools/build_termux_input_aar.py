#!/usr/bin/env python3
"""Offline, reproducible Java-only Termux overlays; original AARs are never written."""
from __future__ import annotations

import argparse
import difflib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PATCH_ROOT = ROOT / "third_party/termux/patches"
# Each overlay has an exact source diff; newly authored classes use /dev/null as base.
OVERLAYS = (
    ("source", "patch", "TerminalSession", "0001-agent-input-owner-hook.patch", True),
    ("rowSource", "rowPatch", "TerminalRow", "0002-bounded-combining-row.patch", True),
    ("emulatorSource", "emulatorPatch", "TerminalEmulator", "0003-bounded-parser-work.patch", True),
    ("bufferSource", "bufferPatch", "TerminalBuffer", "0004-retained-buffer-budget.patch", True),
    ("queueSource", "queuePatch", "ByteQueue", "0005-drainable-output-queue.patch", True),
    ("memorySource", "memoryPatch", "TerminalMemoryBudget", "0006-mobile-memory-budget.patch", False),
    ("jniSource", "jniPatch", "JNI", "0007-owned-process-jni.patch", True),
    ("ownerSource", "ownerPatch", "TerminalProcessOwner", "0008-prestarted-process-owner.patch", False),
    ("ioSource", "ioPatch", "TerminalPtyIo", "0009-bounded-pty-io.patch", False),
)
METADATA = PATCH_ROOT / "REBUILD.json"
ORIGINAL = ROOT / "app/libs/terminal-emulator-v0.118.0-java.aar"
UPSTREAM_SOURCE = ROOT / "third_party/termux/terminal-emulator-v0.118.0-sources.jar"
OUTPUT = ROOT / "app/libs/terminal-emulator-v0.118.0-droide-input.aar"
PREFIX = "com/termux/terminal/"
ADDED_CLASSES = {PREFIX + name + ".class" for name in ('TerminalSession$InputHandler', 'TerminalMemoryBudget', 'TerminalSession$NativeBackend', 'TerminalSession$WriterBackend', 'TerminalSession$ReaderStopCondition', 'ByteQueue$StopCondition', 'TerminalProcessOwner', 'TerminalProcessOwner$Backend', 'TerminalProcessOwner$Worker', 'TerminalPtyIo', 'TerminalPtyIo$Backend', 'TerminalPtyIo$WriteReceipt', 'TerminalSession$ReaderBackend', 'TerminalPtyIo$ReaderBackend', 'TerminalPtyIo$ReadReceipt')}
ABI_SOURCE = ROOT / "third_party/termux/native/OWNED_PROCESS_ABI.json"
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_overlay_class(name: str) -> bool:
    return any(name == PREFIX + class_name + ".class" or
               (name.startswith(PREFIX + class_name + "$") and name.endswith(".class"))
               for _, _, class_name, _, _ in OVERLAYS)


def deterministic_zip(entries: dict[str, bytes]) -> bytes:
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, FIXED_TIME)
            info.create_system = 3
            info.external_attr = (0o100644 if not name.endswith("/") else 0o40755) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data, compresslevel=9)
    return stream.getvalue()


def run() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-jar", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT", "")) / "platforms/android-36/android.jar")
    parser.add_argument("--javac", default="javac")
    choice = parser.add_mutually_exclusive_group()
    choice.add_argument("--check", action="store_true", help="Rebuild into temporary storage and compare; write no artifacts")
    choice.add_argument("--update-metadata", action="store_true", help="Intentionally record reviewed source and rebuilt output hashes")
    args = parser.parse_args()
    if not args.android_jar.is_file():
        parser.error("Android API 36 android.jar is required; no network downloads are performed")
    upstream = json.loads((ROOT / "third_party/termux/UPSTREAM_PROVENANCE.json").read_text())
    for path, key in ((ORIGINAL, "terminalEmulatorJavaAar"), (UPSTREAM_SOURCE, "terminalEmulatorSources")):
        if digest(path.read_bytes()) != upstream["artifacts"][key]["sha256"]:
            raise SystemExit("TERMUX_INPUT_BUILD_FAILED: original upstream input hash mismatch: " + str(path))
    source_paths = []
    overlay_metadata = {}
    with zipfile.ZipFile(UPSTREAM_SOURCE) as sources:
        for source_key, patch_key, class_name, patch_name, upstream_source in OVERLAYS:
            source_name = PREFIX + class_name + ".java"
            source_path = PATCH_ROOT / "src" / source_name
            patch_path = PATCH_ROOT / patch_name
            source_bytes = source_path.read_bytes()
            original_source = sources.read(source_name) if upstream_source else b""
            expected_patch = "".join(difflib.unified_diff(
                original_source.decode().splitlines(keepends=True), source_bytes.decode().splitlines(keepends=True),
                fromfile="a/" + source_name if upstream_source else "/dev/null", tofile="b/" + source_name,
            )).encode()
            if patch_path.read_bytes() != expected_patch:
                raise SystemExit("TERMUX_INPUT_BUILD_FAILED: reviewed patch does not exactly describe " + class_name)
            source_paths.append(source_path)
            overlay_metadata[source_key] = {"path": source_path.relative_to(ROOT).as_posix(), "sha256": digest(source_bytes)}
            overlay_metadata[patch_key] = {"path": patch_path.relative_to(ROOT).as_posix(), "sha256": digest(expected_patch)}
    compile_env = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        compile_env.pop(key, None)
    compiler_version = subprocess.check_output([args.javac, "-version"], stderr=subprocess.STDOUT, text=True, env=compile_env).strip()
    if not compiler_version.startswith("javac 17."):
        raise SystemExit("TERMUX_INPUT_BUILD_FAILED: use pinned JDK 17 with --release 8")
    with zipfile.ZipFile(ORIGINAL) as original:
        original_entries = {name: original.read(name) for name in original.namelist()}
    with zipfile.ZipFile(io.BytesIO(original_entries["classes.jar"])) as classes:
        class_entries = {name: classes.read(name) for name in classes.namelist()}
    original_classes = {name for name in class_entries if is_overlay_class(name)}
    with tempfile.TemporaryDirectory(prefix="droide-termux-input-") as directory:
        work = Path(directory)
        original_jar = work / "classes.jar"
        original_jar.write_bytes(original_entries["classes.jar"])
        compiled = work / "compiled"
        compiled.mkdir()
        subprocess.run([
            args.javac, "--release", "8", "-encoding", "UTF-8", "-g:none", "-classpath",
            str(args.android_jar.resolve()) + os.pathsep + str(original_jar), "-d", str(compiled), *map(str, source_paths),
        ], check=True, env=compile_env)
        replacement = {p.relative_to(compiled).as_posix(): p.read_bytes() for p in compiled.rglob("*.class")}
    expected_classes = original_classes | ADDED_CLASSES
    if set(replacement) != expected_classes:
        raise SystemExit("TERMUX_INPUT_BUILD_FAILED: compiled class scope differs: " + repr(sorted(replacement)))
    class_entries.update(replacement)
    original_entries["classes.jar"] = deterministic_zip(class_entries)
    output_bytes = deterministic_zip(original_entries)
    metadata = {
        "schemaVersion": 3,
        "ownedProcessAbiSource": {"path": ABI_SOURCE.relative_to(ROOT).as_posix(), "sha256": digest(ABI_SOURCE.read_bytes())},
        "upstreamCommit": upstream["upstream"]["commit"],
        "originalJavaAar": {"path": ORIGINAL.relative_to(ROOT).as_posix(), "sha256": digest(ORIGINAL.read_bytes())},
        "originalSourceJar": {"path": UPSTREAM_SOURCE.relative_to(ROOT).as_posix(), "sha256": digest(UPSTREAM_SOURCE.read_bytes())},
        **overlay_metadata,
        "buildScript": {"path": Path(__file__).resolve().relative_to(ROOT).as_posix(), "sha256": digest(Path(__file__).read_bytes())},
        "compiler": {"version": compiler_version, "arguments": ["--release", "8", "-encoding", "UTF-8", "-g:none"]},
        "androidJar": {"api": 36, "sha256": digest(args.android_jar.read_bytes())},
        "zip": {"timestamps": "1980-01-01T00:00:00", "ordering": "sorted", "compression": "deflate-9"},
        "output": {"path": OUTPUT.relative_to(ROOT).as_posix(), "sha256": digest(output_bytes)},
        "replacementClassSha256ByPath": {name: digest(data) for name, data in sorted(replacement.items())},
        "addedClasses": sorted(ADDED_CLASSES),
        "localChanges": [
            "One-time input owner hook routes all write(byte[],offset,count) calls before upstream ByteQueue",
            "Owner worker writeToPty returns actual upstream queue acceptance and rejects Android main-thread forwarding",
            "Native-owned generation handle replaces all raw PID/fd and ParcelFileDescriptor ownership APIs",
            "Waiter, reader and writer are preconstructed and started before native spawn with exactly-once handoffs",
            "One-attempt native I/O exposes EINTR/EAGAIN; bounded partial writer and drain cancellation retain receipts",
            "Writer lane acknowledgment gates clean completion; cleanup failures remain explicit",
            "TerminalRow retains at most 64 UTF-16 units per cell and uses a non-narrowing row-length counter",
            "Wide overwrite consumes the adjacent cell's full combining suffix; clear shrinks recycled text storage",
            "Finite numeric parameters, explicit over-limit REP/SU rejection, ordered bounded owner continuations",
            "Reader completion and queue/parser drain own final delivery; timeout explicitly reports incomplete output",
            "Byte-accounted history, viewport admission and shared retained/reflow reservations across reachable emulators",
            "Nine exact overlay families include JNI ABI v2; renderer and original artifact bytes remain unchanged",
            "This Java-only AAR recipe does not build or attest native ABI v2; release requires separate native evidence",
        ],
    }
    if not args.update_metadata:
        if not METADATA.is_file() or json.loads(METADATA.read_text()) != metadata:
            raise SystemExit("TERMUX_INPUT_BUILD_FAILED: rebuilt metadata differs; review changes before --update-metadata")
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_bytes() != output_bytes:
            raise SystemExit("TERMUX_INPUT_BUILD_FAILED: reproducible output mismatch")
    else:
        OUTPUT.write_bytes(output_bytes)
        if args.update_metadata:
            METADATA.write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"TERMUX_INPUT_BUILD_OK mode={'check' if args.check else 'build'} sha256={digest(output_bytes)} replacement_classes={len(replacement)}")


if __name__ == "__main__":
    run()
