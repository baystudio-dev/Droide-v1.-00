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

class LspClojureIntegrationTest {
    @Test(timeout=10000) fun passiveBuffersNeverStartUntilExplicitEnableThenWarmupIsIndependentOfFeatureCaller()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");f.manager.didOpen("a.clj","(dirty 😀)")
            assertEquals(0,f.host.discoveries)
            assertTrue(runCatching{f.manager.completionItems("a.clj",1,1)}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            f.host.holdInitialize=true
            assertTrue(withTimeout(1000){f.manager.enableProjectAnalysis("a.clj")}.contains("Preparing"))
            awaitUntil{f.host.peers.isNotEmpty()};val p=f.host.peers.single();p.next("initialize")
            repeat(3){assertTrue(runCatching{withTimeout(25){f.manager.completionItems("a.clj",1,1)}}.exceptionOrNull() is LspProjectAnalysisNotReadyException)}
            assertTrue(p.isAlive);assertEquals(1,f.host.peers.size)
            p.finishInitialize();ready(f,"a.clj")
            assertEquals("fixture",f.manager.completionItems("a.clj",1,1).single().label)
            assertEquals(1,f.approvalCalls);assertEquals(0,f.fallbackCalls)
        }
    }
    @Test(timeout=10000) fun fullSyncCurrentBuffersAndCapabilitiesAreReadyOnlyAfterReplay()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");f.file("b.edn");f.manager.didOpen("a.clj","(a 😀)\r\n");f.manager.didOpen("b.edn","")
            f.manager.enableProjectAnalysis("a.clj");ready(f,"a.clj");val p=f.host.peers.single()
            val init=p.params("initialize");assertEquals("file:///workspace",init["rootUri"]!!.jsonPrimitive.content)
            assertEquals("/workspace",f.host.cwd.single());assertEquals(listOf("/usr/bin/clojure-lsp"),p.argv)
            val caps=init["capabilities"]!!.jsonObject;assertFalse(caps["workspace"]!!.jsonObject.containsKey("didChangeWatchedFiles"))
            assertFalse((caps["window"] as? JsonObject)?.containsKey("showDocument")==true)
            assertEquals(JsonPrimitive(false),caps["workspace"]!!.jsonObject["configuration"])
            assertEquals(Json.parseToJsonElement("""{"text-document-sync-kind":"full"}"""),init["initializationOptions"])
            val opens=p.observed.filter{it["method"]==JsonPrimitive("textDocument/didOpen")}
            assertEquals(setOf("(a 😀)\r\n",""),opens.map{it["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content}.toSet())
            assertTrue(opens.all{it["params"]!!.jsonObject["textDocument"]!!.jsonObject["languageId"]==JsonPrimitive("clojure")})
            f.manager.didChange("a.clj","");assertEquals(Json.parseToJsonElement("[{\"text\":\"\"}]"),p.params("textDocument/didChange")["contentChanges"])
        }
    }
    @Test(timeout=10000) fun closeRetiresNativeDirtyOwnerAndNextDemandReplaysOnlyCurrentSibling()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");f.file("b.clj");f.manager.didOpen("a.clj","discard me");f.manager.didOpen("b.clj","keep me")
            f.manager.enableProjectAnalysis("a.clj");ready(f,"a.clj");val old=f.host.peers.single()
            f.manager.didClose("a.clj");assertFalse(old.isAlive)
            assertTrue(runCatching{f.manager.completionItems("b.clj",1,1)}.exceptionOrNull() is LspProjectAnalysisNotReadyException)
            ready(f,"b.clj");val p=f.host.peers.last();assertNotSame(old,p)
            val texts=p.observed.filter{it["method"]==JsonPrimitive("textDocument/didOpen")}.map{it["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content}
            assertEquals(listOf("keep me"),texts);assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun mutationCancelsPreparationWithoutRevokingGrantAndNoSaveDemandLaunch()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");f.manager.didOpen("a.clj","dirty");f.host.holdInitialize=true;f.manager.enableProjectAnalysis("a.clj")
            awaitUntil{f.host.peers.isNotEmpty()};val old=f.host.peers.single();old.next("initialize")
            f.manager.workspaceFilesChanged();awaitUntil{!old.isAlive};assertTrue(f.manager.projectAnalysisStatus("a.clj")!!.enabled)
            f.manager.didSaveSnapshot("a.clj","saved","newest");assertEquals(1,f.host.peers.size)
            f.host.holdInitialize=false;runCatching{f.manager.completionItems("a.clj",1,1)};ready(f,"a.clj")
            assertEquals(JsonPrimitive("newest"),f.host.peers.last().params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"])
            assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun revokeCancelsSuspendedHostPreparationBeforeProcessConstruction()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");f.manager.didOpen("a.clj","dirty");f.host.prepareGate=CompletableDeferred();f.manager.enableProjectAnalysis("a.clj")
            withTimeout(2000){f.host.prepareEntered.await()};f.manager.revokeProjectAnalysis("a.clj");awaitUntil{f.host.prepareCanceled}
            assertTrue(f.host.peers.isEmpty());assertFalse(f.manager.projectAnalysisStatus("a.clj")!!.enabled)
        }
    }
    @Test(timeout=10000) fun recognizedClasspathFailureIsFailedAndTypingNeverAutomaticallyRetries()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");f.manager.didOpen("a.clj","dirty");f.host.holdInitialize=true;f.manager.enableProjectAnalysis("a.clj")
            awaitUntil{f.host.peers.isNotEmpty()};val p=f.host.peers.single();p.next("initialize")
            p.send(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", "classpath"); put("method", "window/showMessageRequest")
                put("params", buildJsonObject {
                    put("type", 2)
                    put("message", "LSP classpath lookup failed when running `tool secret`. Some features may not work properly if ignored.")
                    put("actions", buildJsonArray {
                        add(buildJsonObject { put("title", "Retry") })
                        add(buildJsonObject { put("title", "Ignore") })
                    })
                })
            })
            awaitUntil{f.manager.projectAnalysisStatus("a.clj")!!.readiness==LspProjectAnalysisReadiness.FAILED};awaitUntil{!p.isAlive}
            repeat(3){assertTrue(runCatching{f.manager.completionItems("a.clj",1,1)}.exceptionOrNull() is LspProjectAnalysisNotReadyException)}
            assertEquals(1,f.host.peers.size);assertFalse(f.manager.projectAnalysisStatus("a.clj")!!.readinessDetail!!.contains("secret"))
            f.host.holdInitialize=false;f.manager.retryProjectAnalysis("a.clj");ready(f,"a.clj");assertEquals(2,f.host.peers.size);assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun earlyDiagnosticDrainDoesNotPublishBeforeReadyAndUnopenedReportsStayIgnored()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");f.file("b.clj");f.manager.didOpen("a.clj","dirty");f.host.holdInitialize=true;f.manager.enableProjectAnalysis("a.clj")
            awaitUntil{f.host.peers.isNotEmpty()};val p=f.host.peers.single();p.next("initialize")
            repeat(180){p.publish("file:///workspace/a.clj","early");delay(1)}
            assertTrue(f.manager.diagnostics.value.isEmpty());assertTrue(p.isAlive)
            p.finishInitialize();ready(f,"a.clj");p.publish("file:///workspace/b.clj","unopened");p.publish("file:///workspace/a.clj","late")
            awaitUntil{f.manager.diagnostics.value.values.flatten().any{it.message=="late"}}
            assertFalse(f.manager.diagnostics.value.values.flatten().any{it.message=="early"||it.message=="unopened"})
            assertTrue(f.manager.diagnose("a.clj").contains("unverified"))
        }
    }
    @Test(timeout=10000) fun popupGuardRejectsSameTextReopenAndMutationWithoutChangingOlderItemDefaults()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");f.manager.didOpen("a.clj","same");f.manager.enableProjectAnalysis("a.clj");ready(f,"a.clj")
            val first=f.manager.completionItems("a.clj",1,1).single().applicationGuard!!;assertEquals("same",first.capturedText);assertTrue(first.isCurrent())
            f.manager.didClose("a.clj");f.manager.didOpen("a.clj","same");assertFalse(first.isCurrent())
            runCatching{f.manager.completionItems("a.clj",1,1)};ready(f,"a.clj")
            val second=f.manager.completionItems("a.clj",1,1).single().applicationGuard!!;f.manager.workspaceFilesChanged();assertFalse(second.isCurrent())
            assertNull(LspCompletionItem("legacy","legacy").applicationGuard)
        }
    }
    @Test(timeout=10000) fun fullFormattingNormalizesOnlyTheReviewedSentinelAgainstCapturedUtf16Text()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");val original="(😀)\r(x)\r\n(y)\n";f.manager.didOpen("a.clj",original);f.manager.enableProjectAnalysis("a.clj");ready(f,"a.clj")
            f.host.peers.single().formatResult=Json.parseToJsonElement("""[{"range":{"start":{"line":0,"character":0},"end":{"line":999999,"character":999999}},"newText":"formatted"}]""")
            val edit=f.manager.formatDocument("a.clj")!!;assertEquals(original,edit.expectedContent["a.clj"])
            assertEquals("formatted",TextEditApplier.apply(original,edit.edits.map{it.range}))
        }
    }
    @Test(timeout=10000) fun observedRootReplacementCancelsHeldAttemptWithoutBorrowingAncestorGrant()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("nested/a.clj");f.manager.didOpen("nested/a.clj","dirty");f.host.holdInitialize=true;f.manager.enableProjectAnalysis("nested/a.clj")
            awaitUntil{f.host.peers.isNotEmpty()};val old=f.host.peers.single();old.next("initialize")
            f.file("nested/deps.edn");assertFalse(f.manager.projectAnalysisStatus("nested/a.clj")!!.enabled);awaitUntil{!old.isAlive}
            assertEquals(1,f.host.peers.size)
        }
    }
    @Test(timeout=10000) fun longClojurePreparationDoesNotHoldAnotherFamilysStartupMutex()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");f.file("b.py");f.manager.didOpen("a.clj","dirty");f.manager.didOpen("b.py","x=1")
            f.host.holdInitialize=true;f.manager.enableProjectAnalysis("a.clj")
            awaitUntil{f.host.peers.isNotEmpty()};val clojure=f.host.peers.single();clojure.next("initialize")
            assertEquals("fixture",withTimeout(2000){f.manager.completionItems("b.py",1,1)}.single().label)
            assertTrue(clojure.isAlive);assertEquals(LspProjectAnalysisReadiness.PREPARING,f.manager.projectAnalysisStatus("a.clj")!!.readiness)
            assertEquals(2,f.host.peers.size)
        }
    }
    @Test(timeout=10000) fun heldInitialReplayReportsPreparingWithoutWaitingForDocumentMutex()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");f.manager.didOpen("a.clj","dirty");val gate=CountDownLatch(1);f.host.openGate=gate
            try {
                f.manager.enableProjectAnalysis("a.clj");awaitUntil{f.host.peers.isNotEmpty()}
                f.host.peers.single().next("textDocument/didOpen")
                val failure=runCatching{withTimeout(250){f.manager.completionItems("a.clj",1,1)}}.exceptionOrNull()
                assertTrue("Replay must not block the typed pending result",failure is LspProjectAnalysisNotReadyException)
                assertEquals(LspProjectAnalysisReadiness.PREPARING,(failure as LspProjectAnalysisNotReadyException).readiness)
            } finally {gate.countDown()}
            ready(f,"a.clj")
        }
    }
    @Test(timeout=10000) fun failedOwnerRejectsHeldResponseBeforeDeferredCleanup()=runBlocking<Unit>{
        Fixture().use{f->
            f.file("a.clj");f.manager.didOpen("a.clj","same");f.manager.enableProjectAnalysis("a.clj");ready(f,"a.clj")
            val peer=f.host.peers.single();peer.holdCompletion=true
            val request=async{runCatching{f.manager.completionItems("a.clj",1,1)}};val pending=peer.next("textDocument/completion")
            fun field(name:String)=f.manager.javaClass.getDeclaredField(name).apply{isAccessible=true}
            val scopeField=field("managerScope");val original=scopeField.get(f.manager) as CoroutineScope
            val queued=CopyOnWriteArrayList<Runnable>()
            val paused=object:CoroutineDispatcher(){override fun dispatch(context:CoroutineContext,block:Runnable){queued+=block}}
            scopeField.set(f.manager,CoroutineScope(original.coroutineContext+paused))
            try {
                val owner=(field("clojureWarmups").get(f.manager) as Map<*,*>).values.single()!!
                f.manager.javaClass.getDeclaredMethod("failClojure",owner.javaClass,String::class.java).apply{isAccessible=true}
                    .invoke(f.manager,owner,"Authored safe failure")
                assertEquals(LspProjectAnalysisReadiness.FAILED,f.manager.projectAnalysisStatus("a.clj")!!.readiness)
                assertTrue(peer.isAlive);assertEquals(1,queued.size)
                peer.reply(pending,Json.parseToJsonElement("[{\"label\":\"obsolete\"}]"))
                assertTrue(withTimeout(2000){request.await()}.getOrNull().isNullOrEmpty())
            } finally {scopeField.set(f.manager,original);queued.toList().forEach{it.run()}}
        }
    }
    @Test(timeout=10000) fun formattingWireShouldUseResolvedTabProfile()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.clj")
            File(f.root,".editorconfig").writeText("root=true\n[*.clj]\nindent_style=tab\ntab_width=3\nindent_size=tab")
            val style=CodeStyleAuthority(f.root).resolve("a.clj","clojure","(a)",CodeStyleDefaults(detectIndentation=false))
            assertEquals(3,style.indentSize);assertTrue(style.useTabs)
            f.manager.didOpen("a.clj","(a)");f.manager.enableProjectAnalysis("a.clj");ready(f,"a.clj")
            f.manager.formatDocument("a.clj", LspFormattingOptions.fromCodeStyle(style))
            val options=f.host.peers.single().params("textDocument/formatting").getValue("options").jsonObject
            assertEquals(style.indentSize,options.getValue("tabSize").jsonPrimitive.int)
            assertEquals(!style.useTabs,options.getValue("insertSpaces").jsonPrimitive.boolean)
        }
    }
    @Test(timeout=10000) fun formattingWireShouldUseIndentSizeRatherThanFixedFour()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.clj")
            File(f.root,".editorconfig").writeText("root=true\n[*.clj]\nindent_style=space\ntab_width=8\nindent_size=2")
            val style=CodeStyleAuthority(f.root).resolve("a.clj","clojure","(a)",CodeStyleDefaults(detectIndentation=false))
            assertEquals(2,style.indentSize);assertEquals(8,style.tabWidth)
            f.manager.didOpen("a.clj","(a)");f.manager.enableProjectAnalysis("a.clj");ready(f,"a.clj")
            f.manager.formatDocument("a.clj", LspFormattingOptions.fromCodeStyle(style))
            val options=f.host.peers.single().params("textDocument/formatting").getValue("options").jsonObject
            assertEquals(style.indentSize,options.getValue("tabSize").jsonPrimitive.int)
        }
    }
    @Test(timeout=10000) fun explicitFormattingOptionsDoNotLeakIntoNextPathOnlyRequest()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.clj");f.manager.didOpen("a.clj","(a)");f.manager.enableProjectAnalysis("a.clj");ready(f,"a.clj")
            f.manager.formatDocument("a.clj",LspFormattingOptions(3,false))
            assertEquals(JsonPrimitive(false),f.host.peers.single().params("textDocument/formatting").getValue("options").jsonObject["insertSpaces"])
            f.manager.formatDocument("a.clj")
            val defaults=f.host.peers.single().params("textDocument/formatting").getValue("options").jsonObject
            assertEquals(JsonPrimitive(4),defaults["tabSize"]);assertEquals(JsonPrimitive(true),defaults["insertSpaces"])
        }
    }
    private suspend fun ready(f:Fixture,path:String)=awaitUntil{f.manager.projectAnalysisStatus(path)?.readiness==LspProjectAnalysisReadiness.READY}
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("droide-clojure-").toFile().canonicalFile
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
        override suspend fun resolveExecutable(command:String):String {discoveries++;return "/usr/bin/$command"}
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
                    "textDocument/didOpen" -> openGate?.await()
                    "textDocument/completion" -> if(!holdCompletion) reply(message,Json.parseToJsonElement("[{\"label\":\"fixture\"}]"))
                    "workspace/symbol" -> if(!holdSymbols) reply(message,JsonArray(emptyList()))
                    "textDocument/definition", "textDocument/references" -> reply(message,Json.parseToJsonElement("""[{"uri":"file:///workspace/b.clj","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]"""))
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
        fun capabilities()=Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":{"openClose":true,"change":1,"save":{"includeText":true}},"hoverProvider":true,"documentSymbolProvider":true,"workspaceSymbolProvider":true,"completionProvider":{"triggerCharacters":["/",":"]},"definitionProvider":true,"referencesProvider":true,"codeActionProvider":{"codeActionKinds":["quickfix","refactor"]},"documentFormattingProvider":true}}""")
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
    private companion object { suspend fun awaitUntil(check:()->Boolean)=withTimeout(2000) { while(!check())delay(5) } }
}
