package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentWorkspaceSymbolWireTest {
    @Test(timeout = 15000) fun agentAndEditorTransmitSameSearchTextAndMapGuestLocation(): Unit = runBlocking {
        WireFixture().use { f ->
            val output = AgentTools.execute("lsp", workspaceSymbolArgs(), f.fixture.context(f.manager))
            val editor = f.manager.workspaceSymbols("main.py", "NeedleSymbol")
            val queries = f.host.observed.filter { it["method"] == JsonPrimitive("workspace/symbol") }
                .map { it.getValue("params").jsonObject.getValue("query").jsonPrimitive.content }
            assertEquals(listOf("NeedleSymbol", "NeedleSymbol"), queries)
            assertEquals("other.py", editor.single().path)
            assertEquals(7, editor.single().line); assertEquals(3, editor.single().column)
            assertTrue(output.contains("\"path\":\"other.py\""))
            assertTrue(output.contains("NeedleSymbol")); assertFalse(output.contains("file:///workspace"))
        }
    }

    @Test(timeout = 15000) fun unsupportedCapabilityDoesNotDispatchWorkspaceQuery(): Unit = runBlocking {
        WireFixture().use { f ->
            f.host.supportsSymbols = false
            AgentTools.execute("lsp", workspaceSymbolArgs(), f.fixture.context(f.manager))
            assertTrue(f.host.observed.none { it["method"] == JsonPrimitive("workspace/symbol") })
            assertEquals("value = 1\n", f.fixture.root.resolve("main.py").readText())
        }
    }

    @Test(timeout = 15000) fun pendingSearchCancellationDoesNotPublishLateResult(): Unit = runBlocking {
        WireFixture().use { f ->
            f.host.holdSymbols = true
            var published: String? = null
            val search = async { published = AgentTools.execute("lsp", workspaceSymbolArgs(), f.fixture.context(f.manager)) }
            withTimeout(4000) { f.host.symbolEntered.await() }
            search.cancelAndJoin()
            f.host.lastSymbol?.let { f.host.reply(it, f.host.symbols()) }
            assertNull(published); assertTrue(search.isCancelled)
            assertEquals("value = 1\n", f.fixture.root.resolve("main.py").readText())
        }
    }

    private class WireFixture : AutoCloseable {
        val fixture = WorkspaceSymbolToolFixture()
        val parent = SupervisorJob()
        val host = Host(fixture)
        val manager = ProfessionalLspManager(CoroutineScope(parent + Dispatchers.IO), FileRepository(fixture.root),
            fixture.manager, remoteProcessHost = host)
        override fun close() {
            manager.close(); host.close()
            runBlocking { withTimeout(3000) { parent.cancelAndJoin() } }
            fixture.close()
        }
    }

    private class Host(private val fixture: WorkspaceSymbolToolFixture) : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val observed = CopyOnWriteArrayList<JsonObject>()
        val symbolEntered = CompletableDeferred<Unit>()
        val closed = AtomicBoolean()
        var supportsSymbols = true
        var holdSymbols = false
        var lastSymbol: JsonObject? = null
        val stdout = QueuedInput()
        val stderr = QueuedInput()
        private val pending = ByteArrayOutputStream()
        private val stdin = object : OutputStream() {
            @Synchronized override fun write(b: Int) { pending.write(b) }
            @Synchronized override fun write(b: ByteArray, off: Int, len: Int) { pending.write(b, off, len) }
            @Synchronized override fun flush() {
                val message = Json.parseToJsonElement(requireNotNull(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray())))).jsonObject
                pending.reset(); observed += message
                when (message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> reply(message, buildJsonObject { put("capabilities", buildJsonObject {
                        put("textDocumentSync", 2); put("workspaceSymbolProvider", supportsSymbols)
                    }) })
                    "workspace/symbol" -> {
                        lastSymbol = message; symbolEntered.complete(Unit)
                        if (!holdSymbols) reply(message, symbols())
                    }
                    "shutdown" -> reply(message, JsonNull)
                }
            }
        }
        fun symbols(): JsonElement = Json.parseToJsonElement("""[{"name":"NeedleSymbol","kind":12,"location":{"uri":"file:///workspace/other.py","range":{"start":{"line":6,"character":2},"end":{"line":6,"character":14}}}}]""")
        override suspend fun resolveExecutable(command: String) = "/usr/bin/$command"
        override suspend fun pathMapper() = WorkspacePathMapper(fixture.root, "/workspace")
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess =
            object : HostedStdioProcess {
                override val stdin = this@Host.stdin
                override val stdout = this@Host.stdout
                override val stderr = this@Host.stderr
                override val isAlive get() = !closed.get()
                override val exitCode get() = if (closed.get()) 0 else null
                override suspend fun awaitExit(): Int { while (!closed.get()) delay(5); return 0 }
                override fun close() = this@Host.close()
            }
        fun reply(request: JsonObject, result: JsonElement) {
            val message = buildJsonObject { put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result) }
            val bytes = ByteArrayOutputStream(); ContentLengthProtocol.writeMessage(bytes, message.toString())
            stdout.offer(bytes.toByteArray())
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) { stdout.close(); stderr.close() }
        }
    }

    private class QueuedInput : InputStream() {
        private val bytes = LinkedBlockingQueue<Int>()
        override fun read(): Int = bytes.take()
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val first = read(); if (first < 0) return -1
            buffer[off] = first.toByte(); var size = 1
            while (size < len) {
                val value = bytes.poll() ?: break
                if (value < 0) { bytes.offer(-1); break }
                buffer[off + size++] = value.toByte()
            }
            return size
        }
        fun offer(value: ByteArray) { value.forEach { bytes.add(it.toInt() and 255) } }
        override fun close() { bytes.offer(-1) }
    }
}
