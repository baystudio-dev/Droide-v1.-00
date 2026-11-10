'use strict';
// App-authored data admission. This is not an original-artifact acceptance test.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const SOURCE_COMMIT = '80dc632040e6bada37aac1220dde9c79581c9c22';
const ARCHIVE_SHA256 = '5ced2d48d1d7198739b7f86804de0171aefb6823b684b12341d3321afc3cb0b2';
const NODE = '/opt/droide/packages/runtime.csharp-helper/22.23.3/arm64-v8a/payload/tree/node-v22.23.3-linux-arm64/bin/node';
function check(value, why = 'C# identity refused') { if (!value) throw new Error(why); }
function sha(bytes) { return crypto.createHash('sha256').update(bytes).digest('hex'); }
function regular(file, maximum = 16384) {
  const fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  try {
    const before = fs.fstatSync(fd, {bigint:true});
    check(before.isFile() && before.nlink === 1n && before.size >= 0n && before.size <= BigInt(maximum));
    const data = Buffer.alloc(Number(before.size)); let pos = 0;
    while (pos < data.length) { const n = fs.readSync(fd, data, pos, data.length-pos, null); check(n > 0); pos += n; }
    const end = fs.fstatSync(fd, {bigint:true});
    check(before.dev === end.dev && before.ino === end.ino && before.size === end.size && before.mtimeNs === end.mtimeNs && before.ctimeNs === end.ctimeNs);
    check(fs.lstatSync(file, {bigint:true}).ino === before.ino);
    return data;
  } finally { fs.closeSync(fd); }
}
function canonical(file, directory = false) {
  check(path.isAbsolute(file) && !file.includes('\0') && fs.realpathSync(file) === file);
  const st = fs.lstatSync(file, {bigint:true}); check(directory ? st.isDirectory() : st.isFile()); return st;
}
// Strict bounded JSON and Python-literal config.gypi reader. No eval, import or executable syntax.
function parseData(text, python = false, maximum = 1048576) {
  if(Buffer.isBuffer(text))text=new TextDecoder('utf-8',{fatal:true}).decode(text);
  check(typeof text === 'string' && Buffer.byteLength(text) <= maximum); let i = 0, members = 0;
  function ws() { while (i < text.length) { if ((python ? /\s/ : /[ \t\r\n]/).test(text[i])) {i++;continue;} if (python && text[i] === '#') { while (i < text.length && text[i] !== '\n') i++;continue; } break; } }
  function string() {
    const q = text[i++]; check(q === '"' || python && q === "'"); let out = '';
    while (i < text.length) { const c = text[i++]; if (c === q) return out; check(c.charCodeAt(0) >= 32);
      if (c !== '\\') {out += c;continue;} const e = text[i++];
      const simple = {'"':'"',"'":"'",'\\':'\\','/':'/','b':'\b','f':'\f','n':'\n','r':'\r','t':'\t'};
      if (Object.hasOwn(simple,e)) {check(e!=="'"||python);out += simple[e];} else {check(e === 'u' && /^[0-9a-fA-F]{4}$/.test(text.slice(i,i+4)));out += String.fromCharCode(parseInt(text.slice(i,i+4),16));i+=4;}
    } throw new Error('Unterminated data string');
  }
  function value(depth) {
    check(depth <= 32 && ++members <= 32768); ws(); const c=text[i];
    if(c==='"'||python&&c==="'") return string();
    if(c==='{') {i++;const out=Object.create(null);ws();if(text[i]==='}') {i++;return out;}
      for(;;) {ws();const k=string();check(!Object.hasOwn(out,k));ws();check(text[i++]===':');out[k]=value(depth+1);ws();const e=text[i++];if(e==='}')return out;check(e===',');ws();if(python&&text[i]==='}') {i++;return out;}}
    }
    if(c==='[') {i++;const out=[];ws();if(text[i]===']'){i++;return out;}for(;;){out.push(value(depth+1));ws();const e=text[i++];if(e===']')return out;check(e===',');ws();if(python&&text[i]===']'){i++;return out;}}}
    const token = /^(?:true|false|null|True|False|None|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(text.slice(i));check(token);i+=token[0].length;
    if(['true','false','null','True','False','None'].includes(token[0])) {check(python || !/^[A-Z]/.test(token[0]));return ({true:true,false:false,null:null,True:true,False:false,None:null})[token[0]];}
    const n=Number(token[0]);check(Number.isFinite(n) && (!Number.isInteger(n)||Number.isSafeInteger(n)));return n;
  }
  const out=value(0);ws();check(i===text.length);return out;
}
function normalized(v) {if(v==='true')return true;if(v==='false')return false;if(Array.isArray(v))return v.map(normalized);if(v&&typeof v==='object')return Object.fromEntries(Object.keys(v).sort().map(k=>[k,normalized(v[k])]));return v;}
function inspectBuild(config) {
  check(config && typeof config==='object' && config.variables && typeof config.variables==='object');const v=normalized(config.variables);
  check(v.target_arch === 'arm64' && v.node_shared === false && v.node_shared_libuv === false);
  check(v.node_builtin_modules_path === '');
  check(v.node_module_version === 127 || v.node_module_version === '127');
  check(v.node_custom_snapshot_main === undefined || v.node_custom_snapshot_main === '');
  check(v.node_snapshot_main === undefined || v.node_snapshot_main === '');
  check(v.node_shared_builtin_modules === undefined || Array.isArray(v.node_shared_builtin_modules) && v.node_shared_builtin_modules.length === 0);
  check(v.node_builtin_modules === undefined || Array.isArray(v.node_builtin_modules) && v.node_builtin_modules.length === 0);
  check(v.node_without_node_options === undefined || typeof v.node_without_node_options === 'boolean');
  check(v.node_linked_module === undefined || Array.isArray(v.node_linked_module) && v.node_linked_module.length === 0);
  check(v.node_shared_openssl === false && v.node_shared_zlib === false);
  return config;
}
function assertRuntime(owner, expectedExecutable = NODE) {
  check(process.platform === 'linux' && process.arch === 'arm64' && process.versions.node === '22.23.3' && process.versions.uv === '1.51.0' && process.versions.modules === '127');
  check(process.execPath === expectedExecutable && owner.helperExecutable === expectedExecutable && JSON.stringify(process.execArgv) === JSON.stringify(['--max-old-space-size=64']));
  check(owner.helperSourceCommit === SOURCE_COMMIT && owner.helperArchiveSha256 === ARCHIVE_SHA256);
  check(owner.helperSourceBindingSha256 === 'cbe2acd9df03cee1f27aa02d4aabc938f4cb66b2e437c3359c398298b469b4c0' && /^[0-9a-f]{64}$/.test(owner.closureSha256));
  for(const key of Object.keys(process.env)) check(!key.startsWith('LD_') && !key.startsWith('NODE_') && !['OPENSSL_CONF','OPENSSL_MODULES'].includes(key));
  const configPath=path.resolve(path.dirname(expectedExecutable),'../include/node/config.gypi');canonical(configPath);
  const bytes=regular(configPath,1048576);check(sha(bytes)===owner.helperConfigSha256);
  const config=inspectBuild(parseData(bytes,true));
  check(JSON.stringify(normalized(config)) === JSON.stringify(normalized(process.config)), 'Private helper compiled config differs');
  verifyClosure(owner);return true;
}
function verifyClosure(owner) {
  check(typeof owner.closurePath==='string');canonical(owner.closurePath);const value=parseData(regular(owner.closurePath,1048576),false,1048576);
  check(value.schema===1&&value.sha256===owner.closureSha256&&value.files&&typeof value.files==='object'&&!Array.isArray(value.files));
  const names=Object.keys(value.files).sort();check(names.length>=1&&names.length<=4096);let bytes=0;
  for(const name of names){check(/^[A-Za-z0-9_./+-]+$/.test(name)&&!name.startsWith('/')&&name.split('/').every(p=>p!==''&&p!=='.'&&p!=='..'));check(/^[0-9a-f]{64}$/.test(value.files[name]));
    check(/^(?:(?:usr\/)?lib(?:64)?\/|etc\/ld\.so\.(?:cache|preload)$|(?:usr\/)?bin\/(?:bash|dash|sh|env)$)/.test(name));
    const file='/'+name;canonical(file);const data=regular(file,536870912);bytes+=data.length;check(bytes<=536870912);check(sha(data)===value.files[name]);}
  const material=names.map(n=>n+'\0'+value.files[n]).join('\n');check(sha(Buffer.from(material))===owner.closureSha256);
}
function proc(pid) {
  check(Number.isSafeInteger(pid)&&pid>1);const text=fs.readFileSync(`/proc/${pid}/stat`,'utf8');check(text.length<=4096);
  const end=text.lastIndexOf(')');check(end>0);const fields=text.slice(end+2).trim().split(' ');check(fields.length>=20);
  check(/^[0-9]+$/.test(fields[19]));return {pid,ppid:Number(fields[1]),group:Number(fields[2]),start:fields[19],state:fields[0]};
}
function privateRoot(root) {
  const st=canonical(root,true);check(st.uid===BigInt(process.getuid()) && (st.mode&511n)===448n);return `${st.dev}:${st.ino}`;
}
function atomic(root, name, value) {
  check(/^[a-z][a-z0-9.-]*\.json$/.test(name));privateRoot(root);const bytes=Buffer.from(JSON.stringify(value));check(bytes.length<=16384);
  const target=path.join(root,name),temp=path.join(root,`.${name}.${process.pid}.tmp`);
  const fd=fs.openSync(temp,fs.constants.O_CREAT|fs.constants.O_EXCL|fs.constants.O_WRONLY|fs.constants.O_NOFOLLOW,0o600);
  try {fs.writeFileSync(fd,bytes);fs.fsyncSync(fd);} finally {fs.closeSync(fd);}fs.renameSync(temp,target);privateRoot(root);
}

const POLICY='csharp-original-0.28.0-sdk-10.0.402-node-22.23.3-private-v1';
const ROOT='/opt/droide/packages/lsp.csharp-ls/0.28.0/arm64-v8a';
const SDK='/opt/droide/packages/runtime.csharp-sdk/10.0.402/arm64-v8a/payload/tree';
function exactKeys(value,keys){check(value&&typeof value==='object'&&!Array.isArray(value));check(Object.keys(value).sort().join('\0')===keys.slice().sort().join('\0'));}
function safePath(file){check(typeof file==='string'&&file.length<=4096&&path.isAbsolute(file)&&!/[\x00-\x1f\x7f]/.test(file)&&path.normalize(file)===file);return file;}
function resourceReadback(health=false, text=fs.readFileSync('/proc/self/limits','utf8')) {
  check(text.length<=16384);const expected={'Max cpu time':health?60n:600n,'Max file size':67108864n,'Max core file size':0n,
    'Max processes':256n,'Max open files':256n,'Max address space':68719476736n};
  for(const [name,value] of Object.entries(expected)){
    const rows=text.split('\n').filter(line=>line.startsWith(name+' '));check(rows.length===1,'Missing inherited '+name);
    const match=/^\s+([0-9]+)\s+([0-9]+)(?:\s+.*)?$/.exec(rows[0].slice(name.length));
    check(match&&BigInt(match[1])===value&&BigInt(match[2])===value,'Unsupported inherited '+name);
  }return true;
}
function cleanEnvironment(root,sdkRoot=SDK) {return {HOME:root+'/home',TMPDIR:'/tmp',XDG_CONFIG_HOME:root+'/config',
 XDG_CACHE_HOME:root+'/cache',XDG_DATA_HOME:root+'/data',XDG_STATE_HOME:root+'/state',XDG_RUNTIME_DIR:root+'/run',
 DOTNET_CLI_HOME:root+'/home',NUGET_PACKAGES:root+'/nuget',NUGET_HTTP_CACHE_PATH:root+'/nuget-http',DOTNET_ROOT:sdkRoot,
 DOTNET_HOST_PATH:sdkRoot+'/dotnet',DOTNET_MULTILEVEL_LOOKUP:'0',DOTNET_EnableDiagnostics:'0',DOTNET_EnableDiagnostics_IPC:'0',
 DOTNET_CLI_TELEMETRY_OPTOUT:'1',DOTNET_SKIP_FIRST_TIME_EXPERIENCE:'1',DOTNET_CLI_WORKLOAD_UPDATE_NOTIFY_DISABLE:'1',
 DOTNET_GCHeapHardLimit:'0x20000000',DOTNET_gcServer:'0',PATH:sdkRoot,LANG:'C.UTF-8',LC_ALL:'C.UTF-8',TERM:'dumb'};}
function noGlobalAncestors(start){for(let at=start;;at=path.dirname(at)){check(!fs.existsSync(path.join(at,'global.json')),'Health has effective global.json');if(at===path.dirname(at))break;}}
function loadOwner(root){
 const rootIdentity=privateRoot(root),bytes=regular(path.join(root,'owner.json'),65536),owner=parseData(bytes,false,65536);
 check(owner.schema===1&&owner.policy===POLICY&&owner.privateRoot===root&&owner.privateIdentity===rootIdentity);
 check(/^[0-9a-f]{32}$/.test(owner.token)&&Number.isSafeInteger(owner.ownerGeneration)&&owner.ownerGeneration>0&&/^[0-9a-f]{64}$/.test(owner.consentIdentity));
 check(['editor','health'].includes(owner.mode));safePath(owner.rootPath);canonical(owner.rootPath,true);
 check(!fs.existsSync(root+'/retired.json')&&!fs.existsSync(root+'/stop.json'),'Owner is already retired');
 check(owner.sdkRoot===SDK&&owner.sdkExecutable===SDK+'/dotnet'&&owner.sdkVersion==='10.0.402'&&owner.runtimeVersion==='10.0.13');
 check(owner.mode==='health'||owner.packageRoot===ROOT);safePath(owner.packageRoot);
 check(owner.serverExecutable===owner.packageRoot+'/payload/tree/tools/net10.0/any/CSharpLanguageServer.dll');
 check(owner.solutionPath===null||typeof owner.solutionPath==='string'&&/\.(sln|slnx)$/i.test(owner.solutionPath)&&owner.solutionPath.startsWith(owner.rootPath+'/'));
 for(const dir of ['home','config','cache','data','state','run','tmp','nuget','nuget-http'])privateRoot(root+'/'+dir);
 // A TMPDIR variable alone does not relocate Roslyn's mandatory /tmp/GUID endpoint.
 check(privateRoot('/tmp')===privateRoot(root+'/tmp'),'C# requires its private guest /tmp bind');
 const wanted=cleanEnvironment(root,owner.sdkRoot);for(const [key,value] of Object.entries(wanted))check(process.env[key]===value,'C# environment mismatch '+key);
 for(const key of Object.keys(process.env))check(Object.hasOwn(wanted,key)||key==='DROIDE_PROCESS_LEASE','Unreviewed C# environment '+key);
 resourceReadback(owner.mode==='health');assertRuntime(owner);
 check(Array.isArray(owner.proofs)&&owner.proofs.length>=4&&owner.proofs.length<=64);
 for(const proof of owner.proofs){exactKeys(proof,['path','sha256']);safePath(proof.path);canonical(proof.path);check(/^[0-9a-f]{64}$/.test(proof.sha256));check(sha(regular(proof.path,536870912))===proof.sha256);}
 if(owner.mode==='health'){check(owner.solutionPath===null);noGlobalAncestors(owner.rootPath);}
 return {owner,bytes,rootIdentity};
}
function groupObservation(group){check(Number.isSafeInteger(group)&&group>1);let count=0,rss=0n,scanned=0;
 for(const name of fs.readdirSync('/proc')){check(++scanned<=131072,'Process scan bound');if(!/^[0-9]+$/.test(name)||Number(name)<=1)continue;
  try{const before=proc(Number(name));if(before.group!==group)continue;
   const status=fs.readFileSync('/proc/'+name+'/status','utf8');check(status.length<=65536);const match=/^VmRSS:\s+([0-9]+) kB$/m.exec(status);
   const after=proc(Number(name));check(before.start===after.start&&before.group===after.group,'Process identity changed during accounting');
   if(after.state!=='Z'){check(match,'Missing live process RSS');rss+=BigInt(match[1])*1024n;}count++;
  }catch(e){if(e.code!=='ENOENT'&&e.code!=='ESRCH')throw e;}}
 check(count<=32&&rss<=1610612736n,'Observed group/RSS bound exceeded');return {count,rss};
}
module.exports={check,sha,regular,canonical,parseData,normalized,inspectBuild,assertRuntime,verifyClosure,proc,privateRoot,atomic,
 loadOwner,NODE,ROOT,SDK,POLICY,SOURCE_COMMIT,ARCHIVE_SHA256,exactKeys,safePath,resourceReadback,cleanEnvironment,noGlobalAncestors,groupObservation};
