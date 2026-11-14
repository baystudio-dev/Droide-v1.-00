#!/usr/bin/env python3
"""Finite R46 mutations using the actual freshly generated AAR/class artifacts.

No synthetic success receipts, production source compilation, APK or native runs.
The integration/PTY behavior harness is separate and must run against each actual
AAR's extracted classes.jar with its CodeSource and loaded class hash recorded.
"""
from __future__ import annotations

import importlib.util
import io
import json
from pathlib import Path
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import verify_termux_pty_derivative as V
import build_termux_pty_derivative as B


def jar_mutation(aar: bytes, change):
    outer = V.archive_entries(aar)
    classes = V.archive_entries(outer['classes.jar'])
    change(classes)
    outer['classes.jar'] = V.deterministic_zip(classes)
    return V.deterministic_zip(outer)


def member_offsets(data: bytes):
    """Locate genuine classfile field/method flags and Code attributes for mutations."""
    model = V.parse_class(data)
    p = 10 + len(model['constantPool'])
    access = p
    p += 6
    interfaces = int.from_bytes(data[p:p + 2], 'big'); p += 2 + 2 * interfaces
    groups = []
    for kind in ('fields', 'methods'):
        count = int.from_bytes(data[p:p + 2], 'big'); p += 2
        offsets = []
        for index in range(count):
            flags = p; p += 6
            attr_count = int.from_bytes(data[p:p + 2], 'big'); p += 2
            attrs = {}
            for name in model[kind][index]['attributes']:
                size = int.from_bytes(data[p + 2:p + 6], 'big')
                attrs[name] = p + 6; p += 6 + size
            if len(attrs) != attr_count: raise AssertionError('fixture class parser disagrees')
            offsets.append((flags, attrs))
        groups.append(offsets)
    return access, groups[0], groups[1]


def flip_byte(data: bytes, offset: int):
    out = bytearray(data); out[offset] ^= 1
    return bytes(out)


class ActualAarClassMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (ROOT / V.AAR).is_file():
            raise AssertionError('Generate the real fixed R46 two-build publication before running this suite')
        cls.aar = V.checked_file(ROOT, V.AAR)
        cls.classes = V.archive_entries(V.archive_entries(cls.aar)['classes.jar'])
        cls.pty = cls.classes[V.PTY_CLASS]
        V.verify_aar_bytes(ROOT, cls.aar)

    def rejects(self, data, pattern):
        with self.assertRaisesRegex(SystemExit, pattern): V.verify_aar_bytes(ROOT, data)

    def test_real_aar_and_all_extracted_class_bytes_match_both_actual_compilers(self):
        result = V.verify_aar_bytes(ROOT, self.aar)
        self.assertEqual(V.PTY_CLASS_SHA256, result['ptyClassSha256'])
        for stage in ('primary', 'independent'):
            stage_root = ROOT / V.PUBLICATION / stage
            self.assertEqual(self.aar, (stage_root / 'terminal-emulator-r46-private.aar').read_bytes())
            for name in V.regular_tree(stage_root / 'compiled'):
                self.assertEqual(self.classes[name], (stage_root / 'compiled' / name).read_bytes())

    def test_stale_historical_aar_rejected(self):
        self.rejects(V.checked_file(ROOT, V.HISTORICAL_AAR), 'exactly TerminalPtyIo.class')

    def test_unrelated_actual_nested_class_rejected(self):
        name = 'com/termux/terminal/TerminalPtyIo$ReadReceipt.class'
        self.rejects(jar_mutation(self.aar, lambda d: d.update({name: d[name] + b'changed'})), 'exactly TerminalPtyIo.class')

    def test_actual_jni_class_change_rejected(self):
        self.rejects(jar_mutation(self.aar, lambda d: d.update({V.JNI_CLASS: d[V.JNI_CLASS] + b'x'})), 'exactly TerminalPtyIo.class')

    def test_class_modifier_drift_rejected(self):
        access, _, _ = member_offsets(self.pty)
        data = flip_byte(self.pty, access + 1)
        self.rejects(jar_mutation(self.aar, lambda d: d.update({V.PTY_CLASS: data})), 'modifier/constant drift')

    def test_field_modifier_drift_rejected(self):
        _, fields, _ = member_offsets(self.pty)
        data = flip_byte(self.pty, fields[0][0] + 1)
        self.rejects(jar_mutation(self.aar, lambda d: d.update({V.PTY_CLASS: data})), 'modifier/constant drift')

    def test_method_modifier_drift_rejected(self):
        _, _, methods = member_offsets(self.pty)
        data = flip_byte(self.pty, methods[1][0] + 1)
        self.rejects(jar_mutation(self.aar, lambda d: d.update({V.PTY_CLASS: data})), 'method interface/modifier drift')

    def test_actual_constant_pool_value_change_rejected(self):
        # 4096 appears as CONSTANT_Integer in this real compiled class.
        needle = b'\x03\x00\x00\x10\x00'
        position = self.pty.find(needle)
        self.assertGreater(position, 0)
        data = flip_byte(self.pty, position + 4)
        self.rejects(jar_mutation(self.aar, lambda d: d.update({V.PTY_CLASS: data})), 'constant drift')

    def test_read_implementation_byte_tamper_cannot_be_repinned(self):
        _, _, methods = member_offsets(self.pty)
        # Change an instruction within the real Code attribute, without touching its interface.
        data = flip_byte(self.pty, methods[1][1]['Code'] + 8)
        self.rejects(jar_mutation(self.aar, lambda d: d.update({V.PTY_CLASS: data})), 'reviewed actual compiler output')

    def test_writer_implementation_change_rejected(self):
        _, _, methods = member_offsets(self.pty)
        data = flip_byte(self.pty, methods[2][1]['Code'] + 8)
        self.rejects(jar_mutation(self.aar, lambda d: d.update({V.PTY_CLASS: data})), 'only readUntilClosed')

    def test_new_class_and_native_payload_rejected(self):
        self.rejects(jar_mutation(self.aar, lambda d: d.update({'com/termux/terminal/Extra.class': self.pty})), 'entry set changed')
        outer = V.archive_entries(self.aar); outer['jni/arm64-v8a/libbad.so'] = b'\x7fELF'
        self.rejects(V.deterministic_zip(outer), 'entry set changed')

    def test_unrelated_aar_resource_change_rejected(self):
        outer = V.archive_entries(self.aar)
        name = next(n for n in outer if n != 'classes.jar' and not n.endswith('/'))
        outer[name] += b'changed'
        self.rejects(V.deterministic_zip(outer), 'unrelated AAR entry')

    def test_duplicate_actual_aar_or_jar_entry_rejected(self):
        for level in ('aar', 'jar'):
            with self.subTest(level=level):
                outer = V.archive_entries(self.aar)
                entries = outer if level == 'aar' else V.archive_entries(outer['classes.jar'])
                out = io.BytesIO()
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore')
                    with zipfile.ZipFile(out, 'w') as z:
                        for name, raw in entries.items(): z.writestr(name, raw)
                        name = next(iter(entries)); z.writestr(name, entries[name])
                data = out.getvalue()
                if level == 'jar':
                    outer['classes.jar'] = data; data = V.deterministic_zip(outer)
                self.rejects(data, 'duplicate archive entry')

    def test_traversal_and_zip_symlink_rejected(self):
        for name in ('../escape', '/absolute', 'a//b', 'a\\b'):
            with self.subTest(name=name):
                outer = V.archive_entries(self.aar); outer[name] = b'x'
                self.rejects(V.deterministic_zip(outer), 'unsafe archive entry')
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w') as z:
            entry = zipfile.ZipInfo('classes.jar'); entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            z.writestr(entry, b'target')
        self.rejects(out.getvalue(), 'nonregular/encrypted')

    def test_class_parser_rejects_truncation_and_trailing_bytes(self):
        for data in (self.pty[:20], self.pty + b'junk'):
            with self.assertRaises(SystemExit): V.parse_class(data)


class DerivativeEvidenceMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inputs = V.source_inputs(ROOT)
        cls.real_receipt = V.verify_derivative_inputs(ROOT)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='r46-input-mutation-')
        self.root = Path(self.temporary.name)
        for relative in self.inputs:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
        shutil.copytree(ROOT / V.PUBLICATION, self.root / V.PUBLICATION)

    def tearDown(self): self.temporary.cleanup()

    def receipt(self): return self.root / V.RECEIPT

    def edit_json(self, path, change):
        value = json.loads(path.read_bytes()); change(value); path.write_bytes(V.json_bytes(value))

    def repin_evidence(self):
        actual = V.regular_tree(self.root / V.PUBLICATION); actual.pop('receipt.json')
        self.edit_json(self.receipt(), lambda r: r.update(evidenceSha256ByPath=actual))

    def rejects(self, pattern):
        with self.assertRaisesRegex(SystemExit, pattern): V.verify_derivative_inputs(self.root)

    def test_real_publication_passes_in_relocated_repository(self):
        result = V.verify_derivative_inputs(self.root)
        self.assertEqual(V.AAR, result['output']['path'])
        self.assertFalse(result['releaseCompatible']); self.assertFalse(result['deviceValidated'])
        self.assertEqual(8, len(result['nativeSha256ByBuild']['primary']))
        self.assertEqual(4, len(result['existingRuntimeSha256ByPath']))

    def test_candidate_source_and_patch_mutations_rejected(self):
        for name in (V.SOURCE, V.PATCH):
            with self.subTest(name=name):
                path = self.root / name; before = path.read_bytes(); path.write_bytes(before + b'\n')
                self.rejects('SHA-256 mismatch'); path.write_bytes(before)

    def test_historical_source_receipt_and_gate_mutations_rejected(self):
        for name in (V.BASELINE_SOURCE, V.PRIVATE_RECEIPT, V.CONTRACT,
                     'third_party/termux/native/termux.c', 'tools/verify_termux_provenance.py',
                     'tools/verify_private_debug_native.py'):
            with self.subTest(name=name):
                path = self.root / name; before = path.read_bytes(); path.write_bytes(before + b'\n')
                self.rejects('SHA-256 mismatch'); path.write_bytes(before)

    def test_all_sixteen_real_native_artifacts_hashed(self):
        private = self.real_receipt['baselineReceipt']
        for stage, native in private['nativeSha256ByBuild'].items():
            for name in native:
                with self.subTest(stage=stage, name=name):
                    path = self.root / 'third_party/termux/native/private-debug' / stage / name
                    before = path.read_bytes(); path.write_bytes(before + b'bad')
                    self.rejects('SHA-256 mismatch'); path.write_bytes(before)

    def test_runtime_executable_mutation_rejected(self):
        for name in self.real_receipt['existingRuntimeSha256ByPath']:
            with self.subTest(name=name):
                path = self.root / name; before = path.read_bytes(); path.write_bytes(before + b'bad')
                self.rejects('SHA-256 mismatch'); path.write_bytes(before)

    def test_extra_native_abi_rejected(self):
        path = self.root / 'third_party/termux/native/private-debug/primary/riscv64/libtermux.so'
        path.parent.mkdir(); path.write_bytes(b'\x7fELF')
        self.rejects('historical protected input file set')

    def test_missing_native_artifact_rejected(self):
        (self.root / 'third_party/termux/native/private-debug/independent/x86/libtermux.so').unlink()
        self.rejects('missing or nonregular')

    def test_false_runtime_release_and_native_build_claims_rejected(self):
        original = self.receipt().read_bytes()
        for key, value in (('releaseCompatible', True), ('deviceValidated', True),
                           ('executionEvidence', {}), ('nativeBuildPerformed', True), ('status', 'RELEASE')):
            with self.subTest(key=key):
                self.edit_json(self.receipt(), lambda r: r.update({key: value}))
                self.rejects('strictly private build-only'); self.receipt().write_bytes(original)

    def test_caller_output_selection_rejected(self):
        self.edit_json(self.receipt(), lambda r: r['output'].update(path=V.HISTORICAL_AAR))
        self.rejects('fixed derivative AAR')

    def test_changed_verifier_or_builder_invalidates_fresh_receipt(self):
        for name in (V.VERIFIER, V.BUILDER):
            with self.subTest(name=name):
                path = self.root / name; before = path.read_bytes(); path.write_bytes(before + b'\n')
                self.rejects('inputs changed|SHA-256 mismatch'); path.write_bytes(before)

    def test_missing_or_extra_raw_evidence_rejected_even_after_repin(self):
        path = self.root / V.PUBLICATION / 'extra.log'; path.write_text('fake')
        self.repin_evidence(); self.rejects('unexpected or missing derivative evidence')
        path.unlink(); self.repin_evidence()
        (self.root / V.PUBLICATION / 'primary/javac.stdout.log').unlink()
        self.repin_evidence(); self.rejects('unexpected or missing derivative evidence')

    def test_failed_command_and_relaxed_compiler_flags_rejected_after_repin(self):
        path = self.root / V.PUBLICATION / 'primary/command.json'; original = path.read_bytes()
        for change in (lambda d: d.update(returnCode=1),
                       lambda d: d['argv'].remove('-proc:none'),
                       lambda d: d['argv'].remove('-implicit:none'),
                       lambda d: d['environment'].update(JAVA_TOOL_OPTIONS='-javaagent:bad.jar'),
                       lambda d: d.update(freshDirectoryCreated=False)):
            with self.subTest(change=change):
                self.edit_json(path, change); self.repin_evidence()
                self.rejects('actual compiler command/environment'); path.write_bytes(original)
        self.repin_evidence()

    def test_inputs_before_after_race_rejected_after_repin(self):
        path = self.root / V.PUBLICATION / 'independent/inputs-after.json'
        self.edit_json(path, lambda d: d['copiedInputs'].update({'inputs/android.jar': '0' * 64}))
        self.repin_evidence(); self.rejects('inputs changed before/after')

    def test_stale_final_aar_rejected_after_repin(self):
        (self.root / V.AAR).write_bytes((self.root / V.HISTORICAL_AAR).read_bytes())
        self.repin_evidence(); self.rejects('exactly TerminalPtyIo.class')

    def test_stale_independent_aar_rejected_after_repin(self):
        path = self.root / V.PUBLICATION / 'independent/terminal-emulator-r46-private.aar'
        path.write_bytes((self.root / V.HISTORICAL_AAR).read_bytes())
        self.repin_evidence(); self.rejects('fresh independent AAR outputs')

    def test_compiler_output_mutation_rejected_after_repin(self):
        path = self.root / V.PUBLICATION / 'primary/compiled' / V.PTY_CLASS
        path.write_bytes(path.read_bytes() + b'x'); self.repin_evidence()
        self.rejects('AAR class differs from actual compiler output')

    def test_added_compiler_output_rejected_after_repin(self):
        path = self.root / V.PUBLICATION / 'primary/compiled/extra.class'; path.write_bytes(b'fake')
        self.repin_evidence(); self.rejects('actual compiled overlay class set')

    def test_toolchain_manifest_repin_and_fake_jdk_rejected(self):
        path = self.root / V.BASE / 'JDK17.json'; path.write_bytes(path.read_bytes() + b'\n')
        self.rejects('SHA-256 mismatch')
        path.write_bytes((ROOT / V.BASE / 'JDK17.json').read_bytes())
        fake = self.root / 'fake-jdk'; fake.mkdir()
        with self.assertRaisesRegex(SystemExit, 'pinned JDK complete file set'):
            V.verify_jdk(fake, self.root)

    def test_baseline_manifest_repin_rejected(self):
        path = self.root / V.BASE / 'BASELINE.json'
        self.edit_json(path, lambda d: d['sha256ByPath'].clear())
        self.rejects('SHA-256 mismatch')

    def test_symlinked_actual_input_rejected(self):
        path = self.root / V.SOURCE; real = path.with_suffix('.saved')
        path.rename(real); path.symlink_to(real.name)
        self.rejects('symlinked input/output')

    def test_build_refuses_existing_publication_without_invoking_compiler(self):
        with self.assertRaisesRegex(SystemExit, 'publication already exists'):
            B.build(self.root, self.root / 'irrelevant-jdk', self.root / 'irrelevant-android.jar')

    def test_old_private_gate_rejects_derivative_substitution_and_default_release_stays_closed(self):
        script = str(self.root / 'tools/verify_termux_provenance.py')
        release = subprocess.run([sys.executable, '-B', script], capture_output=True, timeout=30)
        self.assertNotEqual(0, release.returncode)
        self.assertIn(b'native rebuild pending', release.stderr)
        (self.root / V.HISTORICAL_AAR).write_bytes((self.root / V.AAR).read_bytes())
        old = subprocess.run([sys.executable, '-B', script, '--private-debug-device-unvalidated'],
                             capture_output=True, timeout=30)
        self.assertNotEqual(0, old.returncode)
        self.assertIn(b'SHA-256 mismatch', old.stderr)


if __name__ == '__main__':
    unittest.main()
