#!/usr/bin/env python3
"""Verify current Ubuntu/PRoot inputs and reject retired Android assets."""
from pathlib import Path
import hashlib
import json
import lzma
import re

ROOT = Path(__file__).resolve().parents[1]
UBUNTU_NAME = "ubuntu-base-24.04.5-base-arm64.tar.xz"
UBUNTU_IDENTITY = (
    17_880_788, "33f099c82ff503ddae6ff669bb274165d70a7602de84cfd34cb0e30ce4b766ce",
    106_741_760, "d0cebb41bb205a8e4e2afc60987dca30619a4fb2c6ec4a1a23a42839254a2ba6",
)
RETIRED_ASSETS = frozenset((
    "alpine-minirootfs-3.24.2-aarch64.tar.xz",
    "qemu-offline-alpine-v3.24-arm64.tar",
))


def digest(stream):
    value = hashlib.sha256()
    size = 0
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        value.update(block)
        size += len(block)
    return size, value.hexdigest()


def constant(text, name):
    match = re.search(r'\bconst val ' + re.escape(name) + r'\s*=\s*"([^"]+)"', text)
    if not match:
        raise ValueError(f"Missing runtime constant: {name}")
    return match[1]


def require_retired_assets_absent(root=ROOT):


    for assets in (root / "app/src").rglob("assets"):
        if assets.is_symlink():
            raise ValueError(f"Symlink Android asset root rejected: {assets.relative_to(root)}")
        if not assets.is_dir():
            continue
        for path in assets.rglob("*"):
            if path.name in RETIRED_ASSETS:
                raise ValueError(f"Retired runtime asset must not be packaged: {path.relative_to(root)}")
            if path.is_symlink() and path.is_dir():
                raise ValueError(f"Symlink Android asset directory rejected: {path.relative_to(root)}")


def admitted_images(manifest, extractor):
    declared = re.findall(r'Image\("([^"]+)",\s*([\d_]+),\s*"([a-f0-9]{64})",\s*([\d_]+),\s*"([a-f0-9]{64})"', extractor)
    expected = {name: (int(size.replace("_", "")), sha, int(tar_size.replace("_", "")), tar_sha)
                for name, size, sha, tar_size, tar_sha in declared}
    if len(declared) != 1 or expected != {UBUNTU_NAME: UBUNTU_IDENTITY}:
        raise ValueError("Runtime image declarations must contain only the exact pinned Ubuntu image")
    if not re.search(r'\brequire\(image == ubuntu\)', extractor):
        raise ValueError("Rootfs extractor must explicitly admit Ubuntu only")
    if not isinstance(manifest, list) or len(manifest) != 1 or manifest[0].get("file") != UBUNTU_NAME:
        raise ValueError("Rootfs manifest must contain only the exact pinned Ubuntu image")
    item = manifest[0]
    values = (item.get("bytes"), item.get("sha256"), item.get("tarBytes"), item.get("tarSha256"))
    if values != UBUNTU_IDENTITY:
        raise ValueError(f"Rootfs runtime/manifest mismatch: {UBUNTU_NAME}")
    return expected


def verify_rootfs_payload(path, values):
    if path.is_symlink():
        raise ValueError(f"Symlink payload rejected: {path.name}")
    with path.open("rb") as stream:
        if digest(stream) != values[:2]:
            raise ValueError(f"Rootfs XZ integrity failure: {path.name}")
    with lzma.open(path) as stream:
        if digest(stream) != values[2:]:
            raise ValueError(f"Rootfs TAR integrity failure: {path.name}")


def verify_native_payload(path, sha256):
    if path.is_symlink():
        raise ValueError(f"Symlink native payload rejected: {path.name}")
    with path.open("rb") as stream:
        if digest(stream)[1] != sha256:
            raise ValueError(f"Native engine integrity failure: {path.name}")


def verify(root=ROOT):
    require_retired_assets_absent(root)
    assets = root / "app/src/main/assets/workstation"
    core = root / "app/src/main/java/com/baystudio/droide/core"
    manifest = json.loads((assets / "rootfs-repack-manifest.json").read_text())
    extractor = (core / "BundledRootfsExtractor.kt").read_text()
    expected = admitted_images(manifest, extractor)
    checked = []
    for name, values in expected.items():
        verify_rootfs_payload(assets / name, values)
        checked.append(name)
    engine = (core / "PackagedLinuxEngine.kt").read_text()
    for label in ("LIBRARY", "LOADER", "LOADER32"):
        name = constant(engine, label + "_NAME")
        if Path(name).name != name:
            raise ValueError(f"Invalid native engine filename: {name}")
        verify_native_payload(root / "app/src/main/jniLibs/arm64-v8a" / name,
                              constant(engine, label + "_SHA256"))
        checked.append(name)
    return checked


if __name__ == "__main__":
    print(f"BUNDLED_RUNTIME_INTEGRITY_OK files={len(verify())} retired_assets=absent")
