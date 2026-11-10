package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production LSP owner and actual Content-Length/JSON-RPC transport. */
class LspManagerLifetimeTest {
    @Test(timeout = 10000) fun savedSnapshotAndIncrementalLiveBufferStaySeparateThroughTheProductionManager() = runBlocking {
        Fixture().use { fixture ->
            fixture.host.saveSnapshots = true
            fixture.manager.didOpen("main.py", "base")
            fixture.manager.completionItems("main.py", 1, 1)
            fixture.host.next("textDocument/didOpen")
            fixture.manager.didChange("main.py", "baseBC")
            fixture.manager.didSaveSnapshot("main.py", "baseB", "baseBC")
            val saved = fixture.host.next("textDocument/didSave").getValue("params").jsonObject
            assertEquals("baseB", saved.getValue("text").jsonPrimitive.content)
            fixture.manager.didChange("main.py", "baseBCD")
            val changed = fixture.host.next("textDocument/didChange").getValue("params").jsonObject
                .getValue("contentChanges").jsonArray.single().jsonObject
            assertEquals("D", changed.getValue("text").jsonPrimitive.content)
            assertEquals(6, changed.getValue("range").jsonObject.getValue("start").jsonObject.getValue("character").jsonPrimitive.int)
            assertEquals(1, fixture.host.starts.get())
        }
    }

    @Test(timeout = 10_000) fun clangdReceivesTheSafeMappedCmakeDatabaseAtTheActualSpawnBoundary() = runBlocking {
        Fixture().use { fixture ->
            fixture.root.resolve("main.cpp").writeText("int main() { return 0; }\n")
            fixture.root.resolve(".droide/build/cmake").mkdirs()
            fixture.root.resolve(".droide/build/cmake/compile_commands.json").writeText("[]")
            fixture.manager.completionItems("main.cpp", 1, 1)
            assertEquals(
                listOf("/usr/bin/clangd", "--background-index", "--compile-commands-dir=/workspace/.droide/build/cmake"),
                fixture.host.launchedArgv,
            )
        }
    }

    @Test(timeout = 10_000) fun dirtyReactSnapshotUsesTheStandardWireIdentity() = runBlocking {
        Fixture().use { fixture ->
            fixture.manager.didOpen("component.tsx", "export const view = <p>dirty</p>;")
            fixture.manager.completionItems("component.tsx", 1, 4)
            val opened = fixture.host.next("textDocument/didOpen")["params"]!!.jsonObject["textDocument"]!!.jsonObject
            assertEquals("typescriptreact", opened["languageId"]!!.jsonPrimitive.content)
            assertEquals("export const view = <p>dirty</p>;", opened["text"]!!.jsonPrimitive.content)
            assertEquals("file:///workspace/component.tsx", opened["uri"]!!.jsonPrimitive.content)
            assertEquals(1, fixture.host.starts.get())
        }
    }

    @Test(timeout = 10_000) fun diagnosticsPublishedImmediatelyAfterInitializedAreNotLost() = runBlocking {
        Fixture().use { fixture ->
            fixture.host.publishOnInitialized = true
            fixture.manager.completionItems("main.py", 1, 1)
            awaitUntil { fixture.manager.diagnostics.value[fixture.localUri("main.py")]?.singleOrNull()?.message == "initial diagnostic" }
            assertEquals(1, fixture.host.starts.get())
        }
    }

