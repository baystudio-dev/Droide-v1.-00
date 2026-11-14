package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Original five regressions adapted to typed receipts, plus bounded persistence/attempt controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class R29SubagentDeliveryBaselineTest {
    @Test(timeout = 15_000)
    fun launchReceiptDoesNotProveCompletionDelivery() = fixture { bridge, record, _ ->
        val prior = listOf(entry("tool", "SUBAGENT_STARTED task_id=${record.id} child_session_id=${record.childSessionId} state=queued"))
        assertRecovered(bridge, record, prior)
    }

    @Test(timeout = 15_000)
    fun userQuotationDoesNotProveCompletionDelivery() = fixture { bridge, record, _ ->
        // AgentService appends the new user entry before invoking recover().
        assertRecovered(bridge, record, listOf(entry("user", "What happened to task_id=${record.id}?")))
    }

    @Test(timeout = 15_000)
    fun earlierAttemptCompletionDoesNotConsumeLaterAttemptResult() = fixture(attempt = 2) { bridge, record, _ ->
        val earlier = record.copy(attempt = 1, result = "Earlier attempt output")
        val prior = project(bridge, earlier).apiEntries
        assertRecovered(bridge, record, prior)
    }

    @Test(timeout = 15_000)
    fun genuineCurrentCompletionIsDeduplicated() = fixture { bridge, record, root ->
        val prior = project(bridge, record).apiEntries
        val recovered = bridge.recover(PARENT, prior)
        assertTrue(recovered.apiEntries.isEmpty())
        assertTrue(recovered.messages.isEmpty())
        assertEquals(listOf(SubagentDeliveryReceipt.from(record)), recovered.deliveryReceipts)
        assertEquals(SubagentDeliveryState.PENDING, requireNotNull(bridge.manager).pendingDeliveries(PARENT).single().delivery)
        // A real parent save precedes the exact acknowledgement, including duplicate recovery.
        val sessions = SessionManager(root)
        sessions.save(DroideSession(PARENT, "Parent", apiHistory = prior, forkHistory = prior,
            forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        assertEquals(SubagentDeliveryReceipt.from(record), SubagentDeliveryHistory.read(requireNotNull(sessions.get(PARENT)).apiHistory.single()))
        bridge.markDelivered(recovered.deliveryReceipts)
        assertTrue(requireNotNull(bridge.manager).pendingDeliveries(PARENT).isEmpty())
    }

    @Test(timeout = 15_000)
    fun freshResultRemainsPendingUntilParentPersistenceAcknowledgesIt() = fixture { bridge, record, _ ->
        assertRecovered(bridge, record, emptyList())
    }

    @Test(timeout = 15_000)
    fun staleAcknowledgementCannotConsumeReplacementAttempt() = fixture { bridge, record, root ->
        val oldReceipt = SubagentDeliveryReceipt.from(record)
        // Advance the durable task after an earlier receipt has been captured.
        val replacement = record.copy(attempt = record.attempt + 1, result = "Replacement attempt result")
        SubagentTaskStore(root).create(replacement)
        bridge.markDelivered(listOf(oldReceipt, oldReceipt))
        val currentReceipt = SubagentDeliveryReceipt.from(replacement)
        bridge.markDelivered(listOf(currentReceipt.copy(parentSessionId = "other-parent"), currentReceipt.copy(childSessionId = "other-child")))
        assertEquals(replacement.attempt, requireNotNull(bridge.manager).pendingDeliveries(PARENT).single().attempt)
        assertRecovered(bridge, replacement, project(bridge, record).apiEntries)
    }

    @Test(timeout = 15_000)
    fun receiptlessLegacyCompletionCannotConsumeANewerAttempt() = fixture(attempt = 2) { bridge, record, _ ->
        val earlier = record.copy(attempt = 1, result = "Legacy output")
        val legacy = bridge.projectInitial(AgentInputOrigin.SUBAGENT, earlier.id, bridge.render(earlier), 1L).apiEntries
        assertNull(SubagentDeliveryHistory.read(legacy.single()))
        assertRecovered(bridge, record, legacy)
    }

    @Test(timeout = 15_000)
    fun rejectedParentCheckpointKeepsMatchingLiveReceiptPending() = fixture { bridge, record, root ->
        val sessions = SessionManager(root)
        sessions.save(DroideSession(PARENT, "Parent"))
        val prior = project(bridge, record).apiEntries
        val recovered = bridge.recover(PARENT, prior)
        assertTrue(recovered.apiEntries.isEmpty())
        val failure = runCatching {
            sessions.update(PARENT, commitAdmission = { throw IOException("Controlled parent checkpoint rejection") }) {
                requireNotNull(it).copy(apiHistory = prior, forkHistory = prior, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(requireNotNull(sessions.get(PARENT)).apiHistory.isEmpty())
        assertEquals(SubagentDeliveryState.PENDING, requireNotNull(bridge.manager).pendingDeliveries(PARENT).single().delivery)
        assertRecovered(bridge, record, requireNotNull(sessions.get(PARENT)).apiHistory)
    }

    private suspend fun assertRecovered(bridge: AgentSubagentBridge, record: SubagentTaskRecord, prior: List<JsonObject>) {
        val recovered = bridge.recover(PARENT, prior)
        assertEquals("Exactly this pending child result must be projected", listOf(SubagentDeliveryReceipt.from(record)), recovered.deliveryReceipts)
        assertTrue(recovered.apiEntries.single().toString().contains(record.result))
        assertEquals("Recovery alone must not acknowledge unsaved parent history", SubagentDeliveryState.PENDING,
            requireNotNull(bridge.manager).pendingDeliveries(PARENT).single().delivery)
    }

    private suspend fun project(bridge: AgentSubagentBridge, record: SubagentTaskRecord) =
        bridge.projectInitial(AgentInputOrigin.SUBAGENT, record.id, bridge.render(record), 1L,
            parentSessionId = record.parentSessionId, sourceDelivery = SubagentDeliveryReceipt.from(record))

    private fun fixture(attempt: Int = 1, action: suspend (AgentSubagentBridge, SubagentTaskRecord, File) -> Unit) = runBlocking {
        withTimeout(8_000) {
            val root = Files.createTempDirectory("droide-r29-delivery-").toFile()
            val record = SubagentTaskRecord(
                id = "task-delivery", parentSessionId = PARENT, childSessionId = "child-delivery", profile = "explore",
                description = "Inspect source", background = true, state = SubagentTaskState.IDLE,
                delivery = SubagentDeliveryState.PENDING, attempt = attempt, result = "Current result must survive",
            )
            val terminal = NoProcessTerminal()
            val bridge = AgentSubagentBridge(
                files = FileRepository(root), terminal = terminal, git = GitManager(root),
                approvals = ApprovalManager(), questions = QuestionManager(), permissions = PermissionEngine(),
                lsp = object : LspManager {
                    override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
                    override suspend fun diagnose(relPath: String): String = error("No LSP work is allowed")
                    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = error("No LSP work is allowed")
                }, workDir = root, depth = 0, maxDepth = 1,
                maxAssistantChars = AgentService.MAX_ASSISTANT_CHARS, maxApiMessageChars = AgentService.MAX_API_MESSAGE_CHARS,
                onBackgroundCompletion = { error("No background child may run in this baseline") },
            )
            try {
                SubagentTaskStore(root).create(record)
                action(bridge, record, root)
            } finally {
                try {
                    bridge.close()
                    assertEquals("The baseline must never start any terminal process", 0, terminal.executionCalls)
                } finally {
                    PathSecurity.deleteTreeNoFollow(root)
                }
            }
        }
    }

    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        var executionCalls = 0
        private fun forbidden(): Nothing { executionCalls++; error("No process is allowed in this baseline") }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    private fun entry(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
    private companion object { const val PARENT = "parent-delivery" }
}
