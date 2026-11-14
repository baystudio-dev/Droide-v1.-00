#!/usr/bin/env python3
"""Additive current-source consumer; exact original child roots remain immutable."""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import zipfile
import current_composite_native_v1 as common

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = 'tools/current_composite_native_v2.py'
CONTRACT = 'tools/current-composite-native-v2.json'
BASELINE = 'tools/current-composite-v2-baseline.json'
CONTRACT_SHA = 'd0068666f91f6c2064fac88877a7f6ab6c77131136a765609c47225fff06760c'
KIND = 'CURRENT_COMPOSITE_NATIVE_V2'
ADDITIONS = {SCRIPT, CONTRACT, BASELINE, 'tools/tests/test_current_composite_native_v2.py', 'tools/current-composite-native-v2.md'}
require = common.require
checked = common.checked
raw = common.raw
file_hash = common.file_hash
load = common.load
EXTRA_INPUTS = ('child_source_root', 'application_main_jar', 'original_substrate')


def exact_projection(actual, baseline, additions):
    require(set(actual) == set(baseline) | set(additions), 'source membership differs from exact baseline/additions')
    require(all(actual.get(name) == value for name, value in baseline.items()), 'baseline source bytes changed')


def recipe(root, require_running=True):
    root = checked(root)
    if require_running:
        require(checked(__file__) == root / SCRIPT, 'V2 consumer outside selected source')
    require(checked(common.__file__) == ROOT / 'tools/current_composite_native_v1.py', 'V1 helper import outside consumer source')
    require(file_hash(root / CONTRACT) == CONTRACT_SHA, 'V2 contract changed')
    contract = load(root / CONTRACT)
    require(contract['kind'] == KIND and contract['schema'] == 2, 'wrong V2 contract')
    require(file_hash(root / BASELINE) == contract['baselineSha256'], 'baseline manifest changed')
    baseline = load(root / BASELINE)
    require(len(baseline) == 3999, 'baseline size changed')
    exact_projection(common.inventory(root), baseline, ADDITIONS)
    require(set(contract['additionalSha256']) == ADDITIONS - {SCRIPT, CONTRACT}, 'additional pin set differs')
    require(contract['compiledApplication']['sourceBaselineSha256'] == contract['baselineSha256'], 'compiled source baseline differs')
    for name, expected in contract['additionalSha256'].items():
        require(name in ADDITIONS and file_hash(root / name) == expected, 'V2 addition changed: ' + name)
    require(file_hash(Path(common.__file__)) == baseline['tools/current_composite_native_v1.py'], 'imported V1 helper changed')
    require(file_hash(Path(common.classfiles.__file__)) == baseline['tools/verify_termux_pty_derivative.py'], 'imported class parser changed')
    require(common.classfiles.__file__ == str(ROOT / 'tools/verify_termux_pty_derivative.py'), 'class parser outside consumer source')
    old = load(root / 'tools/current-composite-native-v1.json')
    require(file_hash(root / 'tools/current-composite-native-v1.json') == contract['v1ContractSha256'], 'historical V1 contract changed')
    # V1 admission is intentionally NOT called or monkey-patched. Its stale
    # substrate pin is retained as the exact historical compatibility boundary.
    changes = {name for name, expected in old['sourceAndToolSha256'].items() if baseline.get(name) != expected}
    require(changes == {contract['substrate']['path']}, 'unexpected historical compatibility delta')
    require(old['sourceAndToolSha256'][contract['substrate']['path']] == contract['substrate']['beforeSha256'], 'historical substrate pin differs')
    return contract, baseline


def validate_delta(original, current, proof):
    require(common.digest(original) == proof['beforeSha256'] and common.digest(current) == proof['afterSha256'], 'substrate identity differs')
    anchor = proof['anchor'].encode('utf-8'); insertion = proof['insertion'].encode('utf-8')
    require(len(insertion) == 210 and original.count(anchor) == 1, 'ambiguous substrate insertion anchor')
    require(original.replace(anchor, anchor + insertion, 1) == current, 'substrate change is not exact reviewed insertion')


