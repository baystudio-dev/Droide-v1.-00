#!/usr/bin/env python3
from __future__ import annotations
import argparse
import json
import sys
import zipfile
from pathlib import Path
sys.dont_write_bytecode = True
import generate_runtime_legal_inventory as gen
from verify_bundled_runtime import digest, require_retired_assets_absent

ROOT = Path(__file__).resolve().parents[1]
LEGAL = ROOT / "app/src/main/assets/legal"
HISTORY = "third_party/native-engines/qemu/retired-packaged-runtime-2026-10-16"
CURRENT_RUNTIME_PATHS = frozenset((
    "app/src/main/jniLibs/arm64-v8a/libdroide_proot.so",
    "app/src/main/jniLibs/arm64-v8a/libproot-loader.so",
    "app/src/main/jniLibs/arm64-v8a/libproot-loader32.so",
    "app/libs/xz-1.12.jar",
    "app/src/main/assets/workstation/ubuntu-base-24.04.5-base-arm64.tar.xz",
))
CURRENT_SOURCE_PATHS = frozenset((
    "third_party/native-engines/proot/proot-openminis-8cf13e99-source.tar.gz",
    "third_party/native-engines/proot/talloc-2.4.2-source.tar.gz",
))
# Large retired inputs are retained in the separately archived stable source ZIP.
# Their identities stay pinned even though current source releases omit the bytes.
HISTORICAL_PAYLOADS = {
    "third_party/native-engines/qemu/alpine-v3.24-community-aarch64-APKINDEX.tar.gz":
        (2503496, "b3a70055b7f6526b21861d3037b0a15f882ebc2445dd8f6c4f5daf0557796804"),
    "third_party/native-engines/qemu/alpine-v3.24-main-aarch64-APKINDEX.tar.gz":
        (529422, "c9bc75e1d555694bcbdd2bfe53d733661a06c806291f460cd59c3f9fe648616a"),
    "third_party/native-engines/qemu/qemu-v11.0.3-source.tar.xz":
        (33530620, "41b07ba80f7bdb0dcd46dd4e30eeb914588c7148a7fd3b2dd3ea562084289119"),
    HISTORY + "/assets/alpine-minirootfs-3.24.2-aarch64.tar.xz":
        (3203364, "66c582602205a7f90657c5cb01c8d7d1bfd0a2fc8eb03bd9de288f2c9ef2b935"),
    HISTORY + "/assets/qemu-offline-alpine-v3.24-arm64.tar":
        (38912000, "406fbb16607902b7e39bc8b8c90bca3dda1c36772166b4f0d0777bb9b5d5b0f6"),
}
ARCHIVE_INDEX = HISTORY + "/EXTERNAL_ARCHIVE.json"

# Immutable pre-retirement notices and inventories remain in the source tree.
HISTORICAL_FILES = {'pre-retirement/app/src/main/assets/legal/COPYLEFT_SOURCE_OFFER.md': (1861,
                                                                       '87d09c392c825b6db3c15152a6dddf7817a7e47f5efa56602801d86ef83bacb5'),
 'pre-retirement/app/src/main/assets/legal/DISTRIBUTION_COMPLIANCE.md': (4659,
                                                                         '9736c3358a5f3cc313ccf50a9d8a118c1d32d6fd93156f7f61b91faca3a05285'),
 'pre-retirement/app/src/main/assets/legal/THIRD_PARTY_NOTICES.md': (6829,
                                                                     'c7cd90c4131eacf05cf477199f6a8761977a69922b4d98772f63430900e4ba90'),
 'pre-retirement/app/src/main/assets/legal/licenses/Runtime-Aggregates-NOTICE.md': (941,
                                                                                    '59c9a5305c6ec335b2fd4f2e76ce4eb538686839a1c338a08116a2ef1c0ee72c'),
 'pre-retirement/app/src/main/assets/legal/open_source_licenses.json': (23600,
                                                                        '8164c94c08a8ef8d130eedcc2b86dafd82582ff6adbaee9e865dd62a54064e27'),
 'pre-retirement/app/src/main/assets/legal/runtime_legal_inventory.json': (88466,
                                                                           '79338391c33de1a55141ff17eb7ac0fac576926036f6c0ad1df6592fe8cacc7c'),
 'pre-retirement/app/src/main/assets/workstation/rootfs-repack-manifest.json': (862,
                                                                                'e7d2e28389baee06c3f95831a34857fabbee8a1a099be4d23eb1741424babe8a'),
 'pre-retirement/tools/generate_runtime_legal_inventory.py': (10017,
                                                              'eb8317c4a754d97cb975ecb12f89a467492736dd497d6b7c2d79816a51476a6b'),
 'pre-retirement/tools/verify_bundled_runtime.py': (3492,
                                                    '888830991faf35b41a1b99da1fbbc6e3707710be0d9cbaeb1abb1e879c215bad'),
 'pre-retirement/tools/verify_runtime_licenses.py': (3096,
                                                     '31ef707f6a425b7c04da56814465fc860a4bbf06087a5cce95e69424bb12d398')}


