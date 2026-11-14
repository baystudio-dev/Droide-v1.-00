package com.baystudio.droide.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Absolute caller budget. Every subordinate budget is clamped; clocks are never reset by a phase. */
class GraphqlDeadline private constructor(val endNanos: Long, private val now: () -> Long) {
    val expired: Boolean get() = endNanos - now() <= 0
    fun remainingMillis(): Long = ((endNanos - now()).coerceAtLeast(0) / 1_000_000L)
    fun limited(ms: Long): GraphqlDeadline { require(ms >= 0 && ms <= 900_000); return GraphqlDeadline(minOf(endNanos, now() + ms * 1_000_000L), now) }
    fun reserve(ms: Long): GraphqlDeadline { require(ms >= 0 && ms <= 900_000); return GraphqlDeadline(endNanos - ms * 1_000_000L, now) }
    fun check() { check(!expired) { "GraphQL absolute operation deadline exhausted" } }
    companion object {
        fun after(ms: Long, now: () -> Long = System::nanoTime): GraphqlDeadline { require(ms in 1..900_000); return GraphqlDeadline(now() + ms * 1_000_000L, now) }
        fun at(endNanos:Long,now:()->Long=System::nanoTime)=GraphqlDeadline(endNanos,now)
        fun install() = after(GraphqlResourcePolicy.INSTALL_TOTAL_MS)
        fun start() = after(GraphqlResourcePolicy.START_TOTAL_MS)
        fun feature() = after(GraphqlResourcePolicy.FEATURE_MS)
        fun retirement() = after(GraphqlResourcePolicy.RETIRE_MS)
    }
}

data class GraphqlCapturedSource(val role: String, val uri: String, val relativePath: String, val text: String,
    val digest: String, val origin: String, val openGeneration: Long? = null, val revision: Long? = null)
data class GraphqlOwnerRequest(val workspaceRoot: String, val rootPath: String, val workspaceLifetime: String,
    val rootIdentity: String, val canonicalRootUri: String, val ownerGeneration: Long, val consentIdentity: String,
    val snapshotGeneration: String, val configDigest: String, val schemaDigest: String, val fragmentsDigest: String,
    val immutableCapturedSources: List<GraphqlCapturedSource>, val serializedDescriptorDigest: String)
data class GraphqlControlEvent(val kind: String, val reason: String? = null) {
    val fatal: Boolean get() = kind in setOf("policy_failure", "native_failure", "resource_exhausted", "unavailable")
}
data class GraphqlTeardownReceipt(val complete: Boolean, val directChildReaped: Boolean, val groupEmpty: Boolean,
    val birthCustodyVerified: Boolean, val factorySettled: Boolean, val writersSettled: Boolean, val readersSettled: Boolean,
    val closeAttemptsSettled: Boolean, val privateCleanupVerified: Boolean, val reason: String? = null) {
    val privateCleanup: Boolean get() = privateCleanupVerified
    val settled: Boolean get() = directChildReaped && groupEmpty && birthCustodyVerified && factorySettled && writersSettled && readersSettled && closeAttemptsSettled
    companion object { fun unknown(reason: String) = GraphqlTeardownReceipt(false,false,false,false,false,false,false,false,false,reason) }
}
interface GraphqlPreparedOwner: Closeable {
    val identity: GraphqlManagedLaunchIdentity
    val request: GraphqlOwnerRequest
    val token: String
    val isCurrent: Boolean
    fun fence()
    suspend fun retire(deadline: GraphqlDeadline): GraphqlTeardownReceipt
}
interface GraphqlHostedProcess: HostedStdioProcess {
    val owner: GraphqlPreparedOwner
    val fatalControl: GraphqlControlEvent?
    suspend fun awaitBootstrapAccepted(deadline: GraphqlDeadline)
    fun onControl(listener: (GraphqlControlEvent) -> Unit)
    fun registerRequest(id: JsonPrimitive, authority: JsonObject, deadline: GraphqlDeadline)
    fun registerDocument(uri: String, openGeneration: Long, version: Long, textDigest: String)
    suspend fun stopAndAwaitOwnedGroup(deadline: GraphqlDeadline): GraphqlTeardownReceipt
}

/** Descriptor is captured data, never a path lookup. Canonical serialization is shared with the client. */
object GraphqlDescriptorCodec {
    fun sources(sources: List<GraphqlCapturedSource>): JsonArray = JsonArray(sources.map { s -> buildJsonObject {
        put("uri",s.uri);put("relativePath",s.relativePath);put("role",s.role);put("text",s.text);put("textDigest",s.digest);put("origin",s.origin)
        put("openGeneration",s.openGeneration?.let(::JsonPrimitive) ?: JsonNull);put("version",s.revision?.let(::JsonPrimitive) ?: JsonNull)
    } })
    fun digest(sources: List<GraphqlCapturedSource>): String = LspGraphqlLaunchPolicy.sha(sources(sources).toString().toByteArray(Charsets.UTF_8))
    internal fun validate(r: GraphqlOwnerRequest): JsonArray {
        require(r.ownerGeneration in 1..GraphqlWire.SAFE_INTEGER && r.workspaceLifetime.length in 1..1024 && r.rootIdentity.length in 1..4096)
        require(listOf(r.consentIdentity,r.snapshotGeneration,r.configDigest,r.schemaDigest,r.fragmentsDigest,r.serializedDescriptorDigest).all(GraphqlWire::digest))
        require(r.rootPath == File(r.rootPath).canonicalPath && r.workspaceRoot == File(r.workspaceRoot).canonicalPath && File(r.rootPath).toPath().startsWith(File(r.workspaceRoot).toPath()))
        require(r.canonicalRootUri == java.net.URI("file","",r.rootPath.trimEnd('/')+"/",null).toASCIIString())
        val sources=r.immutableCapturedSources;require(sources.size in 2..41 && sources.map{it.uri}.distinct().size==sources.size && sources.map{it.relativePath}.distinct().size==sources.size)
        require(sources.count{it.role=="config"}==1 && sources.count{it.role=="schema"} in 1..8 && sources.count{it.role=="fragment"} in 0..32)
        var total=0L
        sources.forEach { s ->
            require(s.role in setOf("config","schema","fragment") && s.origin in setOf("disk","editor"))
            require(s.relativePath.length in 1..256 && !s.relativePath.startsWith('/') && s.relativePath.split('/').size<=32 && s.relativePath.split('/').none{it.isEmpty()||it=="."||it==".."} && s.relativePath.none{it.code<32||it.code==127||it=='\\'})
            require(s.uri==java.net.URI("file","",r.rootPath.trimEnd('/')+"/"+s.relativePath,null).toASCIIString())
            val bytes=s.text.toByteArray(Charsets.UTF_8);require(bytes.size <= if(s.role=="config")65_536 else 1_048_576);total+=bytes.size;require(total<=8_388_608)
            require(GraphqlWire.digest(s.digest)&&LspGraphqlLaunchPolicy.sha(bytes)==s.digest)
            if(s.role=="config")require(s.relativePath==".graphqlrc.json" && s.digest==r.configDigest)
            if(s.origin=="editor") require(s.openGeneration in 1..GraphqlWire.SAFE_INTEGER && s.revision in 0..GraphqlWire.SAFE_INTEGER) else require(s.openGeneration==null&&s.revision==null)
        }
        fun roleDigest(role:String)=LspGraphqlLaunchPolicy.sha(sources.filter{it.role==role}.joinToString("\n"){it.relativePath+":"+it.digest}.toByteArray(Charsets.UTF_8))
        require(roleDigest("schema")==r.schemaDigest&&roleDigest("fragment")==r.fragmentsDigest)
        require(digest(sources)==r.serializedDescriptorDigest)
        return sources(sources)
    }
}