def compiled_binding(jar, contract, filesystem_methods=None):
    proof = contract['compiledApplication']
    require(checked(jar).stat().st_size == proof['bytes'], 'compiled application size differs')
    blob = raw(jar)
    require(common.digest(blob) == proof['sha256'], 'compiled application provenance differs')
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and len(names) == proof['archiveMemberCount'], 'compiled archive membership differs')
        selected = {name for name in names if any(name == family + '.class' or name.startswith(family + '$') and name.endswith('.class') for family in proof['families'])}
        require(selected == set(proof['classes']), 'compiled family closure differs')
        rows = {}
        for name, expected in proof['classes'].items():
            info = archive.getinfo(name)
            require(info.file_size == expected['size'] and info.file_size <= 4 * 1024 * 1024, 'compiled class size differs')
            value = archive.read(info)
            require(common.digest(value) == expected['sha256'], 'compiled class bytes differ: ' + name)
            rows[name] = expected
        for expected in proof['methods']:
            value = common.classfiles.parse_class(archive.read(expected['class']))
            matches = [m for m in value['methods'] if m['name'] == expected['name'] and m['descriptor'] == expected['descriptor']]
            require(len(matches) == 1 and matches[0]['access'] == expected['access'], 'compiled method descriptor/access differs')
            require(common.digest(matches[0]['attributes']['Code']) == expected['codeAttributeSha256'], 'compiled method Code differs')
        if 'nativeMethods' in proof:
            value = common.classfiles.parse_class(archive.read(proof['nativeMethods']['class']))
            actual = [{'name':m['name'], 'descriptor':m['descriptor'], 'access':m['access']} for m in value['methods'] if m['access'] & 0x100]
            require(actual == proof['nativeMethods']['methods'], 'compiled filesystem native descriptors differ')
            names = {m['name']: m['descriptor'] for m in actual}
            require(filesystem_methods is not None and len(names) == len(actual) and names == filesystem_methods,
                    'compiled filesystem descriptors differ from immutable native recipe')
    return {'mainJarSha256': proof['sha256'], 'classCount': len(rows), 'classes': rows,
            'hostCompiledBindingOnly': True, 'androidRuntimeVerified': False,
            'externalRuntimeDependencies': proof['externalRuntimeDependencies']}


def validate_inputs(root, locations, contract, baseline):
    require(set(locations) == set(common.INPUT_NAMES) | set(EXTRA_INPUTS), 'V2 input set differs')
    p = {name: checked(path) for name, path in locations.items()}
    child = p['child_source_root']
    exact_projection(common.inventory(child), baseline, set())
    require(file_hash(child / 'tools/current_composite_native_v1.py') == file_hash(Path(common.__file__)), 'child V1 helper differs')
    validate_delta(raw(p['original_substrate']), raw(root / contract['substrate']['path']), contract['substrate'])
    compiled = compiled_binding(p['application_main_jar'], contract, load(root / 'tools/filesystem-native-v1.json')['methods'])
    return p, compiled


def child_locations(locations):
    return {name: locations[name] for name in common.INPUT_NAMES}


def observation(root, locations, contract, compiled, children, providers):
    baseline = load(root / BASELINE)
    exact_projection(common.inventory(root), baseline, ADDITIONS)
    exact_projection(common.inventory(locations['child_source_root']), baseline, set())
    return {'currentSourceRoot': str(root), 'childSourceRoot': str(locations['child_source_root']),
            'baselineSha256': file_hash(root / BASELINE),
            'currentSourceManifest': common.inventory(root),
            'compiledApplication': compiled, 'originalSubstrateSha256': file_hash(locations['original_substrate']),
            'components': common.lineage(root, child_locations(locations), contract, providers)}


def measured(root, locations, contract, compiled, children, providers, before, output):
    require(common.inventory(output / 'native') == {n:r['sha256'] for n,r in providers.items()}, 'staged native inventory differs')
    require(file_hash(output / 'engine/terminal-engine.aar') == before['components']['javaAar'], 'staged AAR differs')
    return {'schema': 2, 'kind': KIND, 'status': common.STATUS, 'deviceValidated': False,
            'apkAdmitted': False, 'releaseCompatible': False, 'androidRegistrationVerified': False,
            'componentBytesVerified': True, 'compiledJavaAbiMatched': True, 'linuxOwnedJniVerified': True,
            'sourceRoot': str(root), 'inputs': {name:str(path) for name,path in locations.items()},
            'contractSha256': CONTRACT_SHA, 'compositorSha256': file_hash(Path(__file__)),
            'lineage': before, 'providers': providers,
            'engine': {'path':'engine/terminal-engine.aar', 'sha256': before['components']['javaAar'],
                       'binaryBaseRetained': True, 'fullSourceRebuild': False},
            'hostCounts': {'ownedChecks': children['host']['ownedChecks'], 'regressionTests': children['host']['regressionTests']},
            'limitations': ['Host compiled application linkage is not final APK DEX or Android runtime proof',
                           'Auxiliary native providers retained, not rebuilt',
                           'Original V1 recipe and child receipts unchanged; distinct child/current roots',
                           'APK consumer lane remains false-default and requires separate V2 integration'],
            'evidence': common.output_evidence(output, providers)}


