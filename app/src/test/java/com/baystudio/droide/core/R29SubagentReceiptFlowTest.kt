package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Existing loopback fixture; tasks are direct-seeded terminal records, never executed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class R29SubagentReceiptFlowTest {
    @org.junit.Before fun openAuthoredUserLaunch() {
        // These workflow cases represent an explicitly opened app, not startup
        // recovery. Establish a fresh epoch per method before constructing owners.
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @Test(timeout = 15_000)
    fun queuedCompletionPreservesCapturedAttemptThroughDiskRestoreAndPromotion() = fixture { root, http ->
        val sessions = SessionManager(root)
        val session = sessions.create()
        val receipt = SubagentDeliveryReceipt.from(record(session.id).copy(attempt = 3))
        val coordinator = AgentPromptCoordinator(sessions, 100_000, 10)
        coordinator.load(emptyList(), session.id)
        val lease = coordinator.reserveDirectRun()
        assertTrue(coordinator.markRunReady(lease))
        var accepted = false
        var admissionError: String? = null
        coordinator.submit(CoroutineScope(currentCoroutineContext()), "Captured attempt three", http.config, AgentPromptDelivery.STEER, 1,
            { session.id }, { error("A busy parent must queue this completion") },
            origin = AgentInputOrigin.SUBAGENT, sourceId = receipt.taskId, requiredSessionId = session.id,
            reportError = { admissionError = it }, onAccepted = { accepted = true }, sourceDelivery = receipt)!!.join()
        assertNull(admissionError)
        assertTrue(accepted)
        val saved = requireNotNull(SessionManager(root).get(session.id))
        assertEquals(receipt, saved.pendingInputs.single().sourceDelivery)
        assertEquals(receipt.taskId, saved.pendingInputs.single().sourceId)

        val restored = AgentPromptCoordinator(sessions, 100_000, 10)
        restored.load(saved.pendingInputs, session.id)
        val restoredLease = restored.reserveDirectRun()
        assertTrue(restored.markRunReady(restoredLease))
        val batch = requireNotNull(restored.promoteAtBoundary(restoredLease, false, http.config, 1, 100_000, parentSessionId = session.id))
        assertEquals(listOf(receipt), batch.deliveredSourceReceipts)
        assertEquals(receipt, SubagentDeliveryHistory.read(batch.apiEntries.single()))
        assertEquals(receipt, batch.promotedInputs.single().sourceDelivery)
        assertTrue(restored.pendingInputs.value.isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    @Test(timeout = 15_000)
    fun invalidRestoredReceiptBindingCannotAcknowledgeOrRemovePendingInput() = fixture { root, http ->
        val sessions = SessionManager(root)
        val session = sessions.create()
        val receipt = SubagentDeliveryReceipt.from(record(session.id))
        val valid = AgentPendingInput("input", "Completion", AgentPromptDelivery.STEER, http.config.providerId, http.config.model,
            origin = AgentInputOrigin.SUBAGENT, sourceId = receipt.taskId, sourceDelivery = receipt)
        val invalid = listOf(valid.copy(sourceId = "other-task"), valid.copy(origin = AgentInputOrigin.USER),
            valid.copy(sourceDelivery = receipt.copy(parentSessionId = "other-parent")))
        for (input in invalid) {
            sessions.savePendingInputs(session.id, listOf(input))
            val coordinator = AgentPromptCoordinator(sessions, 100_000, 10)
            coordinator.load(requireNotNull(sessions.get(session.id)).pendingInputs, session.id)
            val lease = coordinator.reserveDirectRun()
            assertTrue(coordinator.markRunReady(lease))
            var prepared = false
            val failure = runCatching {
                coordinator.promoteAtBoundary(lease, false, http.config, 1, 100_000,
                    prepare = { prepared = true }, parentSessionId = session.id)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertFalse(prepared)
            assertEquals(listOf(input), coordinator.pendingInputs.value)
            assertEquals(listOf(input), requireNotNull(sessions.get(session.id)).pendingInputs)
        }
        assertTrue(http.requests.isEmpty())
    }

    @Test(timeout = 15_000)
    fun immediateSubmissionPersistsItsCapturedReceiptBeforeAcknowledging() = fixture { root, http ->
        val terminal = NoProcessTerminal()
        val agent = AgentService(FileRepository(root), terminal, GitManager(root), maxSubagentDepth = 1)
        try {
            val session = agent.sessions.create()
            val completed = record(session.id)
            SubagentTaskStore(root).create(completed)
            agent.loadSession(session)
            submitCompletion(agent, completed, http.config)
            assertNull(agent.persistenceError.value)
            assertEquals(SubagentDeliveryState.DELIVERED, requireNotNull(SubagentTaskStore(root).get(completed.id)).delivery)
            val saved = requireNotNull(agent.sessions.get(session.id))
            assertEquals(listOf(SubagentDeliveryReceipt.from(completed)), saved.apiHistory.mapNotNull(SubagentDeliveryHistory::read))
            assertEquals(1, http.requests.size)
            assertFalse(http.requests.single().toString().contains(SubagentDeliveryHistory.PRIVATE_FIELD))
        } finally {
            try { agent.shutdown() }
            finally { assertEquals("No child or terminal may execute", 0, terminal.executionCalls) }
        }
    }

    @Test(timeout = 15_000)
    fun failedParentSaveLeavesPendingUntilTheSameLiveReceiptIsSuccessfullySaved() = fixture { root, http ->
        val terminal = NoProcessTerminal()
        val agent = AgentService(FileRepository(root), terminal, GitManager(root), maxSubagentDepth = 1)
        try {
            val session = agent.sessions.create()
            val completed = record(session.id)
            SubagentTaskStore(root).create(completed)
            agent.loadSession(session)
            val parentFile = root.resolve(".droide/sessions/${session.id}.json")
            val originalBytes = parentFile.readBytes()
            agent.publicationBoundaryProbe = { boundary ->
                if (boundary == AgentService.PublicationBoundary.INITIAL_STREAM) parentFile.writeText("{")
            }
            submitCompletion(agent, completed, http.config)
            assertNotNull(agent.persistenceError.value)
            assertEquals(SubagentDeliveryState.PENDING, requireNotNull(SubagentTaskStore(root).get(completed.id)).delivery)
            assertEquals(1, http.requests.size)

            // Restore only this fixture's saved metadata, keeping the unsaved live receipt.
            agent.publicationBoundaryProbe = null
            parentFile.writeBytes(originalBytes)
            agent.chat("Continue the parent conversation", http.config, maxTurns = 1)
            assertNull(agent.persistenceError.value)
            assertEquals(SubagentDeliveryState.DELIVERED, requireNotNull(SubagentTaskStore(root).get(completed.id)).delivery)
            val saved = requireNotNull(agent.sessions.get(session.id))
            assertEquals(listOf(SubagentDeliveryReceipt.from(completed)), saved.apiHistory.mapNotNull(SubagentDeliveryHistory::read))
            assertEquals(2, http.requests.size)
        } finally {
            try { agent.shutdown() }
            finally { assertEquals("No child or terminal may execute", 0, terminal.executionCalls) }
        }
    }

    private suspend fun submitCompletion(agent: AgentService, record: SubagentTaskRecord, config: AgentConfig) = coroutineScope {
        requireNotNull(agent.submitAsync(this, renderSubagentCompletionText(record, AgentService.MAX_API_MESSAGE_CHARS), config,
            maxTurns = 1, inputOrigin = AgentInputOrigin.SUBAGENT, sourceId = record.id, requiredSessionId = record.parentSessionId,
            sourceDelivery = SubagentDeliveryReceipt.from(record))).join()
        // The immediate run is a child of this scope, so returning waits for its final checkpoint.
    }

    private fun record(parent: String) = SubagentTaskRecord(
        id = "task-flow", parentSessionId = parent, childSessionId = "child-flow", profile = "explore", description = "Seeded completion",
        background = true, state = SubagentTaskState.IDLE, delivery = SubagentDeliveryState.PENDING, result = "Completed without launching a child",
    )

    private fun fixture(block: suspend (File, ProviderStreamFixture) -> Unit) {
        val root = Files.createTempDirectory("droide-r29-receipt-flow-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { http ->
                http.secret = ""
                http.forceBufferedResponse = true
                http.response = { _, _, _ -> """{"choices":[{"index":0,"message":{"role":"assistant","content":"Parent continued"},"finish_reason":"stop"}]}""" }
                runBlocking { withTimeout(8_000) { block(root, http) } }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        var executionCalls = 0
        private fun forbidden(): Nothing { executionCalls++; error("No process is allowed") }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
}