def fail(msg: str) -> None:
    raise SystemExit(f"RUNTIME_LICENSE_FAILED: {msg}")


def verify_inventory_scope(inventory: dict) -> None:
    if set(inventory.get("aggregates", {})) != {"ubuntuBase"}:
        fail("current runtime aggregates must contain Ubuntu only")
    for key, allowed in (("runtimeFiles", CURRENT_RUNTIME_PATHS), ("sourceArchives", CURRENT_SOURCE_PATHS)):
        records = inventory.get(key, [])
        if len(records) != len(allowed) or {item.get("path") for item in records} != allowed:
            fail(f"current {key} does not match the exact Ubuntu/PRoot/XZ allowlist")


def verify_catalog_scope(catalog: dict) -> None:
    retired_ids = {"qemu-runtime", "alpine-runtime-aggregate", "qemu-alpine-runtime-aggregate"}
    if any(item.get("id") in retired_ids for item in catalog.get("entries", [])):
        fail("retired Alpine/QEMU entries belong in the historical catalog, not the active catalog")


def verify_historical_provenance(root=ROOT) -> tuple[int, int]:
    for rel, (size, sha256) in HISTORICAL_FILES.items():
        path = root / HISTORY / rel
        if path.is_symlink() or not path.is_file() or path.stat().st_size != size:
            fail(f"missing or changed historical runtime evidence: {rel}")
        if gen.sha256(path) != sha256:
            fail(f"historical runtime evidence hash mismatch: {rel}")
    archived = json.loads((root / HISTORY / "pre-retirement/app/src/main/assets/legal/runtime_legal_inventory.json").read_text())
    alpine = archived["aggregates"]["alpineMinirootfs"]["packages"]
    qemu = archived["aggregates"]["qemuOfflinePack"]["packages"]
    if len(alpine) != 16 or len(qemu) != 52:
        fail("historical Alpine/QEMU package inventory is incomplete")
    for pkg in alpine + qemu:
        if not pkg.get("license") or pkg.get("license") == "NOASSERTION":
            fail(f"missing historical Alpine package license: {pkg.get('name')}")
        if not str(pkg.get("sourceRecipe", "")).startswith("https://"):
            fail(f"missing historical Alpine source locator: {pkg.get('name')}")
    return len(alpine), len(qemu)


def verify_historical_archive_index(root=ROOT) -> dict:
    path = root / ARCHIVE_INDEX
    if path.is_symlink() or not path.is_file():
        fail("missing historical archive index")
    try:
        index = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        fail(f"cannot read historical archive index: {error}")
    if not isinstance(index, dict) or index.get("schemaVersion") != 1:
        fail("invalid historical archive index schema")
    if index.get("status") != "retained-in-separate-source-archive":
        fail("invalid historical archive status")
    archive = index.get("archive")
    if not isinstance(archive, dict):
        fail("missing historical source archive identity")
    name, sha = archive.get("fileName"), archive.get("sha256")
    if (not isinstance(name, str) or not name.endswith(".zip") or "/" in name or "\\" in name
            or type(archive.get("bytes")) is not int or archive["bytes"] <= 0
            or not isinstance(sha, str) or len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha)
            or archive.get("entryPrefix") != "Droide/"):
        fail("invalid historical source archive identity")
    if index.get("sourceOffer") != "app/src/main/assets/legal/COPYLEFT_SOURCE_OFFER.md":
        fail("historical source offer locator changed")
    records = index.get("payloads")
    if (not isinstance(records, list) or len(records) != len(HISTORICAL_PAYLOADS)
            or any(not isinstance(item, dict) or not isinstance(item.get("path"), str) for item in records)
            or {item["path"] for item in records} != set(HISTORICAL_PAYLOADS)):
        fail("historical payload inventory differs")
    for item in records:
        rel = item["path"]
        if (item.get("bytes"), item.get("sha256")) != HISTORICAL_PAYLOADS[rel]:
            fail(f"historical payload identity changed: {rel}")
        payload = root / rel
        if payload.exists() or payload.is_symlink():
            fail(f"retired payload must remain outside current source: {rel}")
    return index


