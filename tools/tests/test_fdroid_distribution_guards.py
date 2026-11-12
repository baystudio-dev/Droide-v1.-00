"""F-Droid build must preserve every unrelated runtime and app asset."""

from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from verify_source_release import FDROID_EXCLUDED_ASSET, verify_asset_payloads


class FdroidAssetGuardsTest(unittest.TestCase):
    def verify_fixture(self, include_rootfs=False, include_normal=True, mutate_normal=False,
                       fdroid_build=True):
        with tempfile.TemporaryDirectory() as work:
            base = Path(work)
            assets = base / "app/src/main/assets"
            rootfs = assets / "workstation/ubuntu-base-24.04.5-base-arm64.tar.xz"
            normal = assets / "legal/licenses/required.txt"
            rootfs.parent.mkdir(parents=True)
            normal.parent.mkdir(parents=True)
            rootfs.write_bytes(b"canonical reviewed fixture")
            normal.write_bytes(b"license source")
            apk = base / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as output:
                if include_rootfs:
                    output.write(rootfs, FDROID_EXCLUDED_ASSET)
                if include_normal:
                    output.writestr("assets/legal/licenses/required.txt",
                                    b"tampered license" if mutate_normal else normal.read_bytes())
            with zipfile.ZipFile(apk) as archive:
                return verify_asset_payloads(base, archive, fdroid_build=fdroid_build)

    def test_fdroid_may_omit_only_reviewed_rootfs(self):
        self.assertEqual(self.verify_fixture(), 1)

    def test_regular_release_must_retain_rootfs(self):
        with self.assertRaisesRegex(ValueError, "missing/changed"):
            self.verify_fixture(fdroid_build=False)

    def test_fdroid_rejects_packaged_rootfs(self):
        with self.assertRaisesRegex(ValueError, "embeds"):
            self.verify_fixture(include_rootfs=True)

    def test_fdroid_rejects_any_other_missing_asset(self):
        with self.assertRaisesRegex(ValueError, "missing/changed"):
            self.verify_fixture(include_normal=False)

    def test_fdroid_rejects_modified_other_assets(self):
        with self.assertRaisesRegex(ValueError, "missing/changed"):
            self.verify_fixture(mutate_normal=True)

    def test_regular_release_accepts_both_unchanged_assets(self):
        self.assertEqual(self.verify_fixture(include_rootfs=True, fdroid_build=False), 2)


if __name__ == "__main__":
    unittest.main()
