package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.*

/** Public-bin guard and receipt consistency. Only the package-gated app seam issues managed ownership. */
internal object LocalUbuntuSvelteLaunchPolicy {
    const val REVISION = "svelte-managed-public-bin-launch-v1"
    const val FILE_NAME = "droide-svelte-launch.cjs"
    const val RELATIVE_PATH = "payload/npm/$FILE_NAME"
    const val AUTHORITY_FILE = "SVELTE_LAUNCH_AUTHORITY.json"
    const val HEALTH_PATH = "payload/svelte-lsp-health.cjs"
    val script: String by lazy { """'use strict';
// Authored guard. Adjacent records are consistency evidence, never application ownership.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const C = @CLOSURE@;
const keyStats = new Map(), started = process.hrtime.bigint();
function check(v) { if (!v) throw Error('Svelte managed preflight rejected'); }
function budget() { check(Number(process.hrtime.bigint()-started)/1e6<20000); }
function absolute(p) { return typeof p==='string'&&p.startsWith('/')&&p.length<=4096&&!/[\x00-\x1f\x7f\\]/.test(p)&&p.split('/').slice(1).every(x=>x&&x!=='.'&&x!=='..'); }
function relative(p) { check(typeof p==='string'&&p.length>0&&p.length<=4096&&!p.startsWith('/')&&!/[\x00-\x1f\x7f\\]/.test(p)&&p.split('/').every(x=>x&&x!=='.'&&x!=='..')); }
function stamp(s) { return [s.dev,s.ino,s.size,s.mode,s.mtimeNs,s.ctimeNs,s.nlink].map(String).join(':'); }
function parents(p) { check(absolute(p)); let q=path.dirname(p);while(true){const s=fs.lstatSync(q);check(s.isDirectory()&&!s.isSymbolicLink());if(q==='/')break;q=path.dirname(q);} }
function regular(p,limit) { budget();parents(p);const s=fs.lstatSync(p,{bigint:true});check(s.isFile()&&!s.isSymbolicLink()&&s.nlink===1n&&s.size>0n&&s.size<=BigInt(limit));const fd=fs.openSync(p,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW);try{check(stamp(s)===stamp(fs.fstatSync(fd,{bigint:true})));const b=fs.readFileSync(fd);check(b.length<=limit&&stamp(s)===stamp(fs.fstatSync(fd,{bigint:true}))&&stamp(s)===stamp(fs.lstatSync(p,{bigint:true})));keyStats.set(p,stamp(s));return b;}finally{fs.closeSync(fd);} }
function hash(b){return crypto.createHash('sha256').update(b).digest('hex');}
function canonical(v){if(Array.isArray(v))return '['+v.map(canonical).join(',')+']';if(v&&typeof v==='object')return '{'+Object.keys(v).sort().map(k=>JSON.stringify(k)+':'+canonical(v[k])).join(',')+'}';return JSON.stringify(v);}
function equal(a,b){return canonical(a)===canonical(b);}
function keys(o,ks){check(o&&typeof o==='object'&&!Array.isArray(o)&&equal(Object.keys(o).sort(),ks.slice().sort()));}
function strict(b){check(b.length>0&&b.length<=8000000);const s=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(b);let i=0,count=0;function ws(){while(i<s.length&&/[ \t\r\n]/.test(s[i]))i++;}function str(){ws();const a=i;check(s[i++]==='"');while(i<s.length){const c=s[i++];if(c==='"')return JSON.parse(s.slice(a,i));check(c.charCodeAt(0)>=32);if(c==='\\'){check(i<s.length);const e=s[i++];check('"\\/bfnrtu'.includes(e));if(e==='u'){check(/^[a-fA-F0-9]{4}${'$'}/.test(s.slice(i,i+4)));i+=4;}}}throw Error('JSON string');}function val(d){check(d<=64&&++count<=100000);ws();check(i<s.length);if(s[i]==='{'){i++;const seen=new Set();ws();if(s[i]==='}'){i++;return;}while(true){const k=str();check(!seen.has(k));seen.add(k);ws();check(s[i++]===':');val(d+1);ws();if(s[i]==='}'){i++;return;}check(s[i++]===',');}}else if(s[i]==='['){i++;ws();if(s[i]===']'){i++;return;}while(true){val(d+1);ws();if(s[i]===']'){i++;return;}check(s[i++]===',');}}else if(s[i]==='"')str();else{const a=i;while(i<s.length&&!/[,\]} \t\r\n]/.test(s[i]))i++;check(/^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)${'$'}/.test(s.slice(a,i)));}}val(0);ws();check(i===s.length);const o=JSON.parse(s);check(o&&typeof o==='object'&&!Array.isArray(o));return o;}
function edges(id,a){const e=C.artifacts[id];for(const k of ['dependencies','peerDependencies','peerDependenciesMeta'])check(equal(a[k]||{},e[k]));check(equal(a.engines,e.engines===null?undefined:e.engines));for(const k of ['optionalDependencies','bundledDependencies','bundleDependencies','os','cpu','libc','gypfile','workspaces','resolutions','overrides'])check(!(k in a));}
function slot(p){relative(p);const parts=p.split('/');let i=0,d=0,k;const ancestors=[];while(i<parts.length){check(parts[i++]==='node_modules'&&i<parts.length);k=parts[i++];if(k.startsWith('@')){check(/^@[a-z0-9][a-z0-9._-]*${'$'}/.test(k)&&i<parts.length);k+='/'+parts[i++];}check(/^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*${'$'}/.test(k)&&++d<=8);if(i<parts.length)ancestors.push(parts.slice(0,i).join('/'));}return {key:k,ancestors};}
function resolve(layout,p,k){while(true){if(path.posix.basename(p)!=='node_modules'){const q=(p?p+'/':'')+'node_modules/'+k;if(Object.hasOwn(layout,q))return q;}if(!p)return null;p=p.includes('/')?p.slice(0,p.lastIndexOf('/')):'';}}
function graph(layout){const required=Object.keys(C.artifacts).filter(k=>!C.artifacts[k].optional_audit_only),counts={},g={},resolutions=[];check(Object.keys(layout).length<=128);for(const[p,id]of Object.entries(layout)){const s=slot(p);check(s.ancestors.every(a=>Object.hasOwn(layout,a))&&required.includes(id));check(s.key===(id==='prettier-plugin-svelte@4.1.1'?'prettier-plugin-svelte4':C.artifacts[id].name));counts[id]=(counts[id]||0)+1;check(counts[id]<=4);}check(equal(Object.keys(counts).sort(),required.sort()));for(const[k,id]of Object.entries(C.physical_layout.fixed_root_slots))check(layout['node_modules/'+k]===id);for(const p of Object.keys(layout).sort()){g[p]=[];for(const e of C.logical_edges.filter(e=>e.parent===layout[p])){const target=resolve(layout,p,e.dependency_key);if(e.optional)check(target===null);else{check(target!==null&&layout[target]===e.target);if(e.kind==='peerDependency')check(!target.startsWith(p+'/node_modules/'));g[p].push(target);}resolutions.push({parent:p,key:e.dependency_key,kind:e.kind,target});}}const seen=new Set(),todo=Object.keys(C.physical_layout.fixed_root_slots).map(k=>'node_modules/'+k);while(todo.length){const p=todo.pop();if(seen.has(p))continue;seen.add(p);todo.push(...g[p]);}check(seen.size===Object.keys(layout).length);return {schema:1,contractSha256:'489274f2ce40755481037f0e780f71fcc65ef86c8c3857e3685cef11ef91fb0d',logicalSha256:'4e086cdafefcbde14468eee578b16a438022e7867269ac105e8cbfe8ba7c02bd',manifestSha256:hash(canonical(C.proposed_root_manifest)),placements:layout,resolutions,optionalProfile:'omit-both-with-exact-audit'};}
function layoutCheck(root,lockBytes){const npm=root+'/payload/npm',lock=strict(lockBytes),l={};check(lock.name==='droide-managed-svelte'&&lock.version==='1.0.0'&&lock.lockfileVersion===3);check(equal(strict(regular(npm+'/package.json',64000)),C.proposed_root_manifest));check(Object.keys(lock).every(k=>['name','version','lockfileVersion','requires','packages'].includes(k)));const r=lock.packages[''];keys(r,['name','version','dependencies']);check(r.name===lock.name&&r.version===lock.version&&equal(r.dependencies,C.proposed_root_manifest.dependencies));for(const[p,a]of Object.entries(lock.packages)){if(!p)continue;const s=slot(p),name=s.key==='prettier-plugin-svelte4'?'prettier-plugin-svelte':s.key,id=name+'@'+a.version,e=C.artifacts[id];check(e&&!e.optional_audit_only);check((a.name===undefined||a.name===name)&&(s.key!=='prettier-plugin-svelte4'||a.name===name));check(a.resolved===e.tarball&&a.integrity===e.integrity);check(Object.keys(a).every(k=>['name','version','resolved','integrity','license','engines','dependencies','peerDependencies','peerDependenciesMeta','bin','dev','optional','peer','devOptional','hasInstallScript','link','inBundle'].includes(k)));edges(id,a);check(equal(a.bin,e.bin===null?undefined:e.bin));for(const f of ['dev','optional','devOptional','hasInstallScript','link','inBundle'])check(a[f]===undefined||a[f]===false);l[p]=id;}const proof=graph(l),seen=new Set(),bins={},observedBins=[];let n=0,total=0;function walk(dir,d){budget();check(d<=64&&++n<=40000);const ds=fs.lstatSync(dir);check(ds.isDirectory()&&!ds.isSymbolicLink());for(const name of fs.readdirSync(dir)){check(++n<=40000);const p=dir+'/'+name,rel=path.relative(npm,p),s=fs.lstatSync(p,{bigint:true});relative(rel);if(s.isDirectory()){const parent=path.basename(dir);if(name==='node_modules')check(p===npm+'/node_modules'||Object.hasOwn(l,path.relative(npm,dir)));if(parent==='node_modules'&&name.startsWith('.'))check(name==='.bin');if(parent==='node_modules'&&name.startsWith('@'))check(fs.readdirSync(p).length>0);const isPackage=(parent==='node_modules'&&!name.startsWith('@')&&name!=='.bin')||(parent.startsWith('@')&&path.basename(path.dirname(dir))==='node_modules');if(isPackage){check(Object.hasOwn(l,rel)&&!seen.has(rel));seen.add(rel);const a=strict(regular(p+'/package.json',1000000)),e=C.artifacts[l[rel]];for(const k of ['name','version','bin','main','exports','type','scripts'])check(equal(a[k],e[k]===null?undefined:e[k]));edges(l[rel],a);for(const k of ['preinstall','install','postinstall'])check(!Object.hasOwn(a.scripts||{},k));for(const[k,v]of Object.entries(a.bin||{})){relative(v.replace(/^\.\//,''));const mod=rel.slice(0,rel.lastIndexOf('node_modules/'))+'node_modules',b=mod+'/.bin/'+k,t=rel+'/'+v.replace(/^\.\//,'');check(bins[b]===undefined||bins[b]===t);bins[b]=t;}}walk(p,d+1);}else if(s.isSymbolicLink()){check(path.basename(dir)==='.bin'&&path.basename(path.dirname(dir))==='node_modules');observedBins.push(rel);}else{check(s.isFile()&&s.nlink===1n);total+=Number(s.size);check(total<=1500000000);check(name!=='binding.gyp'&&!/\.(?:node|wasm|dll|so|dylib|exe)${'$'}/i.test(name));if(path.basename(dir)==='node_modules')check(p===npm+'/node_modules/.package-lock.json');const fd=fs.openSync(p,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW);try{const h=Buffer.alloc(4);fs.readSync(fd,h,0,4,0);check(!['7f454c46','0061736d','feedface','feedfacf','cefaedfe','cffaedfe','cafebabe'].includes(h.toString('hex'))&&h.subarray(0,2).toString()!=='MZ');}finally{fs.closeSync(fd);}}}}walk(npm+'/node_modules',0);check(equal([...seen].sort(),Object.keys(l).sort()));for(const b of observedBins){check(Object.hasOwn(bins,b));const link=fs.readlinkSync(npm+'/'+b);check(!path.isAbsolute(link)&&path.resolve(npm,path.dirname(b),link)===path.resolve(npm,bins[b]));regular(npm+'/'+bins[b],8000000);}const hidden=npm+'/node_modules/.package-lock.json';if(fs.existsSync(hidden)){const h=strict(regular(hidden,8000000)),pk={...lock.packages};delete pk[''];check(h.lockfileVersion===3&&h.name===lock.name&&h.version===lock.version&&equal(h.packages,pk));}regular(npm+'/node_modules/svelte-language-server/bin/server.js',1000000);proof.lockSha256=hash(lockBytes);proof.layoutSha256=hash(canonical(l));check(equal(strict(regular(root+'/SVELTE_PHYSICAL_PROOF.json',1000000)),proof));return proof;}
function fullTree(root){const seal=strict(regular(root+'/NPM_TREE_SEAL.json',8000000));keys(seal,['schema','files','links']);check(seal.schema===1);const files={},links={};let count=0,total=0;function walk(dir,d){budget();check(d<=64);for(const name of fs.readdirSync(dir)){const p=dir+'/'+name,rel=path.relative(root,p);if(rel==='NPM_TREE_SEAL.json')continue;relative(rel);check(++count<=40000);const s=fs.lstatSync(p,{bigint:true});if(s.isSymbolicLink()){const target=fs.readlinkSync(p);check(!path.isAbsolute(target)&&fs.realpathSync(p).startsWith(root+'/'));links[rel]=target;}else if(s.isDirectory())walk(p,d+1);else{check(s.isFile()&&s.nlink===1n);total+=Number(s.size);check(total<=1500000000);const fd=fs.openSync(p,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW),h=crypto.createHash('sha256'),buf=Buffer.alloc(65536);try{check(stamp(s)===stamp(fs.fstatSync(fd,{bigint:true})));let n;while((n=fs.readSync(fd,buf,0,buf.length,null))>0){budget();h.update(buf.subarray(0,n));}check(stamp(s)===stamp(fs.fstatSync(fd,{bigint:true})));}finally{fs.closeSync(fd);}files[rel]=h.digest('hex');}}}walk(root,0);check(equal(files,seal.files)&&equal(links,seal.links));}
function inspect(health=false){check(process.execArgv.length===0&&absolute(__filename)&&__filename.endsWith('/payload/npm/droide-svelte-launch.cjs'));const root=__filename.slice(0,-'/payload/npm/droide-svelte-launch.cjs'.length),a=strict(regular(root+'/SVELTE_LAUNCH_AUTHORITY.json',64000));keys(a,['schema','familyId','version','policyRevision','closureContractSha256','finalGuestRoot','stageGuestRoot','activationId','nodeCommand','nodeVersion','nodeDependencyKeyOrNull','launcherSha256','healthSourceSha256','lockSha256','physicalProofSha256','manifestSha256']);check(a.schema===1&&a.familyId==='lsp.svelte-language-server'&&a.version==='0.18.5'&&a.policyRevision==='svelte-managed-public-bin-launch-v1'&&a.closureContractSha256==='489274f2ce40755481037f0e780f71fcc65ef86c8c3857e3685cef11ef91fb0d');check(absolute(a.finalGuestRoot)&&absolute(a.stageGuestRoot)&&a.finalGuestRoot!==a.stageGuestRoot&&[a.finalGuestRoot,a.stageGuestRoot].includes(root));check(a.nodeCommand===process.execPath&&a.nodeVersion===process.versions.node&&Number(a.nodeVersion.split('.')[0])>=22&&process.platform==='linux'&&process.arch==='arm64'&&a.nodeDependencyKeyOrNull==='runtime.node@'+a.nodeVersion);check(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}${'$'}/.test(a.activationId)&&regular(root+'/DROIDE_ACTIVATION_ID',100).toString('utf8')===a.activationId);const r=strict(regular(root+'/RECIPE_LOCK.json',64000));keys(r,['schema','familyId','version','packageName','packageVersion','registryIntegrity','nodeMajor','npmVersion','provenanceUrl','nodeCommand','additionalDependencies','allowLifecycleScripts','launchProfile']);check(r.schema===1&&r.familyId===a.familyId&&r.version===a.version&&r.packageName==='svelte-language-server'&&r.packageVersion===a.version&&r.registryIntegrity===C.artifacts['svelte-language-server@0.18.5'].integrity&&r.provenanceUrl==='https://github.com/sveltejs/language-tools'&&r.nodeCommand===a.nodeCommand&&r.nodeMajor===Number(a.nodeVersion.split('.')[0])&&/^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?${'$'}/.test(r.npmVersion)&&Number(r.npmVersion.split('.')[0])>=10&&equal(r.additionalDependencies,C.recipe_additional_dependencies)&&r.allowLifecycleScripts===false);const profile={revision:a.policyRevision,closureContractSha256:a.closureContractSha256,launcherRelativePath:'payload/npm/droide-svelte-launch.cjs',launcherSha256:a.launcherSha256,healthRelativePath:'payload/svelte-lsp-health.cjs',healthRevision:'svelte-zero-document-private-health-v1',healthContractSha256:'3a7e3a1a171bdd4a31abec2b505fa51e075ecf84bcdc9be208676b593928c516',healthSourceSha256:a.healthSourceSha256,nodeCommand:a.nodeCommand,nodeVersion:a.nodeVersion,nodeDependencyKeyOrNull:a.nodeDependencyKeyOrNull,manifestSha256:a.manifestSha256};check(equal(r.launchProfile,profile));check(hash(regular(__filename,1000000))===a.launcherSha256&&hash(regular(root+'/payload/svelte-lsp-health.cjs',1000000))===a.healthSourceSha256);const lockBytes=regular(root+'/payload/npm/package-lock.json',8000000);check(hash(lockBytes)===a.lockSha256);layoutCheck(root,lockBytes);check(hash(regular(root+'/SVELTE_PHYSICAL_PROOF.json',1000000))===a.physicalProofSha256&&hash(canonical(C.proposed_root_manifest))===a.manifestSha256);if(root===a.stageGuestRoot){let absent=false;try{fs.lstatSync(root+'/NPM_TREE_SEAL.json');}catch(e){if(e.code==='ENOENT')absent=true;else throw e;}check(health&&absent);}else fullTree(root);for(const[p,id]of keyStats){parents(p);check(stamp(fs.lstatSync(p,{bigint:true}))===id);}return {root,authority:a,bin:root+'/payload/npm/node_modules/svelte-language-server/bin/server.js'};}
module.exports = { inspect };
if(require.main===module){try{check(equal(process.argv.slice(2),['--stdio']));const c=inspect(false);const child=spawn(c.authority.nodeCommand,[c.bin,'--stdio'],{cwd:process.cwd(),env:process.env,stdio:'inherit'});child.on('error',()=>{process.exitCode=1;});child.on('exit',(code,signal)=>{process.exitCode=signal?1:(code===null?1:code);});for(const signal of ['SIGINT','SIGTERM','SIGHUP'])process.on(signal,()=>{if(child.exitCode===null&&child.signalCode===null)child.kill(signal);});}catch(_){process.stderr.write('Svelte managed preflight failed\n');process.exitCode=1;}}
""".replace("@CLOSURE@", LocalUbuntuSveltePolicy.closureJson) }
    val SOURCE_SHA256: String by lazy { LocalUbuntuSveltePolicy.hash(script) }