/** Strongly retained one-shot blocking attempt. Cancellation is not falsely called settlement. */
internal class GraphqlIoAttempt<T>(name: String, action: () -> T) {
    @Volatile var complete=false; private set
    @Volatile var failure: Throwable?=null; private set
    private var value:T?=null
    init { Thread({try{value=action()}catch(t:Throwable){failure=t}finally{complete=true}},"droide-graphql-$name").apply{isDaemon=true;start()} }
    val succeeded:Boolean get()=complete&&failure==null
    suspend fun await(deadline:GraphqlDeadline):Boolean { while(!complete&&!deadline.expired)delay(minOf(10,deadline.remainingMillis().coerceAtLeast(1)));return complete }
    @Suppress("UNCHECKED_CAST") fun result():T { check(complete);failure?.let{throw it};return value as T }
}

/** Finite owned queue; a blocked native write never owns the fence or teardown monitor. */
internal class GraphqlAsyncWriter(private val output:OutputStream, private val failed:(Throwable)->Unit) {
    private val lock=java.lang.Object();private val queue=ArrayDeque<ByteArray>();private var bytes=0;private var accepting=true
    @Volatile var settled=false;private set
    @Volatile var failure:Throwable?=null;private set
    init { Thread({try{while(true){val next=synchronized(lock){while(queue.isEmpty()&&accepting)lock.wait();if(queue.isEmpty())null else queue.removeFirst() } ?: break
                output.write(next);output.flush();synchronized(lock){bytes-=next.size}}
        }catch(t:Throwable){failure=t;failed(t)}finally{settled=true}},"droide-graphql-writer").apply{isDaemon=true;start()} }
    fun offer(frame:ByteArray) = synchronized(lock) { check(accepting&&failure==null);require(queue.size<32&&bytes+frame.size<=16_777_216);queue.addLast(frame.copyOf());bytes+=frame.size;lock.notifyAll() }
    val drained:Boolean get()=synchronized(lock){queue.isEmpty()&&bytes==0}
    fun fence()=synchronized(lock){accepting=false;queue.clear();lock.notifyAll()}
}

/** Immutable request authority registered by client before writing, never granted by child echo. */
internal class GraphqlAuthorityLedger(private val owner:GraphqlPreparedOwner) {
    data class Pending(val authority:JsonObject,val total:GraphqlDeadline,val work:GraphqlDeadline,val retirement:Boolean=false)
    private val lock=Any();private val pending=linkedMapOf<JsonPrimitive,Pending>();private val documents=linkedMapOf<String,JsonObject>()
    private val keys=setOf("rootIdentity","configDigest","schemaDigest","fragmentsDigest","uri","openGeneration","version","textDigest","requestGeneration")
    fun validate(a:JsonObject){val r=owner.request;require(a.keys==keys&&a["rootIdentity"]==JsonPrimitive(r.rootIdentity)&&a["configDigest"]==JsonPrimitive(r.configDigest)&&a["schemaDigest"]==JsonPrimitive(r.schemaDigest)&&a["fragmentsDigest"]==JsonPrimitive(r.fragmentsDigest))
        require(a["requestGeneration"]?.jsonPrimitive?.longOrNull in 1..GraphqlWire.SAFE_INTEGER)
        if(a["uri"]===JsonNull)require(listOf("openGeneration","version","textDigest").all{a[it]===JsonNull})
        else {val uri=a["uri"]?.jsonPrimitive?.content?:error("Missing source URI");require(documents[uri]?.let{d->listOf("openGeneration","version","textDigest").all{d[it]==a[it]}}==true)}
    }
    fun register(id:JsonPrimitive,authority:JsonObject,deadline:GraphqlDeadline)=synchronized(lock){check(owner.isCurrent);val work=deadline.reserve(if(authority["uri"]===JsonNull)10_000 else 5_000);work.check();GraphqlWire.id(id);validate(authority);require(pending.size<16&&!pending.containsKey(id));pending[id]=Pending(authority,deadline,work)}
    fun document(uri:String,generation:Long,version:Long,digest:String)=synchronized(lock){check(owner.isCurrent);require(uri.startsWith(owner.request.canonicalRootUri)&&generation in 1..GraphqlWire.SAFE_INTEGER&&version in 0..GraphqlWire.SAFE_INTEGER&&GraphqlWire.digest(digest));require(documents.containsKey(uri)||documents.size<32);documents[uri]=buildJsonObject{put("openGeneration",generation);put("version",version);put("textDigest",digest)}}
    fun outgoing(m:JsonObject):JsonObject=synchronized(lock){GraphqlWire.rpc(m);check(owner.isCurrent);val method=m["method"]?.jsonPrimitive?.content?:error("Client responses forbidden")
        if(m.containsKey("id")){val p=pending[GraphqlWire.id(m["id"])]?:error("Unregistered GraphQL request");p.work.check();val params=m["params"] as? JsonObject ?: buildJsonObject{};require(params["_graphqlAuthority"]==null||params["_graphqlAuthority"]==p.authority);JsonObject(m+ ("params" to JsonObject(params+("_graphqlAuthority" to p.authority))))}
        else {if(method in setOf("textDocument/didOpen","textDocument/didChange","textDocument/didClose")){val params=m["params"]?.jsonObject?:error("Missing params");val d=params["textDocument"]?.jsonObject?:error("Missing document");val uri=d["uri"]?.jsonPrimitive?.content?:error("Missing URI");val authority=documents[uri]?:error("Unregistered document")
                if(method=="textDocument/didClose")documents.remove(uri)
                val a=buildJsonObject{put("rootIdentity",owner.request.rootIdentity);put("configDigest",owner.request.configDigest);put("schemaDigest",owner.request.schemaDigest);put("fragmentsDigest",owner.request.fragmentsDigest);put("uri",uri);authority.forEach{(k,v)->put(k,v)};put("requestGeneration",0)}
                val supplied=params["_graphqlAuthority"] as? JsonObject
                if(supplied!=null){require(supplied.keys==a.keys);require(a.keys.filter{it!="requestGeneration"}.all{supplied[it]==a[it]});require(supplied["requestGeneration"]?.jsonPrimitive?.longOrNull in 1..GraphqlWire.SAFE_INTEGER)}
                JsonObject(m+("params" to JsonObject(params+("_graphqlAuthority" to (supplied?:a)))))
            }else m}
    }
    fun retirement(id:JsonPrimitive,a:JsonObject,deadline:GraphqlDeadline)=synchronized(lock){deadline.check();require(pending.values.none{it.retirement});pending[id]=Pending(a,deadline,deadline,true)}
    fun response(m:JsonObject,a:JsonObject)=synchronized(lock){require(m["method"]==null);val id=GraphqlWire.id(m["id"]);val p=pending[id]?:error("Unowned GraphQL response");check(owner.isCurrent||p.retirement);p.work.check();require(p.authority==a);validate(a);pending.remove(id);Unit}
    fun expired():Boolean=synchronized(lock){pending.values.any{it.work.expired}}
    fun earliestTotal():GraphqlDeadline?=synchronized(lock){pending.values.filter{!it.retirement}.minByOrNull{it.total.endNanos}?.total}
    fun fence()=synchronized(lock){pending.clear();documents.clear()}
}

