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

    def test_asset_set_is_complete_unique_and_pinned(self):
        self.assertEqual('13025adc5ad10a5cc649528a2c1edec93069f1b9', self.manifest['commit'])
        assets = self.manifest['assets']
        self.assertTrue({'pdf', 'word', 'env'}.issubset(a['name'] for a in assets))
        for field in ['name', 'sourceFile', 'drawable']:
            self.assertEqual(len(assets), len({a[field] for a in assets}), field)
        self.assertEqual({p.name for p in BUNDLE.glob('*.svg')}, {a['sourceFile'] for a in assets})
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
            original_shapes = [node for node in source.iter() if node.tag in {SVG + 'path', SVG + 'circle'}]
            target_paths = list(target.iter('path'))
            self.assertEqual(len(original_shapes), len(target_paths), a['name'])
            for original, converted in zip(original_shapes, target_paths):
                if original.tag == SVG + 'path':
                    self.assertEqual(original.attrib['d'], converted.attrib[ANDROID + 'pathData'], a['name'])
                self.assertEqual('nonZero', converted.attrib[ANDROID + 'fillType'])
            viewbox = source.attrib['viewBox'].split()
            self.assertEqual(viewbox[2], target.attrib[ANDROID + 'viewportWidth'])
            self.assertEqual(viewbox[3], target.attrib[ANDROID + 'viewportHeight'])
            self.assertEqual('24dp', target.attrib[ANDROID + 'width'])
            self.assertEqual('24dp', target.attrib[ANDROID + 'height'])
            self.assertNotIn(ANDROID + 'tint', target.attrib)
            self.assertNotIn(ANDROID + 'autoMirrored', target.attrib)

    def test_color_adaptation_is_explicit_and_bounded(self):
        def source_fills(node, inherited='#000000'):
            fill = node.attrib.get('fill', inherited)
            if node.tag in {SVG + 'path', SVG + 'circle'}:
                yield fill
            for child in node:
                yield from source_fills(child, fill)

        for a in self.manifest['assets']:
            source = ET.parse(BUNDLE / a['sourceFile']).getroot()
            target = ET.parse(ROOT / a['drawable']).getroot()
            for fill, path in zip(source_fills(source), target.iter('path')):
                if a['name'] == 'word':
                    self.assertEqual('#01579b', fill)
                    expected = '#1e88e5'
                elif fill == 'none':
                    expected = '#00000000'
                else:
                    expected = fill if len(fill) == 7 else '#' + ''.join(c * 2 for c in fill[1:])
                self.assertEqual(expected.lower(), path.attrib[ANDROID + 'fillColor'].lower(), a['name'])
        self.assertIn('#01579b to #1e88e5', self.manifest['conversion'])

    def test_circles_preserve_exact_geometry_and_group_translation(self):
        svg = ET.fromstring('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 50 50">'
                            '<g transform="translate(.21 -247.01)" fill="#abc">'
                            '<circle cx="13.497" cy="281.63" r="9.555"/></g></svg>')
        converted = ET.fromstring('<vector xmlns:android="http://schemas.android.com/apk/res/android">' +
                                  generator.vector_paths(svg) + '</vector>')
        group = converted.find('group')
        self.assertEqual('0.21', group.attrib[ANDROID + 'translateX'])
        self.assertEqual('-247.01', group.attrib[ANDROID + 'translateY'])
        circle = group.find('path')
        self.assertEqual('#aabbcc', circle.attrib[ANDROID + 'fillColor'])
        self.assertEqual('M3.942 281.63a9.555 9.555 0 1 0 19.11 0a9.555 9.555 0 1 0 -19.11 0z',
                         circle.attrib[ANDROID + 'pathData'])

    def test_unsupported_svg_features_fail_instead_of_silently_changing_artwork(self):
        for child in ['<g transform="scale(2)"><path d="M0 0"/></g>',
                      '<path d="M0 0" stroke="#fff"/>', '<path d="M0 0" fill-opacity="0.5"/>',
                      '<rect x="0" y="0" width="2" height="2"/>',
                      '<circle cx="0" cy="0" r="-1"/>', '<path fill="url(#gradient)" d="M0 0"/>']:
            with self.subTest(child=child), self.assertRaises(ValueError):
                generator.vector_paths(ET.fromstring('<svg xmlns="http://www.w3.org/2000/svg">' + child + '</svg>'))

    def test_upstream_recolor_restriction_is_respected(self):
        svg = ET.fromstring('<svg xmlns="http://www.w3.org/2000/svg">'
                            '<path d="M0 0" fill="#ffffff" data-mit-no-recolor="true"/></svg>')
        with self.assertRaises(ValueError):
            generator.vector_paths(svg, ('#ffffff', '#000000'))

    def test_every_resolver_symbol_has_one_existing_resource(self):
        text = (ROOT / 'app/src/main/java/com/baystudio/droide/ui/FileTypeBrand.kt').read_text()
        names = set(re.findall(r'R\.drawable\.(filetype_\w+)', text))
        self.assertIn('filetype_pdf', names)
        self.assertIn('filetype_word', names)
        self.assertIn('filetype_env', names)
        self.assertTrue({Path(a['drawable']).stem for a in self.manifest['assets']}.issubset(names))
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
        image_branch = text[text.index('    if (resource != null) {'):text.index('    Icon(')]
        self.assertIn('painterResource(resource)', image_branch)
        self.assertIn('modifier = modifier.size(size)', image_branch)
        self.assertNotIn('colorFilter', image_branch)
        self.assertIn('Icons.AutoMirrored.Outlined.InsertDriveFile', text)
        for name in ['FileExplorer', 'EditorTabs', 'QuickOpenDialog', 'ExtensionBrand']:
            consumer = (ROOT / f'app/src/main/java/com/baystudio/droide/ui/{name}.kt').read_text()
            self.assertIn('FileTypeBrandIcon(', consumer)


if __name__ == '__main__':
    unittest.main(verbosity=2)
