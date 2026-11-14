#!/usr/bin/env python3
"""Synthetic structural negatives, not Android APK runtime certification."""
import io
from pathlib import Path
import sys
import unittest
import struct
from unittest import mock
import zipfile
sys.path[:0] = [str(Path(__file__).resolve().parents[1]), str(Path(__file__).resolve().parent)]
import current_apk_binding as B
import test_pty_dex_binding as F

def fixture(target, **kwargs):
    with mock.patch.object(F.binding, 'TARGET', target):
        return F.fixture(**kwargs)

def apk(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w') as z:
        for n, v in entries.items():
            z.writestr(n, v)
    return out.getvalue()


def native(alignment=16384):
    data = bytearray(120)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<H', data, 18, 183)
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 1)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
    return bytes(data)

class BindingTests(unittest.TestCase):

    def entries(self):
        e = {'classes.dex' if i == 0 else 'classes' + str(i + 1) + '.dex': fixture(n) for i,
            n in enumerate(sorted(B.REQUIRED))}
        e.update({'AndroidManifest.xml': b'fixture manifest',
                'resources.arsc': b'fixture resources',
                'lib/arm64-v8a/libfixture.so': native()})
        return e

    def check(self, a, b):
        return B.compare(apk(a), apk(b), {'lib/arm64-v8a/libfixture.so': B.sha(native())})

    def test_elf_alignment_is_separate_from_zip_alignment(self):
        self.assertTrue(B.elf_load_alignment(native())['loadAlignment16KiB'])
        report = B.elf_load_alignment(native(4096))
        self.assertFalse(report['loadAlignment16KiB'])
        self.assertFalse(report['deviceCompatibilityEstablished'])

    def test_complete_positive(self):
        self.assertEqual(len(B.REQUIRED), self.check(self.entries(), self.entries())['boundClasses'])

    def test_stale_live_app_despite_correct_engine(self):
        e = self.entries()
        e['classes.dex'] = fixture(sorted(B.REQUIRED)[0], literal='world')
        with self.assertRaisesRegex(Exception, 'body differs'):
            self.check(e, self.entries())

    def test_every_required_body_is_bound(self):
        for i, n in enumerate(sorted(B.REQUIRED)):
            e = self.entries()
            e['classes.dex' if i == 0 else 'classes' + str(i + 1) + '.dex'] = fixture(n, literal='world')
            with self.subTest(n=n), self.assertRaisesRegex(Exception, 'body differs'):
                self.check(e, self.entries())

    def test_extra_decoy_class_rejected(self):
        e = self.entries()
        e['classes99.dex'] = fixture('Lcom/baystudio/droide/UnusedValidBridge;')
        with self.assertRaisesRegex(Exception, 'body differs'):
            self.check(e, self.entries())

    def test_duplicate_hidden_missing_rejected(self):
        for mutate in [lambda e: e.update({'classes99.dex': e['classes.dex']}),
            lambda e: e.update({'assets/hidden.dex': e['classes.dex']}),
            lambda e: e.pop('classes.dex')]:
            e = self.entries()
            mutate(e)
            with self.assertRaises(Exception):
                self.check(e, self.entries())

    def test_manifest_resources_native_mutations(self):
        for name in ['AndroidManifest.xml', 'resources.arsc', 'lib/arm64-v8a/libfixture.so']:
            e = self.entries()
            e[name] = b'changed'
            with self.subTest(n=name), self.assertRaises(Exception):
                self.check(e, self.entries())

    def test_matching_unknown_native_rejected(self):
        e = self.entries()
        e['lib/arm64-v8a/libextra.so'] = b'x'
        with self.assertRaisesRegex(Exception, 'providers differ'):
            self.check(e, e)

    def test_pool_index_relocation_preserves_canonical_code(self):
        e = self.entries()
        e['classes.dex'] = fixture(sorted(B.REQUIRED)[0], context=True)
        self.check(e, self.entries())
if __name__ == '__main__':
    unittest.main()