/** Blocking consumer is fed only authenticated envelopes by one retained native reader. */
internal class GraphqlEnvelopeInput(private val source:InputStream,private val owner:GraphqlPreparedOwner,
    private val ledger:GraphqlAuthorityLedger,private val failureSink:(GraphqlControlEvent)->Unit,private val onResponse:(JsonObject)->Boolean={true}):InputStream() {
    private val lock=java.lang.Object();private val queue=ArrayDeque<ByteArray>();private var queued=0;private var current:ByteArray?=null;private var offset=0;private var ended=false
    private var sequence=0L;private val ready=CompletableDeferred<Unit>();private var bootstrap=false;private var nativeReady=false
    private val controls=mutableListOf<GraphqlControlEvent>();private val listeners=mutableListOf<(GraphqlControlEvent)->Unit>()
    @Volatile var settled=false;private set
    @Volatile var failure:GraphqlControlEvent?=null;private set
    fun begin(){Thread({try{while(true){val bytes=GraphqlWire.readFrame(source,8_388_608)?:break;accept(GraphqlWire.strict(bytes))};if(owner.isCurrent)fail(GraphqlControlEvent("unavailable","unexpected_eof"))}catch(_:Throwable){fail(GraphqlControlEvent("policy_failure","envelope_protocol"))}finally{settled=true;synchronized(lock){ended=true;lock.notifyAll()};if(!ready.isCompleted)ready.completeExceptionally(IOException("GraphQL bootstrap unavailable"))}},"droide-graphql-envelope").apply{isDaemon=true;start()}}
    internal fun accept(v:JsonObject){val kind=v["kind"]?.jsonPrimitive?.content;val fields=setOf("schema","kind","ownerToken","ownerGeneration","snapshotGeneration","sequence")+if(kind=="lsp")setOf("message","authority")else setOf("event")
        require(v.keys==fields&&v["schema"]==JsonPrimitive(1)&&v["ownerToken"]==JsonPrimitive(owner.token)&&v["ownerGeneration"]==JsonPrimitive(owner.request.ownerGeneration)&&v["snapshotGeneration"]==JsonPrimitive(owner.request.snapshotGeneration))
        require(sequence<GraphqlWire.SAFE_INTEGER&&v["sequence"]==JsonPrimitive(sequence+1));sequence++
        when(kind){"lsp"->{check(failure==null);val m=v["message"]?.jsonObject?:error("Missing RPC");val a=v["authority"]?.jsonObject?:error("Missing authority");GraphqlWire.rpc(m);require(m.toString().toByteArray().size<=2_097_152);ledger.response(m,a);if(!onResponse(m))return;val frame=GraphqlWire.frame(m);synchronized(lock){check(!ended&&queue.size<64&&queued+frame.size<=16_777_216);queue+=frame;queued+=frame.size;lock.notifyAll()}}
            "control"->{val e=v["event"]?.jsonObject?:error("Missing control");require(e.keys.all{it in setOf("kind","reason")}&&e.toString().toByteArray().size<=8192);val event=GraphqlControlEvent(e["kind"]?.jsonPrimitive?.content?:error("Missing event"),e["reason"]?.jsonPrimitive?.content);require(event.kind in setOf("bootstrap_accepted","native_ready","policy_failure","native_failure","resource_exhausted","unavailable"));require(event.reason==null||event.reason.length<=512)
                if(event.kind=="bootstrap_accepted"){require(!bootstrap);bootstrap=true;ready.complete(Unit)}
                if(event.kind=="native_ready"){require(bootstrap&&!nativeReady);nativeReady=true}
                if(event.fatal)fail(event)else publish(event)}
            else->error("Unknown envelope")}
    }
    fun fail(e:GraphqlControlEvent){val first=synchronized(lock){if(failure!=null)false else{failure=e;queue.clear();queued=0;lock.notifyAll();true}};if(first){owner.fence();if(!ready.isCompleted)ready.completeExceptionally(IOException("GraphQL unavailable: ${e.reason}"));failureSink(e);publish(e)}}
    private fun publish(e:GraphqlControlEvent){val callbacks=synchronized(lock){require(controls.size<64);controls+=e;listeners.toList()};callbacks.forEach{runCatching{it(e)}}}
    fun onControl(listener:(GraphqlControlEvent)->Unit){val old=synchronized(lock){require(listeners.size<16);listeners+=listener;controls.toList()};old.forEach(listener)}
    suspend fun awaitReady(deadline:GraphqlDeadline){deadline.check();withTimeout(deadline.remainingMillis().coerceAtLeast(1)){ready.await()};deadline.check()}
    fun fence(){synchronized(lock){ended=true;queue.clear();current=null;queued=0;lock.notifyAll()}}
    override fun read():Int{val a=ByteArray(1);return if(read(a,0,1)<0)-1 else a[0].toInt()and 255}
    override fun read(b:ByteArray,off:Int,len:Int):Int=synchronized(lock){require(off>=0&&len>=0&&off<=b.size-len);if(len==0)return@synchronized 0;while(current==null){if(queue.isNotEmpty()){current=queue.removeFirst();queued-=current!!.size;offset=0;break};if(ended||failure!=null)return@synchronized -1;lock.wait()};val c=requireNotNull(current);val n=minOf(len,c.size-offset);c.copyInto(b,off,offset,offset+n);offset+=n;if(offset==c.size)current=null;n}
    override fun close(){fence()}
}

