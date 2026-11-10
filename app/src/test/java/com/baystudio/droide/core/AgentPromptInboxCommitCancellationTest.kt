package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Hold the actual IO-return continuation after primary JSON commits, then cancel it. */
class AgentPromptInboxCommitCancellationTest {
    @Test(timeout = 30_000) fun cancelledAdmissionReturnKeepsTheDurablePromptInMemoryAndAtTheNextCheckpoint() = workspace { root ->
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
            val manager = SessionManager(root)
            val session = manager.create()
            val inbox = AgentPromptInbox(manager)
            inbox.load(emptyList(), session.id)
            val lease = inbox.reserveDirectRun(); assertTrue(inbox.markReady(lease))
            val dispatcher = ReturnBarrierDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val releaseWrite = CompletableDeferred<Unit>()
            val writeEntered = CompletableDeferred<Unit>()
            val holdingWrite = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                manager.update(session.id) { current ->
                    writeEntered.complete(Unit); releaseWrite.await(); current!!
                }
            }
            writeEntered.await()
            var returnedNormally = false
            try {
                val admission = scope.async {
                    inbox.routeSubmission(session.id, "durably admitted", AgentPromptDelivery.STEER, http.config)
                    returnedNormally = true
                }
                dispatcher.next().run()

                releaseWrite.complete(Unit); holdingWrite.await()
                val ioReturn = dispatcher.next(admission) // IO completed; caller has not resumed.
                val committed = manager.getForUpdate(session.id)!!.pendingInputs.single()
                assertEquals("durably admitted", committed.text)
                assertEquals(listOf(committed), inbox.inputs.value)
                admission.cancel(CancellationException("Cancel after disk commit, before caller return"))
                ioReturn.run()
                withTimeout(5_000) { admission.join() }
                assertTrue(admission.isCancelled)
                assertFalse(returnedNormally)
                checkpoint(manager, inbox, session.id)
                assertEquals(listOf(committed), SessionManager(root).getForUpdate(session.id)!!.pendingInputs)

                val promoted = inbox.takeSteers(lease, http.config.copy(model = "another-model"))
                assertEquals(committed.id, promoted.single().input.id)
                assertEquals(http.config, promoted.single().runtimeConfig)
                assertTrue(http.requests.isEmpty())
            } finally { releaseWrite.complete(Unit); holdingWrite.cancel(); scope.cancel() }
        } }
    }

    @Test(timeout = 30_000) fun cancelledRemovalReturnCannotResurrectTheRemovedPromptAtTheNextCheckpoint() = workspace { root ->
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
            val manager = SessionManager(root)
            val session = manager.create()
            val inbox = AgentPromptInbox(manager)
            inbox.load(emptyList(), session.id)
            val lease = inbox.reserveDirectRun(); assertTrue(inbox.markReady(lease))
            val input = (inbox.routeSubmission(session.id, "remove this pending task", AgentPromptDelivery.QUEUE, http.config)
                as AgentPromptInbox.SubmissionDecision.Admitted).input
            val dispatcher = ReturnBarrierDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val releaseWrite = CompletableDeferred<Unit>()
            val writeEntered = CompletableDeferred<Unit>()
            val holdingWrite = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                manager.update(session.id) { current ->
                    writeEntered.complete(Unit); releaseWrite.await(); current!!
                }
            }
            writeEntered.await()
            var returnedNormally = false
            try {
                val removal = scope.async {
                    inbox.remove(session.id, input.id)
                    returnedNormally = true
                }
                dispatcher.next().run()
                releaseWrite.complete(Unit); holdingWrite.await()
                val ioReturn = dispatcher.next(removal)
                assertTrue(manager.getForUpdate(session.id)!!.pendingInputs.isEmpty())
                assertTrue(inbox.inputs.value.isEmpty())
                removal.cancel(CancellationException("Cancel after removal commit, before caller return"))
                ioReturn.run()
                withTimeout(5_000) { removal.join() }
                assertTrue(removal.isCancelled)
                assertFalse(returnedNormally)
                checkpoint(manager, inbox, session.id)
                assertTrue(SessionManager(root).getForUpdate(session.id)!!.pendingInputs.isEmpty())
                assertTrue(inbox.takeForIdle(lease, http.config).isEmpty())
                assertTrue(http.requests.isEmpty())
            } finally { releaseWrite.complete(Unit); holdingWrite.cancel(); scope.cancel() }
        } }
    }

    private suspend fun checkpoint(manager: SessionManager, inbox: AgentPromptInbox, id: String) {
        inbox.withPendingSnapshot { pending ->
            manager.update(id) { current -> current!!.copy(
                messages = listOf(AgentMessage("assistant", "checkpoint after cancellation")), pendingInputs = pending) }
        }
    }

    private class ReturnBarrierDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun next(operation: Deferred<*>? = null): Runnable {
            tasks.poll(5, TimeUnit.SECONDS)?.let { return it }

            if (operation?.isCompleted == true) runBlocking { operation.await() }
            error("Expected a suspended IO-return continuation")
        }
    }

    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-inbox-committed-cancel-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
