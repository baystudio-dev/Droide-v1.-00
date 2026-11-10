"""Verified JDK extraction must preserve vendor identity across host umasks."""
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import provision_build_jdk as provisioner


class ProvisionBuildJdkTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.root = self.directory / 'source'
        (self.root / 'tools').mkdir(parents=True)
        self.archive = self.directory / 'vendor.tar.gz'
        entries = {}
        prefix = 'jdk-fixture'
        with tarfile.open(self.archive, 'w:gz') as archive:
            for name in ('', 'bin', 'lib'):
                member = tarfile.TarInfo(prefix + ('/' + name if name else ''))
                member.type, member.mode = tarfile.DIRTYPE, 0o755
                archive.addfile(member)
                if name:
                    entries[name] = {'mode': 0o755, 'kind': 'directory'}
            for name, mode, data in [('bin/java', 0o755, b'compiler'), ('lib/legal', 0o444, b'notice')]:
                member = tarfile.TarInfo(prefix + '/' + name)
                member.mode, member.size = mode, len(data)
                archive.addfile(member, io.BytesIO(data))
                entries[name] = {'mode': mode, 'kind': 'file', 'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data)}
            member = tarfile.TarInfo(prefix + '/lib/legal-alias')
            member.type, member.mode, member.linkname = tarfile.SYMTYPE, 0o777, 'legal'
            archive.addfile(member)
            entries['lib/legal-alias'] = {'mode': 0o777, 'kind': 'symlink', 'target': 'legal', 'resolved': 'lib/legal'}
        self.manifest = {'version': 'fixture', 'archiveSha256': hashlib.sha256(self.archive.read_bytes()).hexdigest(), 'entries': entries}
        (self.root / 'tools/terminal-engine-v2-jdk.json').write_text(json.dumps(self.manifest))

    def test_exact_identity_is_preserved_with_restrictive_host_umask(self):
        previous = os.umask(0o077)
        try:
            with patch.object(provisioner, 'ROOT', self.root):
                home = provisioner.provision(self.directory / 'output', self.archive)
        finally:
            os.umask(previous)
        self.assertEqual(self.manifest['entries'], provisioner.vendor.installed_inventory(home, self.manifest))
        self.assertEqual(0o444, (home / 'lib/legal').stat().st_mode & 0o777)
        self.assertTrue((home / 'lib/legal-alias').is_symlink())

    def test_archive_mismatch_is_rejected_before_extraction(self):
        with self.archive.open('ab') as stream:
            stream.write(b'changed')
        with patch.object(provisioner, 'ROOT', self.root), patch.object(tarfile.TarFile, 'extractall') as extract:
            with self.assertRaisesRegex(ValueError, 'archive SHA mismatch'):
                provisioner.provision(self.directory / 'output', self.archive)
            extract.assert_not_called()

    def test_existing_output_is_preserved(self):
        output = self.directory / 'output'
        output.mkdir()
        marker = output / 'retained'
        marker.write_bytes(b'keep')
        with patch.object(provisioner, 'ROOT', self.root), self.assertRaisesRegex(ValueError, 'already exists'):
            provisioner.provision(output, self.archive)
        self.assertEqual(b'keep', marker.read_bytes())

    def test_data_filter_still_rejects_path_escape(self):
        with self.assertRaises(tarfile.OutsideDestinationError):
            provisioner.exact_vendor_filter(tarfile.TarInfo('../escape'), str(self.directory))

    def test_owner_metadata_is_stripped_while_vendor_mode_is_preserved(self):
        member = tarfile.TarInfo('legal')
        member.mode, member.uid, member.gid = 0o444, 123, 456
        checked = provisioner.exact_vendor_filter(member, str(self.directory))
        self.assertIsNone(checked.uid)
        self.assertIsNone(checked.gid)
        self.assertEqual(0o444, checked.mode)


if __name__ == '__main__':
    unittest.main()
