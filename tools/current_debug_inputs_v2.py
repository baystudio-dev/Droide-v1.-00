#!/usr/bin/env python3
"""Explicit V2 APK source bridge; sealed composite remains at its original root."""
from current_native_jni_guard import verify_composite_jni
import argparse
import json
from pathlib import Path
import tempfile
import current_debug_inputs as legacy
from current_owned_command import supervise
regular, sha, source_manifest = legacy.regular, legacy.sha, legacy.source_manifest
COMPOSITE_TIMEOUT_SECONDS = legacy.COMPOSITE_TIMEOUT_SECONDS
ROOT = Path(__file__).resolve().parents[1]
CONTRACT = 'tools/current-debug-v2-bridge.json'
CONTRACT_SHA = 'fbb45d9bcc281ab02ac88e6261b15ce0112d1683b967438087545668339c904e'

def require(value, message):
    if not value: raise ValueError(message)

def bridge(root, composite):
    raw = regular(root / CONTRACT)
    require(sha(raw) == CONTRACT_SHA, 'V2 bridge contract changed')
    spec = json.loads(raw)
    origin = Path(spec['originRoot'])
    require(sha(regular(composite / 'receipt.json')) == spec['receiptSha256'], 'V2 composite receipt changed')
    receipt = json.loads(regular(composite / 'receipt.json'))
    require(receipt['kind'] == 'CURRENT_COMPOSITE_NATIVE_V2' and receipt['sourceRoot'] == str(origin), 'Wrong composite/origin')
    require(sha(regular(Path(spec['compositePython']))) == spec['compositePythonSha256'], 'Composite interpreter changed')
    expected = spec['sourceProjection']
    actual = source_manifest(root)
    require(set(actual) == set(expected) | set(spec['additions']), 'Unexpected successor source membership')
    require(all(actual[k] == v for k,v in expected.items()), 'Unreviewed baseline/build change')
    require(all(actual[k] == v for k,v in spec['additionPins'].items()), 'V2 helper changed')
    require(source_manifest(origin) == spec['originProjection'], 'Original composite source changed')
    return origin

def verify(root, composite, context):
    root, composite = Path(root).absolute(), Path(composite).absolute()
    require(root == ROOT, 'Verifier outside selected application source')
    verify_composite_jni(root, composite)
    context_bytes = regular(context); spec = json.loads(context_bytes)
    require(set(spec) == {'schemaVersion','sourceSha256','compositeReceiptSha256'} and spec['schemaVersion'] == 2, 'Wrong V2 context')
    require(source_manifest(root) == spec['sourceSha256'], 'Application source changed')
    receipt = regular(composite / 'receipt.json')
    require(sha(receipt) == spec['compositeReceiptSha256'], 'Context receipt changed')
    origin = bridge(root, composite)
    logs = Path(tempfile.mkdtemp(prefix='droide-composite-v2-consumer-'))
    interpreter = json.loads(regular(root / CONTRACT))['compositePython']
    argv = [interpreter,'-B','-E','-s',str(origin/'tools/current_composite_native_v2.py'),'verify','--root',str(origin),'--output',str(composite)]
    env = {'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','LC_ALL':'C.UTF-8','TZ':'UTC'}
    with (logs/'consumer.stdout').open('xb') as out, (logs/'consumer.stderr').open('xb') as err:
        result = supervise(argv, origin, env, out.fileno(), err.fileno(), COMPOSITE_TIMEOUT_SECONDS, 1)
    (logs/'consumer.command.json').write_text(json.dumps(result,indent=2)+'\n')
    require(result['outcome'] == 'SUCCESS', 'Composite verification failed; logs: '+str(logs))
    require(source_manifest(root) == spec['sourceSha256'] and regular(context) == context_bytes and regular(composite/'receipt.json') == receipt, 'Inputs changed during verification')
    bridge(root, composite)
    print('CURRENT_DEBUG_V2_INPUTS_OK APK_UNADMITTED evidence='+str(logs))

if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for n in ('root','composite','context'): p.add_argument('--'+n,required=True,type=Path)
    a=p.parse_args();verify(a.root,a.composite,a.context)
