from pathlib import Path
import hashlib
import json
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'third_party/goose-editor-data'


class GooseEditorDataTest(unittest.TestCase):
    def test_only_editor_data_is_vendored_and_hashes_match(self):
        manifest = json.loads((BUNDLE / 'manifest.json').read_text())
        self.assertEqual('Apache-2.0', manifest['license'])
        self.assertEqual({'LICENSE', 'README.md', 'manifest.json', 'goose.tmLanguage.json',
                          'language-configuration.json', 'goose-light-icon.svg'}, {p.name for p in BUNDLE.iterdir()})
        for entry in manifest['files']:
            self.assertIn(manifest['commit'], entry['url'])
            self.assertEqual(entry['sha256'], hashlib.sha256((BUNDLE / entry['file']).read_bytes()).hexdigest())

    def test_grammar_and_full_license_are_packaged_verbatim(self):
        self.assertEqual((BUNDLE / 'goose.tmLanguage.json').read_bytes(),
                         (ROOT / 'app/src/main/resources/droide/grammars/goose.tmLanguage.json').read_bytes())
        self.assertEqual((BUNDLE / 'LICENSE').read_bytes(),
                         (ROOT / 'app/src/main/assets/legal/licenses/Goose-Apache-2.0.txt').read_bytes())
        notice = json.loads((ROOT / 'app/src/main/assets/legal/open_source_licenses.json').read_text())
        self.assertIn('goose-editor-data', json.dumps(notice))

    def test_android_vector_preserves_original_icon_paths(self):
        svg = ET.parse(BUNDLE / 'goose-light-icon.svg').getroot()
        drawable = ET.parse(ROOT / 'app/src/main/res/drawable/filetype_goose.xml').getroot()
        paths = [re.sub(r'\s+', ' ', p.attrib['d']).strip() for p in svg.iter('{http://www.w3.org/2000/svg}path')]
        android = '{http://schemas.android.com/apk/res/android}'
        self.assertEqual(paths, [p.attrib[android + 'pathData'] for p in drawable.iter('path')])
        group = next(drawable.iter('group'))
        self.assertEqual('0.1', group.attrib[android + 'scaleX'])
        self.assertEqual('-0.1', group.attrib[android + 'scaleY'])


if __name__ == '__main__':
    unittest.main()
