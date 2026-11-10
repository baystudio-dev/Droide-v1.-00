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

class LspLemMinxIntegrationTest {
    @Test(timeout=10000) fun passiveEditingNeverPromptsDiscoversOrUsesCliFallback() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.xml");f.manager.didOpen("a.xml","variable \"dirty\" {}")
            assertTrue(runCatching {f.manager.completionItems("a.xml",1,1)}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertTrue(runCatching {f.manager.diagnose("a.xml")}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertEquals(0,f.approvalCalls);assertEquals(0,f.host.discoveries);assertTrue(f.host.peers.isEmpty());assertEquals(0,f.fallbackCalls)
        }
    }
    @Test(timeout=10000) fun persistedMutationRetiresIndexWithoutPromptOrLaunchThenReplaysDirtyBuffers() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.xml");f.file("b.xml");f.manager.didOpen("a.xml","dirty a");f.manager.didOpen("b.xml","dirty b")
            f.manager.enableProjectAnalysis("a.xml");f.manager.completionItems("a.xml",1,1)
            val old=f.host.peers.single();val discoveries=f.host.discoveries
            f.manager.workspaceFilesChanged();assertFalse(old.isAlive);assertEquals(discoveries,f.host.discoveries)
            assertEquals(1,f.host.peers.size);assertTrue(f.manager.projectAnalysisStatus("a.xml")!!.enabled)
            f.manager.completionItems("b.xml",1,1);assertEquals(1,f.approvalCalls)
            val replay=f.host.peers.last().observed.filter {it["method"]==JsonPrimitive("textDocument/didOpen")}
                .map {it["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content}.toSet()
            assertEquals(setOf("dirty a","dirty b"),replay)
        }
    }
    @Test(timeout=10000) fun mutationCancelsHeldInitializePromptlyButKeepsGrantForRetry() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.xml");f.manager.enableProjectAnalysis("a.xml");f.host.holdInitialize=true
            val pending=async {runCatching {f.manager.completionItems("a.xml",1,1)}}
            awaitUntil {f.host.peers.isNotEmpty()};val old=f.host.peers.single();old.next("initialize")
            f.manager.workspaceFilesChanged();awaitUntil {!old.isAlive};withTimeout(2000){pending.await()}
            assertTrue(f.manager.projectAnalysisStatus("a.xml")!!.enabled)
            f.host.holdInitialize=false;assertEquals("fixture",f.manager.completionItems("a.xml",1,1).single().label)
            assertEquals(1,f.approvalCalls);assertTrue(f.host.peers.last().isAlive)
        }
    }
    @Test(timeout=10000) fun mutationCancelsHostPreparationWithoutConstructingAProcess() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.xml");f.manager.enableProjectAnalysis("a.xml");f.host.prepareGate=CompletableDeferred()
            val pending=async {runCatching {f.manager.completionItems("a.xml",1,1)}}
            withTimeout(2000){f.host.prepareEntered.await()};f.manager.workspaceFilesChanged()
            awaitUntil {f.host.prepareCanceled};withTimeout(2000){pending.await()};assertTrue(f.host.peers.isEmpty())
            assertTrue(f.manager.projectAnalysisStatus("a.xml")!!.enabled);f.host.prepareGate=null
            assertEquals("fixture",f.manager.completionItems("a.xml",1,1).single().label);assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun siblingGrantsAreDistinctAndSurviveConservativeIndexRefresh() = runBlocking<Unit> {
        Fixture().use { f ->
            for(p in listOf("one/a.xml","two/a.xml"))f.file(p)
            f.manager.enableProjectAnalysis("one/a.xml");f.manager.completionItems("one/a.xml",1,1)
            assertFalse(f.manager.projectAnalysisStatus("two/a.xml")!!.enabled)
            f.manager.enableProjectAnalysis("two/a.xml");f.manager.completionItems("two/a.xml",1,1)
            f.manager.workspaceFilesChanged();assertTrue(f.host.peers.all {!it.isAlive})
            for(p in listOf("one/a.xml","two/a.xml")) {
                assertTrue(f.manager.projectAnalysisStatus(p)!!.enabled);f.manager.completionItems(p,1,1)
            }
            assertEquals(2,f.approvalCalls);assertEquals(4,f.host.peers.size)
            f.manager.revokeProjectAnalysis("one/a.xml");assertTrue(f.manager.projectAnalysisStatus("two/a.xml")!!.enabled)
            assertTrue(f.host.peers.last().isAlive)
        }
    }
    @Test(timeout=10000) fun missingParentKeepsStatusAndRevokeUsableWithoutAnyNewLaunch() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("module/a.xml");f.manager.didOpen("module/a.xml","dirty retained")
            f.manager.enableProjectAnalysis("module/a.xml");f.manager.completionItems("module/a.xml",1,1)
            val old=f.host.peers.single();val discoveries=f.host.discoveries
            assertTrue(PathSecurity.deleteTreeNoFollow(File(f.root,"module")))
            val status=f.manager.projectAnalysisStatus("module/a.xml")!!
            assertFalse(status.enabled);assertTrue(status.canRevoke)
            assertEquals(File(f.root,"module").path,status.projectPath)
            assertTrue(f.manager.serverStatus("module/a.xml").contains("directory is unavailable"))
            assertTrue(f.manager.enableProjectAnalysis("module/a.xml").contains("unavailable"));assertEquals(1,f.approvalCalls)
            assertTrue(runCatching {f.manager.completionItems("module/a.xml",1,1)}.getOrNull().isNullOrEmpty())
            assertEquals(discoveries,f.host.discoveries);assertEquals(1,f.host.peers.size)
            f.manager.revokeProjectAnalysis("module/a.xml");assertFalse(old.isAlive)
            assertFalse(f.manager.projectAnalysisStatus("module/a.xml")!!.canRevoke)
        }
    }
    @Test(timeout=10000) fun missingParentCloseReleasesOwnerAndRestorationReplaysOnlyCurrentDirtyText() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("module/a.xml");f.manager.didOpen("module/a.xml","dirty retained")
            f.manager.enableProjectAnalysis("module/a.xml");f.manager.completionItems("module/a.xml",1,1)
            val old=f.host.peers.single()
            assertTrue(PathSecurity.deleteTreeNoFollow(File(f.root,"module")))
            f.manager.didClose("module/a.xml");assertFalse(old.isAlive)
            File(f.root,"module").mkdir()
            f.manager.didOpen("module/a.xml","new dirty text")
            assertEquals("fixture",f.manager.completionItems("module/a.xml",1,1).single().label)
            assertEquals(1,f.approvalCalls)
            assertEquals("new dirty text",f.host.peers.last().params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        }
    }
    @Test(timeout=10000) fun missingParentDuringApprovalCannotPublishGrant() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("module/a.xml");f.approvalGate=CompletableDeferred()
            val pending=async {f.manager.enableProjectAnalysis("module/a.xml")}
            awaitUntil {f.approvalCalls==1};assertTrue(PathSecurity.deleteTreeNoFollow(File(f.root,"module")))
            f.approvalGate!!.complete(Unit)
            assertTrue(withTimeout(2000){pending.await()}.contains("remains disabled"))
            File(f.root,"module").mkdir();assertFalse(f.manager.projectAnalysisStatus("module/a.xml")!!.enabled)
            assertEquals(0,f.host.discoveries)
        }
    }
    @Test(timeout=10000) fun missingParentInvalidatesHeldFeatureResultWithoutDiscardingDirtyBuffer() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("module/a.xml");f.manager.didOpen("module/a.xml","dirty retained")
            f.manager.enableProjectAnalysis("module/a.xml");f.manager.completionItems("module/a.xml",1,1)
            val peer=f.host.peers.single();peer.holdCompletion=true
            val pending=async {runCatching {f.manager.completionItems("module/a.xml",1,1)}}
            val request=peer.next("textDocument/completion",after=1)
            assertTrue(PathSecurity.deleteTreeNoFollow(File(f.root,"module")))
            peer.reply(request,Json.parseToJsonElement("[{\"label\":\"stale\"}]"))
            assertTrue(withTimeout(2000){pending.await()}.getOrNull().isNullOrEmpty())
            f.manager.workspaceFilesChanged();File(f.root,"module").mkdir()
            assertEquals("fixture",f.manager.completionItems("module/a.xml",1,1).single().label)
            assertEquals("dirty retained",f.host.peers.last().params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        }
    }
    @Test(timeout=10000) fun exactInitializationUsesDirectoryUriCwdAndFullSettingsBeforeReplay()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("module/a.xml");f.manager.didOpen("module/a.xml","<a>😀</a>");f.manager.enableProjectAnalysis("module/a.xml")
            assertEquals("fixture",f.manager.completionItems("module/a.xml",1,1).single().label)
            val p=f.host.peers.single();assertEquals(listOf("/usr/bin/lemminx"),p.argv);assertEquals(listOf("/workspace/module"),f.host.cwd)
            val init=p.params("initialize");assertEquals(JsonPrimitive("file:///workspace/module/"),init["rootUri"])
            assertEquals(init["rootUri"],init["workspaceFolders"]!!.jsonArray.single().jsonObject["uri"])
            assertEquals(LspLemMinxProfile.initializationOptions(),init["initializationOptions"])
            assertEquals(LspLemMinxProfile.configuration(),p.params("workspace/didChangeConfiguration"))
            val methods=p.observed.map{it["method"]?.jsonPrimitive?.content}
            assertTrue(methods.indexOf("initialized")<methods.indexOf("workspace/didChangeConfiguration"))
            assertTrue(methods.indexOf("workspace/didChangeConfiguration")<methods.indexOf("textDocument/didOpen"))
            assertEquals(JsonPrimitive("xml"),p.params("textDocument/didOpen")["textDocument"]!!.jsonObject["languageId"])
        }
    }

    @Test(timeout=10000) fun explicitOptionsOverrideLemMinxProfileDefaultOnlyForThatRequest()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.dtd");f.manager.didOpen("a.dtd","<!ELEMENT a EMPTY>");f.manager.enableProjectAnalysis("a.dtd")
            f.manager.formatDocument("a.dtd",LspFormattingOptions(5,false))
            val p=f.host.peers.single()
            assertEquals(JsonPrimitive(5),p.params("textDocument/formatting")["options"]!!.jsonObject["tabSize"])
            assertEquals(JsonPrimitive(false),p.params("textDocument/formatting")["options"]!!.jsonObject["insertSpaces"])
            f.manager.formatDocument("a.dtd")
            assertEquals(JsonPrimitive(2),p.params("textDocument/formatting")["options"]!!.jsonObject["tabSize"])
        }
    }
    @Test(timeout=10000) fun dtdUsesItsProtocolIdAndExplicitFormattingUsesProfileWidth()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.dtd");f.manager.didOpen("a.dtd","<!ELEMENT a EMPTY>");f.manager.enableProjectAnalysis("a.dtd")
            assertNotNull(f.manager.formatDocument("a.dtd"));val p=f.host.peers.single()
            assertEquals(JsonPrimitive("dtd"),p.params("textDocument/didOpen")["textDocument"]!!.jsonObject["languageId"])
            assertEquals(JsonPrimitive(2),p.params("textDocument/formatting")["options"]!!.jsonObject["tabSize"])
        }
    }
    @Test(timeout=10000) fun scalarCallbackRequiresRecordedOpenOwnerAndPreservesOrder()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.xml");f.file("b.xml");f.file("sibling/a.xml");f.manager.didOpen("a.xml","<a/>")
            f.manager.enableProjectAnalysis("a.xml");f.manager.completionItems("a.xml",1,1);val p=f.host.peers.single()
            fun q(uri:String)=buildJsonObject {put("items",buildJsonArray {
                add(buildJsonObject {put("section","xml.format.tabSize");put("scopeUri",uri)})
                add(buildJsonObject {put("section","xml.format.insertSpaces");put("scopeUri",uri)})
                add(buildJsonObject {put("section","xml.format.tabSize");put("scopeUri",uri)})
            })}
            assertEquals(Json.parseToJsonElement("[2,true,2]"),p.request("workspace/configuration",q("file:///workspace/a.xml"))["result"])
            for(uri in listOf("file:///workspace/b.xml","file:///workspace/sibling/a.xml","file://foreign/workspace/a.xml","file:///workspace/a.xml?query"))
                assertEquals(Json.parseToJsonElement("[null,null,null]"),p.request("workspace/configuration",q(uri))["result"])
            f.manager.didClose("a.xml")
            assertEquals(0,f.fallbackCalls)
        }
    }
    @Test(timeout=10000) fun unsupportedInboundClientActionsAreExplicitAndCannotApplyEdits()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.xml");f.manager.didOpen("a.xml","<a/>");f.manager.enableProjectAnalysis("a.xml");f.manager.completionItems("a.xml",1,1)
            val p=f.host.peers.single()
            for(method in listOf("xml/executeClientCommand","workspace/codeLens/refresh","client/registerCapability"))
                assertNotNull(p.request(method,buildJsonObject {})["error"])
            assertEquals(Json.parseToJsonElement("{\"success\":false}"),p.request("window/showDocument",buildJsonObject{put("uri","https://example.invalid")})["result"])
            assertEquals(JsonPrimitive(false),p.request("workspace/applyEdit",buildJsonObject {})["result"]!!.jsonObject["applied"])
            assertNotNull(p.request("workspace/configuration",buildJsonObject{put("items",JsonArray(List(65){buildJsonObject{}}))})["error"])
        }
    }
    private fun editAction(command:JsonElement?=null)=buildJsonObject {
        put("title","Fixture action");put("kind","quickfix")
        put("edit",buildJsonObject{put("changes",buildJsonObject{put("file:///workspace/a.xml",buildJsonArray{add(buildJsonObject{
            put("range",buildJsonObject{put("start",buildJsonObject{put("line",0);put("character",0)});put("end",buildJsonObject{put("line",0);put("character",0)})});put("newText","x")
        })})})});command?.let{put("command",it)}
    }
    @Test(timeout=10000) fun initialAndResolvedMixedActionsNeverExposePartialEdits()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.xml");f.manager.didOpen("a.xml","<a/>");f.manager.enableProjectAnalysis("a.xml");f.manager.completionItems("a.xml",1,1)
            val p=f.host.peers.single()
            for(command in listOf(buildJsonObject{put("command","xml.doSomething")},buildJsonObject{put("title",JsonArray(emptyList()));put("command","xml.badTitle")},buildJsonObject{put("title",buildJsonObject{});put("command",JsonArray(emptyList()))},JsonPrimitive("legacy"),JsonPrimitive(true),JsonPrimitive(1),JsonArray(emptyList()))) {
                p.actionResult=buildJsonArray{add(editAction(command))};val action=f.manager.codeActionsAt("a.xml",1,1).single()
                assertNull(action.edit);assertTrue(action.disabledReason!!.contains("unsupported server command"))
                p.actionResult=buildJsonArray{add(JsonObject(editAction() - "edit"))};val plain=f.manager.codeActionsAt("a.xml",1,1).single();assertNull(plain.edit)
                p.resolvedAction=editAction(command);val resolved=f.manager.resolveCodeAction("a.xml",plain)
                assertNull(resolved.edit);assertTrue(resolved.disabledReason!!.contains("unsupported server command"))
            }
            for(command in listOf(null,JsonNull)) {p.actionResult=buildJsonArray{add(editAction(command))};val action=f.manager.codeActionsAt("a.xml",1,1).single();assertNotNull(action.edit);assertNull(action.disabledReason)}
        }
    }
    @Test(timeout=10000) fun persistedGrammarSaveDoesNotCopyZlsSaveDemand()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.xml");f.manager.didOpen("a.xml","<dirty/>");f.manager.enableProjectAnalysis("a.xml");f.manager.completionItems("a.xml",1,1)
            val old=f.host.peers.single();f.manager.workspaceFilesChanged();assertFalse(old.isAlive)
            f.manager.didSaveSnapshot("a.xml","<saved/>","<new/>");assertEquals(1,f.host.peers.size);assertEquals(1,f.approvalCalls)
            f.manager.completionItems("a.xml",1,1);assertEquals(2,f.host.peers.size)
            assertEquals(JsonPrimitive("<new/>"),f.host.peers.last().params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"])
        }
    }
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("droide-xml-").toFile().canonicalFile
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
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess=error("Builtin LemMinX must use its project cwd")
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
                    "initialize" -> if(!holdInitialize) reply(message,Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":2,"documentSymbolProvider":true,"documentHighlightProvider":true,"documentFormattingProvider":true,"documentRangeFormattingProvider":true,"hoverProvider":true,"foldingRangeProvider":true,"definitionProvider":true,"typeDefinitionProvider":true,"referencesProvider":true,"linkedEditingRangeProvider":true,"colorProvider":true,"selectionRangeProvider":true,"workspace":{"workspaceFolders":{"supported":true,"changeNotifications":true}},"documentLinkProvider":{"resolveProvider":true},"completionProvider":{"resolveProvider":true,"triggerCharacters":[".",":","<","\"","=","/","\\","?","'","&","#"]},"codeLensProvider":{},"codeActionProvider":{"resolveProvider":true,"codeActionKinds":["quickfix","source"]},"renameProvider":{"prepareProvider":true}}}"""))
                    "textDocument/completion" -> if(!holdCompletion) reply(message,Json.parseToJsonElement("[{\"label\":\"fixture\"}]"))
                    "workspace/symbol" -> if(!holdSymbols) reply(message,JsonArray(emptyList()))
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
