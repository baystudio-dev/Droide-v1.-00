"""Unit-level guards for length-limited Canonical archive parity checks."""

import io
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from verify_fdroid_ubuntu_archive import digest_bounded, pinned_manifest


class UbuntuArchiveDigestTests(unittest.TestCase):
    def test_digest_exact_length(self):
        count, digest = digest_bounded(io.BytesIO(b"abc"), 3)
        self.assertEqual(count, 3)
        self.assertEqual(digest, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    def test_reject_oversized_archive(self):
        with self.assertRaisesRegex(ValueError, "exceeds reviewed byte bound"):
            digest_bounded(io.BytesIO(b"abcd"), 3)

    def test_reject_nonpositive_bound(self):
        with self.assertRaisesRegex(ValueError, "must be positive"):
            digest_bounded(io.BytesIO(b""), 0)

    def test_manifest_matches_source_pins(self):
        item = pinned_manifest(ROOT)
        self.assertEqual(item["upstreamBytes"], 29_936_675)
        self.assertEqual(item["tarBytes"], 106_741_760)


if __name__ == "__main__":
    unittest.main()
