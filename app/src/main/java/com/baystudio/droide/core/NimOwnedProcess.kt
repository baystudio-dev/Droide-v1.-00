package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class NimAdmissionException private constructor(val safeMessage:String) : IllegalStateException(safeMessage) {
    companion object {
        internal fun fromKind(kind:String?):NimAdmissionException=NimAdmissionException(when(kind) {
            "COMPILER_CONFLICT" -> "The package's observed compiler requirement conflicts with fixed Nim 2.2.12."
            "METADATA_LIMIT" -> "The native Nimble metadata exceeds this mode's 4096-byte limit."
            "METADATA_TIMEOUT" -> "Native Nimble metadata did not finish within 30 seconds."
            "RESTRICTED_METADATA_FAILED" -> "Native Nimble metadata failed with restricted PATH tools. Existing dependencies are required."
            "PROJECT_CHANGED" -> "The package manifest, lock or configuration changed during analysis preparation."
            else -> "Nim fixed-toolchain admission failed. The owner was retired without a fallback compiler."
        })
    }
}

data class NimOwnerRequest(val workspaceRoot: String, val rootPath: String, val ownerGeneration: Long,
    val consentIdentity: String, val projectLocalCanonical: String = rootPath)
data class NimMetadataObservation(val ownerToken: String, val ownerGeneration: Long, val metadataSha256: String,
    val manifestPath: String, val nativeEntryPath: String)
data class NimTeardownReceipt(val complete: Boolean, val directChildReaped: Boolean, val groupEmpty: Boolean,
    val privateCleanup: Boolean, val reason: String? = null)
interface NimPreparedOwner : java.io.Closeable {
    val identity: NimManagedLaunchIdentity
    val request: NimOwnerRequest
    val token: String
    val isCurrent: Boolean
    suspend fun retire(): NimTeardownReceipt
}
interface NimHostedProcess : HostedStdioProcess {
    val owner: NimPreparedOwner
    suspend fun awaitMetadataAdmission(): NimMetadataObservation
    suspend fun checkedAdmission(): NimMetadataObservation?
    fun isAdmissionCurrent(observation: NimMetadataObservation): Boolean
    suspend fun stopAndAwaitOwnedGroup(): NimTeardownReceipt
}

