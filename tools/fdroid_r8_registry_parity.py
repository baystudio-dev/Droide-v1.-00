#!/usr/bin/env python3
"""Opt-in opaque byte parity for the two existing R8 9.1.29 pins only.

Default: read local Gradle verification metadata, without network or writes.
Explicit downloads require a new external directory and an outer OS process
supervisor with a 180-second deadline. The per-file POSIX signal timer is not
an OS hard deadline during blocking C/DNS calls. Nothing is unpacked, executed,
installed or substituted; downloaded POM data is never parsed. Availability,
source-build provenance and F-Droid acceptance are not inferred from a URL.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import errno
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import sys
from typing import NamedTuple
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


# Fixed sibling import also works under Python -I, without changing sys.path or
# the existing eight-artifact helper's inventory, configuration or globals.
_HELPER = Path(__file__).resolve(strict=True)
_SPEC = importlib.util.spec_from_file_location(
    "_droide_r8_registry_utilities", _HELPER.with_name("fdroid_maven_registry_parity.py"))
assert _SPEC is not None and _SPEC.loader is not None
utilities = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = utilities
_PREVIOUS_NO_BYTECODE = sys.dont_write_bytecode
try:
    sys.dont_write_bytecode = True
    _SPEC.loader.exec_module(utilities)
finally:
    sys.dont_write_bytecode = _PREVIOUS_NO_BYTECODE
ParityError = utilities.ParityError
checked_output = utilities.checked_output
make_opener = utilities.make_opener
download_deadline = utilities.download_deadline
error_message = utilities.error_message

ROOT = _HELPER.parents[1]
GRADLE_PINS = "gradle/verification-metadata.xml"
COORDINATE = "com.android.tools:r8:9.1.29"
GOOGLE = "https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.1.29/"
XML_NAMESPACE = "https://schema.gradle.org/dependency-verification"
NS = {"g": XML_NAMESPACE}
MAX_METADATA_BYTES = 2 * 1024 * 1024
JAR_MAX_BYTES = 64 * 1024 * 1024
POM_MAX_BYTES = 64 * 1024
MAX_TOTAL_BYTES = 67174400
CHUNK_BYTES = 64 * 1024
SOCKET_TIMEOUT_SECONDS = 15
ARTIFACT_DEADLINE_SECONDS = 60
OUTER_DEADLINE_SECONDS = 180
new_digest = hashlib.sha256


class Artifact(NamedTuple):
    filename: str
    sha256: str
    maximum_bytes: int

    @property
    def official_url(self) -> str:
        return GOOGLE + self.filename


ARTIFACTS = (
    Artifact("r8-9.1.29.jar", "9a7d1e9e4c2d3ee05482b9695df5f4d8f92c22ebb74603d6be4eab0505467f1a", JAR_MAX_BYTES),
    Artifact("r8-9.1.29.pom", "136af50c91e46198fc439f2e233094edd39c5d68df88f33045526b872a4a6e06", POM_MAX_BYTES),
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ParityError(message)


def open_directory(path: Path) -> int:
    """Open a canonical absolute directory without following any symlink."""
    require(path.anchor == "/" and ".." not in path.parts,
            "Directory must be canonical and absolute without symlinks")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    descriptor = os.open("/", flags)
    try:
        for component in path.parts[1:]:
            child = os.open(component, flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        return descriptor
    except OSError as error:
        os.close(descriptor)
        if error.errno in (errno.ELOOP, errno.ENOTDIR):
            raise ParityError("Directory must not contain symlinks or non-directories") from None
        raise


def directory_identity(status) -> tuple[int, int]:
    return status.st_dev, status.st_ino


def assert_directory(path: Path, descriptor: int) -> None:
    """Path lookup is only an identity check; it never supplies a write FD."""
    current = open_directory(path)
    try:
        require(directory_identity(os.fstat(current)) == directory_identity(os.fstat(descriptor)),
                "Directory identity changed during the comparison")
    finally:
        os.close(current)


@contextmanager
def anchored_directory(path: Path, descriptor: int | None = None):
    owned = descriptor is None
    if owned:
        descriptor = open_directory(path)
    try:
        assert_directory(path, descriptor)
        yield descriptor
    finally:
        if owned:
            os.close(descriptor)


@contextmanager
def new_output_directory(output: Path):
    """Create once through an anchored parent, retaining the new directory FD."""
    with anchored_directory(output.parent) as parent:
        os.mkdir(output.name, mode=0o700, dir_fd=parent)
        descriptor = os.open(output.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                             dir_fd=parent)
    try:
        assert_directory(output, descriptor)
        yield descriptor
    finally:
        os.close(descriptor)


def file_identity(status) -> tuple:
    return (status.st_dev, status.st_ino, status.st_mode, status.st_size,
            status.st_mtime_ns, status.st_ctime_ns)


def read_input(root: Path, relative: str, limit: int, capture: bool = False, *,
               root_fd: int | None = None) -> tuple[dict, bytes]:
    """Bounded regular-file read anchored through each no-follow directory FD."""
    parts = Path(relative).parts
    require(parts and not Path(relative).is_absolute() and ".." not in parts,
            "Input must stay inside its anchored directory without symlinks")
    with anchored_directory(root, root_fd) as anchor:
        directories = [anchor]
        try:
            for component in parts[:-1]:
                directories.append(os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW |
                                           os.O_CLOEXEC, dir_fd=directories[-1]))
            descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC,
                                 dir_fd=directories[-1])
            with os.fdopen(descriptor, "rb") as stream:
                before = os.fstat(stream.fileno())
                require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= limit,
                        "Input is not a nonempty regular file within its byte bound: " + relative)
                digest, size, blocks = hashlib.sha256(), 0, []
                while block := stream.read(min(CHUNK_BYTES, limit - size + 1)):
                    size += len(block)
                    require(size <= limit, "Input exceeded its byte bound: " + relative)
                    digest.update(block)
                    if capture:
                        blocks.append(block)
                after = os.fstat(stream.fileno())
                named = os.stat(parts[-1], dir_fd=directories[-1], follow_symlinks=False)
                require(file_identity(before) == file_identity(after) == file_identity(named) and
                        size == before.st_size, "Input changed while it was being read: " + relative)
            for index, component in enumerate(parts[:-1]):
                named = os.stat(component, dir_fd=directories[index], follow_symlinks=False)
                require(stat.S_ISDIR(named.st_mode) and
                        directory_identity(named) == directory_identity(os.fstat(directories[index + 1])),
                        "Input parent changed while it was being read: " + relative)
            assert_directory(root, anchor)
        except OSError as error:
            if error.errno in (errno.ELOOP, errno.ENOTDIR):
                raise ParityError("Input must stay inside its anchored directory without symlinks") from None
            raise
        finally:
            for descriptor in reversed(directories[1:]):
                os.close(descriptor)
    return {"path": relative, "bytes": size, "sha256": digest.hexdigest()}, b"".join(blocks)


def check_inputs(root: Path = ROOT, *, root_fd: int | None = None) -> dict:
    """Verify only the exact local XML pins; no local/cached R8 binary needed."""
    if root_fd is None:
        root = root.resolve(strict=True)
    record, raw = read_input(root, GRADLE_PINS, MAX_METADATA_BYTES, True, root_fd=root_fd)
    try:
        xml = raw.decode("utf-8")
        require("\0" not in xml and "<!DOCTYPE" not in xml.upper() and "<!ENTITY" not in xml.upper(),
                "DTD/entities and non-UTF-8 XML are not accepted")
        tree = ET.fromstring(xml)
    except (UnicodeError, ET.ParseError) as error:
        raise ParityError("Gradle pin metadata is not valid UTF-8 XML") from error
    require(tree.tag == f"{{{XML_NAMESPACE}}}verification-metadata", "Unexpected Gradle pin metadata schema")
    containers = tree.findall("g:components", NS)
    require(len(containers) == 1, "Expected exactly one Gradle components container")
    matches = [node for node in tree.iter(f"{{{XML_NAMESPACE}}}component")
               if node.get("group") == "com.android.tools" and node.get("name") == "r8"]
    require(len(matches) == 1 and matches[0] in list(containers[0]) and
            matches[0].attrib == {"group": "com.android.tools", "name": "r8", "version": "9.1.29"},
            "Expected exactly one R8 component at the fixed version 9.1.29")
    component = matches[0]
    require(len(component) == 2 and all(node.tag == f"{{{XML_NAMESPACE}}}artifact" for node in component),
            "R8 must have exactly the reviewed JAR and POM artifact pins")
    for item in ARTIFACTS:
        artifacts = [node for node in component if node.get("name") == item.filename]
        require(len(artifacts) == 1 and artifacts[0].attrib == {"name": item.filename},
                "Missing, duplicate or ambiguous R8 artifact pin: " + item.filename)
        artifact = artifacts[0]
        require(len(artifact) == 1 and artifact[0].tag == f"{{{XML_NAMESPACE}}}sha256" and
                artifact[0].get("value") == item.sha256 and len(artifact[0]) == 0 and
                set(artifact[0].attrib) <= {"value", "origin", "reason"},
                "R8 SHA-256 differs or admits alternative bytes: " + item.filename)
    return {"status": "LOCAL_R8_PINS_VERIFIED", "coordinate": COORDINATE,
            "networkAttempted": False, "registryParityVerified": False,
            "pinMetadata": record,
            "artifacts": [{"file": item.filename, "officialUrl": item.official_url,
                           "expectedSha256": item.sha256, "maximumBytes": item.maximum_bytes}
                          for item in ARTIFACTS]}


def validate_target(url: str, item: Artifact) -> None:
    require(item in ARTIFACTS and url == item.official_url,
            "Only the exact two fixed R8 official endpoints are permitted")
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == "https" and parsed.netloc == "dl.google.com" and
            parsed.username is None and parsed.password is None and not parsed.query and not parsed.fragment,
            "R8 endpoint must be canonical credential-free HTTPS")


def response_length(response, limit: int) -> int:
    require(response.status == 200, "Expected a complete HTTP 200 response")
    require(not response.headers.get_all("Transfer-Encoding", []), "HTTP transfer encoding is not accepted")
    encodings = response.headers.get_all("Content-Encoding", [])
    require(not encodings or (len(encodings) == 1 and encodings[0].strip().lower() == "identity"),
            "Only unambiguous identity HTTP content encoding is accepted")
    lengths = response.headers.get_all("Content-Length", [])
    require(len(lengths) == 1 and re.fullmatch(r"[1-9][0-9]{0,9}", lengths[0]) is not None,
            "Expected exactly one positive canonical Content-Length")
    length = int(lengths[0])
    require(length <= limit, "Content-Length exceeds the per-file or remaining cumulative byte bound")
    return length


def download_file(item: Artifact, destination: Path, opener, remaining_bytes: int, *,
                  output_fd: int | None = None) -> dict:
    validate_target(item.official_url, item)
    require(destination.name == item.filename + ".download", "Unexpected fixed R8 output filename")
    require(type(remaining_bytes) is int and 0 < remaining_bytes <= MAX_TOTAL_BYTES,
            "Invalid remaining cumulative download budget")
    result = {"file": destination.name, "officialUrl": item.official_url,
              "expectedSha256": item.sha256, "maximumBytes": item.maximum_bytes,
              "status": "FAILED", "networkAttempted": False, "fileCreated": False,
              "bytesReceived": 0, "completeBodyReceived": False, "sha256": None,
              "matchesPinnedBytes": False, "redirectsFollowed": 0}
    digest = new_digest()
    try:
        with anchored_directory(destination.parent, output_fd) as anchor:
            with download_deadline():
                request = urllib.request.Request(item.official_url, method="GET", headers={
                    "Accept-Encoding": "identity", "User-Agent": "Droide-R8-Registry-Parity/1"})
                result["networkAttempted"] = True
                try:
                    response = opener.open(request, timeout=SOCKET_TIMEOUT_SECONDS)
                except urllib.error.HTTPError as error:
                    code = error.code
                    error.close()
                    if 300 <= code < 400:
                        raise ParityError("Every HTTP redirect is forbidden; no request was followed") from None
                    raise ParityError(f"Official Google Maven endpoint returned HTTP {code}") from None
                except (OSError, urllib.error.URLError, ValueError):
                    raise ParityError("Official Google Maven request failed; no retry or fallback attempted") from None
                with response:
                    validate_target(response.geturl(), item)
                    length = response_length(response, min(item.maximum_bytes, remaining_bytes))
                    result["declaredBytes"] = length
                    assert_directory(destination.parent, anchor)
                    descriptor = os.open(destination.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL |
                                         os.O_NOFOLLOW | os.O_CLOEXEC, 0o600, dir_fd=anchor)
                    result["fileCreated"] = True
                    with os.fdopen(descriptor, "wb") as stream:
                        # Content-Length frames the body; never request bytes beyond
                        # that validated length or the file/cumulative budget.
                        while result["bytesReceived"] < length:
                            requested = min(CHUNK_BYTES, length - result["bytesReceived"])
                            block = response.read(requested)
                            require(isinstance(block, bytes) and 0 < len(block) <= requested,
                                    "HTTP body was truncated or violated its bounded read")
                            result["bytesReceived"] += len(block)
                            digest.update(block)
                            stream.write(block)
                    result["completeBodyReceived"] = True
            result["sha256"] = digest.hexdigest()
            saved, _ = read_input(destination.parent, destination.name, item.maximum_bytes, root_fd=anchor)
            require(saved["bytes"] == result["bytesReceived"] and saved["sha256"] == result["sha256"],
                    "Opaque output bytes changed after their streamed comparison")
            require(result["sha256"] == item.sha256,
                    "Google Maven bytes differ from the existing pin: " + item.filename)
        result["matchesPinnedBytes"] = True
        result["status"] = "MATCH"
    except (ParityError, OSError, urllib.error.URLError, http.client.HTTPException, ValueError) as error:
        result["error"] = error_message(error)
        if not result["completeBodyReceived"] and result["bytesReceived"]:
            result["partialBodySha256"] = digest.hexdigest()
    return result


def report_limits() -> dict:
    return {"jarMaximumBytes": JAR_MAX_BYTES, "pomMaximumBytes": POM_MAX_BYTES,
            "maximumTotalBytes": MAX_TOTAL_BYTES, "streamChunkBytes": CHUNK_BYTES,
            "socketTimeoutSeconds": SOCKET_TIMEOUT_SECONDS,
            "signalDeadlinePerArtifactSeconds": ARTIFACT_DEADLINE_SECONDS,
            "requiredOuterProcessDeadlineSeconds": OUTER_DEADLINE_SECONDS,
            "externalProcessSupervisionRequired": True, "externalProcessSupervisionVerified": False,
            "osHardPerArtifactDeadlineGuaranteed": False, "maximumRedirects": 0,
            "retries": 0, "fallbackEndpoints": 0}


def write_report(output: Path, report: dict, *, output_fd: int) -> None:
    """Publish only after the exclusive pending receipt has closed successfully."""
    assert_directory(output, output_fd)
    pending = "PARITY.pending.json"
    descriptor = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                         0o600, dir_fd=output_fd)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
        written = os.fstat(stream.fileno())
    assert_directory(output, output_fd)
    require(file_identity(written) == file_identity(os.stat(pending, dir_fd=output_fd, follow_symlinks=False)),
            "Pending receipt changed before publication")
    # link() is no-overwrite. Leave the pending name as a diagnostic hard link:
    # no fallible cleanup after publication may retroactively report failure.
    os.link(pending, "PARITY.json", src_dir_fd=output_fd, dst_dir_fd=output_fd, follow_symlinks=False)


def download_and_compare(output_dir: Path, root: Path = ROOT) -> dict:
    root = root.resolve(strict=True)
    with anchored_directory(root) as root_fd:
        before = check_inputs(root, root_fd=root_fd)
        output = checked_output(output_dir, root)
        with new_output_directory(output) as output_fd:
            report = {"status": "FAILED", "scope": "fixed-r8-9.1.29-google-maven-byte-parity-only",
                      "coordinate": COORDINATE, "networkAttempted": False, "registryParityVerified": False,
                      "sourceBuildVerified": False, "fdroidAcceptanceVerified": False,
                      "repositoryWritesPerformed": False, "downloadedArtifactsExecuted": False,
                      "downloadedArchivesUnpacked": False, "downloadedPomParsed": False,
                      "limits": report_limits(), "inputsBefore": before, "downloads": [], "errors": []}
            try:
                opener = make_opener()
                remaining = MAX_TOTAL_BYTES
                for item in ARTIFACTS:
                    result = download_file(item, output / (item.filename + ".download"), opener, remaining,
                                           output_fd=output_fd)
                    report["downloads"].append(result)
                    report["networkAttempted"] |= result["networkAttempted"]
                    remaining -= result["bytesReceived"]
                    require(remaining >= 0, "Cumulative byte bound exceeded")
                    require(result["status"] == "MATCH" and result["matchesPinnedBytes"],
                            result.get("error", "R8 artifact comparison did not succeed"))
            except (ParityError, OSError, urllib.error.URLError, http.client.HTTPException, ValueError) as error:
                report["errors"].append(error_message(error))
            finally:
                try:
                    after = check_inputs(root, root_fd=root_fd)
                    report["inputsAfter"] = after
                    require(after == before, "Gradle pin metadata changed during the comparison")
                except (ParityError, OSError, ValueError) as error:
                    report["errors"].append(error_message(error))
            report["bytesReceived"] = sum(row["bytesReceived"] for row in report["downloads"])
            if not report["errors"] and len(report["downloads"]) == 2:
                report["status"] = "BOTH_R8_REGISTRY_BYTES_MATCH_PINS"
                report["registryParityVerified"] = True
            try:
                write_report(output, report, output_fd=output_fd)
            except (ParityError, OSError, ValueError) as error:
                report["status"], report["registryParityVerified"] = "FAILED", False
                report["errors"].append("Could not write the parity receipt: " + error_message(error))
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--check-inputs", action="store_true", help="Read local pins only (default)")
    modes.add_argument("--download-and-compare", action="store_true", help="Opt in to the two fixed data-only requests")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--output-dir", type=Path, help="New absolute directory outside the checkout; downloads only")
    args = parser.parse_args(argv)
    if args.download_and_compare != (args.output_dir is not None):
        parser.error("--download-and-compare and --output-dir must be supplied together")
    try:
        report = (download_and_compare(args.output_dir, args.root) if args.download_and_compare
                  else check_inputs(args.root))
    except (ParityError, OSError, ValueError) as error:
        report = {"status": "FAILED", "registryParityVerified": False, "errors": [error_message(error)]}
    print(json.dumps(report, indent=2, sort_keys=True))
    return 1 if report["status"] == "FAILED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
