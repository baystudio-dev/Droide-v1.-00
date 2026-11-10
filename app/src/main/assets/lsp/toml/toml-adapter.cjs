'use strict';
// First-party transport only. All language responses come from original TaploLsp.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const {fileURLToPath, pathToFileURL} = require('node:url');
const {TextDecoder} = require('node:util');
const LIMIT = Object.freeze({header:8192, frame:8388608, envelope:8392704, bytes:16777216, queue:128, depth:64, nodes:262144});
const own = (o,k) => Object.prototype.hasOwnProperty.call(o,k);
function check(v, reason='policy_mismatch') { if(!v) throw new Error(reason); }
function exact(o, keys) { check(o && !Array.isArray(o) && typeof o==='object' && Object.keys(o).sort().join('\0')===keys.slice().sort().join('\0')); }
function data(value) {
 let nodes=0;
 function walk(v,d) { check(d<=LIMIT.depth && ++nodes<=LIMIT.nodes,'json_bound');
  if(v===null || typeof v==='boolean' || typeof v==='string') return;
  if(typeof v==='number') {check(Number.isFinite(v));return;}
  check(v && typeof v==='object' && (Object.getPrototypeOf(v)===Object.prototype || Object.getPrototypeOf(v)===null || Array.isArray(v)),'json_object');
  check(Object.getOwnPropertySymbols(v).length===0); const desc=Object.getOwnPropertyDescriptors(v);
  for(const k of Object.keys(desc)) { if(Array.isArray(v)&&k==='length')continue;check(own(desc[k],'value')&&desc[k].enumerable,'json_accessor');walk(desc[k].value,d+1); }
  if(Array.isArray(v))check(Object.keys(v).length===v.length,'json_sparse');
 }
 walk(value,0);return value;
}
function parse(bytes, cap=LIMIT.frame) {
 check(bytes.length>0&&bytes.length<=cap,'json_size');const text=new TextDecoder('utf-8',{fatal:true}).decode(bytes);let i=0,nodes=0;
 function ws(){while(i<text.length&&' \t\r\n'.includes(text[i]))i++;}
 function str(){check(text[i]==='"');const start=i++;let escape=false;while(i<text.length){const c=text[i++];if(c==='"'&&!escape)return JSON.parse(text.slice(start,i));escape=c==='\\'?!escape:false;}throw new Error('json_string');}
 function val(d){check(d<=LIMIT.depth&&++nodes<=LIMIT.nodes,'json_bound');ws();check(i<text.length);const c=text[i];
  if(c==='{'){i++;ws();const keys=new Set();if(text[i]==='}'){i++;return;}for(;;){ws();const k=str();check(!keys.has(k),'json_duplicate');keys.add(k);ws();check(text[i++]===':');val(d+1);ws();const x=text[i++];if(x==='}')return;check(x===',');}}
  if(c==='['){i++;ws();if(text[i]===']'){i++;return;}for(;;){val(d+1);ws();const x=text[i++];if(x===']')return;check(x===',');}}
  if(c==='"'){str();return;}const start=i;while(i<text.length&&!",]} \t\r\n".includes(text[i]))i++;check(i>start);JSON.parse(text.slice(start,i));
 }
 val(0);ws();check(i===text.length);return JSON.parse(text);
}
function idKey(id){check(typeof id==='string'||(Number.isInteger(id)&&id>=-2147483648&&id<=2147483647),'rpc_id');return typeof id+':'+id;}
function rpc(m){data(m);check(m&&!Array.isArray(m)&&m.jsonrpc==='2.0','rpc_object');if(own(m,'id'))idKey(m.id);
 if(own(m,'method'))check(typeof m.method==='string'&&m.method.length>0&&!own(m,'result')&&!own(m,'error'),'rpc_method');
 else check(own(m,'id')&&(own(m,'result')!==own(m,'error')),'rpc_response');return m;}
