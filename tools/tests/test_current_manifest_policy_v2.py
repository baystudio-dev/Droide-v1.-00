import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import current_manifest_policy_v2 as m

BADGING="""package: name='com.baystudio.droide' versionCode='44' versionName='1.00-current-debug-unadmitted'
sdkVersion:'29'
targetSdkVersion:'36'
application-label:'Droide Current Debug (Unadmitted)'
application-debuggable
launchable-activity: name='com.baystudio.droide.MainActivity'
"""
XML='''N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    E: application (line=8)
      A: android:name(0x01010003)="com.baystudio.droide.DroideApplication"
      E: meta-data (line=9)
        A: android:name(0x01010003)="com.baystudio.droide.CURRENT_BUILD_STATUS"
        A: android:value(0x01010024)="UNADMITTED_DEVICE_UNVALIDATED"
'''
class ManifestTests(unittest.TestCase):
    def test_original_format_still_passes(self):m.verify_manifest_policy(BADGING,XML)
    def test_observed_new_spelling_passes_same_policy(self):m.verify_manifest_policy(BADGING.replace("sdkVersion:'29'","minSdkVersion:'29'"),XML.replace('A: android:','A: http://schemas.android.com/apk/res/android:'))
    def test_lexical_normalization_preserves_semantics(self):self.assertEqual(m.normalize(BADGING.replace("sdkVersion:'29'","minSdkVersion:'29'"),XML.replace('A: android:','A: http://schemas.android.com/apk/res/android:')),(BADGING,XML))
    def test_wrong_minimum_refused(self):
        with self.assertRaises(ValueError):m.verify_manifest_policy(BADGING.replace("sdkVersion:'29'","minSdkVersion:'28'"),XML)
    def test_duplicate_conflicting_minimum_refused(self):
        with self.assertRaises(ValueError):m.verify_manifest_policy(BADGING+"minSdkVersion:'29'\n",XML)
    def test_wrong_namespace_refused(self):
        x=XML.replace('A: android:','A: http://schemas.android.com/apk/res/android:').replace('N: android=http://schemas.android.com/apk/res/android','N: android=http://wrong')
        with self.assertRaises(ValueError):m.verify_manifest_policy(BADGING,x)
    def test_duplicate_namespace_refused(self):
        x=XML.replace('A: android:','A: http://schemas.android.com/apk/res/android:')+XML.splitlines()[0]+'\n'
        with self.assertRaises(ValueError):m.verify_manifest_policy(BADGING,x)
    def test_wrong_package_refused(self):
        with self.assertRaises(ValueError):m.verify_manifest_policy(BADGING.replace("name='com.baystudio.droide'","name='other'"),XML)
    def test_wrong_application_refused(self):
        with self.assertRaises(ValueError):m.verify_manifest_policy(BADGING,XML.replace('com.baystudio.droide.DroideApplication','other.Application'))
    def test_missing_metadata_refused(self):
        with self.assertRaises(ValueError):m.verify_manifest_policy(BADGING,XML.replace('CURRENT_BUILD_STATUS','OTHER'))
    def test_promoted_status_refused(self):
        with self.assertRaises(ValueError):m.verify_manifest_policy(BADGING,XML.replace('UNADMITTED_DEVICE_UNVALIDATED','VERIFIED'))
    def test_actual_fixture_api_call_is_nongeneric(self):
        s=(Path(__file__).resolve().parent/'current-app-runtime/CurrentTerminalResizeBridgeContractTest.kt').read_text()
        self.assertEqual(s.count('RuntimeEnvironment.getApplication()'),1)
        self.assertNotIn('RuntimeEnvironment.getApplication<Application>()',s)
if __name__=='__main__':unittest.main()
