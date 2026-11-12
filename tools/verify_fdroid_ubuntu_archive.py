#!/usr/bin/env python3
"""Verify the official Canonical gzip and bundled XZ decode to the same Ubuntu TAR.

No network access or guest execution occurs in this verifier. Supply an archive
downloaded independently from the exact upstream URL pinned in source.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import lzma
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
CHUNK = 1024 * 1024


def digest_bounded(stream, max_bytes: int) -> tuple[int, str]:
    if max_bytes <= 0:
        raise ValueError("Archive byte bound must be positive")
    digest = hashlib.sha256()
    total = 0
    while True:
        chunk = stream.read(CHUNK)
        if not chunk:
            break
        total += len(chunk)
        if total > max_bytes:
            raise ValueError("Ubuntu archive exceeds reviewed byte bound")
        digest.update(chunk)
    return total, digest.hexdigest()


def pinned_manifest(root: Path) -> dict:
    manifest = json.loads((root / "app/src/main/assets/workstation/rootfs-repack-manifest.json").read_text())
    if not isinstance(manifest, list) or len(manifest) != 1:
        raise ValueError("Expected exactly one Ubuntu rootfs manifest record")
    item = manifest[0]
    source = (root / "app/src/main/java/com/baystudio/droide/core/FoundryUbuntuBaseManifest.kt").read_text()

    def kotlin_string(name: str) -> str:
        match = re.search(r"\bconst val " + re.escape(name) + r'\s*=\s*"([^"]+)"', source)
        if match is None:
            raise ValueError("Missing Canonical pin " + name)
        return match[1]

    def kotlin_long(name: str) -> int:
        match = re.search(r"\bconst val " + re.escape(name) + r"\s*=\s*([\d_]+)L?", source)
        if match is None:
            raise ValueError("Missing Canonical byte pin " + name)
        return int(match[1].replace("_", ""))

    name = kotlin_string("ROOTFS_URL").rsplit("/", 1)[-1]
    if not kotlin_string("ROOTFS_URL").startswith("https://cdimage.ubuntu.com/ubuntu-base/releases/"):
        raise ValueError("Canonical URL is no longer on the reviewed HTTPS origin")
    if (item.get("upstreamFile"), item.get("upstreamSha256")) != (name, kotlin_string("ROOTFS_SHA256")):
        raise ValueError("Canonical URL/hash differ from reviewed Ubuntu repack manifest")
    if item.get("file") != "ubuntu-base-24.04.5-base-arm64.tar.xz":
        raise ValueError("Bundled Ubuntu archive identity changed")
    if not isinstance(item.get("tarBytes"), int) or not re.fullmatch(r"[0-9a-f]{64}", item.get("tarSha256", "")):
        raise ValueError("Invalid reviewed TAR size or SHA-256")
    item["upstreamBytes"] = kotlin_long("ROOTFS_BYTES")
    return item


def verify(archive: Path, root: Path = ROOT) -> dict:
    item = pinned_manifest(root)
    if archive.is_symlink() or not archive.is_file():
        raise ValueError("Canonical Ubuntu archive must be a regular non-symlink file")
    if archive.stat().st_size != item["upstreamBytes"]:
        raise ValueError("Canonical Ubuntu gzip length differs from pinned source")
    with archive.open("rb") as compressed:
        if digest_bounded(compressed, item["upstreamBytes"]) != (item["upstreamBytes"], item["upstreamSha256"]):
            raise ValueError("Canonical Ubuntu compressed SHA-256 mismatch")
    wanted = (item["tarBytes"], item["tarSha256"])
    with gzip.open(archive, "rb") as decoded:
        canonical = digest_bounded(decoded, item["tarBytes"])
    if canonical != wanted:
        raise ValueError("Canonical Ubuntu TAR differs from reviewed rootfs")
    bundled = root / "app/src/main/assets/workstation" / item["file"]
    if bundled.is_symlink() or not bundled.is_file() or bundled.stat().st_size != item["bytes"]:
        raise ValueError("Bundled Ubuntu XZ missing or changed")
    with bundled.open("rb") as compressed:
        if digest_bounded(compressed, item["bytes"]) != (item["bytes"], item["sha256"]):
            raise ValueError("Bundled Ubuntu XZ SHA-256 mismatch")
    with lzma.open(bundled, "rb") as decoded:
        if digest_bounded(decoded, item["tarBytes"]) != canonical:
            raise ValueError("Canonical and bundled Ubuntu TAR content differ")
    return {
        "canonicalArchive": item["upstreamFile"],
        "canonicalSha256": item["upstreamSha256"],
        "tarBytes": item["tarBytes"],
        "tarSha256": item["tarSha256"],
        "identicalDecompressedTar": True,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    args = parser.parse_args()
    print("FDROID_CANONICAL_UBUNTU_PARITY_OK", json.dumps(verify(args.archive), sort_keys=True))