internal class LocalGraphqlPreparedOwner private constructor(override val identity:GraphqlManagedLaunchIdentity,override val request:GraphqlOwnerRequest,
    val localRoot:File,val guestRoot:String,override val token:String,val operationDeadline:GraphqlDeadline):GraphqlPreparedOwner {
    private val active=AtomicBoolean(true);private val retirement=Mutex();private val retained=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    internal val privateDirectoryIdentity=GraphqlWire.directoryIdentity(localRoot)
    private val projectAncestors=GraphqlWire.ancestors(File(request.rootPath))
    private lateinit var ownerBytes:ByteArray
    private val launchSpec=AtomicReference<TerminalLaunchSpec?>()
    private val process=AtomicReference<LocalGraphqlHostedProcess?>()
    private val factoryClaimed=AtomicBoolean(false)
    @Volatile private var factorySettled=true
    @Volatile private var factoryFailure:Throwable?=null
    @Volatile private var selectionCurrent:()->Boolean={true}
    private val selectionBound=AtomicBoolean(false)
    @Volatile internal var hostBirth:GraphqlWire.ProcIdentity?=null
    @Volatile internal var nodeBirth:GraphqlWire.ProcIdentity?=null
    @Volatile private var completeReceipt:GraphqlTeardownReceipt?=null
    override val isCurrent:Boolean get()=active.get()&&selectionCurrent()&&process.get()?.knownDead!=true&&process.get()?.fatalControl==null
    override fun fence(){active.set(false)}
    internal fun bindSelectionCurrentness(current:()->Boolean){check(selectionBound.compareAndSet(false,true));selectionCurrent=current;check(isCurrent)}
    internal fun storePreparedLaunchSpec(spec:TerminalLaunchSpec){check(isCurrent&&launchSpec.compareAndSet(null,spec))}
    internal fun requirePreparedLaunchSpec():TerminalLaunchSpec {check(isCurrent);return requireNotNull(launchSpec.get())}
    internal fun verifyPrivate(){check(GraphqlWire.directoryIdentity(localRoot)==privateDirectoryIdentity);check(GraphqlWire.readBytes(File(localRoot,"owner.json"),12_582_912).contentEquals(ownerBytes))}
    internal fun verifyProject(){check(GraphqlWire.ancestors(File(request.rootPath))==projectAncestors);require(File(request.workspaceRoot).canonicalPath==request.workspaceRoot)}
    internal fun shellCommand(command:List<String>):String=GraphqlWire.shellCommand(localRoot,token,command)
    internal fun createProcess(factory:()->Process):LocalGraphqlHostedProcess {
        check(isCurrent&&process.get()==null);val raw=factory()
        // Record returned handles before any cancellation/currentness check; even a late native result remains owned.
        val hosted=LocalGraphqlHostedProcess(raw,this,retained)
        check(process.compareAndSet(null,hosted));ExitHostedProcessRegistry.retain(hosted)
        if(!isCurrent){fence();hosted.retainLateFactoryKill()}
        return hosted
    }
    internal fun beginFactory(block:suspend ()->GraphqlHostedProcess){check(factoryClaimed.compareAndSet(false,true));factorySettled=false
        retained.launch {try{val result=block();check(result===process.get());if(!isCurrent)process.get()?.retainLateFactoryKill()}catch(t:Throwable){factoryFailure=t;fence()}finally{factorySettled=true}}
    }
    internal suspend fun awaitFactory(deadline:GraphqlDeadline):LocalGraphqlHostedProcess {
        while(!factorySettled&&!deadline.expired)delay(minOf(10,deadline.remainingMillis().coerceAtLeast(1)))
        deadline.check();factoryFailure?.let{throw it};check(factorySettled&&isCurrent);return requireNotNull(process.get())
    }
    internal fun beginObservation(){requireNotNull(process.get()).begin()}
    internal suspend fun awaitServer(deadline:GraphqlDeadline){while(true){deadline.check();check(isCurrent&&process.get()?.isAlive==true);verifyPrivate();val hf=File(localRoot,"host.json");val nf=File(localRoot,"server.json")
        if(hf.exists()&&nf.exists()){val h=GraphqlWire.strict(GraphqlWire.readBytes(hf,65536));val n=GraphqlWire.strict(GraphqlWire.readBytes(nf,65536));require(h["token"]==JsonPrimitive(token)&&n["token"]==JsonPrimitive(token));require(n["ownerGeneration"]==JsonPrimitive(request.ownerGeneration)&&n["snapshotGeneration"]==JsonPrimitive(request.snapshotGeneration))
            val hp=GraphqlWire.proc(GraphqlWire.positiveInt(h["pid"]));val np=GraphqlWire.proc(GraphqlWire.positiveInt(n["pid"]));require(hp.start==h["start"]?.jsonPrimitive?.content&&hp.group==hp.pid&&hp.state!="Z");require(np.start==n["start"]?.jsonPrimitive?.content&&np.group==hp.group&&np.state!="Z");hostBirth=hp;nodeBirth=np;return};delay(10)}}
    override fun close(){val deadline=process.get()?.failureBudget()?:operationDeadline;fence();retained.launch{retire(deadline)}}
    override suspend fun retire(deadline:GraphqlDeadline):GraphqlTeardownReceipt=withContext(NonCancellable+Dispatchers.IO){
        fence() // independent of every lifecycle, native factory, pipe and retirement monitor.
        while(!retirement.tryLock()){if(deadline.expired)return@withContext GraphqlTeardownReceipt.unknown("GraphQL retirement already in progress; exact custody retained");delay(10)}
        try{completeReceipt?.let{return@withContext it};val p=process.get()
            val stopped=if(p==null)GraphqlTeardownReceipt(false,true,true,!factoryClaimed.get(),factorySettled,true,true,true,false)
                else runCatching{p.stopOwned(deadline,factorySettled)}.getOrElse{GraphqlTeardownReceipt.unknown("GraphQL group or I/O cleanup UNKNOWN")}
            // A native factory still in flight owns the private directory even if no handle has returned.
            val canClean=stopped.settled&&factorySettled&&!deadline.expired
            val cleaned=canClean&&runCatching{verifyPrivate();GraphqlWire.removePrivate(localRoot,privateDirectoryIdentity);true}.getOrDefault(false)
            val result=stopped.copy(complete=canClean&&cleaned,factorySettled=factorySettled,privateCleanupVerified=cleaned,
                reason=if(cleaned)null else stopped.reason?:"GraphQL exact owner retained; repair required (UNKNOWN custody)")
            if(result.complete){completeReceipt=result;GraphqlRootOwners.release(this@LocalGraphqlPreparedOwner)}
            result
        }finally{retirement.unlock()}
    }
    companion object {
        fun allocate(appRoot:File,identity:GraphqlManagedLaunchIdentity,request:GraphqlOwnerRequest,deadline:GraphqlDeadline):LocalGraphqlPreparedOwner {
            deadline.check();require(LspGraphqlLaunchPolicy.matchesIdentity(identity));val frozen=request.copy(immutableCapturedSources=request.immutableCapturedSources.toList());val sources=GraphqlDescriptorCodec.validate(frozen)
            deadline.check();val parent=File(appRoot,"runtime-tmp");require(parent.canonicalFile==parent.absoluteFile&&Files.isDirectory(parent.toPath(),NOFOLLOW_LINKS))
            val token=UUID.randomUUID().toString().replace("-","");val local=File(parent,"droide-graphql-$token");Files.createDirectory(local.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val owner=LocalGraphqlPreparedOwner(identity.copy(helpersSha256=identity.helpersSha256.toSortedMap()),frozen,local,"/run/droide-graphql/${local.name}",token,deadline)
            try{for(name in listOf("home","tmp","config","cache","data","state","run","workspace")){deadline.check();Files.createDirectory(File(local,name).toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))}
                val descriptor=buildJsonObject{put("schema",1);put("profile",LspGraphqlLaunchPolicy.PROFILE);put("mode","editor");put("ownerToken",token);put("ownerGeneration",frozen.ownerGeneration);put("snapshotGeneration",frozen.snapshotGeneration);put("workspaceLifetime",frozen.workspaceLifetime);put("rootIdentity",frozen.rootIdentity);put("canonicalRootUri",frozen.canonicalRootUri);put("consentIdentity",frozen.consentIdentity);put("configDigest",frozen.configDigest);put("schemaDigest",frozen.schemaDigest);put("fragmentsDigest",frozen.fragmentsDigest);put("serializedDescriptorDigest",frozen.serializedDescriptorDigest);put("sources",sources);put("serverRoot",LspGraphqlLaunchPolicy.ROOT);put("runtimeRoot",LspGraphqlLaunchPolicy.NODE_ROOT);put("privateRoot",owner.guestRoot);put("privateDirectoryIdentity",owner.privateDirectoryIdentity);put("nodeExecutable",identity.nodeExecutable);put("managedIdentity",GraphqlWire.managedIdentity(identity));put("proofFile",LspGraphqlLaunchPolicy.ROOT+"/GRAPHQL_AUTHORITY.json");put("proofDigest",LspGraphqlLaunchPolicy.sha(GraphqlWire.readBytes(File(appRoot,"packages/lsp.graphql-library/5.7.0/arm64-v8a/GRAPHQL_AUTHORITY.json"),1_048_576)))}
                owner.ownerBytes=descriptor.toString().toByteArray(Charsets.UTF_8);require(owner.ownerBytes.size<=12_582_912);GraphqlWire.write(File(local,"owner.json"),owner.ownerBytes);GraphqlWire.write(File(local,"owner.sha256"),(LspGraphqlLaunchPolicy.sha(owner.ownerBytes)+"\n").toByteArray());GraphqlRootOwners.claim(owner);deadline.check();return owner
            }catch(t:Throwable){owner.fence();if(runCatching{GraphqlWire.removePrivate(local,owner.privateDirectoryIdentity);true}.getOrDefault(false))GraphqlRootOwners.release(owner);throw t}
        }
    }
}

