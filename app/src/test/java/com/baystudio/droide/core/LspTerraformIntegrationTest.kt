package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspTerraformIntegrationTest {
    @Test(timeout=10000) fun passiveEditingNeverPromptsDiscoversOrUsesCliFallback() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.tf");f.manager.didOpen("a.tf","variable \"dirty\" {}")
            assertTrue(runCatching {f.manager.completionItems("a.tf",1,1)}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertTrue(runCatching {f.manager.diagnose("a.tf")}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertEquals(0,f.approvalCalls);assertEquals(0,f.host.discoveries);assertTrue(f.host.peers.isEmpty());assertEquals(0,f.fallbackCalls)
        }
    }
    @Test(timeout=10000) fun approvedNativeLaunchUsesModuleCwdStaticOptionsAndNoCliPrerequisite() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("module/a.tf");f.manager.didOpen("module/a.tf","variable \"dirty\" {}")
            f.manager.enableProjectAnalysis("module/a.tf");assertEquals(0,f.host.discoveries)
            assertEquals("fixture",f.manager.completionItems("module/a.tf",1,1).single().label)
            val peer=f.host.peers.single();assertEquals(listOf("/usr/bin/terraform-ls","serve"),peer.argv)
            assertEquals("/workspace/module",f.host.cwd.single());assertEquals(1,f.host.discoveries)
            val init=peer.params("initialize");assertEquals("file:///workspace/module",init["rootUri"]!!.jsonPrimitive.content)
            assertEquals(1,init["workspaceFolders"]!!.jsonArray.size)
            assertEquals(JsonPrimitive("30s"),init["initializationOptions"]!!.jsonObject["terraform"]!!.jsonObject["timeout"])
            assertFalse(peer.observed.any {it["method"]==JsonPrimitive("workspace/didChangeConfiguration")})
            assertFalse(peer.observed.any {it["method"]==JsonPrimitive("workspace/executeCommand")})
            assertEquals("variable \"dirty\" {}",peer.params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            assertTrue(ManagedLanguageServerPlan.forServer("terraform-ls")!!.requiredCommands.isEmpty())
        }
    }
    @Test(timeout=10000) fun moduleAndSameDirectoryVariablesShareGrantWithExactLanguageIds() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("module/a.tf");f.file("module/dev.tfvars");f.manager.enableProjectAnalysis("module/a.tf")
            f.manager.completionItems("module/a.tf",1,1);f.manager.completionItems("module/dev.tfvars",1,1)
            val ids=f.host.peers.single().observed.filter {it["method"]==JsonPrimitive("textDocument/didOpen")}
                .map {it["params"]!!.jsonObject["textDocument"]!!.jsonObject["languageId"]!!.jsonPrimitive.content}
            assertEquals(listOf("terraform","terraform-vars"),ids);assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun persistedMutationRetiresIndexWithoutPromptOrLaunchThenReplaysDirtyBuffers() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.tf");f.file("b.tf");f.manager.didOpen("a.tf","dirty a");f.manager.didOpen("b.tf","dirty b")
            f.manager.enableProjectAnalysis("a.tf");f.manager.completionItems("a.tf",1,1)
            val old=f.host.peers.single();val discoveries=f.host.discoveries
            f.manager.workspaceFilesChanged();assertFalse(old.isAlive);assertEquals(discoveries,f.host.discoveries)
            assertEquals(1,f.host.peers.size);assertTrue(f.manager.projectAnalysisStatus("a.tf")!!.enabled)
            f.manager.completionItems("b.tf",1,1);assertEquals(1,f.approvalCalls)
            val replay=f.host.peers.last().observed.filter {it["method"]==JsonPrimitive("textDocument/didOpen")}
                .map {it["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content}.toSet()
            assertEquals(setOf("dirty a","dirty b"),replay)
        }
    }
    @Test(timeout=10000) fun mutationCancelsHeldInitializePromptlyButKeepsGrantForRetry() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.tf");f.manager.enableProjectAnalysis("a.tf");f.host.holdInitialize=true
            val pending=async {runCatching {f.manager.completionItems("a.tf",1,1)}}
            awaitUntil {f.host.peers.isNotEmpty()};val old=f.host.peers.single();old.next("initialize")
            f.manager.workspaceFilesChanged();awaitUntil {!old.isAlive};withTimeout(2000){pending.await()}
            assertTrue(f.manager.projectAnalysisStatus("a.tf")!!.enabled)
            f.host.holdInitialize=false;assertEquals("fixture",f.manager.completionItems("a.tf",1,1).single().label)
            assertEquals(1,f.approvalCalls);assertTrue(f.host.peers.last().isAlive)
        }
    }
    @Test(timeout=10000) fun mutationCancelsHostPreparationWithoutConstructingAProcess() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.tf");f.manager.enableProjectAnalysis("a.tf");f.host.prepareGate=CompletableDeferred()
            val pending=async {runCatching {f.manager.completionItems("a.tf",1,1)}}
            withTimeout(2000){f.host.prepareEntered.await()};f.manager.workspaceFilesChanged()
            awaitUntil {f.host.prepareCanceled};withTimeout(2000){pending.await()};assertTrue(f.host.peers.isEmpty())
            assertTrue(f.manager.projectAnalysisStatus("a.tf")!!.enabled);f.host.prepareGate=null
            assertEquals("fixture",f.manager.completionItems("a.tf",1,1).single().label);assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun siblingGrantsAreDistinctAndSurviveConservativeIndexRefresh() = runBlocking<Unit> {
        Fixture().use { f ->
            for(p in listOf("one/a.tf","two/a.tf"))f.file(p)
            f.manager.enableProjectAnalysis("one/a.tf");f.manager.completionItems("one/a.tf",1,1)
            assertFalse(f.manager.projectAnalysisStatus("two/a.tf")!!.enabled)
            f.manager.enableProjectAnalysis("two/a.tf");f.manager.completionItems("two/a.tf",1,1)
            f.manager.workspaceFilesChanged();assertTrue(f.host.peers.all {!it.isAlive})
            for(p in listOf("one/a.tf","two/a.tf")) {
                assertTrue(f.manager.projectAnalysisStatus(p)!!.enabled);f.manager.completionItems(p,1,1)
            }
            assertEquals(2,f.approvalCalls);assertEquals(4,f.host.peers.size)
            f.manager.revokeProjectAnalysis("one/a.tf");assertTrue(f.manager.projectAnalysisStatus("two/a.tf")!!.enabled)
            assertTrue(f.host.peers.last().isAlive)
        }
    }
    @Test(timeout=10000) fun heldWorkspaceResultCannotSurviveIndexMutationWithEmptyEditorLedger() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.tf");f.manager.enableProjectAnalysis("a.tf");f.manager.completionItems("a.tf",1,1)
            val old=f.host.peers.single();old.holdSymbols=true
            val pending=async {runCatching {f.manager.workspaceSymbols("a.tf","query")}}
            val request=old.next("workspace/symbol");f.manager.workspaceFilesChanged()
            old.reply(request,Json.parseToJsonElement("[{\"name\":\"stale\",\"kind\":13,\"location\":{\"uri\":\"file:///workspace/a.tf\",\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":1}}}}]"))
            assertTrue(withTimeout(2000){pending.await()}.getOrNull().isNullOrEmpty())
        }
    }
    @Test(timeout=10000) fun explicitFormattingFailureDoesNotTriggerFallbackOrValidateClaims() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.tf");f.manager.enableProjectAnalysis("a.tf")
            f.manager.didOpen("a.tf","dirty text")
            assertNull(f.manager.formatDocument("a.tf"))
            assertEquals(0,f.fallbackCalls)
            assertFalse(f.host.peers.single().observed.any {it["method"]==JsonPrimitive("workspace/executeCommand")})
            assertTrue(f.manager.projectAnalysisStatus("a.tf")!!.capabilityNote!!.contains("do not prove CLI validation"))
        }
    }
    @Test(timeout=10000) fun missingParentKeepsStatusAndRevokeUsableWithoutAnyNewLaunch() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("module/a.tf");f.manager.didOpen("module/a.tf","dirty retained")
            f.manager.enableProjectAnalysis("module/a.tf");f.manager.completionItems("module/a.tf",1,1)
            val old=f.host.peers.single();val discoveries=f.host.discoveries
            assertTrue(PathSecurity.deleteTreeNoFollow(File(f.root,"module")))
            val status=f.manager.projectAnalysisStatus("module/a.tf")!!
            assertFalse(status.enabled);assertTrue(status.canRevoke)
            assertEquals(File(f.root,"module").path,status.projectPath)
            assertTrue(f.manager.serverStatus("module/a.tf").contains("directory is unavailable"))
            assertTrue(f.manager.enableProjectAnalysis("module/a.tf").contains("unavailable"));assertEquals(1,f.approvalCalls)
            assertTrue(runCatching {f.manager.completionItems("module/a.tf",1,1)}.getOrNull().isNullOrEmpty())
            assertEquals(discoveries,f.host.discoveries);assertEquals(1,f.host.peers.size)
            f.manager.revokeProjectAnalysis("module/a.tf");assertFalse(old.isAlive)
            assertFalse(f.manager.projectAnalysisStatus("module/a.tf")!!.canRevoke)
        }
    }
    @Test(timeout=10000) fun missingParentCloseReleasesOwnerAndRestorationReplaysOnlyCurrentDirtyText() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("module/a.tf");f.manager.didOpen("module/a.tf","dirty retained")
            f.manager.enableProjectAnalysis("module/a.tf");f.manager.completionItems("module/a.tf",1,1)
            val old=f.host.peers.single()
            assertTrue(PathSecurity.deleteTreeNoFollow(File(f.root,"module")))
            f.manager.didClose("module/a.tf");assertFalse(old.isAlive)
            File(f.root,"module").mkdir()
            f.manager.didOpen("module/a.tf","new dirty text")
            assertEquals("fixture",f.manager.completionItems("module/a.tf",1,1).single().label)
            assertEquals(1,f.approvalCalls)
            assertEquals("new dirty text",f.host.peers.last().params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        }
    }
    @Test(timeout=10000) fun missingParentDuringApprovalCannotPublishGrant() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("module/a.tf");f.approvalGate=CompletableDeferred()
            val pending=async {f.manager.enableProjectAnalysis("module/a.tf")}
            awaitUntil {f.approvalCalls==1};assertTrue(PathSecurity.deleteTreeNoFollow(File(f.root,"module")))
            f.approvalGate!!.complete(Unit)
            assertTrue(withTimeout(2000){pending.await()}.contains("remains disabled"))
            File(f.root,"module").mkdir();assertFalse(f.manager.projectAnalysisStatus("module/a.tf")!!.enabled)
            assertEquals(0,f.host.discoveries)
        }
    }
    @Test(timeout=10000) fun missingParentInvalidatesHeldFeatureResultWithoutDiscardingDirtyBuffer() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("module/a.tf");f.manager.didOpen("module/a.tf","dirty retained")
            f.manager.enableProjectAnalysis("module/a.tf");f.manager.completionItems("module/a.tf",1,1)
            val peer=f.host.peers.single();peer.holdCompletion=true
            val pending=async {runCatching {f.manager.completionItems("module/a.tf",1,1)}}
            val request=peer.next("textDocument/completion",after=1)
            assertTrue(PathSecurity.deleteTreeNoFollow(File(f.root,"module")))
            peer.reply(request,Json.parseToJsonElement("[{\"label\":\"stale\"}]"))
            assertTrue(withTimeout(2000){pending.await()}.getOrNull().isNullOrEmpty())
            f.manager.workspaceFilesChanged();File(f.root,"module").mkdir()
            assertEquals("fixture",f.manager.completionItems("module/a.tf",1,1).single().label)
            assertEquals("dirty retained",f.host.peers.last().params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        }
    }
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("droide-terraform-").toFile().canonicalFile
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
        val prepareEntered=CompletableDeferred<Unit>();@Volatile var prepareCanceled=false
        override suspend fun resolveExecutable(command:String):String {discoveries++;return "/usr/bin/$command"}
        override suspend fun pathMapper()=WorkspacePathMapper(root,"/workspace")
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess=error("Builtin Terraform must use its project cwd")
        override suspend fun startInWorkspace(argv:List<String>,environment:Map<String,String>,workspaceDirectory:String):HostedStdioProcess {
            prepareEntered.complete(Unit)
            try {prepareGate?.await()} catch(cancel:CancellationException) {prepareCanceled=true;throw cancel}
            cwd+=workspaceDirectory
            return Peer(argv,holdInitialize).also {peers+=it}
        }
    }
    private class Peer(val argv:List<String>,val holdInitialize:Boolean):HostedStdioProcess {
        val observed=CopyOnWriteArrayList<JsonObject>()
        private val alive=AtomicBoolean(true)
        private val out=QueuedInput();private val err=QueuedInput();private val pending=ByteArrayOutputStream()
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
                    "initialize" -> if(!holdInitialize) reply(message,Json.parseToJsonElement("{\"capabilities\":{\"textDocumentSync\":2,\"completionProvider\":{},\"hoverProvider\":true,\"workspaceSymbolProvider\":true,\"documentFormattingProvider\":true}}"))
                    "textDocument/completion" -> if(!holdCompletion) reply(message,Json.parseToJsonElement("[{\"label\":\"fixture\"}]"))
                    "workspace/symbol" -> if(!holdSymbols) reply(message,JsonArray(emptyList()))
                    "textDocument/formatting" -> send(buildJsonObject { put("jsonrpc","2.0");put("id",message["id"]!!);put("error",buildJsonObject {put("code",-32603);put("message","Optional Terraform CLI unavailable")}) })
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
        fun params(method:String)=observed.last { it["method"]?.jsonPrimitive?.content==method }["params"]!!.jsonObject
        suspend fun next(method:String,after:Int=0):JsonObject=withTimeout(2000) {
            while(true) { val matches=observed.filter { it["method"]?.jsonPrimitive?.content==method };if(matches.size>after)return@withTimeout matches[after];delay(5) }
            error("unreachable")
        }
        fun reply(request:JsonObject,result:JsonElement)=send(buildJsonObject { put("jsonrpc","2.0");put("id",request["id"]!!);put("result",result) })
        fun publish(uri:String,message:String,version:Int)=send(buildJsonObject {
            put("jsonrpc","2.0");put("method","textDocument/publishDiagnostics")
            put("params",buildJsonObject { put("uri",uri);put("version",version);put("diagnostics",buildJsonArray {
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
    private companion object { suspend fun awaitUntil(check:()->Boolean)=withTimeout(2000) { while(!check())delay(5) } }
}
