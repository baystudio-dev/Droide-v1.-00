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

data class VueOwnerRequest(val workspaceRoot:String,val rootPath:String,val ownerGeneration:Long,
    val consentIdentity:String,val projectLocalCanonical:String=rootPath)
data class VueTeardownReceipt(val complete:Boolean,val directChildReaped:Boolean,val groupEmpty:Boolean,
    val privateCleanup:Boolean,val reason:String?=null)
interface VuePreparedOwner:java.io.Closeable {
    val identity:VueManagedLaunchIdentity
    val request:VueOwnerRequest
    val token:String
    val isCurrent:Boolean
    suspend fun retire():VueTeardownReceipt
}
interface VueHostedProcess:HostedStdioProcess {
    val owner:VuePreparedOwner
    suspend fun stopAndAwaitOwnedGroup():VueTeardownReceipt
}

/** Owner exists before shell creation; unknown cleanup retains its exact private directory. */
internal class LocalVuePreparedOwner private constructor(override val identity:VueManagedLaunchIdentity,
    override val request:VueOwnerRequest,val localRoot:File,val guestRoot:String,override val token:String):VuePreparedOwner {
    private val active=AtomicBoolean(true)
    private val lifecycle=Any()
    private val retained=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val retirement=Mutex()
    private val rootIdentity=VueControlRecords.directoryIdentity(localRoot)
    @Volatile private var process:LocalVueHostedProcess?=null
    @Volatile private var receipt:VueTeardownReceipt?=null
    private var launchSpec:TerminalLaunchSpec?=null
    private lateinit var ownerBytes:ByteArray
    @Volatile private var selectionCurrent:()->Boolean = { true }
    private var selectionBound=false
    internal fun bindSelectionCurrentness(current:()->Boolean)=synchronized(lifecycle){check(isCurrent&&!selectionBound);selectionCurrent=current;selectionBound=true;check(isCurrent)}
    override val isCurrent:Boolean get()=active.get()&&selectionCurrent()&&process?.knownDead!=true
    internal fun storePreparedLaunchSpec(value:TerminalLaunchSpec)=synchronized(lifecycle){check(isCurrent&&launchSpec==null);launchSpec=value}
    internal fun requirePreparedLaunchSpec():TerminalLaunchSpec=synchronized(lifecycle){check(isCurrent&&process==null);requireNotNull(launchSpec)}
    internal fun verifyPrivate(){check(VueControlRecords.directoryIdentity(localRoot)==rootIdentity);check(VueControlRecords.readBytes(File(localRoot,"owner.json"),65536).contentEquals(ownerBytes))}
    internal fun verifyProject(){val project=File(request.projectLocalCanonical);check(project.isDirectory&&project.canonicalPath==request.projectLocalCanonical&&request.rootPath==request.workspaceRoot&&request.rootPath==project.canonicalPath)}
    internal fun createProcess(factory:()->Process,fallback:suspend ()->Unit):LocalVueHostedProcess=synchronized(lifecycle){check(isCurrent&&process==null);LocalVueHostedProcess(factory(),this,fallback).also{process=it;ExitHostedProcessRegistry.retain(it);retained.launch{while(active.get()&&it.isAlive){if(!selectionCurrent()){close();break};delay(100)}}}}
    internal suspend fun awaitServer()=withTimeout(10_000){while(true){check(isCurrent);val p=requireNotNull(process);check(p.isAlive);verifyPrivate();check(!File(localRoot,"failure.json").exists());val file=File(localRoot,"server.json");if(file.exists()){
        val v=VueControlRecords.read(file);require(v.keys==setOf("schema","token","pid","start","group","supervisorPid","supervisorStart"));require(v["schema"]?.jsonPrimitive?.intOrNull==1&&v["token"]?.jsonPrimitive?.content==token)
        val pid=requireNotNull(v["pid"]?.jsonPrimitive?.intOrNull);val group=requireNotNull(v["group"]?.jsonPrimitive?.intOrNull);val parent=requireNotNull(v["supervisorPid"]?.jsonPrimitive?.intOrNull);require(pid>1&&pid==group)
        val current=VueControlRecords.procIdentity(pid);val supervisor=VueControlRecords.procIdentity(parent);require(current.start==v["start"]?.jsonPrimitive?.content&&current.parent==parent&&current.group==group&&current.state!="Z"&&supervisor.start==v["supervisorStart"]?.jsonPrimitive?.content&&supervisor.state!="Z");return@withTimeout
    };delay(20)}}
    override fun close(){if(active.getAndSet(false))retained.launch{retire()}}
    override suspend fun retire():VueTeardownReceipt=withContext(NonCancellable+Dispatchers.IO){retirement.withLock{
        receipt?.let{return@withLock it};active.set(false);val p=synchronized(lifecycle){process};val stopped=if(p==null)VueTeardownReceipt(true,true,true,false)else p.stopOwned()
        val clean=stopped.complete&&stopped.directChildReaped&&stopped.groupEmpty&&runCatching{removePrivate();true}.getOrDefault(false)
        stopped.copy(complete=stopped.complete&&clean,privateCleanup=clean,reason=if(clean)stopped.reason else stopped.reason?:"Vue private cleanup not confirmed").also{receipt=it;if(it.complete)VueRootOwners.release(this@LocalVuePreparedOwner)}
    }}
    private fun removePrivate(){check(VueControlRecords.directoryIdentity(localRoot)==rootIdentity);var entries=0;var bytes=0L
        fun remove(file:File){check(++entries<=16384);val st=Files.readAttributes(file.toPath(),"unix:mode,size",LinkOption.NOFOLLOW_LINKS);val mode=(st["mode"] as Number).toInt();require(mode and 0xf000 in setOf(0x4000,0x8000));bytes+=(st["size"] as Number).toLong();require(bytes<=536870912);if(mode and 0xf000==0x4000)requireNotNull(file.listFiles()).forEach(::remove);check(file.delete())};remove(localRoot)}
    companion object {
        fun allocate(appRoot:File,identity:VueManagedLaunchIdentity,request:VueOwnerRequest):LocalVuePreparedOwner {
            require(LspVueLaunchPolicy.matchesIdentity(identity)&&request.ownerGeneration in 1..9007199254740991L&&request.consentIdentity.matches(Regex("[0-9a-f]{64}")))
            val workspace=File(request.workspaceRoot);val project=File(request.projectLocalCanonical);require(workspace.canonicalPath==workspace.absolutePath&&project.canonicalPath==project.absolutePath&&project.isDirectory&&workspace==project&&request.rootPath==project.canonicalPath)
            val temp=File(appRoot,"runtime-tmp");require(temp.isDirectory&&!Files.isSymbolicLink(temp.toPath()));val token=UUID.randomUUID().toString().replace("-","");val local=File(temp,"droide-vue-$token");Files.createDirectory(local.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));val guest="/tmp/${local.name}";val owner=LocalVuePreparedOwner(identity,request,local,guest,token)
            try{VueRootOwners.claim(owner);for(name in listOf("home","tmp","config","cache","data","state","run","commands"))Files.createDirectory(File(local,name).toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                val seed=LocalUbuntuVueLaunchAuthority.objectAt(File(appRoot,"packages/lsp.vue/3.3.13/arm64-v8a/${LocalUbuntuVueLaunchAuthority.HEALTH_SEED}"),65536)
                val config=JsonObject(seed+mapOf("schema" to JsonPrimitive(1),"mode" to JsonPrimitive("editor"),"policy" to JsonPrimitive(LspVueLaunchPolicy.PROFILE),"privateRoot" to JsonPrimitive(guest),"privateIdentity" to JsonPrimitive(owner.rootIdentity),"token" to JsonPrimitive(token),"ownerGeneration" to JsonPrimitive(request.ownerGeneration),"consentIdentity" to JsonPrimitive(request.consentIdentity),"rootPath" to JsonPrimitive(request.rootPath)))
                owner.ownerBytes=config.toString().toByteArray();VueControlRecords.write(File(local,"owner.json"),owner.ownerBytes);return owner
            }catch(failure:Throwable){owner.close();throw failure}
        }
    }
}
internal class LocalVueHostedProcess(private val process:Process,override val owner:LocalVuePreparedOwner,private val fallback:suspend ()->Unit):VueHostedProcess {
    @Volatile internal var knownDead=false
    override val stdin:OutputStream get()=process.outputStream
    override val stdout:InputStream get()=process.inputStream
    override val stderr:InputStream get()=process.errorStream
    override val isAlive:Boolean get()=process.isAlive
    override val exitCode:Int? get()=runCatching{process.exitValue()}.getOrNull()
    override fun close()=owner.close()
    override suspend fun stopAndAwaitOwnedGroup():VueTeardownReceipt=owner.retire()
    override suspend fun awaitExit():Int{withContext(Dispatchers.IO){process.waitFor()};knownDead=true;check(owner.retire().complete){"Vue owned cleanup remains UNKNOWN"};return process.exitValue()}
    internal suspend fun stopOwned():VueTeardownReceipt {
        runCatching{owner.verifyPrivate();VueControlRecords.write(File(owner.localRoot,"stop.json"),"{\"stop\":true}".toByteArray())}
        var outer=process.waitFor(9,TimeUnit.SECONDS)
        if(!outer){withTimeoutOrNull(500){runCatching{fallback()}};process.destroy();outer=process.waitFor(250,TimeUnit.MILLISECONDS);if(!outer){process.destroyForcibly();outer=process.waitFor(250,TimeUnit.MILLISECONDS)}}
        knownDead=outer;val value=runCatching{VueControlRecords.read(File(owner.localRoot,"teardown.json"))}.getOrNull();val bound=value?.get("token")?.jsonPrimitive?.content==owner.token&&value["schema"]?.jsonPrimitive?.intOrNull==1
        val direct=bound&&value?.get("directChildReaped")?.jsonPrimitive?.booleanOrNull==true;val empty=bound&&value?.get("groupEmpty")?.jsonPrimitive?.booleanOrNull==true
        runCatching{stdin.close()};runCatching{stdout.close()};runCatching{stderr.close()};return VueTeardownReceipt(outer&&direct&&empty&&value?.get("status")?.jsonPrimitive?.content=="COMPLETE",direct,empty,false,if(outer&&direct&&empty)null else "Vue ordinary process-group cleanup UNKNOWN; owner retained")
    }
}

/** Retains exact owners across cancellation and UNKNOWN; persistent private records block app-restart replacement. */
internal object VueRootOwners {
    private val lock=Any()
    private val roots=mutableMapOf<String,LocalVuePreparedOwner>()
    fun claim(owner:LocalVuePreparedOwner)=synchronized(lock){
        val key=owner.request.rootPath;check(roots[key]==null){"A prior Vue owner is still retained for this root"};check(roots.size<128)
        val siblings=requireNotNull(owner.localRoot.parentFile.listFiles());require(siblings.size<=16384)
        for(candidate in siblings){if(candidate==owner.localRoot||!candidate.name.startsWith("droide-vue-"))continue
            require(!Files.isSymbolicLink(candidate.toPath()))
            val record=File(candidate,"owner.json");if(record.exists())check(VueControlRecords.read(record)["rootPath"]?.jsonPrimitive?.content!=key){"A prior Vue private owner requires verified cleanup before replacement"}
        }
        roots[key]=owner
    }
    fun release(owner:LocalVuePreparedOwner)=synchronized(lock){if(roots[owner.request.rootPath]===owner)roots.remove(owner.request.rootPath);Unit}
}

internal object VueControlRecords {
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
