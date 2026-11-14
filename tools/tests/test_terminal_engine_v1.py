"""Pure contract negatives using historical bytes only as fixtures, not new build proof."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import terminal_engine_v1 as V


class TerminalEngineRecipeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = TOOLS.parent
        cls.recipe = V.contract(cls.root)
        cls.base = V.data(cls.root / cls.recipe['baseAar'])
        cls.replacement = {}
        for stage, folder in zip(cls.recipe['stages'], ['r46', 'r63']):
            directory = cls.root / 'third_party/termux/derivatives' / folder / 'build/primary/compiled'
            cls.replacement[stage['name']] = {name: V.data(directory / name) for name in stage['compiledClasses']}

    def first(self):
        stage = self.recipe['stages'][0]
        return V.merge(self.base, self.replacement[stage['name']], stage)

    def test_pinned_source_inputs(self):
        with patch.object(V.inspect, 'run_baseline_gate', side_effect=AssertionError('historical gate must not run')):
            result = V.source_inputs(self.root, self.recipe)
        self.assertEqual(result[V.CONTRACT], V.CONTRACT_SHA)

    def test_exact_two_stage_composition_and_jni(self):
        stage = self.recipe['stages'][1]
        final = V.merge(self.first(), self.replacement[stage['name']], stage)
        inspection = V.inspect_final(final, self.recipe)
        self.assertEqual(len(inspection['jniMethods']), 12)
        self.assertFalse(inspection['fullSourceRebuild'])
        self.assertTrue(inspection['binaryBaseRetained'])

    def test_unchanged_base_outside_selected_families(self):
        stage = self.recipe['stages'][0]
        before = V.archive(V.archive(self.base)['classes.jar'])
        after = V.archive(V.archive(self.first())['classes.jar'])
        for name in before:
            if not any(V.in_family(name, family) for family in stage['sources']):
                self.assertEqual(before[name], after[name])

    def test_missing_compiled_class_rejected(self):
        stage = self.recipe['stages'][0]
        values = dict(self.replacement[stage['name']]); values.pop(next(iter(values)))
        with self.assertRaisesRegex(ValueError, 'inventory'):
            V.merge(self.base, values, stage)

    def test_extra_compiled_class_rejected(self):
        stage = self.recipe['stages'][0]
        values = dict(self.replacement[stage['name']]); values['unexpected.class'] = next(iter(values.values()))
        with self.assertRaisesRegex(ValueError, 'inventory'):
            V.merge(self.base, values, stage)

    def test_changed_body_rejected_even_with_same_inventory(self):
        stage = self.recipe['stages'][0]
        values = dict(self.replacement[stage['name']])
        name = next(iter(values)); values[name] = values[name][:-1] + bytes([values[name][-1] ^ 1])
        with self.assertRaisesRegex(ValueError, 'body'):
            V.merge(self.base, values, stage)

    def test_native_aar_entry_rejected(self):
        entries = V.archive(self.base); entries['jni/x86/libtermux.so'] = b'not allowed'
        stage = self.recipe['stages'][0]
        with self.assertRaisesRegex(ValueError, 'native'):
            V.merge(V.inspect.deterministic_zip(entries), self.replacement[stage['name']], stage)

    def test_wrong_final_jni_inventory_rejected(self):
        with self.assertRaisesRegex(ValueError, 'JNI (native inventory|class identity)'):
            V.inspect_final(self.base, self.recipe)

    def test_wrong_expected_jni_descriptor_rejected(self):
        stage = self.recipe['stages'][1]
        final = V.merge(self.first(), self.replacement[stage['name']], stage)
        recipe = copy.deepcopy(self.recipe)
        recipe['jniReplacement']['methods'][0]['descriptor'] = '()J'
        with self.assertRaisesRegex(ValueError, 'JNI native inventory'):
            V.inspect_final(final, recipe)

    def test_duplicate_zip_entry_rejected(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as z:
            z.writestr('a', b'1'); z.writestr('a', b'2')
        with self.assertRaises(SystemExit):
            V.archive(stream.getvalue())

    def test_traversal_zip_entry_rejected(self):
        with self.assertRaises(SystemExit):
            V.archive(V.inspect.deterministic_zip({'../escape': b'bad'}))

    def test_wrong_source_pin_rejected(self):
        recipe = copy.deepcopy(self.recipe)
        recipe['sourceSha256'][recipe['baseAar']] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'input pin'):
            V.source_inputs(self.root, recipe)

    def test_duplicate_receipt_key_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'receipt.json'
            path.write_text('{"status":"bad","status":"good"}')
            with self.assertRaisesRegex(ValueError, 'duplicate JSON'):
                V.read_json(path)

    def test_existing_output_is_never_adopted(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'output'; output.mkdir()
            marker = output / 'marker'; marker.write_text('preserve')
            with self.assertRaisesRegex(ValueError, 'output exists'):
                V.build(self.root, Path('/unused-jdk'), Path('/unused-sdk/android.jar'), output)
            self.assertEqual(marker.read_text(), 'preserve')

    def test_selected_source_cannot_borrow_executing_tool(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, 'executing tool'):
                V.contract(Path(temporary))

    def test_selected_source_cannot_borrow_inspection_helper(self):
        with patch.object(V.inspect, '__file__', '/unrelated/verify_termux_pty_derivative.py'):
            with self.assertRaisesRegex(ValueError, 'inspection helper'):
                V.contract(self.root)

    def test_source_family_match_is_exact(self):
        self.assertTrue(V.in_family('com/termux/terminal/TerminalSession$1.class', 'TerminalSession'))
        self.assertFalse(V.in_family('com/termux/terminal/TerminalSessionEvil.class', 'TerminalSession'))

    def test_sourcepath_and_processors_are_explicit(self):
        argv = V.command(Path('/owned/build'), Path('/jdk'), self.recipe['stages'][0])
        for flag in ['-proc:none', '-implicit:none', '-sourcepath', '--release']:
            self.assertIn(flag, argv)
        self.assertEqual(sum(value.endswith('.java') for value in argv), 9)

    def test_output_source_overlap_rejected(self):
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            V.disjoint_output(self.root / 'new-output', self.root)

    def test_output_ancestor_overlap_rejected(self):
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            V.disjoint_output(self.root.parent, self.root)

    def test_symlink_ancestor_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'real').mkdir(); (root / 'alias').symlink_to(root / 'real')
            with self.assertRaisesRegex(ValueError, 'symlinked'):
                V.checked(root / 'alias/new-output')


if __name__ == '__main__':
    unittest.main()
