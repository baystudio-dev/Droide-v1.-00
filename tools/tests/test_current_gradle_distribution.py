#!/usr/bin/env python3
"""Tiny vendor archive fixtures exercise identity checks, not vendor trust itself."""
from pathlib import Path
import hashlib
import stat
import sys
import tempfile
import unittest
from unittest import mock
import zipfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import current_gradle_distribution as distribution


class DistributionTests(unittest.TestCase):
    def fixture(self, root):
        installed = root / 'gradle-8.13'
        (installed / 'bin').mkdir(parents=True)
        (installed / 'bin').chmod(0o755)
        executable = installed / 'bin/gradle'
        executable.write_bytes(b'fixture executable')
        executable.chmod(0o755)
        archive = root / 'vendor.zip'
        with zipfile.ZipFile(archive, 'w') as vendor:
            for name, mode, data in (
                ('bin/', stat.S_IFDIR | 0o755, b''),
                ('bin/gradle', stat.S_IFREG | 0o755, b'fixture executable'),
            ):
                entry = zipfile.ZipInfo('gradle-8.13/' + name)
                entry.create_system = 3
                entry.external_attr = mode << 16
                vendor.writestr(entry, data)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        wrapper = root / 'gradle-wrapper.properties'
        wrapper.write_text('distributionSha256Sum=' + digest + '\n')
        return archive, installed, wrapper, digest

    def test_exact_bytes_modes_and_entries(self):
        with tempfile.TemporaryDirectory() as name:
            archive, installed, wrapper, digest = self.fixture(Path(name))
            with mock.patch.object(distribution, 'ARCHIVE_SHA256', digest):
                result = distribution.verify(archive, installed, wrapper)
            self.assertEqual(2, len(result['installedEntries']))

    def test_mode_mismatch_is_not_repaired(self):
        with tempfile.TemporaryDirectory() as name:
            archive, installed, wrapper, digest = self.fixture(Path(name))
            executable = installed / 'bin/gradle'
            executable.chmod(0o644)
            with mock.patch.object(distribution, 'ARCHIVE_SHA256', digest):
                with self.assertRaisesRegex(ValueError, 'mode differs'):
                    distribution.verify(archive, installed, wrapper)
            self.assertEqual(0o644, stat.S_IMODE(executable.stat().st_mode))

    def test_byte_and_extra_file_drift(self):
        for kind in ('byte', 'extra'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as name:
                archive, installed, wrapper, digest = self.fixture(Path(name))
                if kind == 'byte':
                    (installed / 'bin/gradle').write_bytes(b'changed')
                else:
                    (installed / 'injected').write_text('extra')
                with mock.patch.object(distribution, 'ARCHIVE_SHA256', digest):
                    with self.assertRaisesRegex(ValueError, 'differ'):
                        distribution.verify(archive, installed, wrapper)

    def test_vendor_hash_and_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            archive, installed, wrapper, digest = self.fixture(Path(name))
            with self.assertRaisesRegex(ValueError, 'archive digest'):
                distribution.verify(archive, installed, wrapper)
            executable = installed / 'bin/gradle'
            executable.unlink()
            executable.symlink_to(archive)
            with mock.patch.object(distribution, 'ARCHIVE_SHA256', digest):
                with self.assertRaisesRegex(ValueError, 'linked installed'):
                    distribution.verify(archive, installed, wrapper)


if __name__ == '__main__':
    unittest.main()
