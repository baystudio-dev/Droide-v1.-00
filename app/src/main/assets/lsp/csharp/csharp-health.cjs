'use strict';
// Zero-workspace original stdio health. No document, project, build, restore, compiler or synthetic solution.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const I=require('./csharp-identity.cjs');const {check}=I;
class HealthPeer {
 constructor(input,output,failure){this.output=output;this.failure=failure;this.buffer=Buffer.alloc(0);this.length=null;this.next=0;this.pending=new Map();this.callbacks=0;this.queued=0;this.configurationReplies=0;this.configurationWaiter=null;
 input.on('data',b=>{try{check(this.buffer.length+b.length<=1048576+8192);this.buffer=Buffer.concat([this.buffer,b]);while(true){if(this.length===null){const at=this.buffer.indexOf('\r\n\r\n');if(at<0){check(this.buffer.length<=8192);break;}
 const headers=this.buffer.subarray(0,at).toString('ascii').split('\r\n');let length=null;for(const line of headers){const m=/^([A-Za-z-]+):[ \t]*(.*)$/.exec(line);check(m);if(m[1].toLowerCase()==='content-length'){check(length===null&&/^[1-9][0-9]{0,6}$/.test(m[2]));length=Number(m[2]);}else check(m[1].toLowerCase()==='content-type');}check(length>0&&length<=1048576);this.length=length;this.buffer=this.buffer.subarray(at+4);}
 if(this.buffer.length<this.length)break;const m=I.parseData(this.buffer.subarray(0,this.length),false,1048576);this.buffer=this.buffer.subarray(this.length);this.length=null;this.receive(m);}}catch(e){this.fail(e);}});
 input.on('error',e=>this.fail(e));output.on('error',e=>this.fail(e));input.on('end',()=>{if(this.pending.size||this.buffer.length||this.length!==null)this.fail(new Error('Premature health EOF'));});
 }
 send(value,written=()=>{}){const body=Buffer.from(JSON.stringify(value));check(body.length<=1048576);const frame=Buffer.concat([Buffer.from('Content-Length: '+body.length+'\r\n\r\n'),body]);check(this.queued+frame.length<=1048576);this.queued+=frame.length;this.output.write(frame,e=>{this.queued-=frame.length;if(e)this.fail(e);else written();});}
 receive(m){check(m?.jsonrpc==='2.0');if(typeof m.method==='string'){
  if(Object.hasOwn(m,'id')){check(++this.callbacks<=32);if(m.method==='workspace/configuration'){
    check(Array.isArray(m.params?.items)&&m.params.items.length===1);
    const result=m.params.items.map(item=>{I.exactKeys(item,['section']);check(item.section==='csharp');return {solutionPathOverride:null,applyFormattingOptions:false,analyzersEnabled:false,useMetadataUris:false,razorSupport:false};});
    this.send({jsonrpc:'2.0',id:m.id,result},()=>{this.configurationReplies++;if(this.configurationWaiter){const w=this.configurationWaiter;this.configurationWaiter=null;clearTimeout(w.timer);w.resolve();}});
   }else if(m.method==='workspace/workspaceFolders')this.send({jsonrpc:'2.0',id:m.id,result:[]});
   else if(m.method==='workspace/diagnostic/refresh')this.send({jsonrpc:'2.0',id:m.id,result:null});
   else this.send({jsonrpc:'2.0',id:m.id,error:{code:-32601,message:'Method not found'}});
  }else check(['window/logMessage','window/showMessage','$/logTrace','$/progress'].includes(m.method),'Unexpected health notification');return;
 }
 check(this.pending.has(m.id)&&Object.hasOwn(m,'result')!==Object.hasOwn(m,'error'));const item=this.pending.get(m.id);this.pending.delete(m.id);clearTimeout(item.timer);if(m.error)item.reject(new Error('Original health request rejected'));else item.resolve(m.result);
 }
 request(method,params,deadline){check(this.pending.size<4);const id=++this.next;return new Promise((resolve,reject)=>{const ms=deadline-Date.now();if(ms<=0)return reject(new Error('Health deadline'));const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error('Health deadline'));},ms);this.pending.set(id,{resolve,reject,timer});this.send({jsonrpc:'2.0',id,method,params});});}
 notify(method,params){this.send({jsonrpc:'2.0',method,params});}
 waitForConfiguration(deadline){if(this.configurationReplies>0)return Promise.resolve();check(!this.configurationWaiter);return new Promise((resolve,reject)=>{
  const ms=deadline-Date.now();if(ms<=0)return reject(new Error('C# configuration exchange deadline'));
  const timer=setTimeout(()=>{this.configurationWaiter=null;reject(new Error('C# configuration exchange deadline'));},ms);this.configurationWaiter={resolve,reject,timer};});}
 fail(e){if(this.configurationWaiter){const w=this.configurationWaiter;this.configurationWaiter=null;clearTimeout(w.timer);w.reject(e);}for(const item of this.pending.values()){clearTimeout(item.timer);item.reject(e);}this.pending.clear();this.failure(e);}
}
// The exact trusted supervisor's retained leader birth must accompany the generation-bound retirement receipt.
function completeReceipt(root,owner,receipt){
 const born=I.parseData(I.regular(root+'/server.json',65536));I.exactKeys(born,['schema','token','ownerGeneration','pid','start','group','supervisorPid','supervisorStart']);
 check(born.schema===1&&born.token===owner.token&&born.ownerGeneration===owner.ownerGeneration&&Number.isSafeInteger(born.pid)&&born.pid>1&&born.group===born.pid&&
  Number.isSafeInteger(born.supervisorPid)&&born.supervisorPid>1&&typeof born.start==='string'&&/^[0-9]+$/.test(born.start)&&typeof born.supervisorStart==='string'&&/^[0-9]+$/.test(born.supervisorStart));
 return receipt.schema===1&&receipt.token===owner.token&&receipt.ownerGeneration===owner.ownerGeneration&&receipt.status==='COMPLETE'&&receipt.directChildReaped===true&&receipt.groupEmpty===true&&
  receipt.leaderPid===born.pid&&receipt.leaderStart===born.start&&receipt.supervisorPid===born.supervisorPid&&receipt.supervisorStart===born.supervisorStart;
}
function removePrivate(root,identity){check(I.privateRoot(root)===identity);let count=0,bytes=0;function remove(file){check(++count<=16384);const s=fs.lstatSync(file);check(s.isDirectory()||s.isFile()||s.isSocket());check((bytes+=s.size)<=536870912);if(s.isDirectory()){for(const name of fs.readdirSync(file))remove(path.join(file,name));fs.rmdirSync(file);}else fs.unlinkSync(file);}remove(root);}
async function health(packageRoot,sdkRoot,root){const privateIdentity=I.privateRoot(root);check(root.startsWith('/run/droide-csharp/droide-csharp-health-'));
 check(I.privateRoot('/tmp')===I.privateRoot(root+'/tmp'));I.resourceReadback(true);
 let child=null,closed=false,resolveClosed,owner=null,success=false,failure=null,stderr=0;const closedDone=new Promise(r=>resolveClosed=r),start=Date.now(),deadline=start+78000;
 try{I.canonical(packageRoot,true);check(sdkRoot===I.SDK);const seed=I.parseData(I.regular(packageRoot+'/csharp-health-identity.json',65536));check(seed.packageRoot===packageRoot&&seed.sdkRoot===sdkRoot);
  const workspace=root+'/workspace';I.privateRoot(workspace);check(fs.readdirSync(workspace).length===0);I.noGlobalAncestors(workspace);
  owner={...seed,schema:1,mode:'health',policy:I.POLICY,privateRoot:root,privateIdentity,rootPath:workspace,solutionPath:null,
   token:crypto.randomBytes(16).toString('hex'),ownerGeneration:1,consentIdentity:I.sha(Buffer.from('csharp-zero-workspace-health-v1'))};
  I.atomic(root,'owner.json',owner);I.loadOwner(root);
  child=cp.spawn(owner.helperExecutable,['--max-old-space-size=64',packageRoot+'/payload/csharp-supervisor.cjs',root],{cwd:workspace,env:process.env,stdio:['pipe','pipe','pipe']});
  child.on('error',e=>failure=e);child.once('close',()=>{closed=true;resolveClosed();});child.stderr.on('data',b=>{stderr+=b.length;if(stderr>1048576)failure=new Error('Health stderr bound');});
  const peer=new HealthPeer(child.stdout,child.stdin,e=>failure=e);
  const initialized=await peer.request('initialize',{processId:process.pid,rootUri:null,rootPath:null,workspaceFolders:[],clientInfo:{name:'Droide isolated C# health',version:'1'},
   capabilities:{workspace:{configuration:true,workspaceFolders:true,applyEdit:false},textDocument:{diagnostic:{dynamicRegistration:false,relatedDocumentSupport:false}},general:{positionEncodings:['utf-16']}}},Math.min(deadline,Date.now()+60000));
  check(initialized?.serverInfo?.name==='csharp-ls','Unexpected original server identity');
  const caps=initialized.capabilities;check(caps&&typeof caps==='object'&&!Array.isArray(caps));
  // Static capability subset emitted by the pinned handlers for this non-dynamic client.
  check(caps.textDocumentSync?.openClose===true&&caps.textDocumentSync?.change===2&&caps.textDocumentSync?.save?.includeText===true&&
   caps.completionProvider?.resolveProvider===true&&caps.hoverProvider===true&&caps.definitionProvider===true&&caps.documentSymbolProvider===true,'Original capability subset mismatch');
  check(caps.positionEncoding===undefined||caps.positionEncoding==='utf-16');
  peer.notify('initialized',{});await peer.waitForConfiguration(Math.min(deadline,Date.now()+2000));const shutdown=await peer.request('shutdown',null,Math.min(deadline,Date.now()+2000));check(shutdown===null);peer.notify('exit',null);
  await Promise.race([closedDone,new Promise(r=>setTimeout(r,Math.max(0,Math.min(10000,deadline-Date.now()))))]);
  const receipt=I.parseData(I.regular(root+'/teardown.json',65536));
  check(closed&&child.exitCode===0&&completeReceipt(root,owner,receipt)&&receipt.nativeExitCode===0);
  success=!failure;
 }catch(e){failure=e;}finally{
  if(child&&!closed){try{I.atomic(root,'stop.json',{stop:true});}catch(_){}await Promise.race([closedDone,new Promise(r=>setTimeout(r,Math.max(0,Math.min(10000,start+89000-Date.now()))))]);}
  let cleanup=child===null;try{const r=I.parseData(I.regular(root+'/teardown.json',65536));cleanup=closed&&owner!==null&&completeReceipt(root,owner,r);}catch(_){}
  if(child){child.stdin.destroy();child.stdout.destroy();child.stderr.destroy();if(!closed)child.unref();}
  if(cleanup){try{removePrivate(root,privateIdentity);if(success&&!failure)process.stdout.write('DROIDE_CSHARP_HEALTH_OK\n');process.stdout.write('DROIDE_CSHARP_CLEANUP_OK\n');}catch(e){success=false;failure=e;}}
  else{success=false;failure=new Error('C# health cleanup UNKNOWN; exact private owner retained');}
 }
 if(!success||failure){process.stderr.write('C# isolated health failed: '+String(failure?.message||'unknown').slice(0,256)+'\n');return 70;}return 0;
}
module.exports={HealthPeer,completeReceipt,removePrivate,health};
if(require.main===module){if(process.argv.length!==6||process.argv[2]!=='--health')process.exitCode=70;else health(process.argv[3],process.argv[4],process.argv[5]).then(code=>process.exitCode=code).catch(()=>process.exitCode=70);}
