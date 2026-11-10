package com.baystudio.droide.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class TomlSnapshotEntry(val path:String,val bytes:ByteArray?,val sha256:String?,val origin:String,val optionalConfig:Boolean=false)
data class TomlSnapshotDescriptor(val generation:String,val rootUri:String,val selectedConfigPath:String?,val entries:List<TomlSnapshotEntry>)
data class TomlOwnerRequest(val workspaceRoot:String,val rootPath:String,val ownerGeneration:Long,val consentIdentity:String,val snapshot:TomlSnapshotDescriptor)
data class TomlControlEvent(val kind:String,val path:String?=null,val reason:String?=null,val requestId:JsonPrimitive?=null) {
    val fatal:Boolean get()=kind in setOf("read_miss","remote_schema_unavailable","policy_failure")
}
data class TomlTeardownReceipt(val complete:Boolean,val directChildReaped:Boolean,val groupEmpty:Boolean,val privateCleanup:Boolean,val reason:String?=null)
interface TomlPreparedOwner:Closeable {
    val identity:TomlManagedLaunchIdentity;val request:TomlOwnerRequest;val token:String;val isCurrent:Boolean
    suspend fun retire():TomlTeardownReceipt
}
interface TomlHostedProcess:HostedStdioProcess {
    val owner:TomlPreparedOwner
    val fatalControl:TomlControlEvent?
    suspend fun awaitConfigurationResponseAccepted():JsonPrimitive
    fun onControl(listener:(TomlControlEvent)->Unit)
    suspend fun stopAndAwaitOwnedGroup():TomlTeardownReceipt
}

internal class TomlDeadline private constructor(val endNanos:Long,private val now:()->Long) {
    val expired:Boolean get()=now()>=endNanos
    fun remainingMillis():Long=((endNanos-now()).coerceAtLeast(0)/1_000_000L)
    fun limited(ms:Long)=TomlDeadline(minOf(endNanos,now()+ms*1_000_000L),now)
    fun reserve(ms:Long)=TomlDeadline(endNanos-ms*1_000_000L,now)
    fun check(){check(!expired){"TOML monotonic deadline exhausted"}}
    companion object {fun after(ms:Long,now:()->Long=System::nanoTime):TomlDeadline {require(ms>0);return TomlDeadline(now()+ms*1_000_000L,now)}}
}

/** JVM monitor acquisition and pipe writes/closes are not coroutine-cancellable. Never join unbounded. */
internal class TomlIoAttempt<T>(name:String,action:()->T) {
    @Volatile private var finished=false
    @Volatile var failure:Throwable?=null;private set
    private var value:T?=null
    init {Thread({try{value=action()}catch(t:Throwable){failure=t}finally{finished=true}},"droide-toml-$name").apply{isDaemon=true;start()}}
    val complete:Boolean get()=finished
    val succeeded:Boolean get()=finished&&failure==null
    suspend fun await(deadline:TomlDeadline):Boolean {while(!finished&&!deadline.expired)delay(minOf(10L,deadline.remainingMillis().coerceAtLeast(1)));return finished}
    @Suppress("UNCHECKED_CAST") fun result():T {check(finished);failure?.let{throw it};return value as T}
}

