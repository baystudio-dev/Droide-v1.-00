#!/usr/bin/env python3
"""Exercise production terminal process lifecycle, launch environment and background commands."""
import argparse,os,re,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
CORE=ROOT/'app/src/main/java/com/baystudio/droide/core';TEST=ROOT/'app/src/test/java/com/baystudio/droide/core'
def main():
 p=argparse.ArgumentParser();p.add_argument('--jars',action='append',required=True);a=p.parse_args()
 jars=sorted({f.resolve() for d in a.jars for f in Path(d).glob('*.jar')})
 for name in ['kotlin-compiler','kotlin-stdlib','coroutines-core','junit','hamcrest']:
  if not any(name in f.name for f in jars):raise SystemExit('Missing host dependency: '+name)
 cp=os.pathsep.join(map(str,jars))
 names=['InteractiveTerminalProcessTest','TerminalStartupCommandQueueTest','LocalProcessSupervisorTest','BackgroundCommandManagerTest','TerminalLaunchSpecTest','TerminalPipeShellTest','TerminalBackendStartTest']
 prod=['InteractiveTerminalProcess','TerminalStartupCommandQueue','ProcessInputBuffer','ProcessIo','LocalProcessSupervisor','TailByteBuffer','Utf8StreamDecoder','BackgroundCommandManager','PathSecurity','TerminalLaunchSpec','TerminalPipeShell','TerminalPromptPresentation','TerminalCommandActivity','TerminalBackendStart','ProcessSecurityPolicy','MonotonicProcessDeadline','LocalLinuxUserEnvironment','LocalUbuntuJdkPolicy','AgentTtyPolicy']
 with tempfile.TemporaryDirectory(prefix='droide-terminal-check-') as d:
  out=Path(d);s=(CORE/'TerminalSession.kt').read_text();contract=s.split('class TerminalSession(',1)[0];ansi=s[s.index('object Ansi {'):]
  (out/'TerminalContracts.kt').write_text(contract+'\n'+ansi+'\ndata class AgentDiskExecutionState(val dirty:Boolean=false) { companion object { val CLEAN=AgentDiskExecutionState() } }\n')
  interface=(CORE/'InteractivePtyCommandSession.kt').read_text().split('/** Ownership is separate',1)[0]
  operation=(CORE/'OwnedDevelopmentOperation.kt').read_text().split('interface DevelopmentOperationSession {',1)[1].split('}',1)[0]
  (out/'PtyContracts.kt').write_text(interface+'\ninterface DevelopmentOperationSession {'+operation+'}\n'+'abstract class ExecutionTerminalRouter : ITerminalSession { abstract fun bindCommandSession(): ITerminalSession }\n')
  # Compile-only identifier contracts: the standalone process harness does not test
  # Android workstation composition or Build.VERSION. Gradle tests cover those APIs.
  family=re.search(r'const val FAMILY\s*=\s*("[^"]+")',(CORE/'LocalAndroidWorkstation.kt').read_text())
  if family is None:raise SystemExit('Missing workstation family contract')
  unavailable=(CORE/'NativePtyCompatibility.kt').read_text().split('internal class NativePtyUnavailableException',1)[1].split('internal object NativePtyCompatibility',1)[0]
  (out/'HostIdentifierContracts.kt').write_text('package com.baystudio.droide.core\ninternal object LocalAndroidWorkstationPolicy { const val FAMILY = '+family.group(1)+' }\ninternal class NativePtyUnavailableException'+unavailable)
  files=[CORE/(n+'.kt') for n in prod]+[TEST/(n+'.kt') for n in names]+list(out.glob('*.kt'));classes=out/'classes'
  subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(classes),*map(str,files)],check=True)
  subprocess.run(['java','-cp',str(classes)+os.pathsep+cp,'org.junit.runner.JUnitCore',*['com.baystudio.droide.core.'+n for n in names]],check=True)
if __name__=='__main__':
 try:main()
 except subprocess.CalledProcessError as e:raise SystemExit(e.returncode)
