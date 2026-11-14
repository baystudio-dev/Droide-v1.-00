"""R63 final APK admission cannot mistake engine evidence for complete app proof."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import verify_private_r63_resize_apk as target


class R63FinalApkBlockedTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='r63-blocked-apk-')
        self.root = Path(self.temp.name)
        self.apk = self.root / 'candidate.apk'; self.apk.write_bytes(b'engine-with-stale-app')
        self.report = self.root / 'outputs/report.json'; self.report.parent.mkdir()
        self.export = self.report.parent / target.EXPORT_NAME
        self.report.write_text('{"status":"old-success"}')
        self.export.write_bytes(b'old-apk-export')

    def tearDown(self): self.temp.cleanup()

    def run_gate(self):
        return target.publish_verified_apk(self.root, self.apk, self.report, self.root / 'sdk',
            self.root / 'jdk', self.root / 'keystore', self.root / 'r8.jar', self.root / 'android.jar')

    def test_missing_exact_app_binding_rejects_and_clears_stale_outputs(self):
        with self.assertRaisesRegex(target.BindingError, 'exact app-side binding'):
            self.run_gate()
        self.assertFalse(self.report.exists())
        self.assertFalse(self.export.exists())
        self.assertEqual(b'engine-with-stale-app', self.apk.read_bytes())

    def test_repeated_call_has_no_success_path(self):
        for _ in range(2):
            with self.assertRaisesRegex(target.BindingError, 'cannot establish APK completeness'):
                self.run_gate()
            self.assertFalse(self.report.exists()); self.assertFalse(self.export.exists())

    def test_claimed_success_receipt_cannot_supply_app_proof(self):
        self.report.write_text('{"allAppClassesBound":true,"deviceValidated":true}')
        with self.assertRaisesRegex(target.BindingError, 'No APK has been admitted'):
            self.run_gate()
        self.assertFalse(self.report.exists()); self.assertFalse(self.export.exists())

    def test_cli_rejects_and_clears_stale_outputs(self):
        args = [sys.executable, '-B', str(TOOLS / 'verify_private_r63_resize_apk.py')]
        for name, path in (('root', self.root), ('apk', self.apk), ('report', self.report),
                           ('build-tools', self.root / 'sdk'), ('java-home', self.root / 'jdk'),
                           ('debug-keystore', self.root / 'keystore'), ('r8-jar', self.root / 'r8.jar'),
                           ('android-jar', self.root / 'android.jar')):
            args.extend(['--' + name, str(path)])
        result = subprocess.run(args, capture_output=True, text=True, timeout=30)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('PRIVATE_R63_RESIZE_APK_BLOCKED', result.stderr)
        self.assertNotIn('VERIFIED_DEVICE_UNVALIDATED', result.stdout)
        self.assertFalse(self.report.exists()); self.assertFalse(self.export.exists())

    def test_input_output_alias_is_rejected_without_deleting_input(self):
        self.apk = self.export
        with self.assertRaisesRegex(target.BindingError, 'paths overlap'): self.run_gate()
        self.assertTrue(self.apk.exists())


if __name__ == '__main__': unittest.main()
