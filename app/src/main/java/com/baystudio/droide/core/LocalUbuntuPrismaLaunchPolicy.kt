package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*

/** Managed wrapper consistency. Durable ownership is still established by LocalManagedPackageAuthority. */
internal object LocalUbuntuPrismaLaunchPolicy {
    const val REVISION = "prisma-managed-public-root-launch-v1"
    const val FILE_NAME = "droide-prisma-legacy.cjs"
    const val RELATIVE_PATH = "payload/npm/$FILE_NAME"
    const val AUTHORITY_FILE = "PRISMA_LAUNCH_AUTHORITY.json"
    private const val HEALTH_PATH = "payload/prisma-lsp-health.cjs"
    val script: String by lazy { """'use strict';
// Local package consistency, not a signature or a same-UID security boundary.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const { Console } = require('node:console');
const C = @CLOSURE@;
const family = 'lsp.prisma-language-server', version = '31.13.0';
const revision = 'prisma-managed-public-root-launch-v1';
const closureHash = '6de1f7e4710d74447f40b56e700e2f6d72fec0e7daad211f72eaede4ef0d202d';
const healthRevision = 'prisma-zero-document-source-path-health-v1';
const healthContract = '6c2805fd4a874d03d89aaceb931c1ff13376ffab6284127cad1fd062fcb3356c';
const launcherRel = 'payload/npm/droide-prisma-legacy.cjs', helperRel = 'payload/prisma-lsp-health.cjs';
const keyStats = new Map();
function check(ok) { if (!ok) throw Error('Prisma package preflight rejected'); }
function absolute(p) { return typeof p === 'string' && p.startsWith('/') && p.length <= 4096 && !/[\x00-\x1f\x7f\\]/.test(p) && p.split('/').slice(1).every(x => x && x !== '.' && x !== '..'); }
function relative(p) { check(typeof p === 'string' && p.length > 0 && p.length <= 4096 && !p.startsWith('/') && !/[\x00-\x1f\x7f\\]/.test(p) && p.split('/').every(x => x && x !== '.' && x !== '..')); }
function identity(s) { return [s.dev,s.ino,s.size,s.mtimeNs,s.ctimeNs,s.mode].map(String).join(':'); }
function parents(p) { check(absolute(p)); let q=path.dirname(p); while (true) { const s=fs.lstatSync(q); check(s.isDirectory()&&!s.isSymbolicLink()); if(q==='/')break;q=path.dirname(q); } }
function regular(p, limit, retain=true) {
  parents(p); const before=fs.lstatSync(p,{bigint:true}); check(before.isFile()&&!before.isSymbolicLink()&&before.size>0n&&before.size<=BigInt(limit));
  const fd=fs.openSync(p,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW);
  try { check(identity(before)===identity(fs.fstatSync(fd,{bigint:true}))); const b=fs.readFileSync(fd); check(b.length<=limit&&identity(before)===identity(fs.fstatSync(fd,{bigint:true}))); if(retain)keyStats.set(p,identity(before)); return b; } finally { fs.closeSync(fd); }
}
function hash(b) { return crypto.createHash('sha256').update(b).digest('hex'); }
function strict(b, limit=8000000) {
  check(b.length>0&&b.length<=limit); const s=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(b); let i=0, count=0;
  function ws(){while(i<s.length&&/[ \t\r\n]/.test(s[i]))i++;}
  function str(){ws();const a=i;check(s[i++]==='"');while(i<s.length){const c=s[i++];if(c==='"')return JSON.parse(s.slice(a,i));check(c.charCodeAt(0)>=32);if(c==='\\'){check(i<s.length);const e=s[i++];check('"\\/bfnrtu'.includes(e));if(e==='u'){check(/^[0-9a-fA-F]{4}${'$'}/.test(s.slice(i,i+4)));i+=4;}}}throw Error('JSON string');}
  function val(depth){check(depth<=64&&++count<=100000);ws();check(i<s.length);const c=s[i];if(c==='{'){i++;ws();const keys=new Set();if(s[i]==='}'){i++;return;}while(true){const k=str();check(!keys.has(k));keys.add(k);ws();check(s[i++]===':');val(depth+1);ws();if(s[i]==='}'){i++;return;}check(s[i++ ]===',');}}else if(c==='['){i++;ws();if(s[i]===']'){i++;return;}while(true){val(depth+1);ws();if(s[i]===']'){i++;return;}check(s[i++ ]===',');}}else if(c==='"')str();else{const a=i;while(i<s.length&&!/[,\]} \t\r\n]/.test(s[i]))i++;check(/^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)${'$'}/.test(s.slice(a,i)));}}
  val(0);ws();check(i===s.length);const o=JSON.parse(s);check(o&&typeof o==='object'&&!Array.isArray(o));return o;
}
function canonical(v) { if(Array.isArray(v))return '['+v.map(canonical).join(',')+']';if(v&&typeof v==='object')return '{'+Object.keys(v).sort().map(k=>JSON.stringify(k)+':'+canonical(v[k])).join(',')+'}';return JSON.stringify(v); }
function equal(a,b){return canonical(a)===canonical(b);}
function keys(o,expected){check(o&&typeof o==='object'&&!Array.isArray(o)&&equal(Object.keys(o).sort(),expected.slice().sort()));}
function edges(name, pkg) { for(const [key,value]of Object.entries(C.packageEdges[name]))check(equal(pkg[key]===undefined?{}:pkg[key],value));check((pkg.engines||{}).node===C.engineConstraints[name]); }
function lockCheck(lock) {
  check(lock.lockfileVersion===3&&lock.name==='droide-managed-agent'&&lock.version==='1.0.0');keys(lock.packages,C.expectedLockPaths);
  const root=lock.packages[''];check(root.name==='droide-managed-agent'&&root.version==='1.0.0'&&equal(root.dependencies,C.rootDependencies));
  for(const k of ['scripts','optionalDependencies','peerDependencies','overrides','workspaces','resolutions'])check(!(k in root));
  for(const [name,expected]of Object.entries(C.artifacts)){const pkg=lock.packages['node_modules/'+name];for(const[k,v]of Object.entries(expected))check(pkg[k]===v);for(const k of ['link','hasInstallScript','dev','inBundle'])check(pkg[k]===undefined||pkg[k]===false);edges(name,pkg);}
}
function layout(root) {
  const npm=path.join(root,'payload/npm'), modules=path.join(npm,'node_modules');check(equal(strict(regular(path.join(npm,'package.json'),64000)),C.rootManifest));
  const realDir=p=>{parents(p);const st=fs.lstatSync(p);check(st.isDirectory()&&!st.isSymbolicLink());};realDir(modules);
  const actual=[];for(const n of fs.readdirSync(modules)){const p=path.join(modules,n);if(n==='.package-lock.json'){regular(p,8000000);continue;}if(n==='.bin'){realDir(p);for(const b of fs.readdirSync(p)){const f=path.join(p,b),st=fs.lstatSync(f);if(st.isSymbolicLink()){const target=fs.readlinkSync(f);check(!path.isAbsolute(target)&&target.length<=4096&&fs.realpathSync(f).startsWith(root+'/'));}else check(st.isFile());}continue;}realDir(p);if(n.startsWith('@'))for(const m of fs.readdirSync(p)){realDir(path.join(p,m));actual.push(n+'/'+m);}else actual.push(n);}
  check(equal(actual.sort(),Object.keys(C.artifacts).sort()));let visited=0,layoutBytes=0;
  function walk(p,d){check(d<=64&&++visited<=40000);for(const n of fs.readdirSync(p)){check(++visited<=40000&&n!=='node_modules');const f=path.join(p,n),s=fs.lstatSync(f);if(s.isSymbolicLink()){const target=fs.readlinkSync(f);check(!path.isAbsolute(target)&&target.length<=4096&&!/[\x00-\x1f\x7f\\]/.test(target)&&fs.realpathSync(f).startsWith(root+'/'));}else if(s.isDirectory())walk(f,d+1);else{check(s.isFile());layoutBytes+=s.size;check(layoutBytes<=1500000000);}}}
  for(const name of actual){const dir=path.join(modules,name),pkg=strict(regular(path.join(dir,'package.json'),1000000));check(pkg.name===name&&pkg.version===C.artifacts[name].version);edges(name,pkg);for(const k of ['preinstall','install','postinstall'])check(!Object.hasOwn(pkg.scripts||{},k));walk(dir,0);
    if(name==='@prisma/language-server'){check(pkg.main==='./dist/index.js');check(equal(pkg.exports,{'.':{types:'./dist/index.d.ts',default:'./dist/index.js'},'./prisma-next':{types:'./dist/lib/prismaNext.d.ts',default:'./dist/lib/prismaNext.js'}}));regular(path.join(dir,'dist/index.js'),8000000);}}
}
function fullTree(root) {
  const seal=strict(regular(path.join(root,'NPM_TREE_SEAL.json'),8000000));keys(seal,['schema','files','links']);check(seal.schema===1);check(seal.files&&seal.links&&!Array.isArray(seal.files)&&!Array.isArray(seal.links));
  const files=Object.create(null),links=Object.create(null);let count=0,total=0;const buffer=Buffer.allocUnsafe(65536);
  function walk(dir,depth){check(depth<=64);for(const name of fs.readdirSync(dir)){const p=path.join(dir,name),rel=path.relative(root,p);relative(rel);if(rel==='NPM_TREE_SEAL.json')continue;check(++count<=40000);const st=fs.lstatSync(p,{bigint:true});if(st.isSymbolicLink()){const target=fs.readlinkSync(p);check(!path.isAbsolute(target)&&target.length<=4096&&!/[\x00-\x1f\x7f\\]/.test(target));const resolved=fs.realpathSync(p);check(resolved.startsWith(root+'/'));links[rel]=target;}else if(st.isDirectory())walk(p,depth+1);else{check(st.isFile());total+=Number(st.size);check(total<=1500000000);const fd=fs.openSync(p,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW),h=crypto.createHash('sha256');try{check(identity(st)===identity(fs.fstatSync(fd,{bigint:true})));let n;while((n=fs.readSync(fd,buffer,0,buffer.length,null))>0)h.update(buffer.subarray(0,n));check(identity(st)===identity(fs.fstatSync(fd,{bigint:true})));}finally{fs.closeSync(fd);}files[rel]=h.digest('hex');}}}
  walk(root,0);check(equal(files,seal.files)&&equal(links,seal.links));
}
try {
  check(process.execArgv.length===0&&process.argv[0]===process.execPath&&process.argv[1]===__filename);check(absolute(__filename)&&__filename.endsWith('/'+launcherRel));const root=__filename.slice(0,-('/'+launcherRel).length);check(absolute(root));parents(__filename);
  const helper=path.join(root,helperRel),args=process.argv.slice(2);const ordinary=equal(args,['--stdio']);const privateMode=args.length===4&&args[0]==='--droide-prisma-health-child'&&args[1]===helper&&args[3]==='--stdio';check(ordinary||privateMode);
  const a=strict(regular(path.join(root,'PRISMA_LAUNCH_AUTHORITY.json'),64000));keys(a,['schema','familyId','version','policyRevision','closureContractSha256','finalGuestRoot','stageGuestRoot','activationId','nodeCommand','nodeVersion','nodeDependencyKeyOrNull','launcherSha256','healthSourceSha256','lockSha256']);
  check(a.schema===1&&a.familyId===family&&a.version===version&&a.policyRevision===revision&&a.closureContractSha256===closureHash);
  check(absolute(a.finalGuestRoot)&&absolute(a.stageGuestRoot)&&a.finalGuestRoot!==a.stageGuestRoot&&[a.finalGuestRoot,a.stageGuestRoot].includes(root));
  check(absolute(a.nodeCommand)&&process.execPath===a.nodeCommand&&process.versions.node===a.nodeVersion&&/^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?${'$'}/.test(a.nodeVersion)&&Number(a.nodeVersion.split('.')[0])>=22&&process.platform==='linux'&&process.arch==='arm64');
  check(a.nodeDependencyKeyOrNull===null||a.nodeDependencyKeyOrNull==='runtime.node@'+a.nodeVersion);check(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}${'$'}/.test(a.activationId));
  check(regular(path.join(root,'DROIDE_ACTIVATION_ID'),100).toString('utf8')===a.activationId);if(privateMode)check(args[2]===a.activationId);
  const r=strict(regular(path.join(root,'RECIPE_LOCK.json'),64000));keys(r,['schema','familyId','version','packageName','packageVersion','registryIntegrity','nodeMajor','npmVersion','provenanceUrl','nodeCommand','additionalDependencies','allowLifecycleScripts','launchProfile']);
  check(r.schema===1&&r.familyId===family&&r.version===version&&r.packageName==='@prisma/language-server'&&r.packageVersion===version&&r.registryIntegrity===C.artifacts[r.packageName].integrity&&r.nodeMajor===Number(a.nodeVersion.split('.')[0])&&typeof r.npmVersion==='string'&&/^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?${'$'}/.test(r.npmVersion)&&Number(r.npmVersion.split('.')[0])>=10&&r.provenanceUrl==='https://github.com/prisma/language-tools'&&r.nodeCommand===a.nodeCommand&&equal(r.additionalDependencies,C.additionalDependencies)&&r.allowLifecycleScripts===false);
  const expectedProfile={revision,closureContractSha256:closureHash,launcherRelativePath:launcherRel,launcherSha256:a.launcherSha256,healthRelativePath:helperRel,healthRevision,healthContractSha256:healthContract,healthSourceSha256:a.healthSourceSha256,nodeCommand:a.nodeCommand,nodeVersion:a.nodeVersion,nodeDependencyKeyOrNull:a.nodeDependencyKeyOrNull};check(equal(r.launchProfile,expectedProfile));
  for(const k of ['launcherSha256','healthSourceSha256','lockSha256'])check(/^[0-9a-f]{64}${'$'}/.test(a[k]));
  check(hash(regular(__filename,1000000))===a.launcherSha256&&hash(regular(helper,1000000))===a.healthSourceSha256);
  const lockBytes=regular(path.join(root,'payload/npm/package-lock.json'),8000000);check(hash(lockBytes)===a.lockSha256);lockCheck(strict(lockBytes));layout(root);
  if(root===a.stageGuestRoot){check(privateMode);let absent=false;try{fs.lstatSync(path.join(root,'NPM_TREE_SEAL.json'));}catch(e){if(e.code==='ENOENT')absent=true;else throw e;}check(absent);}else fullTree(root);
  for(const[p,id]of keyStats){parents(p);check(identity(fs.lstatSync(p,{bigint:true}))===id);}
  process.argv=[process.execPath,__filename,'--stdio'];
  const debugConsole=new Console({stdout:process.stderr,stderr:process.stderr});console.debug=debugConsole.debug.bind(debugConsole);
  require('@prisma/language-server').startServer();
} catch (_) { process.stderr.write('Prisma managed launch preflight failed\n');process.exitCode=1; }
""".replace("@CLOSURE@", LocalUbuntuPrismaPolicy.closureJson) }
    val SOURCE_SHA256: String by lazy { LocalUbuntuPrismaPolicy.hash(script) }

