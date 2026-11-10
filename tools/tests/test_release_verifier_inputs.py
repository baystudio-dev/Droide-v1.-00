"""Receipt dependencies must be admitted before expensive release build checks."""
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_source_release as verifier


class ReleaseVerifierInputTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'tools').mkdir()
        self.expected = {}
        for name in verifier.VERIFIER_SOURCES:
            content = ('source for ' + name).encode()
            (self.root / 'tools' / name).write_bytes(content)
            self.expected[name] = hashlib.sha256(content).hexdigest()

    def test_receipt_records_every_verifier_dependency(self):
        self.assertEqual(self.expected, verifier.verifier_sources(self.root))

    def test_missing_dependency_fails_before_other_release_checks(self):
        for name in verifier.VERIFIER_SOURCES:
            with self.subTest(name=name):
                path = self.root / 'tools' / name
                content = path.read_bytes()
                path.unlink()
                with patch.object(verifier, 'native_contract') as native, patch.object(verifier, 'run') as command:
                    with self.assertRaisesRegex(ValueError, 'Missing or empty release verifier source'):
                        verifier.verify_inputs(self.root)
                    native.assert_not_called()
                    command.assert_not_called()
                path.write_bytes(content)

    def test_empty_dependency_fails_before_other_release_checks(self):
        path = self.root / 'tools' / verifier.VERIFIER_SOURCES[-1]
        path.write_bytes(b'')
        with patch.object(verifier, 'native_contract') as native, patch.object(verifier, 'run') as command:
            with self.assertRaisesRegex(ValueError, 'Missing or empty release verifier source'):
                verifier.verify_inputs(self.root)
            native.assert_not_called()
            command.assert_not_called()
