#!/usr/bin/env python3
"""Mutation tests for license packaging, not legal clearance."""
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('license_check', ROOT/'tools/verify_first_party_license.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)

class LicensePackagingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        paths = ['LICENSE', 'COPYING', 'COPYING.LESSER', 'LICENSE_SCOPE.md', 'README.md', 'RELEASE_AND_COMPLIANCE.md',
                 'app/src/main/assets/legal/DROIDE_LICENSE.md', 'app/src/main/assets/legal/DISTRIBUTION_COMPLIANCE.md',
                 'app/src/main/assets/legal/licenses/LGPL-3.0.txt', 'app/src/main/assets/legal/licenses/GPL-3.0.txt',
                 'app/src/main/assets/legal/licenses/Droide-Previous-MIT.txt',
                 'app/src/main/java/com/baystudio/droide/ui/SettingsSheet.kt',
                 'app/src/main/java/com/baystudio/droide/ui/SettingsDiscovery.kt']
        for name in paths:
            dest = self.root/name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT/name, dest)

    def test_complete_bundle(self):
        self.assertTrue(checker.verify(self.root))

    def test_missing_copies(self):
        for name in ['LICENSE', 'COPYING', 'COPYING.LESSER', 'app/src/main/assets/legal/licenses/Droide-Previous-MIT.txt']:
            with self.subTest(name=name):
                p = self.root/name; original = p.read_bytes(); p.unlink()
                with self.assertRaises((ValueError, FileNotFoundError)): checker.verify(self.root)
                p.write_bytes(original)

    def test_corrupt_or_stale_material(self):
        mutations = {
            'app/src/main/assets/legal/licenses/GPL-3.0.txt': b'altered license',
            'app/src/main/assets/legal/licenses/LGPL-3.0.txt': b'altered license',
            'app/src/main/assets/legal/licenses/Droide-Previous-MIT.txt': b'altered prior grant',
            'app/src/main/assets/legal/DROIDE_LICENSE.md': b'SPDX-License-Identifier: LGPL-3.0-only',
            'LICENSE_SCOPE.md': b'SPDX-License-Identifier: LGPL-3.0-only',
            'README.md': b'MIT License',
            'app/src/main/java/com/baystudio/droide/ui/SettingsSheet.kt': b'MIT License',
            'app/src/main/java/com/baystudio/droide/ui/SettingsDiscovery.kt': b'MIT License',
        }
        for name, data in mutations.items():
            with self.subTest(name=name):
                p = self.root/name; original = p.read_bytes(); p.write_bytes(data)
                with self.assertRaises(ValueError): checker.verify(self.root)
                p.write_bytes(original)

if __name__ == '__main__': unittest.main()
