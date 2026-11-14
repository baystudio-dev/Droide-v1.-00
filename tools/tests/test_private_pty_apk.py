"""Publication-boundary tests. External gates are explicit mocks here, not APK evidence."""
from pathlib import Path
import hashlib, json, os, sys, tempfile, unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import verify_private_pty_apk as target

class PrivatePtyPublicationTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.apk=self.root/'input.apk';self.apk.write_bytes(b'candidate-apk-snapshot')
        self.report=self.root/'out/result.json';self.report.parent.mkdir()
        self.export=self.report.parent/target.EXPORT_NAME
        self.report.write_text('STALE');self.export.write_bytes(b'STALE')
        self.admission={'baselineReceipt':{'frozen':True},'output':{'sha256':'candidate-aar'}}
        self.calls=[]
        def native(root,apk,*args):
            self.calls.append(('native',apk));self.assertNotEqual(self.apk,apk)
            self.assertNotIn('JAVA_TOOL_OPTIONS',os.environ)
            return {'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'status':'BUILT_DEVICE_UNVALIDATED'}
        def binding(root,apk,**kwargs):
            self.calls.append(('binding',apk));return {'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest()}
        self.patches=[patch.object(target,'verify_jdk',return_value={'jdk':'pinned'}),
                      patch.object(target,'verify_sdk_tools',return_value={'sdk':'pinned'}),
                      patch.object(target,'verify_derivative_inputs',return_value=self.admission),
                      patch.object(target,'verify_apk',side_effect=native),
                      patch.object(target,'verify_apk_binding',side_effect=binding)]
        self.mocks=[p.start() for p in self.patches]
    def tearDown(self):
        for p in reversed(self.patches):p.stop()
        self.tmp.cleanup()
    def run_gate(self):
        return target.publish_verified_apk(self.root,self.apk,self.report,self.root/'tools',self.root/'jdk',
            self.root/'debug.keystore',self.root/'r8.jar',self.root/'android.jar')
    def reject(self):
        with self.assertRaises((ValueError,SystemExit,OSError)):self.run_gate()
        self.assertFalse(self.report.exists());self.assertFalse(self.export.exists())
    def test_snapshot_same_bytes_all_checks_export_and_report(self):
        with patch.dict(os.environ,{'JAVA_TOOL_OPTIONS':'must-not-reach-inspection'}):
            result=self.run_gate();self.assertEqual('must-not-reach-inspection',os.environ['JAVA_TOOL_OPTIONS'])
        self.assertEqual(self.apk.read_bytes(),self.export.read_bytes())
        self.assertEqual(self.calls[0][1],self.calls[1][1])
        self.assertEqual(result,json.loads(self.report.read_text()))
        self.assertFalse(result['releaseCompatible']);self.assertFalse(result['deviceValidated'])
        self.assertIsNone(result['runtimeEvidence'])
    def test_native_hash_drift_rejected(self):
        self.mocks[3].side_effect=lambda *a,**k:{'apkSha256':'wrong'};self.reject()
        self.mocks[4].assert_not_called()
    def test_binding_hash_drift_rejected(self):
        self.mocks[4].side_effect=lambda *a,**k:{'apkSha256':'wrong'};self.reject()
    def test_snapshot_replaced_during_native_checks_rejected(self):
        def drift(root,apk,*args):
            before=target.sha(apk.read_bytes());apk.write_bytes(b'substitution');return {'apkSha256':before}
        self.mocks[3].side_effect=drift;self.reject()
    def test_snapshot_replaced_during_binding_rejected(self):
        def drift(root,apk,**kwargs):
            before=target.sha(apk.read_bytes());apk.write_bytes(b'substitution');return {'apkSha256':before}
        self.mocks[4].side_effect=drift;self.reject()
    def test_original_replaced_during_binding_rejected(self):
        def drift(root,apk,**kwargs):
            self.apk.write_bytes(b'substitution');return {'apkSha256':target.sha(apk.read_bytes())}
        self.mocks[4].side_effect=drift;self.reject()
    def test_derivative_admission_drift_rejected(self):
        self.mocks[2].side_effect=[self.admission,{'changed':True}];self.reject()
    def test_failed_or_missing_pinned_compiler_does_not_publish(self):
        self.mocks[4].side_effect=target.BindingError('requires exact R8');self.reject()
    def test_native_system_exit_clears_stale_outputs(self):
        self.mocks[3].side_effect=SystemExit('signature failed');self.reject()
    def test_sdk_change_after_inspection_rejected(self):
        self.mocks[1].side_effect=[{'sdk':'pinned'},{'sdk':'changed'}];self.reject()
    def test_published_file_mutation_rejected(self):
        original=Path.replace
        def drift(path,destination):
            out=original(path,destination)
            if Path(destination)==self.export:self.export.write_bytes(b'substitution')
            return out
        with patch.object(Path,'replace',drift):self.reject()
    def test_input_symlink_is_rejected(self):
        real=self.root/'real.apk';self.apk.rename(real);self.apk.symlink_to(real)
        self.reject()
    def test_symlinked_tool_or_keystore_paths_clear_stale_outputs(self):
        for name in ('jdk','tools','debug.keystore','r8.jar','android.jar'):
            with self.subTest(name=name):
                real=self.root/('real-'+name);real.write_bytes(b'input')
                link=self.root/name;link.symlink_to(real)
                self.report.write_text('STALE');self.export.write_bytes(b'STALE')
                self.reject();link.unlink()
    def test_real_sdk_pin_verifier_rejects_unrecognized_tools(self):
        self.patches[1].stop()
        with self.assertRaises((ValueError,OSError)):target.verify_sdk_tools(self.root)

if __name__=='__main__':unittest.main()
