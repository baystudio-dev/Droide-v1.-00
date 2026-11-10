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

class LspResultTruthTest {
    @Test(timeout = 10000) fun silentPushServerRemainsPendingWithoutInventingARequest() = runBlocking {
        Fixture().use { f ->
            val result = f.manager.diagnose("main.py")
            assertTrue(result, result.startsWith("Diagnostics pending:"))
            assertTrue(result.contains("version 1"))
            assertFalse(f.host.observed.any { it["method"]?.jsonPrimitive?.content == "textDocument/diagnostic" })
            assertFalse(f.manager.diagnostics.value.containsKey(f.localUri("main.py")))
        }
    }

    @Test(timeout = 10000) fun actualVersionedEmptyPushIsCleanAndClearsPreviousProblems() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py", "value = 1\n")
            f.manager.completionItems("main.py", 1, 1)
            f.host.publish("file:///workspace/main.py", "problem", 1)
            awaitUntil { f.manager.diagnostics.value[f.localUri("main.py")]?.isNotEmpty() == true }
            val problems = f.manager.diagnose("main.py")
            assertTrue(problems, problems.contains("problem") && problems.contains("push; version 1"))
            f.host.empty()
            awaitUntil { f.manager.diagnostics.value[f.localUri("main.py")]?.isEmpty() == true }
            val clean = f.manager.diagnose("./main.py")
            assertTrue(clean, clean.startsWith("OK:") && clean.contains("push; version 1"))
        }
    }

    @Test(timeout = 10000) fun cachedEmptyReportBecomesStaleAfterEdit() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py", "value = 1\n")
            f.manager.completionItems("main.py", 1, 1); f.host.empty()
            awaitUntil { f.manager.diagnostics.value.containsKey(f.localUri("main.py")) }
            f.manager.didChange("main.py", "value = 2\n")
            val result = f.manager.diagnose("main.py")
            assertTrue(result, result.startsWith("Diagnostics stale:"))
            assertTrue(result.contains("current version 2"))
        }
    }

    @Test(timeout = 10000) fun staleEmptyPushCannotClearCurrentProblems() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py", "value = 1\n")
            f.manager.completionItems("main.py", 1, 1)
            f.manager.didChange("main.py", "value = 2\n")
            f.host.publish("file:///workspace/main.py", "current problem", 2)
            awaitUntil { f.manager.diagnostics.value[f.localUri("main.py")]?.isNotEmpty() == true }
            f.host.empty(version = 1); marker(f)
            assertEquals("current problem", f.manager.diagnostics.value[f.localUri("main.py")]?.single()?.message)
        }
    }

    @Test(timeout = 10000) fun actualUnversionedEmptyPushClearsButCannotVerifyCurrentText() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py", "value = 1\n")
            f.manager.completionItems("main.py", 1, 1)
            f.host.publish("file:///workspace/main.py", "old problem", 1)
            awaitUntil { f.manager.diagnostics.value[f.localUri("main.py")]?.isNotEmpty() == true }
            f.manager.didChange("main.py", "value = 2\n")
            f.host.empty(version = null)
            awaitUntil { f.manager.diagnostics.value[f.localUri("main.py")]?.isEmpty() == true }
            val result = f.manager.diagnose("main.py")
            assertTrue(result, result.startsWith("Diagnostics unverified:"))
            assertTrue(result.contains("unversioned push") && result.contains("version 2"))
        }
    }

    @Test(timeout = 10000) fun malformedPushCannotBecomeEmptyOrClearPreviousReport() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py", "value = 1\n")
            f.manager.completionItems("main.py", 1, 1)
            f.host.publish("file:///workspace/main.py", "keep", 1)
            awaitUntil { f.manager.diagnostics.value[f.localUri("main.py")]?.isNotEmpty() == true }
            for (payload in listOf("{}", "{\"diagnostics\":[{}]}", "{\"diagnostics\":null}")) {
                val params = Json.parseToJsonElement(payload).jsonObject + mapOf("uri" to JsonPrimitive("file:///workspace/main.py"))
                f.host.send(buildJsonObject { put("jsonrpc", "2.0"); put("method", "textDocument/publishDiagnostics"); put("params", JsonObject(params)) })
            }
            marker(f)
            assertEquals("keep", f.manager.diagnostics.value[f.localUri("main.py")]?.single()?.message)
        }
    }

    @Test(timeout = 10000) fun fullEmptyPullCarriesRequestVersionAndIdentifier() = runBlocking {
        Fixture().use { f ->
            f.host.pullDiagnostics = true
            val result = f.manager.diagnose("main.py")
            assertTrue(result, result.startsWith("OK:") && result.contains("pull request 1; version 1"))
            assertEquals("fixture", f.host.next("textDocument/diagnostic")["params"]!!.jsonObject["identifier"]!!.jsonPrimitive.content)
            assertTrue(f.manager.diagnostics.value[f.localUri("main.py")]!!.isEmpty())
        }
    }

    @Test(timeout = 10000) fun invalidPullAndOrphanUnchangedNeverProduceClean() = runBlocking {
        for (payload in listOf("null", "{}", "{\"items\":[]}", "{\"kind\":\"full\",\"items\":[{}]}", "{\"kind\":\"unchanged\",\"resultId\":\"orphan\"}")) {
            Fixture().use { f ->
                f.host.pullDiagnostics = true; f.host.diagnosticResult = Json.parseToJsonElement(payload)
                val result = f.manager.diagnose("main.py")
                assertTrue("$payload -> $result", result.startsWith("Diagnostics no result:"))
                assertFalse(f.manager.diagnostics.value.containsKey(f.localUri("main.py")))
            }
        }
    }

    @Test(timeout = 10000) fun unchangedPullRevalidatesOnlyItsSentPreviousResultBasis() = runBlocking {
        Fixture().use { f ->
            f.host.pullDiagnostics = true
            f.manager.didOpen("main.py", "value = 1\n")
            f.host.diagnosticResult = full("problem", "first")
            assertTrue(f.manager.diagnose("main.py").contains("problem"))
            f.host.next("textDocument/diagnostic")
            f.manager.didChange("main.py", "value = 2\n")
            f.host.diagnosticResult = Json.parseToJsonElement("""{"kind":"unchanged","resultId":"second"}""")
            val result = f.manager.diagnose("main.py")
            val params = f.host.next("textDocument/diagnostic")["params"]!!.jsonObject
            assertEquals("first", params["previousResultId"]!!.jsonPrimitive.content)
            assertTrue(result, result.contains("problem") && result.contains("version 2"))
        }
    }

    @Test(timeout = 10000) fun heldPullCannotValidateTextThatChangedWhileAwaiting() = runBlocking {
        Fixture().use { f ->
            f.host.pullDiagnostics = true; f.host.holdDiagnostics = true
            f.manager.didOpen("main.py", "value = 1\n")
            val pending = async { f.manager.diagnose("main.py") }
            val request = f.host.next("textDocument/diagnostic")
            f.manager.didChange("main.py", "value = 2\n")
            f.host.reply(request, f.host.diagnosticResult)
            assertTrue(pending.await().startsWith("Diagnostics stale:"))
            assertFalse(f.manager.diagnostics.value.containsKey(f.localUri("main.py")))
        }
    }

    @Test(timeout = 10000) fun laterSameVersionPullWinsOverEarlierHeldPull() = runBlocking {
        Fixture().use { f ->
            f.host.pullDiagnostics = true; f.host.holdDiagnostics = true
            f.manager.didOpen("main.py", "value = 1\n")
            val first = async { f.manager.diagnose("main.py") }; val firstWire = f.host.next("textDocument/diagnostic")
            val second = async { f.manager.diagnose("main.py") }; val secondWire = f.host.next("textDocument/diagnostic")
            f.host.reply(secondWire, full("newest")); assertTrue(second.await().contains("newest"))
            f.host.reply(firstWire, f.host.diagnosticResult); assertTrue(first.await().startsWith("Diagnostics stale:"))
            assertEquals("newest", f.manager.diagnostics.value[f.localUri("main.py")]?.single()?.message)
        }
    }

    @Test(timeout = 10000) fun cancellationIsPropagatedAndLateEmptyPullIsIgnored() = runBlocking {
        Fixture().use { f ->
            f.host.pullDiagnostics = true; f.host.holdDiagnostics = true
            val pending = async { f.manager.diagnose("main.py") }; val request = f.host.next("textDocument/diagnostic")
            pending.cancelAndJoin()
            val cancelled = f.host.next("$/cancelRequest")
            assertEquals(request["id"], cancelled["params"]!!.jsonObject["id"])
            f.host.reply(request, f.host.diagnosticResult); marker(f)
            assertTrue(pending.isCancelled)
            assertFalse(f.manager.diagnostics.value.containsKey(f.localUri("main.py")))
        }
    }

    @Test(timeout = 10000) fun silentPullTimesOutAsNoResultAndCancelsItsWireRequest() = runBlocking {
        Fixture().use { f ->
            f.host.pullDiagnostics = true; f.host.holdDiagnostics = true
            val result = f.manager.diagnose("main.py")
            assertTrue(result, result.startsWith("Diagnostics no result:") && result.contains("wait limit"))
            f.host.next("$/cancelRequest")
            assertFalse(f.manager.diagnostics.value.containsKey(f.localUri("main.py")))
        }
    }

    @Test(timeout = 10000) fun newerPushCannotBeErasedByAnOlderPendingPull() = runBlocking {
        Fixture().use { f ->
            f.host.pullDiagnostics = true; f.host.holdDiagnostics = true
            f.manager.didOpen("main.py", "value = 1\n")
            val pending = async { f.manager.diagnose("main.py") }; val request = f.host.next("textDocument/diagnostic")
            f.host.publish("file:///workspace/main.py", "new push", 1)
            awaitUntil { f.manager.diagnostics.value[f.localUri("main.py")]?.isNotEmpty() == true }
            f.host.reply(request, f.host.diagnosticResult)
            val result = pending.await()
            assertTrue(result, result.contains("superseded") && result.contains("new push"))
            assertEquals("new push", f.manager.diagnostics.value[f.localUri("main.py")]?.single()?.message)
        }
    }

    @Test(timeout = 10000) fun closeClearsReportProvenanceAndRejectsLateReply() = runBlocking {
        Fixture().use { f ->
            f.host.pullDiagnostics = true; f.host.holdDiagnostics = true
            f.manager.didOpen("main.py", "value = 1\n")
            val pending = async { f.manager.diagnose("main.py") }; val request = f.host.next("textDocument/diagnostic")
            f.manager.didClose("main.py"); f.host.reply(request, f.host.diagnosticResult)
            assertTrue(pending.await().startsWith("Diagnostics stale:"))
            assertTrue(f.manager.diagnostics.value.isEmpty())
        }
    }

    @Test(timeout = 10000) fun missingResultNeverReusesPreviousEmptyVerdictAsFreshClean() = runBlocking {
        Fixture().use { f ->
            f.host.pullDiagnostics = true
            assertTrue(f.manager.diagnose("main.py").startsWith("OK:"))
            f.host.diagnosticResult = JsonNull
            val result = f.manager.diagnose("main.py")
            assertTrue(result, result.startsWith("Diagnostics no result:"))
            assertTrue(f.manager.diagnostics.value[f.localUri("main.py")]!!.isEmpty())
        }
    }

    @Test(timeout = 10000) fun unchangedEmptyPullUsesItsValidatedPriorEmptyBasis() = runBlocking {
        Fixture().use { f ->
            f.host.pullDiagnostics = true
            f.host.diagnosticResult = Json.parseToJsonElement("""{"kind":"full","items":[],"resultId":"empty-basis"}""")
            assertTrue(f.manager.diagnose("main.py").startsWith("OK:"))
            f.host.next("textDocument/diagnostic")
            f.host.diagnosticResult = Json.parseToJsonElement("""{"kind":"unchanged","resultId":"still-empty"}""")
            val result = f.manager.diagnose("main.py")
            assertTrue(result, result.startsWith("OK:") && result.contains("pull request 2"))
            assertEquals("empty-basis", f.host.next("textDocument/diagnostic")["params"]!!.jsonObject["previousResultId"]!!.jsonPrimitive.content)
        }
    }

    @Test(timeout = 10000) fun reusedVersionAfterCloseReopenCannotCertifyAnEmptyPush() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py", "old = 1\n")
            f.manager.completionItems("main.py", 1, 1)
            f.manager.didClose("main.py")
            f.manager.didOpen("main.py", "new = 2\n")
            f.manager.completionItems("main.py", 1, 1)
            // This may be a delayed report from the old open lifetime, which used the same version. Receipt
            // time alone cannot distinguish that report.
            f.host.empty(version = 1)
            awaitUntil { f.manager.diagnostics.value.containsKey(f.localUri("main.py")) }
            val result = f.manager.diagnose("main.py")
            assertTrue(result, result.startsWith("Diagnostics unverified:") && result.contains("reused version 1"))
        }
    }

    @Test(timeout = 10000) fun pushBeyondAllRetiredVersionsCanVerifyTheNewLifetime() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py", "old = 1\n")
            f.manager.completionItems("main.py", 1, 1)
            f.manager.didChange("main.py", "old = 2\n")
            f.manager.didClose("main.py")
            f.manager.didOpen("main.py", "new = 1\n")
            f.manager.didChange("main.py", "new = 2\n")
            f.host.empty(version = 2)
            awaitUntil { f.manager.diagnostics.value.containsKey(f.localUri("main.py")) }
            assertTrue(f.manager.diagnose("main.py").startsWith("Diagnostics unverified:"))
            f.manager.didChange("main.py", "new = 3\n")
            f.host.publish("file:///workspace/main.py", "new lifetime", 3)
            awaitUntil { f.manager.diagnostics.value[f.localUri("main.py")]?.singleOrNull()?.message == "new lifetime" }
            f.host.empty(version = 3)
            awaitUntil { f.manager.diagnostics.value[f.localUri("main.py")]?.isEmpty() == true }
            assertTrue(f.manager.diagnose("main.py").startsWith("OK:"))
        }
    }

    @Test(timeout = 10000) fun correlatedPullCanVerifyAReopenedDocumentAtAReusedVersion() = runBlocking {
        Fixture().use { f ->
            f.host.pullDiagnostics = true
            f.host.diagnosticResult = Json.parseToJsonElement("""{"kind":"full","items":[],"resultId":"old-lifetime-basis"}""")
            f.manager.didOpen("main.py", "old = 1\n")
            assertTrue(f.manager.diagnose("main.py").startsWith("OK:"))
            f.host.next("textDocument/diagnostic")
            f.manager.didClose("main.py")
            f.manager.didOpen("main.py", "new = 2\n")
            assertTrue(f.manager.diagnose("main.py").startsWith("OK:"))
            val params = f.host.next("textDocument/diagnostic")["params"]!!.jsonObject
            assertFalse(params.containsKey("previousResultId"))
        }
    }

    private suspend fun marker(f: Fixture) {
        f.manager.completionItems("other.py", 1, 1)
        f.host.publish("file:///workspace/other.py", "marker", 1)
        awaitUntil { f.manager.diagnostics.value[f.localUri("other.py")]?.singleOrNull()?.message == "marker" }
    }

    private fun full(message: String, resultId: String = "basis") = buildJsonObject {
        put("kind", "full"); put("resultId", resultId)
        put("items", buildJsonArray { add(buildJsonObject {
            put("message", message)
            put("range", buildJsonObject {
                put("start", buildJsonObject { put("line", 0); put("character", 0) })
                put("end", buildJsonObject { put("line", 0); put("character", 1) })
            })
        }) })
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
        val observed = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        var noChange = false
        var pullDiagnostics = false
        var holdDiagnostics = false
        var diagnosticResult: JsonElement = Json.parseToJsonElement("""{"kind":"full","items":[]}""")
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
                observed.add(message)
                frames.trySend(message)
                val method = message["method"]?.jsonPrimitive?.content
                when (method) {
                    "initialize" -> if (!holdInitialize) reply(message, buildJsonObject {
                        put("capabilities", buildJsonObject {
                            if (noChange) put("textDocumentSync", buildJsonObject { put("openClose", true); put("change", 0) })
                            else if (saveSnapshots) put("textDocumentSync", buildJsonObject {
                                put("openClose", true); put("change", 2)
                                put("save", buildJsonObject { put("includeText", true) })
                            }) else put("textDocumentSync", 2)
                            put("completionProvider", buildJsonObject {})
                            if (pullDiagnostics) put("diagnosticProvider", buildJsonObject {
                                put("identifier", "fixture"); put("interFileDependencies", true); put("workspaceDiagnostics", false)
                            })
                            put("documentFormattingProvider", true)
                        })
                    })
                    "initialized" -> {
                        if (publishOnInitialized) publish("file:///workspace/main.py", "initial diagnostic")
                        releaseInitialized?.let { check(it.await(5, TimeUnit.SECONDS)) { "Held initialize fixture was not released" } }
                    }
                    "textDocument/diagnostic" -> if (!holdDiagnostics) reply(message, diagnosticResult)
                    "textDocument/completion" -> {
                        completionRequests.incrementAndGet()
                        reply(message, JsonArray(emptyList()))
                    }
                    "textDocument/formatting" -> reply(message, Json.parseToJsonElement("""[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":5}},"newText":"formatted"}]"""))
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
        fun empty(uri: String = "file:///workspace/main.py", version: Int? = 1) = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "textDocument/publishDiagnostics")
            put("params", buildJsonObject {
                put("uri", uri); version?.let { put("version", it) }; put("diagnostics", JsonArray(emptyList()))
            })
        })
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
        fun reply(request: JsonObject, result: JsonElement) = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request["id"]!!); put("result", result)
        })
        fun send(message: JsonObject) {
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
