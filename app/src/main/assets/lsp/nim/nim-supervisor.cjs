'use strict';
// Ordinary direct-child/process-group ownership. No hostile same-UID or escaped-descendant claim.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process');
const I=require('./nim-helper-identity-health.cjs');
const {check,regular,parseData,proc,atomic,loadOwner,privateRoot}=I;
function groupMembers(group) {
  check(Number.isSafeInteger(group)&&group>1);
  // Signal zero is non-destructive: after reaping the leader, never send a killing signal to its numeric PID/group.
  try {process.kill(-group,0);return [{group}];}catch(e){if(e.code==='ESRCH')return [];throw e;}
}
function guards(root,owner) {
  check(fs.readdirSync(path.join(root,'commands')).length===3);
  for(const name of ['nim','nimsuggest','nimble']){const bytes=regular(path.join(root,'commands',name),8192);check(I.sha(bytes)===owner.commandSha256[name]);}
  check(process.env.PATH===path.join(root,'commands')&&process.env.DROIDE_NIM_OWNER===root);
}
async function supervise(root) {
  const first=loadOwner(root),owner=first.owner;check(process.cwd()===owner.rootPath);guards(root,owner);
  let child=null,birth=null,closed=false,exitCode=null,exitSignal=null,stopping=false,forced=false,error=null,stdoutBytes=0,stderrBytes=0;
  let monitor=null,killTimer=null,eofTimer=null,retirementDeadline=null,resolveClosed,resolveStop;
  const closeDone=new Promise(resolve=>{resolveClosed=resolve;}),stopDone=new Promise(resolve=>{resolveStop=resolve;});
  const deadline=Date.now()+1800000;const supervisor=proc(process.pid);
  const owned=()=>{if(!birth)return false;try{const now=proc(birth.pid);return now.start===birth.start&&now.group===birth.group&&now.ppid===process.pid;}catch(_){return false;}};
  const signal=sig=>{
    if(closed||!child)return;
    if(owned())process.kill(-birth.group,sig);
    else child.kill(sig); // Exact retained ChildProcess handle only; unknown group is never signalled.
  };
  const invalidate=()=>{try{atomic(root,'retired.json',{schema:1,token:owner.token,status:'RETIRED'});}catch(e){error=e.message;}};
  const stop=reason=>{if(stopping)return;stopping=true;error=reason||error;retirementDeadline=Date.now()+7500;invalidate();resolveStop();
    try{if(child&&!closed){forced=true;signal('SIGTERM');}}catch(e){error=e.message;}
    killTimer=setTimeout(()=>{if(!closed)try{signal('SIGKILL');}catch(e){error=e.message;}},1500);killTimer.unref();
  };
  async function waitClosed(ms){let timer;try{return await Promise.race([closeDone.then(()=>true),new Promise(resolve=>{timer=setTimeout(()=>resolve(false),ms);})]);}finally{clearTimeout(timer);}}
  async function receipt() {
    let empty=0;const end=Math.min(Date.now()+8000,retirementDeadline||Infinity);
    if(birth&&closed)while(Date.now()<end&&empty<2){try{empty=groupMembers(birth.group).length===0?empty+1:0;}catch(e){error=e.message;break;}await new Promise(r=>setTimeout(r,50));}
    const complete=closed&&empty>=2;
    atomic(root,'teardown.json',{schema:1,token:owner.token,status:complete?'COMPLETE':'UNKNOWN',directChildReaped:closed,groupEmpty:empty>=2,forcedTermination:forced,
      exitCode,exitSignal,reason:error,serverPid:birth?birth.pid:null,serverStart:birth?birth.start:null});
    return complete;
  }
  try {
    child=cp.spawn(owner.serverExecutable,['--lsp','--stdio'],{cwd:owner.rootPath,env:process.env,detached:true,stdio:['pipe','pipe','pipe']});
    // Install the cleanup completion before any fallible birth/status/relay work.
    child.once('close',(code,sig)=>{closed=true;exitCode=code;exitSignal=sig;clearTimeout(killTimer);clearTimeout(eofTimer);resolveClosed();});
    await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});
    birth=proc(child.pid);check(birth.ppid===process.pid&&birth.group===child.pid&&birth.start!=='0');
    atomic(root,'server.json',{schema:1,token:owner.token,pid:birth.pid,start:birth.start,group:birth.group,supervisorPid:process.pid,supervisorStart:supervisor.start});
    child.stdout.on('data',b=>{stdoutBytes+=b.length;if(stdoutBytes>268435456)stop('Owner stdout bound');});
    child.stderr.on('data',b=>{stderrBytes+=b.length;if(stderrBytes>4194304)stop('Owner stderr bound');});
    child.stdin.on('error',()=>{if(!closed&&child.exitCode===null&&child.signalCode===null)stop('Server input failed');});
    child.stdout.on('error',()=>stop('Server output failed'));child.stderr.on('error',()=>stop('Server error output failed'));
    process.stdin.on('error',()=>stop('Owner input failed'));process.stdout.on('error',()=>stop('Owner output closed'));process.stderr.on('error',()=>stop('Owner error output closed'));
    process.stdout.on('close',()=>{if(!closed)stop('Owner output closed');});process.stderr.on('close',()=>{if(!closed)stop('Owner error output closed');});
    const inputEnded=()=>{if(closed||eofTimer)return;invalidate();eofTimer=setTimeout(()=>stop('Owner input ended'),1000);};
    process.stdin.on('end',inputEnded);process.stdin.on('close',inputEnded);
    child.stdout.pipe(process.stdout);child.stderr.pipe(process.stderr);process.stdin.pipe(child.stdin);
    for(const sig of ['SIGTERM','SIGINT','SIGHUP'])process.on(sig,()=>stop('Owner retired'));
    monitor=setInterval(()=>{try{
      check(privateRoot(root)===first.rootIdentity);check(regular(path.join(root,'owner.json')).equals(first.bytes));
      if(fs.existsSync(path.join(root,'stop.json')))stop('Owner retired');
      if(fs.existsSync(path.join(root,'failure.json')))stop('Metadata failed');
      if(fs.existsSync(path.join(root,'guard.json')))stop('Forbidden fallback command');
      if(Date.now()>deadline)stop('Owner lifetime exceeded');
      if(!closed&&!owned())stop('Original server identity changed');
    }catch(e){stop(e.message);}},50);
    await Promise.race([closeDone,stopDone]);
    if(!closed)await waitClosed(Math.max(0,retirementDeadline-Date.now()));
    clearInterval(monitor);process.stdin.unpipe(child.stdin);process.stdin.pause();
    const complete=await receipt();return complete&&exitCode===0&&!exitSignal&&!error?0:70;
  } catch(e) {
    error=e.message;stop(error);
    try{atomic(root,'failure.json',{schema:1,token:owner.token,status:'FAILED',kind:'ADMISSION_REJECTED'});}catch(_){}
    if(child)await waitClosed(2000);else {closed=true;resolveClosed();}
    try{await receipt();}catch(_){}return 70;
  } finally {
    clearInterval(monitor);clearTimeout(killTimer);clearTimeout(eofTimer);
    if(child){process.stdin.unpipe(child.stdin);process.stdin.pause();child.stdin.destroy();child.stdout.destroy();child.stderr.destroy();if(!closed)child.unref();}
  }
}
module.exports={groupMembers,guards,supervise};
if(require.main===module){if(process.argv.length!==3)process.exitCode=70;else supervise(process.argv[2]).then(code=>{process.exitCode=code;}).catch(()=>{process.exitCode=70;});}