internal class GraphqlOutgoingFrames(private val receive:(JsonObject)->Unit) {
    private var bytes=ByteArray(0)
    fun feed(chunk:ByteArray,off:Int,len:Int){require(off>=0&&len>=0&&off<=chunk.size-len&&bytes.size+len<=16_777_216);bytes+=chunk.copyOfRange(off,off+len)
        while(true){var end=-1;for(i in 0 until (bytes.size-3).coerceAtLeast(0))if(bytes[i]==13.toByte()&&bytes[i+1]==10.toByte()&&bytes[i+2]==13.toByte()&&bytes[i+3]==10.toByte()){end=i;break}
            if(end<0){require(bytes.size<=8192);return};require(end<=8192);val header=bytes.copyOfRange(0,end).toString(Charsets.US_ASCII);val lines=header.split("\r\n");val matches=lines.filter{it.substringBefore(':').lowercase()=="content-length"};require(matches.size==1);val n=matches.single().substringAfter(':').trim();require(n.matches(Regex("[1-9][0-9]{0,7}")));val size=n.toInt();require(size<=8_388_608);if(bytes.size<end+4+size)return
            val frame=bytes.copyOfRange(0,end+4+size);bytes=bytes.copyOfRange(end+4+size,bytes.size);receive(GraphqlWire.strict(requireNotNull(GraphqlWire.readFrame(ByteArrayInputStream(frame),8_388_608))))}
    }
}

