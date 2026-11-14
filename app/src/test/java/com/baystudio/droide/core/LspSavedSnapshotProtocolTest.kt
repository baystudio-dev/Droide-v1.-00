package com.baystudio.droide.core

import java.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real framed JSON-RPC bytes, with a host fixture instead of an installed language server. */
class LspSavedSnapshotProtocolTest {
    private class Host : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val sent = ByteArrayOutputStream()
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
                while (true) {
                    val frame = ContentLengthProtocol.readMessage(input) ?: break
                    add(Json.parseToJsonElement(frame).jsonObject)
                }
            }
        }
    }

    @Test(timeout = 10000) fun savedPayloadDoesNotRewindTheIncrementalLiveBaseline() = runBlocking {
        val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rpc = JsonRpcProcess(scope, File("."), listOf("fixture-lsp"), processHost = host)
        try {
            rpc.start()
            val sync = LspDocumentSyncAuthority(rpc, LspTextSyncPolicy(
                openClose = true, change = LspTextSyncPolicy.ChangeKind.INCREMENTAL, save = true, saveIncludeText = true))
            val uri = "file:///workspace/a.kt"
            sync.synchronize(uri, "kotlin", "base")
            sync.saveSnapshot(uri, "kotlin", savedText = "baseB", currentText = "baseBC")
            sync.synchronize(uri, "kotlin", "baseBCD")
            val messages = host.messages()
            assertEquals(listOf("textDocument/didOpen", "textDocument/didChange", "textDocument/didSave", "textDocument/didChange"),
                messages.map { it.getValue("method").jsonPrimitive.content })
            assertEquals("baseB", messages[2].getValue("params").jsonObject.getValue("text").jsonPrimitive.content)
            val finalChange = messages[3].getValue("params").jsonObject.getValue("contentChanges").jsonArray.single().jsonObject
            assertEquals("D", finalChange.getValue("text").jsonPrimitive.content)
            assertEquals(6, finalChange.getValue("range").jsonObject.getValue("start").jsonObject.getValue("character").jsonPrimitive.int)
            assertEquals(3, sync.version(uri))
        } finally { rpc.close(); host.close(); scope.cancel() }
    }

    @Test(timeout = 10000) fun serverCanOptOutOfSavedTextWithoutRetransmittingAnUnchangedLiveBuffer() = runBlocking {
        val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rpc = JsonRpcProcess(scope, File("."), listOf("fixture-lsp"), processHost = host)
        try {
            rpc.start()
            val sync = LspDocumentSyncAuthority(rpc, LspTextSyncPolicy(
                openClose = true, change = LspTextSyncPolicy.ChangeKind.FULL, save = true, saveIncludeText = false))
            val uri = "file:///workspace/a.kt"
            sync.synchronize(uri, "kotlin", "live-C")
            sync.saveSnapshot(uri, "kotlin", savedText = "saved-B", currentText = "live-C")
            sync.synchronize(uri, "kotlin", "live-C")
            val messages = host.messages()
            assertEquals(listOf("textDocument/didOpen", "textDocument/didSave"), messages.map { it.getValue("method").jsonPrimitive.content })
            assertFalse(messages.last().getValue("params").jsonObject.containsKey("text"))
        } finally { rpc.close(); host.close(); scope.cancel() }
    }
}
