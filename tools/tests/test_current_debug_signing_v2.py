import importlib.util
from pathlib import Path
import tempfile
import unittest

P = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('signing', P/'current_debug_signing_v2.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class SigningTests(unittest.TestCase):
    def key(self):
        t=tempfile.TemporaryDirectory(); self.addCleanup(t.cleanup)
        p=Path(t.name)/'key';p.write_bytes(b'synthetic private input');return p
    def test_unchanged(self):
        p=self.key();s=m.snapshot(p);m.unchanged(s);self.assertEqual(p.read_bytes(),b'synthetic private input')
    def test_changed_bytes(self):
        p=self.key();s=m.snapshot(p);p.write_bytes(b'changed')
        with self.assertRaises(ValueError):m.unchanged(s)
    def test_same_bytes_new_inode(self):
        p=self.key();s=m.snapshot(p);p.rename(p.with_suffix('.old'));p.write_bytes(b'synthetic private input')
        with self.assertRaises(ValueError):m.unchanged(s)
    def test_missing(self):
        p=self.key();p.unlink()
        with self.assertRaises(FileNotFoundError):m.snapshot(p)
    def test_link(self):
        p=self.key();q=p.with_suffix('.link');q.symlink_to(p)
        with self.assertRaises(ValueError):m.snapshot(q)
    def test_empty_and_large(self):
        p=self.key()
        for data in (b'',b'x'*(1024*1024+1)):
            p.write_bytes(data)
            with self.assertRaises(ValueError):m.snapshot(p)
    def test_wrong_certificate(self):
        with self.assertRaises(ValueError):m.require_certificate(b'foreign',m.DELIVERED_CERTIFICATE_SHA256)
    def test_wrong_requested_identity(self):
        with self.assertRaises(ValueError):m.require_certificate(b'foreign','0'*64)
    def test_redaction_preserves_execution_record(self):
        original={'argv':['keytool','-storepass','secret','-keypass','other'], 'environment':{m.PASSWORD_ENV:'secret','LANG':'C'}}
        result=m.redacted_record(original)
        self.assertNotIn('secret',str(result));self.assertNotIn('other',str(result));self.assertEqual(original['argv'][2],'secret');self.assertEqual(result['environment']['LANG'],'C')
    def test_no_generation_or_key_copy(self):
        source=(P/'build_current_debug_apk_v2.py').read_text()
        self.assertNotIn('-genkeypair',source);self.assertNotIn("out / 'debug.keystore'",source)
        self.assertIn("'-PDROIDE_CURRENT_DEBUG_KEYSTORE=' + key['path']",source)
        self.assertIn('signing.require_certificate',source);self.assertIn('finally:\n        signing.unchanged(key)',source)
        self.assertIn('signing.redacted_record(result)',source)
        self.assertIn("Path(key['path'])",source)

if __name__=='__main__':unittest.main()
