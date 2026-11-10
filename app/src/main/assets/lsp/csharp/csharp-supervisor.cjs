'use strict';
// Authored ordinary process-group owner. No claim about hostile same-UID or escaped descendants.
const fs=require('node:fs'),cp=require('node:child_process');const I=require('./csharp-identity.cjs');
const {boundedRelay}=require('./csharp-held-launcher.cjs');const {check}=I;
function groupEmpty(group){check(Number.isSafeInteger(group)&&group>1);try{process.kill(-group,0);return false;}catch(e){if(e.code==='ESRCH')return true;throw e;}}
async function supervise(root){const first=I.loadOwner(root),owner=first.owner,own=I.proc(process.pid);check(process.cwd()===owner.rootPath);
 let child=null,birth=null,closed=false,stopping=false,reason=null,code=null,signal=null,nativeReaped=null,monitor=null,heldTimer=null;
 let resolveClosed,resolveStop;const closeDone=new Promise(r=>resolveClosed=r),stopDone=new Promise(r=>resolveStop=r);const relays=[];
 const bound=m=>m&&m.token===owner.token&&m.ownerGeneration===owner.ownerGeneration;
 const retained=()=>{if(!birth||closed)return false;try{const now=I.proc(birth.pid);return now.start===birth.start&&now.group===birth.group&&now.ppid===process.pid&&now.state!=='Z';}catch(_){return false;}};
 const control=kind=>{if(child?.connected)child.send({kind,token:owner.token,ownerGeneration:owner.ownerGeneration});};
 const stop=why=>{if(stopping)return;stopping=true;reason=why||reason;try{I.atomic(root,'retired.json',{schema:1,token:owner.token,ownerGeneration:owner.ownerGeneration,status:'RETIRED'});}catch(e){reason=e.message;}
  try{control('stop');}catch(_){}resolveStop();};
 const wait=async ms=>{let timer;try{return await Promise.race([closeDone.then(()=>true),new Promise(r=>timer=setTimeout(()=>r(false),ms))]);}finally{clearTimeout(timer);}};
 const signalOwned=sig=>{check(retained(),'Held leader birth lost; refusing numeric group signal');process.kill(-birth.group,sig);};
 async function cleanup(){stop(reason);if(!closed&&child){const until=Date.now()+2000;while(!closed&&Date.now()<until){if(nativeReaped&&retained()){try{control('finish');}catch(_){}break;}await wait(25);}await wait(100);
  if(!closed){try{signalOwned('SIGTERM');}catch(e){reason=e.message;}const until=Date.now()+2000;while(!closed&&Date.now()<until){if(nativeReaped&&retained()){try{control('finish');}catch(_){}break;}await wait(25);}await wait(100);}
  if(!closed){try{signalOwned('SIGKILL');}catch(e){reason=e.message;}await wait(2000);}}
  let empty=child===null?2:0;const end=Date.now()+2000;if(closed&&birth)while(Date.now()<end&&empty<2){try{empty=groupEmpty(birth.group)?empty+1:0;}catch(e){reason=e.message;break;}await new Promise(r=>setTimeout(r,25));}
  const complete=closed&&empty>=2;I.atomic(root,'teardown.json',{schema:1,token:owner.token,ownerGeneration:owner.ownerGeneration,status:complete?'COMPLETE':'UNKNOWN',directChildReaped:closed,groupEmpty:empty>=2,
   leaderPid:birth?.pid||null,leaderStart:birth?.start||null,supervisorPid:own.pid,supervisorStart:own.start,nativeExitCode:nativeReaped?.exitCode??null,exitCode:code,exitSignal:signal,reason});return complete;
 }
 try{child=cp.spawn(owner.helperExecutable,['--max-old-space-size=64',owner.packageRoot+'/payload/csharp-held-launcher.cjs',root],{cwd:owner.rootPath,env:process.env,detached:true,stdio:['pipe','pipe','pipe','ipc']});
  child.once('close',(c,s)=>{closed=true;code=c;signal=s;resolveClosed();});child.on('error',e=>stop(e.message));
  await new Promise((resolve,reject)=>{heldTimer=setTimeout(()=>reject(new Error('Held leader spawn deadline')),10000);child.once('spawn',resolve);child.once('error',reject);});clearTimeout(heldTimer);
  birth=I.proc(child.pid);check(birth.group===child.pid&&birth.ppid===process.pid);
  await new Promise((resolve,reject)=>{heldTimer=setTimeout(()=>reject(new Error('Held leader proof deadline')),10000);child.on('message',m=>{try{check(bound(m));
   if(m.kind==='held'){check(m.pid===birth.pid&&m.start===birth.start&&retained());I.atomic(root,'server.json',{schema:1,token:owner.token,ownerGeneration:owner.ownerGeneration,pid:birth.pid,start:birth.start,group:birth.group,supervisorPid:own.pid,supervisorStart:own.start});control('release');resolve();}
   else if(m.kind==='native-reaped'){check(m.pid===birth.pid&&m.start===birth.start&&retained());nativeReaped=m;stop('Native process exited');}
   else if(m.kind==='retire')stop(m.reason);else throw new Error('Invalid C# control');
  }catch(e){reject(e);stop(e.message);}});});clearTimeout(heldTimer);
  const fail=e=>stop(e.message);relays.push(boundedRelay(process.stdin,child.stdin,fail),boundedRelay(child.stdout,process.stdout,fail),boundedRelay(child.stderr,process.stderr,fail,1048576));
  process.stdin.on('end',()=>stop('Owner input ended'));process.stdout.on('close',()=>stop('Owner output closed'));for(const s of ['SIGTERM','SIGINT','SIGHUP'])process.on(s,()=>stop('Owner retired'));
  monitor=setInterval(()=>{try{check(I.privateRoot(root)===first.rootIdentity&&I.regular(root+'/owner.json',65536).equals(first.bytes));if(fs.existsSync(root+'/stop.json'))stop('Owner retired');
   if(!closed){check(retained(),'Held leader identity changed');const observation=I.groupObservation(birth.group);if(owner.mode==='health')check(observation.count<=2,'Unexpected health project/BuildHost descendant');}
  }catch(e){stop(e.message);}},100);
  await Promise.race([stopDone,closeDone]);const complete=await cleanup();return complete&&nativeReaped?.exitCode===0&&code===0&&!signal?0:70;
 }catch(e){reason=e.message;try{I.atomic(root,'failure.json',{schema:1,token:owner.token,ownerGeneration:owner.ownerGeneration,status:'FAILED',reason:String(e.message).slice(0,256)});}catch(_){}
  if(!child)closed=true;try{await cleanup();}catch(_){}return 70;
 }finally{clearTimeout(heldTimer);clearInterval(monitor);for(const close of relays)close();if(child){for(const stream of [child.stdin,child.stdout,child.stderr])stream.destroy();try{child.disconnect();}catch(_){}if(!closed)child.unref();}}
}
module.exports={groupEmpty,supervise};
if(require.main===module){if(process.argv.length!==3)process.exitCode=70;else supervise(process.argv[2]).then(code=>process.exitCode=code).catch(()=>process.exitCode=70);}
