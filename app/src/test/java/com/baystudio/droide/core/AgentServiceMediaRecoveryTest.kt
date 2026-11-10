package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Focused source regressions; the coordinator owns execution. */
class AgentServiceMediaRecoveryTest {
    private val admissionState = ApplicationWorkAdmission::class.java.getDeclaredField("state").apply { isAccessible = true }
    private var previousAdmission: Any? = null

    @org.junit.Before fun openAuthoredUserLaunch() {
        previousAdmission = admissionState.get(null)
        val state = ApplicationWorkAdmissionState(
            read = { ApplicationWorkAdmissionState.Snapshot("c".repeat(32), true) }, write = {})
        state.initialize()
        admissionState.set(null, state)
    }

    @org.junit.After fun restoreApplicationAdmission() {
        admissionState.set(null, previousAdmission)
    }

    @Test fun onlyPrimaryHumanAdmissionReadsTheOriginalClock() {
        var calls = 0
        fun captured(origin: AgentInputOrigin = AgentInputOrigin.USER, depth: Int = 0, overlay: String = "",
            kind: AgentRequesterKind = AgentRequesterKind.PRIMARY) = AgentService.MediaRequestAdmission.capture(origin, depth, overlay, kind) {
                calls++; 123L
            }
        assertEquals(123L, captured())
        assertNull(captured(origin = AgentInputOrigin.SUBAGENT))
        assertNull(captured(depth = 1))
        assertNull(captured(overlay = "automated system task"))
        assertNull(captured(kind = AgentRequesterKind.SUBAGENT))
        assertEquals(1, calls)
    }

    @Test fun freshSteerCannotRefreshOldRunAndOlderQueueCanOnlyLowerItsAdmission() {
        val running = AgentService.MediaRequestAdmission(100)
        running.includePromoted(300)
        assertEquals(100L, running.admittedAtNanos)
        running.includePromoted(50)
        assertEquals(50L, running.admittedAtNanos)
        running.includePromoted(500)
        assertEquals(50L, running.admittedAtNanos)
    }

    @Test fun restoredOrSyntheticWorkDegradesAuthorityAndFreshSteerCannotRestoreIt() {
        val running = AgentService.MediaRequestAdmission(300)
        running.includePromoted(null)
        running.includePromoted(500)
        assertNull(running.admittedAtNanos)
        val unknownOriginal = AgentService.MediaRequestAdmission(null)
        unknownOriginal.includePromoted(500)
        assertNull(unknownOriginal.admittedAtNanos)
    }