def assemble(root, locations, output):
    root, output = checked(root), checked(output)
    contract, baseline = recipe(root)
    locations, compiled = validate_inputs(root, locations, contract, baseline)
    require(not output.exists(), 'output exists; no overwrite')
    require(common.disjoint(root, output) and all(common.disjoint(output,p) for p in locations.values()), 'output overlaps protected input')
    output.mkdir(parents=True, exist_ok=False); (output/'verification').mkdir()
    child = locations['child_source_root']; inputs = child_locations(locations)
    common.verify_children(child, inputs, output, record=True)
    children, engine = common.read_components(child, inputs, contract)
    providers = common.selected_providers(root, inputs, contract, children)
    before = observation(root, locations, contract, compiled, children, providers)
    (output/'engine').mkdir(); (output/'engine/terminal-engine.aar').write_bytes(engine)
    for name,row in providers.items():
        target=output/'native'/name; target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('xb') as stream: stream.write(raw(row['source']))
    locations, final_compiled = validate_inputs(root, locations, contract, baseline)
    require(observation(root,locations,contract,final_compiled,children,providers) == before, 'input changed during composition')
    recipe(root)
    result=measured(root,locations,contract,compiled,children,providers,before,output)
    common.write_json(output/'receipt.json',result)
    return result


def verify(root, output):
    requested, output=checked(root),checked(output)
    requested_contract, requested_baseline=recipe(requested)
    saved=load(output/'receipt.json'); origin=checked(saved['sourceRoot'])
    contract,baseline=recipe(origin,require_running=False)
    require(contract==requested_contract and baseline==requested_baseline and file_hash(origin/SCRIPT)==file_hash(Path(__file__)), 'relocated V2 consumer differs')
    locations,compiled=validate_inputs(origin,saved['inputs'],contract,baseline)
    require(all(common.disjoint(output,p) for p in [requested,origin,*locations.values()]), 'composite aliases protected inputs')
    inputs=child_locations(locations); child=locations['child_source_root']
    common.verify_children(child,inputs,output,record=False)
    children,_=common.read_components(child,inputs,contract)
    providers=common.selected_providers(origin,inputs,contract,children)
    before=observation(origin,locations,contract,compiled,children,providers)
    require(saved==measured(origin,locations,contract,compiled,children,providers,before,output), 'V2 receipt differs from current evidence')
    _,final_compiled=validate_inputs(origin,locations,contract,baseline)
    require(observation(origin,locations,contract,final_compiled,children,providers)==before,'inputs changed during verification')
    recipe(requested)
    return saved


def main():
    parser=argparse.ArgumentParser(description=__doc__); subs=parser.add_subparsers(dest='action',required=True)
    for action in ('assemble','verify'):
        p=subs.add_parser(action);p.add_argument('--root',type=Path,default=ROOT);p.add_argument('--output',type=Path,required=True)
        if action=='assemble':
            for name in (*common.INPUT_NAMES,*EXTRA_INPUTS):p.add_argument('--'+name.replace('_','-'),type=Path,required=True)
    args=parser.parse_args()
    try:
        result=(assemble(args.root,{name:getattr(args,name) for name in (*common.INPUT_NAMES,*EXTRA_INPUTS)},args.output)
                if args.action=='assemble' else verify(args.root,args.output))
        print(json.dumps({'kind':KIND,'status':result['status'],'providers':len(result['providers']),'apkAdmitted':False}))
    except (OSError,ValueError,KeyError,subprocess.SubprocessError,struct.error,zipfile.BadZipFile) as error:
        raise SystemExit('CURRENT_COMPOSITE_NATIVE_V2_REJECTED: '+str(error)) from error

if __name__=='__main__': main()
