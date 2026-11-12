"""Guard tracked F-Droid scanner inputs against silent deletion or growth."""

from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from verify_fdroid_scanner_inventory import classify, inventory


class FdroidScannerInventoryTest(unittest.TestCase):
    def test_exact_reported_33_findings_remain_explicit(self):
        result = inventory(ROOT)
        self.assertEqual(result["binaryFindings"], 30)
        self.assertEqual(result["mavenDeclarations"], 3)
        self.assertEqual(result["totalFindings"], 33)
        self.assertEqual(result["status"], "KNOWN_SCAN_ERRORS_UNRESOLVED")

    def test_native_loaders_cannot_be_mislabeled_as_source_archives(self):
        self.assertEqual(classify("app/src/main/jniLibs/arm64-v8a/libproot-loader.so"), "native_runtime_so")

    def test_unknown_artifacts_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "Unclassified"):
            classify("third_party/maven/custom/unknown.jar")


if __name__ == "__main__":
    unittest.main()
