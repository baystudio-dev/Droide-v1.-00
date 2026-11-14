"""Independent actual XML admission negatives, without running Android or Gradle."""
import copy
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_current_debug_apk as recipe


class RuntimeXmlEvidence(unittest.TestCase):
    def fixture(self, source):
        directory = source / 'app/build-current-debug/test-results/testDebugUnitTest'
        directory.mkdir(parents=True)
        receipt = {'expectedCounts': dict(recipe.EXPECTED_RUNTIME_COUNTS),
                   'actualCounts': dict(recipe.EXPECTED_RUNTIME_COUNTS), 'xml': {},
                   'offlineFrameworks': dict(recipe.EXPECTED_FRAMEWORK),
                   'loadedFrameworks': sorted(recipe.EXPECTED_FRAMEWORK)}
        for name, count in recipe.EXPECTED_RUNTIME_COUNTS.items():
            suite = ET.Element('testsuite', name=name, tests=str(count), failures='0', errors='0', skipped='0')
            for number in range(count):
                ET.SubElement(suite, 'testcase', classname=name, name='case' + str(number))
            ET.SubElement(suite, 'system-out').text = '\n'.join(
                'CURRENT_APP_LOADED_FRAMEWORK file=' + name + ' sha256=' + digest
                for name, digest in recipe.EXPECTED_FRAMEWORK.items())
            path = directory / ('TEST-' + name + '.xml')
            path.write_bytes(ET.tostring(suite))
            receipt['xml'][str(path)] = recipe.inputs.sha(path.read_bytes())
        return receipt

    def test_exact_cohort_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            receipt = self.fixture(source)
            self.assertEqual(receipt['xml'], recipe.verify_runtime_xml(source, receipt))

    def test_mutated_evidence_rejected_even_with_rehashed_xml(self):
        for mutation in ('skipped', 'failure', 'count', 'duplicate', 'owner', 'missing-suite', 'changed-hash', 'summary', 'framework-summary', 'missing-framework-log'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temp:
                source = Path(temp)
                receipt = self.fixture(source)
                path = Path(next(iter(receipt['xml'])))
                suite = ET.fromstring(path.read_bytes())
                if mutation in ('skipped', 'failure'):
                    ET.SubElement(suite.find('testcase'), mutation)
                elif mutation == 'count':
                    suite.set('tests', '71')
                elif mutation == 'duplicate':
                    suite.findall('testcase')[1].set('name', 'case0')
                elif mutation == 'owner':
                    suite.find('testcase').set('classname', 'decoy.Other')
                elif mutation == 'framework-summary':
                    receipt['loadedFrameworks'] = []
                elif mutation == 'missing-framework-log':
                    for xml_path in receipt['xml']:
                        xml = ET.fromstring(Path(xml_path).read_bytes())
                        xml.find('system-out').text = ''
                        Path(xml_path).write_bytes(ET.tostring(xml))
                        receipt['xml'][xml_path] = recipe.inputs.sha(Path(xml_path).read_bytes())
                    suite.find('system-out').text = ''
                elif mutation == 'summary':
                    receipt['actualCounts'] = {'decoy.Other': 71}
                path.write_bytes(ET.tostring(suite))
                receipt['xml'][str(path)] = recipe.inputs.sha(path.read_bytes())
                if mutation == 'missing-suite':
                    path.unlink()
                if mutation == 'changed-hash':
                    receipt['xml'][str(path)] = '0' * 64
                with self.assertRaises(ValueError):
                    recipe.verify_runtime_xml(source, receipt)


if __name__ == '__main__':
    unittest.main()
