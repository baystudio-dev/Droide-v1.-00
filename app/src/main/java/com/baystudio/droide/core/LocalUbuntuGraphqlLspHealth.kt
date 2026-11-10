package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Genuine original behavior and independently measured cleanup are separate requirements.
 * Printed markers (including authored fixture peers) can never populate the host receipt map. */
internal object LocalUbuntuGraphqlLspHealth {
    const val TIMEOUT_MS=GraphqlResourcePolicy.HEALTH_TOTAL_MS
    const val SUCCESS="DROIDE_GRAPHQL_GENUINE_HEALTH_OK"
    const val NODE_SUCCESS="DROIDE_GRAPHQL_NODE_IDENTITY_OK"
    const val CLEANUP="DROIDE_GRAPHQL_OWNED_CLEANUP_OK"
    private val receipts=java.util.Collections.synchronizedMap(java.util.IdentityHashMap<ExecResult,GraphqlTeardownReceipt>())
    internal fun recorded(result:ExecResult,receipt:GraphqlTeardownReceipt):ExecResult {require(receipt.complete==receipt.settled&&receipt.privateCleanupVerified);check(receipts.size<128);receipts[result]=receipt;return result}
    fun teardownReceipt(result:ExecResult):GraphqlTeardownReceipt?=receipts[result]
    fun cleanupVerified(result:ExecResult)=!result.outputIncomplete&&teardownReceipt(result)?.complete==true&&result.output.lineSequence().filter{it.isNotBlank()}.lastOrNull()==CLEANUP
    fun nodeCleanupVerified(result:ExecResult)=cleanupVerified(result)
    fun succeeded(result:ExecResult)=result.exitCode==0&&!result.timedOut&&cleanupVerified(result)&&result.output.trim()=="$SUCCESS\n$CLEANUP"
    fun nodeSucceeded(result:ExecResult)=result.exitCode==0&&!result.timedOut&&cleanupVerified(result)&&result.output.trim()=="$NODE_SUCCESS\n$CLEANUP"
    fun acceptsServer(result:ExecResult)=succeeded(result)
    fun acceptsRuntime(result:ExecResult)=nodeSucceeded(result)
    internal fun validObservation(bytes:ByteArray,owner:GraphqlHealthPrivateState):Boolean=runCatching {
        val input=java.io.ByteArrayInputStream(bytes);val value=GraphqlWire.strict(requireNotNull(GraphqlWire.readFrame(input,1_048_576)));require(input.available()==0)
        val kind=if(owner.serverHealth)"graphql_semantic_observation"else"graphql_node_observation"
        val keys=setOf("schema","kind","profile","ownerToken","ownerGeneration","snapshotGeneration","cleanup")+if(owner.serverHealth)setOf("observed","runtime")else setOf("birth","observation")
        require(value.keys==keys&&value["schema"]==JsonPrimitive(1)&&value["kind"]==JsonPrimitive(kind)&&value["profile"]==JsonPrimitive(LspGraphqlLaunchPolicy.PROFILE)&&value["ownerToken"]==JsonPrimitive(owner.token)&&value["ownerGeneration"]==JsonPrimitive(1))
        val descriptor=GraphqlWire.strict(GraphqlWire.readBytes(File(owner.localRoot,"owner.json"),12_582_912));require(value["snapshotGeneration"]==descriptor["snapshotGeneration"]&&value["cleanup"]==JsonPrimitive("HOST_VERIFICATION_REQUIRED"))
        val runtime=value[if(owner.serverHealth)"runtime"else"observation"]!!.jsonObject
        require(runtime.keys==setOf("version","platform","arch","execPath","heapSizeLimit","resources"))
        require(runtime["version"]==JsonPrimitive("v22.23.3")&&runtime["platform"]==JsonPrimitive("linux")&&runtime["arch"]==JsonPrimitive("arm64")&&runtime["execPath"]==JsonPrimitive(LspGraphqlLaunchPolicy.NODE))
        require(runtime["heapSizeLimit"]?.jsonPrimitive?.longOrNull in 1..402_653_184L)
        val wanted=mapOf("Max cpu time" to ("60" to "seconds"),"Max file size" to ("67108864" to "bytes"),"Max core file size" to ("0" to "bytes"),"Max processes" to ("256" to "processes"),"Max open files" to ("256" to "files"),"Max address space" to ("17179869184" to "bytes"))
        val resources=runtime["resources"]!!.jsonObject;require(resources.keys==wanted.keys)
        wanted.forEach{(k,v)->val r=resources[k]!!.jsonObject;require(r.keys==setOf("soft","hard","unit")&&r["soft"]==JsonPrimitive(v.first)&&r["hard"]==JsonPrimitive(v.first)&&r["unit"]==JsonPrimitive(v.second))}
        if(owner.serverHealth){val observed=value["observed"]!!.jsonObject;require(observed.keys==setOf("validDocument","nativeCompletion","nativeHover","nativeNamedOutline","nativeUnknownField","nativeSyntaxPosition","dirtyEmptyNativeEOF","framing")&&observed.values.all{it==JsonPrimitive(true)})}
        else {val birth=value["birth"]!!.jsonObject;val actual=GraphqlWire.strict(GraphqlWire.readBytes(File(owner.localRoot,"server.json"),65_536));require(birth==actual)}
        true
    }.getOrDefault(false)
    internal fun descriptor(stage:File,node:GraphqlPackageProof?,owner:GraphqlHealthPrivateState,deadline:GraphqlDeadline):JsonObject {
        deadline.check();val authorityBytes=GraphqlWire.readBytes(File(stage,"GRAPHQL_AUTHORITY.json"),1_048_576);val a=GraphqlWire.strict(authorityBytes)
        val seedBytes=GraphqlWire.readBytes(File(stage,LocalUbuntuGraphqlLaunchAuthority.HEALTH_SEED),1_048_576);val seed=GraphqlWire.strict(seedBytes)
        fun text(k:String)=requireNotNull(a[k]?.jsonPrimitive?.content)
        val serverRoot=LspGraphqlLaunchPolicy.ROOT;val runtimeRoot=LspGraphqlLaunchPolicy.NODE_ROOT
        val baseUri="file://${owner.guestRoot}/fixture"
        val texts=listOf(Triple("config",".graphqlrc.json","{\"schema\":[\"schema-a.graphql\",\"schema-b.graphql\"],\"documents\":[\"fragments.graphql\"]}"),
            Triple("schema","schema-a.graphql","type Query {\n  \"Health greeting\"\n  hello: String!\n  viewer: User\n}\n"),
            Triple("schema","schema-b.graphql","type User { id: ID! name: String }\n"),
            Triple("fragment","fragments.graphql","fragment UserFields on User { id name }\n"))
        val sources=texts.map{(role,path,body)->GraphqlCapturedSource(role,baseUri+"/"+path,path,body,LspGraphqlLaunchPolicy.sha(body.toByteArray()),"disk")}
        fun roleDigest(role:String)=LspGraphqlLaunchPolicy.sha(sources.filter{it.role==role}.joinToString("\n"){it.relativePath+":"+it.digest}.toByteArray())
        val zero="0".repeat(64);val profile=LspGraphqlLaunchPolicy.PROFILE
        val helpers=JsonObject(a["helpers"]!!.jsonObject.toMap())
        val managed=buildJsonObject {
            put("familyId","lsp.graphql-library");put("version","5.7.0");put("runtimeFamilyId","runtime.graphql-node");put("runtimeVersion","22.23.3");put("profileRevision",profile)
            put("serverReceiptSha256",if(node!=null)seed["packageReceiptSha256"]!!.jsonPrimitive.content else zero);put("runtimeReceiptSha256",node?.receiptSha256?:seed["packageReceiptSha256"]!!.jsonPrimitive.content)
            put("serverSealSha256",zero);put("runtimeSealSha256",node?.sealSha256?:zero);put("serverActivationId",if(node!=null)text("activation")else "00000000-0000-0000-0000-000000000000");put("runtimeActivationId",node?.activationId?:text("activation"))
            put("selectedPackageIdentity",zero);put("discoveredExecutable",LspGraphqlLaunchPolicy.COMMAND);put("nodeExecutable",LspGraphqlLaunchPolicy.NODE);put("adapterExecutable",serverRoot+"/"+LspGraphqlLaunchPolicy.ADAPTER)
            put("runtimeClosureSha256",text("closureSha256"));put("sourceBindingSha256",text("sourceBindingSha256"));put("importReviewSha256",text("importReviewSha256"));put("noticeAdmissionSha256",text("noticeAdmissionSha256"));put("dataAdmissionSha256",text("dataAdmissionSha256"));put("helperHashes",helpers)
        }
        return buildJsonObject {
            put("schema",1);put("profile",profile);put("mode",if(node==null)"node-health"else"server-health");put("ownerToken",owner.token);put("ownerGeneration",1);put("snapshotGeneration",GraphqlDescriptorCodec.digest(sources));put("workspaceLifetime","graphql-fixed-health");put("rootIdentity",owner.identity);put("canonicalRootUri",baseUri);put("consentIdentity",zero)
            put("configDigest",sources.first().digest);put("schemaDigest",roleDigest("schema"));put("fragmentsDigest",roleDigest("fragment"));put("serializedDescriptorDigest",GraphqlDescriptorCodec.digest(sources));put("sources",GraphqlDescriptorCodec.sources(sources))
            put("serverRoot",serverRoot);put("runtimeRoot",runtimeRoot);put("privateRoot",owner.guestRoot);put("privateDirectoryIdentity",owner.identity);put("nodeExecutable",LspGraphqlLaunchPolicy.NODE);put("managedIdentity",managed)
            put("proofFile",(if(node==null)runtimeRoot else serverRoot)+"/GRAPHQL_AUTHORITY.json");put("proofDigest",LspGraphqlLaunchPolicy.sha(authorityBytes));put("healthSeedSha256",LspGraphqlLaunchPolicy.sha(seedBytes))
        }
    }
    suspend fun runStaged(appRoot:String,stage:File,node:ManagedPackageRecord,hold:LocalPackageInstallJournal.GraphqlHealthHold,deadline:GraphqlDeadline):ExecResult=LocalExecutionSubstrate.runGraphqlStagedHealth(appRoot,stage,node,hold,deadline)
    suspend fun runNodeStaged(appRoot:String,stage:File,hold:LocalPackageInstallJournal.GraphqlHealthHold,deadline:GraphqlDeadline):ExecResult=LocalExecutionSubstrate.runGraphqlStagedHealth(appRoot,stage,null,hold,deadline)
}

