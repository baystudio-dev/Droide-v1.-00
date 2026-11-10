"""Corrupt or interrupted runtime downloads must never become admitted test inputs."""
import hashlib
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from provision_test_runtime import ensure_artifact


class HostTestRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.content = b'verified test artifact'
        self.artifact = dict(name='runtime.jar', url='https://example.invalid/runtime.jar',
                             bytes=len(self.content), sha512=hashlib.sha512(self.content).hexdigest())

    def test_offline_accepts_only_exact_pinned_bytes(self):
        target = self.root / 'runtime.jar'
        target.write_bytes(self.content)
        with patch('provision_test_runtime.urlopen', side_effect=AssertionError('unexpected network')):
            self.assertEqual(target, ensure_artifact(self.root, self.artifact, offline=True))
            target.write_bytes(b'x' * len(self.content))
            with self.assertRaisesRegex(ValueError, 'corrupt offline'):
                ensure_artifact(self.root, self.artifact, offline=True)

    def test_invalid_download_preserves_previous_file_and_removes_staging(self):
        target = self.root / 'runtime.jar'
        target.write_bytes(b'previous')
        for content in [b'incomplete', b'x' * len(self.content), self.content + b'oversized']:
            with patch('provision_test_runtime.urlopen', return_value=io.BytesIO(content)):
                with self.assertRaises(ValueError):
                    ensure_artifact(self.root, self.artifact)
            self.assertEqual(b'previous', target.read_bytes())
            self.assertEqual([target], list(self.root.iterdir()))

    def test_verified_download_replaces_file_atomically(self):
        target = self.root / 'runtime.jar'
        target.write_bytes(b'previous')
        with patch('provision_test_runtime.urlopen', return_value=io.BytesIO(self.content)):
            self.assertEqual(target, ensure_artifact(self.root, self.artifact))
        self.assertEqual(self.content, target.read_bytes())
        self.assertEqual([target], list(self.root.iterdir()))

    def test_artifact_cannot_escape_its_cache_directory(self):
        with self.assertRaisesRegex(ValueError, 'artifact name'):
            ensure_artifact(self.root, dict(self.artifact, name='../runtime.jar'))
