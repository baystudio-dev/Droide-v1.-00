#!/usr/bin/env python3
"""Resource/provenance checks only. Does not link Android resources or render Compose."""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'third_party/material-document-icons'
ANDROID = '{http://schemas.android.com/apk/res/android}'
SVG = '{http://www.w3.org/2000/svg}'
spec = importlib.util.spec_from_file_location('generator', ROOT / 'tools/generate_document_file_icons.py')
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class DocumentFileIconAssetsTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((BUNDLE / 'manifest.json').read_text())

    def test_asset_set_and_pin_are_exact(self):
        self.assertEqual('13025adc5ad10a5cc649528a2c1edec93069f1b9', self.manifest['commit'])
        self.assertEqual(['pdf', 'word'], [a['name'] for a in self.manifest['assets']])
        self.assertEqual('MIT', self.manifest['license'])

    def test_pinned_source_bytes_hashes_and_immutable_urls(self):
        for a in self.manifest['assets']:
            data = (BUNDLE / a['sourceFile']).read_bytes()
            self.assertEqual(a['sourceBytes'], len(data))
            self.assertEqual(a['sourceSha256'], hashlib.sha256(data).hexdigest())
            self.assertIn('/' + self.manifest['commit'] + '/', a['sourceUrl'])
        for name in ['LICENSE', 'UPSTREAM-README.md']:
            data = (BUNDLE / name).read_bytes()
            self.assertEqual(self.manifest[name]['sha256'], hashlib.sha256(data).hexdigest())

    def test_generated_resources_match_recipe_byte_for_byte(self):
        for a in self.manifest['assets']:
            self.assertEqual(generator.vector_bytes(a), (ROOT / a['drawable']).read_bytes())

    def test_vector_paths_are_exact_upstream_artwork(self):
        for a in self.manifest['assets']:
            source = ET.parse(BUNDLE / a['sourceFile']).getroot()
            target = ET.parse(ROOT / a['drawable']).getroot()
            self.assertEqual('vector', target.tag)
            self.assertEqual(1, len(target))
            self.assertEqual(source[0].attrib['d'], target[0].attrib[ANDROID + 'pathData'])
            self.assertEqual('nonZero', target[0].attrib[ANDROID + 'fillType'])
            self.assertEqual('24', target.attrib[ANDROID + 'viewportWidth'])
            self.assertEqual('24', target.attrib[ANDROID + 'viewportHeight'])
            self.assertEqual('24dp', target.attrib[ANDROID + 'width'])
            self.assertEqual('24dp', target.attrib[ANDROID + 'height'])
            self.assertNotIn(ANDROID + 'tint', target.attrib)
            self.assertNotIn(ANDROID + 'autoMirrored', target.attrib)

    def test_color_adaptation_is_explicit_and_bounded(self):
        expected = {'pdf': ('#ef5350', '#ef5350'), 'word': ('#01579b', '#1e88e5')}
        for a in self.manifest['assets']:
            self.assertEqual(expected[a['name']], (a['sourceFill'], a['androidFill']))
            target = ET.parse(ROOT / a['drawable']).getroot()
            self.assertEqual(a['androidFill'], target[0].attrib[ANDROID + 'fillColor'])
        self.assertIn('#01579b to #1e88e5', self.manifest['conversion'])

    def test_every_resolver_symbol_has_one_existing_resource(self):
        text = (ROOT / 'app/src/main/java/com/baystudio/droide/ui/FileTypeBrand.kt').read_text()
        names = set(re.findall(r'R\.drawable\.(filetype_\w+)', text))
        self.assertIn('filetype_pdf', names)
        self.assertIn('filetype_word', names)
        for name in names:
            paths = [p for folder in (ROOT / 'app/src/main/res').glob('drawable*') for p in folder.glob(name + '.*')]
            self.assertEqual(1, len(paths), (name, paths))
            self.assertGreater(paths[0].stat().st_size, 0)

    def test_bundled_license_and_catalog_are_consistent(self):
        catalog = json.loads((ROOT / 'app/src/main/assets/legal/open_source_licenses.json').read_text())
        entries = [a for a in catalog['entries'] if a['id'] == 'material-document-file-icons']
        self.assertEqual(1, len(entries))
        entry = entries[0]
        self.assertEqual([], entry['dependencyKeys'])
        self.assertEqual(self.manifest['commit'], entry['version'])
        self.assertEqual('MIT', entry['spdx'])
        self.assertEqual((BUNDLE / 'LICENSE').read_bytes(), (ROOT / 'app/src/main/assets' / entry['licenseTextAsset']).read_bytes())
        self.assertIn('not official Adobe or Microsoft', entry['notice'])
        self.assertIn('Word blue is adjusted', entry['notice'])

    def test_all_new_third_party_files_are_hash_bound(self):
        hashes = dict((line.split(None, 1)[1].strip(), line.split(None, 1)[0])
                      for line in (ROOT / 'third_party/ARTIFACTS_SHA256.txt').read_text().splitlines()
                      if line.strip() and not line.startswith('#'))
        for path in BUNDLE.iterdir():
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), hashes[path.relative_to(ROOT).as_posix()])

    def test_shared_icon_keeps_untinted_image_and_fallback(self):
        text = (ROOT / 'app/src/main/java/com/baystudio/droide/ui/FileTypeBrand.kt').read_text()
        image_branch = text[text.index('    if (resource != null) {'):text.index('    val shape = RoundedCornerShape')]
        self.assertIn('painterResource(resource)', image_branch)
        self.assertIn('modifier = modifier.size(size)', image_branch)
        self.assertNotIn('colorFilter', image_branch)
        self.assertIn('FileIconRegistry.fallbackToken(path)', text)
        for name in ['FileExplorer', 'EditorTabs', 'QuickOpenDialog', 'ExtensionBrand']:
            consumer = (ROOT / f'app/src/main/java/com/baystudio/droide/ui/{name}.kt').read_text()
            self.assertIn('FileTypeBrandIcon(', consumer)


if __name__ == '__main__':
    unittest.main(verbosity=2)