    fun launchProfile(nodeCommand: String, nodeVersion: String, nodeDependencyKey: String?): JsonObject {
        requireRuntime(nodeCommand, nodeVersion, nodeDependencyKey)
        return buildJsonObject {
            put("revision", REVISION); put("closureContractSha256", LocalUbuntuPrismaPolicy.CONTRACT_SHA256)
            put("launcherRelativePath", RELATIVE_PATH); put("launcherSha256", SOURCE_SHA256)
            put("healthRelativePath", HEALTH_PATH); put("healthRevision", LocalUbuntuPrismaLspHealth.REVISION)
            put("healthContractSha256", LocalUbuntuPrismaLspHealth.CONTRACT_SHA256); put("healthSourceSha256", LocalUbuntuPrismaLspHealth.SOURCE_SHA256)
            put("nodeCommand", nodeCommand); put("nodeVersion", nodeVersion)
            put("nodeDependencyKeyOrNull", nodeDependencyKey?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    fun requireRuntime(command: String, version: String, dependency: String?) {
        requireAbsolute(command)
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) && version.substringBefore('.').toInt() >= 22)
        require(dependency == null || dependency == "runtime.node@$version")
    }

    fun profileMatches(review: JsonObject, command: String, version: String, dependency: String?): Boolean = runCatching {
        review["launchProfile"] == launchProfile(command, version, dependency)
    }.getOrDefault(false)

    fun validateReview(review: JsonObject, dependency: String? = null): JsonObject {
        val expectedKeys = setOf("schema", "familyId", "version", "packageName", "packageVersion", "registryIntegrity", "nodeMajor", "npmVersion", "provenanceUrl", "nodeCommand", "additionalDependencies", "allowLifecycleScripts", "launchProfile")
        require(review.keys == expectedKeys)
        require(review["schema"] == JsonPrimitive(1) && review["familyId"] == JsonPrimitive(LocalUbuntuPrismaPolicy.FAMILY_ID) &&
            review["version"] == JsonPrimitive(LocalUbuntuPrismaPolicy.VERSION) && review["packageName"] == JsonPrimitive(LocalUbuntuPrismaPolicy.PACKAGE) &&
            review["packageVersion"] == JsonPrimitive(LocalUbuntuPrismaPolicy.VERSION) && review["registryIntegrity"] == JsonPrimitive(LocalUbuntuPrismaPolicy.INTEGRITY) &&
            review["provenanceUrl"] == JsonPrimitive(LocalUbuntuPrismaPolicy.PROVENANCE) && review["allowLifecycleScripts"] == JsonPrimitive(false) &&
            review["additionalDependencies"] == LocalUbuntuPrismaPolicy.contract.getValue("additionalDependencies"))
        val profile = review.getValue("launchProfile").jsonObject
        val command = string(profile, "nodeCommand"); val version = string(profile, "nodeVersion")
        val key = profile["nodeDependencyKeyOrNull"]?.let { if (it == JsonNull) null else it.jsonPrimitive.takeIf(JsonPrimitive::isString)?.content ?: error("Invalid Node identity") }
        require(profile.containsKey("nodeDependencyKeyOrNull") && key == dependency && profileMatches(review, command, version, key))
        require(review["nodeCommand"] == JsonPrimitive(command) && review["nodeMajor"] == JsonPrimitive(version.substringBefore('.').toInt()))
        require(string(review, "npmVersion").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")) && string(review, "npmVersion").substringBefore('.').toInt() >= 10)
        return profile
    }

    fun authority(stageGuest: String, finalGuest: String, activation: String, nodeCommand: String, nodeVersion: String,
        dependency: String?, lockSha256: String): JsonObject {
        requireAbsolute(stageGuest); requireAbsolute(finalGuest); require(stageGuest != finalGuest)
        require(activation.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) && lockSha256.matches(Regex("[0-9a-f]{64}")))
        requireRuntime(nodeCommand, nodeVersion, dependency)
        return buildJsonObject {
            put("schema", 1); put("familyId", LocalUbuntuPrismaPolicy.FAMILY_ID); put("version", LocalUbuntuPrismaPolicy.VERSION)
            put("policyRevision", REVISION); put("closureContractSha256", LocalUbuntuPrismaPolicy.CONTRACT_SHA256)
            put("finalGuestRoot", finalGuest); put("stageGuestRoot", stageGuest); put("activationId", activation)
            put("nodeCommand", nodeCommand); put("nodeVersion", nodeVersion); put("nodeDependencyKeyOrNull", dependency?.let(::JsonPrimitive) ?: JsonNull)
            put("launcherSha256", SOURCE_SHA256); put("healthSourceSha256", LocalUbuntuPrismaLspHealth.SOURCE_SHA256); put("lockSha256", lockSha256)
        }
    }

    /** Health handoff resets the environment before the first Node; it never inherits project HOME/cwd. */
    fun healthHandoff(node: String, guestRoot: String): String {
        requireAbsolute(node); requireAbsolute(guestRoot)
        val argv = listOf(node, "$guestRoot/$HEALTH_PATH", node, "$guestRoot/$RELATIVE_PATH", "--stdio").joinToString(" ", transform = ::q)
        return """
            set -eu
            umask 077
            cd /tmp
            lease="${'$'}{DROIDE_PROCESS_LEASE:-}"
            case "${'$'}lease" in *[!0-9a-f]*) exit 70;; esac
            if [ -n "${'$'}lease" ]; then
              [ "${'$'}{#lease}" -eq 20 ] || exit 70
              exec /usr/bin/env -i HOME=/nonexistent TMPDIR=/tmp PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb "DROIDE_PROCESS_LEASE=${'$'}lease" $argv
            fi
            exec /usr/bin/env -i HOME=/nonexistent TMPDIR=/tmp PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb $argv
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
        return "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(ubuntuLauncher)} /bin/sh ${q("$finalGuest/payload/bin/${LocalUbuntuPrismaPolicy.COMMAND}")} \"${'$'}@\"\n"
    }

    fun verifyProbe(root: File): Boolean = runCatching {
        LocalUbuntuPrismaPolicy.readRegular(File(root, RELATIVE_PATH), 1_000_000) == script &&
            LocalUbuntuPrismaPolicy.readRegular(File(root, HEALTH_PATH), 1_000_000) == LocalUbuntuPrismaLspHealth.script
    }.getOrDefault(false)

    suspend fun verifyIntegration(root: File, stageGuest: String, finalGuest: String, activation: String, dependency: String?, ubuntuLauncher: String): Boolean = runSuspendCatching {
        require(verifyProbe(root))
        val review = LocalUbuntuPrismaPolicy.strictObject(LocalUbuntuPrismaPolicy.readRegular(File(root, "RECIPE_LOCK.json"), 64_000))
        val profile = validateReview(review, dependency)
        val node = string(profile, "nodeCommand"); val version = string(profile, "nodeVersion")
        val lock = File(root, "payload/npm/package-lock.json")
        val actual = LocalUbuntuPrismaPolicy.strictObject(LocalUbuntuPrismaPolicy.readRegular(File(root, AUTHORITY_FILE), 64_000))
        require(actual == authority(stageGuest, finalGuest, activation, node, version, dependency, LocalUbuntuNpmPolicy.sha256(lock)))
        require(LocalUbuntuPrismaPolicy.readRegular(File(root, "DROIDE_ACTIVATION_ID"), 100) == activation)
        require(LocalUbuntuPrismaPolicy.readRegular(File(root, "payload/bin/${LocalUbuntuPrismaPolicy.COMMAND}"), 64_000) == guestWrapper(node, finalGuest))
        require(LocalUbuntuPrismaPolicy.readRegular(File(root, "payload/host-bin/${LocalUbuntuPrismaPolicy.COMMAND}"), 64_000) == hostWrapper(ubuntuLauncher, finalGuest))
        require(File(root, "payload/bin/${LocalUbuntuPrismaPolicy.COMMAND}").canExecute() && File(root, "payload/host-bin/${LocalUbuntuPrismaPolicy.COMMAND}").canExecute())
        LocalUbuntuPrismaPolicy.validateInstalledLayout(root)
        true
    }.getOrDefault(false)

    private fun string(obj: JsonObject, key: String): String = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing Prisma $key")
    private fun requireAbsolute(value: String) { require(value.startsWith('/') && value.length <= 4096 && value.none { it.code < 32 || it.code == 127 || it == '\\' } && value.drop(1).split('/').none { it.isEmpty() || it == "." || it == ".." }) }
    private fun q(value: String) = LocalExecutionSubstrate.shellQuote(value)
}
