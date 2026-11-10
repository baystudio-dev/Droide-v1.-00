package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class CSharpOwnerRequest(val workspaceRoot:String,val rootPath:String,val ownerGeneration:Long,
    val consentIdentity:String,val projectLocalCanonical:String=rootPath,val solutionPath:String?=null)
data class CSharpTeardownReceipt(val complete:Boolean,val directChildReaped:Boolean,val groupEmpty:Boolean,
    val privateCleanup:Boolean,val reason:String?=null)
interface CSharpPreparedOwner:java.io.Closeable {
    val identity:CSharpManagedLaunchIdentity
    val request:CSharpOwnerRequest
    val token:String
    val isCurrent:Boolean
    suspend fun retire():CSharpTeardownReceipt
}
interface CSharpHostedProcess:HostedStdioProcess {
    val owner:CSharpPreparedOwner
    suspend fun stopAndAwaitOwnedGroup():CSharpTeardownReceipt
}

/** Owner exists before shell creation; unknown cleanup retains its exact private directory. */
internal class LocalCSharpPreparedOwner private constructor(override val identity:CSharpManagedLaunchIdentity,
    override val request:CSharpOwnerRequest,val localRoot:File,val guestRoot:String,override val token:String,
    private val inspection:CSharpSelectionInspection):CSharpPreparedOwner {
    private val active=AtomicBoolean(true)
    private val lifecycle=Any()
    private val retained=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val retirement=Mutex()
    private val rootIdentity=CSharpControlRecords.directoryIdentity(localRoot)
    @Volatile private var process:LocalCSharpHostedProcess?=null
    @Volatile private var receipt:CSharpTeardownReceipt?=null
    private var launchSpec:TerminalLaunchSpec?=null
    private lateinit var ownerBytes:ByteArray
    @Volatile internal var acceptedBirth:JsonObject?=null
    @Volatile private var selectionCurrent:()->Boolean = { true }
    private var selectionBound=false
    internal fun bindSelectionCurrentness(current:()->Boolean)=synchronized(lifecycle){check(isCurrent&&!selectionBound);selectionCurrent=current;selectionBound=true;check(isCurrent)}
    override val isCurrent:Boolean get()=active.get()&&selectionCurrent()&&process?.knownDead!=true
    internal fun storePreparedLaunchSpec(value:TerminalLaunchSpec)=synchronized(lifecycle){check(isCurrent&&launchSpec==null);launchSpec=value}
    internal fun requirePreparedLaunchSpec():TerminalLaunchSpec=synchronized(lifecycle){check(isCurrent&&process==null);requireNotNull(launchSpec)}
    internal fun verifyPrivate(){check(CSharpControlRecords.directoryIdentity(localRoot)==rootIdentity);check(CSharpControlRecords.readBytes(File(localRoot,"owner.json"),65536).contentEquals(ownerBytes))}
    internal fun verifyProject(){check(request.projectLocalCanonical==request.rootPath);inspection.requireCurrent()}
    internal fun createProcess(factory:()->Process,fallback:suspend ()->Unit):LocalCSharpHostedProcess=synchronized(lifecycle){check(isCurrent&&process==null);LocalCSharpHostedProcess(factory(),this,fallback).also{process=it;ExitHostedProcessRegistry.retain(it);retained.launch{while(active.get()&&it.isAlive){if(!selectionCurrent()){close();break};delay(100)}}}}
    internal suspend fun awaitServer()=withTimeout(10_000){while(true){check(isCurrent);val p=requireNotNull(process);check(p.isAlive);verifyPrivate();check(!File(localRoot,"failure.json").exists());val file=File(localRoot,"server.json");if(file.exists()){
        val v=CSharpControlRecords.read(file);require(v.keys==setOf("schema","token","ownerGeneration","pid","start","group","supervisorPid","supervisorStart"));require(v["schema"]?.jsonPrimitive?.intOrNull==1&&v["token"]?.jsonPrimitive?.content==token&&v["ownerGeneration"]?.jsonPrimitive?.longOrNull==request.ownerGeneration)
        val pid=requireNotNull(v["pid"]?.jsonPrimitive?.intOrNull);val group=requireNotNull(v["group"]?.jsonPrimitive?.intOrNull);val parent=requireNotNull(v["supervisorPid"]?.jsonPrimitive?.intOrNull);require(pid>1&&pid==group)
        val current=CSharpControlRecords.procIdentity(pid);val supervisor=CSharpControlRecords.procIdentity(parent);require(current.start==v["start"]?.jsonPrimitive?.content&&current.parent==parent&&current.group==group&&current.state!="Z"&&supervisor.start==v["supervisorStart"]?.jsonPrimitive?.content&&supervisor.state!="Z");acceptedBirth=v;return@withTimeout
    };delay(20)}}
    override fun close(){if(active.getAndSet(false))retained.launch{retire()}}
    override suspend fun retire():CSharpTeardownReceipt=withContext(NonCancellable+Dispatchers.IO){retirement.withLock{
        receipt?.let{return@withLock it};active.set(false);val p=synchronized(lifecycle){process};val stopped=if(p==null)CSharpTeardownReceipt(true,true,true,false)else p.stopOwned()
        val clean=stopped.complete&&stopped.directChildReaped&&stopped.groupEmpty&&runCatching{removePrivate();true}.getOrDefault(false)
        stopped.copy(complete=stopped.complete&&clean,privateCleanup=clean,reason=if(clean)stopped.reason else stopped.reason?:"CSharp private cleanup not confirmed").also{receipt=it;if(it.complete)CSharpRootOwners.release(this@LocalCSharpPreparedOwner)}
    }}
    private fun removePrivate(){check(CSharpControlRecords.directoryIdentity(localRoot)==rootIdentity);var entries=0;var bytes=0L
        fun remove(file:File){check(++entries<=16384);val st=Files.readAttributes(file.toPath(),"unix:mode,size",LinkOption.NOFOLLOW_LINKS);val mode=(st["mode"] as Number).toInt();require(mode and 0xf000 in setOf(0x4000,0x8000,0xc000));bytes+=(st["size"] as Number).toLong();require(bytes<=536870912);if(mode and 0xf000==0x4000)requireNotNull(file.listFiles()).forEach(::remove);check(file.delete())};remove(localRoot)}
    companion object {
        fun allocate(appRoot:File,identity:CSharpManagedLaunchIdentity,request:CSharpOwnerRequest):LocalCSharpPreparedOwner {
            require(LspCSharpLaunchPolicy.matchesIdentity(identity)&&request.ownerGeneration in 1..9007199254740991L&&request.consentIdentity.matches(Regex("[0-9a-f]{64}")))
            val workspace=File(request.workspaceRoot);val project=File(request.projectLocalCanonical);require(workspace.canonicalPath==workspace.absolutePath&&project.canonicalPath==project.absolutePath&&project.isDirectory&&project.toPath().startsWith(workspace.toPath())&&request.rootPath==project.canonicalPath)
            val inspection=CSharpSdkSelection.inspect(request.workspaceRoot,request.rootPath,request.solutionPath)
            val temp=File(appRoot,"runtime-tmp");require(temp.isDirectory&&!Files.isSymbolicLink(temp.toPath()));val token=UUID.randomUUID().toString().replace("-","");val local=File(temp,"droide-csharp-$token");Files.createDirectory(local.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));val guest="/run/droide-csharp/${local.name}"
            var allocated:LocalCSharpPreparedOwner?=null
            try{val owner=LocalCSharpPreparedOwner(identity,request,local,guest,token,inspection);allocated=owner;CSharpRootOwners.claim(owner);for(name in listOf("home","tmp","config","cache","data","state","run","nuget","nuget-http"))Files.createDirectory(File(local,name).toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                val seed=LocalUbuntuCSharpLaunchAuthority.objectAt(File(appRoot,"packages/lsp.csharp-ls/0.28.0/arm64-v8a/${LocalUbuntuCSharpLaunchAuthority.HEALTH_SEED}"),65536)
                val config=JsonObject(seed+mapOf("schema" to JsonPrimitive(1),"mode" to JsonPrimitive("editor"),"policy" to JsonPrimitive(LspCSharpLaunchPolicy.PROFILE),"privateRoot" to JsonPrimitive(guest),"privateIdentity" to JsonPrimitive(owner.rootIdentity),"token" to JsonPrimitive(token),"ownerGeneration" to JsonPrimitive(request.ownerGeneration),"consentIdentity" to JsonPrimitive(request.consentIdentity),"rootPath" to JsonPrimitive(request.rootPath),"solutionPath" to (request.solutionPath?.let(::JsonPrimitive)?:JsonNull)))
                owner.ownerBytes=config.toString().toByteArray();CSharpControlRecords.write(File(local,"owner.json"),owner.ownerBytes);return owner
            }catch(failure:Throwable){
                // If construction itself failed, only the exact newly created empty directory is eligible.
                val owner=allocated
                if(owner!=null)owner.close() else runCatching { Files.delete(local.toPath()) }.onFailure(failure::addSuppressed)
                throw failure
            }
        }
    }
}
internal class LocalCSharpHostedProcess(private val process:Process,override val owner:LocalCSharpPreparedOwner,private val fallback:suspend ()->Unit):CSharpHostedProcess {
    @Volatile internal var knownDead=false
    override val stdin:OutputStream get()=process.outputStream
    override val stdout:InputStream get()=process.inputStream
    override val stderr:InputStream get()=process.errorStream
    override val isAlive:Boolean get()=process.isAlive
    override val exitCode:Int? get()=runCatching{process.exitValue()}.getOrNull()
    override fun close()=owner.close()
    override suspend fun stopAndAwaitOwnedGroup():CSharpTeardownReceipt=owner.retire()
    override suspend fun awaitExit():Int{withContext(Dispatchers.IO){process.waitFor()};knownDead=true;check(owner.retire().complete){"CSharp owned cleanup remains UNKNOWN"};return process.exitValue()}
    internal suspend fun stopOwned():CSharpTeardownReceipt {
        runCatching{owner.verifyPrivate();CSharpControlRecords.write(File(owner.localRoot,"stop.json"),"{\"stop\":true}".toByteArray())}
        var outer=process.waitFor(9,TimeUnit.SECONDS)
        if(!outer){withTimeoutOrNull(500){runCatching{fallback()}};process.destroy();outer=process.waitFor(250,TimeUnit.MILLISECONDS);if(!outer){process.destroyForcibly();outer=process.waitFor(250,TimeUnit.MILLISECONDS)}}
        knownDead=outer;val value=runCatching{CSharpControlRecords.read(File(owner.localRoot,"teardown.json"))}.getOrNull();val bound=value?.get("token")?.jsonPrimitive?.content==owner.token&&value["schema"]?.jsonPrimitive?.intOrNull==1&&value["ownerGeneration"]?.jsonPrimitive?.longOrNull==owner.request.ownerGeneration
        val born=owner.acceptedBirth ?: runCatching { CSharpControlRecords.read(File(owner.localRoot,"server.json")) }.getOrNull()
        val birthBound=bound&&born?.get("token")?.jsonPrimitive?.content==owner.token&&born["ownerGeneration"]?.jsonPrimitive?.longOrNull==owner.request.ownerGeneration&&
            value?.get("leaderPid")==born["pid"]&&value?.get("leaderStart")==born["start"]&&value?.get("supervisorPid")==born["supervisorPid"]&&value?.get("supervisorStart")==born["supervisorStart"]
        val direct=birthBound&&value?.get("directChildReaped")?.jsonPrimitive?.booleanOrNull==true;val empty=birthBound&&value?.get("groupEmpty")?.jsonPrimitive?.booleanOrNull==true
        runCatching{stdin.close()};runCatching{stdout.close()};runCatching{stderr.close()};return CSharpTeardownReceipt(outer&&direct&&empty&&value?.get("status")?.jsonPrimitive?.content=="COMPLETE",direct,empty,false,if(outer&&direct&&empty)null else "CSharp ordinary process-group cleanup UNKNOWN; owner retained")
    }
}

