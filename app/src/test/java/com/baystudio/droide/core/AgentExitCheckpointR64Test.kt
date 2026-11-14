package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Authored aggregate integration tests: real native session/task stores; no provider calls. */
class AgentExitCheckpointR64Test {
    private fun fixture(block: suspend CoroutineScope.(AgentService, File) -> Unit) = runBlocking {
        val admissionField = ApplicationWorkAdmission::class.java.getDeclaredField("state").apply { isAccessible = true }
        val old = admissionField.get(null)
        val state = ApplicationWorkAdmissionState(read = { ApplicationWorkAdmissionState.Snapshot("a".repeat(32), true) }, write = {})
        state.initialize(); admissionField.set(null, state)
        val root = Files.createTempDirectory("agent-exit-r64-").toFile()
        val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0)
        try { withTimeout(8_000) { block(agent, root) } }
        finally { agent.shutdown(); admissionField.set(null, old); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun exitJoinsExactRunBeforeSavingAndDoesNotRetireItsHistoryTicket() = fixture { agent, _ ->
        val saved = agent.sessions.create(); agent.loadSession(saved)
        val field = AgentService::class.java.getDeclaredField("conversationHistory").apply { isAccessible = true }
        val history = field.get(agent) as AgentConversationHistory
        val ticket = history.ownershipVersion
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exactRun = launch {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) {
                release.await()
                agent.todos.set(listOf(Todo("last owned update", "completed", "normal")), saved.id)
            } }
        }
        AgentService::class.java.getDeclaredField("job").apply { isAccessible = true }.set(agent, exactRun)
        started.await(); ApplicationWorkAdmission.closeForExit()
        val exiting = async { agent.stopAndCheckpointForExit() }
        yield(); assertFalse(exiting.isCompleted)
        assertEquals(ticket, history.ownershipVersion)
        release.complete(Unit); exiting.await()
        assertTrue(exactRun.isCompleted)
        assertEquals(ticket, history.ownershipVersion)
        assertEquals("last owned update", agent.sessions.get(saved.id)!!.todos.single().content)
        assertTrue(runCatching { agent.clear() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { agent.loadSession(saved) }.exceptionOrNull() is CancellationException)
        assertNull(agent.submitAsync(this, "late", AgentConfig()))
    }

    @Test fun exitBeforeRunDispatchRetainsAcceptedInputWithoutAnyProviderRequest() {
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
            fixture { agent, _ ->
                val owner = SupervisorJob(coroutineContext[Job])
                val scope = CoroutineScope(coroutineContext + owner)
                try {
                    val admitted = agent.submitAsync(scope, "undispatched native draft", provider.config,
                        onAccepted = { ApplicationWorkAdmission.closeForExit(); agent.cancel() })
                    checkNotNull(admitted).join()
                    agent.stopAndCheckpointForExit()
                    assertTrue(provider.requests.isEmpty())
                    assertNull(agent.currentSessionId)
                    assertEquals(listOf("undispatched native draft"), agent.snapshotUncheckpointedInputsForExit().map { it.text })
                    assertFalse(agent.busy.value)
                } finally { owner.cancelAndJoin() }
            }
        }
    }

    @Test fun failedSessionReceiptRetainsMemoryAndRetrySavesWithoutLaunchingARun() = fixture { agent, root ->
        val saved = agent.sessions.create(); agent.loadSession(saved)
        @Suppress("UNCHECKED_CAST")
        val messages = AgentService::class.java.getDeclaredField("_messages").apply { isAccessible = true }.get(agent) as MutableStateFlow<List<AgentMessage>>
        messages.value = listOf(AgentMessage("user", "preserve"), AgentMessage("assistant", "last public answer"))
        val directory = root.resolve(".droide/sessions")
        val backup = root.resolve(".droide/sessions-before-exit")
        assertTrue(directory.renameTo(backup)); directory.writeText("fixture storage failure")
        ApplicationWorkAdmission.closeForExit()
        assertTrue(runCatching { agent.stopAndCheckpointForExit() }.isFailure)
        assertNotNull(agent.persistenceError.value)
        assertEquals("last public answer", agent.messages.value.last().content)
        assertTrue(directory.delete()); assertTrue(backup.renameTo(directory))
        agent.stopAndCheckpointForExit()
        assertEquals("last public answer", agent.sessions.get(saved.id)!!.messages.last().content)
        assertNull(agent.persistenceError.value)
        assertFalse(agent.busy.value)
    }

    @Test fun failedChildTerminalReceiptSurvivesExitAndRetriesOnlyTheStoredRecord() = fixture { _, root ->
        val terminal = AgentWorkflowTerminal()
        val manager = SubagentManager(FileRepository(root), terminal, GitManager(root), ApprovalManager(), CliLspManager(terminal, FileRepository(root)),
            QuestionManager(), PermissionEngine(), root, 0, 1, onBackgroundCompletion = { error("Exit must not deliver a new parent prompt") })
        val store = SubagentManager::class.java.getDeclaredField("store").apply { isAccessible = true }.get(manager) as SubagentTaskStore
        val initial = store.create(SubagentTaskRecord(id = "task-exit-r64", parentSessionId = "parent", childSessionId = "child",
            profile = "test", description = "exit fixture", background = true, state = SubagentTaskState.QUEUED, delivery = SubagentDeliveryState.PENDING))
        val stopped = initial.copy(state = SubagentTaskState.KILLED, error = "Stopped at Exit", endedAtMs = 1)
        @Suppress("UNCHECKED_CAST")
        val pending = SubagentManager::class.java.getDeclaredField("unsavedTerminalRecords").apply { isAccessible = true }.get(manager) as MutableMap<String, SubagentTaskRecord>
        pending[initial.id] = stopped
        val directory = root.resolve(".droide/subagents"); val backup = root.resolve(".droide/subagents-backup")
        assertTrue(directory.renameTo(backup)); directory.writeText("fixture storage failure")
        try {
            ApplicationWorkAdmission.closeForExit()
            assertTrue(runCatching { manager.closeAndJoin() }.isFailure)
            assertEquals(stopped, pending[initial.id])
            assertTrue(directory.delete()); assertTrue(backup.renameTo(directory))
            manager.closeAndJoin()
            assertTrue(pending.isEmpty())
            assertEquals(SubagentTaskState.KILLED, store.get(initial.id)?.state)
            assertEquals(SubagentDeliveryState.PENDING, store.get(initial.id)?.delivery)
        } finally { manager.close() }
    }
}