/** Strict envelope reader. The generic JsonRpcProcess sees only genuine upstream objects. */
internal class TomlEnvelopeInput(private val source:InputStream,private val token:String,private val generation:String,
    private val scope:CoroutineScope,private val terminal:(TomlControlEvent)->Unit,
    private val upstream:(JsonObject)->Boolean={true}):InputStream() {
    private val lock=java.lang.Object();private val queue=ArrayDeque<ByteArray>();private var queued=0;private var current:ByteArray?=null;private var offset=0
    @Volatile private var readerStarted=false;@Volatile private var readerFinished=false
    val readerSettled:Boolean get()=!readerStarted||readerFinished
    private var ended=false;private var sequence=0;private var configurationId:JsonPrimitive?=null;private var offline=false
    private val configured=CompletableDeferred<JsonPrimitive>();private val controls=mutableListOf<TomlControlEvent>();private val listeners=mutableListOf<kotlinx.coroutines.channels.Channel<TomlControlEvent>>()
    @Volatile var failure:TomlControlEvent?=null;private set
    fun begin(){readerStarted=true;scope.launch(Dispatchers.IO){try{while(true){val body=TomlWire.readFrame(source,8_392_704)?:break;accept(TomlWire.strict(body))}}catch(_:Throwable){fail(TomlControlEvent("policy_failure",reason="envelope_protocol"))}finally{readerFinished=true;synchronized(lock){ended=true;listeners.forEach{it.close()};lock.notifyAll()};if(!configured.isCompleted)configured.completeExceptionally(IOException("TOML configuration unavailable"))}}}
    internal fun accept(v:JsonObject){
        require(v.keys==setOf("schema","kind","ownerToken","generation","sequence",if(v["kind"]?.jsonPrimitive?.content=="upstream")"message" else "event"))
        require(v["schema"]==JsonPrimitive(1)&&v["ownerToken"]==JsonPrimitive(token)&&v["generation"]==JsonPrimitive(generation))
        val seq=v["sequence"] as? JsonPrimitive;require(seq!=null&&!seq.isString&&sequence<Int.MAX_VALUE&&seq.intOrNull==sequence+1);sequence++
        when(v["kind"]?.jsonPrimitive?.content){
            "upstream"->{require(failure==null);val m=v["message"] as? JsonObject?:error("Invalid upstream");TomlWire.rpc(m)
                if(m["method"]?.jsonPrimitive?.content=="workspace/configuration"){require(configurationId==null);configurationId=TomlWire.id(m["id"])}
                if(upstream(m)){val bytes=TomlWire.frame(m);synchronized(lock){require(queue.size<128&&queued+bytes.size<=16_777_216);queue.addLast(bytes);queued+=bytes.size;lock.notifyAll()}}
            }
            "control"->{val e=v["event"] as? JsonObject?:error("Invalid control");require(e.toString().toByteArray().size<=8192)
                val kind=e["kind"]?.jsonPrimitive?.content?:error("Invalid control kind");require(kind in setOf("offline_default_catalog_disabled","configuration_response_accepted","read_miss","remote_schema_unavailable","policy_failure"))
                require(e.keys.all{it in setOf("kind","path","reason","requestId","phase")});val event=TomlControlEvent(kind,e["path"]?.jsonPrimitive?.content,e["reason"]?.jsonPrimitive?.content,e["requestId"]?.let(TomlWire::id))
                require(event.path==null||(event.path.length<=4096&&event.path.startsWith('/')&&event.path.none{it.code<32||it.code==127}));require(event.reason==null||event.reason.matches(Regex("[a-z_]{1,80}")))
                if(kind=="configuration_response_accepted"){require(!configured.isCompleted&&configurationId!=null&&event.requestId==configurationId);configured.complete(requireNotNull(event.requestId))}
                if(kind=="offline_default_catalog_disabled"){require(!offline);offline=true}
                if(event.fatal)fail(event)else publish(event)
            }
            else->error("Invalid envelope kind")
        }
    }
    fun fail(e:TomlControlEvent){val first=synchronized(lock){if(failure!=null)false else{failure=e;queue.clear();queued=0;lock.notifyAll();true}};if(first){terminal(e);if(!configured.isCompleted)configured.completeExceptionally(IOException("TOML owner fenced"));publish(e)}}
    private fun publish(e:TomlControlEvent){synchronized(lock){require(controls.size<128);controls+=e;listeners.forEach{if(!ended)check(it.trySend(e).isSuccess)}}}
    fun onControl(listener:(TomlControlEvent)->Unit){val channel=kotlinx.coroutines.channels.Channel<TomlControlEvent>(128);synchronized(lock){require(listeners.size<16);controls.forEach{check(channel.trySend(it).isSuccess)};listeners+=channel;if(ended)channel.close()};scope.launch{for(event in channel)runCatching{listener(event)}}}
    suspend fun awaitConfigurationResponseAccepted():JsonPrimitive=configured.await()
    override fun read():Int {val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255}
    override fun read(b:ByteArray,off:Int,len:Int):Int=synchronized(lock){require(off>=0&&len>=0&&off+len<=b.size);if(len==0)return@synchronized 0
        while(current==null){if(queue.isNotEmpty()){current=queue.removeFirst();queued-=current!!.size;offset=0;break};if(ended||failure!=null)return@synchronized -1;lock.wait()}
        val c=requireNotNull(current);val n=minOf(len,c.size-offset);c.copyInto(b,off,offset,offset+n);offset+=n;if(offset==c.size)current=null;n}
    override fun close(){synchronized(lock){ended=true;queue.clear();queued=0;listeners.forEach{it.close()};lock.notifyAll()};source.close()}
}

