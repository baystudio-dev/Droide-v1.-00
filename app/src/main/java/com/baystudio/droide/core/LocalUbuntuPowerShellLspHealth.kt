package com.baystudio.droide.core

internal object LocalUbuntuPowerShellLspHealth {
    const val FILE_NAME = "powershell-supervisor.cjs"
    const val TIMEOUT_MS = 60_000L
    val script: String = """
        'use strict';
        // Owned lifecycle and transparent byte transport only. No PowerShell or LSP emulation.
        const fs = require('node:fs'), path = require('node:path'), net = require('node:net');
        const { spawn } = require('node:child_process');
        const { createHash, randomBytes } = require('node:crypto');
        const { performance } = require('node:perf_hooks');
        const { TextDecoder } = require('node:util');
        const decode = new TextDecoder('utf-8', { fatal:true });
        const own = (v,k) => Object.prototype.hasOwnProperty.call(v,k);
        const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
        const same = (a,b) => a.dev === b.dev && a.ino === b.ino && a.mode === b.mode;
        const unchanged = (a,b) => same(a,b) && a.size === b.size && a.mtimeMs === b.mtimeMs && a.ctimeMs === b.ctimeMs;
        const digest = b => createHash('sha256').update(b).digest('hex');
        const delay = ms => new Promise(resolve => setTimeout(resolve,ms));
        const absolute = v => typeof v === 'string' && v.startsWith('/') && Buffer.byteLength(v) <= 4095 && !/[\x00-\x1f\x7f]/.test(v) && v.slice(1).split('/').every(p => p && p !== '.' && p !== '..');
        function strictJson(text) {
          let offset=0, values=0;
          const space=()=>{ while(offset<text.length && /[ \t\r\n]/.test(text[offset])) ++offset; };
          function string() { const begin=offset++; while(offset<text.length) { const ch=text[offset++]; if(ch==='"') return JSON.parse(text.slice(begin,offset)); if(ch==='\\') ++offset; } throw Error('incomplete JSON string'); }
          function value(depth) {
            if(depth>64 || ++values>50000) throw Error('JSON structure bound exceeded');
            space(); const ch=text[offset]; if(ch==='"') return string();
            if(ch==='{') { ++offset; const result=Object.create(null); space(); if(text[offset]==='}') { ++offset; return result; }
              for(;;) { space(); if(text[offset]!=='"') throw Error('invalid JSON key'); const key=string(); if(own(result,key)) throw Error('duplicate JSON key'); space(); if(text[offset++]!==':') throw Error('invalid JSON object'); result[key]=value(depth+1); space(); const next=text[offset++]; if(next==='}') return result; if(next!==',') throw Error('invalid JSON object'); }
            }
            if(ch==='[') { ++offset; const result=[]; space(); if(text[offset]===']') { ++offset; return result; } for(;;) { result.push(value(depth+1)); space(); const next=text[offset++]; if(next===']') return result; if(next!==',') throw Error('invalid JSON array'); } }
            const match=/^(?:null|true|false|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(text.slice(offset));
            if(!match) throw Error('invalid JSON scalar'); offset+=match[0].length; const result=JSON.parse(match[0]); if(typeof result==='number' && !Number.isFinite(result)) throw Error('nonfinite JSON'); return result;
          }
          const result=value(0); space(); if(offset!==text.length) throw Error('trailing JSON data'); return result;
        }
        function endpointFor(tmp,name) { if(!absolute(tmp) || !/^[a-z0-9]{16}${'$'}/.test(name)) throw Error('invalid owned pipe'); const endpoint=tmp+'/CoreFxPipe_'+name; if(Buffer.byteLength(endpoint)>90) throw Error('owned pipe exceeds 90 byte bound'); return endpoint; }
        function nativeArgs(tree,name,session,logs) { return ['-NoLogo','-NoProfile','-NonInteractive','-File',tree+'/PowerShellEditorServices/Start-EditorServices.ps1','-LanguageServiceOnly','-LanguageServicePipeName',name,'-HostName','Droide','-HostProfileId','Droide','-HostVersion','1.0.0','-BundledModulesPath',tree,'-SessionDetailsPath',session,'-LogPath',logs,'-LogLevel','Error']; }
        function sidecar(value,endpoint) {
          if(!object(value) || Object.keys(value).sort().join(',')!=='languageServicePipeName,languageServiceTransport,powerShellVersion,status' || value.status!=='started' || value.languageServiceTransport!=='NamedPipe' || value.languageServicePipeName!==endpoint || value.powerShellVersion!=='7.6.7') throw Error('PSES sidecar transport/version mismatch');
        }
        function capabilities(result) {
          if(!object(result) || !object(result.capabilities)) throw Error('missing initialize capabilities');
          const c=result.capabilities, s=c.textDocumentSync;
          if(!object(s) || s.openClose!==true || s.change!==2 || !object(s.save) || s.save.includeText!==true || (c.positionEncoding!==undefined && c.positionEncoding!=='utf-16')) throw Error('unexpected PSES sync/encoding');
          if(!object(c.completionProvider) || !Array.isArray(c.completionProvider.triggerCharacters) || c.completionProvider.triggerCharacters.length!==6 || new Set(c.completionProvider.triggerCharacters).size!==6 || !['.','-',':','\\','${'$'}',' '].every(x=>c.completionProvider.triggerCharacters.includes(x)) || c.hoverProvider!==true || c.definitionProvider!==true || c.referencesProvider!==true || c.documentSymbolProvider!==true) throw Error('unexpected PSES capability subset');
        }
        class Frames {
          constructor(handle) { this.handle=handle; this.buffer=Buffer.alloc(0); this.length=null; this.received=0; this.messages=0; }
          receive(chunk) {
            this.received+=chunk.length; if(this.received>4194304) throw Error('health protocol byte bound exceeded');
            this.buffer=Buffer.concat([this.buffer,chunk]);
            for(;;) {
              if(this.length===null) { const end=this.buffer.indexOf('\r\n\r\n'); if(end<0) { if(this.buffer.length>8192) throw Error('header too large'); return; } if(end>8192) throw Error('header too large');
                const header=this.buffer.subarray(0,end).toString('ascii'); const match=/^Content-Length: ([1-9][0-9]*)${'$'}/.exec(header);
                if(!match || [...this.buffer.subarray(0,end)].some(b=>b<32 || b>126)) throw Error('invalid Content-Length'); this.length=Number(match[1]);
                if(!Number.isSafeInteger(this.length) || this.length>1048576) throw Error('frame too large'); this.buffer=this.buffer.subarray(end+4); }
              if(this.buffer.length<this.length) return;
              const body=this.buffer.subarray(0,this.length); this.buffer=this.buffer.subarray(this.length); this.length=null;
              if(++this.messages>128) throw Error('health message bound exceeded'); this.handle(strictJson(decode.decode(body)));
            }
          }
          complete() { return this.length===null && this.buffer.length===0; }
        }
        async function run() {
          const started=performance.now(), args=process.argv.slice(2);
          let mode, base, authority, target, root=null, rootFd=null, rootIdentity=null, tmpFd=null, tmpIdentity=null, endpoint=null, endpointIdentity=null, owner=null, socket=null;
          let finishing=false, finishCode=0, workTimer=null, watchTimer=null, phaseTimer=null, peerEnded=false, exitFlushed=false, phase='initialize', stdoutBytes=0, stderrBytes=0, runtimeOutput=Buffer.alloc(0), frames=null;
          const identities=[], descriptors=[], privateDirs=[], sharedIdentities=[];
          const health=()=>mode!=='editor';
          function realDir(p) { const a=fs.lstatSync(p); if(!a.isDirectory() || a.isSymbolicLink()) throw Error('unsafe directory: '+p); return a; }
          function parents(p) { let current='/'; for(const part of path.dirname(p).slice(1).split('/').filter(Boolean)) { current=path.join(current,part); realDir(current); } }
          function read(p,limit,empty=false,retain=true) { parents(p); const fd=fs.openSync(p,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW|fs.constants.O_NONBLOCK); try { const before=fs.fstatSync(fd); if(!before.isFile() || before.nlink!==1 || before.size<(empty?0:1) || before.size>limit) throw Error('invalid bounded file: '+p); const data=Buffer.alloc(before.size+1); let n=0, count; while(n<data.length && (count=fs.readSync(fd,data,n,data.length-n,n))>0) n+=count; if(n!==before.size || !unchanged(before,fs.fstatSync(fd)) || !unchanged(before,fs.lstatSync(p))) throw Error('file changed: '+p); if(retain) identities.push([p,before]); return data.subarray(0,n); } finally { fs.closeSync(fd); } }
          function checkIdentity() { for(const [p,a] of identities) if(!unchanged(a,fs.lstatSync(p))) throw Error('managed launch identity changed'); }
          function checkRoot() { if(root===null || !same(fs.fstatSync(tmpFd),tmpIdentity) || !same(realDir('/tmp'),tmpIdentity) || !same(fs.fstatSync(rootFd),rootIdentity) || !same(realDir(root),rootIdentity)) throw Error('owned private root replaced'); for(const [p,a] of privateDirs) if(!same(realDir(p),a)) throw Error('private directory replaced'); }
          function sharedModules() {
            let current='';
            for(const part of ['usr','local','share','powershell','Modules']) {
              current+='/'+part;
              try { const a=fs.lstatSync(current); if(!a.isDirectory() || a.isSymbolicLink()) throw Error('PowerShell Editor Services health cannot verify '+current+': path is symlinked or not a directory'); sharedIdentities.push([current,a]); }
              catch(e) { if(e.code==='ENOENT') { sharedIdentities.push([current,null]); return; } throw e; }
            }
            if(fs.readdirSync(current).length!==0) throw Error('PowerShell Editor Services health is blocked: /usr/local/share/powershell/Modules contains modules outside the reviewed managed inventory.');
          }
          function checkShared() { for(const [p,a] of sharedIdentities) { if(a===null) { try { fs.lstatSync(p); throw Error('shared module path appeared'); } catch(e) { if(e.code!=='ENOENT') throw e; } } else if(!same(realDir(p),a)) throw Error('shared module parent changed'); } if(sharedIdentities.length===5 && sharedIdentities[4][1] && fs.readdirSync('/usr/local/share/powershell/Modules').length) throw Error('shared module inventory changed'); }
          function preflight() {
            if(args.length!==2 || !['editor','health','runtime-health'].includes(args[0]) || !absolute(args[1])) throw Error('invalid supervisor arguments');
            mode=args[0]; base=args[1]; const runtime=mode==='runtime-health';
            authority=strictJson(decode.decode(read(base+'/POWERSHELL_LAUNCH_AUTHORITY.json',65536)));
            if(!object(authority) || authority.schema!==1 || authority.policy!=='powershell-7.6.7-pses-4.7.0-consolehost-pipe-v1' ||
               authority.family!==(runtime?'runtime.powershell':'lsp.powershell-editor-services') || authority.version!==(runtime?'7.6.7':'4.7.0') ||
               authority.archiveSha256!==(runtime?'ba465efdfb0984c1c5dc5ed9eb0b79a7e91a1b52c45f99377058c0659b84d3f1':'5084f0326cc88539e9d880b08f6c52ce5be4672bb8ecee8921e35f8895a6b9d7') ||
               (base!==authority.stageRoot && base!==authority.finalRoot) || !absolute(authority.stageRoot) || !absolute(authority.finalRoot) ||
               authority.finalRoot!=='/opt/droide/packages/'+authority.family+'/'+authority.version+'/arm64-v8a' ||
               (mode==='editor' && base!==authority.finalRoot) || process.argv[1]!==base+'/powershell-supervisor.cjs') throw Error('managed supervisor authority mismatch');
            if(read(base+'/DROIDE_ACTIVATION_ID',128).toString('ascii')!==authority.activationId || digest(read(base+'/TREE_SHA256SUMS',2097152))!==authority.treeManifestSha256 || digest(read(process.argv[1],65536))!==authority.helperSha256) throw Error('managed activation/helper/tree manifest mismatch');
            if(authority.supplementaryNoticeSha256!=='86f77f1007adb7813c9a60b0a4c8711d1bc1a8832731291db5e4ab41ecaff3f0' || digest(read(base+'/DROIDE_POWERSHELL_RECONCILIATION.txt',16384))!==authority.supplementaryNoticeSha256) throw Error('supplementary notice changed');
            if(read(base+'/TREE_LINKS.tsv',1,true).length) throw Error('PowerShell links are not admitted');
            const runtimeRoot=runtime?base:'/opt/droide/packages/runtime.powershell/7.6.7/arm64-v8a';
            if(authority.runtimeRoot!=='/opt/droide/packages/runtime.powershell/7.6.7/arm64-v8a' || authority.runtimeArchiveSha256!=='ba465efdfb0984c1c5dc5ed9eb0b79a7e91a1b52c45f99377058c0659b84d3f1' || read(runtimeRoot+'/DROIDE_ACTIVATION_ID',128).toString('ascii')!==authority.runtimeActivationId) throw Error('managed exact runtime changed');
            if(!runtime && digest(read(runtimeRoot+'/SHA256SUMS',2097152))!==authority.runtimeSealSha256) throw Error('runtime seal changed');
            target=runtimeRoot+'/payload/tree/pwsh'; parents(target); const fd=fs.openSync(target,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW|fs.constants.O_NONBLOCK), a=fs.fstatSync(fd); descriptors.push(fd);
            if(!a.isFile() || a.nlink!==1 || a.size<1 || a.size>536870912 || (a.mode&0o777)!==0o755 || !unchanged(a,fs.lstatSync(target))) throw Error('invalid exact runtime executable'); identities.push([target,a]);
            if(health()) sharedModules(); checkIdentity();
          }
          function prepare() {
            process.umask(0o077); tmpIdentity=realDir('/tmp'); tmpFd=fs.openSync('/tmp',fs.constants.O_RDONLY|fs.constants.O_DIRECTORY|fs.constants.O_NOFOLLOW); descriptors.push(tmpFd);
            if(!same(tmpIdentity,fs.fstatSync(tmpFd))) throw Error('private temp parent replaced');
            root=fs.mkdtempSync('/tmp/dps-'); rootIdentity=realDir(root); rootFd=fs.openSync(root,fs.constants.O_RDONLY|fs.constants.O_DIRECTORY|fs.constants.O_NOFOLLOW); descriptors.push(rootFd); checkRoot();
            if((rootIdentity.mode&0o777)!==0o700) throw Error('private mode is not 0700');
            const directory=name=>{ checkRoot(); fs.mkdirSync('/proc/self/fd/'+rootFd+'/'+name,{mode:0o700}); const p=root+'/'+name,a=realDir(p); if((a.mode&0o777)!==0o700) throw Error('private directory mode changed'); privateDirs.push([p,a]); return p; };
            const tmp=directory('tmp'), logs=directory('logs');
            const env={LANG:'C.UTF-8',LC_ALL:'C.UTF-8',TERM:'dumb',TMPDIR:tmp,POWERSHELL_TELEMETRY_OPTOUT:'1',POWERSHELL_UPDATECHECK:'Off',DOTNET_EnableDiagnostics:'0'};
            let cwd=process.cwd();
            if(health()) { env.HOME=directory('home'); env.PATH=''; env.XDG_CONFIG_HOME=directory('config'); env.XDG_DATA_HOME=directory('data'); env.XDG_CACHE_HOME=directory('cache'); env.XDG_STATE_HOME=directory('state'); env.XDG_RUNTIME_DIR=directory('run'); cwd=directory('work'); }
            else { for(const key of ['HOME','PATH','PSModulePath','XDG_CONFIG_HOME','XDG_DATA_HOME','XDG_CACHE_HOME','XDG_STATE_HOME','XDG_RUNTIME_DIR']) if(own(process.env,key)) { const value=process.env[key]; if(value.length>8192 || /[\x00-\x1f\x7f]/.test(value)) throw Error('invalid editor context'); env[key]=value; } if(!absolute(env.HOME) || !absolute(cwd)) throw Error('editor HOME/cwd missing'); }
            const lease=process.env.DROIDE_PROCESS_LEASE; if(lease) { if(!/^[0-9a-f]{20}${'$'}/.test(lease)) throw Error('invalid lease'); env.DROIDE_PROCESS_LEASE=lease; }
            const name=randomBytes(8).toString('hex'); endpoint=endpointFor(tmp,name);
            for(const p of [endpoint,root+'/session.json']) { try { fs.lstatSync(p); throw Error('owned sidecar/endpoint leaf exists'); } catch(e) { if(e.code!=='ENOENT') throw e; } }
            checkRoot(); checkIdentity(); if(health()) checkShared();
            return {cwd,env,args:mode==='runtime-health'?['-NoLogo','-NoProfile','-NonInteractive','-Version']:nativeArgs(base+'/payload/tree',name,root+'/session.json',logs)};
          }
          function scanPrivate(remove=false) {
            if(root===null) return; let entries=0,bytes=0; const deadline=performance.now()+2000;
            if(endpoint) { try { const a=fs.lstatSync(endpoint); if(!a.isSocket() || a.isSymbolicLink() || endpointIdentity && !same(a,endpointIdentity)) throw Error('owned endpoint type/identity changed'); if(!endpointIdentity) endpointIdentity=a; } catch(e) { if(e.code!=='ENOENT') throw e; } }
            function walk(p,depth,expected) {
              if(++entries>4096 || depth>32 || bytes>67108864 || performance.now()>deadline) throw Error('private state bound exceeded'); checkRoot();
              const a=fs.lstatSync(p); if(!same(a,expected)) throw Error('private entry replaced');
              if(a.isSymbolicLink()) { if(remove) fs.unlinkSync(p); return; }
              if(a.isFile()) { bytes+=a.size; if(bytes>67108864) throw Error('private state byte bound exceeded'); if(remove) { if(!unchanged(a,fs.lstatSync(p))) throw Error('private file changed'); fs.unlinkSync(p); } return; }
              if(a.isSocket()) { if(!endpointIdentity || !same(a,endpointIdentity) || path.basename(p)!==path.basename(endpoint)) throw Error('unowned private socket'); if(remove) fs.unlinkSync(p); return; }
              if(!a.isDirectory()) throw Error('private special file');
              const fd=fs.openSync(p,fs.constants.O_RDONLY|fs.constants.O_DIRECTORY|fs.constants.O_NOFOLLOW); let dir;
              try { if(!same(a,fs.fstatSync(fd))) throw Error('private directory replaced'); dir=fs.opendirSync('/proc/self/fd/'+fd); for(;;) { const entry=dir.readSync(); if(!entry) break; const child='/proc/self/fd/'+fd+'/'+entry.name; walk(child,depth+1,fs.lstatSync(child)); } }
              finally { if(dir) dir.closeSync(); fs.closeSync(fd); }
              if(remove) { if(!same(a,fs.lstatSync(p))) throw Error('private directory replaced'); fs.rmdirSync(p); const index=privateDirs.findIndex(([,saved])=>same(saved,a)); if(index!==-1) privateDirs.splice(index,1); }
            }
            walk('/proc/self/fd/'+tmpFd+'/'+path.basename(root),0,rootIdentity); if(remove) root=null;
          }
          function groupAlive() { if(!owner || !owner.child.pid || owner.groupGone) return false; try { process.kill(-owner.child.pid,0); return true; } catch(e) { if(e.code==='ESRCH') { owner.groupGone=true; return false; } throw e; } }
          function signalGroup(signal) { if(!groupAlive()) return; try { process.kill(-owner.child.pid,signal); } catch(e) { if(e.code!=='ESRCH') throw e; owner.groupGone=true; } }
          async function stop() { if(!owner) return; if(!owner.closed || groupAlive()) { if(health()) {finishCode=1;console.error('PowerShell: health required forced owned child/group teardown');} signalGroup('SIGTERM'); await delay(200); if(!owner.closed || groupAlive()) signalGroup('SIGKILL'); } const deadline=performance.now()+7800; while(!owner.closed || groupAlive()) { if(performance.now()>deadline) throw Error('owned child/group not reaped'); await delay(20); } }
          async function finish(code,reason) {
            if(code!==0) finishCode=code; if(finishing) return; finishing=true;
            clearTimeout(workTimer); clearTimeout(phaseTimer); clearInterval(watchTimer); process.stdin.pause(); if(socket) socket.destroy();
            if(reason) console.error('PowerShell: '+reason);
            try { await stop(); checkIdentity(); if(health()) checkShared(); scanPrivate(true); }
            catch(e) { finishCode=1; console.error('PowerShell: '+e.message+'; owned residue '+String(root)); }
            for(const fd of descriptors) try { fs.closeSync(fd); } catch(_) {}
            if(health() && performance.now()-started>55000) finishCode=1;
            if(health() && finishCode===0) console.log(mode==='runtime-health'?'PowerShell 7.6.7 managed startup/version OK':'PSES controlled zero-document initialize/getVersion/shutdown OK; document semantics untested');
            process.exit(finishCode);
          }
          const fail=e=>{ void finish(1, e instanceof Error?e.message:String(e)); };
          function send(message, flushed) { const body=Buffer.from(JSON.stringify(message),'utf8'); if(!socket || socket.destroyed || socket.writableLength>1048576) throw Error('health transport unavailable'); socket.write(Buffer.concat([Buffer.from('Content-Length: '+body.length+'\r\n\r\n','ascii'),body]),flushed); }
          function deadline(ms) { clearTimeout(phaseTimer); const left=45000-(performance.now()-started); if(left<=0) throw Error('health work deadline'); phaseTimer=setTimeout(()=>fail('health '+phase+' deadline'),Math.min(ms,left)); }
          function handle(message) {
            if(!object(message) || message.jsonrpc!=='2.0') throw Error('invalid JSON-RPC envelope');
            if(own(message,'method')) {
              if(typeof message.method!=='string' || message.method.length>200 || own(message,'result') || own(message,'error')) throw Error('invalid server method');
              if(own(message,'id')) {
                if(!(typeof message.id==='string' && message.id.length<=256 || Number.isSafeInteger(message.id))) throw Error('invalid server request ID');
                if(message.method==='workspace/configuration') { const p=message.params; if(!object(p) || !Array.isArray(p.items) || p.items.length>32 || p.items.some(i=>!object(i) || Object.keys(i).some(k=>!['section','scopeUri'].includes(k)) || own(i,'section') && typeof i.section!=='string' || own(i,'scopeUri') && typeof i.scopeUri!=='string')) { send({jsonrpc:'2.0',id:message.id,error:{code:-32602,message:'Invalid health configuration request'}},()=>fail('invalid configuration')); return; } send({jsonrpc:'2.0',id:message.id,result:p.items.map(()=>null)}); return; }
                if(message.method==='powerShell/showInputPrompt' || message.method==='powerShell/showChoicePrompt') { send({jsonrpc:'2.0',id:message.id,result:{promptCancelled:true,responseText:null}},()=>fail('unexpected prompt during zero-document health')); return; }
                send({jsonrpc:'2.0',id:message.id,error:{code:-32601,message:'Method not supported by installer health client'}},()=>fail('unexpected server request during health')); return;
              }
              if(!['window/logMessage','telemetry/event','${'$'}/logTrace','powerShell/executionStatusChanged'].includes(message.method)) throw Error('unexpected server notification');
              return;
            }
            const expected=phase==='initialize'?1:phase==='version'?2:phase==='shutdown'?3:null;
            if(Object.keys(message).sort().join(',')!=='id,jsonrpc,result' || message.id!==expected || expected===null) throw Error('unexpected response envelope/ID/phase');
            if(phase==='initialize') { capabilities(message.result); send({jsonrpc:'2.0',method:'initialized',params:{}}); phase='version'; deadline(10000); send({jsonrpc:'2.0',id:2,method:'powerShell/getVersion',params:{}}); }
            else if(phase==='version') { const v=message.result; if(!object(v) || Object.keys(v).sort().join(',')!=='architecture,commit,edition,version' || v.version!=='7.6.7' || v.edition!=='Core' || v.commit!=='7.6.7' || v.architecture!=='Arm64') throw Error('runtime host version mismatch'); phase='shutdown'; deadline(3000); send({jsonrpc:'2.0',id:3,method:'shutdown'}); }
            else { if(message.result!==null) throw Error('shutdown result must be null'); phase='exit'; deadline(8000); send({jsonrpc:'2.0',method:'exit'}); socket.end(()=>{exitFlushed=true;}); }
          }
          async function connect() {
            const until=Math.min(started+45000,performance.now()+10000); let detail=null;
            while(!finishing) {
              if(owner.closed) throw Error('PSES exited before pipe connection'); if(performance.now()>until) throw Error('PSES sidecar/pipe deadline'); checkRoot(); checkIdentity();
              try { const bytes=read(root+'/session.json',16384,true,false); if(bytes.length===0) { await delay(25); continue; } const text=decode.decode(bytes); try { JSON.parse(text); } catch(_) { await delay(25); continue; } detail=strictJson(text); sidecar(detail,endpoint); }
              catch(e) { if(e.code==='ENOENT') { await delay(25); continue; } throw e; }
              try { const a=fs.lstatSync(endpoint); if(!a.isSocket() || a.isSymbolicLink()) throw Error('owned endpoint is not a socket'); if(endpointIdentity && !same(endpointIdentity,a)) throw Error('owned endpoint replaced'); endpointIdentity=a; }
              catch(e) { if(e.code==='ENOENT') { await delay(25); continue; } throw e; }
              break;
            }
            if(finishing) return;
            socket=net.createConnection({path:endpoint}); // One exact connection; never trust another sidecar address or reconnect.
            socket.on('error',fail); socket.on('end',()=>{peerEnded=true; if(mode==='editor') process.stdout.end(); else if(phase!=='exit') fail('premature native pipe EOF'); });
            socket.on('close',()=>{ if(!finishing && mode==='editor' && !owner.closed) { phaseTimer=setTimeout(()=>fail('native pipe disconnected'),1000); } });
            await new Promise((resolve,reject)=>{ const timer=setTimeout(()=>reject(Error('pipe connect deadline')),Math.max(1,until-performance.now())); socket.once('connect',()=>{clearTimeout(timer);resolve();}); socket.once('error',e=>{clearTimeout(timer);reject(e);}); });
            checkRoot(); if(!same(fs.lstatSync(endpoint),endpointIdentity)) throw Error('endpoint replaced at connect');
            if(mode==='editor') {
              socket.on('data',()=>{if(process.stdout.writableLength>1048576) fail('relay output queue exceeded');});
              process.stdin.on('data',()=>{if(socket.writableLength>1048576) fail('relay input queue exceeded');});
              process.stdin.on('end',()=>{ socket.end(); phaseTimer=setTimeout(()=>fail('editor input closed without native exit'),8000); }); process.stdin.on('error',fail); process.stdout.on('error',fail);
              process.stdin.pipe(socket); socket.pipe(process.stdout,{end:false}); process.stdin.resume();
            } else { frames=new Frames(handle); socket.on('data',chunk=>{try {frames.receive(chunk);}catch(e){fail(e);}}); deadline(25000); send({jsonrpc:'2.0',id:1,method:'initialize',params:{processId:process.pid,rootUri:null,workspaceFolders:[],capabilities:{general:{positionEncodings:['utf-16']},workspace:{configuration:true,workspaceFolders:false},textDocument:{synchronization:{dynamicRegistration:false},completion:{completionItem:{snippetSupport:true}}}},initializationOptions:{enableProfileLoading:false,initialWorkingDirectory:owner.cwd}}}); }
          }
          for(const signal of ['SIGTERM','SIGINT','SIGHUP']) process.on(signal,()=>fail('supervisor canceled'));
          process.stdin.pause(); process.stdout.on('error',fail); process.stderr.on('error',()=>{void finish(1,null);});
          try {
            preflight(); const prepared=prepare(); if(health() && performance.now()-started>=45000) throw Error('health preparation deadline');
            const child=spawn(target,prepared.args,{cwd:prepared.cwd,env:prepared.env,shell:false,detached:true,stdio:['ignore','pipe','pipe']});
            owner={child,cwd:prepared.cwd,closed:false,error:null,groupGone:false};
            child.once('error',e=>{owner.error=e;fail('cannot start exact runtime');});
            child.stdout.on('error',fail); child.stderr.on('error',fail);
            child.stdout.on('data',chunk=>{stdoutBytes+=chunk.length;if(stdoutBytes>4194304) return fail('native console output bound'); if(mode==='runtime-health') {if(stdoutBytes>1024) return fail('version output bound');runtimeOutput=Buffer.concat([runtimeOutput,chunk]);}});
            child.stderr.on('data',chunk=>{stderrBytes+=chunk.length;if(stderrBytes>65536) fail('native stderr bound');});
            child.once('close',(code,signal)=>{owner.closed=true;if(finishing)return;
              if(mode==='runtime-health') {try {const text=decode.decode(runtimeOutput);void finish(!owner.error && code===0 && signal===null && stderrBytes===0 && /^PowerShell 7\.6\.7\r?\n${'$'}/.test(text)?0:1,'runtime version health completed');} catch(e) {fail(e);}}
              else if(mode==='health') void finish(!owner.error && code===0 && signal===null && phase==='exit' && exitFlushed && peerEnded && frames && frames.complete()?0:1,'PSES lifecycle completed');
              else void finish(!owner.error && code===0 && signal===null?0:1,code===0&&signal===null?null:'native editor exited unexpectedly');
            });
            if(health()) workTimer=setTimeout(()=>fail('health work deadline'),Math.max(1,45000-(performance.now()-started)));
            watchTimer=setInterval(()=>{try{checkRoot();checkIdentity();scanPrivate(false);}catch(e){fail(e);}},250);
            if(mode!=='runtime-health') await connect();
          } catch(e) {await finish(1,e.message);}
        }
        module.exports={strictJson,endpointFor,nativeArgs,sidecar,capabilities,Frames};
        if(require.main===module) void run();
    """.trimIndent() + "\n"
}
