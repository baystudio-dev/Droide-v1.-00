from pathlib import Path
import os,json,hashlib,subprocess,time
s=Path(__file__).resolve().parents[2];r=s.parent; f=s/'tools/host-regressions/r64-cli-exit'
o=r/'evidence'/os.environ.get('R64_CLI_EVIDENCE','cli-exit-boundary-01');o.mkdir()
for n in ['host-classes','api36-classes']: (o/n).mkdir()
tc=r.parent/'r44-recovery-20261017/toolchain';java=tc/'jdk-17.0.20.1+1/bin/java';lib=tc/'gradle-8.13/lib'
sdk=s/'third_party/termux/derivatives/r63/build/primary/inputs/android.jar'
compiler=[lib/n for n in ['kotlin-compiler-embeddable-2.0.21.jar','kotlin-stdlib-2.0.21.jar','kotlin-script-runtime-2.0.21.jar','kotlin-reflect-2.0.21.jar','trove4j-1.0.20200330.jar','kotlinx-coroutines-core-jvm-1.6.4.jar','annotations-24.0.1.jar']]
runtime=[lib/n for n in ['kotlin-stdlib-2.0.21.jar','annotations-24.0.1.jar','kotlinx-coroutines-core-jvm-1.6.4.jar','kotlinx-serialization-core-jvm-1.6.2.jar','kotlinx-serialization-json-jvm-1.6.2.jar','junit-4.13.2.jar','hamcrest-core-1.3.jar']]
core=s/'app/src/main/java/com/baystudio/droide/core'; test=s/'app/src/test/java/com/baystudio/droide/core'
server=Path(os.environ.get('R64_CLI_SOURCE',str(core/'DroideCliServer.kt'))).resolve()
prod=[core/(n+'.kt') for n in ['CliEndpointPublication','CliConnectionLifetime','DroideCliWireProtocol','CliProgressRelay','PackageProgressObserver']]+[server]
classes=['CliEndpointPublicationTest','CliConnectionLifetimeTest','CliProgressRelayTest','DroideCliWireProtocolTest']
host=prod+list(f.glob('Android*.kt'))+[f/'Boundaries.kt',f/'CliServerExitReceiptHostTest.kt']+[test/(c+'.kt') for c in classes]
api=prod+[f/'Boundaries.kt']
cp=':'.join(map(str,runtime));ccp=':'.join(map(str,compiler))
def compile(src,dest,extra=''):return [str(java),'-cp',ccp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp+extra,'-d',str(dest)]+list(map(str,src))
commands=[[str(java.parent/'javap'),'-constants','-classpath',str(sdk),'android.os.Build$VERSION_CODES'],compile(api,o/'api36-classes',':'+str(sdk)),compile(host,o/'host-classes'),[str(java),'-cp',str(o/'host-classes')+':'+cp,'org.junit.runner.JUnitCore']+['com.baystudio.droide.core.'+c for c in classes+['CliServerExitReceiptHostTest']]]
def rec(p):return {'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
inputs=[rec(p) for p in list(dict.fromkeys(host+api))+[Path(__file__)]]
(o/'PLAN.json').write_text(json.dumps({'scope':'Targeted actual Android API36 Kotlin type compilation and exact server source host lifecycle receipts with Android socket doubles; existing pure TCP connection and real temporary-file endpoint tests. No Android runtime/full app/APK/dependency downloads.','inputs':inputs,'tools':[rec(p) for p in compiler+runtime+[sdk]],'commands':commands,'limitations':['Runtime uses explicitly fake Android sockets, Context, Process, Log and unrelated authority/tool/admission/installer boundaries. Pure connection tests use actual loopback TCP only.','API36 type compilation uses the byte-recorded android.jar and the same explicit unrelated boundary contracts. Existing pinned toolchain coroutines 1.6.4/serialization 1.6.2 are used, not a claim about the full Gradle production dependency graph.']},indent=2))
steps=[]
for i,c in enumerate(commands):
 log=o/f'{i+1}.log';start=time.monotonic()
 with log.open('wb') as output: res=subprocess.run(c,cwd=s,stdout=output,stderr=subprocess.STDOUT,timeout=150)
 steps.append({'exit_code':res.returncode,'seconds':time.monotonic()-start,'log':rec(log)})
 print(log.read_text())
 if res.returncode:break
(o/'RESULT.json').write_text(json.dumps({'steps':steps,'inputs_unchanged':all(rec(Path(x['path']))==x for x in inputs),'android_runtime':False,'full_app_compile':False},indent=2))
raise SystemExit(steps[-1]['exit_code'])
