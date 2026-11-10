package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolManagerMailboxIntegrationTest {
    @Test(timeout = 15_000) fun lspManagerReceivesInitializedDiagnosticAndClosesItsCollector() = runBlocking {
        val root = Files.createTempDirectory("r29-lsp-mailbox-").toFile()
        root.resolve("main.py").writeText("value = 1\n")
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val host = DebugProtocolFixture(root)
        val peer = TinyPeer(host)
        val fallback = object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath: String) = "fixture fallback"
            override suspend fun lsp(op: String, path: String, line: Int, col: Int) = "fixture fallback"
        }
        val manager = ProfessionalLspManager(scope, FileRepository(root), fallback, remoteProcessHost = host)
        val adapter = scope.launch {
            try {
                for (request in host.requests) when (request["method"]?.jsonPrimitive?.content) {
                    "initialize" -> peer.rpcReply(request, buildJsonObject {
                        putJsonObject("capabilities") {
                            put("textDocumentSync", 2)
                            put("completionProvider", buildJsonObject {})
                        }
                    })
                    "initialized" -> peer.send(buildJsonObject {
                        put("jsonrpc", "2.0"); put("method", "textDocument/publishDiagnostics")
                        putJsonObject("params") {
                            put("uri", "file:///workspace/main.py")
                            putJsonArray("diagnostics") { add(buildJsonObject {
                                put("message", "mailbox diagnostic")
                                putJsonObject("range") {
                                    putJsonObject("start") { put("line", 0); put("character", 0) }
                                    putJsonObject("end") { put("line", 0); put("character", 1) }
                                }
                            }) }
                        }
                    })
                    "textDocument/completion" -> peer.rpcReply(request, JsonArray(emptyList()))
                    "shutdown" -> peer.rpcReply(request, JsonNull)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IOException) { if (!host.closed.get()) throw failure }
        }
        try {
            withTimeout(3_000) { manager.completionItems("main.py", 1, 1) }
            val uri = root.resolve("main.py").toURI().toString()
            withTimeout(2_000) {
                while (manager.diagnostics.value[uri]?.singleOrNull()?.message != "mailbox diagnostic") delay(5)
            }
            manager.close()
            assertTrue(host.closed.get())
            assertEquals("LSP closed", manager.serverStatus("main.py"))
            assertTrue(manager.diagnostics.value.isEmpty())
            assertTrue(owner.isActive)
            withTimeout(2_000) { while (owner.children.any()) delay(5) }
        } finally {
            adapter.cancel(); manager.close(); host.close(); scope.cancel()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test(timeout = 15_000) fun debugManagerCanAwaitThreadsWhileOutputQueuesThenObserveEofTermination() = runBlocking {
        val root = Files.createTempDirectory("r29-debug-mailbox-").toFile()
        root.resolve("main.py").writeText("value = 1\n")
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val host = DebugProtocolFixture(root)
        val peer = TinyPeer(host)
        val terminalCreations = AtomicInteger(0)
        val terminal = TerminalManager(scope, root, linuxLaunchSpecFactory = { _, _ ->
            terminalCreations.incrementAndGet()
            throw AssertionError("The protocol fixture must never prepare a Linux backend")
        }, localSessionFactory = { _, _, _ ->
            terminalCreations.incrementAndGet()
            throw AssertionError("The protocol fixture must never create a terminal session")
        })
        val manager = DebugManager(scope, FileRepository(root), terminal, remoteProcessHost = host)
        val threadsEntered = CompletableDeferred<Unit>()
        val releaseThreads = CompletableDeferred<Unit>()
        val adapter = scope.launch {
            try {
                for (request in host.requests) when (request["command"]?.jsonPrimitive?.content) {
                    "launch" -> {
                        peer.dapReply(request)
                        peer.dapEvent("initialized")
                    }
                    "threads" -> {
                        threadsEntered.complete(Unit)
                        withTimeout(5_000) { releaseThreads.await() }
                        peer.dapReply(request, buildJsonObject {
                            putJsonArray("threads") { add(buildJsonObject { put("id", 1); put("name", "main") }) }
                        })
                    }
                    "stackTrace" -> peer.dapReply(request, buildJsonObject { put("stackFrames", JsonArray(emptyList())) })
                    else -> peer.dapReply(request)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IOException) { if (!host.closed.get()) throw failure }
        }
        try {
            withTimeout(3_000) {
                manager.start(DebugConfiguration("fixture", "launch", listOf("/usr/bin/adapter"), buildJsonObject {}, "test"))
            }
            peer.dapEvent("stopped", buildJsonObject { put("reason", "breakpoint"); put("threadId", 1) })
            withTimeout(2_000) { threadsEntered.await() }
            withTimeout(2_000) {
                repeat(32) { peer.dapEvent("output", buildJsonObject { put("output", "queued-$it\n") }) }
            }
            releaseThreads.complete(Unit)
            withTimeout(2_000) {
                while (!manager.output.value.contains("queued-31")) delay(5)
            }
            assertEquals(DebugState.STOPPED, manager.state.value)
            assertTrue(owner.isActive)
            host.close() // EOF on fake streams, not a real process kill.
            withTimeout(2_000) { while (manager.state.value != DebugState.TERMINATED) delay(5) }
            withTimeout(2_000) { manager.stop(terminateDebuggee = false) }
            assertTrue(owner.isActive)
            assertEquals("No terminal session may be created by this fixture", 0, terminalCreations.get())
        } finally {
            releaseThreads.complete(Unit); adapter.cancel(); manager.close(); host.close(); scope.cancel()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private class TinyPeer(private val host: DebugProtocolFixture) {
        private var totalFrameBytes = 0
        suspend fun rpcReply(request: JsonObject, result: JsonElement) = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request["id"]!!); put("result", result)
        })
        suspend fun dapReply(request: JsonObject, body: JsonObject = buildJsonObject {}) = send(buildJsonObject {
            put("seq", 1); put("type", "response"); put("request_seq", request["seq"]!!)
            put("success", true); put("body", body)
        })
        suspend fun dapEvent(name: String, body: JsonObject = buildJsonObject {}) = send(buildJsonObject {
            put("seq", 1); put("type", "event"); put("event", name); put("body", body)
        })
        suspend fun send(message: JsonObject) {
            val framed = ByteArrayOutputStream()
            ContentLengthProtocol.writeMessage(framed, message.toString())
            assertTrue("Each complete fake frame must be below 1KiB", framed.size() < 1024)
            synchronized(this) {
                totalFrameBytes += framed.size()
                assertTrue("Total fake frames must be below 16KiB", totalFrameBytes < 16 * 1024)
            }
            withTimeout(2_000) { host.send(message) }
        }
    }
}
