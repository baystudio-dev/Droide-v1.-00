'use strict';
// Private zero-document framed transport and teardown only. Never semantic readiness.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process');
const I=require('./nim-helper-identity-health.cjs');
const {check,sha,regular,parseData,privateRoot}=I;
const CONFIG=Object.freeze({nimsuggestPath:'',maxNimsuggestProcesses:1,useNimCheck:false,useNimTrack:false,nimExpandMacro:false,nimExpandArc:false,
  formatOnSave:false,logNimsuggest:false,projectMapping:[],workingDirectoryMapping:[],autoRestart:false,
  inlayHints:{typeHints:{enable:false},parameterHints:{enable:false},exceptionHints:{enable:false}}});
class Frames {
  constructor(stream){this.pending=Buffer.alloc(0);this.queue=[];this.bytes=0;this.frames=0;this.eof=false;this.error=null;
    stream.on('data',b=>{try{this.bytes+=b.length;check(this.bytes<=4194304);this.pending=Buffer.concat([this.pending,b]);this.parse();}catch(e){this.error=e;}});
    stream.on('end',()=>{this.eof=true;});stream.on('error',e=>{this.error=e;});}
  parse(){for(;;){const marker=this.pending.indexOf('\r\n\r\n');if(marker<0){check(this.pending.length<=8192);return;}check(marker<=8192);
    const header=this.pending.subarray(0,marker).toString('latin1');check(/^Content-Length: [1-9][0-9]*$/.test(header));const length=Number(header.slice(16));check(Number.isSafeInteger(length)&&length<=1048576);
    if(this.pending.length<marker+4+length)return;const raw=this.pending.subarray(marker+4,marker+4+length);this.pending=this.pending.subarray(marker+4+length);
    check(++this.frames<=128);const value=parseData(new TextDecoder('utf-8',{fatal:true}).decode(raw));check(value&&typeof value==='object'&&!Array.isArray(value)&&value.jsonrpc==='2.0');this.queue.push(value);
  }}
  async next(deadline){for(;;){if(this.error)throw this.error;if(this.queue.length)return this.queue.shift();check(!this.eof&&Date.now()<deadline,'Health frame deadline');await new Promise(r=>setTimeout(r,5));}}
}
function send(stream,value){const body=Buffer.from(JSON.stringify(value));check(body.length<=1048576);stream.write(Buffer.concat([Buffer.from(`Content-Length: ${body.length}\r\n\r\n`),body]));}
function clean(root){let count=0,bytes=0;function visit(p){check(++count<=4096);const st=fs.lstatSync(p);check(st.isDirectory()||st.isFile());bytes+=st.size;check(bytes<=268435456);if(st.isDirectory()){for(const name of fs.readdirSync(p))visit(path.join(p,name));fs.rmdirSync(p);}else fs.unlinkSync(p);}visit(root);}
async function run(packageRoot,serverExecutable){
  const seed=parseData(regular(path.join(packageRoot,'payload/nim-health-identity.json')));check(seed.serverExecutable===serverExecutable);I.assertRuntime(seed);
  check(Array.isArray(seed.proofs)&&seed.proofs.length>=4&&seed.proofs.length<=24);for(const proof of seed.proofs)check(sha(regular(proof.path,134217728))===proof.sha256);
  const root=fs.mkdtempSync('/tmp/droide-nim-health-');fs.chmodSync(root,0o700);const privateIdentity=privateRoot(root),token=require('node:crypto').randomBytes(16).toString('hex');
  let child=null,exited=false,exitCode=null,exitSignal=null,cleanupAllowed=false;
  try {
    for(const name of ['cwd','home','tmp','config','cache','data','state','run','commands'])fs.mkdirSync(path.join(root,name),{mode:0o700});
    const commandSha256={};for(const name of ['nim','nimsuggest','nimble']){const script=`#!/bin/sh\nexec '${seed.helperExecutable}' '${packageRoot}/payload/nim-helper-identity-health.cjs' --refuse '${root}'\n`;fs.writeFileSync(path.join(root,'commands',name),script,{mode:0o700,flag:'wx'});commandSha256[name]=sha(Buffer.from(script));}
    const owner={...seed,schema:1,mode:'health',policy:'nimlangserver-1.16.0-nim-2.2.12-fixed-analysis-v1',privateRoot:root,privateIdentity,token,ownerGeneration:0,consentIdentity:'0'.repeat(64),rootPath:path.join(root,'cwd'),commandSha256};
    fs.writeFileSync(path.join(root,'owner.json'),JSON.stringify(owner),{mode:0o600,flag:'wx'});
    const env={HOME:root+'/home',TMPDIR:root+'/tmp',XDG_CONFIG_HOME:root+'/config',XDG_CACHE_HOME:root+'/cache',XDG_DATA_HOME:root+'/data',XDG_STATE_HOME:root+'/state',XDG_RUNTIME_DIR:root+'/run',PATH:root+'/commands',LANG:'C.UTF-8',LC_ALL:'C.UTF-8',TERM:'dumb',DROIDE_NIM_OWNER:root};
    const start=Date.now(),end=start+30000;let stderr=0;
    child=cp.spawn(seed.helperExecutable,[path.join(packageRoot,'payload/nim-supervisor.cjs'),root],{cwd:owner.rootPath,env,stdio:['pipe','pipe','pipe']});
    const frames=new Frames(child.stdout);child.stderr.on('data',b=>{stderr+=b.length;if(stderr>262144)frames.error=new Error('Health stderr bound');});
    child.on('error',e=>{frames.error=e;});child.on('close',(code,signal)=>{exited=true;exitCode=code;exitSignal=signal;});child.stdin.on('error',e=>{if(!exited)frames.error=e;});
    const deadline=ms=>Math.min(end,Date.now()+ms);let configuration=false;
    async function reply(id,limit){for(;;){const v=await frames.next(limit);
      if(v.method==='workspace/configuration'&&Object.hasOwn(v,'id')){check(id!==1&&v.params&&Array.isArray(v.params.items)&&v.params.items.length>=1&&v.params.items.length<=64);const values=v.params.items.map(item=>{check(item&&typeof item==='object'&&!Array.isArray(item));check(Object.keys(item).every(k=>['section','scopeUri'].includes(k)));check(item.scopeUri===undefined||item.scopeUri===null||item.scopeUri===`file://${owner.rootPath}`);return item.section==='nim'?CONFIG:null;});send(child.stdin,{jsonrpc:'2.0',id:v.id,result:values});configuration=true;continue;}
      if(v.id===id){check(!Object.hasOwn(v,'error')&&Object.hasOwn(v,'result'));return v.result;}
      check(!Object.hasOwn(v,'id')&&['window/logMessage','window/showMessage','nim/statusUpdate'].includes(v.method),'Unexpected health traffic');
    }}
    send(child.stdin,{jsonrpc:'2.0',id:1,method:'initialize',params:{processId:null,rootUri:`file://${owner.rootPath}`,workspaceFolders:[],capabilities:{general:{positionEncodings:['utf-16']},workspace:{configuration:true,didChangeConfiguration:{dynamicRegistration:false},workspaceFolders:false},textDocument:{synchronization:{dynamicRegistration:false,didSave:false},rename:{prepareSupport:false}}}}});
    const result=await reply(1,deadline(10000));check(result&&typeof result==='object'&&result.capabilities&&typeof result.capabilities==='object');const sync=result.capabilities.textDocumentSync;check(sync===1||sync&&sync.openClose===true&&sync.change===1);check(result.capabilities.positionEncoding===undefined||result.capabilities.positionEncoding==='utf-16');
    send(child.stdin,{jsonrpc:'2.0',method:'initialized',params:{}});
    const configEnd=deadline(5000);while(!configuration){const v=await frames.next(configEnd);check(v.method==='workspace/configuration'&&Object.hasOwn(v,'id')&&v.params&&Array.isArray(v.params.items)&&v.params.items.length>=1&&v.params.items.length<=64);
      const values=v.params.items.map(item=>{check(item&&Object.keys(item).every(k=>['section','scopeUri'].includes(k))&&(item.scopeUri===undefined||item.scopeUri===null||item.scopeUri===`file://${owner.rootPath}`));return item.section==='nim'?CONFIG:null;});send(child.stdin,{jsonrpc:'2.0',id:v.id,result:values});configuration=true;}
    check(!fs.existsSync(root+'/guard.json')&&!fs.existsSync(root+'/invocation.json')&&!fs.existsSync(root+'/metadata.json'));
    send(child.stdin,{jsonrpc:'2.0',id:2,method:'shutdown',params:null});check(await reply(2,deadline(5000))===null);
    send(child.stdin,{jsonrpc:'2.0',method:'exit',params:null});child.stdin.end();const exitEnd=deadline(5000);
    while(!exited){check(Date.now()<exitEnd);if(frames.error)throw frames.error;await new Promise(r=>setTimeout(r,10));}
    check(exitCode===0&&exitSignal===null&&frames.eof&&frames.pending.length===0&&frames.queue.length===0&&configuration);
    check(!fs.existsSync(root+'/guard.json')&&!fs.existsSync(root+'/invocation.json')&&!fs.existsSync(root+'/failure.json'));
    const receipt=parseData(regular(root+'/teardown.json'));check(receipt.token===token&&receipt.status==='COMPLETE'&&receipt.directChildReaped===true&&receipt.groupEmpty===true&&receipt.forcedTermination===false&&receipt.exitCode===0&&receipt.exitSignal===null);
    I.assertRuntime(seed);for(const proof of seed.proofs)check(sha(regular(proof.path,134217728))===proof.sha256);cleanupAllowed=true;check(privateRoot(root)===privateIdentity);clean(root);return true;
  }catch(e){if(child&&!exited){try{I.atomic(root,'stop.json',{stop:true});}catch(_){}const end=Date.now()+10000;while(!exited&&Date.now()<end)await new Promise(r=>setTimeout(r,20));if(!exited)child.kill('SIGTERM');}
    try{const receipt=parseData(regular(root+'/teardown.json'));cleanupAllowed=exited&&receipt.token===token&&receipt.status==='COMPLETE'&&receipt.directChildReaped&&receipt.groupEmpty;}catch(_){}
    if(cleanupAllowed&&fs.existsSync(root)&&privateRoot(root)===privateIdentity)clean(root);throw e;
  }
}
module.exports={Frames,send,CONFIG,run};
if(require.main===module){(async()=>{check(process.argv.length===5&&process.argv[2]==='--health');await run(process.argv[3],process.argv[4]);process.stdout.write('DROIDE_NIM_HEALTH_OK\n');})().catch(()=>{process.stderr.write('Nim isolated zero-document health failed\n');process.exitCode=70;});}
