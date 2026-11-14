#!/usr/bin/env python3
"""Offline provenance gate for the two locally pinned Termux terminal AARs."""
from __future__ import annotations
import argparse, difflib, hashlib, io, json, zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
MANIFEST=ROOT/'third_party/termux/UPSTREAM_PROVENANCE.json'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--private-debug-device-unvalidated', action='store_true',
                    help='Verify separate private build-only native evidence; never release validation')
PRIVATE_DEBUG=parser.parse_args().private_debug_device_unvalidated

def fail(msg:str)->None:
    raise SystemExit('TERMUX_PROVENANCE_FAILED: '+msg)

def sha256(data:bytes)->str:
    return hashlib.sha256(data).hexdigest()

def git_blob_sha1(data:bytes)->str:
    return hashlib.sha1(f'blob {len(data)}\0'.encode()+data).hexdigest()

def read_json(path:Path):
    try: return json.loads(path.read_text(encoding='utf-8'))
    except Exception as e: fail(f'invalid {path}: {e}')

m=read_json(MANIFEST)
if m.get('schemaVersion')!=1: fail('unsupported schema')
up=m.get('upstream',{})
if up.get('tag')!='v0.118.0' or up.get('commit')!='6e2689f55295fa444be8ac8592c527c2c5ef3253':
    fail('upstream tag/commit pin changed')
license_copy=ROOT/'app/src/main/assets/legal/licenses/Termux-upstream-LICENSE.md'
if git_blob_sha1(license_copy.read_bytes()) != up.get('rootLicenseGitBlobSha1'):
    fail('packaged upstream LICENSE.md is not byte-identical to v0.118.0')

source_top_classes=set()
source_count=0
for key in ('terminalEmulatorSources','terminalViewSources'):
    spec=m['artifacts'][key]
    path=ROOT/spec['path']
    raw=path.read_bytes()
    if sha256(raw)!=spec['sha256']: fail(f'{spec["path"]} SHA-256 mismatch')
    expected=spec['gitBlobSha1ByPath']
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        actual={n for n in z.namelist() if n.endswith('.java')}
        if actual != set(expected):
            fail(f'{path.name} source set mismatch: expected={len(expected)} actual={len(actual)}')
        for name,want in expected.items():
            data=z.read(name)
            got=git_blob_sha1(data)
            if got!=want: fail(f'{path.name}:{name} upstream git blob mismatch')
            source_top_classes.add(name[:-5]+'.class')
            source_count += 1
if source_count != m['constraints']['expectedJavaSourceFiles']:
    fail(f'expected {m["constraints"]["expectedJavaSourceFiles"]} upstream Java files, found {source_count}')

all_classes=set()
for key in ('terminalEmulatorAar','terminalViewAar'):
    spec=m['artifacts'][key]
    path=ROOT/spec['path']
    raw=path.read_bytes()
    if sha256(raw)!=spec['sha256']: fail(f'{spec["path"]} SHA-256 mismatch')
    with zipfile.ZipFile(io.BytesIO(raw)) as aar:
        if 'classes.jar' not in aar.namelist(): fail(f'{path.name} missing classes.jar')
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as classes:
            current={n for n in classes.namelist() if n.endswith('.class')}
            all_classes |= current
for prefix in m['constraints']['forbiddenClassPrefixes']:
    bad=sorted(c for c in all_classes if c.startswith(prefix))
    if bad: fail(f'forbidden class scope {prefix}: {bad[:3]}')
allowed_generated=set(m['constraints']['allowedGeneratedTopLevelClasses'])
for cls in all_classes:
    top=cls.split('$',1)[0]+'.class' if '$' in cls else cls
    if top not in source_top_classes and top not in allowed_generated:
        fail(f'AAR class has no verified upstream source mapping: {cls}')
missing=sorted(source_top_classes - all_classes)
if missing: fail('verified source file missing compiled top-level class: '+', '.join(missing))
if len(all_classes)!=m['constraints']['expectedAarClassFiles']:
    fail(f'expected {m["constraints"]["expectedAarClassFiles"]} AAR classes, found {len(all_classes)}')

em_path=ROOT/m['artifacts']['terminalEmulatorAar']['path']
with zipfile.ZipFile(em_path) as aar:
    actual_native={n:sha256(aar.read(n)) for n in aar.namelist() if n.startswith('jni/') and n.endswith('/libtermux.so')}
