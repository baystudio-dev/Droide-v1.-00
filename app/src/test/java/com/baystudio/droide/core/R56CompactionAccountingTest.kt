package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Authored real-stack controls: production transport, session transactions and accounting;
 * loopback responses only, with no terminal or external provider execution. */
class R56CompactionAccountingTest {
    @Test(timeout = 60_000)
    fun laterChunkFailureRetainsEarlierAndFailedRequestUsageAcrossProtocols() {
        ProviderWireProtocol.entries.forEach { protocol -> withCompaction(protocol) {
            respond(failAt = 2)
            val failure = runCatching { compact() }.exceptionOrNull()
            assertTrue("Expected typed provider failure for $protocol", failure is LlmProviderStreamException)
            assertSamples(2)
            assertUnchanged()
        } }
    }

    @Test(timeout = 60_000)
    fun consolidationFailureRetainsEveryCompletedChunkAndItsOwnUsageAcrossProtocols() {
        ProviderWireProtocol.entries.forEach { protocol -> withCompaction(protocol) {
            respond(failAt = 3)
            val failure = runCatching { compact() }.exceptionOrNull()
            assertTrue("Expected typed consolidation failure for $protocol", failure is LlmProviderStreamException)
            assertSamples(3)
            assertUnchanged()
        } }
    }

    @Test(timeout = 15_000)
    fun blankCompletedLaterSummaryIsAccountedExactlyOnceBeforeExtractionRejectsIt() = withCompaction {
        respond(blankAt = 2)
        val failure = runCatching { compact() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("empty checkpoint"))
        assertSamples(2)
        assertUnchanged()
    }

    @Test(timeout = 15_000)
    fun finalAdmissionFailureCannotDiscardCompletedUsageOrPublishAPartialCheckpoint() = withCompaction {
        respond()
        var admissions = 0
        val revoked = CancellationException("Compaction owner revoked")
        val failure = runCatching {
            manager.compact(session, fixture.config, keepRecentTokens = 1000,
                onUsage = { samples += it }, commitAdmission = {
                    admissions++
                    assertSamples(3)
                    throw revoked
                })
        }.exceptionOrNull()
        assertSame(revoked, failure)
        assertEquals(1, admissions)
        assertSamples(3)
        assertUnchanged()
    }

    @Test(timeout = 15_000)
    fun successfulCompactionKeepsExchangeDiagnosticsWithoutReplayingUsage() = withCompaction {
        respond()
        val result = compact()
        assertTrue(result.changed)
        assertEquals(3, result.exchanges.size)
        assertSamples(3)
        assertEquals(result.session.apiHistory, manager.getForUpdate(session.id)!!.apiHistory)
        assertTrue(result.session.apiHistory.first()["content"].toString().contains("checkpoint 3"))
    }

    @Test(timeout = 45_000)
    fun automaticOverflowAndManualCallersRetainUsageWhenALaterRequestFails() {
        EntryPoint.entries.forEach { entry -> withCompaction {
            val tokens = TokenTracker()
            respond(failAt = 2, reported = { tokens.history.value.size })
            val activeHistory = session.apiHistory.toMutableList()
            var loads = 0
            val failure = runCatching {
                invoke(entry, tokens, activeHistory) { loads++ }
            }.exceptionOrNull()
            assertTrue("Expected provider failure through $entry", failure is LlmProviderStreamException)
            assertEquals(2, fixture.requests.size)
            assertEquals(2, tokens.history.value.size)
            assertEquals(2L, tokens.usage.value.requestCount)
            assertEquals(226, tokens.usage.value.total)
            assertEquals(UsageAuthority.AUTHORITATIVE, tokens.usage.value.tokenAuthority)
            assertEquals(0, loads)
            assertEquals(session.apiHistory, activeHistory)
            assertUnchanged()
        } }
    }

    @Test(timeout = 45_000)
    fun automaticOverflowAndManualSuccessDoNotDoubleCountExchangeDiagnostics() {
        EntryPoint.entries.forEach { entry -> withCompaction {
            val tokens = TokenTracker()
            respond(reported = { tokens.history.value.size })
            val activeHistory = session.apiHistory.toMutableList()
            var loads = 0
            invoke(entry, tokens, activeHistory) { loaded ->
                loads++
                assertEquals(manager.getForUpdate(session.id)!!.apiHistory, loaded.apiHistory)
            }
            assertEquals(3, fixture.requests.size)
            assertEquals(3, tokens.history.value.size)
            assertEquals(3L, tokens.usage.value.requestCount)
            assertEquals(342, tokens.usage.value.total)
            assertEquals(if (entry == EntryPoint.MANUAL) 1 else 0, loads)
            if (entry != EntryPoint.MANUAL) {
                assertEquals(manager.getForUpdate(session.id)!!.apiHistory, activeHistory)
            }
        } }
    }

    private enum class EntryPoint { AUTOMATIC, OVERFLOW, MANUAL }

