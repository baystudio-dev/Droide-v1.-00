package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Existing APIs only; tiny local text files and no provider, network, process, or native backend. */
class AgentDiskWriteReceiptBaselineTest {
    @Test(timeout = 15_000)
    fun matchingForeignBytesBeforeWriteRefusalDoNotEarnAnAgentUndoEntry() = refusedMatchingWrite("write_file")

    @Test(timeout = 15_000)
    fun matchingForeignBytesBeforeEditRefusalDoNotEarnAnAgentUndoEntry() = refusedMatchingWrite("edit_file")

    private fun refusedMatchingWrite(tool: String) {
        val root = Files.createTempDirectory("droide-agent-receipt-baseline-").toFile()
        try {
            val target = root.resolve("main.kt").apply { writeText("reviewed original") }
            val beforeCalls = AtomicInteger()
            val afterCalls = AtomicInteger()
            val files = FileRepository(root, beforeMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Write) {
                    beforeCalls.incrementAndGet()
                    // A foreign actor reaches the same bytes without this repository
                    // invocation admitting/staging/publishing its reviewed write.
                    target.writeText("requested replacement")
                    throw IOException("fixture refused before repository publication")
                }
            }, onMutation = { afterCalls.incrementAndGet() })
            val externalCalls = AtomicInteger()
            fun forbidden(): Nothing {
                externalCalls.incrementAndGet()
                throw AssertionError("External execution is forbidden in this local receipt baseline")
            }
            val terminal = NoProcessTerminal(::forbidden)
            val web = AgentWebResearch(root, AgentWebTransport { _, _ -> forbidden() }, object : AgentWebPdfBackend {
                override fun pageCount(file: File): Int = forbidden()
                override fun text(file: File, page: Int): String? = forbidden()
                override fun screenshot(file: File, page: Int, output: OutputStream): AgentWebImageSize = forbidden()
            })
            try {
                val history = EditHistory(files)
                val lsp = object : LspManager {
                    override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
                    override suspend fun diagnose(relPath: String): String = forbidden()
                    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = forbidden()
                }
                val ctx = AgentTools.Ctx(
                    files, terminal, GitManager(root), ApprovalManager(), QuestionManager(), history,
                    PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), AgentMode.BUILD,
                    LlmClient(), AgentConfig(), root, TodoManager(), "receipt-baseline", lsp,
                    webResearch = web,
                )
                val result = runBlocking { withTimeout(8_000) {
                    AgentTools.execute(tool, buildJsonObject {
                        put("path", "main.kt")
                        if (tool == "write_file") put("content", "requested replacement") else {
                            put("search", "reviewed original")
                            put("replace", "requested replacement")
                        }
                    }, ctx)
                } }
                assertTrue(result, result.startsWith("ERROR:"))
                assertTrue(result, result.contains("fixture refused before repository publication"))
                assertFalse("A refusal cannot claim a local save", result.contains("saved locally"))
                assertEquals(1, beforeCalls.get())
                assertEquals("No admitted write reached synchronization", 0, afterCalls.get())
                assertEquals("requested replacement", target.readText())
                assertEquals("No process, network, native renderer, or LSP may run", 0, externalCalls.get())
                assertEquals("Matching current bytes do not prove this invocation committed; foreign work must not earn Agent undo", 0, history.depth())
                assertEquals("requested replacement", target.readText())
            } finally { web.close() }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private class NoProcessTerminal(private val forbidden: () -> Nothing) : ITerminalSession {
        override val output = MutableStateFlow("")
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
}
