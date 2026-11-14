#!/usr/bin/env python3
"""R63 fixed-scope and evidence mutation tests using actual double-build outputs."""
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import verify_r63_resize_derivative as V
import build_r63_resize_derivative as B


def jar_mutation(aar, mutate):
    outer = V.archive_entries(aar)
    classes = V.archive_entries(outer['classes.jar'])
    mutate(classes)
    outer['classes.jar'] = V.deterministic_zip(classes)
    return V.deterministic_zip(outer)


class ActualR63AarTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.aar = V.checked_file(ROOT, V.AAR)
        cls.review = V.review(ROOT)
        cls.classes = V.archive_entries(V.archive_entries(cls.aar)['classes.jar'])
        cls.result = V.verify_derivative_inputs(ROOT)

    def rejects(self, aar, pattern):
        with self.assertRaisesRegex(SystemExit, pattern): V.verify_aar_bytes(ROOT, aar)

    def test_actual_doublebuild_scope_is_exact(self):
        for stage in ('primary', 'independent'):
            work = ROOT / V.PUBLICATION / stage
            self.assertEqual(self.aar, (work / V.AAR_NAME).read_bytes())
            self.assertEqual(self.review['compiledSha256ByPath'], V.regular_tree(work / 'compiled'))
            for name in self.review['compiledSha256ByPath']:
                self.assertEqual(self.classes[name], (work / 'compiled' / name).read_bytes())
        self.assertFalse(self.result['releaseCompatible'])
        self.assertFalse(self.result['deviceValidated'])
        self.assertFalse(self.result['nativeBuildPerformed'])
        self.assertIsNone(self.result['executionEvidence'])

    def test_every_changed_and_new_class_is_byte_pinned(self):
        for name in self.review['changedClassPaths'] + self.review['newClassPaths']:
            with self.subTest(name=name):
                self.rejects(jar_mutation(self.aar, lambda d: d.update({name: d[name] + b'x'})),
                             'reviewed actual compiler output')

    def test_non_candidate_classes_including_pty_and_jni_are_immutable(self):
        for name in (V.R46.JNI_CLASS, V.R46.PTY_CLASS, 'com/termux/terminal/TerminalEmulator.class'):
            with self.subTest(name=name):
                self.rejects(jar_mutation(self.aar, lambda d: d.update({name: d[name] + b'x'})),
                             'changed/new class set')

    def test_missing_new_class_rejected(self):
        for name in self.review['newClassPaths']:
            with self.subTest(name=name):
                self.rejects(jar_mutation(self.aar, lambda d: d.pop(name)), 'entry set changed')

    def test_unreviewed_new_nested_class_rejected(self):
        self.rejects(jar_mutation(self.aar, lambda d: d.update({
            'com/termux/terminal/TerminalSession$Unreviewed.class': b'fake'})), 'entry set changed')

    def test_stale_r46_aar_rejected(self):
        self.rejects(V.checked_file(ROOT, V.R46.AAR), 'entry set changed|changed/new class set')

    def test_native_payload_and_resource_mutation_rejected(self):
        original = V.archive_entries(self.aar)
        outer = dict(original, **{'jni/arm64-v8a/libtermux.so': b'fake'})
        self.rejects(V.deterministic_zip(outer), 'entry set changed')
        name = next(n for n in original if n != 'classes.jar' and not n.endswith('/'))
        original[name] += b'x'
        self.rejects(V.deterministic_zip(original), 'unrelated AAR entry')

    def test_r46_full_admission_still_passes(self):
        baseline = V.R46.verify_derivative_inputs(ROOT)
        self.assertEqual(self.review['baselineAarSha256'], baseline['output']['sha256'])

    def test_existing_publication_is_never_replaced(self):
        with self.assertRaisesRegex(SystemExit, 'publication already exists'):
            B.build(ROOT, ROOT / 'unused-jdk', ROOT / 'unused-api.jar')


class EvidenceMutationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inputs = V.source_inputs(ROOT)
        cls.temp = tempfile.TemporaryDirectory(prefix='r63-evidence-mutations-')
        cls.publication = Path(cls.temp.name) / 'build'
        shutil.copytree(ROOT / V.PUBLICATION, cls.publication)
        cls.receipt = (cls.publication / 'receipt.json').read_bytes()

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def mutation(self, name, change, pattern, repin=True):
        path = self.publication / name
        before = path.read_bytes() if path.exists() else None
        try:
            change(path)
            if repin:
                receipt = V.read_json(self.publication, 'receipt.json')
                tree = V.regular_tree(self.publication); tree.pop('receipt.json')
                receipt['evidenceSha256ByPath'] = tree
                (self.publication / 'receipt.json').write_bytes(V.json_bytes(receipt))
            with self.assertRaisesRegex(SystemExit, pattern):
                V.verify_publication(ROOT, self.publication, self.inputs)
        finally:
            if before is None: path.unlink(missing_ok=True)
            else: path.write_bytes(before)
            (self.publication / 'receipt.json').write_bytes(self.receipt)

    def edit(self, path, change):
        value = V.read_json(path.parent, path.name)
        change(value)
        path.write_bytes(V.json_bytes(value))

    def test_real_evidence_passes(self):
        V.verify_publication(ROOT, self.publication, self.inputs)

    def test_extra_evidence_rejected_even_if_receipt_repinned(self):
        self.mutation('forged.log', lambda p: p.write_text('fake'), 'unexpected or missing')

    def test_compiler_command_and_environment_cannot_be_repinned(self):
        for change in (lambda d: d.update(returnCode=1), lambda d: d['argv'].remove('-proc:none'),
                       lambda d: d['environment'].update(JAVA_TOOL_OPTIONS='-javaagent:fake.jar'),
                       lambda d: d.update(freshDirectoryCreated=False)):
            with self.subTest(change=change):
                self.mutation('primary/command.json', lambda p: self.edit(p, change), 'compiler command/environment')

    def test_compiler_class_mutation_cannot_be_repinned(self):
        name = V.review(ROOT)['newClassPaths'][0]
        self.mutation('independent/compiled/' + name, lambda p: p.write_bytes(p.read_bytes() + b'x'),
                      'AAR class differs')

    def test_false_claims_rejected(self):
        for name, value in (('releaseCompatible', True), ('deviceValidated', True),
                            ('nativeBuildPerformed', True), ('executionEvidence', {})):
            with self.subTest(name=name):
                self.mutation('receipt.json', lambda p: self.edit(p, lambda d: d.update({name: value})),
                              'strictly private build-only', repin=False)

    def test_source_input_and_baseline_receipt_repin_rejected(self):
        for change in (lambda d: d['sourceInputSha256ByPath'].clear(),
                       lambda d: d.update(baselineReceiptSha256='0' * 64)):
            self.mutation('receipt.json', lambda p: self.edit(p, change),
                          'inputs changed|baseline receipt binding', repin=False)

    def test_input_after_race_rejected(self):
        self.mutation('independent/inputs-after.json',
                      lambda p: self.edit(p, lambda d: d['copiedInputs'].clear()), 'inputs changed before/after')

    def test_stale_independent_aar_rejected(self):
        self.mutation('independent/' + V.AAR_NAME,
                      lambda p: p.write_bytes(V.checked_file(ROOT, V.R46.AAR)), 'independent AAR outputs')


if __name__ == '__main__':
    unittest.main()
