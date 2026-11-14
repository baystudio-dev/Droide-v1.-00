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

/** Make regression fixtures: production manager/transport, no executable server. */
class LspMakeIntegrationTest {
    @Test(timeout=10000) fun passiveEditingNeverPromptsDiscoversOrUsesCliFallback() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.mk");f.manager.didOpen("a.mk","variable \"dirty\" {}")
            assertTrue(runCatching {f.manager.completionItems("a.mk",1,1)}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertTrue(runCatching {f.manager.diagnose("a.mk")}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertEquals(0,f.approvalCalls);assertEquals(0,f.host.discoveries);assertTrue(f.host.peers.isEmpty());assertEquals(0,f.fallbackCalls)
        }
    }
    @Test(timeout=10000) fun persistedMutationRetiresIndexWithoutPromptOrLaunchThenReplaysDirtyBuffers() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.mk");f.file("b.mk");f.manager.didOpen("a.mk","dirty a");f.manager.didOpen("b.mk","dirty b")
            f.manager.enableProjectAnalysis("a.mk");f.manager.completionItems("a.mk",1,1)
            val old=f.host.peers.single();val discoveries=f.host.discoveries
            f.manager.workspaceFilesChanged();assertFalse(old.isAlive);assertEquals(discoveries,f.host.discoveries)
            assertEquals(1,f.host.peers.size);assertTrue(f.manager.projectAnalysisStatus("a.mk")!!.enabled)
            f.manager.completionItems("b.mk",1,1);assertEquals(1,f.approvalCalls)
            val replay=f.host.peers.last().observed.filter {it["method"]==JsonPrimitive("textDocument/didOpen")}
                .map {it["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content}.toSet()
            assertEquals(setOf("dirty a","dirty b"),replay)
        }
    }
    @Test(timeout=10000) fun mutationCancelsHeldInitializePromptlyButKeepsGrantForRetry() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.mk");f.manager.enableProjectAnalysis("a.mk");f.host.holdInitialize=true
            val pending=async {runCatching {f.manager.completionItems("a.mk",1,1)}}
            awaitUntil {f.host.peers.isNotEmpty()};val old=f.host.peers.single();old.next("initialize")
            f.manager.workspaceFilesChanged();awaitUntil {!old.isAlive};withTimeout(2000){pending.await()}
            assertTrue(f.manager.projectAnalysisStatus("a.mk")!!.enabled)
            f.host.holdInitialize=false;assertEquals("fixture",f.manager.completionItems("a.mk",1,1).single().label)
            assertEquals(1,f.approvalCalls);assertTrue(f.host.peers.last().isAlive)
        }
    }
    @Test(timeout=10000) fun mutationCancelsHostPreparationWithoutConstructingAProcess() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.mk");f.manager.enableProjectAnalysis("a.mk");f.host.prepareGate=CompletableDeferred()
            val pending=async {runCatching {f.manager.completionItems("a.mk",1,1)}}
            withTimeout(2000){f.host.prepareEntered.await()};f.manager.workspaceFilesChanged()
            awaitUntil {f.host.prepareCanceled};withTimeout(2000){pending.await()};assertTrue(f.host.peers.isEmpty())
            assertTrue(f.manager.projectAnalysisStatus("a.mk")!!.enabled);f.host.prepareGate=null
            assertEquals("fixture",f.manager.completionItems("a.mk",1,1).single().label);assertEquals(1,f.approvalCalls)
        }
    }

    @Test(timeout=10000) fun exactNativeStartupUsesProjectCwdNoArgumentsAndFullSyncWithoutSettings()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("nested/Makefile");f.manager.didOpen("nested/Makefile","all: 😀\r\n\techo x\r\n")
            f.manager.enableProjectAnalysis("nested/Makefile");f.manager.completionItems("nested/Makefile",1,1)
            val p=f.host.peers.single();assertEquals(listOf("/usr/bin/make-ls"),p.argv)
            assertEquals("/workspace",f.host.cwd.single())
            val init=p.params("initialize");assertEquals("file:///workspace",init["rootUri"]!!.jsonPrimitive.content)
            assertEquals(JsonArray(listOf(JsonPrimitive("utf-16"))),init["capabilities"]!!.jsonObject["general"]!!.jsonObject["positionEncodings"])
            assertEquals(JsonPrimitive(false),init["capabilities"]!!.jsonObject["workspace"]!!.jsonObject["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
            assertEquals(buildJsonObject{},init["initializationOptions"])
            assertFalse(p.observed.any{it["method"]==JsonPrimitive("workspace/didChangeConfiguration")})
            assertEquals(JsonPrimitive("makefile"),p.params("textDocument/didOpen")["textDocument"]!!.jsonObject["languageId"])
            f.manager.didChange("nested/Makefile","")
            val change=p.params("textDocument/didChange")["contentChanges"]!!.jsonArray
            assertEquals(Json.parseToJsonElement("[{\"text\":\"\"}]"),change)
            f.manager.didSaveSnapshot("nested/Makefile","","")
            assertFalse(p.params("textDocument/didSave").containsKey("text"))
        }
    }
    @Test(timeout=10000) fun defectiveProvidersAreMaskedBeforeRequestsWhileNavigationRemainsNative()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.mk");f.file("b.mk");f.manager.didOpen("a.mk","all: other\n");f.manager.didOpen("b.mk","other:\n")
            f.manager.enableProjectAnalysis("a.mk");f.manager.completionItems("a.mk",1,1);val p=f.host.peers.single()
            assertTrue(f.manager.lsp("hover","a.mk",1,1).contains("upstream"))
            assertTrue(f.manager.symbolItems("a.mk").isEmpty());assertNull(f.manager.formatDocument("a.mk"))
            for(method in listOf("textDocument/hover","textDocument/documentSymbol","textDocument/formatting"))
                assertFalse(p.observed.any{it["method"]==JsonPrimitive(method)})
            assertEquals("b.mk",f.manager.locationItems("goToDefinition","a.mk",1,1).single().path)
            assertEquals("b.mk",f.manager.locationItems("findReferences","a.mk",1,1).single().path)
            assertTrue(f.manager.serverStatus("a.mk").contains("formatting unavailable"))
            assertTrue(f.manager.projectAnalysisStatus("a.mk")!!.capabilityNote!!.contains("upstream correctness defects"))
        }
    }
    @Test(timeout=10000) fun selectedDiagnosticActionKeepsEditButUnexpectedCommandsNeverExposePartialEdits()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.mk");f.manager.didOpen("a.mk","all:\n");f.manager.enableProjectAnalysis("a.mk");f.manager.completionItems("a.mk",1,1)
            val p=f.host.peers.single()
            fun action(command:JsonElement?=null)=buildJsonObject {
                put("title","Add .PHONY: all");put("kind","quickfix")
                put("edit",buildJsonObject{put("changes",buildJsonObject{put("file:///workspace/a.mk",buildJsonArray{add(buildJsonObject{
                    put("range",buildJsonObject{put("start",buildJsonObject{put("line",0);put("character",0)});put("end",buildJsonObject{put("line",0);put("character",0)})});put("newText",".PHONY: all\n")
                })})})});command?.let{put("command",it)}
            }
            val diagnostic=LspDiagnostic(1,1,1,4,4,"all looks like a phony target; consider adding .PHONY: all","make-ls")
            p.actionResult=buildJsonArray{add(action())};val offered=f.manager.codeActions("a.mk",diagnostic).single()
            assertNotNull(offered.edit);assertNull(offered.disabledReason)
            assertEquals(JsonPrimitive("make-ls"),p.params("textDocument/codeAction")["context"]!!.jsonObject["diagnostics"]!!.jsonArray.single().jsonObject["source"])
            assertEquals(JsonPrimitive(diagnostic.message),p.params("textDocument/codeAction")["context"]!!.jsonObject["diagnostics"]!!.jsonArray.single().jsonObject["message"])
            for(command in listOf(buildJsonObject{put("title",JsonArray(emptyList()));put("command","unsafe")},JsonPrimitive("legacy"),JsonPrimitive(true),JsonArray(emptyList()))) {
                p.actionResult=buildJsonArray{add(action(command))};val blocked=f.manager.codeActions("a.mk",diagnostic).single()
                assertNull(blocked.edit);assertTrue(blocked.disabledReason!!.contains("unsupported server command"))
            }
            assertFalse(p.observed.any{it["method"]==JsonPrimitive("workspace/executeCommand")})
        }
    }
    @Test(timeout=10000) fun unversionedDiagnosticReportNeverCertifiesCurrentMakeValidity()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.mk");f.manager.didOpen("a.mk","all:\n");f.manager.enableProjectAnalysis("a.mk");f.manager.completionItems("a.mk",1,1)
            f.host.peers.single().publish("file:///workspace/a.mk","heuristic")
            awaitUntil{f.manager.diagnostics.value.values.flatten().any{it.message=="heuristic"}}
            val report=f.manager.diagnose("a.mk");assertTrue(report.contains("unverified"));assertTrue(report.contains("CRLF continuation parsing is partial"))
        }
    }
    @Test(timeout=10000) fun typedNestedProjectsHaveDistinctGrantsAndRecordedRevokePreservesSibling()=runBlocking<Unit> {
        Fixture().use {f->
            for(dir in listOf("one","two")){f.file("$dir/a.mk");File(f.root,"$dir/.git").mkdir()}
            f.manager.enableProjectAnalysis("one/a.mk");f.manager.completionItems("one/a.mk",1,1)
            assertFalse(f.manager.projectAnalysisStatus("two/a.mk")!!.enabled)
            f.manager.enableProjectAnalysis("two/a.mk");f.manager.completionItems("two/a.mk",1,1)
            val sibling=f.host.peers.last();f.manager.revokeProjectAnalysis("one/a.mk")
            assertFalse(f.host.peers.first().isAlive);assertTrue(sibling.isAlive);assertTrue(f.manager.projectAnalysisStatus("two/a.mk")!!.enabled)
        }
    }
    @Test(timeout=10000) fun missingParentCannotBorrowApprovedWorkspaceAndRestorationKeepsDirtyText()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.mk");f.file("nested/b.mk");f.manager.didOpen("nested/b.mk","dirty 😀")
            f.manager.enableProjectAnalysis("a.mk");f.manager.completionItems("a.mk",1,1)
            f.manager.completionItems("nested/b.mk",1,1);val discoveries=f.host.discoveries
            assertTrue(PathSecurity.deleteTreeNoFollow(File(f.root,"nested")))
            val status=f.manager.projectAnalysisStatus("nested/b.mk")!!;assertFalse(status.enabled);assertTrue(status.capabilityNote!!.contains("unavailable"))
            assertTrue(runCatching{f.manager.completionItems("nested/b.mk",1,1)}.isFailure);assertEquals(discoveries,f.host.discoveries)
            File(f.root,"nested").mkdir();f.manager.completionItems("nested/b.mk",1,1)
            assertEquals(1,f.approvalCalls)
            val opens=f.host.peers.last().observed.filter{it["method"]==JsonPrimitive("textDocument/didOpen")}
            assertTrue(opens.any{it["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]==JsonPrimitive("dirty 😀")})
        }
    }
    @Test(timeout=10000) fun persistedSaveDoesNotStartNewIndexUntilSupportedDemand()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.mk");f.manager.didOpen("a.mk","dirty");f.manager.enableProjectAnalysis("a.mk");f.manager.completionItems("a.mk",1,1)
            val old=f.host.peers.single();f.manager.workspaceFilesChanged();assertFalse(old.isAlive)
            f.manager.didSaveSnapshot("a.mk","saved","newer");assertEquals(1,f.host.peers.size)
            f.manager.completionItems("a.mk",1,1);assertEquals(2,f.host.peers.size);assertEquals(1,f.approvalCalls)
            assertEquals(JsonPrimitive("newer"),f.host.peers.last().params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"])
        }
    }
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("droide-make-").toFile().canonicalFile
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
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess=error("Builtin Make must use its project cwd")
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
                    "initialize" -> if(!holdInitialize) reply(message,Json.parseToJsonElement("""{"capabilities":{"positionEncoding":"utf-16","textDocumentSync":{"openClose":true,"change":1,"save":{"includeText":false}},"hoverProvider":true,"documentSymbolProvider":{},"completionProvider":{"triggerCharacters":["$","("]},"definitionProvider":true,"referencesProvider":true,"codeActionProvider":{"codeActionKinds":["quickfix"]},"documentFormattingProvider":true},"serverInfo":{"name":"make-ls","version":"0.1.0"}}"""))
                    "textDocument/completion" -> if(!holdCompletion) reply(message,Json.parseToJsonElement("[{\"label\":\"fixture\"}]"))
                    "workspace/symbol" -> if(!holdSymbols) reply(message,JsonArray(emptyList()))
                    "textDocument/definition", "textDocument/references" -> reply(message,Json.parseToJsonElement("""[{"uri":"file:///workspace/b.mk","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]"""))
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
    private companion object { suspend fun awaitUntil(check:()->Boolean)=withTimeout(2000) { while(!check())delay(5) } }
}
