'use strict';
// Held process-group leader. Native LSP is relayed unchanged; anonymous IPC carries ownership controls only.
const cp=require('node:child_process');const I=require('./csharp-identity.cjs');const {check}=I;
function boundedRelay(input,output,fail,maximum=268435456){let total=0,queued=0,stopped=false;
 const onData=bytes=>{if(stopped)return;try{check((total+=bytes.length)<=maximum&&queued+bytes.length<=8388608,'C# relay byte bound');queued+=bytes.length;
  const writable=output.write(bytes,e=>{queued-=bytes.length;if(e)fail(e);});if(!writable)input.pause();}catch(e){fail(e);}};
 const drain=()=>input.resume();input.on('data',onData);output.on('drain',drain);input.on('end',()=>output.end());input.on('error',fail);output.on('error',fail);
 return ()=>{stopped=true;input.off('data',onData);output.off('drain',drain);input.pause();};
}
async function sdkHealth(owner){
 return await new Promise((resolve,reject)=>{let total=0,output='',error='',done=false;
  const probe=cp.spawn(owner.sdkExecutable,['--list-sdks'],{cwd:owner.rootPath,env:process.env,stdio:['ignore','pipe','pipe']});
  const timer=setTimeout(()=>{reject(new Error('SDK discovery deadline'));},15000);
  probe.stdout.on('data',b=>{total+=b.length;if(total>65536)reject(new Error('SDK discovery output bound'));else output+=b.toString('utf8');});
  probe.stderr.on('data',b=>{total+=b.length;if(total>65536)reject(new Error('SDK discovery output bound'));else error+=b.toString('utf8');});
  probe.on('error',e=>{clearTimeout(timer);reject(e);});probe.once('close',(code,signal)=>{clearTimeout(timer);try{
   check(code===0&&!signal&&error.trim()===''&&output.trim()==='10.0.402 ['+owner.sdkRoot+'/sdk]','Unexpected original SDK discovery');resolve();
  }catch(e){reject(e);}});
 });
}
async function runHeld(root){check(process.send&&process.connected,'C# held launcher requires owner IPC');
 const first=I.loadOwner(root),owner=first.owner,birth=I.proc(process.pid);check(birth.group===process.pid);
 let released=false,child=null,closed=false,stopping=false,terminal=false,code=null,signal=null;const relays=[];
 const send=value=>{if(process.connected)process.send({...value,token:owner.token,ownerGeneration:owner.ownerGeneration});};
 const retire=reason=>{if(stopping)return;stopping=true;send({kind:'retire',reason:String(reason||'Native exit').slice(0,256)});try{child?.stdin.end();}catch(_){}};
 // Keep the leader alive until the supervisor has retired ordinary descendants and requests final exit.
 const monitor=setInterval(()=>{try{check(process.connected,'Supervisor disconnected');check(I.privateRoot(root)===first.rootIdentity&&I.regular(root+'/owner.json',65536).equals(first.bytes));
  if(closed&&I.groupObservation(birth.group).count===1)send({kind:'native-reaped',pid:birth.pid,start:birth.start,exitCode:code,exitSignal:signal});
 }catch(e){retire(e.message);}},100);
 process.on('message',async m=>{try{check(m&&m.token===owner.token&&m.ownerGeneration===owner.ownerGeneration,'C# ownership control mismatch');
  if(m.kind==='release'){check(!released&&!stopping);released=true;I.loadOwner(root);
   if(owner.mode==='health'){await sdkHealth(owner);check(!stopping,'Owner retired during SDK discovery');I.loadOwner(root);}
   const argv=[owner.serverExecutable];if(owner.solutionPath!==null)argv.push('--solution',owner.solutionPath);
   child=cp.spawn(owner.sdkExecutable,argv,{cwd:owner.rootPath,env:process.env,stdio:['pipe','pipe','pipe']});
   child.on('error',e=>retire(e.message));// Direct reaping is exit, not close: an ordinary descendant can retain the native stdout pipe.
   child.once('exit',(c,s)=>{closed=true;code=c;signal=s;retire('Native process exited');});
   const fail=e=>retire(e.message);relays.push(boundedRelay(process.stdin,child.stdin,fail),boundedRelay(child.stdout,process.stdout,fail),boundedRelay(child.stderr,process.stderr,fail,1048576));
  }else if(m.kind==='stop'){retire('Owner retired');}
  else if(m.kind==='finish'){check(!child||closed);check(I.groupObservation(birth.group).count===1);terminal=true;clearInterval(monitor);for(const close of relays)close();process.disconnect();process.exitCode=code===0&&!signal?0:70;}
  else throw new Error('Unsupported C# ownership control');
 }catch(e){retire(e.message);}});
 process.on('disconnect',()=>{if(!terminal){
  stopping=true;clearInterval(monitor);for(const close of relays)close();
  // The retained leader is still this live process. Never signal a recycled direct-child PID after reaping.
  const ownGroup=sig=>{const now=I.proc(process.pid);check(now.start===birth.start&&now.group===process.pid&&birth.group===process.pid,'Held leader birth lost');process.kill(-birth.group,sig);};
  try{ownGroup('SIGTERM');}catch(_){}
  setTimeout(()=>{try{ownGroup('SIGKILL');}catch(_){process.exit(70);}},1000);
 }});
 for(const name of ['SIGTERM','SIGINT','SIGHUP'])process.on(name,()=>retire('Group retirement requested'));
 process.stdin.on('end',()=>retire('Owner input ended'));process.stdout.on('error',e=>retire(e.message));
 send({kind:'held',pid:birth.pid,start:birth.start});
}
module.exports={boundedRelay,sdkHealth,runHeld};
if(require.main===module){if(process.argv.length!==3)process.exitCode=70;else runHeld(process.argv[2]).catch(()=>process.exitCode=70);}
