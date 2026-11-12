"""F-Droid metadata smoke and source-asset integrity tests."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "tools/verify_fdroid_metadata.py"
SPEC = importlib.util.spec_from_file_location("verify_fdroid_metadata", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FdroidMetadataTests(unittest.TestCase):
    def test_metadata_is_valid_for_current_version(self):
        self.assertEqual(MODULE.verify(), (44, 5))

    def test_artwork_matches_unmodified_originals(self):
        source_to_listing = {
            "resources/branding/droide-logo-source.png": "fastlane/metadata/android/en-US/images/icon.png",
            "resources/screenshots/code-editor.jpg": "fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg",
            "resources/screenshots/agent-activity.jpg": "fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg",
            "resources/screenshots/terminal.jpg": "fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg",
            "resources/screenshots/project-files.jpg": "fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg",
            "resources/screenshots/extensions.jpg": "fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg",
        }
        for source, listing in source_to_listing.items():
            with self.subTest(source=source):
                self.assertEqual((ROOT / source).read_bytes(), (ROOT / listing).read_bytes())


if __name__ == "__main__":
    unittest.main()
