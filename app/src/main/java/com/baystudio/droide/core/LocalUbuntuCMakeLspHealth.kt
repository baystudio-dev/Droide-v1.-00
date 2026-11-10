package com.baystudio.droide.core

/** Zero-document lifecycle and independent help probes; never project/semantic acceptance. */
internal object LocalUbuntuCMakeLspHealth {
    const val TIMEOUT_MS = 60_000L
    const val WORK_MS = 45_000L
    const val CLEANUP_MS = 8_000L
    const val SUCCESS = "DROIDE_CMAKE_HEALTH_OK"
    fun succeeded(result: ExecResult): Boolean = result.exitCode == 0 && !result.timedOut &&
        !result.outputIncomplete && result.output.trim() == SUCCESS
    val script: String = """# App-authored zero-document health, using the same held-leader primitive as editor ownership.
import sys
if not (sys.flags.isolated and sys.flags.no_site and sys.flags.dont_write_bytecode):
    raise SystemExit(70)
import os
import time
import json
import selectors
import importlib.util
import re

START = time.monotonic()
WORK_END = START + 45
OWNED = []
STDERR_TOTAL = 0
PRIVATE = None
PRIVATE_ID = None
S = None


def check(value, message='CMake health rejected'):
    if not value:
        raise RuntimeError(message)


def load(name, path):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec)
    sys.modules[name]=module;spec.loader.exec_module(module);return module


def end(seconds):
    return min(WORK_END,time.monotonic()+seconds)


def complete(owner, request_stop=False):
    receipt=S.teardown_owned(owner,min(START+53,time.monotonic()+8),request_stop=request_stop)
    check(receipt['status']=='COMPLETE' and receipt['directChildReaped'] is True and receipt['groupEmpty'] is True and
          receipt['forcedTermination'] is False and type(receipt['preReapScans']) is int and receipt['preReapScans']>=2 and
          receipt['postReapGroupObservation']=='ESRCH' and type(receipt['exitCode']) is int and receipt['exitCode']==0)
    OWNED.remove(owner)


class Pipes:
    def __init__(self,owner,limit):
        self.owner=owner;self.selector=selectors.DefaultSelector();self.stdout=bytearray();self.stderr=0;self.total=0;self.limit=limit;self.eof=set()
        for name,stream in (('stdout',owner.process.stdout),('stderr',owner.process.stderr)):
            os.set_blocking(stream.fileno(),False);self.selector.register(stream,selectors.EVENT_READ,name)
        os.set_blocking(owner.process.stdin.fileno(),False)
    def send(self,value,deadline):
        data=json.dumps(value,separators=(',',':'),ensure_ascii=True).encode('ascii');pending=memoryview(b'Content-Length: '+str(len(data)).encode()+b'\r\n\r\n'+data)
        while pending:
            check(time.monotonic()<deadline)
            try:n=os.write(self.owner.process.stdin.fileno(),pending);check(n>0);pending=pending[n:]
            except BlockingIOError:self.poll(deadline,0.01)
    def poll(self,deadline,wait=0.05):
        global STDERR_TOTAL
        check(time.monotonic()<deadline,'CMake health deadline')
        for key,_ in self.selector.select(min(wait,max(0,deadline-time.monotonic()))):
            try:data=os.read(key.fileobj.fileno(),65536)
            except BlockingIOError:continue
            if not data:self.eof.add(key.data);self.selector.unregister(key.fileobj);continue
            if key.data=='stderr':self.stderr+=len(data);STDERR_TOTAL+=len(data);check(STDERR_TOTAL<=65536)
            else:self.total+=len(data);check(self.total<=self.limit);self.stdout.extend(data)
    def frame(self,deadline):
        while b'\r\n\r\n' not in self.stdout:
            check(len(self.stdout)<=8192 and 'stdout' not in self.eof);self.poll(deadline)
        header,_,rest=self.stdout.partition(b'\r\n\r\n');check(len(header)<=8192)
        lines=header.decode('ascii','strict').split('\r\n');check(len(lines)==1 and re.fullmatch(r'Content-Length: (?:0|[1-9][0-9]*)',lines[0]) is not None)
        length=int(lines[0].split(': ')[1]);check(0<length<=1048576)
        while len(rest)<length:
            check('stdout' not in self.eof);self.poll(deadline);header,_,rest=self.stdout.partition(b'\r\n\r\n')
        body=bytes(rest[:length]);self.stdout=bytearray(rest[length:])
        def pairs(items):
            out={}
            for k,v in items:check(k not in out);out[k]=v
            return out
        value=json.loads(body.decode('utf-8','strict'),object_pairs_hook=pairs,parse_constant=lambda _:check(False))
        check(isinstance(value,dict));return value
    def drain(self,deadline):
        while len(self.eof)<2:self.poll(deadline)
    def close(self):
        self.selector.close()
        for stream in (self.owner.process.stdin,self.owner.process.stdout,self.owner.process.stderr):stream.close()


def run():
    global PRIVATE,PRIVATE_ID,S
    check(len(sys.argv)==3)
    PRIVATE=sys.argv[1];expected_identity=sys.argv[2]
    check(re.fullmatch(r'/tmp/droide-cmake-health-[A-Za-z0-9]+',PRIVATE) is not None)
    root=__file__[:-len('/payload/cmake-lsp-health.py')]
    check(__file__==root+'/payload/cmake-lsp-health.py')
    B=load('droide_cmake_bootstrap',root+'/payload/cmake-bootstrap.py')
    S=load('droide_cmake_supervisor',root+'/payload/cmake-supervisor.py')
    PRIVATE_ID=S.private_identity(PRIVATE)
    check(str(PRIVATE_ID['dev'])+':'+str(PRIVATE_ID['ino'])==expected_identity)
    check(os.getcwd()==PRIVATE+'/cwd')
    saved_argv=sys.argv;sys.argv=[B.__file__]
    try:B.inspect(True)
    finally:sys.argv=saved_argv
    env={'PATH':'','HOME':PRIVATE+'/home','TMPDIR':PRIVATE+'/tmp','XDG_CONFIG_HOME':PRIVATE+'/xdg-config',
         'XDG_CACHE_HOME':PRIVATE+'/xdg-cache','XDG_DATA_HOME':PRIVATE+'/xdg-data','XDG_STATE_HOME':PRIVATE+'/xdg-state',
         'XDG_RUNTIME_DIR':PRIVATE+'/xdg-run','LANG':'C.UTF-8','LC_ALL':'C.UTF-8','TERM':'dumb','DROIDE_CMAKE_POLICY':'health'}
    lease=os.environ.get('DROIDE_PROCESS_LEASE','');check(re.fullmatch('[0-9a-f]{20}',lease) is not None);env['DROIDE_PROCESS_LEASE']=lease
    aggregate=0
    for argument in ('--version','--help-commands','--help-variables','--help-modules'):
        owner=S.launch_owned(['/usr/bin/cmake',argument],PRIVATE+'/cwd',env,'health');OWNED.append(owner)
        pipes=Pipes(owner,8388608);deadline=end(5)
        try:
            owner.process.stdin.close();pipes.drain(deadline);data=bytes(pipes.stdout);text=data.decode('utf-8','strict');check(text.strip())
            aggregate+=len(data);check(aggregate<=16777216)
            if argument=='--version':check(re.match(r'cmake version 3\.28\.[0-9]+(?:\n|\r\n)',text) is not None)
            complete(owner)
        finally:pipes.close()
    owner=S.launch_owned(['/usr/bin/python3.12','-I','-S','-B',root+'/payload/cmake-bootstrap.py'],PRIVATE+'/cwd',env,'health');OWNED.append(owner)
    pipes=Pipes(owner,4194304)
    try:
        request={'jsonrpc':'2.0','id':1,'method':'initialize','params':{'processId':None,'rootUri':None,'workspaceFolders':[],
            'capabilities':{'general':{'positionEncodings':['utf-32']}},
            'initializationOptions':{'cmakeExecutable':'/usr/bin/cmake','buildDirectory':PRIVATE+'/build'}}}
        deadline=end(20);pipes.send(request,deadline);reply=pipes.frame(deadline)
        check(set(reply)=={'jsonrpc','id','result'} and reply['jsonrpc']=='2.0' and type(reply['id']) is int and reply['id']==1)
        result=reply['result'];check(isinstance(result,dict) and set(result)=={'capabilities','serverInfo'})
        check(result['serverInfo']=={'name':'cmake-language-server','version':'0.1.11'})
        caps=result['capabilities'];check(caps.get('positionEncoding')=='utf-32' and caps.get('hoverProvider') is True)
        completion=caps.get('completionProvider');check(isinstance(completion,dict) and completion.get('triggerCharacters')==['{','('] and completion.get('resolveProvider',False) is False)
        check(set(completion)<= {'triggerCharacters','resolveProvider'})
        sync=caps.get('textDocumentSync');check(isinstance(sync,dict) and set(sync)=={'openClose','change','save'})
        check(sync['openClose'] is True and type(sync['change']) is int and sync['change']==2)
        check(isinstance(sync['save'],dict) and set(sync['save'])=={'includeText'} and sync['save']['includeText'] is False)
        check(set(caps)<= {'positionEncoding','hoverProvider','completionProvider','textDocumentSync','workspace'})
        if 'workspace' in caps:
            workspace=caps['workspace'];check(isinstance(workspace,dict) and set(workspace) in ({'workspaceFolders'},{'workspaceFolders','fileOperations'}))
            folders=workspace['workspaceFolders'];check(isinstance(folders,dict) and set(folders)=={'supported','changeNotifications'} and folders['supported'] is True and folders['changeNotifications'] is True)
            check('fileOperations' not in workspace or workspace['fileOperations']=={})
        pipes.send({'jsonrpc':'2.0','method':'initialized','params':{}},end(3))
        pipes.send({'jsonrpc':'2.0','id':2,'method':'shutdown','params':None},end(3))
        reply=pipes.frame(end(3));check(reply=={'jsonrpc':'2.0','id':2,'result':None} and type(reply['id']) is int)
        pipes.send({'jsonrpc':'2.0','method':'exit','params':None},end(1));owner.process.stdin.close()
        pipes.drain(end(5));check(not pipes.stdout,'Trailing or partial LSP output');complete(owner)
    finally:pipes.close()
    saved_argv=sys.argv;sys.argv=[B.__file__]
    try:B.inspect(True)
    finally:sys.argv=saved_argv
    check(time.monotonic()<WORK_END)
    os.chdir('/');check(S.private_cleanup(PRIVATE,PRIVATE_ID,min(START+53,time.monotonic()+8)),'Private health cleanup failed');PRIVATE=None
    print('DROIDE_CMAKE_HEALTH_OK')


try:
    run()
except BaseException as failure:
    for child in list(OWNED):
        try:
            receipt=S.teardown_owned(child,min(START+53,time.monotonic()+8),request_stop=True)
            if receipt['status']=='COMPLETE' and receipt['directChildReaped'] is True and receipt['groupEmpty'] is True:
                OWNED.remove(child)
        except BaseException:pass
    if PRIVATE is not None and PRIVATE_ID is not None and not OWNED and not isinstance(failure,getattr(S,'OwnedLaunchError',())):
        try:os.chdir('/');S.private_cleanup(PRIVATE,PRIVATE_ID,min(START+53,time.monotonic()+8))
        except BaseException:pass
    sys.stderr.write('CMake isolated zero-document health failed\n');raise SystemExit(1)
"""
    val sha256: String get() = LocalUbuntuCMakePolicy.hash(script.toByteArray())
    fun command(guestRoot: String): List<String> {
        LocalUbuntuCMakePolicy.requireAbsolute(guestRoot)
        return listOf("/bin/sh", "-c", handoff(guestRoot))
    }
    fun handoff(guestRoot: String): String {
        LocalUbuntuCMakePolicy.requireAbsolute(guestRoot)
        return """set -eu
umask 077
lease="${'$'}{DROIDE_PROCESS_LEASE:-}"
case "${'$'}lease" in *[!0-9a-f]*) exit 70;; esac
[ "${'$'}{#lease}" -eq 20 ] || exit 70
private=${'$'}(/usr/bin/mktemp -d /tmp/droide-cmake-health-XXXXXXXXXXXX)
[ -d "${'$'}private" ] && [ ! -L "${'$'}private" ] || exit 70
for name in cwd build home tmp xdg-config xdg-cache xdg-data xdg-state xdg-run; do /bin/mkdir -m 700 "${'$'}private/${'$'}name"; done
identity=${'$'}(/usr/bin/stat -c '%d:%i' "${'$'}private")
cd "${'$'}private/cwd"
exec /usr/bin/env -i "HOME=${'$'}private/home" "TMPDIR=${'$'}private/tmp" "XDG_CONFIG_HOME=${'$'}private/xdg-config" "XDG_CACHE_HOME=${'$'}private/xdg-cache" "XDG_DATA_HOME=${'$'}private/xdg-data" "XDG_STATE_HOME=${'$'}private/xdg-state" "XDG_RUNTIME_DIR=${'$'}private/xdg-run" PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb "DROIDE_PROCESS_LEASE=${'$'}lease" DROIDE_CMAKE_POLICY=health /usr/bin/python3.12 -I -S -B ${LocalExecutionSubstrate.shellQuote("$guestRoot/${LocalUbuntuCMakePolicy.HEALTH}")} "${'$'}private" "${'$'}identity"
"""
    }
}
