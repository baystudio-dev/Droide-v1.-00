"""Pure composite guards; synthetic provider bytes are not native build evidence."""
import ast
import copy
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import current_composite_native_v1 as C

METHODS = [{'name': 'ownedProcessApiVersion', 'descriptor': '()I'}]


def native_children():
    filesystem = {'artifacts': {}}
    termux = {'artifacts': {}}
    for stage in ('primary', 'independent'):
        for abi in C.ABIS:
            key = stage + '/' + abi
            filesystem['artifacts'][key] = {'sha256': 'a' * 64}
            termux['artifacts'][key] = {'sha256': 'b' * 64, 'staticInspection': {
                'registrationTable': copy.deepcopy(METHODS), 'registrationPathVerified': False}}
    return filesystem, termux


class CompositeGuards(unittest.TestCase):
    def test_exact_native_stage_abi_and_descriptor_agreement(self):
        fs, termux = native_children()
        C.native_rows(fs, termux, METHODS)

    def test_missing_extra_and_mixed_native_provider_rejected(self):
        for mutation in ('missing', 'extra', 'mixed', 'descriptor', 'promoted'):
            fs, termux = native_children()
            if mutation == 'missing': del fs['artifacts']['primary/x86']
            if mutation == 'extra': fs['artifacts']['primary/riscv64'] = {'sha256': 'a' * 64}
            if mutation == 'mixed': fs['artifacts']['independent/x86']['sha256'] = 'c' * 64
            if mutation == 'descriptor': termux['artifacts']['primary/x86']['staticInspection']['registrationTable'][0]['descriptor'] = '()J'
            if mutation == 'promoted': termux['artifacts']['primary/x86']['staticInspection']['registrationPathVerified'] = True
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                C.native_rows(fs, termux, METHODS)

    def test_child_scope_must_remain_unadmitted(self):
        valid = dict(status=C.STATUS, deviceValidated=False, apkAdmitted=False, releaseCompatible=False)
        C.scope_flags(valid, C.STATUS)
        for key in ('deviceValidated', 'apkAdmitted', 'releaseCompatible', 'status'):
            bad = dict(valid); bad[key] = True
            with self.subTest(key=key), self.assertRaises(ValueError): C.scope_flags(bad, C.STATUS)

    def test_input_names_are_exact(self):
        with self.assertRaises(ValueError): C.paths({'unexpected': '/tmp/a'})
        with self.assertRaises(ValueError): C.paths({})

    def test_fixed_verification_commands_do_not_execute_receipt_commands(self):
        values = {key: Path('/inputs') / key for key in C.INPUT_NAMES}
        result = C.commands(Path('/source'), values)
        self.assertEqual({'filesystem', 'termux', 'java', 'host', 'auxiliary-cli'}, set(result))
        for argv in result.values():
            self.assertEqual(['-B', '-E', '-s'], argv[1:4])
        self.assertIn('/source/tools/run_current_engine_host_v1.py', result['host'])
        self.assertIn('--engine-output', result['host'])
        self.assertNotIn('build', result['filesystem'])

    def test_fresh_host_lineage_rejects_old_run_even_identical_engine_bytes(self):
        engine = Path('/fresh/engine')
        pre = {'javaBuild': {'root': str(engine), 'receiptSha256': 'receipt'},
               'engine': {'aarPath': str(engine/'terminal-engine.aar'), 'aarSha256': 'aar'}}
        owned = {'status': 'PASS', 'childCleanupStatus': 'OWNED_TEST_CHILDREN_REAPED',
                 'sourceBefore': {'source': 'c', 'engine': 'aar'},
                 'sourceAfter': {'source': 'c', 'engine': 'aar'},
                 'engineJarSha256': 'jar', 'jniClassSha256': 'jni'}
        C.validate_host_lineage(pre, owned, engine, 'receipt', 'aar', 'jar', 'jni', 'c')
        for mutation in ('old-root', 'old-receipt', 'changed-c', 'changed-engine', 'cleanup', 'failed'):
            wrong_pre, wrong_owned = copy.deepcopy(pre), copy.deepcopy(owned)
            if mutation == 'old-root': wrong_pre['javaBuild']['root'] = '/historical/engine'
            if mutation == 'old-receipt': wrong_pre['javaBuild']['receiptSha256'] = 'historical'
            if mutation == 'changed-c': wrong_owned['sourceAfter']['source'] = 'changed'
            if mutation == 'changed-engine': wrong_owned['engineJarSha256'] = 'stale'
            if mutation == 'cleanup': wrong_owned['childCleanupStatus'] = 'UNCONFIRMED'
            if mutation == 'failed': wrong_owned['status'] = 'FAIL'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                C.validate_host_lineage(wrong_pre, wrong_owned, engine, 'receipt', 'aar', 'jar', 'jni', 'c')

    def test_actual_provider_bytes_and_mixed_architecture_are_bound(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            locations = {'filesystem_output': root/'fs', 'termux_output': root/'termux'}
            children = {'filesystem': {'artifacts': {}}, 'termux': {'artifacts': {}}}
            blob = bytearray(64); blob[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<H', blob, 18, 183)
            for component, filename, location in (
                ('filesystem', 'libdroide_files.so', 'filesystem_output'),
                ('termux', 'libtermux.so', 'termux_output')):
                for abi in C.ABIS:
                    target = locations[location]/'primary'/abi/filename
                    target.parent.mkdir(parents=True); target.write_bytes(blob)
                    children[component]['artifacts']['primary/'+abi] = {'sha256': C.digest(blob)}
            auxiliary = {}
            for index in range(4):
                key = 'arm64-v8a/aux'+str(index)+'.so'
                target = root/'app/src/main/jniLibs'/key
                target.parent.mkdir(parents=True, exist_ok=True)
                aux = bytearray(blob)
                if index == 3:
                    aux[4] = 1; struct.pack_into('<H', aux, 18, 40)
                target.write_bytes(aux); auxiliary[key] = C.digest(aux)
            contract = {'auxiliarySha256': auxiliary}
            providers = C.selected_providers(root, locations, contract, children)
            self.assertEqual(12, len(providers))
            self.assertEqual(8, sum(r['origin'] == 'CURRENT_REPRODUCED_COMPONENT' for r in providers.values()))
            self.assertEqual(40, providers['arm64-v8a/aux3.so']['elfMachine'])
            self.assertEqual('arm64-v8a', providers['arm64-v8a/aux3.so']['abiLocation'])
            target.write_bytes(b'drift')
            with self.assertRaisesRegex(ValueError, 'auxiliary drift'):
                C.selected_providers(root, locations, contract, children)
            target.write_bytes(aux)
            native = locations['termux_output']/'primary/x86/libtermux.so'
            native.write_bytes(b'drift')
            with self.assertRaisesRegex(ValueError, 'native provider changed'):
                C.selected_providers(root, locations, contract, children)

    def test_fixed_recipe_matches_current_files_and_refuses_clone_drift(self):
        value = C.recipe(TOOLS.parent)
        self.assertEqual(4, len(value['auxiliarySha256']))
        with mock.patch.object(C, 'file_hash', return_value='changed'):
            with self.assertRaisesRegex(ValueError, 'recipe changed'):
                C.recipe(TOOLS.parent)

    def test_output_whitelist_rejects_extra_and_missing_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            providers = {'arm64-v8a/libexample.so': {}}
            files = ['engine/terminal-engine.aar', 'native/arm64-v8a/libexample.so']
            for name in ('filesystem', 'termux', 'java', 'host', 'auxiliary-cli'):
                files.extend('verification/'+name+suffix for suffix in ('.stdout.log', '.stderr.log', '.command.json'))
            for name in files:
                path = output/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b'fixture')
            self.assertEqual(set(files), set(C.output_evidence(output, providers)))
            (output/'extra').write_text('unrelated')
            with self.assertRaisesRegex(ValueError, 'evidence files'): C.output_evidence(output, providers)
            (output/'extra').unlink(); (output/files[0]).unlink()
            with self.assertRaisesRegex(ValueError, 'evidence files'): C.output_evidence(output, providers)

    def test_timeout_retains_diagnostics_without_success_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary); (output/'verification').mkdir()
            locations = {key: output/key for key in C.INPUT_NAMES}
            failure = subprocess.TimeoutExpired(['fixture'], 1800, output=b'partial out', stderr=b'partial err')
            with mock.patch.object(C.subprocess, 'run', side_effect=failure):
                with self.assertRaises(subprocess.TimeoutExpired):
                    C.verify_children(output, locations, output, record=True)
            self.assertEqual(b'partial out', (output/'verification/filesystem.stdout.log').read_bytes())
            self.assertEqual(b'partial err', (output/'verification/filesystem.stderr.log').read_bytes())
            self.assertTrue(C.load(output/'verification/filesystem.command.json')['timedOut'])
            self.assertFalse((output/'receipt.json').exists())

    def test_relocated_source_change_rejected_before_child_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary); origin = base/'origin'; output = base/'bundle'; output.mkdir()
            C.write_json(output/'receipt.json', {'sourceRoot': str(origin)})
            with mock.patch.object(C, 'recipe', side_effect=[{'pins': 'requested'}, {'pins': 'changed-original'}]), \
                 mock.patch.object(C, 'verify_children') as run:
                with self.assertRaisesRegex(ValueError, 'consumer source/tool identity'):
                    C.verify(base/'copy', output)
                run.assert_not_called()

    def test_contract_covers_all_child_declared_inputs(self):
        pins = C.load(TOOLS/'current-composite-native-v1.json')['sourceAndToolSha256']
        required = set()
        for filename in ('filesystem-native-v1.json', 'termux-native-v1.json', 'terminal-engine-v2.json'):
            child = C.load(TOOLS/filename)
            required.add('tools/'+filename)
            required.update(child.get('sourceSha256', {}))
            required.update(child.get('helperSha256', {}))
        for filename, name in (('termux_native_v1.py', 'TOOL_FILES'), ('run_current_engine_host_v1.py', 'PINNED')):
            module = ast.parse((TOOLS/filename).read_text())
            declarations = [ast.literal_eval(node.value) for node in module.body if isinstance(node, ast.Assign)
                            and any(isinstance(target, ast.Name) and target.id == name for target in node.targets)]
            self.assertEqual(1, len(declarations)); required.update(declarations[0])
        required.update('tools/'+name for name in ('filesystem_native_v1.py', 'filesystem-native-v1.md',
                        'terminal_engine_v2.py', 'run_current_engine_host_v1.py'))
        self.assertFalse(required-set(pins), 'child manifest inputs omitted from composite contract')

    def test_existing_output_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); output = root / 'output'; output.mkdir()
            marker = output / 'owned'; marker.write_text('retain')
            values = {key: root / key for key in C.INPUT_NAMES}
            with self.assertRaisesRegex(ValueError, 'output exists'):
                C.assemble(root / 'source', values, output)
            self.assertEqual('retain', marker.read_text())

    def test_symlink_and_special_evidence_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'real').write_text('data')
            (root / 'alias').symlink_to(root / 'real')
            with self.assertRaisesRegex(ValueError, 'symlinked'): C.inventory(root)
            (root / 'alias').unlink(); os.mkfifo(root / 'fifo')
            with self.assertRaisesRegex(ValueError, 'special'): C.inventory(root)

    def test_duplicate_receipt_key_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'receipt.json'; path.write_text('{"status":1,"status":2}')
            with self.assertRaisesRegex(ValueError, 'duplicate'): C.load(path)

    def test_rel_root_consumer_does_not_retarget_origin(self):
        # Flow guard: requested copy validates its own pins, then original pinned
        # scripts remain the execution target. No expensive child run is made.
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary); requested = base / 'copy'; origin = base / 'origin'; output = base / 'bundle'
            requested.mkdir(); origin.mkdir(); output.mkdir()
            locations = {key: str(base / key) for key in C.INPUT_NAMES}
            saved = {'sourceRoot': str(origin), 'inputs': locations}
            C.write_json(output / 'receipt.json', saved)
            with mock.patch.object(C, 'recipe', return_value={}), mock.patch.object(C, 'file_hash', return_value='same'), \
                 mock.patch.object(C, 'verify_children', side_effect=RuntimeError('stop before child execution')) as run:
                with self.assertRaisesRegex(RuntimeError, 'stop'):
                    C.verify(requested, output)
                self.assertEqual(origin, run.call_args.args[0])


if __name__ == '__main__':
    unittest.main()