    fun launchProfile(nodeCommand: String, nodeVersion: String, nodeDependencyKey: String?): JsonObject {
        requireRuntime(nodeCommand, nodeVersion, nodeDependencyKey)
        return buildJsonObject {
            put("revision", REVISION); put("closureContractSha256", LocalUbuntuSveltePolicy.CONTRACT_SHA256)
            put("launcherRelativePath", RELATIVE_PATH); put("launcherSha256", SOURCE_SHA256)
            put("healthRelativePath", HEALTH_PATH); put("healthRevision", LocalUbuntuSvelteLspHealth.REVISION)
            put("healthContractSha256", LocalUbuntuSvelteLspHealth.CONTRACT_SHA256); put("healthSourceSha256", LocalUbuntuSvelteLspHealth.SOURCE_SHA256)
            put("nodeCommand", nodeCommand); put("nodeVersion", nodeVersion)
            put("nodeDependencyKeyOrNull", nodeDependencyKey?.let(::JsonPrimitive) ?: JsonNull)
            put("manifestSha256", LocalUbuntuSveltePolicy.manifestSha256)
        }
    }

    fun requireRuntime(command: String, version: String, dependency: String?) {
        requireAbsolute(command)
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) && version.substringBefore('.').toInt() >= 22)
        require(dependency == "runtime.node@$version")
    }

    fun profileMatches(review: JsonObject, command: String, version: String, dependency: String?): Boolean = runCatching {
        review["launchProfile"] == launchProfile(command, version, dependency)
    }.getOrDefault(false)

    fun validateReview(review: JsonObject, dependency: String? = null): JsonObject {
        val expectedKeys = setOf("schema", "familyId", "version", "packageName", "packageVersion", "registryIntegrity", "nodeMajor", "npmVersion", "provenanceUrl", "nodeCommand", "additionalDependencies", "allowLifecycleScripts", "launchProfile")
        require(review.keys == expectedKeys)
        require(review["schema"] == JsonPrimitive(1) && review["familyId"] == JsonPrimitive(LocalUbuntuSveltePolicy.FAMILY_ID) &&
            review["version"] == JsonPrimitive(LocalUbuntuSveltePolicy.VERSION) && review["packageName"] == JsonPrimitive(LocalUbuntuSveltePolicy.PACKAGE) &&
            review["packageVersion"] == JsonPrimitive(LocalUbuntuSveltePolicy.VERSION) && review["registryIntegrity"] == JsonPrimitive(LocalUbuntuSveltePolicy.INTEGRITY) &&
            review["provenanceUrl"] == JsonPrimitive(LocalUbuntuSveltePolicy.PROVENANCE) && review["allowLifecycleScripts"] == JsonPrimitive(false) &&
            review["additionalDependencies"] == LocalUbuntuSveltePolicy.contract.getValue("recipe_additional_dependencies"))
        val profile = review.getValue("launchProfile").jsonObject
        val command = string(profile, "nodeCommand"); val version = string(profile, "nodeVersion")
        val key = profile["nodeDependencyKeyOrNull"]?.let { if (it == JsonNull) null else it.jsonPrimitive.takeIf(JsonPrimitive::isString)?.content ?: error("Invalid Node identity") }
        require(profile.containsKey("nodeDependencyKeyOrNull") && key == dependency && profileMatches(review, command, version, key))
        require(review["nodeCommand"] == JsonPrimitive(command) && review["nodeMajor"] == JsonPrimitive(version.substringBefore('.').toInt()))
        require(string(review, "npmVersion").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")) && string(review, "npmVersion").substringBefore('.').toInt() >= 10)
        return profile
    }

    fun authority(stageGuest: String, finalGuest: String, activation: String, nodeCommand: String, nodeVersion: String,
        dependency: String?, lockSha256: String, physicalProofSha256: String): JsonObject {
        requireAbsolute(stageGuest); requireAbsolute(finalGuest); require(stageGuest != finalGuest)
        require(activation.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) && lockSha256.matches(Regex("[0-9a-f]{64}")))
        requireRuntime(nodeCommand, nodeVersion, dependency)
        return buildJsonObject {
            put("schema", 1); put("familyId", LocalUbuntuSveltePolicy.FAMILY_ID); put("version", LocalUbuntuSveltePolicy.VERSION)
            put("policyRevision", REVISION); put("closureContractSha256", LocalUbuntuSveltePolicy.CONTRACT_SHA256)
            put("finalGuestRoot", finalGuest); put("stageGuestRoot", stageGuest); put("activationId", activation)
            put("nodeCommand", nodeCommand); put("nodeVersion", nodeVersion); put("nodeDependencyKeyOrNull", dependency?.let(::JsonPrimitive) ?: JsonNull)
            put("launcherSha256", SOURCE_SHA256); put("healthSourceSha256", LocalUbuntuSvelteLspHealth.SOURCE_SHA256); put("lockSha256", lockSha256)
            put("physicalProofSha256", physicalProofSha256); put("manifestSha256", LocalUbuntuSveltePolicy.manifestSha256)
        }
    }

    /** Create the empty private state before the first Node interpreter, even the health supervisor. */
    fun healthHandoff(node: String, guestRoot: String): String {
        requireAbsolute(node); requireAbsolute(guestRoot)
        return """
            set -eu
            umask 077
            lease="${'$'}{DROIDE_PROCESS_LEASE:-}"
            case "${'$'}lease" in *[!0-9a-f]*) exit 70;; esac
            [ "${'$'}{#lease}" -eq 20 ] || exit 70
            private=${'$'}(/usr/bin/mktemp -d /tmp/droide-svelte-health-XXXXXXXXXXXX)
            [ -d "${'$'}private" ] && [ ! -L "${'$'}private" ] || exit 70
            for name in workspace home tmp config cache data; do /bin/mkdir -m 700 "${'$'}private/${'$'}name"; done
            identity=${'$'}(/usr/bin/stat -c '%d:%i' "${'$'}private")
            cd "${'$'}private/workspace"
            exec /usr/bin/env -i "HOME=${'$'}private/home" "TMPDIR=${'$'}private/tmp" "XDG_CONFIG_HOME=${'$'}private/config" "XDG_CACHE_HOME=${'$'}private/cache" "XDG_DATA_HOME=${'$'}private/data" PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb "DROIDE_PROCESS_LEASE=${'$'}lease" ${q(node)} ${q("$guestRoot/$HEALTH_PATH")} "${'$'}private" "${'$'}identity"
        """.trimIndent() + "\n"
    }

    fun guestWrapper(node: String, finalGuest: String): String {
        requireAbsolute(node); requireAbsolute(finalGuest)
        return "#!/bin/sh\nset -eu\n[ \"${'$'}#\" -eq 1 ] || exit 64\ncase \"${'$'}1\" in\n--droide-health)\n" +
            healthHandoff(node, finalGuest) + ";;\n--stdio)\nunset NODE_OPTIONS NODE_PATH LD_PRELOAD LD_LIBRARY_PATH\n" +
            "exec ${q(node)} ${q("$finalGuest/$RELATIVE_PATH")} --stdio\n;;\n*) exit 64;;\nesac\n"
    }

    fun hostWrapper(ubuntuLauncher: String, finalGuest: String): String {
        requireAbsolute(ubuntuLauncher); requireAbsolute(finalGuest)
        return "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(ubuntuLauncher)} /bin/sh ${q("$finalGuest/payload/bin/${LocalUbuntuSveltePolicy.COMMAND}")} \"${'$'}@\"\n"
    }

    fun verifyProbe(root: File): Boolean = runCatching {
        LocalUbuntuSveltePolicy.readRegular(File(root, RELATIVE_PATH), 1_000_000) == script &&
            LocalUbuntuSveltePolicy.readRegular(File(root, HEALTH_PATH), 1_000_000) == LocalUbuntuSvelteLspHealth.script
    }.getOrDefault(false)

    suspend fun verifyIntegration(root: File, stageGuest: String, finalGuest: String, activation: String, dependency: String?, ubuntuLauncher: String): Boolean = runSuspendCatching {
        require(verifyProbe(root))
        val review = LocalUbuntuSveltePolicy.strictObject(LocalUbuntuSveltePolicy.readRegular(File(root, "RECIPE_LOCK.json"), 64_000))
        val profile = validateReview(review, dependency)
        val node = string(profile, "nodeCommand"); val version = string(profile, "nodeVersion")
        val lock = File(root, "payload/npm/package-lock.json")
        val actual = LocalUbuntuSveltePolicy.strictObject(LocalUbuntuSveltePolicy.readRegular(File(root, AUTHORITY_FILE), 64_000))
        require(actual == authority(stageGuest, finalGuest, activation, node, version, dependency, LocalUbuntuNpmPolicy.sha256(lock), LocalUbuntuNpmPolicy.sha256(File(root, LocalUbuntuSveltePolicy.PROOF_FILE))))
        require(LocalUbuntuSveltePolicy.readRegular(File(root, "DROIDE_ACTIVATION_ID"), 100) == activation)
        require(LocalUbuntuSveltePolicy.readRegular(File(root, "payload/bin/${LocalUbuntuSveltePolicy.COMMAND}"), 64_000) == guestWrapper(node, finalGuest))
        require(LocalUbuntuSveltePolicy.readRegular(File(root, "payload/host-bin/${LocalUbuntuSveltePolicy.COMMAND}"), 64_000) == hostWrapper(ubuntuLauncher, finalGuest))
        require(File(root, "payload/bin/${LocalUbuntuSveltePolicy.COMMAND}").canExecute() && File(root, "payload/host-bin/${LocalUbuntuSveltePolicy.COMMAND}").canExecute())
        require(LocalUbuntuSveltePolicy.strictObject(LocalUbuntuSveltePolicy.readRegular(File(root, LocalUbuntuSveltePolicy.PROOF_FILE), 1_000_000)) == LocalUbuntuSveltePolicy.validateInstalledLayout(root))
        true
    }.getOrDefault(false)

    private fun string(obj: JsonObject, key: String): String = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing Svelte $key")
    private fun requireAbsolute(value: String) { require(value.startsWith('/') && value.length <= 4096 && value.none { it.code < 32 || it.code == 127 || it == '\\' } && value.drop(1).split('/').none { it.isEmpty() || it == "." || it == ".." }) }
    private fun q(value: String) = LocalExecutionSubstrate.shellQuote(value)
}
