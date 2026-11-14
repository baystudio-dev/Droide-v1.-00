package com.baystudio.droide.core

import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Content-Length frames over authored in-memory streams; no native process. */
class LspPowerShellDocumentSyncTest {
    private class Host : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        private val sent = ByteArrayOutputStream()
        private val serverWrites = PipedOutputStream()
        private val clientReads = PipedInputStream(serverWrites, 65536)
        @Volatile private var closed = false
        override suspend fun resolveExecutable(command: String): String = command
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) =
            object : HostedStdioProcess {
                override val stdin: OutputStream = sent
                override val stdout: InputStream = clientReads
                override val stderr: InputStream = ByteArrayInputStream(byteArrayOf())
                override val isAlive: Boolean get() = !closed
                override val exitCode: Int? get() = if (closed) 0 else null
                override suspend fun awaitExit(): Int { while (!closed) delay(1); return 0 }
                override fun close() = this@Host.close()
            }
        override fun close() { closed = true; serverWrites.close(); clientReads.close() }
        fun messages(): List<JsonObject> {
            val input = ByteArrayInputStream(sent.toByteArray())
            return buildList {
                while (true) add(Json.parseToJsonElement(ContentLengthProtocol.readMessage(input) ?: break).jsonObject)
            }
        }
    }
    private val policy = LspTextSyncPolicy(true, LspTextSyncPolicy.ChangeKind.FULL, true, true)
    private val uri = "file:///workspace/dirty.ps1"

    @Test(timeout = 10000) fun exactOpenAndForcedReloadAreTwoRealFramesThenOneFullReplacement() = runBlocking {
        val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rpc = JsonRpcProcess(scope, File("."), listOf("authored-peer"), processHost = host)
        try {
            rpc.start()
            val sync = LspDocumentSyncAuthority(rpc, policy, forceInitialFullChange = true)
            val source = "@'\r\n😀\n'@\r\n"
            sync.synchronize(uri, "powershell", source)
            assertTrue(sync.isOpen(uri)); assertEquals(2, sync.version(uri)); assertEquals(source, sync.text(uri))
            sync.synchronize(uri, "powershell", source)
            val initial = host.messages()
            assertEquals(listOf("textDocument/didOpen", "textDocument/didChange"), initial.map { it.getValue("method").jsonPrimitive.content })
            assertEquals(1, initial[0].getValue("params").jsonObject.getValue("textDocument").jsonObject.getValue("version").jsonPrimitive.int)
            assertEquals(source, initial[0].getValue("params").jsonObject.getValue("textDocument").jsonObject.getValue("text").jsonPrimitive.content)
            assertChange(initial[1], 2, source)
            sync.synchronize(uri, "powershell", source + "#changed")
            assertChange(host.messages().last(), 3, source + "#changed")
            assertEquals(3, sync.version(uri)); assertEquals(source + "#changed", sync.text(uri))
        } finally { rpc.close(); host.close(); scope.cancel() }
    }

    @Test(timeout = 10000) fun ledgerWaitsForReloadCommitAndCallerCancellationCannotSplitThePair() = runBlocking {
        val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val rpc = JsonRpcProcess(scope, File("."), listOf("authored-peer"), processHost = host, sessionAdmission = { frame ->
            if (frame?.get("method")?.jsonPrimitive?.contentOrNull == "textDocument/didChange") {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            }
        })
        try {
            rpc.start()
            val sync = LspDocumentSyncAuthority(rpc, policy, forceInitialFullChange = true)
            val task = launch(Dispatchers.Default) { sync.synchronize(uri, "powershell", "same") }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(1, host.messages().size)
            assertFalse(sync.isOpen(uri)); assertNull(sync.version(uri)); assertNull(sync.text(uri))
            task.cancel(CancellationException("UI detached"))
            release.countDown(); task.join()
            assertEquals(2, host.messages().size)
            assertEquals(2, sync.version(uri)); assertEquals("same", sync.text(uri))
            assertTrue(sync.isOpen(uri))
        } finally { release.countDown(); rpc.close(); host.close(); scope.cancel() }
    }

    @Test(timeout = 10000) fun revokedSecondFrameCannotPublishAPartiallyTrackedDocument() = runBlocking {
        val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rpc = JsonRpcProcess(scope, File("."), listOf("authored-peer"), processHost = host, sessionAdmission = { frame ->
            check(frame?.get("method")?.jsonPrimitive?.contentOrNull != "textDocument/didChange") { "Authored revoked owner" }
        })
        try {
            rpc.start()
            val sync = LspDocumentSyncAuthority(rpc, policy, forceInitialFullChange = true)
            assertTrue(runCatching { sync.synchronize(uri, "powershell", "same") }.isFailure)
            assertEquals(1, host.messages().size)
            assertFalse(sync.isOpen(uri)); assertNull(sync.version(uri)); assertNull(sync.text(uri))
            assertFalse(rpc.isRunning)
        } finally { rpc.close(); host.close(); scope.cancel() }
    }

    @Test(timeout = 10000) fun saveKeepsActualPersistedSnapshotAndCloseReopenReloadsAgain() = runBlocking {
        val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rpc = JsonRpcProcess(scope, File("."), listOf("authored-peer"), processHost = host)
        try {
            rpc.start(); val sync = LspDocumentSyncAuthority(rpc, policy, forceInitialFullChange = true)
            sync.synchronize(uri, "powershell", "A")
            sync.saveSnapshot(uri, "powershell", savedText = "A", currentText = "AB")
            assertChange(host.messages()[2], 3, "AB")
            assertEquals("A", host.messages()[3].getValue("params").jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals("AB", sync.text(uri)); assertEquals(3, sync.version(uri))
            assertTrue(sync.close(uri)); assertFalse(sync.close(uri))
            sync.synchronize(uri, "powershell", "AB")
            assertEquals(7, host.messages().size); assertChange(host.messages().last(), 2, "AB")
        } finally { rpc.close(); host.close(); scope.cancel() }
    }

    @Test(timeout = 10000) fun defaultRemainsOneOpenAndRequiresAnExplicitValidForcedPolicy() = runBlocking {
        val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rpc = JsonRpcProcess(scope, File("."), listOf("authored-peer"), processHost = host)
        try {
            rpc.start(); val sync = LspDocumentSyncAuthority(rpc, policy)
            sync.synchronize(uri, "kotlin", "x"); sync.synchronize(uri, "kotlin", "x")
            assertEquals(1, host.messages().size); assertEquals(1, sync.version(uri))
            assertTrue(runCatching { LspDocumentSyncAuthority(rpc, policy.copy(openClose = false), true) }.isFailure)
            assertTrue(runCatching { LspDocumentSyncAuthority(rpc, policy.copy(change = LspTextSyncPolicy.ChangeKind.INCREMENTAL), true) }.isFailure)
        } finally { rpc.close(); host.close(); scope.cancel() }
    }

    private fun assertChange(frame: JsonObject, version: Int, text: String) {
        assertEquals("textDocument/didChange", frame.getValue("method").jsonPrimitive.content)
        val params = frame.getValue("params").jsonObject
        assertEquals(version, params.getValue("textDocument").jsonObject.getValue("version").jsonPrimitive.int)
        val change = params.getValue("contentChanges").jsonArray.single().jsonObject
        assertEquals(setOf("text"), change.keys)
        assertEquals(text, change.getValue("text").jsonPrimitive.content)
    }
}
