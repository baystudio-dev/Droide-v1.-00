#!/usr/bin/env python3
"""Bounded synthetic-file tests for source-only runtime verification; no processes/native code."""
import copy
import hashlib
import json
import lzma
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_bundled_runtime as runtime
import verify_runtime_licenses as legal


class UbuntuOnlyRuntimeContractTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="droide-ubuntu-only-contract-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        size, sha, tar_size, tar_sha = runtime.UBUNTU_IDENTITY
        self.manifest = [{"file": runtime.UBUNTU_NAME, "bytes": size, "sha256": sha,
                          "tarBytes": tar_size, "tarSha256": tar_sha}]
        self.extractor = (f'val ubuntu = Image("{runtime.UBUNTU_NAME}", {size}, "{sha}", '
                          f'{tar_size}, "{tar_sha}")\nrequire(image == ubuntu)')

    def put(self, rel, data=b"synthetic-only"):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def scope(self):
        return {"aggregates": {"ubuntuBase": {}},
                "runtimeFiles": [{"path": p} for p in sorted(legal.CURRENT_RUNTIME_PATHS)],
                "sourceArchives": [{"path": p} for p in sorted(legal.CURRENT_SOURCE_PATHS)]}

    def test_exact_ubuntu_admission(self):
        self.assertEqual({runtime.UBUNTU_NAME: runtime.UBUNTU_IDENTITY},
                         runtime.admitted_images(self.manifest, self.extractor))

    def test_alpine_or_duplicate_image_declaration_rejected(self):
        for suffix in (self.extractor.splitlines()[0],
                       self.extractor.splitlines()[0].replace(runtime.UBUNTU_NAME, "alpine-minirootfs-3.24.2-aarch64.tar.xz")):
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, "only the exact pinned Ubuntu"):
                runtime.admitted_images(self.manifest, self.extractor + "\n" + suffix)

    def test_altered_ubuntu_identity_rejected(self):
        for field in ("bytes", "sha256", "tarBytes", "tarSha256"):
            wrong = copy.deepcopy(self.manifest)
            wrong[0][field] = 0 if field.endswith("Bytes") or field == "bytes" else "0" * 64
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "mismatch"):
                runtime.admitted_images(wrong, self.extractor)
        with self.assertRaisesRegex(ValueError, "only the exact pinned Ubuntu"):
            runtime.admitted_images(self.manifest, self.extractor.replace(runtime.UBUNTU_IDENTITY[1], "0" * 64))

    def test_absent_duplicate_or_alpine_manifest_rejected(self):
        for wrong in ([], self.manifest * 2, [{"file": "alpine-minirootfs-3.24.2-aarch64.tar.xz"}]):
            with self.subTest(wrong=wrong), self.assertRaisesRegex(ValueError, "manifest must contain only"):
                runtime.admitted_images(wrong, self.extractor)

    def test_permissive_extractor_admission_rejected(self):
        with self.assertRaisesRegex(ValueError, "explicitly admit Ubuntu only"):
            runtime.admitted_images(self.manifest, self.extractor.replace("image == ubuntu", "image == alpine || image == ubuntu"))

    def test_both_retired_assets_rejected_even_when_undeclared_or_nested(self):
        for variant in ("main", "debug"):
            for name in sorted(runtime.RETIRED_ASSETS):
                with self.subTest(variant=variant, name=name):
                    path = self.put(f"app/src/{variant}/assets/undeclared/nested/{name}")
                    with self.assertRaisesRegex(ValueError, "must not be packaged"):
                        runtime.require_retired_assets_absent(self.root)
                    path.unlink()

    def test_history_outside_assets_is_allowed(self):
        for name in runtime.RETIRED_ASSETS:
            self.put(f"{legal.HISTORY}/assets/{name}")
        runtime.require_retired_assets_absent(self.root)

    def test_dangling_retired_asset_symlink_rejected(self):
        path = self.root / "app/src/main/assets/workstation" / sorted(runtime.RETIRED_ASSETS)[0]
        path.parent.mkdir(parents=True)
        path.symlink_to(self.root / "missing")
        with self.assertRaisesRegex(ValueError, "must not be packaged"):
            runtime.require_retired_assets_absent(self.root)

    def test_asset_directory_alias_rejected(self):
        target = self.root / "outside"
        target.mkdir()
        assets = self.root / "app/src/main/assets"
        assets.parent.mkdir(parents=True)
        assets.symlink_to(target, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Symlink Android asset root"):
            runtime.require_retired_assets_absent(self.root)

    def test_synthetic_xz_checks_both_compressed_and_decoded_identity(self):
        decoded = b"synthetic archive bytes, never extracted or executed\n"
        compressed = lzma.compress(decoded)
        path = self.put("fixture.xz", compressed)
        values = (len(compressed), hashlib.sha256(compressed).hexdigest(),
                  len(decoded), hashlib.sha256(decoded).hexdigest())
        runtime.verify_rootfs_payload(path, values)
        with self.assertRaisesRegex(ValueError, "XZ integrity"):
            runtime.verify_rootfs_payload(path, (values[0], "0" * 64, *values[2:]))
        with self.assertRaisesRegex(ValueError, "TAR integrity"):
            runtime.verify_rootfs_payload(path, (*values[:2], values[2], "0" * 64))

    def test_missing_or_symlink_rootfs_rejected(self):
        missing = self.root / "missing.xz"
        with self.assertRaises(FileNotFoundError):
            runtime.verify_rootfs_payload(missing, runtime.UBUNTU_IDENTITY)
        alias = self.root / "alias.xz"
        alias.symlink_to(missing)
        with self.assertRaisesRegex(ValueError, "Symlink payload"):
            runtime.verify_rootfs_payload(alias, runtime.UBUNTU_IDENTITY)

    def test_native_integrity_missing_and_symlink_guards(self):
        data = b"synthetic native placeholder, not an executable"
        path = self.put("fixture.so", data)
        sha = hashlib.sha256(data).hexdigest()
        runtime.verify_native_payload(path, sha)
        with self.assertRaisesRegex(ValueError, "Native engine integrity"):
            runtime.verify_native_payload(path, "0" * 64)
        missing = self.root / "missing.so"
        with self.assertRaises(FileNotFoundError):
            runtime.verify_native_payload(missing, sha)
        alias = self.root / "alias.so"
        alias.symlink_to(path)
        with self.assertRaisesRegex(ValueError, "Symlink native"):
            runtime.verify_native_payload(alias, sha)

    def test_exact_active_inventory_scope(self):
        legal.verify_inventory_scope(self.scope())

    def test_stale_active_alpine_or_qemu_aggregate_rejected(self):
        for key in ("alpineMinirootfs", "qemuOfflinePack"):
            wrong = self.scope()
            wrong["aggregates"][key] = {"packages": []}
            with self.subTest(key=key), self.assertRaisesRegex(SystemExit, "Ubuntu only"):
                legal.verify_inventory_scope(wrong)

    def test_retired_active_catalog_entries_rejected(self):
        legal.verify_catalog_scope({"entries": [{"id": "ubuntu-base-runtime-aggregate"}]})
        for retired in ("qemu-runtime", "alpine-runtime-aggregate", "qemu-alpine-runtime-aggregate"):
            with self.subTest(retired=retired), self.assertRaisesRegex(SystemExit, "historical catalog"):
                legal.verify_catalog_scope({"entries": [{"id": retired}]})

    def test_missing_duplicate_or_retired_active_runtime_rejected(self):
        valid = self.scope()
        cases = []
        missing = copy.deepcopy(valid); missing["runtimeFiles"].pop(); cases.append(missing)
        duplicate = copy.deepcopy(valid); duplicate["runtimeFiles"].append(duplicate["runtimeFiles"][0]); cases.append(duplicate)
        retired = copy.deepcopy(valid); retired["runtimeFiles"][0]["path"] = "app/src/main/assets/workstation/qemu-offline-alpine-v3.24-arm64.tar"; cases.append(retired)
        for wrong in cases:
            with self.subTest(wrong=wrong), self.assertRaisesRegex(SystemExit, "exact Ubuntu/PRoot/XZ allowlist"):
                legal.verify_inventory_scope(wrong)

    def test_historical_qemu_source_not_current_inventory(self):
        wrong = self.scope()
        wrong["sourceArchives"].append({"path": "third_party/native-engines/qemu/qemu-v11.0.3-source.tar.xz"})
        with self.assertRaisesRegex(SystemExit, "current sourceArchives"):
            legal.verify_inventory_scope(wrong)

    def test_historical_hash_and_notice_preservation(self):
        # Substitute only the expected evidence map and use an explicit synthetic root.
        # No production payload/source is opened by this fixture.
        pkg = {"name": "fixture", "license": "MIT", "sourceRecipe": "https://example.invalid/source"}
        inventory = {"aggregates": {"alpineMinirootfs": {"packages": [pkg] * 16},
                                     "qemuOfflinePack": {"packages": [pkg] * 52}}}
        rel = "pre-retirement/app/src/main/assets/legal/runtime_legal_inventory.json"
        payload = json.dumps(inventory).encode()
        notice_rel = "pre-retirement/app/src/main/assets/legal/COPYLEFT_SOURCE_OFFER.md"
        notice = b"synthetic retained source-offer evidence"
        self.put(f"{legal.HISTORY}/{rel}", payload)
        notice_path = self.put(f"{legal.HISTORY}/{notice_rel}", notice)
        expected = {rel: (len(payload), hashlib.sha256(payload).hexdigest()),
                    notice_rel: (len(notice), hashlib.sha256(notice).hexdigest())}
        with mock.patch.dict(legal.HISTORICAL_FILES, expected, clear=True):
            self.assertEqual((16, 52), legal.verify_historical_provenance(self.root))
            notice_path.write_bytes(b"x" * len(notice))
            with self.assertRaisesRegex(SystemExit, "historical runtime evidence hash mismatch"):
                legal.verify_historical_provenance(self.root)
            notice_path.unlink()
            with self.assertRaisesRegex(SystemExit, "missing or changed historical runtime evidence"):
                legal.verify_historical_provenance(self.root)

    def historical_archive_fixture(self):
        payloads = {name: ("synthetic historical payload: " + name).encode()
                    for name in legal.HISTORICAL_PAYLOADS}
        expected = {name: (len(data), hashlib.sha256(data).hexdigest()) for name, data in payloads.items()}
        patcher = mock.patch.dict(legal.HISTORICAL_PAYLOADS, expected, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)
        archive = self.root / "historical-source.zip"
        with zipfile.ZipFile(archive, "w") as bundle:
            for name, data in payloads.items():
                bundle.writestr("Droide/" + name, data)
        index = {"schemaVersion": 1, "status": "retained-in-separate-source-archive",
                 "sourceOffer": "app/src/main/assets/legal/COPYLEFT_SOURCE_OFFER.md",
                 "archive": {"fileName": archive.name, "bytes": archive.stat().st_size,
                             "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "entryPrefix": "Droide/"},
                 "payloads": [{"path": name, "bytes": values[0], "sha256": values[1]}
                              for name, values in expected.items()]}
        self.put(legal.ARCHIVE_INDEX, json.dumps(index).encode())
        return index, archive, payloads

    def test_current_checkout_verifies_metadata_without_loading_retired_payloads(self):
        index, archive, _ = self.historical_archive_fixture()
        self.assertEqual(index, legal.verify_historical_archive_index(self.root))
        self.assertEqual(5, legal.verify_historical_source_zip(index, archive))
        archive.unlink()
        self.assertEqual(index, legal.verify_historical_archive_index(self.root))
        with self.assertRaisesRegex(SystemExit, "source ZIP is missing"):
            legal.verify_historical_source_zip(index, archive)

    def test_missing_index_and_reintroduced_payload_are_rejected(self):
        with self.assertRaisesRegex(SystemExit, "missing historical archive index"):
            legal.verify_historical_archive_index(self.root)
        _, _, payloads = self.historical_archive_fixture()
        name = next(iter(payloads))
        self.put(name, payloads[name])
        with self.assertRaisesRegex(SystemExit, "outside current source"):
            legal.verify_historical_archive_index(self.root)

    def test_missing_duplicate_or_changed_historical_payload_record_is_rejected(self):
        index, _, _ = self.historical_archive_fixture()
        for mutation in ("missing", "duplicate", "hash"):
            wrong = copy.deepcopy(index)
            if mutation == "missing":
                wrong["payloads"].pop()
            elif mutation == "duplicate":
                wrong["payloads"][-1] = wrong["payloads"][0]
            else:
                wrong["payloads"][0]["sha256"] = "0" * 64
            self.put(legal.ARCHIVE_INDEX, json.dumps(wrong).encode())
            with self.subTest(mutation=mutation), self.assertRaisesRegex(SystemExit, "historical payload"):
                legal.verify_historical_archive_index(self.root)

    def test_archive_container_and_member_hashes_are_both_verified(self):
        index, archive, payloads = self.historical_archive_fixture()
        changed = dict(payloads)
        name = next(iter(changed))
        changed[name] = b"X" * len(changed[name])
        with zipfile.ZipFile(archive, "w") as bundle:
            for name, data in changed.items():
                bundle.writestr("Droide/" + name, data)
        with self.assertRaisesRegex(SystemExit, "source ZIP identity mismatch"):
            legal.verify_historical_source_zip(index, archive)
        index["archive"]["bytes"] = archive.stat().st_size
        index["archive"]["sha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
        with self.assertRaisesRegex(SystemExit, "historical payload hash mismatch"):
            legal.verify_historical_source_zip(index, archive)


if __name__ == "__main__":
    unittest.main()
