package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Original process-local acceptance evidence must never be refreshed by queue promotion. */
class AgentPromptAdmissionEvidenceTest {
    @Test fun coordinatorCapturesBeforeAsyncImagePreparationAndKeepsOriginalQueueTime() = fixture { root, config ->
        val sessions = SessionManager(root)
        val session = sessions.create()
        var clock = 100L
        val coordinator = AgentPromptCoordinator(sessions, 100_000, 100, admissionClockNanos = { clock })
        coordinator.load(emptyList(), session.id)
        val lease = coordinator.reserveDirectRun(); assertTrue(coordinator.markRunReady(lease))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val submission = coordinator.submit(this, "new request", config, AgentPromptDelivery.QUEUE, 3,
            { session.id }, { error("Already running") }, prepareImages = { entered.complete(Unit); release.await(); emptyList() },
            reportError = { check(it == null) { it.orEmpty() } })!!
        entered.await()
        clock = 900L
        release.complete(Unit); submission.join()
        val batch = coordinator.promoteAtBoundary(lease, true, config, 1, 100_000)!!
        assertEquals(100L, batch.requestAdmittedAtNanos)
        assertEquals("new request", batch.promotedInputs.single().text)
        assertFalse(sessions.getForUpdate(session.id)!!.toString().contains("requestAdmittedAtNanos"))
    }

    @Test fun steerBatchCarriesEarliestOriginalRatherThanMostRecentRequest() = fixture { root, config ->
        val sessions = SessionManager(root); val session = sessions.create()
        val coordinator = AgentPromptCoordinator(sessions, 100_000, 100)
        coordinator.load(emptyList(), session.id)
        val lease = coordinator.reserveDirectRun(); assertTrue(coordinator.markRunReady(lease))
        admit(coordinator, session.id, config, "accepted before grant", 100)
        admit(coordinator, session.id, config, "accepted after grant", 300)
        val batch = coordinator.promoteAtBoundary(lease, false, config, 1, 100_000)!!
        assertEquals(2, batch.promotedInputs.size)
        assertEquals(100L, batch.requestAdmittedAtNanos)
        assertTrue(coordinator.pendingInputs.value.isEmpty())
    }

    @Test fun queueFifoRetainsEachOriginalAdmissionInsteadOfPromotionTime() = fixture { root, config ->
        val sessions = SessionManager(root); val session = sessions.create()
        val coordinator = AgentPromptCoordinator(sessions, 100_000, 100)
        coordinator.load(emptyList(), session.id)
        val lease = coordinator.reserveDirectRun(); assertTrue(coordinator.markRunReady(lease))
        admit(coordinator, session.id, config, "old queued request", 100, AgentPromptDelivery.QUEUE)
        admit(coordinator, session.id, config, "fresh queued request", 300, AgentPromptDelivery.QUEUE)
        val first = coordinator.promoteAtBoundary(lease, true, config, 1, 100_000)!!
        val second = coordinator.promoteAtBoundary(lease, true, config, 2, 100_000)!!
        assertEquals("old queued request", first.promotedInputs.single().text)
        assertEquals(100L, first.requestAdmittedAtNanos)
        assertEquals("fresh queued request", second.promotedInputs.single().text)
        assertEquals(300L, second.requestAdmittedAtNanos)
    }

    @Test fun restoredOrUnknownOriginalMakesMixedBatchUntrusted() = fixture { root, config ->
        val sessions = SessionManager(root); val session = sessions.create()
        val coordinator = AgentPromptCoordinator(sessions, 100_000, 100)
        coordinator.load(emptyList(), session.id)
        var lease = coordinator.reserveDirectRun(); assertTrue(coordinator.markRunReady(lease))
        admit(coordinator, session.id, config, "restored original", 100)
        val persisted = sessions.getForUpdate(session.id)!!.pendingInputs
        coordinator.load(persisted, session.id)
        lease = coordinator.reserveDirectRun(); assertTrue(coordinator.markRunReady(lease))
        admit(coordinator, session.id, config, "fresh user", 300)
        val batch = coordinator.promoteAtBoundary(lease, false, config, 1, 100_000)!!
        assertEquals(2, batch.promotedInputs.size)
        assertNull(batch.requestAdmittedAtNanos)
    }

    @Test fun explicitUnknownAndSyntheticInputsNeverReceiveFreshUserAuthority() = fixture { root, config ->
        val sessions = SessionManager(root); val session = sessions.create()
        val coordinator = AgentPromptCoordinator(sessions, 100_000, 100, admissionClockNanos = { 999 })
        coordinator.load(emptyList(), session.id)
        val lease = coordinator.reserveDirectRun(); assertTrue(coordinator.markRunReady(lease))
        admit(coordinator, session.id, config, "unknown original", null)
        assertNull(coordinator.promoteAtBoundary(lease, false, config, 1, 100_000)!!.requestAdmittedAtNanos)
        admit(coordinator, session.id, config, "synthetic completion", 500, origin = AgentInputOrigin.SUBAGENT)
        assertNull(coordinator.promoteAtBoundary(lease, false, config, 2, 100_000)!!.requestAdmittedAtNanos)
    }

