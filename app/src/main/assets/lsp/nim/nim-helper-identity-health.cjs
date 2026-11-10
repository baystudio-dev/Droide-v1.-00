'use strict';
// App-authored data admission. This is not an original-artifact acceptance test.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const SOURCE_COMMIT = '80dc632040e6bada37aac1220dde9c79581c9c22';
const ARCHIVE_SHA256 = '5ced2d48d1d7198739b7f86804de0171aefb6823b684b12341d3321afc3cb0b2';
const NODE = '/opt/droide/packages/runtime.nim-helper/22.23.3/arm64-v8a/payload/tree/node-v22.23.3-linux-arm64/bin/node';
function check(value, why = 'Nim identity refused') { if (!value) throw new Error(why); }
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
    check(depth <= 16 && ++members <= 16384); ws(); const c=text[i];
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
  check(process.execPath === expectedExecutable && owner.helperExecutable === expectedExecutable && process.execArgv.length === 0);
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
  check(typeof owner.closurePath==='string');canonical(owner.closurePath);const value=parseData(regular(owner.closurePath,65536));
  check(value.schema===1&&value.sha256===owner.closureSha256&&value.files&&typeof value.files==='object'&&!Array.isArray(value.files));
  const names=Object.keys(value.files).sort();check(names.length>=1&&names.length<=67);let bytes=0;
  for(const name of names){check(/^[A-Za-z0-9_./+-]+$/.test(name)&&!name.startsWith('/')&&name.split('/').every(p=>p!==''&&p!=='.'&&p!=='..'));check(/^[0-9a-f]{64}$/.test(value.files[name]));
    check(/^(?:(?:usr\/)?lib(?:64)?\/|etc\/ld\.so\.(?:cache|preload)$|(?:usr\/)?bin\/(?:dash|sh)$)/.test(name));
    const file='/'+name;canonical(file);const data=regular(file,536870912);bytes+=data.length;check(bytes<=536870912);check(sha(data)===value.files[name]);}
  const material=names.map(n=>n+'\0'+value.files[n]).join('\n');check(sha(Buffer.from(material))===owner.closureSha256);
}
function stageIdentity(root,receipt,activation) {
  const suffix='runtime.nim-helper-22.23.3-local-ubuntu-vendor-'+sha(Buffer.from('runtime.nim-helper@22.23.3:local-ubuntu-vendor')).slice(0,24);
  check(path.isAbsolute(root)&&root.endsWith('/packages/.staging/'+suffix)&&root.split('/').every(p=>p!=='.'&&p!=='..'));
  check(receipt.schema===1&&receipt.policy==='nimlangserver-1.16.0-nim-2.2.12-helper-22.23.3-fixed-v1'&&receipt.family==='runtime.nim-helper'&&receipt.version==='22.23.3');
  check(receipt.archiveSha256===ARCHIVE_SHA256&&receipt.sourceCommit===SOURCE_COMMIT&&receipt.sourceBindingSha256==='cbe2acd9df03cee1f27aa02d4aabc938f4cb66b2e437c3359c398298b469b4c0');
  check(receipt.stage===root&&receipt.final===path.resolve(path.dirname(NODE),'../../../..')&&receipt.activation===activation&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(activation));
  return {helperExecutable:root+'/payload/tree/node-v22.23.3-linux-arm64/bin/node',helperSourceCommit:SOURCE_COMMIT,helperArchiveSha256:ARCHIVE_SHA256,helperConfigSha256:receipt.configSha256,closureSha256:receipt.closureSha256,helperSourceBindingSha256:receipt.sourceBindingSha256,closurePath:root+'/NIM_CLOSURE.json'};
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
function loadOwner(root) {
  const rootIdentity=privateRoot(root), bytes=regular(path.join(root,'owner.json')), owner=parseData(bytes);
  check(owner.schema===1 && owner.privateRoot===root && owner.privateIdentity===rootIdentity && /^[0-9a-f]{32}$/.test(owner.token));
  check(Number.isSafeInteger(owner.ownerGeneration)&&owner.ownerGeneration>=0 && /^[0-9a-f]{64}$/.test(owner.consentIdentity));
  check(owner.policy==='nimlangserver-1.16.0-nim-2.2.12-fixed-analysis-v1' && ['editor','health'].includes(owner.mode));
  check(owner.rootPath===fs.realpathSync(owner.rootPath)); check(!fs.existsSync(path.join(root,'stop.json'))&&!fs.existsSync(path.join(root,'retired.json')));
  assertRuntime(owner);
  check(Array.isArray(owner.proofs)&&owner.proofs.length>=4&&owner.proofs.length<=24);
  for(const proof of owner.proofs) {canonical(proof.path);check(sha(regular(proof.path,134217728))===proof.sha256);}
  return {owner,bytes,rootIdentity};
}
module.exports={check,sha,regular,canonical,parseData,normalized,inspectBuild,assertRuntime,verifyClosure,stageIdentity,proc,privateRoot,atomic,loadOwner,NODE,SOURCE_COMMIT,ARCHIVE_SHA256};
if(require.main===module) {
  try {
    if(process.argv.length===4&&process.argv[2]==='--refuse') {
      const root=process.argv[3];check(process.env.DROIDE_NIM_OWNER===root);atomic(root,'guard.json',{schema:1,status:'REFUSED'});process.exitCode=70;
    } else {
      check(process.argv.length===4&&process.argv[2]==='--installed-health'||process.argv.length===7&&process.argv[2]==='--health');
      const root=process.argv[3];canonical(root,true);const receipt=parseData(regular(path.join(root,'NIM_AUTHORITY.json'),65536));let identity;
      if(process.argv[2]==='--health') {
        const activation=new TextDecoder('utf-8',{fatal:true}).decode(regular(root+'/DROIDE_ACTIVATION_ID',128));identity=stageIdentity(root,receipt,activation);
        check(identity.helperConfigSha256===process.argv[4]&&identity.closureSha256===process.argv[5]&&identity.helperSourceBindingSha256===process.argv[6]);
        assertRuntime(identity,identity.helperExecutable);
      } else {
        check(root===path.resolve(path.dirname(NODE),'../../../..'));
        identity={helperExecutable:NODE,helperSourceCommit:SOURCE_COMMIT,helperArchiveSha256:ARCHIVE_SHA256,helperConfigSha256:receipt.configSha256,closureSha256:receipt.closureSha256,helperSourceBindingSha256:receipt.sourceBindingSha256,closurePath:root+'/NIM_CLOSURE.json'};assertRuntime(identity);
      }
      process.stdout.write('DROIDE_NIM_HELPER_IDENTITY_OK\n');
    }
  }catch(_) {process.stderr.write('Nim private helper identity health refused\n');process.exitCode=70;}
}