/** Retains exact owners across cancellation and UNKNOWN; persistent private records block app-restart replacement. */
internal object CSharpRootOwners {
    private val lock=Any()
    private val roots=mutableMapOf<String,LocalCSharpPreparedOwner>()
    fun claim(owner:LocalCSharpPreparedOwner)=synchronized(lock){
        val key=owner.request.rootPath;check(roots[key]==null){"A prior CSharp owner is still retained for this root"};check(roots.size<128)
        val siblings=requireNotNull(owner.localRoot.parentFile.listFiles());require(siblings.size<=16384)
        for(candidate in siblings){if(candidate==owner.localRoot||!candidate.name.startsWith("droide-csharp-"))continue
            require(!Files.isSymbolicLink(candidate.toPath()))
            val record=File(candidate,"owner.json");if(record.exists())check(CSharpControlRecords.read(record)["rootPath"]?.jsonPrimitive?.content!=key){"A prior CSharp private owner requires verified cleanup before replacement"}
        }
        roots[key]=owner
    }
    fun release(owner:LocalCSharpPreparedOwner)=synchronized(lock){if(roots[owner.request.rootPath]===owner)roots.remove(owner.request.rootPath);Unit}
}

internal object CSharpControlRecords {
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
        return strict(readBytes(file,65536))
    }
    fun write(file:File,bytes:ByteArray) {require(bytes.size<=65536);Files.write(file.toPath(),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);Files.setPosixFilePermissions(file.toPath(),PosixFilePermissions.fromString("rw-------"))}
    fun strict(bytes:ByteArray):JsonObject {
        require(bytes.size in 1..65536);val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);val text=decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();var i=0;var count=0
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
    data class ProcIdentity(val parent:Int,val group:Int,val start:String,val state:String)
    fun procIdentity(pid:Int):ProcIdentity {
        require(pid>1);val bytes=File("/proc/$pid/stat").inputStream().use {input->val buffer=ByteArray(4097);var count=0;while(count<buffer.size){val n=input.read(buffer,count,buffer.size-count);if(n<0)break;require(n>0);count+=n};require(count<=4096);buffer.copyOf(count)}
        val value=bytes.toString(Charsets.US_ASCII);val end=value.lastIndexOf(')');require(end>0);val fields=value.substring(end+2).trim().split(' ');require(fields.size>=20)
        val start=fields[19].also {require(it.matches(Regex("[0-9]+")))};return ProcIdentity(fields[1].toInt(),fields[2].toInt(),start,fields[0])
    }
    fun procStart(pid:Int):String=procIdentity(pid).start
}
