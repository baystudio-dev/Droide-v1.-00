#!/usr/bin/env python3
"""Bounded real-evidence mutations and synthetic ZIP/DEX payload tests; no Android execution."""
from __future__ import annotations
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import unittest
import warnings
import zipfile

import test_termux_input_provenance as original_tests
from test_termux_input_provenance import zip_bytes, zip_entries

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('private_native', ROOT / 'tools/verify_private_debug_native.py')
N = importlib.util.module_from_spec(spec)
spec.loader.exec_module(N)


def synthetic_dex(methods):
    """Minimal declaration-only DEX test fixture; never a device-valid APK claim."""
    def leb(value):
        output = bytearray()
        while value > 127:
            output.append((value & 127) | 128); value >>= 7
        output.append(value); return output
    class_name = 'Lcom/termux/terminal/JNI;'
    parsed = []
    types = [class_name]
    for method in methods:
        args, returned = method['descriptor'][1:].split(')')
        args = re.findall(r'\[*(?:L[^;]+;|[ZBCSIJFD])', args)
        parsed.append((method['name'], args, returned))
        for value in [*args, returned]:
            if value not in types: types.append(value)
    strings = list(dict.fromkeys(types + [m['name'] for m in methods]))
    data = bytearray(112)
    def reserve(size):
        offset = len(data); data.extend(b'\0' * size); return offset
    def word(offset, value): struct.pack_into('<I', data, offset, value)
    sid = reserve(len(strings) * 4); tid = reserve(len(types) * 4)
    proto = reserve(len(methods) * 12); mid = reserve(len(methods) * 8); cls = reserve(32)
    for i, value in enumerate(strings):
        word(sid + i * 4, len(data)); data.extend(leb(len(value)) + value.encode() + b'\0')
    for i, value in enumerate(types): word(tid + i * 4, strings.index(value))
    for i, (name, args, returned) in enumerate(parsed):
        word(proto + i * 12 + 4, types.index(returned))
        if args:
            word(proto + i * 12 + 8, len(data)); data.extend(struct.pack('<I', len(args)))
            for arg in args: data.extend(struct.pack('<H', types.index(arg)))
        struct.pack_into('<HHI', data, mid + i * 8, 0, i, strings.index(name))
    word(cls, 0); word(cls + 24, len(data))
    data.extend(leb(0) + leb(0) + leb(len(methods)) + leb(0))
    for i in range(len(methods)): data.extend(leb(0 if i == 0 else 1) + leb(0x109) + leb(0))
    data[:8] = b'dex\n035\0'; word(32, len(data)); word(36,112); word(40,0x12345678)
    for size_at, count, offset in ((56,len(strings),sid),(64,len(types),tid),(72,len(methods),proto),(88,len(methods),mid),(96,1,cls)):
        word(size_at,count);word(size_at+4,offset)
    return bytes(data)


