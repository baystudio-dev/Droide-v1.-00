#!/usr/bin/env python3
"""Multi-class parser regressions and optional actual pinned-R8 R63 binding tests.

Synthetic ZIP containers below are parser-only inputs, never distributable APKs
or native/device validation. The real integration builds an ephemeral reference
from the exact admitted candidate with the recovered pinned R8/JDK/API36.
"""
from pathlib import Path
import os
import struct
import sys
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'tools/tests')]
import verify_r63_resize_dex_binding as B
import verify_r63_resize_derivative as V
import test_pty_dex_binding as F


def fixture(target, **kwargs):
    with mock.patch.object(F.binding, 'TARGET', target):
        return F.fixture(**kwargs)


class MultiClassParserTests(unittest.TestCase):
    targets = ('Lcom/termux/terminal/TerminalSession;',
               'Lcom/termux/terminal/TerminalSessionClient;',
               'Lcom/termux/terminal/TerminalResizeState;',
               'Lcom/termux/terminal/TerminalResizeState$Receipt;')

    def entries(self):
        return {('classes.dex' if i == 0 else 'classes' + str(i + 1) + '.dex'): fixture(n)
                for i, n in enumerate(self.targets)}

    def compare(self, actual, reference):
        return B.compare_candidate_classes(actual, reference, [n[1:-1] + '.class' for n in self.targets])

    def test_all_classes_compared(self):
        result = self.compare(self.entries(), self.entries())
        self.assertEqual(len(self.targets), result['boundClassCount'])
        self.assertEqual(set(self.targets), set(result['classBindingByDescriptor']))

    def test_mutation_in_every_target_is_detected(self):
        for index, target in enumerate(self.targets):
            with self.subTest(target=target), self.assertRaisesRegex(B.BindingError, 'differs from fresh'):
                actual = self.entries()
                name = list(actual)[index]
                actual[name] = fixture(target, words=[0x1012, 0x000e])
                self.compare(actual, self.entries())

    def test_duplicate_across_dex_rejected(self):
        actual = self.entries(); actual['classes9.dex'] = actual['classes.dex']
        with self.assertRaisesRegex(B.BindingError, 'duplicate class'):
            self.compare(actual, self.entries())

    def test_missing_nested_class_and_extra_namespace_class_rejected(self):
        actual = self.entries(); actual.pop('classes4.dex')
        with self.assertRaisesRegex(B.BindingError, 'namespace differs'):
            self.compare(actual, self.entries())
        actual = self.entries(); actual['classes9.dex'] = fixture('Lcom/termux/terminal/Injected;')
        with self.assertRaisesRegex(B.BindingError, 'namespace differs'):
            self.compare(actual, self.entries())

    def test_reference_omission_cannot_shrink_required_targets(self):
        ref = self.entries(); ref.pop('classes4.dex')
        with self.assertRaisesRegex(B.BindingError, 'omitted an admitted class'):
            self.compare(ref, ref)

    def test_fresh_compiler_synthetics_are_also_compared(self):
        ref = self.entries(); ref['classes9.dex'] = fixture('Lcom/termux/terminal/TerminalSession$Synthetic;')
        actual = dict(ref)
        self.assertEqual(5, self.compare(actual, ref)['boundClassCount'])
        actual['classes9.dex'] = fixture('Lcom/termux/terminal/TerminalSession$Synthetic;', constant=17)
        with self.assertRaisesRegex(B.BindingError, 'differs from fresh'):
            self.compare(actual, ref)

    def test_nested_dex_names_fail_closed(self):
        actual = self.entries(); actual['assets/hidden.dex'] = fixture(self.targets[0])
        with self.assertRaisesRegex(B.BindingError, 'DEX path/name'):
            self.compare(actual, self.entries())


@unittest.skipUnless(all(os.environ.get(key) for key in ('R63_R8_JAR', 'R63_JAVA_HOME', 'R63_ANDROID_JAR')),
                     'real compiler integration requires explicit recovered pinned tool paths')
class ActualPinnedCompilerTests(unittest.TestCase):
    def test_actual_reference_binds_all_classes_and_every_changed_new_class(self):
        with B.build_fresh_reference(ROOT, Path(os.environ['R63_R8_JAR']),
                                     Path(os.environ['R63_ANDROID_JAR']), Path(os.environ['R63_JAVA_HOME'])) as fresh:
            entries = fresh['dexEntries']
            paths = fresh['provenance']['candidateClassSha256ByPath']
            result = B.compare_candidate_classes(entries, entries, paths)
            self.assertGreaterEqual(result['boundClassCount'], len(paths))
            for path in V.review(ROOT)['changedClassPaths'] + V.review(ROOT)['newClassPaths']:
                target = 'L' + path[:-6] + ';'
                name, parsed = B.parsed_dexes(entries)[target]
                mutated = bytearray(entries[name])
                # Change the genuine target class access flags; preserve valid checksums.
                count, offset = parsed.u32(96), parsed.u32(100)
                matches = [offset + i * 32 for i in range(count)
                           if parsed.types[parsed.u32(offset + i * 32)] == target]
                self.assertEqual(1, len(matches))
                flags = parsed.u32(matches[0] + 4)
                struct.pack_into('<I', mutated, matches[0] + 4, flags ^ 0x10)
                actual = dict(entries); actual[name] = F.fix_checksums(mutated)
                with self.subTest(target=target), self.assertRaisesRegex(B.BindingError, 'differs from fresh'):
                    B.compare_candidate_classes(actual, entries, paths)


if __name__ == '__main__':
    unittest.main()