/** Owns factory, reader, close, signaling and reap attempts. All waits use the parent absolute deadline. */
internal object GraphqlHealthCapture {
    private val retained=java.util.concurrent.CopyOnWriteArrayList<Any>()
    suspend fun capture(factory:()->Process,deadline:GraphqlDeadline,owner:GraphqlHealthPrivateState):ExecResult {
        val work=deadline.reserve(GraphqlResourcePolicy.RETIRE_MS);work.check()
        val handle=AtomicReference<Process?>();val factoryAttempt=GraphqlIoAttempt("health-factory"){factory().also{handle.set(it)}};retained+=factoryAttempt;retained+=owner
        if(!factoryAttempt.await(work)){
            // No handle exists yet: retain both factory and owner, never release stage/hold. The retained
            // completion watcher kills a late returned direct child without deleting any private state.
            CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{while(!factoryAttempt.complete)delay(50);if(factoryAttempt.succeeded)GraphqlIoAttempt("health-late-kill"){factoryAttempt.result().destroyForcibly()}.also{retained+=it}}
            return ExecResult(124,"GraphQL native factory custody UNKNOWN; exact health hold retained",true,outputIncomplete=true)
        }
        val process=runCatching{factoryAttempt.result()}.getOrElse{return ExecResult(70,"GraphQL native factory failed; exact health hold retained",false,outputIncomplete=true)}
        val bytes=java.io.ByteArrayOutputStream();val lock=Any();var accepting=true;var overflow=false
        val attempts=java.util.concurrent.CopyOnWriteArrayList<GraphqlIoAttempt<*>>()
        fun<T> attempt(name:String,action:()->T)=GraphqlIoAttempt("health-$name",action).also{check(attempts.size<16);attempts+=it;retained+=it}
        val reader=attempt("read"){val chunk=ByteArray(8192);while(true){val n=process.inputStream.read(chunk);if(n<0)break;check(n>0);synchronized(lock){if(accepting){if(bytes.size()+n>1_048_576){overflow=true;error("Health output bound exceeded")};bytes.write(chunk,0,n)}}}}
        attempt("stdin-close"){process.outputStream.close()};val reap=attempt("reap"){process.waitFor()}
        var failure:Throwable?=null;var timedOut=false;var exit=70;var semanticObservation=false;var receipt=GraphqlTeardownReceipt.unknown("Health cleanup not measured")
        try{while(process.isAlive){currentCoroutineContext().ensureActive();if(work.expired){timedOut=true;break};reader.failure?.let{throw it};owner.observe();delay(minOf(GraphqlResourcePolicy.MONITOR_MS,work.remainingMillis().coerceAtLeast(1)))};if(!process.isAlive)exit=process.exitValue()}
        catch(t:Throwable){failure=t}
        finally{withContext(NonCancellable){
            val group=attempt("group-stop"){owner.terminateExact(deadline)};group.await(deadline.limited(2000))
            if(process.isAlive)attempt("direct-kill"){process.destroyForcibly()}
            reader.await(deadline.limited(500));synchronized(lock){accepting=false}
            attempt("stdout-close"){process.inputStream.close()};attempt("stderr-close"){process.errorStream.close()}
            while(!deadline.reserve(250).expired&&attempts.any{!it.complete})delay(10)
            semanticObservation=reader.succeeded&&!overflow&&LocalUbuntuGraphqlLspHealth.validObservation(synchronized(lock){bytes.toByteArray()},owner)
            val settled=attempts.all{it.succeeded}&&factoryAttempt.succeeded&&reap.succeeded&&!process.isAlive
            val direct=reap.succeeded&&!process.isAlive;val empty=runCatching{owner.groupEmpty()}.getOrDefault(false)
            val cleanup=settled&&empty&&!deadline.expired&&runCatching{owner.cleanup();true}.getOrDefault(false)
            receipt=GraphqlTeardownReceipt(settled&&empty&&cleanup,direct,empty,owner.birthVerified(),factoryAttempt.succeeded,true,reader.succeeded,attempts.all{it.succeeded},cleanup,if(cleanup)null else "GraphQL health custody UNKNOWN; durable owner retained")
        }}
        if(failure is CancellationException)throw failure as CancellationException
        timedOut=timedOut||deadline.expired
        val output=(if(semanticObservation&&exit==0)if(owner.serverHealth)LocalUbuntuGraphqlLspHealth.SUCCESS else LocalUbuntuGraphqlLspHealth.NODE_SUCCESS else "GraphQL genuine native observation failed")+if(receipt.complete)"\n${LocalUbuntuGraphqlLspHealth.CLEANUP}\n"else"\nGraphQL health owned cleanup UNKNOWN\n"
        val result=ExecResult(if(timedOut)124 else if(failure!=null||!receipt.complete||!semanticObservation)70 else exit,output,timedOut,outputIncomplete=overflow||!reader.succeeded||!receipt.complete)
        if(receipt.complete){retained.remove(factoryAttempt);retained.remove(owner);attempts.forEach{retained.remove(it)};return LocalUbuntuGraphqlLspHealth.recorded(result,receipt)}
        return result
    }
}

