"""Vendor identity controls with tiny synthetic fixtures, never build evidence."""
import copy
import hashlib
import io
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import terminal_jdk_v2 as J


class VendorJdkTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.home = self.root / 'home'
        self.home.mkdir()
        self.home.chmod(0o755)
        legal = self.home / 'legal'
        legal.mkdir()
        legal.chmod(0o755)
        (self.home / 'legal/LICENSE').write_bytes(b'vendor'); (self.home / 'legal/LICENSE').chmod(0o644)
        (self.home / 'legal/ALIAS').symlink_to('LICENSE')
        self.archive = self.root / 'vendor.tar.gz'
        with tarfile.open(self.archive, 'w:gz') as t:
            for name, kind, mode, payload in [('jdk-test',tarfile.DIRTYPE,0o755,b''),
              ('jdk-test/legal',tarfile.DIRTYPE,0o755,b''),
              ('jdk-test/legal/LICENSE',tarfile.REGTYPE,0o644,b'vendor'),
              ('jdk-test/legal/ALIAS',tarfile.SYMTYPE,0o777,b'')]:
                entry=tarfile.TarInfo(name);entry.type=kind;entry.mode=mode;entry.size=len(payload)
                if kind==tarfile.SYMTYPE:entry.linkname='LICENSE'
                t.addfile(entry,io.BytesIO(payload))
        self.manifest={'version':'test','archiveSha256':hashlib.sha256(self.archive.read_bytes()).hexdigest(),
          'archiveUrl':'https://example.test/vendor','checksumUrl':'https://example.test/vendor.sha256','entries':{
          'legal':{'mode':0o755,'kind':'directory'},
          'legal/LICENSE':{'mode':0o644,'kind':'file','size':6,'sha256':hashlib.sha256(b'vendor').hexdigest()},
          'legal/ALIAS':{'mode':0o777,'kind':'symlink','target':'LICENSE','resolved':'legal/LICENSE'}}}

    def test_full_vendor_positive(self):
        self.assertEqual(J.verify(self.home,self.archive,self.manifest)['entries'],self.manifest['entries'])

    def test_wrong_archive_hash(self):
        self.manifest['archiveSha256']='0'*64
        with self.assertRaisesRegex(ValueError,'SHA'):J.verify(self.home,self.archive,self.manifest)

    def test_changed_installed_bytes(self):
        (self.home/'legal/LICENSE').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'identity'):J.verify(self.home,self.archive,self.manifest)

    def test_mode_drift(self):
        (self.home/'legal/LICENSE').chmod(0o755)
        with self.assertRaisesRegex(ValueError,'identity'):J.verify(self.home,self.archive,self.manifest)

    def test_extra_file(self):
        (self.home/'extra').write_text('extra')
        with self.assertRaisesRegex(ValueError,'identity'):J.verify(self.home,self.archive,self.manifest)

    def test_absolute_alias(self):
        p=self.home/'legal/ALIAS';p.unlink();p.symlink_to(self.home/'legal/LICENSE')
        with self.assertRaisesRegex(ValueError,'absolute'):J.verify(self.home,self.archive,self.manifest)

    def test_external_alias(self):
        outside=self.root/'outside';outside.write_text('vendor')
        p=self.home/'legal/ALIAS';p.unlink();p.symlink_to('../../outside')
        with self.assertRaisesRegex(ValueError,'external'):J.verify(self.home,self.archive,self.manifest)

    def test_dangling_alias(self):
        (self.home/'legal/LICENSE').unlink()
        with self.assertRaises(OSError):J.verify(self.home,self.archive,self.manifest)

    def test_directory_alias(self):
        p=self.home/'legal/ALIAS';p.unlink();p.symlink_to('.')
        with self.assertRaisesRegex(ValueError,'directory'):J.verify(self.home,self.archive,self.manifest)

    def test_vendor_manifest_file_pin(self):
        self.manifest['entries']['legal/LICENSE']['sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'manifest'):J.verify(self.home,self.archive,self.manifest)

    def test_input_root_alias(self):
        alias=self.root/'alias';alias.symlink_to(self.home)
        with self.assertRaisesRegex(ValueError,'symlinked'):J.verify(alias,self.archive,self.manifest)

if __name__=='__main__':unittest.main()
