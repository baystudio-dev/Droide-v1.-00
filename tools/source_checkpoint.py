#!/usr/bin/env python3
"""Export a clean Git tree as full source, or restore/verify that source without running it."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import time
import zipfile


def safe_path(name):
    if (not name or PurePosixPath(name).is_absolute() or "\\" in name or ":" in name
            or any(part in ("", ".", "..", ".git") for part in name.split("/"))):
        raise ValueError("Unsafe archive path: " + name)
    return name


def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def digest(data, mode):
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "mode": mode}


def file_sha256(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def export(args):
    source = Path(args.source).resolve(strict=True)
    output = Path(args.archive).absolute()
    if source == output or source in output.parents:
        raise ValueError("Put the archive outside the source tree")
    if output.exists():
        raise ValueError("Never overwrite an existing checkpoint")
    if not re.fullmatch(r"R[0-9]+", args.checkpoint):
        raise ValueError("Expected a checkpoint such as R103")

    def git(*command):
        return subprocess.check_output(["git", "-c", "core.hooksPath=/dev/null", *command], cwd=source)

    if git("status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Commit all project files before export; working source must be clean")
    commit = git("rev-parse", "HEAD").decode().strip()
    tree = git("rev-parse", "HEAD^{tree}").decode().strip()
    timestamp = max(int(git("show", "-s", "--format=%ct", "HEAD")), 315532800)
    if git("rev-parse", "--show-object-format").strip() != b"sha1":
        raise ValueError("This verifier currently expects Git SHA-1 object identities")
    prefix = "Droide-" + args.checkpoint + "-source"
    directories = json.loads(Path(args.directory_modes).read_text()) if args.directory_modes else {}
    for name, mode in directories.items():
        safe_path(name)
        if type(mode) is not int or not 0 <= mode <= 0o777:
            raise ValueError("Invalid directory mode")
    files = {}
    entries = []
    for line in git("ls-tree", "-rz", "--full-tree", "HEAD").split(b"\0"):
        if not line:
            continue
        metadata, name_bytes = line.split(b"\t", 1)
        mode, kind, oid = metadata.decode().split()
        name = safe_path(name_bytes.decode("utf-8"))
        if kind != "blob" or mode not in ("100644", "100755"):
            raise ValueError("Only ordinary tracked files are admitted: " + name)
        entries.append((name, 0o755 if mode == "100755" else 0o644, oid))
        for parent in PurePosixPath(name).parents:
            if str(parent) != ".":
                directories.setdefault(str(parent), 0o755)

    payload = {}
    report = safe_path(args.report)
    if report not in {name for name, _, _ in entries}:
        raise ValueError("The checkpoint report must be committed with the source")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        def put(name, data, mode=0o644):
            safe_path(name)
            if name in payload:
                raise ValueError("Duplicate payload")
            info = zipfile.ZipInfo(name, time.gmtime(timestamp)[:6])
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
            payload[name] = digest(data, mode)

        for name, mode, oid in entries:
            path = source / name
            if not stat.S_ISREG(path.lstat().st_mode):
                raise ValueError("Tracked path was replaced: " + name)
            data = path.read_bytes()
            blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
            if blob != oid:
                raise ValueError("Working file does not match committed content: " + name)
            files[name] = digest(data, mode)
            put(prefix + "/" + name, data, mode)

        identity = {"checkpoint": args.checkpoint, "commit": commit, "tree": tree, "source_root": prefix,
                    "source_files": len(files), "directory_modes": len(directories), "source_clean": True,
                    "parents": git("show", "-s", "--format=%P", "HEAD").decode().split(), "report": report,
                    "requires_prior_checkpoint": False, "git_history_included": False,
                    "apk": False, "library_delivery": "NOT_UPLOADED", "external_build_dependencies": True}
        put("EXPORT-METADATA/SOURCE-IDENTITY.json", encoded(identity))
        put("EXPORT-METADATA/SOURCE-MANIFEST.json", encoded(files))
        put("EXPORT-METADATA/DIRECTORY-MODES.json", encoded(directories))
        put("source_checkpoint.py", Path(__file__).read_bytes())
        instructions = f"""# Droide {args.checkpoint}: complete project source

Commit: `{commit}`\nTree: `{tree}`

This archive contains every file in this committed project tree, including application source,
resources, project-owned libraries, tests, tools, legal notices and historical evidence.
It is not an overlay or a progress-only report. No earlier archive is required.
Build caches, installed SDK/toolchains and Git internals are not project payloads.
No APK or native/device acceptance is implied. See `{report}`
in the source for the fresh verification scope. Older reports retain their historical meaning.

