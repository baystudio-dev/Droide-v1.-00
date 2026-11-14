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

/** Reconstructed regression fixtures: production manager/transport, no executable server. */
class LspPerlIntegrationTest {
    @Test(timeout=10000) fun passiveOperationsNeverPromptDiscoverStartOrFallback() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.pl");f.manager.didOpen("a.pl","# unsaved")
            assertTrue(runCatching {f.manager.completionItems("a.pl",1,1)}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertTrue(runCatching {f.manager.diagnose("a.pl")}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertTrue(runCatching {f.manager.lsp("hover","a.pl",1,1)}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertEquals(0,f.approvalCalls);assertEquals(0,f.host.discoveries);assertTrue(f.host.peers.isEmpty())
            assertEquals(0,f.fallbackCalls)
        }
    }
    @Test(timeout=10000) fun approvedSessionSendsFullSettingsBeforeOpenWithActualProjectCwd() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("project/cpanfile");f.file("project/a.pl")
            f.manager.didOpen("project/a.pl","# latest unsaved")
            f.manager.enableProjectAnalysis("project/a.pl")
            assertEquals(0,f.host.discoveries)
            assertEquals("fixture",f.manager.completionItems("project/a.pl",1,1).single().label)
            val peer=f.host.peers.single();val methods=peer.observed.mapNotNull {it["method"]?.jsonPrimitive?.content}
            assertTrue(methods.indexOf("initialized") < methods.indexOf("workspace/didChangeConfiguration"))
            assertTrue(methods.indexOf("workspace/didChangeConfiguration") < methods.indexOf("textDocument/didOpen"))
            assertEquals("/workspace/project",f.host.cwd.single())
            assertEquals(listOf("/usr/bin/perlnavigator","--stdio"),peer.argv)
            assertEquals(JsonPrimitive(false),peer.params("initialize")["capabilities"]!!.jsonObject["workspace"]!!.jsonObject["configuration"])
            assertEquals("perl",peer.params("textDocument/didOpen")["textDocument"]!!.jsonObject["languageId"]!!.jsonPrimitive.content)
            assertEquals("# latest unsaved",peer.params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        }
    }
    @Test(timeout=10000) fun revokeStopsHeldInitializeWithoutWaitingForItsResponse() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.pl");f.manager.enableProjectAnalysis("a.pl");f.host.holdInitialize=true
            val pending=async {runCatching {f.manager.completionItems("a.pl",1,1)}}
            awaitUntil {f.host.peers.isNotEmpty()};val old=f.host.peers.single();old.next("initialize")
            f.manager.revokeProjectAnalysis("a.pl")
            awaitUntil {!old.isAlive};withTimeout(2000) {pending.await()}
            f.host.holdInitialize=false;f.manager.enableProjectAnalysis("a.pl")
            assertEquals("fixture",f.manager.completionItems("a.pl",1,1).single().label)
            assertEquals(2,f.host.peers.size);assertTrue(f.host.peers.last().isAlive)
        }
    }
    @Test(timeout=10000) fun revokeCancelsHostPreparationBeforeConstructingAnyProcess() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.pl");f.manager.enableProjectAnalysis("a.pl");f.host.prepareGate=CompletableDeferred()
            val pending=async {runCatching {f.manager.completionItems("a.pl",1,1)}}
            withTimeout(2000) {f.host.prepareEntered.await()}
            f.manager.revokeProjectAnalysis("a.pl")
            awaitUntil {f.host.prepareCanceled};withTimeout(2000) {pending.await()}
            assertTrue(f.host.peers.isEmpty());f.host.prepareGate=null
            f.manager.enableProjectAnalysis("a.pl")
            assertEquals("fixture",f.manager.completionItems("a.pl",1,1).single().label)
        }
    }
    @Test(timeout=10000) fun callerCancellationClosesItsStartupButPreservesConsentForRetry() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.pl");f.manager.enableProjectAnalysis("a.pl");f.host.holdInitialize=true
            val pending=async {f.manager.completionItems("a.pl",1,1)}
            awaitUntil {f.host.peers.isNotEmpty()};val old=f.host.peers.single();old.next("initialize")
            pending.cancelAndJoin();awaitUntil {!old.isAlive}
            assertTrue(f.manager.projectAnalysisStatus("a.pl")!!.enabled)
            f.host.holdInitialize=false
            assertEquals("fixture",f.manager.completionItems("a.pl",1,1).single().label)
            assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun scopedRevokePreservesAnotherApprovedProjectSession() = runBlocking<Unit> {
        Fixture().use { f ->
            for(path in listOf("one/cpanfile","one/a.pl","two/cpanfile","two/a.pl"))f.file(path)
            for(path in listOf("one/a.pl","two/a.pl")) {
                f.manager.enableProjectAnalysis(path);assertEquals("fixture",f.manager.completionItems(path,1,1).single().label)
            }
            val one=f.host.peers[0];val two=f.host.peers[1]
            f.manager.revokeProjectAnalysis("one/a.pl");assertFalse(one.isAlive);assertTrue(two.isAlive)
            assertEquals("fixture",f.manager.completionItems("two/a.pl",1,1).single().label)
            assertEquals(2,f.host.peers.size)
        }
    }
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("droide-perl-").toFile().canonicalFile
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val host=Host(root);var approvalCalls=0;var fallbackCalls=0
        val manager=ProfessionalLspManager(scope,FileRepository(root),object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String):String {fallbackCalls++;error("No CLI execution")}
            override suspend fun lsp(op:String,path:String,line:Int,col:Int):String {fallbackCalls++;error("No CLI execution")}
        },remoteProcessHost=host,approveProjectAnalysis={_,current -> approvalCalls++;current()})
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
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess=error("Builtin Perl must use its project cwd")
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
        @Volatile var failCloseWrite=false
        override val stdin=object:OutputStream() {
            @Synchronized override fun write(b:Int) { pending.write(b) }
            @Synchronized override fun write(b:ByteArray,off:Int,len:Int) { pending.write(b,off,len) }
            @Synchronized override fun flush() {
                val message=Json.parseToJsonElement(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray()))!!).jsonObject
                pending.reset();observed+=message
                when(message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> if(!holdInitialize) reply(message,Json.parseToJsonElement("{\"capabilities\":{\"textDocumentSync\":2,\"completionProvider\":{},\"hoverProvider\":true}}"))
                    "textDocument/completion" -> if(!holdCompletion) reply(message,Json.parseToJsonElement("[{\"label\":\"fixture\"}]"))
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