    private class CompactionFixture(
        val root: File,
        val fixture: ProviderStreamFixture,
        val manager: SessionManager,
        val session: DroideSession,
    ) {
        val samples = CopyOnWriteArrayList<TokenUsage>()
        private val originalFiles = savedFiles()

        fun respond(failAt: Int? = null, blankAt: Int? = null, reported: () -> Int = { samples.size }) {
            fixture.response = { request, _, streaming ->
                assertFalse("Compaction must use the production buffered transport", streaming)
                assertEquals("Prior work must be accounted before the next request starts", request - 1, reported())
                response(fixture.protocol, request, failed = request == failAt,
                    content = if (request == blankAt) " \n\t" else "checkpoint $request")
            }
        }

        suspend fun compact() = manager.compact(session, fixture.config, keepRecentTokens = 1000,
            onUsage = { samples += it })

        fun assertSamples(expected: Int) {
            assertEquals("A failed HTTP-200 response must never be replayed", expected, fixture.requests.size)
            assertEquals("Exactly one sample for each response", expected, samples.size)
            samples.forEachIndexed { index, usage ->
                val request = index + 1
                assertEquals(1L, usage.requestCount)
                assertEquals(100 + request, usage.prompt)
                assertEquals(10 + request, usage.completion)
                assertEquals(110 + request * 2, usage.total)
                assertEquals(UsageAuthority.AUTHORITATIVE, usage.tokenAuthority)
                assertEquals(1L, usage.knownTokenRequestCount)
            }
        }

        suspend fun assertUnchanged() {
            assertEquals("No summary or failed public text may replace historical evidence", session,
                manager.getForUpdate(session.id))
            assertEquals("Failed compaction must leave the persisted transaction unchanged", originalFiles, savedFiles())
        }

        suspend fun invoke(entry: EntryPoint, tokens: TokenTracker, activeHistory: MutableList<JsonObject>,
            load: suspend (DroideSession) -> Unit) {
            if (entry == EntryPoint.MANUAL) {
                val hooks = AgentHookLifecycleBridge(
                    HookManager(root, NoExecutionTerminal(), PermissionEngine(), ApprovalManager()),
                    { session.id }, { session.mode },
                )
                val result = AgentManualCompaction.run(session.id, manager, session.messages, activeHistory,
                    null, session.todos, session.mode, session.pendingInputs, fixture.config, tokens, hooks, load)
                assertEquals("Session compacted.", result)
            } else {
                assertTrue(AgentRequestSupport.compact(manager, tokens, TodoManager(), session.mode,
                    session.pendingInputs, session.messages, activeHistory, null, fixture.config, session.id,
                    overflowRecovery = entry == EntryPoint.OVERFLOW, ensureCurrent = {}))
            }
        }

        private fun savedFiles(): Map<String, String> = root.resolve(".droide/sessions").walkTopDown()
            .filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    private fun withCompaction(protocol: ProviderWireProtocol = ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
        block: suspend CompactionFixture.() -> Unit) {
        val root = Files.createTempDirectory("droide-r56-compaction-accounting-").toFile()
        try {
            withProviderStreamFixture(protocol, authRequired = false) { fixture ->
                fixture.secret = ""
                ModelContextRegistry.remember(fixture.provider.id, fixture.model, 16_384)
                try { runBlocking { withTimeout(10_000) {
                    val manager = SessionManager(root)
                    val session = manager.create("Original conversation").copy(apiHistory = listOf(
                        message("user", "PRESERVE_RESTRICTIONS " + "A".repeat(26_000)),
                        message("assistant", "UNRESOLVED_FAILURE " + "B".repeat(26_000)),
                        message("user", "Continue within existing restrictions."),
                    ))
                    manager.save(session)
                    val selected = AgentHistory.selectForCompaction(session.apiHistory, 1000)
                    assertEquals("Two historical requests must complete before consolidation", 2,
                        manager.chunkRenderedHistory(selected.historical, manager.compactionChunkChars(fixture.config)).size)
                    CompactionFixture(root, fixture, manager, session).block()
                } } } finally { ModelContextRegistry.forget(fixture.provider.id) }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private class NoExecutionTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        private fun forbidden(): Nothing = throw AssertionError("Compaction accounting cannot execute a terminal command")
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

    companion object {
        private fun message(role: String, content: String) = buildJsonObject {
            put("role", role); put("content", content)
        }

        private fun response(protocol: ProviderWireProtocol, request: Int, failed: Boolean, content: String): String {
            val input = 100 + request
            val output = 10 + request
            val error = buildJsonObject {
                put("code", 503); put("type", "overloaded_error"); put("message", "Fixture interrupted generation")
            }
            return buildJsonObject {
                if (failed) put("error", error)
                when (protocol) {
                    ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> {
                        put("choices", buildJsonArray { add(buildJsonObject {
                            put("message", message("assistant", content))
                            put("finish_reason", if (failed) JsonNull else JsonPrimitive("stop"))
                        }) })
                        put("usage", buildJsonObject {
                            put("prompt_tokens", input); put("completion_tokens", output); put("total_tokens", input + output)
                        })
                    }
                    ProviderWireProtocol.OPENAI_RESPONSES -> {
                        put("status", if (failed) "failed" else "completed")
                        put("output", buildJsonArray { add(buildJsonObject {
                            put("type", "message"); put("role", "assistant")
                            put("status", if (failed) "incomplete" else "completed")
                            put("content", buildJsonArray { add(buildJsonObject {
                                put("type", "output_text"); put("text", content)
                            }) })
                        }) })
                        put("usage", buildJsonObject {
                            put("input_tokens", input); put("output_tokens", output); put("total_tokens", input + output)
                        })
                    }
                    ProviderWireProtocol.ANTHROPIC_MESSAGES -> {
                        put("type", "message"); put("role", "assistant")
                        put("stop_reason", if (failed) JsonNull else JsonPrimitive("end_turn"))
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", content) }) })
                        put("usage", buildJsonObject { put("input_tokens", input); put("output_tokens", output) })
                    }
                    ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> {
                        put("candidates", buildJsonArray { add(buildJsonObject {
                            if (!failed) put("finishReason", "STOP")
                            put("content", buildJsonObject {
                                put("role", "model")
                                put("parts", buildJsonArray { add(buildJsonObject { put("text", content) }) })
                            })
                        }) })
                        put("usageMetadata", buildJsonObject {
                            put("promptTokenCount", input); put("candidatesTokenCount", output); put("totalTokenCount", input + output)
                        })
                    }
                }
            }.toString()
        }
    }
}
