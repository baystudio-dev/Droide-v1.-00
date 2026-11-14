#!/usr/bin/env python3
"""Explicit offline TEST-ONLY Java compilation; never builds an AAR or native code.

--build compiles the nine reviewed overlay families into a host-test classes JAR.
--verify-only reads the source, receipt and JAR without invoking a compiler.
The fixed output location is separate from every distributable artifact/receipt.
"""
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
ORIGINAL = ROOT / "app/libs/terminal-emulator-v0.118.0-java.aar"
SOURCES = ROOT / "third_party/termux/terminal-emulator-v0.118.0-sources.jar"
OUTPUT_ROOT = ROOT / "app/build-host-jvm-tests/termux-test-only"
OUTPUT = OUTPUT_ROOT / "terminal-emulator-TEST-ONLY.jar"
RECEIPT = OUTPUT_ROOT / "TEST-ONLY-RECEIPT.json"
MARKER = "META-INF/DROIDE-TERMUX-TEST-ONLY.json"
KIND = "DROIDE_TERMUX_TEST_ONLY_JAR"
PREFIX = "com/termux/terminal/"
ANDROID_JAR_SHA256 = "d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a"
OVERLAYS = (
    ("TerminalSession", "0001-agent-input-owner-hook.patch", True),
    ("TerminalRow", "0002-bounded-combining-row.patch", True),
    ("TerminalEmulator", "0003-bounded-parser-work.patch", True),
    ("TerminalBuffer", "0004-retained-buffer-budget.patch", True),
    ("ByteQueue", "0005-drainable-output-queue.patch", True),
    ("TerminalMemoryBudget", "0006-mobile-memory-budget.patch", False),
    ("JNI", "0007-owned-process-jni.patch", True),
    ("TerminalProcessOwner", "0008-prestarted-process-owner.patch", False),
    ("TerminalPtyIo", "0009-bounded-pty-io.patch", False),
)
REPLACEMENTS = {PREFIX + name + ".class" for name in (
    "TerminalSession", "TerminalSession$1", "TerminalSession$2", "TerminalSession$3",
    "TerminalSession$MainThreadHandler", "TerminalSession$InputHandler", "TerminalRow",
    "TerminalEmulator", "TerminalEmulator$SavedScreenState", "TerminalBuffer",
    "ByteQueue", "TerminalMemoryBudget", "JNI",
    'TerminalSession$NativeBackend',
    'TerminalSession$WriterBackend',
    'TerminalSession$ReaderStopCondition',
    'ByteQueue$StopCondition',
    'TerminalProcessOwner',
    'TerminalProcessOwner$Backend',
    'TerminalProcessOwner$Worker',
    'TerminalPtyIo',
    'TerminalPtyIo$Backend',
    'TerminalPtyIo$WriteReceipt',
    'TerminalSession$ReaderBackend',
    'TerminalPtyIo$ReaderBackend',
    'TerminalPtyIo$ReadReceipt',

)}
ADDITIONS = {PREFIX + name + ".class" for name in ('TerminalSession$InputHandler', 'TerminalMemoryBudget', 'TerminalSession$NativeBackend', 'TerminalSession$WriterBackend', 'TerminalSession$ReaderStopCondition', 'ByteQueue$StopCondition', 'TerminalProcessOwner', 'TerminalProcessOwner$Backend', 'TerminalProcessOwner$Worker', 'TerminalPtyIo', 'TerminalPtyIo$Backend', 'TerminalPtyIo$WriteReceipt', 'TerminalSession$ReaderBackend', 'TerminalPtyIo$ReaderBackend', 'TerminalPtyIo$ReadReceipt')}
COMPILER_ARGUMENTS = ["--release", "8", "-encoding", "UTF-8", "-g:none", "-proc:none", "-implicit:none"]


