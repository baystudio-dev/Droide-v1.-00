package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Authored byte-stream peer only. No native parser/schema/provider or real process is exercised. */
internal class GraphqlAuthoredHost : StdioProcessHost {
    override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
    var prepares = 0; var starts = 0; var genericStarts = 0
    val h = "a".repeat(64)
    val identity = GraphqlManagedLaunchIdentity("lsp.graphql-library","5.7.0","runtime.graphql-node","22.23.3",LspGraphqlProfile.IDENTITY,h,h,h,h,"selected","droide-graphql-local-lsp","/private/adapter","/private/node","activation","runtime-activation",h,h,h,h,h,emptyMap())
    var owner: Owner? = null
    var beforePrepare: CompletableDeferred<Unit>? = null
    var continuePrepare: CompletableDeferred<Unit>? = null
    var holdCleanup = false
    override suspend fun resolveExecutable(command:String):String? = error("Generic resolution forbidden")
    override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess { genericStarts++; error("Generic start forbidden") }
    override suspend fun reviewedGraphqlLaunchIdentity(command:String,workspaceRoot:String,deadline:GraphqlDeadline):GraphqlManagedLaunchIdentity? { assertEquals(LspGraphqlProfile.COMMAND,command);return identity }
    override suspend fun prepareReviewedGraphqlOwner(identity:GraphqlManagedLaunchIdentity,request:GraphqlOwnerRequest,deadline:GraphqlDeadline):GraphqlPreparedOwner {
        prepares++;beforePrepare?.complete(Unit);continuePrepare?.await();return Owner(identity,request).also{owner=it}
    }
    override suspend fun startReviewedGraphql(owner:GraphqlPreparedOwner,deadline:GraphqlDeadline,admitStart:(()->GraphqlHostedProcess)->GraphqlHostedProcess):GraphqlHostedProcess = admitStart { starts++;Peer(owner) }
    inner class Owner(override val identity:GraphqlManagedLaunchIdentity,override val request:GraphqlOwnerRequest):GraphqlPreparedOwner {
        private val current=AtomicBoolean(true)
        override val token="authored-owner-$prepares"
        override val isCurrent:Boolean get()=current.get()
        override fun fence(){current.set(false)}
        override fun close(){fence()}
        override suspend fun retire(deadline:GraphqlDeadline):GraphqlTeardownReceipt {fence();return if(holdCleanup)GraphqlTeardownReceipt.unknown("authored unsettled fixture")else GraphqlTeardownReceipt(true,true,true,true,true,true,true,true,true)}
    }
    class QueueInput:InputStream(){private val queue=LinkedBlockingQueue<Int>();override fun read():Int=queue.take();fun offer(bytes:ByteArray){bytes.forEach{queue.put(it.toInt() and 255)}};override fun close(){queue.offer(-1)}}
    inner class Peer(override val owner:GraphqlPreparedOwner):GraphqlHostedProcess {
        private var alive=true
        private val input=QueueInput()
        override val stdout:InputStream=input
        override val stderr:InputStream=ByteArrayInputStream(byteArrayOf())
        override val isAlive:Boolean get()=alive
        override val exitCode:Int? get()=if(alive)null else 0
        override val fatalControl:GraphqlControlEvent?=null
        override suspend fun awaitExit():Int {while(alive)delay(10);return 0}
        override suspend fun awaitBootstrapAccepted(deadline:GraphqlDeadline){}
        override fun onControl(listener:(GraphqlControlEvent)->Unit){}
        override fun registerRequest(id:JsonPrimitive,authority:JsonObject,deadline:GraphqlDeadline){}
        override fun registerDocument(uri:String,openGeneration:Long,version:Long,textDigest:String){}
        override suspend fun stopAndAwaitOwnedGroup(deadline:GraphqlDeadline)=owner.retire(deadline)
        override fun close(){alive=false;input.close()}
        override val stdin:OutputStream=object:ByteArrayOutputStream(){override fun flush(){
            val bytes=toByteArray();reset();if(bytes.isEmpty())return
            val message=ContentLengthProtocol.readMessage(ByteArrayInputStream(bytes))?:return
            val obj=Json.parseToJsonElement(message).jsonObject;val id=obj["id"]?:return;val method=obj["method"]?.jsonPrimitive?.content
            val params=obj["params"] as? JsonObject ?: buildJsonObject{}
            val result=when(method){
                "initialize"->buildJsonObject{put("capabilities",buildJsonObject{put("positionEncoding","utf-16");put("textDocumentSync",buildJsonObject{put("openClose",true);put("change",1)});put("completionProvider",buildJsonObject{});put("hoverProvider",true);put("documentSymbolProvider",true);put("diagnosticProvider",buildJsonObject{put("interFileDependencies",true);put("workspaceDiagnostics",false)})})}
                "droide/graphqlStatus"->buildJsonObject{put("ready",true)}
                "textDocument/completion"->buildJsonObject{put("isIncomplete",false);put("context",buildJsonObject{put("start",2);put("end",4);put("caret",4)});put("items",buildJsonArray{add(buildJsonObject{put("label","field");put("kind",5)})})}
                "textDocument/hover"->buildJsonObject{put("contents",buildJsonObject{put("kind","plaintext");put("value","authored hover fixture")})}
                "textDocument/documentSymbol"->JsonArray(emptyList())
                "textDocument/diagnostic"->buildJsonObject{put("kind","full");put("items",JsonArray(emptyList()));val auth=params["_graphqlAuthority"]!!.jsonObject;put("sourceAuthority",buildJsonObject{put("uri",auth["uri"]!!);put("origin","editor");put("snapshotGeneration",owner.request.snapshotGeneration);put("textDigest",auth["textDigest"]!!);put("openGeneration",auth["openGeneration"]!!);put("version",auth["version"]!!)})}
                else->JsonNull
            }
            val response=buildJsonObject{put("jsonrpc","2.0");put("id",id);put("result",result)}.toString()
            val out=ByteArrayOutputStream();ContentLengthProtocol.writeMessage(out,response);input.offer(out.toByteArray())
        }}
    }
}
internal fun graphqlFixtureRoot():File=Files.createTempDirectory("graphql-manager-authored-").toFile().also{File(it,".graphqlrc.json").writeText("""{"schema":"s.gql"}""");File(it,"s.gql").writeText("type Query { field: Int }")}
class LspGraphqlIntegrationTest {
 @Test fun noCaptureBeforeDeclinedApproval()=runBlocking {
    val root=graphqlFixtureRoot();File(root,".graphqlrc.json").writeText("not valid JSON and must not be read before approval")
    val host=GraphqlAuthoredHost();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var prompts=0
    val manager=GraphqlLspManager(scope,root,host,{true},{_,_->prompts++;false})
    manager.didOpen("q.gql","{ fi }");assertEquals("GraphQL analysis remains disabled.",manager.enableProjectAnalysis("q.gql"));assertEquals(1,prompts);assertEquals(0,host.prepares);assertEquals(0,host.starts);manager.close();scope.cancel();root.deleteRecursively();Unit
 }
 @Test fun nativeFakeCaptureCompletionAndSameTextLifetime()=runBlocking {
    val root=graphqlFixtureRoot();val host=GraphqlAuthoredHost();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var prompts=0
    val manager=GraphqlLspManager(scope,root,host,{true},{_,_->prompts++;true})
    manager.didOpen("q.gql","{ fi }");assertTrue(manager.enableProjectAnalysis("q.gql").startsWith("READY:"));val item=manager.completionItems("q.gql",1,5).single()
    assertEquals("field",item.insertText);assertEquals(3,item.editStartColumn);assertEquals(5,item.editEndColumn);assertTrue(item.applicationGuard!!.isCurrent())
    manager.didChange("q.gql","{ fi }");assertTrue(item.applicationGuard!!.isCurrent());assertEquals(1,prompts);assertEquals(0,host.genericStarts)
    manager.didSaveSnapshot("q.gql","stale saved","stale current");assertEquals(1,manager.completionItems("q.gql",1,5,null,"{ fi }").size)
    manager.didClose("q.gql");manager.didSaveSnapshot("q.gql","old","old");assertTrue(runCatching{manager.completionItems("q.gql",1,5)}.isFailure)
    manager.close();scope.cancel();root.deleteRecursively();Unit
 }
 @Test fun queuedPublicationCannotRebindAfterChange()=runBlocking {
    val root=graphqlFixtureRoot();val host=GraphqlAuthoredHost();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val queued=java.util.concurrent.CopyOnWriteArrayList<GraphqlDiagnosticPublication>()
    val manager=GraphqlLspManager(scope,root,host,{true},{_,_->true},{queued.add(it)})
    manager.didOpen("q.gql","{ fi }");manager.enableProjectAnalysis("q.gql");manager.diagnose("q.gql");delay(30);val old=queued.last();manager.didChange("q.gql","{ field }");var published=false;assertFalse(old.publishIfCurrent{published=true});assertFalse(published)
    manager.close();scope.cancel();root.deleteRecursively();Unit
 }
 @Test fun revokeWhilePreparationHeldNeverLaunchesLateOwner()=runBlocking {
    val root=graphqlFixtureRoot();val host=GraphqlAuthoredHost();host.beforePrepare=CompletableDeferred();host.continuePrepare=CompletableDeferred();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val manager=GraphqlLspManager(scope,root,host,{true},{_,_->true});manager.didOpen("q.gql","{ fi }")
    val enable=async{manager.enableProjectAnalysis("q.gql")};host.beforePrepare!!.await();manager.revokeProjectAnalysis("q.gql");host.continuePrepare!!.complete(Unit);enable.await();assertEquals(0,host.starts);assertTrue(host.owner == null || !host.owner!!.isCurrent);assertFalse(manager.projectAnalysisStatus("q.gql").enabled)
    manager.close();scope.cancel();root.deleteRecursively();Unit
 }
}
