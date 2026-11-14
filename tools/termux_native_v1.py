#!/usr/bin/env python3
"""New current-source Termux build evidence; not historical or APK admission."""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import subprocess
import filesystem_native_v1 as common
import termux_native_elf as elf_tools

CONTRACT = Path(__file__).with_name('termux-native-v1.json')
CONTRACT_SHA = 'eb38cd0f2a62e22d3461eccb984215e55347fe5ffc2f406162d1133bb72ec7ec'
STATUS = 'BUILD_ONLY_REGISTRATION_BINDING_PENDING'
TOOL_FILES = ('tools/termux_native_v1.py', 'tools/termux_native_elf.py', 'tools/termux-native-v1.json',
              'tools/termux-native-v1.md', 'tools/tests/test_termux_native_v1.py', 'tools/filesystem_native_v1.py')


def c_type(descriptor, offset):
    types = {'I':'jint','J':'jlong','Z':'jboolean','V':'void'}
    if descriptor.startswith('[B',offset): return 'jbyteArray',offset+2
    if descriptor.startswith('[Ljava/lang/String;',offset): return 'jobjectArray',offset+19
    if descriptor.startswith('Ljava/lang/String;',offset): return 'jstring',offset+18
    common.require(offset < len(descriptor) and descriptor[offset] in types, 'unsupported JNI descriptor type')
    return types[descriptor[offset]],offset+1


def header(methods):
    lines = ['/* Frozen owned-token JNI signatures. Not dynamic registration proof. */', '#include <jni.h>']
    for method in methods:
        descriptor = method['descriptor']; offset = 1; args = []
        common.require(descriptor.startswith('('), 'invalid JNI descriptor')
        while offset < len(descriptor) and descriptor[offset] != ')':
            value,offset = c_type(descriptor,offset)
            common.require(value != 'void', 'void JNI argument')
            args.append(value)
        common.require(offset < len(descriptor), 'unterminated JNI descriptor')
        returns,end = c_type(descriptor,offset+1)
        common.require(end == len(descriptor), 'trailing JNI descriptor bytes')
        parameters = ', '.join(['JNIEnv *','jclass',*args])
        lines.append(f'static {returns} JNICALL {method["function"]}({parameters});')
    lines.append('JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *);')
    return '\n'.join(lines)+'\n'


def source_contract(root, contract):
    for relative, expected in contract['sourceSha256'].items():
        common.require(common.sha(common.no_links(root/relative)) == expected, 'source pin changed: '+relative)
    abi = common.load(root/'third_party/termux/native/OWNED_PROCESS_ABI.json')
    common.require(abi['ownedProcessApiVersion']==contract['ownedProcessApiVersion']==2, 'owned API version changed')
    common.require(abi['registeredNativeMethods']==[{k:r[k] for k in ('name','descriptor')} for r in contract['methods']], 'owned native contract differs')
    common.require(len(contract['methods'])==12 and len({r['name'] for r in contract['methods']})==12 and len({r['function'] for r in contract['methods']})==12, 'nonunique/incomplete owned method set')
    source=(root/'third_party/termux/native/termux.c').read_text()
    rows=re.findall(r'\{ "(\w+)", "([^"]+)", \(void\*\) (\w+) \}',source)
    common.require(rows==[(r['name'],r['descriptor'],r['function']) for r in contract['methods']], 'current C registration initializer differs')


def verify_helpers(root, contract):
    for module, relative in ((common, 'tools/filesystem_native_v1.py'),
                             (elf_tools, 'tools/termux_native_elf.py')):
        expected = common.no_links(root / relative)
        common.require(common.no_links(Path(module.__file__)) == expected,
                       'imported helper is outside selected source root')
        common.require(common.sha(expected) == contract['helperSha256'][relative],
                       'fixed helper identity changed: ' + relative)


