package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentPromptPromotionTransactionTest {
    @org.junit.Before fun openAuthoredUserLaunch() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @Test(timeout = 30_000) fun cancellationAtActualMediaRetainReturnKeepsTheQueueThroughChatFinally() = fixture { agent, http, root -> runBlocking {
        val (sessionId, input, store) = seedMediaQueue(agent, http, root)
        val dispatcher = ReturnBarrierDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val running = scope.async { agent.chat("Continue the conversation", http.config, maxTurns = 2) }
            // Drive production setup until retain(session) completed and its IO return is
            // waiting. No model request or history delivery has happened at this point.
            while (true) {
                val continuation = dispatcher.next()
                if (store.ownerRefs(sessionId).any { it.id == input.media.single().id }) {
                    assertEquals(listOf(input), agent.pendingInputs.value)
                    assertFalse(agent.messages.value.any { it.role == "user" && it.content == input.text })
                    running.cancel(CancellationException("Stop at local attachment IO return"))
                    continuation.run()
                    break
                }
                continuation.run()
                check(!running.isCompleted) { "Run ended before the media-retain boundary" }
            }
            while (!running.isCompleted) dispatcher.next().run()
            assertTrue(running.isCancelled)
            assertFalse(agent.busy.value)
            assertEquals(listOf(input), agent.pendingInputs.value)
            val restored = SessionManager(root).getForUpdate(sessionId)!!
            assertEquals(listOf(input), restored.pendingInputs)
            assertFalse(restored.messages.any { it.role == "user" && it.content == input.text })
            assertTrue(restored.messages.any { it.content == "Stopped by user." })
            assertTrue(http.requests.isEmpty())
        } finally { scope.cancel() }
    } }

    @Test(timeout = 30_000) fun failedActualMediaRetentionKeepsAcceptedQueueInTheSuccessfulFinalCheckpoint() = fixture { agent, http, root -> runBlocking {
        val (sessionId, input, store) = seedMediaQueue(agent, http, root)
        // The pending owner still holds a valid blob. Only the future session-owner
        // retain fails, so chat.finally can still save the original pending input.
        store.releaseOwner(sessionId)
        agent.chat("Continue the conversation", http.config, maxTurns = 2)
        assertFalse(agent.busy.value)
        assertNull(agent.persistenceError.value)
        assertEquals(listOf(input), agent.pendingInputs.value)
        val restored = SessionManager(root).getForUpdate(sessionId)!!
        assertEquals(listOf(input), restored.pendingInputs)
        assertFalse(restored.messages.any { it.role == "user" && it.content == input.text })
        assertTrue(restored.messages.any { it.content.contains("media cannot be attached") })
        assertNotNull(store.file(input.media.single()))
        assertTrue(http.requests.isEmpty())
    } }

    @Test(timeout = 30_000) fun promotionCommitsOnceAndKeepsNewArrivalsAndTheirRuntimeConfiguration() = fixture { agent, http, root -> runBlocking {
        val manager = agent.sessions
        val session = manager.create()
        val coordinator = AgentPromptCoordinator(manager, 100_000, 100)
        coordinator.load(emptyList(), session.id)
        val lease = coordinator.reserveDirectRun(); assertTrue(coordinator.markRunReady(lease))
        val first = admit(coordinator, session.id, "first", http.config)
        val secondConfig = http.config.copy(reasoningEffort = ReasoningEffort.HIGH)
        val second = admit(coordinator, session.id, "second", secondConfig)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        val promotion = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.promoteAtBoundary(lease, true, http.config, 1, 100_000,
                prepare = { entered.complete(Unit); release.await() },
                commit = { batch, acknowledge -> acknowledge(); delivered += batch.promotedInputs.map { it.id } })
        }
        entered.await()
        assertEquals(listOf(first, second), coordinator.pendingInputs.value)
        val third = async(start = CoroutineStart.UNDISPATCHED) { admit(coordinator, session.id, "third", http.config) }
        assertFalse(third.isCompleted)
        release.complete(Unit)
        assertEquals(listOf(first), promotion.await()!!.promotedInputs)
        val thirdInput = third.await()
        assertEquals(listOf(second, thirdInput), coordinator.pendingInputs.value)
        val next = coordinator.promoteAtBoundary(lease, true, http.config, 2, 100_000)!!
        assertEquals(listOf(second), next.promotedInputs)
        assertEquals(secondConfig, next.nextConfig)
        assertEquals(listOf(first.id), delivered)
        assertEquals(listOf(thirdInput), coordinator.pendingInputs.value)
        coordinator.withPendingSnapshot { pending -> manager.update(session.id) { it!!.copy(pendingInputs = pending) } }
        assertEquals(listOf(thirdInput), SessionManager(root).getForUpdate(session.id)!!.pendingInputs)
    } }

    @Test(timeout = 30_000) fun clearOrRestoreDuringPreparationCannotAcknowledgeOrResurrectOldInputs() = fixture { agent, http, _ -> runBlocking {
        val manager = agent.sessions
        val old = manager.create(); val replacement = manager.create()
        val coordinator = AgentPromptCoordinator(manager, 100_000, 100)
        coordinator.load(emptyList(), old.id)
        val lease = coordinator.reserveDirectRun(); assertTrue(coordinator.markRunReady(lease))
        val first = admit(coordinator, old.id, "old pending", http.config)
        val newer = first.copy(id = "in-new-owner", text = "new owner task")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var delivered = false
        val promotion = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
            coordinator.promoteAtBoundary(lease, true, http.config, 1, 100_000,
                prepare = { entered.complete(Unit); release.await() },
                commit = { _, acknowledge -> acknowledge(); delivered = true })
        } }
        entered.await()
        coordinator.clear(); coordinator.load(listOf(newer), replacement.id)
        release.complete(Unit)
        assertTrue(promotion.await().exceptionOrNull() is CancellationException)
        assertFalse(delivered)
        assertEquals(listOf(newer), coordinator.pendingInputs.value)
        assertEquals(listOf(first), manager.getForUpdate(old.id)!!.pendingInputs)
    } }

    private suspend fun admit(coordinator: AgentPromptCoordinator, sid: String, text: String, config: AgentConfig): AgentPendingInput {
        var accepted = false
        coordinator.submit(CoroutineScope(currentCoroutineContext()), text, config, AgentPromptDelivery.QUEUE, 3,
            { sid }, { error("Must keep the active lease") }, reportError = { check(it == null) { it.orEmpty() } },
            onAccepted = { accepted = true })!!.join()
        check(accepted)
        return coordinator.pendingInputs.value.last()
    }

    private suspend fun seedMediaQueue(agent: AgentService, http: ProviderStreamFixture, root: File): Triple<String, AgentPendingInput, ChatMediaStore> {
        val store = ChatMediaStore(root)
        val ref = ByteArrayInputStream("queued attachment".toByteArray()).use { store.import("draft-fixture", "notes.txt", "text/plain", it) }
        val base = agent.sessions.create()
        val input = AgentPendingInput("in-media-queue", "Inspect the pending attachment", AgentPromptDelivery.STEER,
            http.config.providerId, http.config.model, media = listOf(ref))
        agent.sessions.savePendingInputs(base.id, listOf(input))
        store.releaseOwner("draft-fixture")
        agent.loadSession(agent.sessions.getForUpdate(base.id)!!)
        assertTrue(store.ownerRefs(base.id).isEmpty())
        return Triple(base.id, input, store)
    }

    private class ReturnBarrierDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun next(): Runnable = checkNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Expected a local IO continuation" }
    }

    private fun fixture(block: (AgentService, ProviderStreamFixture, File) -> Unit) {
        val fixture = Files.createTempDirectory("droide-promotion-transaction-").toFile()
        val root = fixture.resolve("files/projects/project").apply { mkdirs() }
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0)
                try { block(agent, http, root) } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(fixture) }
    }
}
