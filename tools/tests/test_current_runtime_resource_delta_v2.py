from pathlib import Path
import hashlib,difflib,unittest
R=Path(__file__).resolve().parents[1]
class Delta(unittest.TestCase):
 def test_exact_preserved_baseline_and_reviewed_delta(self):
  old=(R/'current-app-runtime-contracts.gradle').read_bytes()
  self.assertEqual(hashlib.sha256(old).hexdigest(),'e0a0560b37b314b328a1320ee56296df8421acdb58c53b63d8bc0a80b83fc332')
  new=(R/'current-app-runtime-contracts-v2-base.gradle').read_text()
  actual=''.join(difflib.unified_diff(old.decode().splitlines(True),new.splitlines(True),fromfile='V1',tofile='V2'))
  self.assertEqual(actual,(R/'tests/current-runtime-resource-delta-v2.patch').read_text())
  self.assertNotIn('android.testConfig.useRelativePath=false',new)
  self.assertNotIn('System.setProperty',new)
if __name__=='__main__':unittest.main()