class Frames {
 constructor(receive,fail,cap=LIMIT.frame){this.receive=receive;this.fail=fail;this.cap=cap;this.buffer=Buffer.alloc(0);this.length=null;this.failed=false;}
 push(bytes){if(this.failed)return;try{check(Buffer.isBuffer(bytes)||bytes instanceof Uint8Array);check(this.buffer.length+bytes.length<=LIMIT.bytes,'input_cap');this.buffer=Buffer.concat([this.buffer,bytes]);let count=0;
  for(;;){if(this.length===null){const at=this.buffer.indexOf('\r\n\r\n');if(at<0){check(this.buffer.length<=LIMIT.header,'header_cap');break;}check(at<=LIMIT.header);let length=null,ct=false;
    check(this.buffer.subarray(0,at).every(b=>b<128),'header_encoding');for(const line of this.buffer.subarray(0,at).toString('ascii').split('\r\n')){check(/^[\x20-\x7e\t]+$/.test(line));const m=/^([A-Za-z-]+):[ \t]*(.*)$/.exec(line);check(m,'header');if(m[1].toLowerCase()==='content-length'){check(length===null&&/^[1-9][0-9]{0,7}$/.test(m[2]),'length');length=Number(m[2]);}else{check(m[1].toLowerCase()==='content-type'&&!ct&&/^application\/(?:vscode-jsonrpc|json)(?:;\s*charset=utf-8)?$/i.test(m[2]),'content_type');ct=true;}}
    check(length>0&&length<=this.cap,'frame_cap');this.length=length;this.buffer=this.buffer.subarray(at+4);}
   if(this.buffer.length<this.length)break;check(++count<=LIMIT.queue,'input_frame_cap');const bytes=this.buffer.subarray(0,this.length);this.buffer=this.buffer.subarray(this.length);this.length=null;this.receive(parse(bytes,this.cap));}
 }catch(e){this.failed=true;this.fail(e);}}
 end(){if(this.length!==null||this.buffer.length)this.fail(new Error('truncated_frame'));}
}
function canonicalJSON(v){return JSON.stringify(v, function(k,x){if(x&&typeof x==='object'&&!Array.isArray(x)){const o={};for(const key of Object.keys(x).sort())o[key]=x[key];return o;}return x;});}
function settings(){return {taplo:{configFile:{enabled:true,path:null}},schema:{enabled:true,associations:{},catalogs:[],links:false},completion:{maxKeys:5},syntax:{semanticTokens:true},formatter:{},rules:[]};}
function canonicalPath(p,root){check(typeof p==='string'&&p.length<=4096&&p.startsWith('/')&&!/[\x00-\x1f\x7f\\]/.test(p),'invalid_path');check(path.posix.normalize(p)===p&&!p.endsWith('/'),'invalid_path');check(p===root||p.startsWith(root+'/'),'outside_root');return p;}
function snapshot(d){exact(d,['generation','rootUri','selectedConfigPath','entries']);check(/^[0-9a-f]{64}$/.test(d.generation));check(typeof d.rootUri==='string'&&d.rootUri.endsWith('/'));
 const root=fileURLToPath(d.rootUri).replace(/\/$/,'');check(pathToFileURL(root+'/').href===d.rootUri&&root.startsWith('/')&&root!=='');check(Array.isArray(d.entries)&&d.entries.length<=64);const map=new Map();let total=0;
 for(const e of d.entries){exact(e,['path','bytes','sha256','origin','optionalConfig']);const p=canonicalPath(e.path,root);check(!map.has(p));check(typeof e.origin==='string'&&e.origin.length<=8192&&typeof e.optionalConfig==='boolean');const optional=[root+'/.taplo.toml',root+'/taplo.toml'].includes(p);check(!e.optionalConfig||optional);
  let b=null;if(e.bytes!==null){check(typeof e.bytes==='string'&&/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(e.bytes));b=Buffer.from(e.bytes,'base64');check(b.length<=1048576&&(total+=b.length)<=8388608);check(crypto.createHash('sha256').update(b).digest('hex')===e.sha256);}else check(e.sha256===null);
  map.set(p,Object.freeze({bytes:b,optionalConfig:e.optionalConfig}));}
 check(d.selectedConfigPath===null||([root+'/.taplo.toml',root+'/taplo.toml'].includes(d.selectedConfigPath)&&map.get(d.selectedConfigPath)?.bytes));return {root,map,generation:d.generation,rootUri:d.rootUri,selected:d.selectedConfigPath};
}
function createAdapter({descriptor,privateRoot,output,log=()=>{},load,retire=()=>{},flow=()=>{}}){
 const snap=snapshot(descriptor);check(typeof privateRoot==='string'&&privateRoot.startsWith('/'));let phase='POLICY_READY',poisoned=false,disposed=false,api=null,initialized=false,initializeId=null,configId=null,configAccepted=false,barrierId=null,shutdownId=null,shutdownOk=false,offline=false,docs=0,sequence=0,writing=false,outBytes=0,inputBytes=0,retiring=false;
 let queue=[],inputQueue=[],flowPaused=false;const nativeRequests=new Set(),clientRequests=new Map();const ownerToken=arguments[0].ownerToken;check(/^[0-9a-f]{32}$/.test(ownerToken));
 function updateFlow(){const high=queue.length>=96||outBytes>=12582912||inputQueue.length>=96||inputBytes>=12582912;const low=queue.length<=32&&outBytes<=4194304&&inputQueue.length<=32&&inputBytes<=4194304;if(!flowPaused&&high){flowPaused=true;flow(true);}else if(flowPaused&&low&&!poisoned){flowPaused=false;flow(false);}}
 function scheduleRetire(){if(!retiring){retiring=true;setImmediate(()=>{try{retire();}catch{}});}}
 function event(kind,extra={}){enqueue('control',{kind,...extra});}
 function poison(kind='policy_failure',extra={reason:'adapter_failure'}){if(poisoned)return;poisoned=true;phase='POISONED';inputQueue=[];inputBytes=0;queue=queue.filter(v=>v.kind==='control');outBytes=queue.reduce((n,v)=>n+v.body.length,0);try{event(kind,extra);}catch{}scheduleRetire();}
 function pump(){if(writing||queue.length===0)return;try{check(sequence<2147483647,'sequence_exhausted');const q=queue.shift();outBytes-=q.body.length;updateFlow();const envelope={schema:1,kind:q.kind,ownerToken,generation:snap.generation,sequence:++sequence,[q.kind==='upstream'?'message':'event']:JSON.parse(q.body.toString('utf8'))};const body=Buffer.from(JSON.stringify(envelope));check(body.length<=LIMIT.envelope);writing=true;output.write(Buffer.concat([Buffer.from('Content-Length: '+body.length+'\r\n\r\n'),body]),e=>{writing=false;if(e)poison('policy_failure',{reason:'output_failure'});else pump();});}catch{writing=false;poison('policy_failure',{reason:'output_failure'});}}
 function enqueue(kind,value){if(kind==='upstream'&&poisoned)return;data(value);const body=Buffer.from(JSON.stringify(value));check(body.length<=(kind==='control'?8192:LIMIT.frame));check(queue.length<(kind==='control'?LIMIT.queue:LIMIT.queue-1)&&outBytes+body.length<=(kind==='control'?LIMIT.bytes:LIMIT.bytes-8192),'output_cap');queue.push({kind,body});outBytes+=body.length;updateFlow();pump();}
 function onMessage(message){try{if(poisoned)return;rpc(message);if(own(message,'method')&&own(message,'id')){const key=idKey(message.id);check(nativeRequests.size<LIMIT.queue&&!nativeRequests.has(key),'native_request_id');nativeRequests.add(key);}else if(!own(message,'method')){const key=idKey(message.id);check(clientRequests.has(key),'unknown_response_id');clientRequests.delete(key);}
   if(message.method==='workspace/configuration'){check(configId===null&&initialized,'configuration_phase');const items=message.params?.items;check(Array.isArray(items)&&items.length===2);items.forEach((item,i)=>{check(item&&item.section==='evenBetterToml'&&(i===0?(item.scopeUri===null||!own(item,'scopeUri')):item.scopeUri===snap.rootUri.replace(/\/$/,'' )||item.scopeUri===snap.rootUri));check(Object.keys(item).every(k=>['scopeUri','section'].includes(k)));});configId=message.id;idKey(configId);}
   if(!own(message,'method')&&initializeId!==null&&idKey(message.id)===idKey(initializeId)){check(own(message,'result')&&!own(message,'error'),'initialize_failure');initialized=true;phase='CONFIGURING';}
   if(!own(message,'method')&&barrierId!==null&&idKey(message.id)===idKey(barrierId)){check(own(message,'result')&&!own(message,'error'),'barrier_failure');phase='ACTIVE';}
   if(!own(message,'method')&&shutdownId!==null&&idKey(message.id)===idKey(shutdownId)){check(message.result===null&&!own(message,'error'),'shutdown_failure');shutdownOk=true;}
   enqueue('upstream',message);
  }catch{poison('policy_failure',{reason:'upstream_message'});} // MUST return normally across the WASM callback boundary.
 }
 function failPath(reason,p){const safe=typeof p==='string'&&p.length<=4096&&!/[\x00-\x1f\x7f]/.test(p)?p:undefined;poison(reason==='unknown_snapshot_path'?'read_miss':'policy_failure',{reason,...(safe?{path:safe}:{})});}
 const environment=Object.freeze({now:()=>new Date(),envVar:()=>undefined,envVars:()=>[],stdErrAtty:()=>false,stdin:async()=>new Uint8Array(),
  stdout:()=>{poison('policy_failure',{reason:'direct_stdout'});return Promise.reject(new Error('Unsupported host output'));},
  stderr:async bytes=>{if(!(bytes instanceof Uint8Array)||bytes.length>65536){poison('policy_failure',{reason:'stderr_bound'});return 0;}try{log(bytes);}catch{}return bytes.length;},
  glob:()=>{poison('policy_failure',{reason:'unsupported_glob'});return [];},
  readFile:p=>{try{canonicalPath(p,snap.root);const e=snap.map.get(p);if(!e){failPath('unknown_snapshot_path',p);return Promise.reject(new Error('Snapshot dependency unavailable'));}if(e.bytes===null){if(!e.optionalConfig)failPath('known_negative_schema',p);return Promise.reject(new Error('Snapshot file absent'));}if(poisoned)return Promise.reject(new Error('Owner retired'));return Promise.resolve(new Uint8Array(e.bytes));}catch{failPath('invalid_host_path');return Promise.reject(new Error('Host path unavailable'));}},
  writeFile:()=>{poison('policy_failure',{reason:'write_denied'});return Promise.reject(new Error('Read-only host'));},
  urlToFilePath:u=>{try{if(u==='root:///')return undefined;check(typeof u==='string'&&u.length<=8192);const url=new URL(u);check(url.protocol==='file:'&&!url.host&&!url.search&&!url.hash);const p=fileURLToPath(url);canonicalPath(p,snap.root);check(pathToFileURL(p).href===url.href,'noncanonical_uri');return p;}catch{failPath('invalid_host_uri');return undefined;}},
  isAbsolute:p=>{if(typeof p!=='string'||p.length>4096||/[\x00-\x1f\x7f\\]/.test(p)){failPath('invalid_host_path');return false;}return p.startsWith('/');},cwd:()=>privateRoot,
  findConfigFile:from=>{try{check(from===snap.root||from===snap.root+'/','config_root');return snap.selected===null?undefined:snap.selected;}catch{failPath('invalid_config_root');return undefined;}}
 });
 // Capture only native brand-checking Request getters, never the original callable fetch.
 const requestURL=globalThis.Request&&Object.getOwnPropertyDescriptor(globalThis.Request.prototype,'url')?.get;
 const requestMethod=globalThis.Request&&Object.getOwnPropertyDescriptor(globalThis.Request.prototype,'method')?.get;
 const rejectingFetch=(arg,init)=>{try{let url,method='GET';if(typeof arg==='string')url=arg;else{check(requestURL&&requestMethod);url=requestURL.call(arg);method=requestMethod.call(arg);}check(typeof url==='string'&&url.length<=8192);if(init!==undefined){data(init);check(init!==null&&!Array.isArray(init)&&Object.keys(init).length<=16);if(own(init,'method')){check(typeof init.method==='string'&&init.method.length<=16);method=init.method;}}check(typeof method==='string');
   if(url==='https://www.schemastore.org/api/json/catalog.json'&&method==='GET'&&phase==='INITIALIZE_IN_FLIGHT'&&!offline&&docs===0){offline=true;event('offline_default_catalog_disabled');}else poison('remote_schema_unavailable',{reason:'fetch_rejected',phase});
  }catch{poison('remote_schema_unavailable',{reason:'malformed_fetch',phase});}return Promise.reject(new Error('Remote fetch is disabled'));};
 Object.defineProperty(globalThis,'fetch',{value:rejectingFetch,writable:false,configurable:false,enumerable:true});check(globalThis.fetch===rejectingFetch);
 function forward(m){if(poisoned)return;rpc(m);
  if(m.method==='initialize'){check(initializeId===null&&own(m,'id'),'initialize_once');const p=m.params;check(p&&p.rootUri&&(p.rootUri===snap.rootUri||p.rootUri===snap.rootUri.replace(/\/$/,'')));check(Array.isArray(p.workspaceFolders)&&p.workspaceFolders.length===1&&(p.workspaceFolders[0].uri===p.rootUri));initializeId=m.id;phase='INITIALIZE_IN_FLIGHT';}
  else if(m.method==='initialized')check(initialized&&!configAccepted,'initialized_phase');
  else if(m.method==='exit'){check(shutdownOk&&!poisoned,'exit_without_shutdown');api.send(m);dispose();phase='RETIRED';scheduleRetire();return;}
  else if(m.method==='shutdown'){check(initialized&&shutdownId===null&&own(m,'id'));shutdownId=m.id;phase='SHUTTING_DOWN';}
  else if(m.method==='taplo/listSchemas'){check(configAccepted&&barrierId===null&&own(m,'id'),'barrier_phase');check(typeof m.params?.documentUri==='string');environment.urlToFilePath(m.params.documentUri);check(!poisoned);barrierId=m.id;}
  else if(own(m,'method')&&!['$/cancelRequest','$/setTrace'].includes(m.method)) {check(phase==='ACTIVE','before_barrier');if(m.method==='textDocument/didOpen')docs++;}
  if(!own(m,'method'))check(nativeRequests.has(idKey(m.id)),'unknown_native_request_id');
  const configResponse=!own(m,'method')&&configId!==null&&idKey(m.id)===idKey(configId);
  if(configResponse){check(!configAccepted&&own(m,'result')&&!own(m,'error')&&Array.isArray(m.result)&&m.result.length===2,'configuration_response');check(m.result.every(v=>canonicalJSON(v)===canonicalJSON(settings())),'configuration_settings');}
  if(own(m,'method')&&own(m,'id')){const key=idKey(m.id);check(clientRequests.size<LIMIT.queue&&!clientRequests.has(key),'client_request_id');clientRequests.set(key,m.method);}
  api.send(m);check(!poisoned,'send_poison');if(!own(m,'method'))nativeRequests.delete(idKey(m.id));if(configResponse){configAccepted=true;event('configuration_response_accepted',{requestId:configId});}
 }
 const frames=new Frames(m=>{const bytes=Buffer.byteLength(JSON.stringify(m));if(!api){check(inputQueue.length<LIMIT.queue&&(inputBytes+=bytes)<=LIMIT.bytes);inputQueue.push(m);updateFlow();}else forward(m);},()=>poison('policy_failure',{reason:'input_protocol'}));
 function dispose(){if(disposed)return;disposed=true;try{api?.dispose();}catch{poison('policy_failure',{reason:'dispose_failure'});}}
 const ready=Promise.resolve().then(()=>{check(!poisoned);return load();}).then(module=>{check(module?.TaploLsp&&typeof module.TaploLsp.initialize==='function');return module.TaploLsp.initialize(environment,{onMessage});}).then(value=>{check(value&&typeof value.send==='function'&&typeof value.dispose==='function');api=value;check(!poisoned);phase='API_READY';const q=inputQueue;inputQueue=[];inputBytes=0;updateFlow();for(const m of q)forward(m);}).catch(()=>{poison('policy_failure',{reason:'api_initialization'});});
 return {ready,environment,onMessage,push:b=>frames.push(b),end:()=>{frames.end();if(!shutdownOk)poison('policy_failure',{reason:'premature_eof'});},dispose,poison,get poisoned(){return poisoned;},get orderly(){return shutdownOk&&!poisoned&&disposed;},get drained(){return !writing&&queue.length===0;},get phase(){return phase;}};
}
async function main(ownerPath){
 const N=require('./toml-node-health.cjs');const owner=N.loadOwner(ownerPath);N.checkRuntime(owner.mode==='health');N.recordBirth(owner);
 let adapter,stopped=false,logged=0;const finish=()=>{if(stopped)return;stopped=true;process.stdin.pause();const limit=Date.now()+1000;const drain=()=>{if(adapter?.drained||Date.now()>=limit){adapter?.dispose();process.exit(adapter?.orderly?0:70);}else setTimeout(drain,10);};drain();};
 adapter=createAdapter({ownerToken:owner.token,descriptor:owner.snapshot,privateRoot:owner.privateRoot,output:process.stdout,flow:paused=>{if(paused)process.stdin.pause();else if(!stopped)process.stdin.resume();},log:b=>{logged+=b.length;if(logged<=1048576)process.stderr.write(b);else adapter.poison('policy_failure',{reason:'stderr_bound'});},load:()=>require(owner.packageRoot+'/payload/npm/node_modules/@taplo/lsp/dist/index.js'),retire:finish});
 process.stdin.on('data',b=>adapter.push(b));process.stdin.on('end',()=>adapter.end());process.stdin.on('error',()=>adapter.poison('policy_failure',{reason:'input_failure'}));process.stdout.on('error',()=>adapter.poison('policy_failure',{reason:'output_failure'}));
 process.on('uncaughtException',()=>adapter.poison('policy_failure',{reason:'uncaught_failure'}));process.on('unhandledRejection',()=>adapter.poison('policy_failure',{reason:'unhandled_failure'}));await adapter.ready;
}
module.exports={LIMIT,check,exact,data,parse,idKey,rpc,Frames,settings,snapshot,createAdapter,main};
if(require.main===module)main(process.argv[2]).catch(()=>{process.stderr.write('Taplo adapter unavailable\n');process.exitCode=70;});