if actual_native != m['native']['libtermuxSha256ByAarPath']:
    fail('libtermux.so ABI set/hash mismatch')
java_spec=m['artifacts'].get('terminalEmulatorJavaAar')
if not java_spec: fail('missing stripped Java-only AAR provenance')
java_path=ROOT/java_spec['path']
java_raw=java_path.read_bytes()
if sha256(java_raw)!=java_spec['sha256']: fail(f'{java_spec["path"]} SHA-256 mismatch')
with zipfile.ZipFile(em_path) as full, zipfile.ZipFile(io.BytesIO(java_raw)) as stripped:
    full_non_jni={n for n in full.namelist() if not n.startswith('jni/')}
    stripped_names=set(stripped.namelist())
    if stripped_names != full_non_jni:
        fail('Java-only terminal AAR is not exactly the reviewed upstream AAR with JNI entries removed')
    for name in full_non_jni:
        if stripped.read(name) != full.read(name): fail(f'Java-only terminal AAR entry differs from upstream AAR: {name}')

view_path=ROOT/m['artifacts']['terminalViewAar']['path']
with zipfile.ZipFile(view_path) as aar:
    if any(n.startswith('jni/') and n.endswith('.so') for n in aar.namelist()):
        fail('terminal-view unexpectedly contains native libraries')
if set(m['native']['sourceGitBlobs']) != {'terminal-emulator/src/main/jni/termux.c','terminal-emulator/src/main/jni/Android.mk'}:
    fail('native source provenance set changed')

abi_path=ROOT/'third_party/termux/native/OWNED_PROCESS_ABI.json'
abi=read_json(abi_path)
if abi.get('schemaVersion') != 1 or abi.get('ownedProcessApiVersion') != 2:
    fail('owned PTY ABI source contract changed')
for relative,want in abi.get('sourceSha256ByPath',{}).items():
    if sha256((ROOT/relative).read_bytes()) != want:
        fail('owned PTY ABI source contract hash mismatch: '+relative)
# Intentionally fail closed. Source authoring and a TEST-ONLY host JAR cannot
# authorize a distributable native ABI. Future release needs a reviewed native
# receipt, complete registered signatures and measured ABI/library evidence.
if not PRIVATE_DEBUG and abi.get('status') != 'BUILT_AND_INDEPENDENTLY_VERIFIED':
    fail('owned PTY ABI v2 native rebuild pending: source-only contract is UNBUILT')
rebuild=read_json(ROOT/'third_party/termux/native/REBUILD.json')
if not PRIVATE_DEBUG and (rebuild.get('owned_process_api_version') != 2 or
        rebuild.get('owned_process_abi_source_sha256') != sha256(abi_path.read_bytes()) or
        rebuild.get('jni_registration_signatures') != abi.get('registeredNativeMethods') or
        rebuild.get('jni_onload_export') != 'JNI_OnLoad' or
        rebuild.get('obsolete_numeric_exports_absent') is not True):
    fail('native ABI v2 registration/rebuild receipt missing or stale')
if rebuild.get('upstream_commit') != up['commit']: fail('native rebuild upstream commit mismatch')
native_source=ROOT/'third_party/termux/native/termux.c'
if not PRIVATE_DEBUG and sha256(native_source.read_bytes()) != rebuild.get('termux_c_sha256'):
    fail('compiled native PTY source differs from reviewed rebuild metadata')
if rebuild.get('local_changes'):
    original=ROOT/rebuild.get('upstream_source','')
    if not original.is_file(): fail('locally patched JNI is missing its original upstream source')
    raw=original.read_bytes()
    if sha256(raw) != rebuild.get('upstream_termux_c_sha256') or git_blob_sha1(raw) != m['native']['sourceGitBlobs']['terminal-emulator/src/main/jni/termux.c']:
        fail('original native PTY source does not match pinned upstream')
elif git_blob_sha1(native_source.read_bytes()) != m['native']['sourceGitBlobs']['terminal-emulator/src/main/jni/termux.c']:
    fail('unrecorded native source changes')

