package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class LspInlayHintDocumentEndTest {
    private fun end(text: String, line: Int, column: Int) =
        assertEquals(line to column, LspInlayHintProtocol::class.java
            .getDeclaredMethod("documentEnd", String::class.java)
            .apply { isAccessible = true }
            .invoke(LspInlayHintProtocol, text))

    @Test fun emptyDocument() = end("", 0, 0)
    @Test fun singleLineUsesUtf16Units() = end("a😀z", 0, 4)
    @Test fun lfDocument() = end("one\ntwo", 1, 3)
    @Test fun crlfDocument() = end("one\r\ntwo", 1, 3)
    @Test fun crDocument() = end("one\rtwo", 1, 3)
    @Test fun mixedBreaks() = end("one\rtwo\nthree\r\nfour", 3, 4)
    @Test fun trailingLf() = end("one\n", 1, 0)
    @Test fun trailingCrLf() = end("one\r\n", 1, 0)
    @Test fun trailingCr() = end("one\r", 1, 0)
    @Test fun repeatedCrAreSeparateLines() = end("\r\r", 2, 0)
    @Test fun repeatedMixedBreaks() = end("\r\n\r\n\r", 3, 0)
    @Test fun supplementaryCharacterAfterCrUsesUtf16Units() = end("one\r😀", 1, 2)

    @Test(timeout = 10_000) fun crDocumentRequestUsesActualEndAndAcceptsFinalLineHint() =
        wireEnd("one\r😀", 1, 2)
    @Test(timeout = 10_000) fun crlfDocumentRequestCountsOneBreakAndAcceptsFinalLineHint() =
        wireEnd("one\r\n😀", 1, 2)

    private fun wireEnd(text: String, endLine: Int, endColumn: Int) = runBlocking {
        val host = PipeHost()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rpc = JsonRpcProcess(scope, File("."), listOf("fixture-lsp"), processHost = host)
        try {
            rpc.start()
            val response = async(Dispatchers.Default) {
                LspInlayHintProtocol.requestFull(rpc, "file:///sample.kt", text)
            }
            val request = withContext(Dispatchers.IO) {
                Json.parseToJsonElement(ContentLengthProtocol.readMessage(host.requests)!!).jsonObject
            }
            assertEquals("textDocument/inlayHint", request.getValue("method").jsonPrimitive.content)
            val params = request.getValue("params").jsonObject
            val end = params.getValue("range").jsonObject.getValue("end").jsonObject
            assertEquals(endLine, end.getValue("line").jsonPrimitive.int)
            assertEquals(endColumn, end.getValue("character").jsonPrimitive.int)
            val reply = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", request.getValue("id"))
                put("result", buildJsonArray {
                    add(buildJsonObject {
                        put("position", buildJsonObject {
                            put("line", endLine)
                            put("character", endColumn)
                        })
                        put("label", ": Int")
                    })
                })
            }.toString()
            withContext(Dispatchers.IO) {
                val bytes = reply.toByteArray(Charsets.UTF_8)
                host.responses.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                host.responses.write(bytes)
                host.responses.flush()
            }
            val hint = response.await().single()
            assertEquals(endLine + 1, hint.line)
            assertEquals(endColumn + 1, hint.column)
            assertEquals(": Int", hint.label)
        } finally {
            rpc.close()
            host.close()
            scope.cancel()
        }
    }

    private class PipeHost : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        private val writes = PipedOutputStream()
        val requests = PipedInputStream(writes, 65536)
        val responses = PipedOutputStream()
        private val reads = PipedInputStream(responses, 65536)
        private val alive = AtomicBoolean(true)
        private val process = object : HostedStdioProcess {
            override val stdin = writes
            override val stdout = reads
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode get() = if (alive.get()) null else 0
            override suspend fun awaitExit() = 0
            override fun close() = this@PipeHost.close()
        }
        override suspend fun resolveExecutable(command: String) = command
        override suspend fun start(argv: List<String>, environment: Map<String, String>,
            resourceLimits: ProcessResourceLimits?) = process
        override fun close() {
            if (alive.compareAndSet(true, false)) {
                runCatching { writes.close() }
                runCatching { requests.close() }
                runCatching { responses.close() }
                runCatching { reads.close() }
            }
        }
    }
}