def verify_historical_source_zip(index: dict, archive_path: Path) -> int:
    """Verify the retained bytes on request, without extraction or network access."""
    identity = index["archive"]
    if archive_path.is_symlink() or not archive_path.is_file():
        fail("historical source ZIP is missing or a symlink")
    if archive_path.stat().st_size != identity["bytes"] or gen.sha256(archive_path) != identity["sha256"]:
        fail("historical source ZIP identity mismatch")
    try:
        with zipfile.ZipFile(archive_path) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                fail("duplicate historical source ZIP entries")
            for rel, expected in HISTORICAL_PAYLOADS.items():
                member = archive.getinfo(identity["entryPrefix"] + rel)
                if member.file_size != expected[0]:
                    fail(f"historical payload size mismatch: {rel}")
                with archive.open(member) as stream:
                    if digest(stream) != expected:
                        fail(f"historical payload hash mismatch: {rel}")
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        fail(f"cannot verify historical source ZIP: {error}")
    return len(HISTORICAL_PAYLOADS)


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify current runtime licenses and retained historical metadata.")
    parser.add_argument("--historical-source-zip", type=Path,
                        help="Also verify the five archived payloads in the separately retained stable source ZIP")
    args = parser.parse_args()
    require_retired_assets_absent(ROOT)
    inventory = LEGAL / "runtime_legal_inventory.json"
    if not inventory.is_file(): fail("runtime legal inventory missing")
    expected = json.loads(inventory.read_text(encoding="utf-8"))
    verify_inventory_scope(expected)
    verify_catalog_scope(json.loads((LEGAL / "open_source_licenses.json").read_text(encoding="utf-8")))
    actual = gen.build()
    if expected != actual: fail("runtime legal inventory is stale; run tools/generate_runtime_legal_inventory.py")
    for rel in [
        "licenses/GPL-2.0.txt", "licenses/GPL-3.0.txt", "licenses/LGPL-2.1.txt", "licenses/LGPL-3.0.txt",
        "licenses/0BSD.txt", "COPYLEFT_SOURCE_OFFER.md", "THIRD_PARTY_NOTICES.md",
    ]:
        p = LEGAL / rel
        if not p.is_file() or p.stat().st_size == 0: fail(f"missing legal asset: legal/{rel}")
    srcs = {x["component"]: ROOT / x["path"] for x in expected["sourceArchives"]}
    for component, path in srcs.items():
        if not path.is_file(): fail(f"missing corresponding source archive for {component}")
    build_inputs = expected.get("sourceBuildInputs", [])
    if len(build_inputs) != 3: fail("PRoot corresponding-source build inputs are incomplete")
    for item in build_inputs:
        path = ROOT / item["path"]
        if not path.is_file() or path.stat().st_size == 0:
            fail(f"missing corresponding-source build input: {item['component']}")
    ubuntu = expected["aggregates"]["ubuntuBase"]["packages"]
    if len(ubuntu) < 50: fail("Ubuntu package inventory unexpectedly small")
    for pkg in ubuntu:
        if not pkg.get("sourcePackage") or not pkg.get("sourceVersion"): fail(f"missing Ubuntu source identity: {pkg.get('name')}")
        if not str(pkg.get("sourcePage", "")).startswith("https://launchpad.net/ubuntu/+source/"): fail(f"missing Ubuntu source locator: {pkg.get('name')}")
        if not str(pkg.get("copyrightNoticePath", "")).startswith("usr/share/doc/"): fail(f"missing embedded Ubuntu copyright notice: {pkg.get('name')}")
    historical_alpine, historical_qemu = verify_historical_provenance()
    archive_index = verify_historical_archive_index()
    archive_status = "external-not-checked"
    if args.historical_source_zip is not None:
        count = verify_historical_source_zip(archive_index, args.historical_source_zip)
        archive_status = f"verified-{count}"
    print(f"RUNTIME_LICENSE_OK active_ubuntu={len(ubuntu)} historical_alpine={historical_alpine} "
          f"historical_qemu={historical_qemu} historical_payloads={archive_status}")

if __name__ == "__main__": main()