# This distinct local Java-only artifact leaves the four upstream AAR/source pins above intact.
local=read_json(ROOT/'third_party/termux/patches/REBUILD.json')
if local.get('schemaVersion') != 3 or local.get('upstreamCommit') != up['commit']:
    fail('reviewed terminal overlay rebuild pending: local schema/upstream mismatch (do not edit receipts without rebuilding)')
local_paths={
    'originalJavaAar':'app/libs/terminal-emulator-v0.118.0-java.aar',
    'originalSourceJar':'third_party/termux/terminal-emulator-v0.118.0-sources.jar',
    'source':'third_party/termux/patches/src/com/termux/terminal/TerminalSession.java',
    'patch':'third_party/termux/patches/0001-agent-input-owner-hook.patch',
    'rowSource':'third_party/termux/patches/src/com/termux/terminal/TerminalRow.java',
    'rowPatch':'third_party/termux/patches/0002-bounded-combining-row.patch',
    'emulatorSource':'third_party/termux/patches/src/com/termux/terminal/TerminalEmulator.java',
    'emulatorPatch':'third_party/termux/patches/0003-bounded-parser-work.patch',
    'bufferSource':'third_party/termux/patches/src/com/termux/terminal/TerminalBuffer.java',
    'bufferPatch':'third_party/termux/patches/0004-retained-buffer-budget.patch',
    'queueSource':'third_party/termux/patches/src/com/termux/terminal/ByteQueue.java',
    'queuePatch':'third_party/termux/patches/0005-drainable-output-queue.patch',
    'memorySource':'third_party/termux/patches/src/com/termux/terminal/TerminalMemoryBudget.java',
    'memoryPatch':'third_party/termux/patches/0006-mobile-memory-budget.patch',
    'jniSource':'third_party/termux/patches/src/com/termux/terminal/JNI.java',
    'jniPatch':'third_party/termux/patches/0007-owned-process-jni.patch',
    'ownerSource':'third_party/termux/patches/src/com/termux/terminal/TerminalProcessOwner.java',
    'ownerPatch':'third_party/termux/patches/0008-prestarted-process-owner.patch',
    'ioSource':'third_party/termux/patches/src/com/termux/terminal/TerminalPtyIo.java',
    'ioPatch':'third_party/termux/patches/0009-bounded-pty-io.patch',
    'ownedProcessAbiSource':'third_party/termux/native/OWNED_PROCESS_ABI.json',
    'buildScript':'tools/build_termux_input_aar.py',
    'output':'app/libs/terminal-emulator-v0.118.0-droide-input.aar',
}
for key,expected_path in local_paths.items():
    spec=local.get(key,{})
    if spec.get('path') != expected_path: fail(f'local {key} path changed')
    path=ROOT/expected_path
    if not path.is_file() or sha256(path.read_bytes()) != spec.get('sha256'):
        fail(f'local {key} SHA-256 mismatch')
if local['originalJavaAar']['sha256'] != java_spec['sha256']:
    fail('local hook was not derived from pinned original Java-only AAR')
if local['originalSourceJar']['sha256'] != m['artifacts']['terminalEmulatorSources']['sha256']:
    fail('local hook was not derived from pinned original source JAR')
compiler=local.get('compiler',{})
if not compiler.get('version','').startswith('javac 17.') or compiler.get('arguments') != ['--release','8','-encoding','UTF-8','-g:none']:
    fail('local input hook compiler/Java bytecode target changed')
if local.get('androidJar',{}).get('api') != 36:
    fail('local input hook Android SDK API changed')
overlays=(
    ('source','patch','TerminalSession',True),
    ('rowSource','rowPatch','TerminalRow',True),
    ('emulatorSource','emulatorPatch','TerminalEmulator',True),
    ('bufferSource','bufferPatch','TerminalBuffer',True),
    ('queueSource','queuePatch','ByteQueue',True),
    ('memorySource','memoryPatch','TerminalMemoryBudget',False),
    ('jniSource','jniPatch','JNI',True),
    ('ownerSource','ownerPatch','TerminalProcessOwner',False),
    ('ioSource','ioPatch','TerminalPtyIo',False),
)
with zipfile.ZipFile(ROOT/local_paths['originalSourceJar']) as sources:
    for source_key,patch_key,class_name,from_upstream in overlays:
        source_name='com/termux/terminal/'+class_name+'.java'
        original_source=sources.read(source_name).decode('utf-8') if from_upstream else ''
        local_source=(ROOT/local_paths[source_key]).read_text(encoding='utf-8')
        exact_patch=''.join(difflib.unified_diff(
            original_source.splitlines(keepends=True),local_source.splitlines(keepends=True),
            fromfile='a/'+source_name if from_upstream else '/dev/null',tofile='b/'+source_name,
        )).encode('utf-8')
        if (ROOT/local_paths[patch_key]).read_bytes() != exact_patch:
            fail('local overlay diff does not exactly describe '+class_name)
