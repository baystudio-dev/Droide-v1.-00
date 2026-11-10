package com.baystudio.droide.core
import java.io.*
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspStartupRecoveryTest {
    @Test(timeout=10000) fun environmentHintDuringMissingLookupPreventsStaleBackoffPublication() = runBlocking {
        Fixture().use {f ->
            f.host.mode="missing";f.host.lookupGate=CompletableDeferred()
            val request=launch {f.manager.completionItems("bad.foo",1,1)}
            withTimeout(2000){f.host.lookupEntered.await()}
            f.manager.environmentInvalidated();f.host.lookupGate!!.complete(Unit)
            request.join()
            f.host.lookupGate=null;f.host.mode="ok"
            assertEquals("fixture",f.manager.completionItems("bad.foo",1,1).single().label)
            assertEquals(1,f.host.starts)
        }
    }
    @Test(timeout=10000) fun environmentHintClearsMissingBackoffButDoesNotRestartHealthyOrFailedOwners() = runBlocking {
        Fixture().use {f ->
            f.host.mode="missing";f.manager.completionItems("bad.foo",1,1);assertEquals(0,f.host.starts)
            f.host.mode="ok";f.manager.environmentInvalidated()
            f.manager.completionItems("bad.foo",1,1);assertEquals(1,f.host.starts)
            val healthy=f.host.peers.single()
            f.host.mode="reject";f.manager.completionItems("good.ok",1,1);val starts=f.host.starts
            f.manager.environmentInvalidated();f.manager.completionItems("good.ok",1,1)
            assertEquals(starts,f.host.starts);assertTrue(healthy.isAlive)
        }
    }
    @Test(timeout=15000) fun rejectedInitializeIsVisibleAndScopedRetryPreservesHealthySibling() = runBlocking {
        Fixture().use { f ->
            f.manager.completionItems("good.ok",1,1)
            val healthy=f.host.peers.single()
            f.host.mode="reject"
            f.manager.completionItems("bad.foo",1,1)
            val failure=f.manager.startupFailure("bad.foo")!!
            assertEquals(LspStartupStage.INITIALIZE,failure.stage)
            assertTrue(failure.cause.message.orEmpty().contains("secret-do-not-display"))
            assertFalse(f.manager.serverStatus("bad.foo").contains("secret-do-not-display"))
            assertFalse(f.host.peers.last().isAlive)
            val starts=f.host.peers.size
            f.manager.completionItems("bad.foo",1,1)
            assertEquals(starts,f.host.peers.size)
            f.host.mode="ok"
            f.manager.retryStartup("bad.foo")
            assertNull(f.manager.startupFailure("bad.foo"))
            assertEquals("fixture",f.manager.completionItems("bad.foo",1,1).single().label)
            assertTrue(healthy.isAlive)
            assertEquals("fixture",f.manager.completionItems("good.ok",1,1).single().label)
            assertEquals(starts+1,f.host.peers.size)
        }
    }
    @Test(timeout=10000) fun permissionFailureDoesNotBecomeMissingAndRetryRechecksHost() = runBlocking {
        Fixture().use { f ->
            f.host.mode="permission"
            f.manager.completionItems("bad.foo",1,1)
            assertEquals(LspStartupStage.PROCESS_START,f.manager.startupFailure("bad.foo")!!.stage)
            assertTrue(f.host.peers.isEmpty())
            f.host.mode="ok";f.manager.retryStartup("bad.foo")
            assertNull(f.manager.startupFailure("bad.foo"))
            assertEquals(2,f.host.starts)
        }
    }
    @Test(timeout=10000) fun malformedInitializeIsNotPublishedAsHealthy() = runBlocking {
        Fixture().use { f ->
            f.host.mode="malformed";f.manager.completionItems("bad.foo",1,1)
            assertEquals(LspStartupStage.INITIALIZE,f.manager.startupFailure("bad.foo")!!.stage)
            assertFalse(f.host.peers.single().isAlive)
        }
    }
    @Test(timeout=20000) fun requestTimeoutIsVisibleButCallerCancellationIsNotCached() = runBlocking {
        Fixture().use { f ->
            f.host.mode="hold"
            val job=launch {f.manager.completionItems("good.ok",1,1)}
            withTimeout(2000) {while(f.host.peers.isEmpty()) delay(5)}
            job.cancelAndJoin()
            assertNull(f.manager.startupFailure("good.ok"))
            assertFalse(f.host.peers.single().isAlive)
            f.manager.completionItems("bad.foo",1,1)
            assertTrue(f.manager.startupFailure("bad.foo")!!.timedOut)
            assertFalse(f.host.peers.last().isAlive)
            f.host.mode="ok";f.manager.retryStartup("bad.foo")
            assertNull(f.manager.startupFailure("bad.foo"))
        }
    }
    @Test(timeout=10000) fun closeDuringInitializeCannotPublishStaleFailure() = runBlocking {
        Fixture().use { f ->
            f.host.mode="hold"
            val request=launch { runCatching { f.manager.completionItems("bad.foo",1,1) } }
            withTimeout(2000) {while(f.host.peers.isEmpty()) delay(5)}
            f.manager.close()
            withTimeout(2000) {request.join()}
            assertNull(f.manager.startupFailure("bad.foo"))
            assertFalse(f.host.peers.single().isAlive)
        }
    }
    private class Fixture:AutoCloseable {
        val root=Files.createTempDirectory("lsp-startup-").toFile().canonicalFile
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO);val host=Host(root)
        val manager=ProfessionalLspManager(scope,FileRepository(root),object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String)="fallback"
            override suspend fun lsp(op:String,path:String,line:Int,col:Int)="fallback"
        },remoteProcessHost=host,extraLanguageServers={listOf(
            LanguageServerSpec("fixture-bad",setOf("foo"),setOf("foo"),listOf(listOf("fixture-bad")),source="test"),
            LanguageServerSpec("fixture-good",setOf("ok"),setOf("ok"),listOf(listOf("fixture-good")),source="test"))})
        init {File(root,"bad.foo").writeText("test");File(root,"good.ok").writeText("test")}
        override fun close() {manager.close();host.peers.forEach {it.close()};job.cancel();PathSecurity.deleteTreeNoFollow(root)}
    }
    private class Host(val root:File):StdioProcessHost {
        override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
        var mode="ok";var starts=0;val peers=CopyOnWriteArrayList<Peer>()
        var lookupGate:CompletableDeferred<Unit>?=null
        val lookupEntered=CompletableDeferred<Unit>()
        override suspend fun resolveExecutable(command:String):String? {
            lookupEntered.complete(Unit);lookupGate?.await()
            return if(mode=="missing")null else "/usr/bin/$command"
        }
        override suspend fun pathMapper()=WorkspacePathMapper(root,"/workspace")
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess {
            starts++
            if(mode=="permission") throw SecurityException("secret-permission-detail")
            return Peer(argv,mode).also {peers+=it}
        }
    }
    private class Peer(val argv:List<String>,val mode:String):HostedStdioProcess {
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
                    "initialize" -> when(mode) {
                        "hold" -> Unit
                        "reject" -> send(buildJsonObject { put("jsonrpc", "2.0");put("id",message["id"]!!);put("error",buildJsonObject {put("code",-32002);put("message","secret-do-not-display")}) })
                        "malformed" -> reply(message,JsonPrimitive("not an initialize object"))
                        else -> reply(message,Json.parseToJsonElement("{\"capabilities\":{\"textDocumentSync\":2,\"completionProvider\":{},\"hoverProvider\":true}}"))
                    }
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
}
