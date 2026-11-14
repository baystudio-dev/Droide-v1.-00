package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test



/** Exact manager/transport with authored peers and real local observation. No upstream execution. */
class LspSvelteIntegrationTest {
    @Test(timeout=12000) fun passiveEventsRequireConsentAndManagedIdentity()=runBlocking<Unit>{Fixture().use{f->
        f.file("App.svelte");f.manager.didOpen("App.svelte","<h1>dirty</h1>")
        assertEquals(0,f.host.discoveries)
        assertTrue(runCatching{f.manager.completionItems("App.svelte",1,1)}.exceptionOrNull() is LspProjectAnalysisRequiredException)
        f.host.managed=false;f.manager.enableProjectAnalysis("App.svelte")
        awaitUntil{f.manager.projectAnalysisStatus("App.svelte")?.readiness==LspProjectAnalysisReadiness.FAILED}
        assertTrue(f.host.peers.isEmpty());assertEquals(0,f.fallbackCalls)
    }}
    @Test(timeout=12000) fun realRegistrationPrimerCustomReplayPrimaryAndPullAreOrdered()=runBlocking<Unit>{Fixture().use{f->
        f.file("App.svelte");f.file("dep.ts");f.manager.didOpen("dep.ts","export const answer = '😀';\r\n")
        f.manager.didOpen("App.svelte","<script>import {answer} from './dep'</script>")
        f.manager.enableProjectAnalysis("App.svelte");ready(f,"App.svelte");val p=f.host.peers.single()
        val methods=p.observed.map{it["method"]?.jsonPrimitive?.contentOrNull}
        assertTrue(methods.indexOf("$/getFileReferences")<methods.indexOf("$/onDidChangeTsOrJsFile"))
        assertTrue(methods.indexOf("$/onDidChangeTsOrJsFile")<methods.indexOf("textDocument/didOpen"))
        assertTrue(methods.indexOf("textDocument/didOpen")<methods.indexOf("textDocument/diagnostic"))
        assertEquals(JsonPrimitive("file:///workspace/dep.ts"),p.observed.first{it["method"]==JsonPrimitive("$/getFileReferences")}["params"])
        assertEquals("export const answer = '😀';\r\n",p.params("$/onDidChangeTsOrJsFile")["changes"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("svelte",p.params("textDocument/didOpen")["textDocument"]!!.jsonObject["languageId"]!!.jsonPrimitive.content)
        assertEquals(1,p.observed.count{it["method"]==JsonPrimitive("textDocument/didOpen")})
        assertEquals("/workspace",f.host.cwd.single());assertEquals(listOf("/owned/svelteserver","--stdio"),p.argv)
        assertEquals(JsonNull,p.observed.first{it["id"]==JsonPrimitive("watch-1")}["result"])
        assertEquals("fixture",f.manager.completionItems("App.svelte",1,1).single().label)
    }}
    @Test(timeout=12000) fun emptyCompanionTextUsesOneFullChangeAndNoSecondPrimer()=runBlocking<Unit>{Fixture().use{f->
        f.file("App.svelte");f.file("dep.js");f.manager.didOpen("dep.js","dirty");f.manager.didOpen("App.svelte","<p/>")
        f.manager.enableProjectAnalysis("App.svelte");ready(f,"App.svelte");val p=f.host.peers.single()
        f.manager.didChange("dep.js","");f.manager.completionItems("App.svelte",1,1)
        assertEquals(Json.parseToJsonElement("[{\"text\":\"\"}]"),p.params("$/onDidChangeTsOrJsFile")["changes"])
        assertEquals(1,p.observed.count{it["method"]==JsonPrimitive("$/getFileReferences")})
    }}
    @Test(timeout=12000) fun nullPrimerAndUnsupportedDirtyScriptFailUnavailable()=runBlocking<Unit>{
        Fixture().use{f->f.file("App.svelte");f.file("dep.ts");f.manager.didOpen("dep.ts","dirty");f.host.primerResult=JsonNull
            f.manager.enableProjectAnalysis("App.svelte");awaitUntil{f.manager.projectAnalysisStatus("App.svelte")?.readiness==LspProjectAnalysisReadiness.FAILED}
            assertFalse(f.host.peers.single().observed.any{it["method"]==JsonPrimitive("$/onDidChangeTsOrJsFile")})}
        Fixture().use{f->f.file("App.svelte");f.file("dep.tsx");f.manager.didOpen("dep.tsx","dirty")
            f.manager.enableProjectAnalysis("App.svelte");awaitUntil{f.manager.projectAnalysisStatus("App.svelte")?.readiness==LspProjectAnalysisReadiness.FAILED}
            assertFalse(f.host.peers.single().observed.any{it["method"]==JsonPrimitive("textDocument/didOpen")})}
    }
    @Test(timeout=12000) fun companionCloseRetiresExactOwnerAndPrimaryDirtyTextSurvives()=runBlocking<Unit>{Fixture().use{f->
        f.file("App.svelte");f.file("dep.ts");f.manager.didOpen("dep.ts","dirty");f.manager.didOpen("App.svelte","<p>kept</p>")
        f.manager.enableProjectAnalysis("App.svelte");ready(f,"App.svelte");val old=f.host.peers.single()
        f.manager.didClose("dep.ts");awaitUntil{!old.isAlive}
        runCatching{f.manager.completionItems("App.svelte",1,1)};ready(f,"App.svelte")
        assertEquals(1,f.approvalCalls);assertEquals("<p>kept</p>",f.host.peers.last().params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }}
    @Test(timeout=15000) fun realWatchedDiskMutationDrainsThenRestartsUnderSameGrant()=runBlocking<Unit>{Fixture().use{f->
        f.file("App.svelte");val dep=f.file("dep.ts");f.manager.didOpen("App.svelte","<p/>")
        f.manager.enableProjectAnalysis("App.svelte");ready(f,"App.svelte");val old=f.host.peers.single();dep.writeText("real mutation")
        awaitUntil{!old.isAlive}
        assertEquals(JsonPrimitive("file:///workspace/dep.ts"),old.params("workspace/didChangeWatchedFiles")["changes"]!!.jsonArray.first().jsonObject["uri"])
        assertTrue(f.manager.projectAnalysisStatus("App.svelte")!!.enabled)
        runCatching{f.manager.completionItems("App.svelte",1,1)};ready(f,"App.svelte");assertEquals(1,f.approvalCalls)
    }}
    @Test(timeout=15000) fun configurationMutationRevokesAndNoFeatureEscapes()=runBlocking<Unit>{Fixture().use{f->
        f.file("App.svelte");val cfg=f.file("package.json");cfg.writeText("{}")
        f.manager.enableProjectAnalysis("App.svelte");ready(f,"App.svelte");val old=f.host.peers.single();cfg.writeText("{\"type\":\"module\"}")
        awaitUntil{!old.isAlive};assertFalse(f.manager.projectAnalysisStatus("App.svelte")!!.enabled)
        assertTrue(runCatching{f.manager.completionItems("App.svelte",1,1)}.exceptionOrNull() is LspProjectAnalysisRequiredException)
    }}
    @Test(timeout=12000) fun oldCompletionAndWorkspaceEditGuardRejectCompanionChanges()=runBlocking<Unit>{Fixture().use{f->
        f.file("App.svelte");f.file("dep.ts");f.manager.didOpen("dep.ts","one");f.manager.didOpen("App.svelte","<p/>")
        f.manager.enableProjectAnalysis("App.svelte");ready(f,"App.svelte");val item=f.manager.completionItems("App.svelte",1,1).single()
        assertTrue(item.applicationGuard!!.isCurrent());f.manager.didChange("dep.ts","two");assertFalse(item.applicationGuard!!.isCurrent())
    }}
    @Test(timeout=12000) fun capturedDiagnosticsWithdrawOnPrimaryAndCompanionRevisionsButNotNoOps()=runBlocking<Unit>{Fixture().use{f->
        f.file("App.svelte");f.file("dep.ts");f.manager.didOpen("App.svelte","<p/>");f.manager.didOpen("dep.ts","one")
        f.manager.enableProjectAnalysis("App.svelte");ready(f,"App.svelte")
        val peer=f.host.peers.single();peer.pullResult=errorPull()
        suspend fun publish() {f.manager.diagnose("App.svelte");assertTrue(f.manager.diagnostics.value.values.flatten().isNotEmpty())}
        publish();f.manager.didChange("dep.ts","one");assertTrue(f.manager.diagnostics.value.values.flatten().isNotEmpty())
        f.manager.didChange("dep.ts","two");assertTrue(f.manager.diagnostics.value.isEmpty())
        publish();f.manager.didChange("App.svelte","<p>new</p>");assertTrue(f.manager.diagnostics.value.isEmpty())
        publish();f.manager.didSaveSnapshot("dep.ts","saved","latest");assertTrue(f.manager.diagnostics.value.isEmpty())
    }}
    @Test(timeout=12000) fun capturedDiagnosticsWithdrawOnNullableSavePublicationAndClose()=runBlocking<Unit>{Fixture().use{f->
        f.file("App.svelte");f.file("dep.ts");f.manager.didOpen("App.svelte","<p/>")
        f.manager.enableProjectAnalysis("App.svelte");ready(f,"App.svelte");f.host.peers.single().pullResult=errorPull()
        f.manager.diagnose("App.svelte");assertTrue(f.manager.diagnostics.value.values.flatten().isNotEmpty())
        f.manager.didSave("dep.ts",null);assertTrue(f.manager.diagnostics.value.isEmpty())
        f.manager.diagnose("App.svelte");assertTrue(f.manager.diagnostics.value.values.flatten().isNotEmpty())
        f.manager.didClose("dep.ts");assertTrue(f.manager.diagnostics.value.isEmpty())
    }}
    @Test(timeout=12000) fun completedUnchangedObservationWithdrawsOlderCapturedDiagnostics()=runBlocking<Unit>{Fixture().use{f->
        f.file("App.svelte");f.manager.didOpen("App.svelte","<p/>")
        f.manager.enableProjectAnalysis("App.svelte");ready(f,"App.svelte");f.host.peers.single().pullResult=errorPull()
        f.manager.diagnose("App.svelte");assertTrue(f.manager.diagnostics.value.values.flatten().isNotEmpty())
        awaitUntil{f.manager.diagnostics.value.isEmpty()}
        assertTrue(f.manager.projectAnalysisStatus("App.svelte")!!.enabled)
    }}
    @Test(timeout=12000) fun unavailableRootKeepsPriorGrantRevocableAndRestorationNeedsNoNewApproval()=runBlocking<Unit>{Fixture().use{f->
        f.file("App.svelte");f.manager.didOpen("App.svelte","<p>dirty</p>")
        f.manager.enableProjectAnalysis("App.svelte");ready(f,"App.svelte");val old=f.host.peers.single()
        PathSecurity.deleteTreeNoFollow(f.root)
        val absent=f.manager.projectAnalysisStatus("App.svelte")!!;assertFalse(absent.enabled);assertTrue(absent.canRevoke)
        awaitUntil{!old.isAlive};assertTrue(f.manager.projectAnalysisStatus("App.svelte")!!.canRevoke)
        f.root.mkdirs();f.file("App.svelte")
        assertTrue(f.manager.projectAnalysisStatus("App.svelte")!!.enabled)
        runCatching{f.manager.completionItems("App.svelte",1,1)};ready(f,"App.svelte");assertEquals(1,f.approvalCalls)
        PathSecurity.deleteTreeNoFollow(f.root);f.manager.revokeProjectAnalysis("App.svelte")
        f.root.mkdirs();f.file("App.svelte");assertFalse(f.manager.projectAnalysisStatus("App.svelte")!!.enabled)
    }}
    private fun errorPull():JsonElement=Json.parseToJsonElement("""{"kind":"full","items":[{"message":"captured fixture error","severity":1,"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]}""")
    private suspend fun ready(f:Fixture,path:String)=awaitUntil{f.manager.projectAnalysisStatus(path)?.readiness==LspProjectAnalysisReadiness.READY}
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("droide-svelte-").toFile().canonicalFile
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val host=Host(root);var approvalCalls=0;var fallbackCalls=0
        var approvalGate:CompletableDeferred<Unit>?=null
        val manager=ProfessionalLspManager(scope,FileRepository(root),object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String):String {fallbackCalls++;error("No CLI execution")}
            override suspend fun lsp(op:String,path:String,line:Int,col:Int):String {fallbackCalls++;error("No CLI execution")}
        },remoteProcessHost=host,approveProjectAnalysis={_,current -> approvalCalls++;approvalGate?.await();current()})
        fun file(path:String)=File(root,path).apply {parentFile.mkdirs();writeText("# inert host fixture")}
        override fun close() {manager.close();host.peers.forEach {it.close()};job.cancel();PathSecurity.deleteTreeNoFollow(root)}
    }
    private class Host(private val root:File):StdioProcessHost {
        override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
        val peers=CopyOnWriteArrayList<Peer>();val cwd=CopyOnWriteArrayList<String>()
        var discoveries=0;@Volatile var holdInitialize=false
        var prepareGate:CompletableDeferred<Unit>?=null
        var openGate:CountDownLatch?=null
        val prepareEntered=CompletableDeferred<Unit>();@Volatile var prepareCanceled=false
        var managed=true
        var primerResult:JsonElement=JsonArray(emptyList())
        override suspend fun resolveExecutable(command:String):String {discoveries++;return "/opt/droide/bin/$command"}
        override suspend fun reviewedSvelteLaunchIdentity(resolvedExecutable:String,workspaceRoot:String):SvelteManagedLaunchIdentity? = if(managed) identity else null
        private val identity=SvelteManagedLaunchIdentity("lsp.svelte-language-server","0.18.5",
            "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64),"f".repeat(64),
            "fixture-policy","/owned/node","22.22.2","fixture-selected",
            "/opt/droide/bin/svelteserver","/owned/svelteserver")
        override suspend fun startReviewedSvelteInWorkspace(identity:SvelteManagedLaunchIdentity,argv:List<String>,environment:Map<String,String>,remoteCwd:String,admitStart:(()->HostedStdioProcess)->HostedStdioProcess):HostedStdioProcess {
            prepareEntered.complete(Unit)
            try {prepareGate?.await()} catch(cancel:CancellationException) {prepareCanceled=true;throw cancel}
            return admitStart {cwd+=remoteCwd;Peer(argv,holdInitialize,openGate).also{it.primerResult=primerResult;peers+=it}}
        }
        override suspend fun pathMapper()=WorkspacePathMapper(root,"/workspace")
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess=Peer(argv,false).also {peers+=it}
        override suspend fun startInWorkspace(argv:List<String>,environment:Map<String,String>,workspaceDirectory:String):HostedStdioProcess {
            prepareEntered.complete(Unit)
            try {prepareGate?.await()} catch(cancel:CancellationException) {prepareCanceled=true;throw cancel}
            cwd+=workspaceDirectory
            return Peer(argv,holdInitialize,openGate).also {peers+=it}
        }
    }
    private class Peer(val argv:List<String>,val holdInitialize:Boolean,val openGate:CountDownLatch?=null):HostedStdioProcess {
        val observed=CopyOnWriteArrayList<JsonObject>()
        private val alive=AtomicBoolean(true)
        private val out=QueuedInput();private val err=QueuedInput();private val pending=ByteArrayOutputStream()
        var primerResult:JsonElement=JsonArray(emptyList())
        var pullResult:JsonElement=Json.parseToJsonElement("{\"kind\":\"full\",\"items\":[]}")
        var actionResult:JsonElement=JsonArray(emptyList())
        var resolvedAction:JsonElement=buildJsonObject {}
        var formatResult:JsonElement=JsonArray(emptyList())
        @Volatile var holdCompletion=false
        @Volatile var holdSymbols=false
        @Volatile var failCloseWrite=false
        override val stdin=object:OutputStream() {
            @Synchronized override fun write(b:Int) { pending.write(b) }
            @Synchronized override fun write(b:ByteArray,off:Int,len:Int) { pending.write(b,off,len) }
            @Synchronized override fun flush() {
                val message=Json.parseToJsonElement(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray()))!!).jsonObject
                pending.reset();observed+=message
                when(message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> if(!holdInitialize) reply(message,capabilities())
                    "initialized" -> send(buildJsonObject {
                        put("jsonrpc","2.0");put("id","watch-1");put("method","client/registerCapability")
                        put("params",buildJsonObject {put("registrations",buildJsonArray {add(buildJsonObject {
                            put("id","watch-a");put("method","workspace/didChangeWatchedFiles");put("registerOptions",buildJsonObject {
                                put("watchers",buildJsonArray {add(buildJsonObject {put("globPattern",LspSvelteFilePoller.GLOB)})})
                            })
                        })})})
                    })
                    "$/getFileReferences" -> reply(message,primerResult)
                    "textDocument/diagnostic" -> reply(message,pullResult)
                    "textDocument/didOpen" -> openGate?.await()
                    "textDocument/completion" -> if(!holdCompletion) reply(message,completion(message["params"]!!.jsonObject["position"]!!.jsonObject))
                    "textDocument/hover" -> reply(message,buildJsonObject{put("contents","fixture hover")})
                    "textDocument/signatureHelp" -> reply(message,JsonNull)
                    "textDocument/semanticTokens/full" -> reply(message,Json.parseToJsonElement("{\"data\":[0,0,2,0,0]}"))
                    "workspace/symbol" -> if(!holdSymbols) reply(message,JsonArray(emptyList()))
                    "textDocument/definition", "textDocument/references" -> reply(message,Json.parseToJsonElement("""[{"uri":"file:///workspace/b.ps1","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]"""))
                    "textDocument/formatting" -> reply(message,formatResult)
                    "textDocument/codeAction" -> reply(message,actionResult)
                    "codeAction/resolve" -> reply(message,resolvedAction)
                    "textDocument/didClose" -> if (failCloseWrite) throw IOException("fixture close write failed")
                    "shutdown" -> reply(message,JsonNull)
                    "exit" -> close()
                }
            }
        }
        override val stdout:InputStream get()=out
        override val stderr:InputStream get()=err
        override val isAlive get()=alive.get()
        override val exitCode get()=if(isAlive)null else 0
        override suspend fun awaitExit():Int { while(isAlive)delay(5);return 0 }
        override fun close() { if(alive.compareAndSet(true,false)) {out.close();err.close()} }
        suspend fun request(method:String,params:JsonElement):JsonObject {
            val id="inbound-"+observed.size+"-"+System.nanoTime()
            send(buildJsonObject {put("jsonrpc","2.0");put("id",id);put("method",method);put("params",params)})
            return withTimeout(2000) {while(true) {observed.firstOrNull {it["id"]==JsonPrimitive(id)}?.let{return@withTimeout it};delay(5)};error("unreachable")}
        }
        fun completion(position:JsonObject=buildJsonObject{put("line",0);put("character",0)})=buildJsonArray {
            add(buildJsonObject {put("label","fixture");put("textEdit",buildJsonObject{
                put("range",buildJsonObject{put("start",position);put("end",position)});put("newText","fixture")})})
        }
        fun capabilities()=Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":{"openClose":true,"change":2,"save":{"includeText":false}},"hoverProvider":true,"documentSymbolProvider":true,"workspaceSymbolProvider":true,"completionProvider":{"resolveProvider":true,"triggerCharacters":[".","/","@"]},"signatureHelpProvider":{"triggerCharacters":["(",",","<"],"retriggerCharacters":[")"]},"semanticTokensProvider":{"full":true,"range":true,"legend":{"tokenTypes":["variable"],"tokenModifiers":[]}},"definitionProvider":true,"referencesProvider":true,"renameProvider":true,"codeActionProvider":{"codeActionKinds":["quickfix"],"resolveProvider":true},"documentFormattingProvider":true,"callHierarchyProvider":true,"diagnosticProvider":{"interFileDependencies":true,"workspaceDiagnostics":false}}}""")
        fun finishInitialize()=reply(observed.first{it["method"]==JsonPrimitive("initialize")},capabilities())
        fun params(method:String)=observed.last { it["method"]?.jsonPrimitive?.content==method }["params"]!!.jsonObject
        suspend fun next(method:String,after:Int=0):JsonObject=withTimeout(2000) {
            while(true) { val matches=observed.filter { it["method"]?.jsonPrimitive?.content==method };if(matches.size>after)return@withTimeout matches[after];delay(5) }
            error("unreachable")
        }
        fun reply(request:JsonObject,result:JsonElement)=send(buildJsonObject { put("jsonrpc","2.0");put("id",request["id"]!!);put("result",result) })
        fun publish(uri:String,message:String,version:Int?=null)=send(buildJsonObject {
            put("jsonrpc","2.0");put("method","textDocument/publishDiagnostics")
            put("params",buildJsonObject { put("uri",uri);version?.let {put("version",it)};put("diagnostics",buildJsonArray {
                add(buildJsonObject { put("message",message);put("range",buildJsonObject {
                    put("start",buildJsonObject { put("line",0);put("character",0) });put("end",buildJsonObject { put("line",0);put("character",1) })
                }) })
            }) })
        })
        fun send(message:JsonObject) { val bytes=ByteArrayOutputStream();ContentLengthProtocol.writeMessage(bytes,message.toString());out.offer(bytes.toByteArray()) }
    }
    private class QueuedInput:InputStream() {
        val bytes=LinkedBlockingQueue<Int>()
        override fun read()=bytes.take()
        override fun read(buffer:ByteArray,off:Int,len:Int):Int {
            if(len==0)return 0
            val first=read();if(first<0)return -1
            buffer[off]=first.toByte();var size=1
            while(size<len) { val next=bytes.poll()?:break;if(next<0) { bytes.offer(-1);break };buffer[off+size++]=next.toByte() }
            return size
        }
        fun offer(value:ByteArray) { value.forEach { bytes.add(it.toInt() and 255) } }
        override fun close() { bytes.offer(-1) }
    }
    private companion object { suspend fun awaitUntil(check:()->Boolean)=withTimeout(5000) { while(!check())delay(5) } }
}