    @Test fun failedPromotionPreparationKeepsTheOriginalAdmissionForRetry() = fixture { root, config ->
        val sessions = SessionManager(root); val session = sessions.create()
        val coordinator = AgentPromptCoordinator(sessions, 100_000, 100)
        coordinator.load(emptyList(), session.id)
        val lease = coordinator.reserveDirectRun(); assertTrue(coordinator.markRunReady(lease))
        admit(coordinator, session.id, config, "accepted user request", 100)
        try {
            coordinator.promoteAtBoundary(lease, false, config, 1, 100_000, prepare = { throw java.io.IOException("prepare failed") })
            fail("Expected failed preparation")
        } catch (_: java.io.IOException) { }
        assertEquals(1, coordinator.pendingInputs.value.size)
        assertEquals(100L, coordinator.promoteAtBoundary(lease, false, config, 2, 100_000)!!.requestAdmittedAtNanos)
    }

    @Test(timeout = 30_000) fun cancellationAfterDurableInboxCommitRetainsOriginalAdmissionInSameProcess() = fixture { root, config ->
        val sessions = SessionManager(root); val session = sessions.create()
        val inbox = AgentPromptInbox(sessions)
        inbox.load(emptyList(), session.id)
        val lease = inbox.reserveDirectRun(); assertTrue(inbox.markReady(lease))
        val dispatcher = ReturnBarrierDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holdingWrite = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            sessions.update(session.id) { current -> entered.complete(Unit); release.await(); current!! }
        }
        entered.await()
        try {
            val submission = scope.async {
                inbox.routeSubmission(session.id, "durably accepted request", AgentPromptDelivery.STEER, config,
                    requestAdmittedAtNanos = 123L)
            }
            dispatcher.next().run()
            release.complete(Unit); holdingWrite.await()
            val returning = dispatcher.next()
            assertEquals(1, sessions.getForUpdate(session.id)!!.pendingInputs.size)
            assertEquals(1, inbox.inputs.value.size)
            submission.cancel(CancellationException("cancel at durable IO return"))
            returning.run()
            withTimeout(5_000) { submission.join() }
            val promoted = inbox.takeSteers(lease, config).single()
            assertEquals(123L, promoted.requestAdmittedAtNanos)
            assertEquals(config, promoted.runtimeConfig)
        } finally { release.complete(Unit); holdingWrite.cancel(); scope.cancel() }
    }

    @Test fun boundedInboxRetainsAdmissionEvidenceAndClearCannotRestoreIt() = fixture { root, config ->
        val sessions = SessionManager(root); val session = sessions.create()
        val inbox = AgentPromptInbox(sessions)
        inbox.load(emptyList(), session.id)
        var lease = inbox.reserveDirectRun(); assertTrue(inbox.markReady(lease))
        repeat(AgentPromptInbox.MAX_PENDING_INPUTS) { index ->
            inbox.routeSubmission(session.id, "request-$index", AgentPromptDelivery.STEER, config,
                requestAdmittedAtNanos = index.toLong())
        }
        try {
            inbox.routeSubmission(session.id, "over limit", AgentPromptDelivery.STEER, config, requestAdmittedAtNanos = 99)
            fail("Inbox bound must reject rather than evict evidence")
        } catch (_: IllegalArgumentException) { }
        val retained = inbox.inputs.value
        val promoted = inbox.takeSteers(lease, config)
        assertEquals(AgentPromptInbox.MAX_PENDING_INPUTS, promoted.size)
        assertEquals((0 until AgentPromptInbox.MAX_PENDING_INPUTS).map { it.toLong() }, promoted.map { it.requestAdmittedAtNanos })
        inbox.clear(); inbox.load(retained, session.id)
        lease = inbox.reserveDirectRun(); assertTrue(inbox.markReady(lease))
        assertTrue(inbox.takeSteers(lease, config).all { it.requestAdmittedAtNanos == null })
    }

    private suspend fun CoroutineScope.admit(coordinator: AgentPromptCoordinator, sessionId: String, config: AgentConfig,
        text: String, accepted: Long?, delivery: AgentPromptDelivery = AgentPromptDelivery.STEER,
        origin: AgentInputOrigin = AgentInputOrigin.USER) {
        coordinator.submit(this, text, config, delivery, 3, { sessionId }, { error("Already running") },
            origin = origin, reportError = { check(it == null) { it.orEmpty() } }, requestAdmittedAtNanos = accepted)!!.join()
    }
    private class ReturnBarrierDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun next(): Runnable = checkNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Expected local IO continuation" }
    }
    private fun fixture(block: suspend CoroutineScope.(File, AgentConfig) -> Unit) {
        val root = Files.createTempDirectory("prompt-admission-evidence-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http ->
                runBlocking { block(root, http.config) }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
