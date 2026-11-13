#!/usr/bin/env python3
"""Opt-in, data-only byte comparison of eight existing runtime dependencies.

The default is an offline, read-only pin check. Explicit downloads require a
fresh directory outside the checkout and trusted external process supervision.
The signal timer is not an OS hard deadline during blocking C calls such as DNS.
Nothing is unpacked, executed, installed,
or substituted into the build. Registry byte parity is NOT a source-build proof
or an F-Droid acceptance decision. No arbitrary URL/version/digest is accepted.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import signal
import ssl
import stat
import threading
from typing import NamedTuple
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
EMBEDDED = "third_party/kadb/EMBEDDED_SHA256SUMS.txt"
GRADLE_PINS = "gradle/verification-metadata.xml"
XZ_PROVENANCE = "third_party/xz/PROVENANCE.json"
CENTRAL = "https://repo.maven.apache.org/maven2/"
GOOGLE = "https://dl.google.com/dl/android/maven2/"
MAX_ARTIFACT_BYTES = 32 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_METADATA_BYTES = 2 * 1024 * 1024
CHUNK_BYTES = 64 * 1024
SOCKET_TIMEOUT_SECONDS = 15
ARTIFACT_DEADLINE_SECONDS = 60
MAX_REDIRECTS = 3
XML_NAMESPACE = "https://schema.gradle.org/dependency-verification"
NS = {"g": XML_NAMESPACE}


class Artifact(NamedTuple):
    coordinate: str
    local_path: str
    sha256: str
    official_url: str
    embedded: bool = True


# These are the existing bundled pins, not hashes learned from a download.
# The seven Maven mirrors also have independent repository pin cross-checks.
ARTIFACTS = (
    Artifact("com.squareup.okio:okio-jvm:3.17.0",
             "third_party/maven/com/squareup/okio/okio-jvm/3.17.0/okio-jvm-3.17.0.jar",
             "3b13dcc3e1573047c2d507fc4f7fcb0bb0cbcd9d395da21d20e09406e2cd6a73",
             CENTRAL + "com/squareup/okio/okio-jvm/3.17.0/okio-jvm-3.17.0.jar"),
    Artifact("org.bouncycastle:bcpkix-jdk18on:1.84",
             "third_party/maven/org/bouncycastle/bcpkix-jdk18on/1.84/bcpkix-jdk18on-1.84.jar",
             "c87f16ed9e5ec61bc94151e9f3646ac44e50cd448121ce84367fa4b7ec7ec1bb",
             CENTRAL + "org/bouncycastle/bcpkix-jdk18on/1.84/bcpkix-jdk18on-1.84.jar"),
    Artifact("org.bouncycastle:bcprov-jdk18on:1.84",
             "third_party/maven/org/bouncycastle/bcprov-jdk18on/1.84/bcprov-jdk18on-1.84.jar",
             "64d6c5a6121fcd927152dd182cbed39afe0fda641a970d9bcc0c9cb1858b2731",
             CENTRAL + "org/bouncycastle/bcprov-jdk18on/1.84/bcprov-jdk18on-1.84.jar"),
    Artifact("org.bouncycastle:bcutil-jdk18on:1.84",
             "third_party/maven/org/bouncycastle/bcutil-jdk18on/1.84/bcutil-jdk18on-1.84.jar",
             "b374e16963421fb9cfb01cc20d7ad8fd2f8b8188e3eef0ec0a8965e245f7619a",
             CENTRAL + "org/bouncycastle/bcutil-jdk18on/1.84/bcutil-jdk18on-1.84.jar"),
    Artifact("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
             "third_party/maven/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.jar",
             "d1d75aa01dffbb4d1c520e67e4c4e7f5f6174718e7cb4632412503f2f0e604fa",
             CENTRAL + "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.jar"),
    Artifact("androidx.documentfile:documentfile:1.1.0",
             "third_party/maven/androidx/documentfile/documentfile/1.1.0/documentfile-1.1.0.aar",
             "684e0ffcb21a660c939d1bb814521f42c4bd74816f4a117291fe6a101cf3a822",
             GOOGLE + "androidx/documentfile/documentfile/1.1.0/documentfile-1.1.0.aar"),
    Artifact("org.lsposed.hiddenapibypass:hiddenapibypass:6.1",
             "third_party/maven/org/lsposed/hiddenapibypass/hiddenapibypass/6.1/hiddenapibypass-6.1.aar",
             "e3161dd21c97a4540b1698a33f7062aeaa1450008e1e2176070e5380f7a6324c",
             CENTRAL + "org/lsposed/hiddenapibypass/hiddenapibypass/6.1/hiddenapibypass-6.1.aar"),
    Artifact("org.tukaani:xz:1.12", "app/libs/xz-1.12.jar",
             "3e158a87bd73d8afb4b6e8239c013b7d049c48563f45860ce99cd2e448cf4a6b",
             CENTRAL + "org/tukaani/xz/1.12/xz-1.12.jar", False),
)


class ParityError(RuntimeError):
    """A fixed-input, download-bound, or parity precondition failed."""


def validate_inventory() -> None:
    if len(ARTIFACTS) != 8 or len({a.coordinate for a in ARTIFACTS}) != 8:
        raise ParityError("The reviewed inventory must contain exactly eight unique coordinates")
    if sum(a.embedded for a in ARTIFACTS) != 7:
        raise ParityError("Expected seven embedded Maven mirrors and the direct XZ JAR")
    for item in ARTIFACTS:
        group, name, version = item.coordinate.split(":")
        extension = Path(item.local_path).suffix
        filename = f"{name}-{version}{extension}"
        prefix = GOOGLE if group == "androidx.documentfile" else CENTRAL
        expected = prefix + f"{group.replace('.', '/')}/{name}/{version}/{filename}"
        if item.official_url != expected or Path(item.local_path).name != filename:
            raise ParityError("An inventory URL/path differs from the fixed Maven coordinate")
        if extension not in (".aar", ".jar") or not re.fullmatch(r"[0-9a-f]{64}", item.sha256):
            raise ParityError("Invalid reviewed artifact format or SHA-256 pin")


def read_input(root: Path, relative: str, limit: int, capture: bool = False) -> tuple[dict, bytes]:
    """Bounded, stable regular-file read; no archive interpretation."""
    path = root / relative
    if Path(relative).is_absolute() or ".." in Path(relative).parts or path.resolve() != path:
        raise ParityError(f"Input must stay inside the checkout without symlinks: {relative}")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= limit:
            raise ParityError(f"Input is not a nonempty regular file within its byte bound: {relative}")
        digest, size, blocks = hashlib.sha256(), 0, []
        while block := stream.read(min(CHUNK_BYTES, limit - size + 1)):
            size += len(block)
            if size > limit:
                raise ParityError(f"Input exceeded its byte bound: {relative}")
            digest.update(block)
            if capture:
                blocks.append(block)
        after = os.fstat(stream.fileno())
        named = path.lstat()
        identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        if identity(before) != identity(after) or identity(after) != identity(named) or size != before.st_size:
            raise ParityError(f"Input changed while it was being read: {relative}")
    return {"path": relative, "bytes": size, "sha256": digest.hexdigest()}, b"".join(blocks)


def embedded_pins(data: bytes) -> dict[str, str]:
    result = {}
    for line in data.decode("utf-8").splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r"([0-9a-f]{64})  ([^\r\n]+)", line)
        if not match or match[2] in result:
            raise ParityError("Malformed or duplicate EMBEDDED_SHA256SUMS entry")
        result[match[2]] = match[1]
    return result


def check_gradle_pin(tree: ET.Element, item: Artifact) -> None:
    group, name, version = item.coordinate.split(":")
    matches = [c for c in tree.findall("g:components/g:component", NS)
               if (c.get("group"), c.get("name"), c.get("version")) == (group, name, version)]
    if len(matches) != 1:
        raise ParityError(f"Missing/duplicate Gradle component pin: {item.coordinate}")
    artifacts = [a for a in matches[0].findall("g:artifact", NS)
                 if a.get("name") == Path(item.local_path).name]
    if len(artifacts) != 1:
        raise ParityError(f"Missing/duplicate Gradle artifact pin: {item.coordinate}")
    pins = artifacts[0].findall("g:sha256", NS)
    if len(pins) != 1 or pins[0].get("value") != item.sha256 or len(pins[0]):
        raise ParityError(f"Gradle SHA-256 differs or admits alternative bytes: {item.coordinate}")


def check_inputs(root: Path = ROOT) -> dict:
    root = root.resolve(strict=True)
    validate_inventory()
    metadata = {}
    contents = {}
    for relative in (EMBEDDED, GRADLE_PINS, XZ_PROVENANCE):
        metadata[relative], contents[relative] = read_input(root, relative, MAX_METADATA_BYTES, True)
    embedded = embedded_pins(contents[EMBEDDED])
    xml = contents[GRADLE_PINS]
    if b"<!DOCTYPE" in xml.upper() or b"<!ENTITY" in xml.upper():
        raise ParityError("DTD/entities are not accepted in Gradle verification metadata")
    try:
        tree = ET.fromstring(xml)
        xz = json.loads(contents[XZ_PROVENANCE])
    except (ET.ParseError, ValueError) as error:
        raise ParityError("Pin metadata could not be parsed") from error
    if tree.tag != f"{{{XML_NAMESPACE}}}verification-metadata" or not isinstance(xz, dict):
        raise ParityError("Unexpected pin metadata schema")
    results = []
    for item in ARTIFACTS:
        if item.embedded:
            if embedded.get(item.local_path) != item.sha256:
                raise ParityError(f"Embedded SHA-256 differs from the reviewed pin: {item.coordinate}")
            check_gradle_pin(tree, item)
            pin_sources = [EMBEDDED, GRADLE_PINS]
        else:
            if xz.get("version") != "1.12" or xz.get("jar_sha256") != item.sha256:
                raise ParityError("XZ provenance differs from the reviewed version/SHA-256")
            pin_sources = [XZ_PROVENANCE]
        local, _ = read_input(root, item.local_path, MAX_ARTIFACT_BYTES)
        if local["sha256"] != item.sha256:
            raise ParityError(f"Local artifact SHA-256 differs from the reviewed pin: {item.coordinate}")
        results.append({**local, "coordinate": item.coordinate, "officialUrl": item.official_url,
                        "expectedSha256": item.sha256, "pinSources": pin_sources})
    if sum(item["bytes"] for item in results) > MAX_TOTAL_BYTES:
        raise ParityError("Inventory exceeds the total download-byte bound")
    return {"status": "LOCAL_INPUTS_MATCH_PINS", "registryParityVerified": False,
            "networkAttempted": False, "artifacts": results, "pinMetadata": metadata}


def checked_output(output_dir: Path, root: Path) -> Path:
    if not output_dir.is_absolute() or output_dir.resolve() != output_dir:
        raise ParityError("--output-dir must be absolute, normalized, and free of symlinks")
    if output_dir.exists() or output_dir.is_symlink():
        raise ParityError("--output-dir must be new; existing files/directories are never overwritten")
    if output_dir == root or root in output_dir.parents or output_dir in root.parents:
        raise ParityError("--output-dir must be outside the checkout")
    if not output_dir.parent.is_dir():
        raise ParityError("The output parent directory must already exist")
    return output_dir


def validate_target(url: str, original_url: str) -> None:
    """Canonical endpoints only, including after each HTTP redirect."""
    allowed = {a.official_url for a in ARTIFACTS}
    if original_url not in allowed or url != original_url:
        raise ParityError("Redirect/URL is not the exact allowlisted official artifact endpoint")
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.netloc not in ("repo.maven.apache.org", "dl.google.com")
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment):
        raise ParityError("Only credential-free canonical official HTTPS endpoints are allowed")


class NoAutomaticRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def make_opener():
    # Keep normal TLS trust and managed proxy settings; do not discover account
    # credentials, use netrc, add authorization, cookies, or disable validation.
    return urllib.request.build_opener(NoAutomaticRedirect(),
                                      urllib.request.HTTPSHandler(context=ssl.create_default_context()))


def deadline_handler(_signum, _frame):
    raise ParityError("The per-artifact signal deadline expired")


@contextmanager
def download_deadline():
    # Python signal handling can wait for blocking C/DNS calls; an external OS
    # supervisor is required. Do not clobber an embedding caller's timer.
    if (not hasattr(signal, "setitimer") or threading.current_thread() is not threading.main_thread()
            or signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0)):
        raise ParityError("Downloads require a POSIX main thread without an existing real-time timer")
    previous = signal.signal(signal.SIGALRM, deadline_handler)
    try:
        signal.setitimer(signal.ITIMER_REAL, ARTIFACT_DEADLINE_SECONDS)
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def download_file(item: Artifact, expected_bytes: int, destination: Path, opener) -> dict:
    if not 0 < expected_bytes <= MAX_ARTIFACT_BYTES:
        raise ParityError("Download size is outside the reviewed bound")
    url, redirects = item.official_url, 0
    with download_deadline():
        while True:
            validate_target(url, item.official_url)
            request = urllib.request.Request(url, headers={"Accept-Encoding": "identity",
                                             "User-Agent": "Droide-Registry-Parity/1"}, method="GET")
            try:
                response = opener.open(request, timeout=SOCKET_TIMEOUT_SECONDS)
            except urllib.error.HTTPError as error:
                code = error.code
                locations = error.headers.get_all("Location", [])
                error.close()
                if code not in (301, 302, 303, 307, 308):
                    raise ParityError(f"Official registry returned HTTP {code}") from None
                if len(locations) != 1 or redirects >= MAX_REDIRECTS:
                    raise ParityError("Missing/ambiguous redirect target or redirect bound exceeded") from None
                url = urllib.parse.urljoin(url, locations[0])
                validate_target(url, item.official_url)
                redirects += 1
                continue
            except (OSError, urllib.error.URLError, ValueError):
                # Do not serialize exception text: a managed proxy can include
                # credentials or unrelated configuration in a network error.
                raise ParityError("Official registry request failed; no endpoint fallback was attempted") from None
            break
        with response:
            validate_target(response.geturl(), item.official_url)
            if response.status != 200:
                raise ParityError("Expected a complete HTTP 200 artifact response")
            encoding = response.headers.get("Content-Encoding", "identity").strip().lower()
            if encoding != "identity":
                raise ParityError("Compressed HTTP content encoding is not accepted")
            lengths = response.headers.get_all("Content-Length", [])
            if lengths and (len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0])
                            or int(lengths[0]) != expected_bytes):
                raise ParityError("Content-Length differs from the pinned local artifact size")
            descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL
                                 | getattr(os, "O_NOFOLLOW", 0), 0o600)
            digest, size = hashlib.sha256(), 0
            with os.fdopen(descriptor, "wb") as stream:
                while block := response.read(min(CHUNK_BYTES, expected_bytes - size + 1)):
                    size += len(block)
                    if size > expected_bytes:
                        raise ParityError("Download exceeded the pinned local artifact size")
                    stream.write(block)
                    digest.update(block)
            if size != expected_bytes:
                raise ParityError("Download was truncated before the pinned local artifact size")
    downloaded_hash = digest.hexdigest()
    return {"coordinate": item.coordinate, "officialUrl": item.official_url,
            "file": destination.name, "bytes": size, "sha256": downloaded_hash,
            "expectedSha256": item.sha256, "matchesPinnedBytes": downloaded_hash == item.sha256,
            "redirects": redirects}


def error_message(error: Exception) -> str:
    return str(error) if isinstance(error, ParityError) else f"I/O or metadata failure ({type(error).__name__})"


def report_limits() -> dict:
    return {"maximumArtifactBytes": MAX_ARTIFACT_BYTES, "maximumTotalBytes": MAX_TOTAL_BYTES,
            "socketTimeoutSeconds": SOCKET_TIMEOUT_SECONDS,
            "signalDeadlinePerArtifactSeconds": ARTIFACT_DEADLINE_SECONDS,
            "osHardPerArtifactDeadlineGuaranteed": False,
            "externalProcessSupervisionRequired": True,
            "externalProcessSupervisionVerified": False,
            "maximumRedirects": MAX_REDIRECTS}


def download_and_compare(output_dir: Path, root: Path = ROOT) -> dict:
    root = root.resolve(strict=True)
    before = check_inputs(root)
    output = checked_output(output_dir, root)
    output.mkdir(mode=0o700)
    report = {"status": "FAILED", "scope": "eight-runtime-mirror-registry-byte-parity-only",
              "networkAttempted": False, "registryParityVerified": False,
              "sourceBuildVerified": False, "fdroidAcceptanceVerified": False,
              "repositoryWritesPerformed": False, "limits": report_limits(),
              "inputsBefore": before, "downloads": [], "errors": []}
    try:
        opener = make_opener()
        for item, local in zip(ARTIFACTS, before["artifacts"]):
            destination = output / (Path(item.local_path).name + ".download")
            report["networkAttempted"] = True
            result = download_file(item, local["bytes"], destination, opener)
            report["downloads"].append(result)
            if not result["matchesPinnedBytes"]:
                raise ParityError(f"Registry bytes differ from the existing SHA-256 pin: {item.coordinate}")
    except (ParityError, OSError, urllib.error.URLError, http.client.HTTPException, ValueError) as error:
        report["errors"].append(error_message(error))
    finally:
        try:
            after = check_inputs(root)
            report["inputsAfter"] = after
            if after != before:
                raise ParityError("Local inputs or pin metadata changed during the comparison")
        except (ParityError, OSError, ValueError) as error:
            report["errors"].append(error_message(error))
    if not report["errors"] and len(report["downloads"]) == 8:
        report["status"] = "ALL_EIGHT_REGISTRY_BYTES_MATCH_PINS"
        report["registryParityVerified"] = True
    # Retain incomplete/mismatching data for inspection; never retry with
    # different versions, update a pin, or copy an artifact into the repository.
    descriptor = os.open(output / "PARITY.json", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2, sort_keys=True)
        stream.write("\n")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check-inputs", action="store_true", help="Offline read-only check (default)")
    mode.add_argument("--download-and-compare", action="store_true", help="Explicitly fetch the eight pinned official artifacts")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--output-dir", type=Path, help="New absolute directory outside the checkout; downloads only")
    args = parser.parse_args(argv)
    if args.download_and_compare != (args.output_dir is not None):
        parser.error("--download-and-compare and --output-dir must be supplied together")
    try:
        result = (download_and_compare(args.output_dir, args.root) if args.download_and_compare
                  else check_inputs(args.root))
    except (ParityError, OSError, ValueError) as error:
        result = {"status": "FAILED", "registryParityVerified": False, "errors": [error_message(error)]}
    print(json.dumps(result, indent=2, sort_keys=True))
    return 1 if result["status"] == "FAILED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
