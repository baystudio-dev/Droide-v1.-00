#!/usr/bin/env python3
"""Mutation controls for report boundaries. Mutates copies only; never accesses a real report server."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
for name in ('root','toolchain','dependencies','output'):
    parser.add_argument('--'+name,type=Path,required=True)
args=parser.parse_args()
root=args.root.resolve();output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
files=['app/src/main/java/com/baystudio/droide/core/'+name+'.kt' for name in ('BugReportPolicy','BugReportCoordinator','OfficialBugReportTransport','OkHttpAwait')]
files+=['app/src/test/java/com/baystudio/droide/core/'+name+'.kt' for name in ('BugReportPolicyR75Test','BugReportCoordinatorR75Test','OfficialBugReportTransportR75Test')]
files+=['tools/host-regressions/run_bug_report.py','tools/host-regressions/r75-report/FocusedJUnitRunner.java','third_party/maven/com/squareup/okio/okio-jvm/3.17.0/okio-jvm-3.17.0.jar']
transport='app/src/main/java/com/baystudio/droide/core/OfficialBugReportTransport.kt'
policy='app/src/main/java/com/baystudio/droide/core/BugReportPolicy.kt'
mutations=[
    ('redirect_enabled',transport,'.followRedirects(false)','.followRedirects(true)'),
    ('string_ok_accepted',transport,'?.takeUnless { it.isString }?.booleanOrNull','?.booleanOrNull'),
    ('privacy_ack_ignored',policy,'if (!draft.privacyAcknowledged) put(BugReportField.PRIVACY, "Agree to the privacy notice before sending.")','// Negative control: consent check removed.'),
    ('second_post_dispatched',transport,'requireExactResponse(response, BugReportPolicy.SUBMIT_URL)','client.newCall(request).execute().close() // Negative control: duplicate POST.\n                    requireExactResponse(response, BugReportPolicy.SUBMIT_URL)'),
    ('replayable_post_body',transport,'override fun isOneShot(): Boolean = true','override fun isOneShot(): Boolean = false'),
]
def digest(path):
    return {'path':str(path),'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
original=[digest(root/file) for file in files]+[digest(Path(__file__).resolve())]
records=[]
for name,file,before,after in mutations:
    source=output/name/'source';source.mkdir(parents=True)
    for item in files:
        target=source/item;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(root/item,target)
    target=source/file;text=target.read_text()
    if text.count(before)!=1:raise RuntimeError('Mutation target is not unique: '+name)
    target.write_text(text.replace(before,after))
    records.append({'name':name,'changed_file':file,'before':before,'after':after,'source':str(source)})
def run(item):
    source=Path(item['source']);evidence=source.parent/'result'
    command=['python3',str(source/'tools/host-regressions/run_bug_report.py'),'--root',str(source),
        '--toolchain',str(args.toolchain.resolve()),'--dependencies',str(args.dependencies.resolve()),'--output',str(evidence)]
    result=subprocess.run(command,capture_output=True,text=True,timeout=240)
    (source.parent/'run.log').write_text(result.stdout+result.stderr)
    failures=[]
    for file in evidence.glob('TEST-*.xml'):
        for testcase in ET.parse(file).getroot().findall('testcase'):
            if testcase.find('failure') is not None:failures.append(testcase.get('classname')+'#'+testcase.get('name'))
    return {**item,'exit_code':result.returncode,'failure_tests':failures,'killed':result.returncode!=0 and bool(failures),'commands':command}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    results=list(pool.map(run,records))
unchanged=all(digest(Path(item['path']))==item for item in original)
(output/'RESULT.json').write_text(json.dumps({
    'scope':'Negative controls in isolated source copies only. All HTTP requests use in-process test interceptors; no sockets or production reporting. Each mutant must fail a focused assertion after successful compilation.',
    'source_inputs':original,'source_inputs_unchanged':unchanged,'mutations':results,'all_killed':all(item['killed'] for item in results)},indent=2)+'\n')
for item in results:print(item['name'],item['killed'],item['failure_tests'])
raise SystemExit(0 if unchanged and all(item['killed'] for item in results) else 1)