class PrivateDebugAdmissionTest(unittest.TestCase):
    def setUp(self):
        original_tests.TermuxInputProvenanceTest.setUp(self)
        for path in ('tools/verify_private_debug_native.py', 'app/src/main/cpp/droide_files.c'):
            target = self.root / path; target.parent.mkdir(parents=True,exist_ok=True); shutil.copyfile(ROOT/path,target)
        shutil.copytree(ROOT/N.BASE, self.root/N.BASE)
        shutil.copytree(ROOT/'app/src/main/jniLibs', self.root/'app/src/main/jniLibs')

    def tearDown(self): self.temporary.cleanup()
    def receipt(self): return self.root/N.BASE/'receipt.json'
    def edit_json(self, path, function):
        value=json.loads(path.read_text());function(value);path.write_text(json.dumps(value))
    def verify(self, private=True):
        return subprocess.run(['python3','-B',str(self.root/'tools/verify_termux_provenance.py')] +
                              (['--private-debug-device-unvalidated'] if private else []),capture_output=True,text=True,timeout=30)
    def rejected(self, contains):
        result=self.verify();self.assertNotEqual(0,result.returncode);self.assertIn(contains,result.stdout+result.stderr)
    def mutate(self, relative):
        path=self.root/relative;path.write_bytes(path.read_bytes()+b'changed')
    def replace_aar_and_pin(self, entries): original_tests.TermuxInputProvenanceTest.replace_aar_and_pin(self,entries)

    def test_real_rebuilt_private_inputs_pass_without_promoting_release(self):
        result=self.verify();self.assertEqual(0,result.returncode,result.stderr)
        result=self.verify(False);self.assertNotEqual(0,result.returncode);self.assertIn('native rebuild pending',result.stderr)
        self.assertEqual('UNBUILT',json.loads((self.root/N.CONTRACT).read_text())['status'])

    def test_each_primary_and_independent_binary_is_actually_hashed(self):
        for stage in ('primary','independent'):
            for path in sorted(N.EXPECTED_LIBS):
                with self.subTest(stage=stage,path=path):
                    file=self.root/N.BASE/stage/path;original=file.read_bytes();file.write_bytes(original+b'bad')
                    self.rejected('SHA-256 mismatch');file.write_bytes(original)

    def test_missing_native_abi_is_rejected(self):
        (self.root/N.BASE/'independent/x86/libtermux.so').unlink();self.rejected('exactly eight')

    def test_extra_native_abi_is_rejected(self):
        path=self.root/N.BASE/'primary/riscv64/libtermux.so';path.parent.mkdir();path.write_bytes(b'ELF');self.rejected('exactly eight')

    def test_status_only_native_promotion_is_rejected(self):
        self.edit_json(self.root/N.CONTRACT,lambda d:d.update(status='BUILT_AND_INDEPENDENTLY_VERIFIED'))
        self.rejected('SHA-256 mismatch')

    def test_native_source_mutation_is_rejected(self):
        self.mutate('third_party/termux/native/termux.c');self.rejected('source contract hash mismatch')

    def test_file_publication_source_is_pinned_too(self):
        self.mutate('app/src/main/cpp/droide_files.c');self.rejected('SHA-256 mismatch')

    def test_jni_source_mutation_is_rejected(self):
        self.mutate('third_party/termux/patches/src/com/termux/terminal/JNI.java');self.rejected('source contract hash mismatch')

    def test_false_device_and_release_claims_are_required(self):
        path=self.receipt();original=path.read_bytes()
        for field,value in (('status','BUILT_AND_INDEPENDENTLY_VERIFIED'),('releaseCompatible',True),('deviceValidated',True),('executionEvidence',{'passed':True})):
            with self.subTest(field=field):
                path.write_bytes(original);self.edit_json(path,lambda d:d.update({field:value}));self.rejected('explicitly private')

    def test_source_hash_repin_cannot_change_measured_native_inputs(self):
        path='app/src/main/cpp/droide_files.c';self.mutate(path)
        self.edit_json(self.receipt(),lambda d:d['sourceSha256ByPath'].update({path:hashlib.sha256((self.root/path).read_bytes()).hexdigest()}))
        self.rejected('actually compiled native inputs')

    def test_real_recipe_tampering_is_rejected(self):
        self.mutate(N.BASE+'/evidence/independent/reproduce_native.py');self.rejected('SHA-256 mismatch')

    def test_real_reproduction_report_tampering_is_rejected(self):
        self.mutate(N.BASE+'/evidence/independent/reproduction-comparison.json');self.rejected('SHA-256 mismatch')

    def test_reproduction_status_claim_cannot_replace_artifacts(self):
        self.edit_json(self.receipt(),lambda d:d['nativeSha256ByBuild']['independent'].update({'arm64-v8a/libtermux.so':'0'*64}))
        self.rejected('SHA-256 mismatch')

    def test_native_source_symlink_is_rejected(self):
        path=self.root/N.BASE/'primary/arm64-v8a/libtermux.so';path.unlink();path.symlink_to(self.root/N.BASE/'independent/arm64-v8a/libtermux.so')
        self.rejected('symlinked provenance')

    def test_aar_tamper_is_rejected(self):
        self.mutate(N.AAR);self.rejected('local output SHA-256 mismatch')

    def test_unrelated_class_change_rejected_even_with_new_output_pin(self):
        entries=zip_entries((self.root/N.AAR).read_bytes());classes=zip_entries(entries['classes.jar'])
        classes['com/termux/terminal/TerminalOutput.class']+=b'change';entries['classes.jar']=zip_bytes(classes)
        self.replace_aar_and_pin(entries);self.rejected('changed an unrelated compiled entry')

    def test_added_class_rejected_even_with_new_output_pin(self):
        entries=zip_entries((self.root/N.AAR).read_bytes());classes=zip_entries(entries['classes.jar'])
        classes['com/termux/terminal/Unexpected.class']=b'change';entries['classes.jar']=zip_bytes(classes)
        self.replace_aar_and_pin(entries);self.rejected('introduced unexpected/missing classes')

    def test_aar_manifest_change_rejected_even_with_new_output_pin(self):
        entries=zip_entries((self.root/N.AAR).read_bytes());entries['AndroidManifest.xml']+=b'change'
        self.replace_aar_and_pin(entries);self.rejected('changed an unrelated entry')

    def test_exact_overlay_diff_still_required(self):
        metadata=self.root/N.JAVA_RECEIPT;data=json.loads(metadata.read_text());path=data['rowSource']['path']
        self.mutate(path);data['rowSource']['sha256']=hashlib.sha256((self.root/path).read_bytes()).hexdigest();metadata.write_text(json.dumps(data))
        self.rejected('diff does not exactly describe TerminalRow')

    def test_java_receipt_schema_still_required(self):
        self.edit_json(self.root/N.JAVA_RECEIPT,lambda d:d.update(schemaVersion=1));self.rejected('overlay rebuild pending')

    def test_java_checksum_manifest_still_required(self):
        path=self.root/'third_party/termux/SHA256SUMS';path.write_text('\n'.join(line for line in path.read_text().splitlines() if not line.endswith(N.AAR)))
        self.rejected('SHA256SUMS')

    def test_upstream_aar_tamper_is_rejected_in_private_mode(self):
        self.mutate('app/libs/terminal-emulator-v0.118.0.aar');self.rejected('terminal-emulator-v0.118.0.aar SHA-256 mismatch')

    def test_unknown_opt_in_argument_is_not_a_bypass(self):
        result=subprocess.run(['python3','-B',str(self.root/'tools/verify_termux_provenance.py'),'--skip-native'],capture_output=True,text=True)
        self.assertNotEqual(0,result.returncode);self.assertIn('unrecognized arguments',result.stderr)

    def apk_entries(self):
        receipt=json.loads(self.receipt().read_text());entries={}
        for path in N.EXPECTED_LIBS: entries['lib/'+path]=(self.root/N.BASE/'primary'/path).read_bytes()
        for path in receipt['existingRuntimeSha256ByPath']:entries['lib/'+path.removeprefix('app/src/main/jniLibs/')]=(self.root/path).read_bytes()
        for abi in N.ABIS:
            for name in ('libdatastore_shared_counter.so','libandroidx.graphics.path.so'):entries[f'lib/{abi}/{name}']=b'synthetic external dependency fixture'
        entries['classes.dex']=synthetic_dex(json.loads((self.root/N.CONTRACT).read_text())['registeredNativeMethods'])
        return entries
    def payload(self,entries):
        path=self.root/'synthetic-payload-only.apk';path.write_bytes(zip_bytes(entries))
        return N.verify_apk_payload(self.root,path,json.loads(self.receipt().read_text()))

    def test_synthetic_apk_payload_positive(self):
        result=self.payload(self.apk_entries());self.assertEqual(12,len(result['jniNativeMethods']));self.assertEqual(12,len(result['nativeSha256ByApkPath']))

    def test_final_apk_mutation_missing_extra_and_wrong_native_bytes(self):
        entries=self.apk_entries()
        mutations=[lambda e:e.pop('lib/x86/libtermux.so'),lambda e:e.update({'lib/x86/libtermux.so':b'bad'}),lambda e:e.update({'lib/riscv64/libtermux.so':b'bad'}),lambda e:e.update({'lib/x86/libunexpected.so':b'bad'}),lambda e:e.update({'lib/arm64-v8a/libdroide_cli.so':b'bad'})]
        for change in mutations:
            with self.subTest(change=change):
                current=entries.copy();change(current)
                with self.assertRaises(SystemExit):self.payload(current)

    def test_final_apk_duplicate_zip_entries_rejected(self):
        path=self.root/'duplicate.apk';entries=self.apk_entries();path.write_bytes(zip_bytes(entries))
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            with zipfile.ZipFile(path,'a') as archive:archive.writestr('lib/x86/libtermux.so',entries['lib/x86/libtermux.so'])
        with self.assertRaisesRegex(SystemExit,'duplicate APK ZIP entry'):N.verify_apk_payload(self.root,path,json.loads(self.receipt().read_text()))

    def test_final_dex_missing_or_stale_jni_declarations_rejected(self):
        entries=self.apk_entries();methods=json.loads((self.root/N.CONTRACT).read_text())['registeredNativeMethods']
        entries['classes.dex']=synthetic_dex(methods[:-1])
        with self.assertRaisesRegex(SystemExit,'twelve compiled native methods'):self.payload(entries)
        entries['classes2.dex']=synthetic_dex(methods)
        with self.assertRaisesRegex(SystemExit,'twelve compiled native methods'):self.payload(entries)

    def test_final_dex_suffix_ten_is_included_and_duplicates_rejected(self):
        entries=self.apk_entries();entries['classes10.dex']=entries.pop('classes.dex')
        self.assertEqual(12,len(self.payload(entries)['jniNativeMethods']))
        entries['classes.dex']=entries['classes10.dex']
        with self.assertRaisesRegex(SystemExit,'twelve compiled native methods'):self.payload(entries)

    def test_final_dex_is_parsed_without_execution(self):
        with self.assertRaisesRegex(SystemExit,'invalid packaged DEX'):N.dex_jni_methods(b'not dex')
        methods=json.loads((self.root/N.CONTRACT).read_text())['registeredNativeMethods']
        self.assertEqual(methods,N.dex_jni_methods(synthetic_dex(methods)))


if __name__=='__main__':unittest.main()
