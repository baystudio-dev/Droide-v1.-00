"""Integration checks against a real R8 APK; no synthetic success receipts."""
import hashlib
import os
from pathlib import Path
import struct
import sys
import unittest
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from verify_source_release import native_contract, native_methods_from_dex
from verify_pty_dex_binding import Dex


class ReleaseNativeBindingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        apk = os.environ.get("DROIDE_SOURCE_RELEASE_APK")
        if not apk:
            raise unittest.SkipTest("Set DROIDE_SOURCE_RELEASE_APK to a real current-source R8 APK")
        with zipfile.ZipFile(apk) as archive:
            cls.entries = {name: archive.read(name) for name in archive.namelist() if name.endswith(".dex")}
        cls.target_entry, cls.methods = native_methods_from_dex(cls.entries)

    def test_real_release_jni_declarations_match_current_native_contract(self):
        expected = sorted(({key: row[key] for key in ("name", "descriptor")}
                           for row in native_contract(ROOT)["methods"]), key=lambda row: row["name"])
        self.assertEqual(expected, self.methods)

    def test_duplicate_jni_class_in_another_dex_is_rejected(self):
        entries = dict(self.entries)
        number = 2
        while f"classes{number}.dex" in entries:
            number += 1
        entries[f"classes{number}.dex"] = entries[self.target_entry]
        with self.assertRaisesRegex(ValueError, "one definition"):
            native_methods_from_dex(entries)

    def test_corrupted_dex_checksum_is_rejected(self):
        entries = dict(self.entries)
        data = bytearray(entries[self.target_entry])
        data[-1] ^= 1
        entries[self.target_entry] = bytes(data)
        with self.assertRaisesRegex(ValueError, "checksum/signature"):
            native_methods_from_dex(entries)

    def test_noncanonical_dex_name_is_rejected(self):
        entries = {**self.entries, "assets/classes.dex": self.entries[self.target_entry]}
        with self.assertRaisesRegex(ValueError, "DEX names"):
            native_methods_from_dex(entries)

    def test_repaired_checksums_do_not_hide_nonstatic_native_method(self):
        data = bytearray(self.entries[self.target_entry])
        dex = Dex(data)
        position = dex.classes["Lcom/termux/terminal/JNI;"][6]
        counts = []
        for _ in range(4):
            count, position = dex.leb(position)
            counts.append(count)
        changed = False
        for group, count in enumerate(counts):
            for _ in range(count):
                _, position = dex.leb(position)
                flags_offset = position
                flags, position = dex.leb(position)
                if group >= 2:
                    _, position = dex.leb(position)
                    if flags & 0x100 and not changed:
                        self.assertTrue(flags & 8)
                        data[flags_offset] ^= 8
                        changed = True
        self.assertTrue(changed)
        data[12:32] = hashlib.sha1(data[32:]).digest()
        struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xffffffff)
        entries = {**self.entries, self.target_entry: bytes(data)}
        with self.assertRaisesRegex(ValueError, "static native"):
            native_methods_from_dex(entries)


if __name__ == "__main__":
    unittest.main()