def fail(message: str) -> None:
    raise SystemExit("TERMUX_TEST_ONLY_FAILED: " + message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def archive_entries(raw: bytes) -> dict[str, bytes]:
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            fail("duplicate archive entries")
        return {name: archive.read(name) for name in names}


def deterministic_jar(entries: dict[str, bytes]) -> bytes:
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (0o40755 if name.endswith("/") else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data, compresslevel=9)
    return out.getvalue()


def reviewed_inputs() -> tuple[dict[str, str], list[Path], dict[str, bytes]]:
    upstream_path = ROOT / "third_party/termux/UPSTREAM_PROVENANCE.json"
    upstream = json.loads(upstream_path.read_bytes())
    hashes = {}
    for path, key in ((ORIGINAL, "terminalEmulatorJavaAar"), (SOURCES, "terminalEmulatorSources")):
        value = digest(path.read_bytes())
        if value != upstream["artifacts"][key]["sha256"]:
            fail("pinned original input hash mismatch: " + str(path.relative_to(ROOT)))
        hashes[path.relative_to(ROOT).as_posix()] = value
    hashes[upstream_path.relative_to(ROOT).as_posix()] = digest(upstream_path.read_bytes())
    source_entries = archive_entries(SOURCES.read_bytes())
    source_paths = []
    for name, patch_name, original in OVERLAYS:
        relative = PREFIX + name + ".java"
        path = ROOT / "third_party/termux/recording/owned" / relative if name == "TerminalSession" else PATCH_ROOT / "src" / relative
        patch = ROOT / "third_party/termux/recording/owned-output-observer.patch" if name == "TerminalSession" else PATCH_ROOT / patch_name
        if name in ("TerminalRow", "TerminalBuffer", "TerminalEmulator"):
            path = ROOT / "third_party/termux/links/src" / relative
            patch = ROOT / "third_party/termux/links" / (name + ".patch")
        content = path.read_bytes()
        before = source_entries[relative] if original else b""
        expected = "".join(difflib.unified_diff(
            before.decode("utf-8").splitlines(True), content.decode("utf-8").splitlines(True),
            fromfile="a/" + relative if original else "/dev/null", tofile="b/" + relative,
        )).encode("utf-8")
        if patch.read_bytes() != expected:
            fail("exact source patch mismatch: " + name)
        for entry in (path, patch):
            hashes[entry.relative_to(ROOT).as_posix()] = digest(entry.read_bytes())
        source_paths.append(path)
    # Source ABI identity only: this recipe never loads/builds native code.
    abi = ROOT / "third_party/termux/native/OWNED_PROCESS_ABI.json"
    hashes[abi.relative_to(ROOT).as_posix()] = digest(abi.read_bytes())
    hashes[Path(__file__).resolve().relative_to(ROOT).as_posix()] = digest(Path(__file__).read_bytes())
    original_entries = archive_entries(ORIGINAL.read_bytes())
    if any(name.startswith("jni/") for name in original_entries):
        fail("original Java-only AAR unexpectedly contains native entries")
    original_classes = archive_entries(original_entries["classes.jar"])
    if not REPLACEMENTS - ADDITIONS <= original_classes.keys() or ADDITIONS & original_classes.keys():
        fail("reviewed original class scope changed")
    return hashes, source_paths, original_classes


def marker(hashes: dict[str, str]) -> dict[str, object]:
    return {"artifactKind": KIND, "releaseCompatible": False, "sourceInputSha256ByPath": hashes,
            "scope": "Host JVM tests only; no AAR, native, APK, device or stress validation"}


def validate_jar(raw: bytes, hashes: dict[str, str], original: dict[str, bytes]) -> dict[str, str]:
    entries = archive_entries(raw)
    if set(entries) != set(original) | ADDITIONS | {MARKER}:
        fail("TEST-ONLY JAR has an unexpected or missing entry")
    if entries[MARKER] != json_bytes(marker(hashes)):
        fail("missing, stale or non-TEST-ONLY identity marker")
    for name in set(original) - REPLACEMENTS:
        if entries[name] != original[name]:
            fail("unrelated original entry changed: " + name)
    for name in REPLACEMENTS:
        data = entries[name]
        if data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") != 52:
            fail("overlay is not Java 8 class bytecode: " + name)
    return {name: digest(data) for name, data in sorted(entries.items())}


def verify() -> None:
    hashes, _, original = reviewed_inputs()
    receipt = json.loads(RECEIPT.read_bytes())
    if receipt.get("schemaVersion") != 1 or receipt.get("artifactKind") != KIND or receipt.get("releaseCompatible") is not False:
        fail("receipt is not the dedicated TEST-ONLY schema")
    if receipt.get("sourceInputSha256ByPath") != hashes:
        fail("TEST-ONLY source inputs changed since compilation")
    if receipt.get("output", {}).get("path") != OUTPUT.relative_to(ROOT).as_posix():
        fail("TEST-ONLY output path changed")
    raw = OUTPUT.read_bytes()
    if receipt["output"].get("sha256") != digest(raw):
        fail("TEST-ONLY JAR hash mismatch")
    if receipt.get("entrySha256ByPath") != validate_jar(raw, hashes, original):
        fail("TEST-ONLY class/entry receipt mismatch")
    if receipt.get("compiler", {}).get("arguments") != COMPILER_ARGUMENTS or not receipt["compiler"].get("version", "").startswith("javac 17."):
        fail("TEST-ONLY compiler contract changed")
    if receipt.get("androidJar", {}).get("api") != 36 or receipt["androidJar"].get("sha256") != ANDROID_JAR_SHA256:
        fail("TEST-ONLY Android API input changed")
    print("TERMUX_TEST_ONLY_VERIFIED sha256=" + digest(raw) + " replacement_classes=" + str(len(REPLACEMENTS)) + " release_compatible=false")


def require_owned_output_directory() -> None:
    current = ROOT
    for component in OUTPUT_ROOT.relative_to(ROOT).parts:
        current = current / component
        if current.is_symlink():
            fail("refusing symlinked host-test output path")
        current.mkdir(exist_ok=True)
    for path in (OUTPUT, RECEIPT):
        if path.is_symlink() or (path.exists() and not path.is_file()):
            fail("refusing nonregular host-test output leaf")


def build(javac: Path, android_jar: Path) -> None:
    if not javac.is_absolute() or not javac.is_file() or not android_jar.is_file():
        fail("explicit local javac and Android API 36 android.jar paths are required")
    if digest(android_jar.read_bytes()) != ANDROID_JAR_SHA256:
        fail("Android API 36 android.jar does not match the reviewed input")
    hashes, source_paths, original = reviewed_inputs()
    environment = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
        environment.pop(key, None)
    version = subprocess.check_output([str(javac), "-version"], stderr=subprocess.STDOUT,
                                      text=True, env=environment, timeout=15).strip()
    if not version.startswith("javac 17."):
        fail("only JDK 17 is admitted")
    require_owned_output_directory()
    with tempfile.TemporaryDirectory(prefix="java-only-", dir=OUTPUT_ROOT) as directory:
        work = Path(directory)
        original_jar = work / "original-classes.jar"
        original_jar.write_bytes(archive_entries(ORIGINAL.read_bytes())["classes.jar"])
        compiled = work / "compiled"
        compiled.mkdir()
        empty_sourcepath = work / "empty-sourcepath"
        empty_sourcepath.mkdir()
        subprocess.run([str(javac), *COMPILER_ARGUMENTS, "-sourcepath", str(empty_sourcepath),
                        "-classpath", str(android_jar.resolve()) + os.pathsep + str(original_jar),
                        "-d", str(compiled), *map(str, source_paths)], check=True, env=environment, timeout=120)
        replacement = {p.relative_to(compiled).as_posix(): p.read_bytes() for p in compiled.rglob("*") if p.is_file()}
        if set(replacement) != REPLACEMENTS:
            fail("compiled overlay class scope changed: " + repr(sorted(replacement)))
        entries = dict(original)
        entries.update(replacement)
        entries[MARKER] = json_bytes(marker(hashes))
        raw = deterministic_jar(entries)
        entry_hashes = validate_jar(raw, hashes, original)
        # A concurrent source edit makes this build unusable, not a stale successful receipt.
        if reviewed_inputs()[0] != hashes:
            fail("source inputs changed during compilation")
        receipt = {"schemaVersion": 1, **marker(hashes),
                   "output": {"path": OUTPUT.relative_to(ROOT).as_posix(), "sha256": digest(raw)},
                   "entrySha256ByPath": entry_hashes,
                   "compiler": {"path": str(javac.resolve()), "version": version, "arguments": COMPILER_ARGUMENTS},
                   "androidJar": {"api": 36, "sha256": ANDROID_JAR_SHA256}}
        staged_jar = work / "TEST-ONLY.jar"
        staged_receipt = work / "TEST-ONLY.json"
        staged_jar.write_bytes(raw)
        staged_receipt.write_bytes(json_bytes(receipt))
        os.replace(staged_jar, OUTPUT)
        os.replace(staged_receipt, RECEIPT)
    verify()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--build", action="store_true")
    mode.add_argument("--verify-only", action="store_true")
    parser.add_argument("--javac", type=Path)
    parser.add_argument("--android-jar", type=Path)
    args = parser.parse_args()
    if args.verify_only:
        if args.javac is not None or args.android_jar is not None:
            parser.error("verify-only does not invoke or accept compiler inputs")
        verify()
    else:
        if args.javac is None or args.android_jar is None:
            parser.error("--build requires explicit --javac and --android-jar")
        build(args.javac, args.android_jar)


if __name__ == "__main__":
    main()
