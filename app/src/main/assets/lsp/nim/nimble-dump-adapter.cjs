'use strict';
// stdout is reserved exclusively for the one complete admitted native Buffer publication below.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const I = require('./nim-helper-identity-health.cjs');
const {check,sha,regular,canonical,parseData,proc,privateRoot,atomic,loadOwner} = I;
const ALIASES = new Set(['nim','nimrod','compiler']);
const FIELDS = ['name','srcDir','nimDir','nimblePath','entryPoints','requires'];
function version(value) {check(/^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/.test(value),'Unsupported compiler version');const out=value.split('.').map(Number);check(out.every(Number.isSafeInteger));return out;}
function compare(a,b) {for(let i=0;i<3;i++) if(a[i]!==b[i])return a[i]<b[i]?-1:1;return 0;}
function requirements(value) {
  if(value==='')return;const items=value.split(', ');check(items.length<=1024);
  for(const item of items) {const at=item.indexOf('@');check(at>0 && at===item.lastIndexOf('@'));const name=item.slice(0,at);check(/^[A-Za-z0-9_.\/-]+$/.test(name));if(!ALIASES.has(name))continue;
    const constraints=item.slice(at+1).split(' & ');check(constraints.length>=1&&constraints.length<=4);
    for(const part of constraints) {if(part==='any version')continue;const m=/^(?:(>=|<=|>|<) )?((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))$/.exec(part);check(m,'Unsupported compiler constraint');const c=compare([2,2,12],version(m[2]));check(({'>=':c>=0,'<=':c<=0,'>':c>0,'<':c<0,'':c===0})[m[1]||''],'Compiler requirement conflict');}
  }
}
function lock(bytes) {
  if(bytes===null)return;const data=parseData(bytes);
  check(data&&typeof data==='object'&&!Array.isArray(data)&&data.version===2&&data.packages&&typeof data.packages==='object'&&!Array.isArray(data.packages));
  check(Object.keys(data).every(k=>['version','packages','tasks'].includes(k)));
  if(data.tasks!==undefined)check(data.tasks&&typeof data.tasks==='object'&&!Array.isArray(data.tasks));
  for(const [name,entry] of Object.entries(data.packages)) {if(!ALIASES.has(name))continue;check(typeof entry==='string'||entry&&typeof entry==='object'&&!Array.isArray(entry));const v=typeof entry==='string'?entry:entry.version;check(typeof v==='string'&&compare(version(v),[2,2,12])===0,'Locked compiler conflict');
    if(typeof entry==='object') {check(Object.keys(entry).every(k=>['version','vcsRevision','url','downloadMethod','dependencies','checksums'].includes(k)),'Unknown compiler lock field');
      if(entry.vcsRevision!==undefined)check(typeof entry.vcsRevision==='string'&&(entry.vcsRevision===''||entry.vcsRevision==='8e8fbf60693418dc95bb0d762fd660231d08a583'));
      if(entry.url!==undefined)check(typeof entry.url==='string'&&(entry.url===''||entry.url==='https://github.com/nim-lang/Nim'));
    }
  }
}
function fileSnapshot(file,optional=false) {
  let exists=true;try{check(!fs.lstatSync(file).isSymbolicLink(),'Symbolic metadata file');}catch(e){if(e.code==='ENOENT')exists=false;else throw e;}
  if(!exists) {check(optional);check(!fs.lstatSync(path.dirname(file)).isSymbolicLink());return null;}
  const st=canonical(file),bytes=regular(file,1048576);return {path:file,dev:String(st.dev),ino:String(st.ino),size:String(st.size),mtime:String(st.mtimeNs),ctime:String(st.ctimeNs),sha256:sha(bytes)};
}
function projectSnapshot(root) {
  canonical(root,true);const names=fs.readdirSync(root);check(names.length<=16384);
  const manifests=names.filter(n=>n.endsWith('.nimble'));check(manifests.length===1);const manifest=path.join(root,manifests[0]);canonical(manifest);
  const files=[manifest,'nimble.lock','nim.cfg','config.nims'].map((p,i)=>fileSnapshot(i===0?p:path.join(root,p),i!==0));
  if(files[1])lock(regular(path.join(root,'nimble.lock'),1048576));return {manifest,files};
}
function relative(value,empty=false) {check(value.length<=4095 && !value.includes('\\')&&!value.includes(',')&&/^[\x20-\x7e]*$/.test(value));check((empty&&(value===''||value==='.'))||value!==''&&!path.isAbsolute(value)&&value.split('/').every(p=>p!==''&&p!=='.'&&p!=='..'));return value;}
function admitMetadata(raw,owner) {
  check(Buffer.isBuffer(raw)&&raw.length>=1&&raw.length<=4096);check(!raw.includes(0)&&!raw.includes(13));
  const text=new TextDecoder('utf-8',{fatal:true}).decode(raw);const lines=text.split('\n');if(lines.at(-1)==='')lines.pop();check(lines.length>=6&&lines.length<=4096);
  const fields=Object.create(null),seen=new Set();
  for(const line of lines) {check(Buffer.byteLength(line)<=16384);const match=/^([A-Za-z][A-Za-z0-9_]{0,127}): "((?:[^"\\]|\\.)*)"$/.exec(line);check(match,'Malformed native dump line');const [,key,value]=match;check(!seen.has(key));seen.add(key);
    for(const consumed of FIELDS)check(!key.startsWith(consumed)||key===consumed,'Native prefix collision');
    if(FIELDS.includes(key)) {check(/^[\x20-\x7e]*$/.test(value)&&!value.includes('\\')&&!value.includes('"'));fields[key]=value;}
  }
  check(FIELDS.every(k=>Object.hasOwn(fields,k)));check(/^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/.test(fields.name));relative(fields.srcDir,true);
  check(fields.nimDir===path.dirname(owner.compilerExecutable)&&fields.nimDir===path.dirname(owner.backendExecutable));canonical(fields.nimDir,true);
  check(fields.nimblePath===projectSnapshot(owner.rootPath).manifest);requirements(fields.requires);
  // Native split(',').strip({' ', '"'}) is modeled without rewriting the published bytes.
  const entries=fields.entryPoints.split(',').map(v=>v.replace(/^ +| +$/g,''));check(entries.length<=256);
  const candidates=[],existing=new Set();
  for(const item of entries) {relative(item);check(item.endsWith('.nim'));const target=path.resolve(owner.rootPath,item);check(target.startsWith(owner.rootPath+'/'));let snapshot=null;
    let exists=true;try{check(!fs.lstatSync(target).isSymbolicLink(),'Symbolic native candidate');}catch(e){if(e.code==='ENOENT')exists=false;else throw e;}
    if(exists) {snapshot=fileSnapshot(target);existing.add(target);}else {let ancestor=path.dirname(target);while(!fs.existsSync(ancestor))ancestor=path.dirname(ancestor);canonical(ancestor,true);}
    candidates.push({path:target,snapshot});
  }
  check(existing.size===1,'Exactly one distinct existing native entry required');return {nativeEntryPath:[...existing][0],manifestPath:fields.nimblePath,metadataSha256:sha(raw),candidates};
}
function boundedProc(file,maximum=4096) {const fd=fs.openSync(file,fs.constants.O_RDONLY);try{const b=Buffer.alloc(maximum+1);let n=0;while(n<b.length){const k=fs.readSync(fd,b,n,b.length-n,null);if(!k)break;n+=k;}check(n<=maximum);return b.subarray(0,n).toString('utf8');}finally{fs.closeSync(fd);}}
function fdinfo(text,writer,inode) {
  check(text.length<=4096);const fields=Object.create(null);
  for(const line of text.trimEnd().split('\n')) {const m=/^(pos|flags|mnt_id|ino):\s+([0-9]+)$/.exec(line);check(m&&!Object.hasOwn(fields,m[1]));fields[m[1]]=m[2];}
  check(Object.keys(fields).length===4&&fields.pos==='0'&&fields.ino===String(inode)&&/^[0-7]+$/.test(fields.flags));
  check(fields.flags.length<=16);const flags=BigInt('0o'+fields.flags);const allowed=writer?(1n|0o2000000n|0o100000n):(0o4000n|0o2000000n|0o100000n);
  check((flags&3n)===(writer?1n:0n)&&(flags&~allowed)===0n && /^[1-9][0-9]*$/.test(fields.mnt_id));return fields;
}
function descriptor(owner,expected) {
  check(process.platform==='linux'&&process.ppid===expected.pid&&expected.token===owner.token);
  const parent=proc(expected.pid);check(parent.start===expected.start&&parent.state!=='Z'&&parent.group===expected.group);
  const self=proc(process.pid);check(self.ppid===expected.pid);
  const st=fs.fstatSync(1,{bigint:true});check(st.isFIFO());check(fs.readlinkSync('/proc/self/fd/1')===`pipe:[${st.ino}]`);fdinfo(boundedProc('/proc/self/fdinfo/1'),true,st.ino);
  const names=fs.readdirSync(`/proc/${expected.pid}/fd`);check(names.length<=256);let matches=0;
  for(const name of names) {check(/^(?:0|[1-9][0-9]*)$/.test(name));let target;try{target=fs.readlinkSync(`/proc/${expected.pid}/fd/${name}`);}catch(e){if(e.code==='ENOENT')continue;throw e;}
    if(target!==`pipe:[${st.ino}]`)continue;const rs=fs.statSync(`/proc/${expected.pid}/fd/${name}`,{bigint:true});check(rs.isFIFO()&&rs.dev===st.dev&&rs.ino===st.ino);fdinfo(boundedProc(`/proc/${expected.pid}/fdinfo/${name}`),false,st.ino);matches++;
  }
  check(matches===1,'Unowned metadata pipe');check(proc(expected.pid).start===expected.start);return `${st.dev}:${st.ino}:${parent.start}`;
}
async function capture(owner) {
  return await new Promise((resolve,reject)=>{
    let child,finished=false,total=0,errors=0;const chunks=[];let timer;
    function fail(e){if(finished)return;finished=true;clearTimeout(timer);if(child)child.kill('SIGKILL');reject(e);}
    try {child=cp.spawn(owner.nimbleExecutable,[`--nim:${owner.compilerExecutable}`,'--useSystemNim','--offline','--reject','--disableNimBinaries','dump','--ini'],{cwd:owner.rootPath,env:process.env,stdio:['ignore','pipe','pipe']});}catch(e){fail(e);return;}
    timer=setTimeout(()=>fail(new Error('Native Nimble deadline')),30000);
    child.stdout.on('data',b=>{total+=b.length;if(total>4096)fail(new Error('Native metadata oversized'));else chunks.push(Buffer.from(b));});
    child.stderr.on('data',b=>{errors+=b.length;if(errors>65536)fail(new Error('Native metadata stderr exceeded bound'));});
    child.on('error',fail);child.on('close',(code,signal)=>{if(finished)return;finished=true;clearTimeout(timer);if(code!==0||signal)reject(new Error('Native metadata failed'));else resolve(Buffer.concat(chunks,total));});
  });
}
async function run() {
  const root=process.env.DROIDE_NIM_OWNER;check(typeof root==='string');let token='';
  try {
    check(process.argv.length===3&&process.argv[2]==='dump');const first=loadOwner(root),owner=first.owner;token=owner.token;check(owner.mode==='editor'&&process.cwd()===owner.rootPath&&process.env.PATH===path.join(root,'commands'));
    check(!fs.existsSync(path.join(root,'metadata.json'))&&!fs.existsSync(path.join(root,'invocation.json')),'Repeated metadata invocation');
    const server=parseData(regular(path.join(root,'server.json')));const pipe=descriptor(owner,server);
    const claim=fs.openSync(path.join(root,'invocation.lock'),fs.constants.O_CREAT|fs.constants.O_EXCL|fs.constants.O_WRONLY|fs.constants.O_NOFOLLOW,0o600);fs.closeSync(claim);
    atomic(root,'invocation.json',{token,pid:process.pid,start:proc(process.pid).start});const before=projectSnapshot(owner.rootPath);
    check(JSON.stringify(before)===JSON.stringify(owner.projectSnapshot),'Project changed before evaluation');
    const raw=await capture(owner);check(JSON.stringify(projectSnapshot(owner.rootPath))===JSON.stringify(before),'Project changed during evaluation');const metadata=admitMetadata(raw,owner);
    const receipt={schema:1,status:'PUBLISHED',token,ownerGeneration:owner.ownerGeneration,...metadata,serverPid:server.pid,serverStart:server.start,pipeIdentity:pipe};check(Buffer.byteLength(JSON.stringify(receipt))<=16384);
    // Last section is fully synchronous. No fd replacement, stream, yield, child writer or retry.
    const final=loadOwner(root);check(final.bytes.equals(first.bytes));check(JSON.stringify(projectSnapshot(owner.rootPath))===JSON.stringify(before));
    check(JSON.stringify(admitMetadata(raw,owner))===JSON.stringify(metadata));check(descriptor(owner,server)===pipe);
    const written=fs.writeSync(1, raw, 0, raw.length, null);
    check(written===raw.length,'Atomic publication invariant failed');
    atomic(root,'metadata.json',receipt);
  } catch(e) {
    const kind=['Compiler requirement conflict','Locked compiler conflict'].includes(e.message)?'COMPILER_CONFLICT':e.message==='Native metadata oversized'?'METADATA_LIMIT':e.message==='Native Nimble deadline'?'METADATA_TIMEOUT':e.message==='Native metadata failed'?'RESTRICTED_METADATA_FAILED':e.message.startsWith('Project changed')?'PROJECT_CHANGED':'ADMISSION_REJECTED';
    try {atomic(root,'failure.json',{schema:1,token,status:'FAILED',kind});}catch(_){}
    process.stderr.write('Nim fixed-toolchain metadata refused\n');process.exitCode=70;
  }
}
module.exports={version,compare,requirements,lock,fileSnapshot,projectSnapshot,admitMetadata,fdinfo,descriptor,capture};
if(require.main===module)run();