internal class GraphqlHealthPrivateState private constructor(val localRoot:File,val guestRoot:String,val nodeExecutable:String,val token:String,val serverHealth:Boolean) {
    val identity=GraphqlWire.directoryIdentity(localRoot)
    val localWorkspace=File(localRoot,"workspace")
    @Volatile private var birth:GraphqlWire.ProcIdentity?=null
    @Volatile private var nodeBirth:GraphqlWire.ProcIdentity?=null
    fun verify(){check(GraphqlWire.directoryIdentity(localRoot)==identity)}
    fun shellCommand(command:List<String>)=GraphqlWire.shellCommand(localRoot,token,command)
    fun observe(){verify();if(birth==null){val f=File(localRoot,"host.json");if(!f.exists())return;val r=GraphqlWire.strict(GraphqlWire.readBytes(f,65536));require(r["token"]==JsonPrimitive(token));val p=GraphqlWire.proc(GraphqlWire.positiveInt(r["pid"]));require(p.start==r["start"]?.jsonPrimitive?.content&&p.pid==p.group);birth=p}
        val b=requireNotNull(birth);val group=GraphqlWire.group(b.group);require(group.size<=GraphqlResourcePolicy.GROUP_MEMBERS_MAX&&group.sumOf{it.rss}<=GraphqlResourcePolicy.OBSERVED_RSS_BYTES_MAX)
        val file=File(localRoot,"server.json");if(nodeBirth==null&&file.exists()){val r=GraphqlWire.strict(GraphqlWire.readBytes(file,65536));require(r["token"]==JsonPrimitive(token));val n=GraphqlWire.proc(GraphqlWire.positiveInt(r["pid"]));require(n.start==r["start"]?.jsonPrimitive?.content&&n.group==b.group);nodeBirth=n;GraphqlWire.write(File(localRoot,"host-birth-accepted.json"),buildJsonObject{put("schema",1);put("ownerToken",token);put("pid",n.pid);put("start",n.start);put("group",n.group)}.toString().toByteArray())};nodeBirth?.let{n->require(group.none{it.parent==n.pid&&it.pid!=n.pid})}
    }
    fun terminateExact(deadline:GraphqlDeadline){verify();val b=birth?:runCatching{val r=GraphqlWire.strict(GraphqlWire.readBytes(File(localRoot,"host.json"),65536));require(r["token"]==JsonPrimitive(token));GraphqlWire.recordedBirth(r)}.getOrNull()?:error("GraphQL health birth unavailable");birth=b
        for(signal in listOf(15,9)){val group=GraphqlWire.group(b.group);if(group.isEmpty())return;require(group.singleOrNull{it.pid==b.pid}?.start==b.start);GraphqlWire.signalGroup(b,signal);if(signal==15&&!deadline.expired)Thread.sleep(minOf(200,deadline.remainingMillis()))}
    }
    fun groupEmpty():Boolean=birth?.let{GraphqlWire.group(it.group).isEmpty()}?:false
    fun birthVerified():Boolean=birth!=null&&nodeBirth!=null
    fun cleanup(){require(birthVerified()&&groupEmpty());verify();GraphqlWire.removePrivate(localRoot,identity)}
    fun writeDescriptor(value:JsonObject){verify();val bytes=value.toString().toByteArray(Charsets.UTF_8);GraphqlWire.write(File(localRoot,"owner.json"),bytes);GraphqlWire.write(File(localRoot,"owner.sha256"),(LspGraphqlLaunchPolicy.sha(bytes)+"\n").toByteArray())}
    companion object {
        fun allocate(appRoot:File,deadline:GraphqlDeadline,serverHealth:Boolean):GraphqlHealthPrivateState {deadline.check();val parent=File(appRoot,"runtime-tmp");require(parent.canonicalFile==parent.absoluteFile&&Files.isDirectory(parent.toPath(),NOFOLLOW_LINKS));val token=UUID.randomUUID().toString().replace("-","");val root=File(parent,"droide-graphql-health-$token");val mode=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));Files.createDirectory(root.toPath(),mode);for(name in listOf("home","tmp","config","cache","data","state","run","workspace")){deadline.check();Files.createDirectory(File(root,name).toPath(),mode)};return GraphqlHealthPrivateState(root,"/run/droide-graphql/${root.name}",LspGraphqlLaunchPolicy.NODE,token,serverHealth)}
    }
}