internal class LocalGraphqlHostedProcess(private val process:Process,override val owner:LocalGraphqlPreparedOwner,private val retained:CoroutineScope):GraphqlHostedProcess {
    private val ledger=GraphqlAuthorityLedger(owner)
    private val attempts=java.util.concurrent.CopyOnWriteArrayList<GraphqlIoAttempt<*>>()
    private val stopStarted=AtomicBoolean(false)
    private val outboundLock=Any()
    private val writer=GraphqlAsyncWriter(process.outputStream){owner.fence()}
    @Volatile private var initializeId:JsonPrimitive?=null
    @Volatile private var initialized=false
    @Volatile private var nativeReady=false
    private val stopId=JsonPrimitive("droide-graphql-retire-${owner.token}")
    @Volatile private var shutdownAccepted=false
    private val envelopes=GraphqlEnvelopeInput(process.inputStream,owner,ledger,{owner.fence()},{m->
        if(m["id"]==initializeId&&initializeId!=null&&m["result"] is JsonObject&&m["error"]==null)initialized=true
        if(m["id"]==stopId){shutdownAccepted=m["result"]===JsonNull&&m["error"]==null;false}else true
    })
    private val outgoing=GraphqlOutgoingFrames{m->if(m["method"]==JsonPrimitive("initialize")){require(initializeId==null);initializeId=GraphqlWire.id(m["id"])};writer.offer(GraphqlWire.frame(ledger.outgoing(m)))}
    private val stderrLock=java.lang.Object();private val stderrQueue=ArrayDeque<ByteArray>();private var stderrBytes=0;private var stderrCurrent:ByteArray?=null;private var stderrOffset=0
    @Volatile private var stderrEnded=false;@Volatile private var stderrSettled=false;@Volatile private var began=false
    @Volatile internal var knownDead=false
    override val fatalControl:GraphqlControlEvent? get()=envelopes.failure
    override val isAlive:Boolean get()=process.isAlive
    override val exitCode:Int? get()=runCatching{process.exitValue()}.getOrNull()
    override val stdin:OutputStream=object:OutputStream(){override fun write(b:Int)=write(byteArrayOf(b.toByte()),0,1);override fun write(b:ByteArray,off:Int,len:Int){check(owner.isCurrent);synchronized(outboundLock){check(owner.isCurrent);outgoing.feed(b,off,len)}};override fun flush(){check(owner.isCurrent)};override fun close(){/* Actual pipe closure belongs to exact-owner retirement. */}}
    override val stdout:InputStream get()=envelopes
    override val stderr:InputStream=object:InputStream(){override fun read():Int{val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt()and 255};override fun read(b:ByteArray,off:Int,len:Int):Int=synchronized(stderrLock){require(off>=0&&len>=0&&off<=b.size-len);if(len==0)return@synchronized 0;while(stderrCurrent==null){if(stderrQueue.isNotEmpty()){stderrCurrent=stderrQueue.removeFirst();stderrOffset=0;break};if(stderrEnded)return@synchronized -1;stderrLock.wait()};val c=requireNotNull(stderrCurrent);val n=minOf(len,c.size-stderrOffset);c.copyInto(b,off,stderrOffset,stderrOffset+n);stderrOffset+=n;if(stderrOffset==c.size)stderrCurrent=null;n};override fun close(){synchronized(stderrLock){stderrEnded=true;stderrQueue.clear();stderrLock.notifyAll()}}}
    override fun registerRequest(id:JsonPrimitive,authority:JsonObject,deadline:GraphqlDeadline)=ledger.register(id,authority,deadline)
    override fun registerDocument(uri:String,openGeneration:Long,version:Long,textDigest:String)=ledger.document(uri,openGeneration,version,textDigest)
    override fun onControl(listener:(GraphqlControlEvent)->Unit)=envelopes.onControl(listener)
    override suspend fun awaitBootstrapAccepted(deadline:GraphqlDeadline)=envelopes.awaitReady(deadline)
    internal fun failureBudget():GraphqlDeadline=ledger.earliestTotal()?:if(nativeReady)GraphqlDeadline.retirement() else owner.operationDeadline
    internal fun begin(){check(!began);began=true;envelopes.onControl{if(it.kind=="native_ready")nativeReady=true};envelopes.begin()
        attempt("stderr-reader"){try{val b=ByteArray(8192);while(true){val n=process.errorStream.read(b);if(n<0)break;require(n>0);synchronized(stderrLock){stderrBytes+=n;require(stderrBytes<=1_048_576);if(!stderrEnded){stderrQueue+=b.copyOf(n);stderrLock.notifyAll()}}}}catch(t:Throwable){owner.fence();throw t}finally{stderrSettled=true;synchronized(stderrLock){stderrEnded=true;stderrLock.notifyAll()}}}
        retained.launch{while(!stopStarted.get()){try{if(!process.isAlive){knownDead=true;owner.fence()};if(ledger.expired())owner.fence();if(owner.hostBirth!=null)observe();if(!owner.isCurrent){owner.retire(failureBudget());break}}catch(_:Throwable){owner.fence();owner.retire(failureBudget());break};delay(GraphqlResourcePolicy.MONITOR_MS)}}
    }
    private fun observe(){val h=requireNotNull(owner.hostBirth);val n=requireNotNull(owner.nodeBirth);val group=GraphqlWire.group(h.group);require(group.size<=GraphqlResourcePolicy.GROUP_MEMBERS_MAX&&group.sumOf{it.rss}<=GraphqlResourcePolicy.OBSERVED_RSS_BYTES_MAX);require(group.any{it.pid==h.pid&&it.start==h.start}&&group.any{it.pid==n.pid&&it.start==n.start});require(group.none{it.parent==n.pid&&it.pid!=n.pid}){"Unexpected GraphQL child"}}
    private fun<T> attempt(name:String,action:()->T):GraphqlIoAttempt<T>{require(attempts.size<24);return GraphqlIoAttempt(name,action).also{attempts+=it}}
    internal fun retainLateFactoryKill(){owner.fence();if(stopStarted.compareAndSet(false,true)){attempt("late-factory-kill"){process.destroyForcibly()};attempt("late-factory-reap"){process.waitFor()}}}
    override fun close()=owner.close()
    override suspend fun stopAndAwaitOwnedGroup(deadline:GraphqlDeadline)=owner.retire(deadline)
    override suspend fun awaitExit():Int {while(process.isAlive)delay(20);knownDead=true;check(owner.retire(failureBudget()).complete){"GraphQL cleanup UNKNOWN"};return process.exitValue()}
    internal suspend fun stopOwned(deadline:GraphqlDeadline,factorySettled:Boolean):GraphqlTeardownReceipt {
        owner.fence()
        // A single exact, host-owned shutdown is optional and bounded by the same operation total.
        // It cannot acquire the client writer monitor or delay independent signals beyond this grace.
        if(!stopStarted.get()&&initialized&&fatalControl==null&&process.isAlive&&!deadline.expired){
            val grace=deadline.limited(GraphqlResourcePolicy.SHUTDOWN_GRACE_MS)
            runCatching{
                val a=buildJsonObject{put("rootIdentity",owner.request.rootIdentity);put("configDigest",owner.request.configDigest);put("schemaDigest",owner.request.schemaDigest);put("fragmentsDigest",owner.request.fragmentsDigest);put("uri",JsonNull);put("openGeneration",JsonNull);put("version",JsonNull);put("textDigest",JsonNull);put("requestGeneration",GraphqlWire.SAFE_INTEGER)}
                ledger.retirement(stopId,a,grace)
                writer.offer(GraphqlWire.frame(buildJsonObject{put("jsonrpc","2.0");put("id",stopId);put("method","shutdown");put("params",buildJsonObject{put("_graphqlAuthority",a)})}))
                while(!shutdownAccepted&&process.isAlive&&!grace.expired)delay(10)
                if(shutdownAccepted&&!grace.expired){writer.offer(GraphqlWire.frame(buildJsonObject{put("jsonrpc","2.0");put("method","exit")}));while(!writer.drained&&!grace.expired)delay(10)}
            }
        }
        writer.fence();ledger.fence();envelopes.fence();synchronized(stderrLock){stderrEnded=true;stderrQueue.clear();stderrLock.notifyAll()}
        // Closing blocked streams is independent of TERM/KILL/reaping, each retained exactly once.
        if(stopStarted.compareAndSet(false,true)){
            attempt("stdin-close"){process.outputStream.close()};attempt("reap"){process.waitFor()}
            val b=owner.hostBirth?:runCatching{val r=GraphqlWire.strict(GraphqlWire.readBytes(File(owner.localRoot,"host.json"),65536));require(r["token"]==JsonPrimitive(owner.token));GraphqlWire.recordedBirth(r)}.getOrNull()
            if(b!=null){owner.hostBirth=b;attempt("group-stop"){for(signal in listOf(15,9)){val group=GraphqlWire.group(b.group);if(group.isEmpty())break;require(group.singleOrNull{it.pid==b.pid}?.start==b.start);GraphqlWire.signalGroup(b,signal);if(signal==15&&!deadline.expired)Thread.sleep(minOf(200,deadline.remainingMillis()))}}}
            if(process.isAlive)attempt("direct-kill"){process.destroyForcibly()}
            attempt("stdout-close"){process.inputStream.close()};attempt("stderr-close"){process.errorStream.close()}
        }
        while(!deadline.reserve(250).expired&&(attempts.any{!it.complete}||!writer.settled||(began&&(!envelopes.settled||!stderrSettled))))delay(10)
        val reaped=!process.isAlive&&attempts.all{it.complete} // independently verify direct wait below without an unbounded call
        val direct=reaped&&runCatching{process.waitFor(0,TimeUnit.MILLISECONDS)}.getOrDefault(false)
        val b=owner.hostBirth;val empty=b!=null&&runCatching{GraphqlWire.group(b.group).isEmpty()}.getOrDefault(false)
        val custody=b!=null&&empty&&attempts.all{it.succeeded}
        knownDead=direct
        return GraphqlTeardownReceipt(false,direct,empty,custody,factorySettled,writer.settled&&writer.failure==null,!began||envelopes.settled&&stderrSettled,attempts.all{it.succeeded},false,
            if(direct&&empty&&custody&&factorySettled&&writer.settled&&(!began||envelopes.settled&&stderrSettled))null else "GraphQL factory/group/read/write/close settlement UNKNOWN; retained")
    }
}

