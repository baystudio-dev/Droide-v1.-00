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

class LspAsmIntegrationTest {
    @Test(timeout=10000) fun openingAssemblyDoesNotInstallProbeOrAskForTrust() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("main.s"); f.manager.didOpen("main.s", "main: nop")
            assertTrue(runCatching { f.manager.completionItems("main.s", 1, 1) }.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertEquals(0, f.approvalCalls); assertEquals(0, f.host.discoveries)
            assertTrue(f.host.peers.isEmpty()); assertEquals(0, f.fallbackCalls)
        }
    }
    @Test(timeout=10000) fun backgroundStartReplaysCurrentBuffersAndPreservesNativeProtocol() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("main.s"); f.file("b.S")
            f.manager.didOpen("main.s", "main: ret\r\n"); f.manager.didOpen("b.S", "")
            f.host.holdInitialize = true
            assertTrue(withTimeout(1000) { f.manager.enableProjectAnalysis("main.s") }.contains("Preparing"))
            awaitUntil { f.host.peers.isNotEmpty() }; val p = f.host.peers.single(); p.next("initialize")
            repeat(3) { assertTrue(runCatching { f.manager.completionItems("main.s", 1, 1) }.exceptionOrNull() is LspProjectAnalysisNotReadyException) }
            assertEquals(1, f.host.peers.size)
            assertEquals(listOf("/usr/bin/asm-lsp") + LspAsmProfile.command.drop(1), p.argv)
            assertEquals(listOf("/workspace"), f.host.cwd.toList())
            p.finishInitialize(); ready(f, "main.s")
            val opened = p.observed.filter { it["method"] == JsonPrimitive("textDocument/didOpen") }
                .map { it["params"]!!.jsonObject["textDocument"]!!.jsonObject }
            assertEquals(setOf("main: ret\r\n", ""), opened.map { it["text"]!!.jsonPrimitive.content }.toSet())
            assertTrue(opened.all { it["languageId"] == JsonPrimitive("asm") })
            assertEquals("fixture", f.manager.completionItems("main.s", 1, 1).single().label)
            assertEquals(1, f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun saveAndTypingReuseAssemblyWithoutRepeatingStartup() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("main.s"); f.manager.didOpen("main.s", "main: nop")
            f.manager.enableProjectAnalysis("main.s"); ready(f, "main.s")
            val p = f.host.peers.single(); val discoveries = f.host.discoveries
            f.manager.didChange("main.s", "main: ret")
            f.manager.didSaveSnapshot("main.s", "main: ret", "main: ret")
            f.manager.workspaceFilesChanged()
            assertEquals("fixture", f.manager.completionItems("main.s", 1, 1).single().label)
            assertTrue(p.isAlive); assertEquals(1, f.host.peers.size)
            assertEquals(discoveries, f.host.discoveries); assertEquals(1, f.approvalCalls)
            assertFalse(p.observed.any { it["method"] == JsonPrimitive("textDocument/didChange") })
            assertTrue(p.observed.any { it["method"] == JsonPrimitive("textDocument/didClose") })
            assertFalse(p.observed.any { it["method"] == JsonPrimitive("textDocument/didSave") })
            assertEquals(2, p.observed.count { it["method"] == JsonPrimitive("textDocument/didOpen") })
        }
    }
    @Test(timeout=10000) fun revokeDuringPreparationCancelsOwnedAttempt() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("main.s"); f.manager.didOpen("main.s", "main: nop")
            f.host.prepareGate = CompletableDeferred(); f.manager.enableProjectAnalysis("main.s")
            withTimeout(2000) { f.host.prepareEntered.await() }
            f.manager.revokeProjectAnalysis("main.s")
            awaitUntil { f.host.prepareCanceled }
            assertTrue(f.host.peers.isEmpty()); assertFalse(f.manager.projectAnalysisStatus("main.s")!!.enabled)
        }
    }
    @Test(timeout=10000) fun failureNeedsExplicitRetryRatherThanTypingLoop() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("main.s"); f.manager.didOpen("main.s", "main: nop")
            f.manager.enableProjectAnalysis("main.s"); ready(f, "main.s"); f.host.peers.single().close()
            repeat(3) { runCatching { f.manager.completionItems("main.s", 1, 1) } }
            assertEquals(1, f.host.peers.size)
            assertEquals(LspProjectAnalysisReadiness.FAILED, f.manager.projectAnalysisStatus("main.s")!!.readiness)
            f.manager.retryProjectAnalysis("main.s"); ready(f, "main.s")
            assertEquals(2, f.host.peers.size); assertEquals(1, f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun serverCannotExecuteCommandsOrPublishEditsWithoutReview() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("main.s"); f.manager.didOpen("main.s", "main: nop")
            f.manager.enableProjectAnalysis("main.s"); ready(f, "main.s"); val p = f.host.peers.single()
            val response = p.request("workspace/applyEdit", buildJsonObject { put("edit", buildJsonObject {}) })
            assertEquals(JsonPrimitive(false), response["result"]!!.jsonObject["applied"])
            val command = p.request("executeClientCommand", buildJsonObject { put("command", "arbitrary") })
            assertTrue(command.containsKey("error")); assertEquals(0, f.fallbackCalls)
            assertEquals("# inert host fixture", File(f.root, "main.s").readText())
        }
    }
    @Test(timeout=10000) fun unicodeBuffersStayIntactAndPositionRequestsResumeWithAscii() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("main.s"); f.manager.didOpen("main.s", "main: ret")
            f.manager.enableProjectAnalysis("main.s"); ready(f, "main.s"); val p = f.host.peers.single()
            val before = p.observed.count { it["method"] == JsonPrimitive("textDocument/didOpen") }
            repeat(3) { f.manager.didChange("main.s", "main: ret // 文$it") }
            assertEquals(before, p.observed.count { it["method"] == JsonPrimitive("textDocument/didOpen") })
            val requests = p.observed.count { it["method"] == JsonPrimitive("textDocument/completion") }
            assertTrue(f.manager.completionItems("main.s", 1, 1).isEmpty())
            assertEquals(requests, p.observed.count { it["method"] == JsonPrimitive("textDocument/completion") })
            assertEquals(JsonPrimitive("main: ret // 文2"), p.params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"])
            f.manager.didChange("main.s", "main: nop")
            assertEquals("fixture", f.manager.completionItems("main.s", 1, 1).single().label)
            assertEquals(1, f.host.peers.size)
            assertFalse(p.observed.any { it["method"] == JsonPrimitive("textDocument/didChange") })
        }
    }
    @Test(timeout=10000) fun closingOneDocumentKeepsReadySiblingButLastCloseReleasesProcess() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("main.asm").writeText("first"); f.file("b.asm").writeText("second")
            f.manager.didOpen("main.asm", "first"); f.manager.didOpen("b.asm", "second")
            f.manager.enableProjectAnalysis("main.asm"); ready(f, "main.asm")
            val p = f.host.peers.single(); val discoveries = f.host.discoveries
            f.manager.didClose("main.asm")
            assertEquals("fixture", f.manager.completionItems("b.asm",1,1).single().label)
            assertTrue(p.isAlive); assertEquals(1,f.host.peers.size); assertEquals(1,f.approvalCalls)
            assertEquals(discoveries,f.host.discoveries)
            f.manager.didClose("b.asm")
            awaitUntil { !p.isAlive }
        }
    }
    private suspend fun ready(f:Fixture,path:String)=awaitUntil{f.manager.projectAnalysisStatus(path)?.readiness==LspProjectAnalysisReadiness.READY}
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("droide-asm-").toFile().canonicalFile
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
                    "textDocument/definition", "textDocument/references" -> reply(message,Json.parseToJsonElement("""[{"uri":"file:///workspace/b.S","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]"""))
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
        fun capabilities()=Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":{"openClose":true,"change":2,"save":{"includeText":true}},"hoverProvider":true,"documentSymbolProvider":true,"workspaceSymbolProvider":true,"completionProvider":{"triggerCharacters":["/",":"]},"definitionProvider":true,"referencesProvider":true,"codeActionProvider":{"codeActionKinds":["quickfix","refactor"]},"documentFormattingProvider":true}}""")
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