/** Cleanup owner exists before native creation. Wire checks below read immutable/atomic state only. */
internal class LocalNimPreparedOwner private constructor(
    override val identity: NimManagedLaunchIdentity, override val request: NimOwnerRequest,
    val localRoot: File, val guestRoot: String, override val token: String,
    private val projectSnapshot: JsonObject,
) : NimPreparedOwner {
    private val active = AtomicBoolean(true)
    private val lifecycle = Any()
    private val retained = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retirement = Mutex()
    private val rootIdentity = NimControlRecords.directoryIdentity(localRoot)
    @Volatile private var process: LocalNimHostedProcess? = null
    @Volatile private var metadata: NimMetadataObservation? = null
    @Volatile internal var failure: NimAdmissionException? = null
    @Volatile private var receipt: NimTeardownReceipt? = null
    @Volatile private var launchSpec: TerminalLaunchSpec? = null
    private lateinit var ownerBytes: ByteArray
    private var metadataBytes: ByteArray? = null
    override val isCurrent: Boolean get() = active.get() && process?.knownDead != true
    internal fun storePreparedLaunchSpec(value: TerminalLaunchSpec) = synchronized(lifecycle) { check(isCurrent && launchSpec == null); launchSpec = value }
    internal fun requirePreparedLaunchSpec(): TerminalLaunchSpec = synchronized(lifecycle) { check(isCurrent && process == null); requireNotNull(launchSpec) }
    internal fun verifyPrivate() { check(NimControlRecords.directoryIdentity(localRoot) == rootIdentity); check(NimControlRecords.readBytes(File(localRoot,"owner.json"),16384).contentEquals(ownerBytes)) }
    internal fun verifyProject() { check(NimControlRecords.projectSnapshot(File(request.projectLocalCanonical), request.rootPath) == projectSnapshot) { "Nim project metadata changed" } }
    internal fun createProcess(factory: () -> Process, terminateFallback: suspend () -> Unit): LocalNimHostedProcess = synchronized(lifecycle) {
        check(isCurrent && process == null)
        LocalNimHostedProcess(factory(),this,retained,terminateFallback).also { process=it; ExitHostedProcessRegistry.retain(it); it.beginObservation() }
    }
    internal suspend fun awaitServer() = withTimeout(10_000) {
        while (true) { check(isCurrent); val p=requireNotNull(process); check(p.isAlive)
            val file=File(localRoot,"server.json")
            if (file.exists()) { verifyPrivate(); val v=NimControlRecords.read(file); requireServer(v); return@withTimeout }
            delay(20)
        }
    }
    private fun requireServer(v: JsonObject) {
        require(v.keys == setOf("schema","token","pid","start","group","supervisorPid","supervisorStart"))
        require(v["schema"]?.jsonPrimitive?.intOrNull==1 && v["token"]?.jsonPrimitive?.content==token)
        val pid=requireNotNull(v["pid"]?.jsonPrimitive?.intOrNull); val group=requireNotNull(v["group"]?.jsonPrimitive?.intOrNull)
        require(pid>1&&group==pid)
        val supervisorPid=requireNotNull(v["supervisorPid"]?.jsonPrimitive?.intOrNull)
        val current=NimControlRecords.procIdentity(pid);val supervisor=NimControlRecords.procIdentity(supervisorPid)
        require(current.start==v["start"]?.jsonPrimitive?.content&&current.parent==supervisorPid&&current.group==group&&current.state!="Z")
        require(supervisor.start==v["supervisorStart"]?.jsonPrimitive?.content&&supervisor.state!="Z")
    }
    internal suspend fun observe(): NimMetadataObservation? = withContext(Dispatchers.IO) {
        if(!isCurrent)return@withContext null
        try {
            verifyPrivate(); if(metadata==null)verifyProject()
            val failureFile=File(localRoot,"failure.json")
            if(failureFile.exists()) { val record=NimControlRecords.read(failureFile); throw NimAdmissionException.fromKind(record["kind"]?.jsonPrimitive?.content.takeIf { record["token"]?.jsonPrimitive?.content==token }) }
            if(File(localRoot,"guard.json").exists()||File(localRoot,"stop.json").exists()||File(localRoot,"retired.json").exists())throw NimAdmissionException.fromKind(null)
            val serverFile=File(localRoot,"server.json")
            if(!serverFile.exists())return@withContext null
            val server=NimControlRecords.read(serverFile);requireServer(server)
            val file=File(localRoot,"metadata.json");if(!file.exists())return@withContext null
            metadata?.let { check(NimControlRecords.readBytes(file,16384).contentEquals(requireNotNull(metadataBytes))); return@withContext it }
            val raw=NimControlRecords.readBytes(file,16384);val v=NimControlRecords.strict(raw)
            require(v.keys==setOf("schema","status","token","ownerGeneration","nativeEntryPath","manifestPath","metadataSha256","candidates","serverPid","serverStart","pipeIdentity"))
            require(v["schema"]?.jsonPrimitive?.intOrNull==1&&v["status"]?.jsonPrimitive?.content=="PUBLISHED"&&v["token"]?.jsonPrimitive?.content==token)
            require(v["ownerGeneration"]?.jsonPrimitive?.longOrNull==request.ownerGeneration)
            require(v["serverPid"]==server["pid"]&&v["serverStart"]==server["start"])
            val pipe=requireNotNull(v["pipeIdentity"]?.jsonPrimitive?.content);require(pipe.matches(Regex("[0-9]+:[0-9]+:"+server["start"]!!.jsonPrimitive.content)))
            val hash=requireNotNull(v["metadataSha256"]?.jsonPrimitive?.content).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            val manifest=requireNotNull(v["manifestPath"]?.jsonPrimitive?.content);require(manifest==projectSnapshot["manifest"]?.jsonPrimitive?.content)
            val entry=requireNotNull(v["nativeEntryPath"]?.jsonPrimitive?.content);require(entry.startsWith(request.rootPath.trimEnd('/')+"/")&&entry.endsWith(".nim"))
            val candidates=v["candidates"] as? JsonArray ?: error("Missing Nim candidate records");require(candidates.size in 1..256)
            val existing=mutableSetOf<String>()
            for(value in candidates) { val candidate=value.jsonObject;require(candidate.keys==setOf("path","snapshot"));val guest=requireNotNull(candidate["path"]?.jsonPrimitive?.content)
                require(guest.startsWith(request.rootPath.trimEnd('/')+"/")&&guest.endsWith(".nim"));val local=File(request.projectLocalCanonical,guest.removePrefix(request.rootPath.trimEnd('/')+"/"))
                require(local.canonicalPath==local.absolutePath)
                val current=NimControlRecords.fileSnapshot(local,guest,true)
                require(current==(candidate["snapshot"]?:JsonNull));if(current!=JsonNull)existing.add(guest)
            }
            require(existing==setOf(entry));val result=NimMetadataObservation(token,request.ownerGeneration,hash,manifest,entry)
            synchronized(lifecycle) { check(isCurrent);val old=metadata;require(old==null||old==result);metadataBytes=raw;metadata=result }
            result
        } catch(problem: Throwable) { val safe=problem as? NimAdmissionException ?: NimAdmissionException.fromKind(null);failure=safe;close();throw safe }
    }
    internal fun admissionCurrent(value: NimMetadataObservation): Boolean = isCurrent && metadata == value && value.ownerToken==token && value.ownerGeneration==request.ownerGeneration
    internal fun currentMetadata(): NimMetadataObservation? = metadata.takeIf { isCurrent }
    override fun close() {
        if(active.getAndSet(false))retained.launch { retire() }
    }
    override suspend fun retire(): NimTeardownReceipt = withContext(NonCancellable + Dispatchers.IO) { retirement.withLock {
        receipt?.let { return@withLock it };active.set(false)
        val p=synchronized(lifecycle) { process }
        val stopped=if(p==null) NimTeardownReceipt(true,true,true,false) else p.stopOwned()
        val cleanup=if(stopped.complete&&stopped.directChildReaped&&stopped.groupEmpty)runCatching { removePrivate();true }.getOrDefault(false)else false
        stopped.copy(complete=stopped.complete&&cleanup,privateCleanup=cleanup).also { receipt=it }
    } }
    private fun removePrivate() {
        check(NimControlRecords.directoryIdentity(localRoot)==rootIdentity)
        var count=0;var bytes=0L
        fun remove(file:File) { check(++count<=8192);val st=Files.readAttributes(file.toPath(),"unix:mode,size",LinkOption.NOFOLLOW_LINKS)
            val mode=(st["mode"] as Number).toInt();require(mode and 0xf000 in setOf(0x4000,0x8000))
            bytes+=(st["size"] as Number).toLong();require(bytes<=512L*1024*1024)
            if(mode and 0xf000==0x4000)file.listFiles()?.forEach(::remove)?:error("Nim private enumeration failed")
            check(file.delete())
        }
        remove(localRoot)
    }
    companion object {
        fun allocate(appRoot:File,identity:NimManagedLaunchIdentity,request:NimOwnerRequest):LocalNimPreparedOwner {
            require(LspNimLaunchPolicy.matchesIdentity(identity));require(request.ownerGeneration>=0&&request.consentIdentity.matches(Regex("[0-9a-f]{64}")))
            val workspace=File(request.workspaceRoot);val project=File(request.projectLocalCanonical)
            require(workspace.canonicalPath==workspace.absolutePath&&project.canonicalPath==project.absolutePath&&project.isDirectory&&project.toPath().startsWith(workspace.toPath()))
            require(request.rootPath==project.canonicalPath) { "Nim requires exact local/guest root binding" }
            val temp=File(appRoot,"runtime-tmp");require(temp.isDirectory&&!Files.isSymbolicLink(temp.toPath()))
            val snapshot=NimControlRecords.projectSnapshot(project,request.rootPath)
            val token=UUID.randomUUID().toString().replace("-","");val local=File(temp,"droide-nim-$token")
            Files.createDirectory(local.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val guest="/tmp/${local.name}"
            val owner=LocalNimPreparedOwner(identity,request,local,guest,token,snapshot)
            try {
                for(name in listOf("home","tmp","config","cache","data","state","run","commands"))Files.createDirectory(File(local,name).toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                val commandHashes=buildJsonObject {
                    for(name in listOf("nim","nimsuggest","nimble")) {val text=if(name=="nimble")LspNimLaunchPolicy.adapterEntry(identity)else LspNimLaunchPolicy.refusalEntry(guest)
                        val file=File(local,"commands/$name");NimControlRecords.write(file,text.toByteArray());Files.setPosixFilePermissions(file.toPath(),PosixFilePermissions.fromString("rwx------"));put(name,LspNimLaunchPolicy.sha(text.toByteArray()))}
                }
                fun localPackage(guestPath:String):File {require(guestPath.startsWith("/opt/droide/packages/"));return File(appRoot,"packages/"+guestPath.removePrefix("/opt/droide/packages/"))}
                val packageRoot=LspNimLaunchPolicy.packageRoot(identity)
                val proofPaths=listOf(identity.serverExecutable,identity.compilerExecutable,identity.backendExecutable,identity.nimbleExecutable,identity.helperExecutable,
                    "$packageRoot/${LspNimLaunchPolicy.SUPERVISOR}","$packageRoot/${LspNimLaunchPolicy.ADAPTER}","$packageRoot/${LspNimLaunchPolicy.HEALTH}","$packageRoot/${LspNimLaunchPolicy.HELPER_HEALTH}")+
                    listOf("lsp.nimlangserver" to "1.16.0","runtime.nim-lsp" to "2.2.12","runtime.nim-helper" to "22.23.3").flatMap { (family,version)->
                        listOf("DROIDE_ACTIVATION_ID","NIM_AUTHORITY.json","NIM_CLOSURE.json","SHA256SUMS","TREE_SHA256SUMS").map {"/opt/droide/packages/$family/$version/arm64-v8a/$it"} }
                val config=buildJsonObject {
                    put("schema",1);put("mode","editor");put("policy",LspNimLaunchPolicy.PROFILE);put("privateRoot",guest);put("privateIdentity",owner.rootIdentity)
                    put("token",token);put("ownerGeneration",request.ownerGeneration);put("consentIdentity",request.consentIdentity);put("rootPath",request.rootPath)
                    put("helperExecutable",identity.helperExecutable);put("helperSourceCommit",LspNimLaunchPolicy.SOURCE_COMMIT);put("helperArchiveSha256",identity.helperArchiveSha256)
                    put("helperSourceBindingSha256",identity.helperSourceBindingSha256);put("helperConfigSha256",identity.helperConfigSha256);put("closureSha256",identity.closureSha256)
                    put("closurePath","$packageRoot/NIM_CLOSURE.json");put("serverExecutable",identity.serverExecutable);put("compilerExecutable",identity.compilerExecutable);put("backendExecutable",identity.backendExecutable);put("nimbleExecutable",identity.nimbleExecutable)
                    put("projectSnapshot",snapshot);put("commandSha256",commandHashes)
                    putJsonArray("proofs") { for(guestPath in proofPaths)add(buildJsonObject {put("path",guestPath);put("sha256",LspNimLaunchPolicy.sha(NimControlRecords.readBytes(localPackage(guestPath),134217728)))}) }
                }
                owner.ownerBytes=config.toString().toByteArray();require(owner.ownerBytes.size<=16384);NimControlRecords.write(File(local,"owner.json"),owner.ownerBytes)
                return owner
            } catch(failure:Throwable) { owner.close();throw failure }
        }
    }
}

internal class LocalNimHostedProcess(private val process:Process,override val owner:LocalNimPreparedOwner,
    private val scope:CoroutineScope,private val fallback:suspend ()->Unit):NimHostedProcess {
    @Volatile internal var knownDead=false
    override val stdin:OutputStream get()=process.outputStream
    override val stdout:InputStream get()=process.inputStream
    override val stderr:InputStream get()=process.errorStream
    override val isAlive:Boolean get()=process.isAlive
    override val exitCode:Int? get()=runCatching { process.exitValue() }.getOrNull()
    fun beginObservation() { scope.launch { try { while(owner.isCurrent&&process.isAlive){owner.observe();delay(100)} }catch(_:Exception){}finally{knownDead=!process.isAlive;if(knownDead)owner.close()} } }
    override suspend fun awaitMetadataAdmission():NimMetadataObservation=withTimeout(LspNimLaunchPolicy.METADATA_MS) {
        while(true) {owner.failure?.let {throw it};check(owner.isCurrent&&isAlive);owner.observe()?.let {return@withTimeout it};delay(20)}
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    override suspend fun checkedAdmission():NimMetadataObservation?=owner.currentMetadata()
    override fun isAdmissionCurrent(observation:NimMetadataObservation):Boolean=owner.admissionCurrent(observation)&&!knownDead
    override fun close()=owner.close()
    override suspend fun stopAndAwaitOwnedGroup():NimTeardownReceipt=owner.retire()
    override suspend fun awaitExit():Int {withContext(Dispatchers.IO){process.waitFor()};knownDead=true;owner.retire();return process.exitValue()}
    internal suspend fun stopOwned():NimTeardownReceipt {
        var outer=false
        runCatching {owner.verifyPrivate();NimControlRecords.write(File(owner.localRoot,"stop.json"),"{\"stop\":true}".toByteArray())}
        outer=withContext(Dispatchers.IO){process.waitFor(9,TimeUnit.SECONDS)}
        if(!outer) {withTimeoutOrNull(500) {runCatching {fallback()}};process.destroy();outer=process.waitFor(250,TimeUnit.MILLISECONDS);if(!outer){process.destroyForcibly();outer=process.waitFor(250,TimeUnit.MILLISECONDS)}}
        knownDead=outer
        val value=runCatching {NimControlRecords.read(File(owner.localRoot,"teardown.json"))}.getOrNull()
        val bound=value?.get("token")?.jsonPrimitive?.content==owner.token&&value["schema"]?.jsonPrimitive?.intOrNull==1
        val direct=bound&&value?.get("directChildReaped")?.jsonPrimitive?.booleanOrNull==true
        val empty=bound&&value?.get("groupEmpty")?.jsonPrimitive?.booleanOrNull==true
        runCatching {stdin.close()};runCatching {stdout.close()};runCatching {stderr.close()}
        return NimTeardownReceipt(outer&&direct&&empty&&value?.get("status")?.jsonPrimitive?.content=="COMPLETE",direct,empty,false,
            if(outer&&direct&&empty)null else "Nim ordinary process-group cleanup was not confirmed")
    }
}

internal object NimControlRecords {
    fun directoryIdentity(file:File):String {require(file.canonicalPath==file.absolutePath);val a=Files.readAttributes(file.toPath(),"unix:dev,ino,mode",LinkOption.NOFOLLOW_LINKS)
        require((a["mode"] as Number).toInt() and 0xf1ff==0x41c0);return "${a["dev"]}:${a["ino"]}"}
    fun readBytes(file:File,max:Int):ByteArray {
        val attrs="unix:dev,ino,size,lastModifiedTime,ctime,nlink,mode";val before=Files.readAttributes(file.toPath(),attrs,LinkOption.NOFOLLOW_LINKS)
        require((before["mode"] as Number).toInt() and 0xf000==0x8000&&(before["nlink"] as Number).toInt()==1)
        val size=(before["size"] as Number).toLong();require(size in 0..max.toLong())
        Files.newByteChannel(file.toPath(),setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)).use {channel->
            require(channel.size()==size);val out=java.nio.ByteBuffer.allocate(size.toInt());while(out.hasRemaining())require(channel.read(out)>0)
            require(channel.size()==size&&before==Files.readAttributes(file.toPath(),attrs,LinkOption.NOFOLLOW_LINKS));return out.array()
        }
    }
    fun read(file:File):JsonObject {
        require((Files.getAttribute(file.toPath(),"unix:mode",LinkOption.NOFOLLOW_LINKS) as Number).toInt() and 0x1ff==0x180)
        return strict(readBytes(file,16384))
    }
    fun write(file:File,bytes:ByteArray) {require(bytes.size<=16384);Files.write(file.toPath(),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);Files.setPosixFilePermissions(file.toPath(),PosixFilePermissions.fromString("rw-------"))}
    fun strict(bytes:ByteArray):JsonObject {
        require(bytes.size in 1..16384);val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);val text=decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();var i=0;var count=0
        fun ws(){while(i<text.length&&text[i] in " \t\r\n")i++}
        fun string():String {require(text[i]=='"');val start=i++;var escaped=false;while(i<text.length){val c=text[i++];if(c=='"'&&!escaped)return Json.parseToJsonElement(text.substring(start,i)).jsonPrimitive.content;escaped=if(c=='\\')!escaped else false};error("Unclosed JSON string")}
        fun value(depth:Int) {require(depth<=16&&++count<=4096);ws();require(i<text.length);when(text[i]) {
            '{'->{i++;ws();val keys=mutableSetOf<String>();if(i<text.length&&text[i]=='}'){i++}else while(true){ws();require(keys.add(string()));ws();require(text[i++]==':');value(depth+1);ws();val c=text[i++];if(c=='}')break;require(c==',')}}
            '['->{i++;ws();if(i<text.length&&text[i]==']'){i++}else while(true){value(depth+1);ws();val c=text[i++];if(c==']')break;require(c==',')}}
            '"'->string()
            else->{val start=i;while(i<text.length&&text[i] !in ",]} \t\r\n")i++;require(i>start);Json.parseToJsonElement(text.substring(start,i))}
        }}
        value(0);ws();require(i==text.length);return Json.parseToJsonElement(text).jsonObject
    }
    fun fileSnapshot(file:File,guest:String,optional:Boolean=false):JsonElement {
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)){require(optional);return JsonNull}
        require(file.canonicalPath==file.absolutePath&&!Files.isSymbolicLink(file.toPath()));val a=Files.readAttributes(file.toPath(),"unix:dev,ino,size,lastModifiedTime,ctime,nlink,mode",LinkOption.NOFOLLOW_LINKS)
        require((a["mode"] as Number).toInt() and 0xf000==0x8000&&(a["nlink"] as Number).toInt()==1)
        val bytes=readBytes(file,1048576);require(a==Files.readAttributes(file.toPath(),"unix:dev,ino,size,lastModifiedTime,ctime,nlink,mode",LinkOption.NOFOLLOW_LINKS));return buildJsonObject {put("path",guest);for(k in listOf("dev","ino","size"))put(k,a[k].toString());for(k in listOf("mtime","ctime"))put(k,(a[if(k=="mtime")"lastModifiedTime" else k] as FileTime).to(TimeUnit.NANOSECONDS).toString());put("sha256",LspNimLaunchPolicy.sha(bytes))}
    }
    fun projectSnapshot(root:File,guestRoot:String):JsonObject {
        require(root.canonicalPath==root.absolutePath&&root.isDirectory);val names=requireNotNull(root.listFiles());require(names.size<=16384);val manifests=names.filter {it.name.endsWith(".nimble")};require(manifests.size==1)
        val manifest=manifests.single();val guest="$guestRoot/${manifest.name}";return buildJsonObject {put("manifest",guest);putJsonArray("files"){add(fileSnapshot(manifest,guest));for(name in listOf("nimble.lock","nim.cfg","config.nims"))add(fileSnapshot(File(root,name),"$guestRoot/$name",true))}}
    }
    data class ProcIdentity(val parent:Int,val group:Int,val start:String,val state:String)
    fun procIdentity(pid:Int):ProcIdentity {
        require(pid>1);val bytes=File("/proc/$pid/stat").inputStream().use {input->val buffer=ByteArray(4097);var count=0;while(count<buffer.size){val n=input.read(buffer,count,buffer.size-count);if(n<0)break;require(n>0);count+=n};require(count<=4096);buffer.copyOf(count)}
        val value=bytes.toString(Charsets.US_ASCII);val end=value.lastIndexOf(')');require(end>0);val fields=value.substring(end+2).trim().split(' ');require(fields.size>=20)
        val start=fields[19].also {require(it.matches(Regex("[0-9]+")))};return ProcIdentity(fields[1].toInt(),fields[2].toInt(),start,fields[0])
    }
    fun procStart(pid:Int):String=procIdentity(pid).start
}