internal object GraphqlRootOwners {
    private val lock=Any();private val roots=mutableMapOf<String,LocalGraphqlPreparedOwner>()
    fun claim(owner:LocalGraphqlPreparedOwner)=synchronized(lock){check(roots[owner.request.rootPath]==null);val siblings=requireNotNull(owner.localRoot.parentFile.listFiles());require(siblings.size<=16_384)
        for(dir in siblings){if(dir==owner.localRoot||!dir.name.startsWith("droide-graphql-"))continue;require(!Files.isSymbolicLink(dir.toPath()));val file=File(dir,"owner.json");if(file.exists()){val old=GraphqlWire.strict(GraphqlWire.readBytes(file,12_582_912));check(old["canonicalRootUri"]!=JsonPrimitive(owner.request.canonicalRootUri)){"Prior GraphQL owner requires verified cleanup"}}};roots[owner.request.rootPath]=owner}
    fun release(owner:LocalGraphqlPreparedOwner)=synchronized(lock){if(roots[owner.request.rootPath]===owner)roots.remove(owner.request.rootPath);Unit}
    /** Durable private directories block replacement/removal, including after an app process restart. */
    fun requireNoCustody(appRoot:String,family:String){if(family !in setOf("lsp.graphql-library","runtime.graphql-node"))return;val parent=File(appRoot,"runtime-tmp");if(!parent.exists())return;require(parent.canonicalFile==parent.absoluteFile&&!Files.isSymbolicLink(parent.toPath()));val dirs=requireNotNull(parent.listFiles());require(dirs.size<=16_384);check(dirs.none{it.name.startsWith("droide-graphql-")}){"GraphQL exact private custody requires repair before mutation"}}
}

internal object GraphqlWire {
    const val SAFE_INTEGER=9_007_199_254_740_991L
    fun digest(s:String)=s.matches(Regex("[0-9a-f]{64}"))
    fun positiveInt(v:JsonElement?):Int= requireNotNull(v?.jsonPrimitive?.intOrNull).also{require(it>1)}
    fun managedIdentity(i:GraphqlManagedLaunchIdentity):JsonObject=buildJsonObject {
        put("familyId",i.familyId);put("version",i.version);put("runtimeFamilyId",i.runtimeFamilyId);put("runtimeVersion",i.runtimeVersion);put("profileRevision",i.sourcePolicyRevision)
        put("serverReceiptSha256",i.receiptSha256);put("runtimeReceiptSha256",i.runtimeReceiptSha256);put("serverSealSha256",i.sealSha256);put("runtimeSealSha256",i.runtimeSealSha256)
        put("serverActivationId",i.activationIdentity);put("runtimeActivationId",i.runtimeActivationIdentity);put("selectedPackageIdentity",i.selectedPackageIdentity);put("discoveredExecutable",i.discoveredExecutable)
        put("nodeExecutable",i.nodeExecutable);put("adapterExecutable",LspGraphqlLaunchPolicy.ROOT+"/"+LspGraphqlLaunchPolicy.ADAPTER)
        put("runtimeClosureSha256",i.closureSha256);put("sourceBindingSha256",i.sourceBindingSha256);put("importReviewSha256",i.importReviewSha256);put("noticeAdmissionSha256",i.noticeAdmissionSha256);put("dataAdmissionSha256",i.dataAdmissionSha256)
        put("helperHashes",JsonObject(i.helpersSha256.mapValues{JsonPrimitive(it.value)}))
    }
    fun shellCommand(root:File,token:String,command:List<String>):String {
        require(token.matches(Regex("[0-9a-f]{32}")))
        val q=LocalExecutionSubstrate::shellQuote
        val body="set -eu; umask 077; line=\$(/system/bin/toybox cat /proc/\$\$/stat); fields=\${line##*) }; set -- \$fields; [ \"\$3\" = \"\$\$\" ]; start=\${20}; printf '{\"schema\":1,\"token\":\"$token\",\"pid\":%s,\"start\":\"%s\",\"group\":%s}' \"\$\$\" \"\$start\" \"\$3\" > ${q(File(root,"host.json").absolutePath)}; exec "+command.joinToString(" ",transform=q)
        return "exec /system/bin/toybox setsid /system/bin/sh -c "+q(body)
    }