Extract the standalone helper into a separate directory, review it, then restore with:

    python3 source_checkpoint.py restore ARCHIVE.zip EXPECTED_SHA256 NEW_DIRECTORY

Use a new directory; existing projects are never overwritten. This verifies the archive,
every file's SHA-256 and ordinary mode, and restores empty directories and executable bits.
It does not execute Gradle, project scripts or bundled third-party code.
The project is `{prefix}/`. No Git history is included; commit/tree identity is recorded.

Library ChatGPT delivery is pending. Creating this ZIP does not prove an upload.
"""
        put("START-HERE.md", instructions.encode())
        # The manifest lists all other members; the externally recorded ZIP SHA binds it.
        put("PAYLOAD-MANIFEST.json", encoded(payload))
    if git("rev-parse", "HEAD").decode().strip() != commit or git("status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Source changed during export; discard this unaccepted artifact")
    receipt = dict(identity, archive=str(output), archive_sha256=file_sha256(output), archive_bytes=output.stat().st_size)
    Path(str(output) + ".json").write_bytes(encoded(receipt))
    print(json.dumps(receipt, indent=2))


def restore(args):
    archive_path = Path(args.archive)
    destination = Path(args.destination).absolute()
    if not re.fullmatch(r"[0-9a-f]{64}", args.sha256) or file_sha256(archive_path) != args.sha256:
        raise ValueError("Archive SHA-256 mismatch")
    if destination.exists() or any(p.is_symlink() for p in [destination, *destination.parents]):
        raise ValueError("Choose a new destination without symlink ancestors")
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive members")
        for name in names:
            safe_path(name)
        payload = json.loads(archive.read("PAYLOAD-MANIFEST.json"))
        if set(names) != set(payload) | {"PAYLOAD-MANIFEST.json"}:
            raise ValueError("Payload membership differs")
        for name in names:
            info = archive.getinfo(name)
            mode = info.external_attr >> 16
            if not stat.S_ISREG(mode) or stat.S_IMODE(mode) > 0o777:
                raise ValueError("Unexpected file type/mode")
            data = archive.read(name)
            if name in payload and digest(data, stat.S_IMODE(mode)) != payload[name]:
                raise ValueError("Payload mismatch: " + name)
        identity = json.loads(archive.read("EXPORT-METADATA/SOURCE-IDENTITY.json"))
        prefix = safe_path(identity["source_root"])
        if "/" in prefix:
            raise ValueError("Invalid source root")
        files = json.loads(archive.read("EXPORT-METADATA/SOURCE-MANIFEST.json"))
        directories = json.loads(archive.read("EXPORT-METADATA/DIRECTORY-MODES.json"))
        if files != {name[len(prefix) + 1:]: value for name, value in payload.items() if name.startswith(prefix + "/")}:
            raise ValueError("Source manifest mismatch")
        for name, mode in directories.items():
            safe_path(name)
            if type(mode) is not int or not 0 <= mode <= 0o777:
                raise ValueError("Invalid directory mode")
        destination.mkdir(parents=True)
        root = destination / prefix
        for name in sorted(directories, key=lambda n: len(PurePosixPath(n).parts)):
            (root / name).mkdir(parents=True, exist_ok=True)
        for name in names:
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
            target.chmod(stat.S_IMODE(archive.getinfo(name).external_attr >> 16))
        for name, mode in directories.items():
            (root / name).chmod(mode)
    actual_files, actual_directories = {}, {}
    for path in root.rglob("*"):
        name = path.relative_to(root).as_posix()
        attributes = path.lstat()
        mode = stat.S_IMODE(attributes.st_mode)
        if stat.S_ISREG(attributes.st_mode):
            actual_files[name] = digest(path.read_bytes(), mode)
        elif stat.S_ISDIR(attributes.st_mode):
            actual_directories[name] = mode
        else:
            raise ValueError("Unexpected restored entry")
    if actual_files != files or actual_directories != directories:
        raise ValueError("Restored source does not match")
    print(json.dumps({"status": "FULL_SOURCE_RESTORED_AND_VERIFIED", "commit": identity["commit"],
                      "source_files": len(files), "directory_modes": len(directories),
                      "source_scripts_executed": False, "destination": str(root)}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("export")
    create.add_argument("source"); create.add_argument("checkpoint"); create.add_argument("archive")
    create.add_argument("--directory-modes")
    create.add_argument("--report", required=True)
    unpack = commands.add_parser("restore")
    unpack.add_argument("archive"); unpack.add_argument("sha256"); unpack.add_argument("destination")
    args = parser.parse_args()
    (export if args.command == "export" else restore)(args)


if __name__ == "__main__":
    main()
