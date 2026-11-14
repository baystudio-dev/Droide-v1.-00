package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AgentOwnedExitDrainR64Test {
    @Test fun backgroundExitAwaitsBackendFinalizationAndRejectsLateLaunches() = runBlocking {
        val admissionField = ApplicationWorkAdmission::class.java.getDeclaredField("state").apply { isAccessible = true }
        val previousAdmission = admissionField.get(null)
        val admission = ApplicationWorkAdmissionState(read = { ApplicationWorkAdmissionState.Snapshot("b".repeat(32), true) }, write = {})
        admission.initialize(); admissionField.set(null, admission)
        val started = CompletableDeferred<Unit>(); val finalized = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val terminal = object : ITerminalSession by AgentWorkflowTerminal() {
            override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
                try { started.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { release.await(); finalized.complete(Unit) } }
            }
        }
        val manager = BackgroundCommandManager(terminal)
        try {
            val initial = manager.start("fixture-owned-operation")
            started.await()
            val exiting = async { manager.closeAndJoin() }
            yield()
            assertFalse(exiting.isCompleted)
            assertTrue(runCatching { manager.start("must-not-launch") }.isFailure)
            release.complete(Unit); exiting.await()
            assertTrue(finalized.isCompleted)
            assertEquals(BackgroundCommandManager.State.KILLED, manager.snapshot(initial.id)?.state)
            manager.closeAndJoin()
        } finally { release.complete(Unit); manager.close(); admissionField.set(null, previousAdmission) }
    }

    @Test fun suspendingChildCheckpointCompletesBeforeRemainingOwnedResourcesClose() = runBlocking {
        val order = mutableListOf<String>()
        val checkpoint = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val resources = SubagentOwnedResources { _, failure -> throw AssertionError(failure) }
        val work = launch {
            resources.use {
                resources.register("terminal") { order += "terminal" }
                resources.register("child checkpoint") { started.complete(Unit); checkpoint.await(); order += "checkpoint" }
                awaitCancellation()
            }
        }
        yield(); work.cancel(); started.await()
        assertTrue(order.isEmpty())
        checkpoint.complete(Unit); work.join()
        assertEquals(listOf("checkpoint", "terminal"), order)
    }
}