    @Test fun oldActiveAndQueuedRequestsCannotUseGrantButFreshSeparateUserRunCan() = runBlocking {
        val root = Files.createTempDirectory("service-admission-grant-").toFile()
        var now = 100L
        val journal = ProjectCopyJournal(root, admissionClockNanos = { now })
        try {
            val owner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, "session", "old-run", "media")
            val old = unknown(journal, root, owner, "asset.bin")
            val scope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, "session")
            now = 200
            journal.acknowledge(scope, journal.inspect(scope, old.intent.id).token)
            now = 300
            val running = AgentService.MediaRequestAdmission(100)
            running.includePromoted(300)
            fails { journal.begin(owner.copy(runId = "already-running"), "asset.bin", running.admittedAtNanos) }
            val newRunWithOldQueue = AgentService.MediaRequestAdmission(300)
            newRunWithOldQueue.includePromoted(100)
            fails { journal.begin(owner.copy(runId = "queued-before-grant"), "asset.bin", newRunWithOldQueue.admittedAtNanos) }
            journal.begin(owner.copy(runId = "fresh-manual-run"), "asset.bin", AgentService.MediaRequestAdmission(300).admittedAtNanos).close()
            assertFalse(root.resolve("asset.bin").exists())
            assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal.recovery(scope).records.single { it.intent.id == old.intent.id }.status)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun openingRecoveryDoesNotCreateOrScanAStartupJournal() = workspace { agent, root ->
        agent.loadSession(agent.sessions.create())
        assertFalse(root.resolve(".droide/copy-recovery").exists())
        val access = agent.openMediaRecovery()
        assertFalse(root.resolve(".droide/copy-recovery").exists())
        assertTrue(access.recovery().records.isEmpty())
        assertFalse(root.resolve(".droide/copy-recovery").exists())
        access.close(); access.close()
    }

    @Test fun recoveryShowsOnlyCurrentConversationAndAcknowledgementPerformsNoCopy() = workspace { agent, root ->
        val session = agent.sessions.create(); agent.loadSession(session)
        val journal = ProjectCopyJournal(root)
        val own = unknown(journal, root, ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, session.id, "old", "media"), "own.bin")
        unknown(journal, root, ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, "foreign-session", "old", "foreign"), "foreign.bin")
        unknown(journal, root, ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null, "picker", ProjectCopyJournal.explorerSourceId("content://example/file")), "import.bin")
        val access = agent.openMediaRecovery()
        try {
            assertEquals(listOf(own.intent.id), access.recovery().records.map { it.intent.id })
            access.acknowledge(access.inspect(own.intent.id).token)
            assertFalse(root.resolve("own.bin").exists())
            assertFalse(root.resolve("foreign.bin").exists())
            assertFalse(root.resolve("import.bin").exists())
            assertEquals(ProjectCopyJournal.Status.UNKNOWN, access.recovery().records.single().status)
        } finally { access.close() }
    }

    @Test fun inspectionTokenCannotMoveToAnotherViewAndCloseInvalidatesOldAccess() = workspace { agent, root ->
        val session = agent.sessions.create(); agent.loadSession(session)
        val journal = ProjectCopyJournal(root)
        val old = unknown(journal, root, ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, session.id, "old", "media"), "asset.bin")
        val first = agent.openMediaRecovery(); val other = agent.openMediaRecovery()
        val token = first.inspect(old.intent.id).token
        fails { other.acknowledge(token) }
        first.close(); first.close()
        fails { first.recovery() }
        fails { first.inspect(old.intent.id) }
        fails { first.acknowledge(token) }
        assertFalse(root.resolve("asset.bin").exists())
        other.close()
    }

    @Test fun sameConversationRestoredAgainCannotUsePriorGenerationInspection() = workspace { agent, root ->
        val session = agent.sessions.create(); agent.loadSession(session)
        val journal = ProjectCopyJournal(root)
        val old = unknown(journal, root, ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, session.id, "old", "media"), "asset.bin")
        val access = agent.openMediaRecovery(); val token = access.inspect(old.intent.id).token
        agent.loadSession(session)
        fails { access.acknowledge(token) }
        fails { access.recovery() }
        val current = agent.openMediaRecovery()
        fails { current.acknowledge(token) }
        assertFalse(root.resolve("asset.bin").exists())
        access.close(); current.close()
    }

    @Test fun shutdownInvalidatesRecoveryBeforeAnyFurtherAcknowledgement() = workspace { agent, root ->
        val session = agent.sessions.create(); agent.loadSession(session)
        val journal = ProjectCopyJournal(root)
        val old = unknown(journal, root, ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, session.id, "old", "media"), "asset.bin")
        val access = agent.openMediaRecovery(); val token = access.inspect(old.intent.id).token
        agent.shutdown()
        fails { access.acknowledge(token) }
        fails { agent.openMediaRecovery() }
        assertFalse(root.resolve("asset.bin").exists())
        access.close()
    }

    @Test fun startupReservationDeniesRecoveryEvenBeforeBusyOrServiceJobExists() = workspace { agent, _ ->
        agent.loadSession(agent.sessions.create())
        val coordinator = AgentService::class.java.getDeclaredField("promptCoordinator").apply { isAccessible = true }.get(agent) as AgentPromptCoordinator
        assertFalse(agent.busy.value)
        val access = agent.openMediaRecovery()
        val lease = coordinator.reserveDirectRun()
        try {
            assertTrue(coordinator.hasReservedRun)
            assertFalse(agent.busy.value)
            fails { agent.openMediaRecovery() }
            fails { access.recovery() }
        } finally { coordinator.markRunInactive(lease); access.close() }
    }

    @Test fun sessionChangeAtIoReturnCannotPublishStaleRecoveryResult() = workspace { agent, root ->
        val session = agent.sessions.create(); agent.loadSession(session)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val journal = ProjectCopyJournal(root, inspectDestination = { directory, key, path ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Inspection IO was not released" }
            ProjectCopyEvidence.inspect(directory, key, path)
        })
        val old = unknown(journal, root,
            ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, session.id, "old", "media"), "asset.bin")
        val generation = (AgentService::class.java.getDeclaredField("runGeneration").apply { isAccessible = true }
            .get(agent) as java.util.concurrent.atomic.AtomicLong).get()
        val access = agent.MediaRecoveryAccess(session.id, generation, journal)
        val dispatcher = ReturnBarrierDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val read = scope.async { access.inspect(old.intent.id) }
            dispatcher.next().run()
            assertTrue("Inspection IO must suspend before its return", entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            val returning = dispatcher.next()
            agent.clear()
            returning.run()
            fails { read.await() }
            assertFalse(root.resolve("asset.bin").exists())
        } finally { release.countDown(); scope.cancel(); access.close() }
    }

    @Test fun accessCloseDoesNotWaitForOwnerMonitorHeldByAnotherOperation() = workspace { agent, _ ->
        agent.loadSession(agent.sessions.create())
        val access = agent.openMediaRecovery()
        val lock = AgentService::class.java.getDeclaredField("admissionLock").apply { isAccessible = true }.get(agent)
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val holding = async(Dispatchers.IO) { synchronized(lock) {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "Test owner monitor was not released" }
        } }
        try {
            entered.await()
            val closing = async(Dispatchers.Default) { access.close() }
            withTimeout(5_000) { closing.await() }
            assertFalse(holding.isCompleted)
        } finally { release.countDown(); holding.join(); access.close() }
        fails { access.recovery() }
    }

    @Test fun systemOverlayInstanceCannotOpenHumanMediaRecovery() = workspace { agent, root ->
        val session = agent.sessions.create()
        val synthetic = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
            maxSubagentDepth = 0, systemOverlay = "automated task")
        try {
            synthetic.loadSession(session)
            fails { synthetic.openMediaRecovery() }
        } finally { synthetic.shutdown() }
    }

    @Test fun originalAdmissionIsMergedOnlyAtAcknowledgedPromotionCommit() = runBlocking {
        val root = Files.createTempDirectory("service-admission-commit-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
                val sessions = SessionManager(root); val session = sessions.create()
                val coordinator = AgentPromptCoordinator(sessions, 100_000, 100)
                coordinator.load(emptyList(), session.id)
                val lease = coordinator.reserveDirectRun(); assertTrue(coordinator.markRunReady(lease))
                coordinator.submit(this, "old queued request", http.config, AgentPromptDelivery.QUEUE, 3,
                    { session.id }, { error("Already running") }, reportError = { check(it == null) { it.orEmpty() } },
                    requestAdmittedAtNanos = 100)!!.join()
                val admission = AgentService.MediaRequestAdmission(300)
                fails { coordinator.promoteAtBoundary(lease, true, http.config, 1, 100_000,
                    prepare = { throw java.io.IOException("prepare failed") },
                    commit = { batch, acknowledge -> acknowledge(); admission.includePromoted(batch.requestAdmittedAtNanos) }) }
                assertEquals(300L, admission.admittedAtNanos)
                assertEquals(1, coordinator.pendingInputs.value.size)
                coordinator.promoteAtBoundary(lease, true, http.config, 2, 100_000,
                    prepare = { assertEquals(300L, admission.admittedAtNanos) },
                    commit = { batch, acknowledge ->
                        acknowledge()
                        admission.includePromoted(batch.requestAdmittedAtNanos)
                    })
                assertEquals(100L, admission.admittedAtNanos)
                assertTrue(coordinator.pendingInputs.value.isEmpty())
            } }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun clearDoesNotWaitForRecoveryEvidenceAndRejectsStaleFinalGrant() = blockedEvidence { agent, _ -> agent.clear() }

    @Test fun sessionSwitchDoesNotWaitForRecoveryEvidenceAndRejectsStaleFinalGrant() = blockedEvidence { agent, _ ->
        agent.loadSession(agent.sessions.create())
    }

    @Test fun shutdownDoesNotWaitForRecoveryEvidenceAndRejectsStaleFinalGrant() = blockedEvidence { agent, _ -> agent.shutdown() }

    @Test fun closingViewDuringEvidenceReadRejectsItsFinalGrantWithoutWaiting() = blockedEvidence { _, access -> access.close() }

    private fun blockedEvidence(invalidate: suspend (AgentService, AgentService.MediaRecoveryAccess) -> Unit) = workspace { agent, root ->
        val session = agent.sessions.create(); agent.loadSession(session)
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        val journal = ProjectCopyJournal(root, inspectDestination = { directory, key, path ->
            if (block.get()) {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "Test evidence read was not released" }
            }
            ProjectCopyEvidence.inspect(directory, key, path)
        })
        val owner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, session.id, "old", "media")
        val old = unknown(journal, root, owner, "asset.bin")
        val generation = (AgentService::class.java.getDeclaredField("runGeneration").apply { isAccessible = true }
            .get(agent) as java.util.concurrent.atomic.AtomicLong).get()
        val access = agent.MediaRecoveryAccess(session.id, generation, journal)
        val token = access.inspect(old.intent.id).token
        block.set(true)
        val acknowledging = async(Dispatchers.Default) { runCatching { access.acknowledge(token) }.exceptionOrNull() }
        try {
            withTimeout(5_000) { entered.await() }
            withTimeout(5_000) { async(Dispatchers.Default) { invalidate(agent, access) }.await() }
            assertFalse("Owner invalidation must not wait for evidence IO", acknowledging.isCompleted)
        } finally { release.countDown() }
        try {
            assertNotNull(withTimeout(5_000) { acknowledging.await() })
            block.set(false)
            fails { journal.begin(owner.copy(runId = "new-run"), "asset.bin", System.nanoTime()) }
            assertFalse(root.resolve("asset.bin").exists())
        } finally { access.close() }
    }

    private fun unknown(journal: ProjectCopyJournal, root: File, owner: ProjectCopyJournal.Owner, path: String): ProjectCopyJournal.Lease {
        val lease = journal.begin(owner, path)
        lease.beforePublish(StreamPublicationPreparation(UUID.randomUUID().toString(), root.canonicalPath,
            ProjectCopyEvidence.root(root).second, path, 1, ProjectCopyEvidence.sha256(byteArrayOf(1)), "fixture-stage"))
        lease.close()
        return lease
    }
    private suspend fun fails(block: suspend () -> Unit): Throwable {
        try { block() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected conservative refusal")
    }
    private class ReturnBarrierDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun next(): Runnable = checkNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Expected local IO continuation" }
    }
    private fun workspace(block: suspend CoroutineScope.(AgentService, File) -> Unit) = runBlocking {
        val fixture = Files.createTempDirectory("service-media-recovery-").toFile()
        val root = fixture.resolve("files/projects/project").apply { mkdirs() }
        val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0)
        try { block(agent, root) } finally { agent.shutdown(); PathSecurity.deleteTreeNoFollow(fixture) }
    }
}