internal class LocalTomlPreparedOwner private constructor(override val identity:TomlManagedLaunchIdentity,override val request:TomlOwnerRequest,
    val localRoot:File,val guestRoot:String,override val token:String):TomlPreparedOwner {
    private val active=AtomicBoolean(true);private val lifecycle=Any();private val retirement=Mutex();private val retained=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val rootIdentity=TomlWire.directoryIdentity(localRoot);private val projectAncestors=TomlWire.ancestors(File(request.rootPath))
    private lateinit var ownerBytes:ByteArray;private var launchSpec:TerminalLaunchSpec?=null
    @Volatile private var process:LocalTomlHostedProcess?=null;@Volatile private var receipt:TomlTeardownReceipt?=null;@Volatile private var selectionCurrent:()->Boolean={true};private var bound=false
    @Volatile internal var hostBirth:TomlWire.ProcIdentity?=null;@Volatile internal var nodeBirth:TomlWire.ProcIdentity?=null
    override val isCurrent:Boolean get()=active.get()&&selectionCurrent()&&process?.knownDead!=true&&process?.fatalControl==null
    internal fun fence(){active.set(false)}
    internal fun bindSelectionCurrentness(current:()->Boolean)=synchronized(lifecycle){check(isCurrent&&!bound);bound=true;selectionCurrent=current;check(isCurrent)}
    internal fun storePreparedLaunchSpec(value:TerminalLaunchSpec)=synchronized(lifecycle){check(isCurrent&&launchSpec==null);launchSpec=value}
    internal fun requirePreparedLaunchSpec():TerminalLaunchSpec=synchronized(lifecycle){check(isCurrent&&process==null);requireNotNull(launchSpec)}
    internal fun verifyPrivate(){check(TomlWire.directoryIdentity(localRoot)==rootIdentity);check(TomlWire.readBytes(File(localRoot,"owner.json"),12_582_912).contentEquals(ownerBytes))}
    internal fun verifyProject(){check(TomlWire.ancestors(File(request.rootPath))==projectAncestors);require(File(request.workspaceRoot).canonicalPath==request.workspaceRoot)}
    internal fun shellCommand(command:List<String>):String {
        val q=LocalExecutionSubstrate::shellQuote;val body="set -eu; umask 077; line=\$(/system/bin/toybox cat /proc/\$\$/stat); fields=\${line##*) }; set -- \$fields; [ \"\$3\" = \"\$\$\" ]; start=\${20}; printf '{\"schema\":1,\"token\":\"$token\",\"pid\":%s,\"start\":\"%s\",\"group\":%s}' \"\$\$\" \"\$start\" \"\$3\" > ${q(File(localRoot,"host.json").absolutePath)}; exec "+command.joinToString(" ",transform=q)
        return "exec /system/bin/toybox setsid /system/bin/sh -c "+q(body)
    }
    internal fun createProcess(factory:()->Process):LocalTomlHostedProcess=synchronized(lifecycle){check(isCurrent&&process==null);LocalTomlHostedProcess(factory(),this,retained).also{process=it;ExitHostedProcessRegistry.retain(it)}}
    internal fun beginObservation(){val p=synchronized(lifecycle){requireNotNull(process)};p.begin()}
    internal suspend fun awaitServer()=withTimeout(10_000){while(true){check(isCurrent&&process?.isAlive==true);verifyPrivate();val hf=File(localRoot,"host.json");val nf=File(localRoot,"server.json")
        if(hf.exists()&&nf.exists()){val h=TomlWire.strict(TomlWire.readBytes(hf,65536));val n=TomlWire.strict(TomlWire.readBytes(nf,65536));require(h["token"]?.jsonPrimitive?.content==token&&n["token"]?.jsonPrimitive?.content==token&&n["generation"]?.jsonPrimitive?.content==request.snapshot.generation)
            val hp=TomlWire.proc(requireNotNull(h["pid"]?.jsonPrimitive?.intOrNull));val np=TomlWire.proc(requireNotNull(n["pid"]?.jsonPrimitive?.intOrNull));require(hp.start==h["start"]?.jsonPrimitive?.content&&hp.group==hp.pid&&hp.state!="Z");require(np.start==n["start"]?.jsonPrimitive?.content&&np.group==hp.group&&np.state!="Z");hostBirth=hp;nodeBirth=np;return@withTimeout};delay(20)}}
    override fun close(){fence();retained.launch{retire()}}
    override suspend fun retire():TomlTeardownReceipt=withContext(NonCancellable+Dispatchers.IO){retirement.withLock{
        receipt?.let{return@withLock it};fence();val p=synchronized(lifecycle){process};val stopped=if(p==null)TomlTeardownReceipt(true,true,true,false)else runCatching{p.stopOwned()}.getOrElse{TomlTeardownReceipt(false,false,false,false,"TOML custody observation failed; exact owner retained")}
        val cleaned=stopped.complete&&stopped.directChildReaped&&stopped.groupEmpty&&runCatching{verifyPrivate();TomlWire.removePrivate(localRoot,rootIdentity);true}.getOrDefault(false)
        stopped.copy(complete=stopped.complete&&cleaned,privateCleanup=cleaned,reason=if(cleaned)stopped.reason else stopped.reason?:"TOML private cleanup remains UNKNOWN").also{receipt=it;if(it.complete)TomlRootOwners.release(this@LocalTomlPreparedOwner)}
    }}
    companion object {
        fun allocate(appRoot:File,identity:TomlManagedLaunchIdentity,request:TomlOwnerRequest):LocalTomlPreparedOwner {
            require(LspTomlLaunchPolicy.matchesIdentity(identity)&&request.ownerGeneration in 1..9_007_199_254_740_991L&&request.consentIdentity.matches(Regex("[0-9a-f]{64}")))
            require(request.rootPath==File(request.rootPath).canonicalPath&&File(request.rootPath).toPath().startsWith(File(request.workspaceRoot).toPath()))
            val frozen=request.copy(snapshot=request.snapshot.copy(entries=request.snapshot.entries.map{it.copy(bytes=it.bytes?.copyOf())}))
            val snapshot=TomlWire.snapshot(frozen.snapshot,frozen.rootPath);val parent=File(appRoot,"runtime-tmp");require(parent.isDirectory&&!Files.isSymbolicLink(parent.toPath()))
            val token=UUID.randomUUID().toString().replace("-","");val local=File(parent,"droide-toml-$token");Files.createDirectory(local.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val owner=LocalTomlPreparedOwner(identity,frozen,local,"/run/droide-toml/${local.name}",token)
            try{TomlRootOwners.claim(owner);for(name in listOf("home","tmp","config","cache","data","state","run"))Files.createDirectory(File(local,name).toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                val value=buildJsonObject{put("schema",1);put("policy",LspTomlLaunchPolicy.PROFILE);put("mode","editor");put("token",token);put("ownerGeneration",request.ownerGeneration);put("consentIdentity",request.consentIdentity);put("privateRoot",owner.guestRoot);put("privateIdentity",owner.rootIdentity);put("packageRoot",LspTomlLaunchPolicy.ROOT);put("nodeExecutable",identity.nodeExecutable);put("snapshot",snapshot)}
                owner.ownerBytes=value.toString().toByteArray();TomlWire.write(File(local,"owner.json"),owner.ownerBytes);return owner
            }catch(t:Throwable){owner.close();throw t}
        }
    }
}

/** Observes only complete client frames; does not alter ordinary JSON-RPC bytes or semantic payloads. */
internal class TomlOutgoingFrames(private val receive:(JsonObject)->Unit) {
    private var bytes=ByteArray(0)
    fun feed(chunk:ByteArray,off:Int,len:Int){require(off>=0&&len>=0&&off+len<=chunk.size&&bytes.size+len<=16_777_216);bytes+=chunk.copyOfRange(off,off+len)
        while(true){var end=-1;for(i in 0 until (bytes.size-3).coerceAtLeast(0)){if(bytes[i]==13.toByte()&&bytes[i+1]==10.toByte()&&bytes[i+2]==13.toByte()&&bytes[i+3]==10.toByte()){end=i;break}}
            if(end<0){require(bytes.size<=8192);return};require(end<=8192);val header=bytes.copyOfRange(0,end).toString(Charsets.US_ASCII)
            val lengths=header.split("\r\n").filter{it.substringBefore(':').lowercase()=="content-length"};require(lengths.size==1)
            val length=lengths.single().substringAfter(':').trim();require(length.matches(Regex("[1-9][0-9]{0,7}")));val n=length.toInt();require(n<=8_388_608);if(bytes.size<end+4+n)return
            val frame=bytes.copyOfRange(0,end+4+n);bytes=bytes.copyOfRange(end+4+n,bytes.size);val message=TomlWire.strict(requireNotNull(TomlWire.readFrame(ByteArrayInputStream(frame),8_388_608)));TomlWire.rpc(message);receive(message)
        }
    }
}

internal class LocalTomlHostedProcess(private val process:Process,override val owner:LocalTomlPreparedOwner,private val retained:CoroutineScope):TomlHostedProcess {
    @Volatile internal var knownDead=false;@Volatile private var initialized=false
    private val writerLock=Any();private val activeWrites=java.util.concurrent.atomic.AtomicInteger();private val activeErrorReads=java.util.concurrent.atomic.AtomicInteger();private val stopId=JsonPrimitive("droide-toml-retire-${owner.token}");private val stopped=CompletableDeferred<Unit>()
    @Volatile private var initializeId:JsonPrimitive?=null;@Volatile private var shutdownId:JsonPrimitive?=null;@Volatile private var exitForwarded=false;@Volatile private var shutdownAccepted=false
    private val outgoing=TomlOutgoingFrames{m->when(m["method"]?.jsonPrimitive?.content){"initialize"->{check(initializeId==null);initializeId=TomlWire.id(m["id"])};"shutdown"->{check(shutdownId==null);shutdownId=TomlWire.id(m["id"])};"exit"->{check(shutdownAccepted);exitForwarded=true}}}
    private val envelopes=TomlEnvelopeInput(process.inputStream,owner.token,owner.request.snapshot.generation,retained,{owner.fence();retained.launch{owner.retire()}},{m->
        if(m["method"]==null&&m["id"]==initializeId&&initializeId!=null&&m["result"] is JsonObject&&(m["result"] as JsonObject).containsKey("capabilities"))initialized=true
        if(m["method"]==null&&m["id"]==shutdownId&&shutdownId!=null){if(m["result"]==JsonNull&&!m.containsKey("error")){shutdownAccepted=true;stopped.complete(Unit)}else stopped.completeExceptionally(IOException("Native shutdown failed"))};m["id"]!=stopId})
    override val fatalControl:TomlControlEvent? get()=envelopes.failure
    private fun<T> writing(action:()->T):T {activeWrites.incrementAndGet();try{return synchronized(writerLock){action()}}finally{activeWrites.decrementAndGet()}}
    override val stdin:OutputStream=object:OutputStream(){override fun write(b:Int){write(byteArrayOf(b.toByte()))};override fun write(b:ByteArray,off:Int,len:Int)=writing{check(owner.isCurrent&&fatalControl==null);outgoing.feed(b,off,len);process.outputStream.write(b,off,len)};override fun flush()=writing{check(owner.isCurrent&&fatalControl==null);process.outputStream.flush()};override fun close(){/* Retirement owns genuine shutdown before closing the actual pipe. */}}
    override val stdout:InputStream get()=envelopes
    override val stderr:InputStream=object:InputStream(){private fun reading(action:()->Int):Int{activeErrorReads.incrementAndGet();try{return action()}finally{activeErrorReads.decrementAndGet()}};override fun read()=reading{process.errorStream.read()};override fun read(b:ByteArray,off:Int,len:Int)=reading{process.errorStream.read(b,off,len)};override fun close(){/* The bounded owner cleanup closes the actual pipe. */}}
    override val isAlive:Boolean get()=process.isAlive
    override val exitCode:Int? get()=runCatching{process.exitValue()}.getOrNull()
    override fun onControl(listener:(TomlControlEvent)->Unit)=envelopes.onControl(listener)
    override suspend fun awaitConfigurationResponseAccepted():JsonPrimitive=envelopes.awaitConfigurationResponseAccepted()
    internal fun begin(){envelopes.begin();retained.launch{while(process.isAlive&&!knownDead){try{if(owner.hostBirth!=null)observe();if(!owner.isCurrent){owner.retire();break}}catch(_:Throwable){envelopes.fail(TomlControlEvent("policy_failure",reason="process_observation"))};delay(TomlResourcePolicy.MONITOR_MS)}}}
    private fun observe(){val h=requireNotNull(owner.hostBirth);val n=requireNotNull(owner.nodeBirth);val group=TomlWire.group(h.group);require(group.size<=TomlResourcePolicy.GROUP_MEMBERS_MAX);require(group.sumOf{it.rss}<=TomlResourcePolicy.OBSERVED_RSS_BYTES_MAX)
        require(group.any{it.pid==h.pid&&it.start==h.start}&&group.any{it.pid==n.pid&&it.start==n.start});require(group.none{it.parent==n.pid&&it.pid!=n.pid}){"Unexpected Taplo child"}}
    override fun close()=owner.close()
    override suspend fun stopAndAwaitOwnedGroup():TomlTeardownReceipt=owner.retire()
    override suspend fun awaitExit():Int{withContext(Dispatchers.IO){process.waitFor()};knownDead=true;check(owner.retire().complete){"TOML cleanup UNKNOWN"};return process.exitValue()}
    private fun writeControl(value:JsonObject)=writing{val bytes=TomlWire.frame(value);outgoing.feed(bytes,0,bytes.size);process.outputStream.write(bytes);process.outputStream.flush()}
    internal suspend fun stopOwned():TomlTeardownReceipt {
        val deadline=TomlDeadline.after(TomlResourcePolicy.RETIRE_MS)
        val attempts=mutableListOf<TomlIoAttempt<*>>()
        fun<T> attempt(name:String,action:()->T)=TomlIoAttempt(name,action).also{attempts+=it}
        if(process.isAlive&&initialized&&fatalControl==null){
            val grace=deadline.limited(TomlResourcePolicy.SHUTDOWN_GRACE_MS)
            runCatching{if(shutdownId==null)attempt("shutdown"){writeControl(buildJsonObject{put("jsonrpc","2.0");put("id",stopId);put("method","shutdown")})}.await(grace)
                while(!stopped.isCompleted&&!grace.expired)delay(10)
                if(shutdownAccepted&&!exitForwarded&&!grace.expired)attempt("exit"){writeControl(buildJsonObject{put("jsonrpc","2.0");put("method","exit")})}.await(grace)}
        }
        // A blocked close or writer must never delay independent TERM/KILL/reap.
        attempt("stdin-close"){process.outputStream.close()}
        val reaping=attempt("reap"){process.waitFor()}
        var birth=owner.hostBirth
        if(birth==null)birth=runCatching{val h=TomlWire.strict(TomlWire.readBytes(File(owner.localRoot,"host.json"),65536));require(h["token"]?.jsonPrimitive?.content==owner.token);TomlWire.recordedBirth(h)}.getOrNull()
        var custody=birth!=null
        for(signal in listOf(15,9)){val b=birth;if(b==null){custody=false;break};val group=runCatching{TomlWire.group(b.group)}.getOrElse{custody=false;emptyList()};if(group.isEmpty())break
            val leader=group.singleOrNull{it.pid==b.pid};if(leader==null||leader.start!=b.start){custody=false;break}
            val signalled=attempt("group-$signal"){TomlWire.signalGroup(b,signal)};signalled.await(deadline.limited(1500));if(!signalled.succeeded)custody=false
            if(signal==15&&!deadline.expired)delay(minOf(200L,deadline.remainingMillis()))
        }
        if(process.isAlive){attempt("destroy"){process.destroy()}.await(deadline.limited(250));if(process.isAlive)attempt("kill"){process.destroyForcibly()}.await(deadline.limited(250))}
        attempt("stdout-close"){envelopes.close()};attempt("stderr-close"){process.errorStream.close()}

        val settle=deadline.reserve(500)
        while(!settle.expired&&(attempts.any{!it.complete}||activeWrites.get()!=0||activeErrorReads.get()!=0||!envelopes.readerSettled))delay(10)
        val reaped=reaping.succeeded&&!process.isAlive
        val empty=birth?.let{b->runCatching{TomlWire.group(b.group).isEmpty()}.getOrDefault(false)}?:false
        knownDead=reaped
        val settled=attempts.all{it.succeeded}&&activeWrites.get()==0&&activeErrorReads.get()==0&&envelopes.readerSettled
        val complete=reaped&&empty&&custody&&settled&&!deadline.expired
        return TomlTeardownReceipt(complete,reaped,empty,false,if(complete)null else "TOML exact owner or I/O cleanup UNKNOWN; retained for repair")
    }
}

internal object TomlRootOwners {
    private val lock=Any();private val roots=mutableMapOf<String,LocalTomlPreparedOwner>()
    fun claim(owner:LocalTomlPreparedOwner)=synchronized(lock){check(roots[owner.request.rootPath]==null);val siblings=requireNotNull(owner.localRoot.parentFile.listFiles());require(siblings.size<=16384)
        for(dir in siblings){if(dir==owner.localRoot||!dir.name.startsWith("droide-toml-"))continue;require(!Files.isSymbolicLink(dir.toPath()));val record=File(dir,"owner.json");if(record.exists()){val old=TomlWire.strict(TomlWire.readBytes(record,12_582_912));check(old["snapshot"]?.jsonObject?.get("rootUri")?.jsonPrimitive?.content!=owner.request.snapshot.rootUri){"A prior TOML owner requires verified cleanup"}}};roots[owner.request.rootPath]=owner}
    fun release(owner:LocalTomlPreparedOwner)=synchronized(lock){if(roots[owner.request.rootPath]===owner)roots.remove(owner.request.rootPath);Unit}
}

internal object TomlWire {
    fun id(value:JsonElement?):JsonPrimitive {val p=value as? JsonPrimitive?:error("Invalid RPC id");require(p!==JsonNull&&(p.isString||p.intOrNull!=null));return p}
    fun rpc(m:JsonObject){require(m["jsonrpc"]==JsonPrimitive("2.0"));if(m.containsKey("id"))id(m["id"]);if(m.containsKey("method"))require(m["method"] is JsonPrimitive&&m["method"]!!.jsonPrimitive.isString&&!m.containsKey("result")&&!m.containsKey("error"))else require(m.containsKey("id")&&(m.containsKey("result") xor m.containsKey("error")))}
    fun frame(m:JsonObject):ByteArray {val b=m.toString().toByteArray();require(b.size<=8_388_608);return "Content-Length: ${b.size}\r\n\r\n".toByteArray()+b}
    fun readFrame(input:InputStream,max:Int):ByteArray? {val header=ByteArrayOutputStream();var tail=0;while(true){val b=input.read();if(b<0){require(header.size()==0);return null};require(b in 9..127);header.write(b);require(header.size()<=8192);tail=(tail shl 8)or b;if(tail==0x0d0a0d0a)break}
        var length:Int?=null;var contentType=false;for(line in header.toString("US-ASCII").removeSuffix("\r\n\r\n").split("\r\n")){val m=Regex("([A-Za-z-]+):[ \\t]*(.*)").matchEntire(line)?:error("Invalid header");when(m.groupValues[1].lowercase()){"content-length"->{require(length==null&&m.groupValues[2].matches(Regex("[1-9][0-9]{0,7}")));length=m.groupValues[2].toInt()};"content-type"->{require(!contentType&&m.groupValues[2].matches(Regex("application/(vscode-jsonrpc|json)(;\\s*charset=utf-8)?",RegexOption.IGNORE_CASE)));contentType=true};else->error("Unknown header")}}
        val n=requireNotNull(length);require(n in 1..max);val bytes=ByteArray(n);var i=0;while(i<n){val read=input.read(bytes,i,n-i);require(read>0);i+=read};return bytes}
    fun strict(bytes:ByteArray):JsonObject {require(bytes.size in 1..12_582_912);val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();var i=0;var nodes=0
        fun ws(){while(i<text.length&&text[i] in " \t\r\n")i++}
        fun string():String{require(i<text.length&&text[i]=='"');val begin=i++;var escaped=false;while(i<text.length){val c=text[i++];if(c=='"'&&!escaped)return Json.parseToJsonElement(text.substring(begin,i)).jsonPrimitive.content;escaped=if(c=='\\')!escaped else false};error("Unclosed string")}
        fun value(depth:Int){require(depth<=64&&++nodes<=262144);ws();require(i<text.length);when(text[i]){'{'->{i++;ws();val keys=mutableSetOf<String>();if(i<text.length&&text[i]=='}')i++ else while(true){ws();require(keys.add(string()));ws();require(text[i++]==':');value(depth+1);ws();val c=text[i++];if(c=='}')break;require(c==',')}};'['->{i++;ws();if(i<text.length&&text[i]==']')i++ else while(true){value(depth+1);ws();val c=text[i++];if(c==']')break;require(c==',')}};'"'->string();else->{val begin=i;while(i<text.length&&text[i] !in ",]} \t\r\n")i++;require(i>begin);Json.parseToJsonElement(text.substring(begin,i))}}}
        value(0);ws();require(i==text.length);return Json.parseToJsonElement(text).jsonObject}
    fun snapshot(s:TomlSnapshotDescriptor,root:String):JsonObject {require(s.generation.matches(Regex("[0-9a-f]{64}"))&&s.entries.size<=64&&s.entries.map{it.path}.distinct().size==s.entries.size);require(s.rootUri==java.net.URI("file","",root.trimEnd('/')+"/",null).toASCIIString())
        var total=0;val entries=s.entries.map{e->require(e.path.startsWith("$root/")&&java.nio.file.Paths.get(e.path).normalize().toString()==e.path&&e.path.none{it.code<32||it.code==127||it=='\\'});require(e.origin.length<=8192);require(!e.optionalConfig||e.path in setOf("$root/.taplo.toml","$root/taplo.toml"));e.bytes?.let{require(it.size<=1_048_576);total+=it.size;require(total<=8_388_608&&LspTomlLaunchPolicy.sha(it)==e.sha256)}?:require(e.sha256==null)
            buildJsonObject{put("path",e.path);put("bytes",e.bytes?.let{JsonPrimitive(Base64.getEncoder().encodeToString(it))}?:JsonNull);put("sha256",e.sha256?.let(::JsonPrimitive)?:JsonNull);put("origin",e.origin);put("optionalConfig",e.optionalConfig)}}
        require(s.selectedConfigPath==null||(s.selectedConfigPath in setOf("$root/.taplo.toml","$root/taplo.toml")&&s.entries.any{it.path==s.selectedConfigPath&&it.bytes!=null}))
        return buildJsonObject{put("generation",s.generation);put("rootUri",s.rootUri);put("selectedConfigPath",s.selectedConfigPath?.let(::JsonPrimitive)?:JsonNull);put("entries",JsonArray(entries))}}
    fun ancestors(file:File):Map<String,String>{val out=linkedMapOf<String,String>();var current:File?=file;while(current!=null){require(out.size<64);out[current.absolutePath]=canonicalDirectory(current)+":"+Files.getAttribute(current.toPath(),"unix:mode",NOFOLLOW_LINKS);require(Files.isReadable(current.toPath())&&Files.isExecutable(current.toPath()));current=current.parentFile};return out}
    fun canonicalDirectory(file:File):String{require(file.canonicalPath==file.absolutePath);val a=Files.readAttributes(file.toPath(),"unix:dev,ino,mode",NOFOLLOW_LINKS);require((a["mode"] as Number).toInt() and 0xf000==0x4000);return "${a["dev"]}:${a["ino"]}"}
    fun directoryIdentity(file:File):String{val id=canonicalDirectory(file);require((Files.getAttribute(file.toPath(),"unix:mode",NOFOLLOW_LINKS) as Number).toInt() and 0x1ff==0x1c0);return id}
    fun readBytes(file:File,max:Int):ByteArray{val attrs="unix:dev,ino,size,lastModifiedTime,ctime,nlink,mode";val before=Files.readAttributes(file.toPath(),attrs,NOFOLLOW_LINKS);require((before["mode"] as Number).toInt() and 0xf000==0x8000&&(before["nlink"] as Number).toInt()==1);val size=(before["size"] as Number).toLong();require(size in 0..max.toLong());Files.newByteChannel(file.toPath(),setOf(StandardOpenOption.READ,NOFOLLOW_LINKS)).use{c->val out=ByteBuffer.allocate(size.toInt());while(out.hasRemaining())require(c.read(out)>0);require(c.size()==size&&before==Files.readAttributes(file.toPath(),attrs,NOFOLLOW_LINKS));return out.array()}}
    fun write(file:File,bytes:ByteArray){require(bytes.size<=12_582_912);Files.write(file.toPath(),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);Files.setPosixFilePermissions(file.toPath(),PosixFilePermissions.fromString("rw-------"))}
    data class ProcIdentity(val pid:Int,val parent:Int,val group:Int,val start:String,val state:String,val rss:Long)
    fun proc(pid:Int):ProcIdentity{require(pid>1);val file=File("/proc/$pid/stat");val bytes=file.inputStream().use{it.readBytesBounded(4096)};val text=bytes.toString(Charsets.US_ASCII);val fields=text.substring(text.lastIndexOf(')')+2).trim().split(Regex(" +"));require(fields.size>=22&&fields[19].matches(Regex("[0-9]+")));val status=File("/proc/$pid/status").inputStream().use{it.readBytesBounded(65536)}.toString(Charsets.US_ASCII);val rss=status.lineSequence().firstOrNull{it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)?:if(fields[0]=="Z")0L else error("Unknown RSS");return ProcIdentity(pid,fields[1].toInt(),fields[2].toInt(),fields[19],fields[0],rss)}
    private fun InputStream.readBytesBounded(max:Int):ByteArray{val out=ByteArrayOutputStream();val b=ByteArray(4096);while(true){val n=read(b);if(n<0)break;require(n>0&&out.size()+n<=max);out.write(b,0,n)};return out.toByteArray()}
    fun recordedBirth(record:JsonObject):ProcIdentity {val pid=requireNotNull(record["pid"]?.jsonPrimitive?.intOrNull);val group=requireNotNull(record["group"]?.jsonPrimitive?.intOrNull);val start=requireNotNull(record["start"]?.jsonPrimitive?.content);require(pid>1&&pid==group&&start.matches(Regex("[0-9]+")));return ProcIdentity(pid,0,group,start,"?",0)}
    fun group(group:Int):List<ProcIdentity>{require(group>1);val dirs=requireNotNull(File("/proc").listFiles());require(dirs.size<=TomlResourcePolicy.SCAN_BOUND);return dirs.mapNotNull{d->val pid=d.name.toIntOrNull()?:return@mapNotNull null;if(pid<=1)return@mapNotNull null;try{val text=File(d,"stat").inputStream().use{it.readBytesBounded(4096)}.toString(Charsets.US_ASCII);val fields=text.substring(text.lastIndexOf(')')+2).trim().split(Regex(" +"));require(fields.size>=22);if(fields[2].toInt()!=group)null else proc(pid)}catch(e:java.nio.file.NoSuchFileException){null}catch(e:FileNotFoundException){if(d.exists())throw e else null}}}
    fun signalGroup(birth:ProcIdentity,signal:Int){val now=proc(birth.pid);require(now.start==birth.start&&now.group==birth.group&&now.pid==now.group);val result=ProcessBuilder("/system/bin/toybox","kill","-$signal","--","-${birth.group}").start();check(result.waitFor(1,TimeUnit.SECONDS)&&result.exitValue()==0)}
    fun removePrivate(root:File,identity:String){check(directoryIdentity(root)==identity);var count=0;var bytes=0L;fun remove(f:File){require(++count<=256);val a=Files.readAttributes(f.toPath(),"unix:mode,size",NOFOLLOW_LINKS);val mode=(a["mode"] as Number).toInt() and 0xf000;require(mode in setOf(0x4000,0x8000));bytes+=(a["size"] as Number).toLong();require(bytes<=16_777_216);if(mode==0x4000)requireNotNull(f.listFiles()).forEach(::remove);check(f.delete())};remove(root)}
}