def inputs(root,sdk,contract):
    verify_helpers(root, contract)
    source_contract(root,contract)
    ndk=sdk/'ndk'/common.NDK; cmake=sdk/'cmake'/common.CMAKE
    for path,version in ((ndk,common.NDK),(cmake,common.CMAKE)):
        text=(common.no_links(path)/'source.properties').read_text()
        common.require(re.search(r'^Pkg.Revision\s*=\s*'+re.escape(version)+r'\s*$',text,re.M), 'toolchain revision changed')
    tools={name:(cmake/'bin'/name if name in ('cmake','ninja') else ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'/name) for name in contract['toolSha256']}
    for name,expected in contract['toolSha256'].items():
        common.require(common.sha(tools[name])==expected, 'historical tool identity changed: '+name)
    # Disassembler is measured as part of the full installed NDK; no vendor archive claim.
    tools['llvm-objdump']=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump'
    common.require(tools['llvm-objdump'].is_file(), 'installed NDK disassembler unavailable')
    manifest={'source':{p:common.sha(root/p) for p in contract['sourceSha256']},
              'tooling':{p:common.sha(common.no_links(root/p)) for p in TOOL_FILES},
              'ndk':common.snapshot(ndk),'cmake':common.snapshot(cmake)}
    return tools,manifest


def commands(root,sdk,output,stage,abi,tools):
    # Reuse the frozen deterministic recipe builder, replacing only the exact component target.
    result=common.commands(root,sdk,output,stage,abi,tools)
    common.require(result[1][result[1].index('--target')+1]=='droide_files', 'shared recipe contract drifted')
    result[1][result[1].index('--target')+1]='termux'
    result.append([str(tools['llvm-objdump']),'-d','--disassemble-symbols=JNI_OnLoad',str(output/stage/abi/'libtermux.so')])
    return result


def artifacts(root,sdk,output,contract,tools):
    result={}
    for stage in ('primary','independent'):
        common.require(common.no_links(output/stage/'jni-contract.h').read_text()==header(contract['methods']), 'generated prototype header changed')
        for abi in common.ABIS:
            directory=output/stage/abi; binary=common.no_links(directory/'libtermux.so')
            common.require(not (directory/'libdroide_files.so').exists(), 'unrequested filesystem component compiled')
            record=common.load(common.no_links(directory/'commands.json'))
            common.require(record==[{'argv':cmd,'exit':0} for cmd in commands(root,sdk,output,stage,abi,tools)], 'native command evidence differs')
            inspection=elf_tools.inspect(binary.read_bytes(),abi,contract)
            result[stage+'/'+abi]={'sha256':common.sha(binary),'size':binary.stat().st_size,'staticInspection':inspection,
                'evidenceSha256':{name:common.sha(common.no_links(directory/name)) for name in ('commands.json','command-0.log','command-1.log','command-2.log','compile_commands.json')}}
    for abi in common.ABIS:
        common.require(all(result['primary/'+abi][k]==result['independent/'+abi][k] for k in ('sha256','size','staticInspection')), 'independent bytes/static table differ: '+abi)
    return result


def execute(root,sdk,output,verify=False):
    root,sdk,output=map(common.no_links,(root,sdk,output))
    common.require(all(common.disjoint(a,b) for a,b in ((root,sdk),(root,output),(sdk,output))), 'overlapping roots')
    common.require(all(re.fullmatch(r'[A-Za-z0-9_./-]+',str(p)) for p in (root,sdk,output)), 'unsupported recipe path')
    common.require(common.no_links(Path(__file__))==root/'tools/termux_native_v1.py', 'tooling must belong to selected source')
    common.require(output.is_dir() if verify else not output.exists(), 'missing verify output or existing build output')
    common.require(common.sha(common.no_links(CONTRACT)) == CONTRACT_SHA, 'versioned Termux contract changed')
    contract=common.load(CONTRACT)
    common.require(contract['schema']=='termux-native-v1', 'wrong Termux contract schema')
    tools,before=inputs(root,sdk,contract)
    if not verify:
        output.mkdir(parents=True,exist_ok=False);common.save(output/'inputs-before.json',before)
        for stage in ('primary','independent'):
            stage_root=output/stage;stage_root.mkdir()
            (stage_root/'jni-contract.h').write_text(header(contract['methods']))
            (stage_root/'home').mkdir();(stage_root/'tmp').mkdir()
            env={'PATH':str(tools['cmake'].parent)+':'+str(tools['clang'].parent)+':/usr/bin:/bin','HOME':str(stage_root/'home'),'TMPDIR':str(stage_root/'tmp'),'LC_ALL':'C','LANG':'C','TZ':'UTC','SOURCE_DATE_EPOCH':'0'}
            for abi in common.ABIS:
                directory=stage_root/abi;directory.mkdir();record=[]
                for i,cmd in enumerate(commands(root,sdk,output,stage,abi,tools)):
                    with (directory/f'command-{i}.log').open('wb') as log:
                        completed=subprocess.run(cmd,cwd=stage_root,env=env,stdout=log,stderr=subprocess.STDOUT,check=False)
                    record.append({'argv':cmd,'exit':completed.returncode});common.save(directory/'commands.json',record)
                    common.require(completed.returncode==0,'native command failed; retained logs are diagnostic only')
    common.require(common.load(common.no_links(output/'inputs-before.json'))==before,'inputs changed since build')
    measured=artifacts(root,sdk,output,contract,tools)
    _,after=inputs(root,sdk,contract)
    common.require(before==after,'inputs changed during run')
    receipt={'schema':'termux-native-v1','status':STATUS,'releaseCompatible':False,'deviceValidated':False,'apkAdmitted':False,
        'registrationPathVerified':False,'finalJavaBindingVerified':False,'componentAdmitted':False,
        'sourceArchiveSha256':contract['sourceArchiveSha256'],'api':29,'ndk':common.NDK,'cmake':common.CMAKE,
        'contractSha256':common.sha(CONTRACT),'inputManifestSha256':common.sha(output/'inputs-before.json'),'artifacts':measured,
        'limitations':['No Android code executed','Static loader-relative table is not JNI_OnLoad call-path proof','Final selected compiled JNI.class binding pending','Installed toolchain measured; no vendor archive provenance','Old R40/R46/R63 gates unchanged','RELRO header presence only; GNU hash/version graph semantics not independently emulated']}
    if verify: common.require(common.load(common.no_links(output/'receipt.json'))==receipt,'receipt does not match measured evidence')
    else: common.save(output/'receipt.json',receipt)
    return receipt


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('build','verify'))
    for name in ('root','sdk','output'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args()
    try:
        result=execute(args.root,args.sdk,args.output,args.action=='verify')
        print(result['status']+'; componentAdmitted=false; APK/device not validated')
    except (ValueError,OSError,KeyError,IndexError) as error:
        raise SystemExit('TERMUX_NATIVE_V1_REJECTED: '+str(error)) from error

if __name__=='__main__':main()