prefix='com/termux/terminal/'
def local_class(name:str)->bool:
    return any(name == prefix+class_name+'.class' or (name.startswith(prefix+class_name+'$') and name.endswith('.class'))
               for _,_,class_name,_ in overlays)
with zipfile.ZipFile(java_path) as original,zipfile.ZipFile(ROOT/local_paths['output']) as patched:
    if set(original.namelist()) != set(patched.namelist()):
        fail('local input AAR entry set differs from original Java-only AAR')
    if any(name.startswith('jni/') for name in patched.namelist()):
        fail('local input AAR unexpectedly includes JNI entries')
    for name in original.namelist():
        if name != 'classes.jar' and patched.read(name) != original.read(name):
            fail('local input AAR changed an unrelated entry: '+name)
    with zipfile.ZipFile(io.BytesIO(original.read('classes.jar'))) as before,zipfile.ZipFile(io.BytesIO(patched.read('classes.jar'))) as after:
        before_set=set(before.namelist());after_set=set(after.namelist())
        added={prefix+name+'.class' for name in ('TerminalSession$InputHandler', 'TerminalMemoryBudget', 'TerminalSession$NativeBackend', 'TerminalSession$WriterBackend', 'TerminalSession$ReaderStopCondition', 'ByteQueue$StopCondition', 'TerminalProcessOwner', 'TerminalProcessOwner$Backend', 'TerminalProcessOwner$Worker', 'TerminalPtyIo', 'TerminalPtyIo$Backend', 'TerminalPtyIo$WriteReceipt', 'TerminalSession$ReaderBackend', 'TerminalPtyIo$ReaderBackend', 'TerminalPtyIo$ReadReceipt')}
        if local.get('addedClasses') != sorted(added) or after_set != before_set|added:
            fail('local input hook introduced unexpected/missing classes')
        replaced={name for name in after_set if local_class(name)}
        if replaced != {prefix+name+'.class' for name in ('TerminalRow','TerminalBuffer','ByteQueue','TerminalEmulator','TerminalEmulator$SavedScreenState','TerminalSession','TerminalSession$1','TerminalSession$2','TerminalSession$3','TerminalSession$MainThreadHandler','JNI')}|added:
            fail('local Termux overlay compiled class scope changed')
        actual_replacement={name:sha256(after.read(name)) for name in replaced}
        if actual_replacement != local.get('replacementClassSha256ByPath'):
            fail('local terminal overlay compiled class hashes differ from rebuild metadata')
        for name in before_set-replaced:
            if before.read(name) != after.read(name): fail('local input hook changed an unrelated compiled entry: '+name)
        for name in replaced:
            data=after.read(name)
            if data[:4] != b'\xca\xfe\xba\xbe' or int.from_bytes(data[6:8],'big') != 52:
                fail('local input hook class is not valid Java 8 bytecode: '+name)

sums_path=ROOT/'third_party/termux/SHA256SUMS'
expected_sum=local['output']['sha256']+'  ../../app/libs/terminal-emulator-v0.118.0-droide-input.aar'
if expected_sum not in sums_path.read_text(encoding='utf-8').splitlines():
    fail('local overlay SHA256SUMS entry does not match the measured rebuilt artifact')

if PRIVATE_DEBUG:
    # Only the native release admission above differs. Every upstream, original native
    # source, Java overlay, exact diff/class scope and checksum check still ran.
    from verify_private_debug_native import verify_private_inputs
    verify_private_inputs(ROOT)

print(f'TERMUX_PROVENANCE_OK upstream={up["tag"]}@{up["commit"][:7]} java_sources={source_count} classes={len(all_classes)} native_abis={len(actual_native)} local_input_classes={len(replaced)}')