    fun id(value:JsonElement?):JsonPrimitive {val p=value as? JsonPrimitive?:error("Invalid RPC id");require(p!==JsonNull&&(p.isString||(p.longOrNull?.let{it in -SAFE_INTEGER..SAFE_INTEGER}==true)));return p}
    fun rpc(m:JsonObject){require(m["jsonrpc"]==JsonPrimitive("2.0"));if(m.containsKey("id"))id(m["id"]);if(m.containsKey("method"))require(m["method"] is JsonPrimitive&&m["method"]!!.jsonPrimitive.isString&&!m.containsKey("result")&&!m.containsKey("error"))else require(m.containsKey("id")&&(m.containsKey("result") xor m.containsKey("error")))}
    fun frame(m:JsonObject):ByteArray {val b=m.toString().toByteArray();require(b.size<=8_388_608);return "Content-Length: ${b.size}\r\n\r\n".toByteArray()+b}
    fun readFrame(input:InputStream,max:Int):ByteArray? {val header=ByteArrayOutputStream();var tail=0;while(true){val b=input.read();if(b<0){require(header.size()==0);return null};require(b in 9..127);header.write(b);require(header.size()<=8192);tail=(tail shl 8)or b;if(tail==0x0d0a0d0a)break}
        var length:Int?=null;var contentType=false;for(line in header.toString("US-ASCII").removeSuffix("\r\n\r\n").split("\r\n")){val m=Regex("([A-Za-z-]+):[ \\t]*(.*)").matchEntire(line)?:error("Invalid header");when(m.groupValues[1].lowercase()){"content-length"->{require(length==null&&m.groupValues[2].matches(Regex("[1-9][0-9]{0,7}")));length=m.groupValues[2].toInt()};"content-type"->{require(!contentType&&m.groupValues[2].matches(Regex("application/(vscode-jsonrpc|json)(;\\s*charset=utf-8)?",RegexOption.IGNORE_CASE)));contentType=true};else->error("Unknown header")}}
        val n=requireNotNull(length);require(n in 1..max);val bytes=ByteArray(n);var i=0;while(i<n){val read=input.read(bytes,i,n-i);require(read>0);i+=read};return bytes}
    fun strict(bytes:ByteArray):JsonObject {require(bytes.size in 1..12_582_912);val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();var i=0;var nodes=0
        fun ws(){while(i<text.length&&text[i] in " \t\r\n")i++}
        fun unicode(s:String):String{var n=0;while(n<s.length){val c=s[n++];if(c.isHighSurrogate()){require(n<s.length&&s[n].isLowSurrogate());n++}else require(!c.isLowSurrogate())};return s}
        fun string():String{require(i<text.length&&text[i]=='"');val begin=i++;var escaped=false;while(i<text.length){val c=text[i++];if(c=='"'&&!escaped)return unicode(Json.parseToJsonElement(text.substring(begin,i)).jsonPrimitive.content);escaped=if(c=='\\')!escaped else false};error("Unclosed string")}
        fun value(depth:Int){require(depth<=64&&++nodes<=200000);ws();require(i<text.length);when(text[i]){'{'->{i++;ws();val keys=mutableSetOf<String>();if(i<text.length&&text[i]=='}')i++ else while(true){ws();require(keys.add(string()));ws();require(text[i++]==':');value(depth+1);ws();val c=text[i++];if(c=='}')break;require(c==',')}};'['->{i++;ws();if(i<text.length&&text[i]==']')i++ else while(true){value(depth+1);ws();val c=text[i++];if(c==']')break;require(c==',')}};'"'->string();else->{val begin=i;while(i<text.length&&text[i] !in ",]} \t\r\n")i++;require(i>begin);Json.parseToJsonElement(text.substring(begin,i))}}}
        value(0);ws();require(i==text.length);val parsed=Json.parseToJsonElement(text).jsonObject
        fun finite(e:JsonElement){when(e){is JsonObject->e.values.forEach(::finite);is JsonArray->e.forEach(::finite);is JsonPrimitive->if(!e.isString&&e!==JsonNull&&e.booleanOrNull==null){val number=e.doubleOrNull;require(number!=null&&number.isFinite()&&kotlin.math.abs(number)<=SAFE_INTEGER.toDouble())}}};finite(parsed);return parsed}
    fun ancestors(file:File):Map<String,String>{val out=linkedMapOf<String,String>();var current:File?=file;while(current!=null){require(out.size<64);out[current.absolutePath]=canonicalDirectory(current)+":"+Files.getAttribute(current.toPath(),"unix:mode",NOFOLLOW_LINKS);require(Files.isReadable(current.toPath())&&Files.isExecutable(current.toPath()));current=current.parentFile};return out}
    fun canonicalDirectory(file:File):String{require(file.canonicalPath==file.absolutePath);val a=Files.readAttributes(file.toPath(),"unix:dev,ino,mode",NOFOLLOW_LINKS);require((a["mode"] as Number).toInt() and 0xf000==0x4000);return "${a["dev"]}:${a["ino"]}"}
    fun directoryIdentity(file:File):String{val id=canonicalDirectory(file);require((Files.getAttribute(file.toPath(),"unix:mode",NOFOLLOW_LINKS) as Number).toInt() and 0x1ff==0x1c0);return id}
    fun readBytes(file:File,max:Int):ByteArray{val attrs="unix:dev,ino,size,lastModifiedTime,ctime,nlink,mode";val before=Files.readAttributes(file.toPath(),attrs,NOFOLLOW_LINKS);require((before["mode"] as Number).toInt() and 0xf000==0x8000&&(before["nlink"] as Number).toInt()==1);val size=(before["size"] as Number).toLong();require(size in 0..max.toLong());Files.newByteChannel(file.toPath(),setOf(StandardOpenOption.READ,NOFOLLOW_LINKS)).use{c->val out=ByteBuffer.allocate(size.toInt());while(out.hasRemaining())require(c.read(out)>0);require(c.size()==size&&before==Files.readAttributes(file.toPath(),attrs,NOFOLLOW_LINKS));return out.array()}}
    fun write(file:File,bytes:ByteArray){require(bytes.size<=12_582_912);Files.write(file.toPath(),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);Files.setPosixFilePermissions(file.toPath(),PosixFilePermissions.fromString("rw-------"))}
    data class ProcIdentity(val pid:Int,val parent:Int,val group:Int,val start:String,val state:String,val rss:Long)
    fun proc(pid:Int):ProcIdentity{require(pid>1);val file=File("/proc/$pid/stat");val bytes=file.inputStream().use{it.readBytesBounded(4096)};val text=bytes.toString(Charsets.US_ASCII);val fields=text.substring(text.lastIndexOf(')')+2).trim().split(Regex(" +"));require(fields.size>=22&&fields[19].matches(Regex("[0-9]+")));val status=File("/proc/$pid/status").inputStream().use{it.readBytesBounded(65536)}.toString(Charsets.US_ASCII);val rss=status.lineSequence().firstOrNull{it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)?:if(fields[0]=="Z")0L else error("Unknown RSS");return ProcIdentity(pid,fields[1].toInt(),fields[2].toInt(),fields[19],fields[0],rss)}
    private fun InputStream.readBytesBounded(max:Int):ByteArray{val out=ByteArrayOutputStream();val b=ByteArray(4096);while(true){val n=read(b);if(n<0)break;require(n>0&&out.size()+n<=max);out.write(b,0,n)};return out.toByteArray()}
    fun recordedBirth(record:JsonObject):ProcIdentity {val pid=requireNotNull(record["pid"]?.jsonPrimitive?.intOrNull);val group=requireNotNull(record["group"]?.jsonPrimitive?.intOrNull);val start=requireNotNull(record["start"]?.jsonPrimitive?.content);require(pid>1&&pid==group&&start.matches(Regex("[0-9]+")));return ProcIdentity(pid,0,group,start,"?",0)}
    fun group(group:Int):List<ProcIdentity>{require(group>1);val dirs=requireNotNull(File("/proc").listFiles());require(dirs.size<=GraphqlResourcePolicy.SCAN_BOUND);return dirs.mapNotNull{d->val pid=d.name.toIntOrNull()?:return@mapNotNull null;if(pid<=1)return@mapNotNull null;try{val text=File(d,"stat").inputStream().use{it.readBytesBounded(4096)}.toString(Charsets.US_ASCII);val fields=text.substring(text.lastIndexOf(')')+2).trim().split(Regex(" +"));require(fields.size>=22);if(fields[2].toInt()!=group)null else proc(pid)}catch(e:java.nio.file.NoSuchFileException){null}catch(e:FileNotFoundException){if(d.exists())throw e else null}}}
    fun signalGroup(birth:ProcIdentity,signal:Int){val now=proc(birth.pid);require(now.start==birth.start&&now.group==birth.group&&now.pid==now.group);val result=ProcessBuilder("/system/bin/toybox","kill","-$signal","--","-${birth.group}").start();check(result.waitFor(1,TimeUnit.SECONDS)&&result.exitValue()==0)}
    fun removePrivate(root:File,identity:String){check(directoryIdentity(root)==identity);var count=0;var bytes=0L;fun remove(f:File){require(++count<=256);val a=Files.readAttributes(f.toPath(),"unix:mode,size",NOFOLLOW_LINKS);val mode=(a["mode"] as Number).toInt() and 0xf000;require(mode in setOf(0x4000,0x8000));bytes+=(a["size"] as Number).toLong();require(bytes<=16_777_216);if(mode==0x4000)requireNotNull(f.listFiles()).forEach(::remove);check(f.delete())};remove(root)}
}