    @Test(timeout = 10_000) fun concurrentIntelligenceCannotOvertakeTheInitializedNotification() = runBlocking {
        Fixture().use { fixture ->
            fixture.host.releaseInitialized = CountDownLatch(1)
            try {
                val first = async { fixture.manager.completionItems("main.py", 1, 1) }
                fixture.host.next("initialized")
                val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.manager.completionItems("other.py", 1, 1) }
                assertEquals("Server publication must wait until initialized finishes", 0, fixture.host.completionRequests.get())
                fixture.host.releaseInitialized!!.countDown()
                withTimeout(2_000) { first.await(); second.await() }
                assertEquals(1, fixture.host.starts.get())
                assertEquals(2, fixture.host.completionRequests.get())
            } finally { fixture.host.releaseInitialized!!.countDown() }
        }
    }

    @Test(timeout = 10_000) fun closeDuringInitializeReleasesOwnedTransportAndRejectsFutureSpawns() = runBlocking {
        Fixture().use { fixture ->
            fixture.host.holdInitialize = true
            val request = async { runCatching { fixture.manager.completionItems("main.py", 1, 1) } }
            fixture.host.next("initialize")
            fixture.manager.close()
            assertTrue(withTimeout(2_000) { request.await() }.isFailure)
            assertTrue(fixture.host.closed.get())
            assertEquals("LSP closed", fixture.manager.serverStatus("main.py"))
            assertTrue(runCatching { fixture.manager.completionItems("main.py", 1, 1) }.isFailure)
            assertEquals(1, fixture.host.starts.get())
            assertTrue(fixture.parent.isActive)
            awaitUntil { fixture.parent.children.none() }
        }
    }

    @Test(timeout = 10_000) fun nonCancellableDiscoveryReturningAfterCloseCannotStartAChild() = runBlocking {
        Fixture().use { fixture ->
            fixture.host.discoveryEntered = CompletableDeferred()
            fixture.host.releaseDiscovery = CompletableDeferred()
            val request = async { runCatching { fixture.manager.completionItems("main.py", 1, 1) } }
            withTimeout(2_000) { fixture.host.discoveryEntered!!.await() }
            fixture.manager.close()
            fixture.host.releaseDiscovery!!.complete(Unit)
            assertTrue(withTimeout(2_000) { request.await() }.isFailure)
            assertEquals(0, fixture.host.starts.get())
            assertTrue(fixture.parent.isActive)
            awaitUntil { fixture.parent.children.none() }
        }
    }

    @Test(timeout = 10_000) fun shutdownCancellationStillClosesTheChildAndNotificationOwner() = runBlocking {
        Fixture().use { fixture ->
            fixture.manager.completionItems("main.py", 1, 1)
            fixture.host.holdShutdown = true
            val shutdown = launch { fixture.manager.shutdown() }
            fixture.host.next("shutdown")
            shutdown.cancelAndJoin()
            assertTrue(fixture.host.closed.get())
            assertTrue(fixture.parent.isActive)
            awaitUntil { fixture.parent.children.none() }
        }
    }

    @Test(timeout = 10_000) fun outsideWorkspaceClosedAndStaleDiagnosticsCannotRepopulateState() = runBlocking {
        Fixture().use { fixture ->
            fixture.manager.completionItems("main.py", 1, 1)
            fixture.host.publish("file:///workspace/main.py", "current", version = 1)
            awaitUntil { fixture.manager.diagnostics.value[fixture.localUri("main.py")]?.singleOrNull()?.message == "current" }
            fixture.manager.didChange("main.py", "value = 2\n")
            fixture.host.publish("file:///workspace/main.py", "stale", version = 1)
            fixture.host.publish("file:///outside/secret.py", "outside", version = 2)
            fixture.manager.didClose("main.py")
            fixture.host.publish("file:///workspace/main.py", "late without version")
            fixture.manager.completionItems("other.py", 1, 1)

            fixture.host.publish("file:///workspace/other.py", "marker", version = 1)
            awaitUntil { fixture.manager.diagnostics.value[fixture.localUri("other.py")]?.singleOrNull()?.message == "marker" }
            assertEquals(setOf(fixture.localUri("other.py")), fixture.manager.diagnostics.value.keys)
            fixture.manager.close()
            assertTrue(fixture.manager.diagnostics.value.isEmpty())
        }
    }

    @Test(timeout = 10_000) fun sameIdCommandReplacementRetiresTheOldGenericServer() = runBlocking {
        ConfigurationFixture().use { fixture ->
            fixture.manager.didOpen("main.py", "dirty buffer")
            fixture.manager.completionItems("main.py", 1, 1)
            val old = fixture.children.single()
            assertEquals(listOf("/usr/bin/python-server-old", "--stdio"), old.launchedArgv)
            old.publish("file:///workspace/main.py", "old diagnostic", version = 1)
            awaitUntil { fixture.manager.diagnostics.value.isNotEmpty() }

            fixture.spec = fixture.spec.copy(commands = listOf(listOf("python-server-new", "--stdio")))
            fixture.manager.completionItems("main.py", 1, 1)
            assertEquals("A same-ID executable change must create a new process", 2, fixture.children.size)
            assertTrue("The obsolete process must be released", old.closed.get())
            assertTrue("The obsolete diagnostics must be removed", fixture.manager.diagnostics.value.isEmpty())
            val replacement = fixture.children.last()
            assertEquals(listOf("/usr/bin/python-server-new", "--stdio"), replacement.launchedArgv)
            val opened = replacement.next("textDocument/didOpen")["params"]!!.jsonObject["textDocument"]!!.jsonObject
            assertEquals("dirty buffer", opened["text"]!!.jsonPrimitive.content)
            fixture.manager.completionItems("main.py", 1, 2)
            assertEquals("An unchanged route must reuse the process", 2, fixture.children.size)
        }
    }

    @Test(timeout = 10_000) fun sameIdArgumentReplacementCannotReuseGenericServer() = runBlocking {
        ConfigurationFixture().use { fixture ->
            fixture.manager.completionItems("main.py", 1, 1)
            val old = fixture.children.single()
            fixture.spec = fixture.spec.copy(commands = listOf(listOf("python-server-old", "--stdio", "--new-config")))
            fixture.manager.completionItems("main.py", 1, 1)
            assertEquals(2, fixture.children.size)
            assertTrue(old.closed.get())
            assertEquals(listOf("/usr/bin/python-server-old", "--stdio", "--new-config"), fixture.children.last().launchedArgv)
        }
    }

    private class ConfigurationFixture : AutoCloseable {
        val root = Files.createTempDirectory("droide-lsp-config-").toFile()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.IO)
        val children = java.util.concurrent.CopyOnWriteArrayList<Host>()
        @Volatile var spec = LanguageServerSpec("python-pyright", setOf("python"), setOf("py"),
            listOf(listOf("python-server-old", "--stdio")), source = "test-extension")
        private val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/usr/bin/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, "/workspace")
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                val child = Host(root)
                children += child
                return child.start(argv, environment, resourceLimits)
            }
        }
        val manager = ProfessionalLspManager(scope, FileRepository(root), object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath: String) = "fallback"
            override suspend fun lsp(op: String, path: String, line: Int, col: Int) = "fallback"
        }, remoteProcessHost = host, extraLanguageServers = { listOf(spec) })
        init { root.resolve("main.py").writeText("saved") }
        override fun close() {
            manager.close(); children.forEach { it.close() }; parent.cancel()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("droide-lsp-lifetime-").toFile()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.IO)
        val host = Host(root)
        val manager = ProfessionalLspManager(scope, FileRepository(root), object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath: String) = "fallback"
            override suspend fun lsp(op: String, path: String, line: Int, col: Int) = "fallback"
        }, remoteProcessHost = host)
        init {
            root.resolve("main.py").writeText("value = 1\n")
            root.resolve("other.py").writeText("other = 1\n")
            root.resolve("component.tsx").writeText("export const view = <p>saved</p>;")
        }
        fun localUri(path: String) = root.resolve(path).toURI().toString()
        override fun close() {
            manager.close()
            host.releaseDiscovery?.complete(Unit)
            host.close()
            parent.cancel()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private class Host(private val root: File) : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val starts = AtomicInteger()
        val completionRequests = AtomicInteger()
        val closed = AtomicBoolean()
        var holdInitialize = false
        var holdShutdown = false
        var publishOnInitialized = false
        var saveSnapshots = false
        var discoveryEntered: CompletableDeferred<Unit>? = null
        var releaseDiscovery: CompletableDeferred<Unit>? = null
        var releaseInitialized: CountDownLatch? = null
        var launchedArgv: List<String>? = null
        private val frames = Channel<JsonObject>(Channel.UNLIMITED)
        private val stdout = QueuedInput()
        private val stderr = QueuedInput()
        private val pending = ByteArrayOutputStream()
        private val stdin = object : OutputStream() {
            @Synchronized override fun write(b: Int) { pending.write(b) }
            @Synchronized override fun write(b: ByteArray, off: Int, len: Int) { pending.write(b, off, len) }
            @Synchronized override fun flush() {
                val message = Json.parseToJsonElement(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray()))!!).jsonObject
                pending.reset()
                frames.trySend(message)
                val method = message["method"]?.jsonPrimitive?.content
                when (method) {
                    "initialize" -> if (!holdInitialize) reply(message, buildJsonObject {
                        put("capabilities", buildJsonObject {
                            if (saveSnapshots) put("textDocumentSync", buildJsonObject {
                                put("openClose", true); put("change", 2)
                                put("save", buildJsonObject { put("includeText", true) })
                            }) else put("textDocumentSync", 2)
                            put("completionProvider", buildJsonObject {})
                        })
                    })
                    "initialized" -> {
                        if (publishOnInitialized) publish("file:///workspace/main.py", "initial diagnostic")
                        releaseInitialized?.let { check(it.await(5, TimeUnit.SECONDS)) { "Held initialize fixture was not released" } }
                    }
                    "textDocument/completion" -> {
                        completionRequests.incrementAndGet()
                        reply(message, JsonArray(emptyList()))
                    }
                    "shutdown" -> if (!holdShutdown) reply(message, JsonNull)
                }
            }
        }
        override suspend fun resolveExecutable(command: String): String {
            discoveryEntered?.complete(Unit)
            releaseDiscovery?.let { withContext(NonCancellable) { it.await() } }
            return "/usr/bin/$command"
        }
        override suspend fun pathMapper() = WorkspacePathMapper(root, "/workspace")
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            starts.incrementAndGet()
            launchedArgv = argv.toList()
            return object : HostedStdioProcess {
                override val stdin = this@Host.stdin
                override val stdout = this@Host.stdout
                override val stderr = this@Host.stderr
                override val isAlive get() = !closed.get()
                override val exitCode get() = if (closed.get()) 0 else null
                override suspend fun awaitExit(): Int { while (!closed.get()) delay(5); return 0 }
                override fun close() = this@Host.close()
            }
        }
        suspend fun next(method: String): JsonObject = withTimeout(2_000) {
            var message: JsonObject
            do { message = frames.receive() } while (message["method"]?.jsonPrimitive?.content != method)
            message
        }
        fun publish(uri: String, message: String, version: Int? = null) = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "textDocument/publishDiagnostics")
            put("params", buildJsonObject {
                put("uri", uri)
                version?.let { put("version", it) }
                put("diagnostics", buildJsonArray { add(buildJsonObject {
                    put("message", message)
                    put("range", buildJsonObject {
                        put("start", buildJsonObject { put("line", 0); put("character", 0) })
                        put("end", buildJsonObject { put("line", 0); put("character", 1) })
                    })
                }) })
            })
        })
        private fun reply(request: JsonObject, result: JsonElement) = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request["id"]!!); put("result", result)
        })
        private fun send(message: JsonObject) {
            val bytes = ByteArrayOutputStream()
            ContentLengthProtocol.writeMessage(bytes, message.toString())
            stdout.offer(bytes.toByteArray())
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            stdout.close(); stderr.close(); frames.close()
        }
    }

    private class QueuedInput : InputStream() {
        private val bytes = LinkedBlockingQueue<Int>()
        override fun read(): Int = bytes.take()
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val first = read()
            if (first < 0) return -1
            buffer[off] = first.toByte()
            var size = 1
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

    private companion object {
        suspend fun awaitUntil(predicate: () -> Boolean) = withTimeout(2_000) {
            while (!predicate()) delay(5)
        }
    }
}
