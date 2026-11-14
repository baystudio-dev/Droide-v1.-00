package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.baystudio.droide.core.DiskFileVersion
import com.baystudio.droide.core.EditorRecoveryCipher
import com.baystudio.droide.core.EditorRecoverySnapshot
import com.baystudio.droide.core.EditorStateStore
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.WorkspaceFailureReason
import com.baystudio.droide.core.WorkspaceHandoffAdmission
import com.baystudio.droide.core.WorkspaceHandoffGate
import com.baystudio.droide.core.WorkspaceHandoffRetention
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Fake native callbacks and tiny identity-cipher fixtures; no native editor, IME, PTY, or device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorNativeHandoffOwnershipTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 15_000)
    fun freezesOnlyAtFinalAdmissionAfterCheckpointAndSelectedIdIntervals() = runBlocking {
        val f = fixture()
        val gate = WorkspaceHandoffGate()
        val checkpoint = IoGate()
        f.cipher.beforeEncrypt = checkpoint::enter
        val preparing = async(start = CoroutineStart.UNDISPATCHED) {
            prepareEditorProjectHandoff(f.state, f.store, gate)
        }
        val admission = try {
            checkpoint.awaitEntered()
            assertTrue(f.document.canEdit)
            assertFalse(f.native.frozen)
            assertEquals(0, f.native.begins)
            f.state.requireMutableOwner()
            checkpoint.release()
            preparing.await()
        } finally {
            checkpoint.release()
            f.cipher.beforeEncrypt = null
        }
        // Represent the selected-ID writer's suspension without starting a coordinator or IO worker.
        val selectedIdReturned = CompletableDeferred<Unit>()
        val bOwner = Any()
        val switching = async(start = CoroutineStart.UNDISPATCHED) {
            selectedIdReturned.await()
            admission()
            admission.commit()
            gate.publishOwner(bOwner)
        }
        assertFalse(switching.isCompleted)
        assertTrue(f.document.canEdit)
        assertFalse(f.native.frozen)
        assertEquals(0, f.native.begins)
        f.state.requireMutableOwner()
        selectedIdReturned.complete(Unit)
        switching.await()
        assertFalse(f.document.canEdit)
        assertTrue(f.native.frozen)
        assertEquals(1, f.native.begins)
        assertEquals(1, f.native.commits)
        assertSame(bOwner, gate.publicationFor(admission as WorkspaceHandoffRetention))
        f.native.release()
        gate.requireClear()
        assertEquals("saved A", f.source.readText())
    }

    @Test(timeout = 15_000)
    fun abortRestoresExactNativeModelAndStoreButCannotUndoANewerAdmission() = runBlocking {
        val f = fixture()
        val gate = WorkspaceHandoffGate()
        val originalOwner = f.state.projectHandoffOwner()
        val first = prepareEditorProjectHandoff(f.state, f.store, gate)
        first()
        assertFalse(f.document.canEdit)
        assertTrue(f.native.frozen)
        rejected { f.store.save(emptySnapshot()) }
        first.abort()
        first.abort()
        assertEquals(originalOwner, f.state.projectHandoffOwner())
        assertSame(f.native.owner, f.state.nativeForProjectHandoff()!!.owner)
        assertTrue(f.document.canEdit)
        assertFalse(f.native.frozen)
        assertEquals(1, f.native.aborts)
        f.state.requireMutableOwner()
        f.store.save(emptySnapshot())
        gate.requireClear()

        val second = prepareEditorProjectHandoff(f.state, f.store, gate)
        second()
        first.abort()
        assertFalse(f.document.canEdit)
        assertTrue(f.native.frozen)
        rejected { f.store.save(emptySnapshot()) }
        second.abort()
        assertTrue(f.document.canEdit)
        assertEquals(originalOwner, f.state.projectHandoffOwner())
        f.store.save(emptySnapshot())
        gate.requireClear()
    }

    @Test(timeout = 15_000)
    fun reviewModeDoesNotDiscardAlreadyDeliveredNativeText() = runBlocking {
        val f = fixture()
        f.document.setReviewMode(true)
        assertFalse(f.document.canEdit)
        f.native.text = "native bytes delivered in review mode"
        f.state.captureForProjectHandoff()
        assertEquals(f.native.text, f.document.content)
        assertTrue(f.document.dirty)
        assertFalse(f.document.canEdit)
        rejected { prepareEditorProjectHandoff(f.state, f.store) }
        assertEquals(0, f.native.begins)
        assertEquals(0, f.cipher.encryptions)
        assertEquals("saved A", f.source.readText())
    }

    @Test(timeout = 15_000)
    fun lateRawReleaseDeltaKeepsRetentionUntilTheExactStoreAcknowledges() = runBlocking {
        withPreservationScope { scope ->
            val f = fixture()
            val gate = WorkspaceHandoffGate()
            val bOwner = Any()
            val baseline = f.document.savedDiskVersion
            val admission = commit(f, gate, bOwner, scope)
            val priorBytes = f.manifest.readBytes()
            val acknowledgement = IoGate()
            f.afterSync = { directory ->
                if (directory == f.manifest.parentFile && !f.manifest.readBytes().contentEquals(priorBytes)) {
                    acknowledgement.enter()
                }
            }
            try {
                // The model remains clean until release actually captures the raw native delta.
                f.native.text = "raw A delta at release"
                assertEquals("saved A", f.document.content)
                assertFalse(f.document.canEdit)
                f.native.release()
                acknowledgement.awaitEntered()
                assertEquals("raw A delta at release", f.document.content)
                assertEquals(baseline, f.document.savedDiskVersion)
                assertFalse(f.manifest.readBytes().contentEquals(priorBytes))
                assertSame(bOwner, gate.publicationFor(admission as WorkspaceHandoffRetention))
                assertTrue(runCatching { gate.requireClear() }.isFailure)
                acknowledgement.release()
                awaitPreservation(scope)
                gate.requireClear()
                val saved = f.store.load()!!
                assertEquals("raw A delta at release", saved.deferredBuffers.getValue(PATH).readText())
                assertEquals(baseline, saved.diskVersions[PATH])
                rejected { f.store.save(emptySnapshot()) }
                assertEquals("saved A", f.source.readText())
            } finally {
                acknowledgement.release()
                f.afterSync = null
            }
        }
    }

    @Test(timeout = 15_000)
    fun ordinaryUnchangedReleaseClearsImmediatelyWithoutAnotherCheckpoint() = runBlocking {
        val f = fixture()
        val gate = WorkspaceHandoffGate()
        val bOwner = Any()
        commit(f, gate, bOwner)
        val before = f.cipher.encryptions
        assertTrue(runCatching { gate.requireClear() }.isFailure)
        f.native.release()
        gate.requireClear()
        assertEquals(before, f.cipher.encryptions)
        gate.retryPending(bOwner)
        assertEquals(before, f.cipher.encryptions)
        assertFalse(f.document.canEdit)
    }

    @Test(timeout = 15_000)
    fun initialFailureAndRepeatedExplicitRetriesRetainImmutableAAndUnchangedBOwnership() = runBlocking {
        withPreservationScope { scope ->
            val a = fixture()
            val b = fixture("saved B")
            b.store.save(emptySnapshot())
            val bCheckpoint = b.manifest.readBytes()
            val baseline = a.document.savedDiskVersion
            val conflictBaseline = DiskFileVersion.fromText("original conflict baseline")
            a.state.retainRecoveryConflict(CONFLICT, "older A recovery", null, conflictBaseline)
            val gate = WorkspaceHandoffGate()
            val failures = mutableListOf<Throwable>()
            val admission = commit(a, gate, b.state, scope) { failures += it } as WorkspaceHandoffRetention
            a.cipher.failuresRemaining = 2
            val initialCalls = a.cipher.encryptions
            a.native.text = "immutable A release snapshot"
            a.native.release()
            awaitPreservation(scope)
            assertEquals(initialCalls + 1, a.cipher.encryptions)
            assertTrue(failures.isNotEmpty())
            assertSame(b.state, gate.publicationFor(admission))
            assertTrue(runCatching { gate.requireClear() }.isFailure)
            assertEquals("immutable A release snapshot", a.document.content)
            assertEquals(baseline, a.document.savedDiskVersion)
            assertTrue(b.document.canEdit)
            assertArrayEquals(bCheckpoint, b.manifest.readBytes())

            // A later observation must not replace the one release snapshot retained for retry.
            a.document.captureNativeText("later observation must not rewrite retained snapshot")
            rejected { gate.retryPending(b.state) }
            assertEquals(initialCalls + 2, a.cipher.encryptions)
            assertSame(b.state, gate.publicationFor(admission))
            assertArrayEquals(bCheckpoint, b.manifest.readBytes())
            gate.retryPending(b.state)
            gate.requireClear()
            val saved = a.store.load()!!
            assertEquals("immutable A release snapshot", saved.deferredBuffers.getValue(PATH).readText())
            assertEquals(baseline, saved.diskVersions[PATH])
            assertEquals("older A recovery", saved.deferredConflictBuffers.getValue(CONFLICT).readText())
            assertEquals(conflictBaseline, saved.conflictDiskVersions[CONFLICT])
            assertTrue(b.document.canEdit)
            assertEquals("saved B", b.document.content)
            assertArrayEquals(bCheckpoint, b.manifest.readBytes())
            assertEquals("saved A", a.source.readText())
            assertEquals("saved B", b.source.readText())
            val completedCalls = a.cipher.encryptions
            gate.retryPending(b.state)
            assertEquals("No retry remains after acknowledgement", completedCalls, a.cipher.encryptions)
        }
    }

    @Test(timeout = 15_000)
    fun concurrentExplicitRetryIsRejectedWithoutStartingAnotherWrite() = runBlocking {
        val f = fixture()
        val gate = WorkspaceHandoffGate()
        val bOwner = Any()
        commit(f, gate, bOwner)
        f.native.text = "pending A snapshot"
        f.native.release()
        val io = IoGate()
        f.cipher.beforeEncrypt = io::enter
        val retry = async(start = CoroutineStart.UNDISPATCHED) { gate.retryPending(bOwner) }
        try {
            io.awaitEntered()
            val calls = f.cipher.encryptions
            rejected { gate.retryPending(bOwner) }
            assertEquals(calls, f.cipher.encryptions)
            assertTrue(runCatching { gate.requireClear() }.isFailure)
            io.release()
            retry.await()
            gate.requireClear()
            assertEquals("pending A snapshot", f.store.load()!!.deferredBuffers.getValue(PATH).readText())
        } finally {
            io.release()
            f.cipher.beforeEncrypt = null
        }
    }

    @Test(timeout = 15_000)
    fun incorrectPublishedOwnerCannotRetryOrAcknowledgeThePendingSnapshot() = runBlocking {
        val f = fixture()
        val gate = WorkspaceHandoffGate()
        val bOwner = Any()
        val admission = commit(f, gate, bOwner) as WorkspaceHandoffRetention
        rejected { gate.retryPending(bOwner) }
        f.native.text = "A retained for B"
        f.native.release()
        val before = f.cipher.encryptions
        val incorrectOwner = Any()
        rejected { gate.retryPending(incorrectOwner) }
        assertFalse(gate.acknowledgePreservation(admission, incorrectOwner))
        assertEquals(before, f.cipher.encryptions)
        assertSame(bOwner, gate.publicationFor(admission))
        gate.retryPending(bOwner)
        gate.requireClear()
    }

    @Test(timeout = 15_000)
    fun publicationChangeDuringSaveRejectsOldAcknowledgementAndRetainsTheSnapshot() = runBlocking {
        val f = fixture()
        val gate = WorkspaceHandoffGate()
        val bOwner = Any()
        val cOwner = Any()
        val lateReports = mutableListOf<Throwable>()
        val admission = commit(f, gate, bOwner, failures = { lateReports += it }) as WorkspaceHandoffRetention
        f.native.text = "A snapshot across owner change"
        f.native.release()
        val io = IoGate()
        f.cipher.beforeEncrypt = io::enter
        val retry = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { gate.retryPending(bOwner) }.exceptionOrNull()
        }
        try {
            io.awaitEntered()
            gate.publishOwner(cOwner)
            io.release()
            assertTrue(retry.await() is IllegalStateException)
            assertTrue("A stale B failure must not report against C", lateReports.isEmpty())
            assertSame(cOwner, gate.publicationFor(admission))
            assertTrue(runCatching { gate.requireClear() }.isFailure)
            rejected { gate.retryPending(bOwner) }
            // Successful storage alone must not have discarded the retained release snapshot.
            f.document.captureNativeText("later observation after the rejected ACK")
            gate.retryPending(cOwner)
            gate.requireClear()
            assertEquals("A snapshot across owner change", f.store.load()!!.deferredBuffers.getValue(PATH).readText())
        } finally {
            io.release()
            f.cipher.beforeEncrypt = null
        }
    }

    @Test(timeout = 15_000)
    fun staleOldAcknowledgementCannotClearANewerRetainedSlot() = runBlocking {
        val f = fixture()
        val gate = WorkspaceHandoffGate()
        val bOwner = Any()
        val lateReports = mutableListOf<Throwable>()
        val old = commit(f, gate, bOwner, failures = { lateReports += it }) as WorkspaceHandoffRetention
        val oldNoticeCurrent = requireNotNull(gate.noticeValidityFor(old, bOwner))
        assertTrue(oldNoticeCurrent())
        f.native.text = "old A release snapshot"
        f.native.release()
        val io = IoGate()
        f.cipher.beforeEncrypt = io::enter
        val retry = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { gate.retryPending(bOwner) }.exceptionOrNull()
        }
        var newerRetries = 0
        val newer = object : WorkspaceHandoffRetention {
            override suspend fun retryPreservation() { newerRetries++; gate.clear(this) }
        }
        val cOwner = Any()
        try {
            io.awaitEntered()
            // Exercise the gate's identity boundary even if a caller replaces its slot.
            gate.clear(old)
            gate.reserve(newer)
            gate.publishOwner(cOwner)
            assertFalse(oldNoticeCurrent())
            val newNoticeCurrent = requireNotNull(gate.noticeValidityFor(newer, cOwner))
            assertTrue(newNoticeCurrent())
            io.release()
            assertTrue(retry.await() is IllegalStateException)
            assertTrue("An old slot must not publish a failure for its replacement", lateReports.isEmpty())
            assertSame(cOwner, gate.publicationFor(newer))
            assertNull(gate.publicationFor(old))
            assertFalse(gate.acknowledgePreservation(old, bOwner))
            gate.clear(old)
            assertTrue(runCatching { gate.requireClear() }.isFailure)
            gate.retryPending(cOwner)
            assertEquals(1, newerRetries)
            gate.requireClear()
            assertFalse(newNoticeCurrent())
            assertFalse(oldNoticeCurrent())
        } finally {
            io.release()
            f.cipher.beforeEncrypt = null
        }
    }

    @Test(timeout = 15_000)
    fun incompleteNativeCaptureBlocksPreparationWithoutSealingOrWriting() = runBlocking {
        val f = fixture()
        f.native.captureIncomplete = true
        rejected { prepareEditorProjectHandoff(f.state, f.store) }
        assertEquals(0, f.cipher.encryptions)
        assertEquals(0, f.native.begins)
        assertFalse(f.native.frozen)
        assertTrue(f.document.canEdit)
        assertFalse(f.manifest.exists())
        assertEquals("saved A", f.source.readText())
    }

    @Test(timeout = 15_000)
    fun incompleteReleaseCaptureKeepsRetentionAndBlocksEveryExplicitRetry() = runBlocking {
        val f = fixture()
        val gate = WorkspaceHandoffGate()
        val bOwner = Any()
        val failures = mutableListOf<Throwable>()
        val reasons = mutableListOf<WorkspaceFailureReason>()
        val validity = mutableListOf<() -> Boolean>()
        val admission = commit(f, gate, bOwner,
            notice = { reason, isCurrent -> reasons += reason; validity += isCurrent },
            failures = { failures += it }) as WorkspaceHandoffRetention
        val prior = f.manifest.readBytes()
        val before = f.cipher.encryptions
        f.native.text = "uncaptured native bytes"
        f.native.captureIncomplete = true
        f.native.release()
        assertTrue(failures.isNotEmpty())
        assertEquals(listOf(WorkspaceFailureReason.EDITOR_CAPTURE_BLOCKED), reasons)
        assertTrue(validity.single()())
        rejected { gate.retryPending(bOwner) }
        rejected { gate.retryPending(bOwner) }
        assertSame(bOwner, gate.publicationFor(admission))
        assertTrue(runCatching { gate.requireClear() }.isFailure)
        assertEquals(before, f.cipher.encryptions)
        assertArrayEquals(prior, f.manifest.readBytes())
        assertEquals("uncaptured native bytes", f.native.text)
        assertEquals("saved A", f.source.readText())
    }

    @Test(timeout = 15_000)
    fun actionableNoticeIsBoundToTheExactPendingOwnerAndOneExplicitAttempt() = runBlocking {
        withPreservationScope { scope ->
            val f = fixture()
            val gate = WorkspaceHandoffGate()
            val bOwner = Any()
            val reasons = mutableListOf<WorkspaceFailureReason>()
            val validity = mutableListOf<() -> Boolean>()
            commit(f, gate, bOwner, scope,
                notice = { reason, isCurrent -> reasons += reason; validity += isCurrent })
            f.cipher.failuresRemaining = 2
            f.native.text = "pending A memory-only snapshot"
            f.native.release()
            awaitPreservation(scope)
            assertEquals(listOf(WorkspaceFailureReason.EDITOR_PRESERVATION_RETRY), reasons)
            assertTrue(validity.single()())
            f.native.release()
            assertEquals("Duplicate release cannot flood the notice", 1, reasons.size)
            rejected { gate.retryPending(bOwner) }
            assertEquals("One explicit failed retry permits one new notice", 2, reasons.size)
            assertTrue(reasons.all { it == WorkspaceFailureReason.EDITOR_PRESERVATION_RETRY })
            assertTrue(validity.all { it() })
            gate.retryPending(bOwner)
            gate.requireClear()
            assertTrue("Acknowledged notices must not be shown later", validity.none { it() })
            assertEquals("pending A memory-only snapshot", f.store.load()!!.deferredBuffers.getValue(PATH).readText())
            assertEquals("saved A", f.source.readText())
        }
    }

    private suspend fun commit(
        f: Fixture, gate: WorkspaceHandoffGate, nextOwner: Any,
        scope: CoroutineScope? = null,
        notice: (WorkspaceFailureReason, () -> Boolean) -> Unit = { _, _ -> },
        failures: (Throwable) -> Unit = {},
    ): WorkspaceHandoffAdmission {
        val admission = prepareEditorProjectHandoff(f.state, f.store, gate, scope) { reason, failure, isCurrent ->
            notice(reason, isCurrent)
            failures(failure)
        }
        admission()
        admission.commit()
        // Match the coordinator's required order before treating nextOwner as published.
        gate.publishOwner(nextOwner)
        return admission
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        assertTrue("Expected an ownership/capture rejection", runCatching { block() }.isFailure)
    }

    private suspend fun withPreservationScope(block: suspend (CoroutineScope) -> Unit) = coroutineScope {
        val job = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + job)
        try { block(scope) } finally { job.cancelAndJoin() }
    }

    private suspend fun awaitPreservation(scope: CoroutineScope) {
        checkNotNull(scope.coroutineContext[Job]).children.toList().joinAll()
    }

    private class IoGate {
        private val entered = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)
        fun enter() {
            entered.complete(Unit)
            check(released.await(5, TimeUnit.SECONDS)) { "Fixture IO was not released" }
        }
        suspend fun awaitEntered() = withTimeout(5_000) { entered.await() }
        fun release() { released.countDown() }
    }

    private class IdentityCipher : EditorRecoveryCipher {
        @Volatile var encryptions = 0
        @Volatile var failuresRemaining = 0
        @Volatile var beforeEncrypt: (() -> Unit)? = null
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            encryptions++
            beforeEncrypt?.invoke()
            if (failuresRemaining > 0) {
                failuresRemaining--
                throw IOException("Fixture recovery write refused")
            }
            return plain.copyOf()
        }
        override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray = encrypted.copyOf()
    }

    private class FakeNative(private val document: EditorDocument) {
        val owner = Any()
        var text = document.content
        var captureIncomplete = false
        var frozen = false
            private set
        var begins = 0
            private set
        var commits = 0
            private set
        var aborts = 0
            private set
        private var lease: Lease? = null
        val binding = EditorNativeHandoffBinding(owner, ::begin) {
            check(!captureIncomplete) { "Fixture native capture is incomplete" }
        }
        fun capture() {
            check(!captureIncomplete) { "Fixture native capture is incomplete" }
            document.captureNativeText(text)
        }
        private fun begin(onReleased: (Throwable?) -> Unit): EditorNativeHandoffLease {
            check(lease == null) { "Fixture already owns a native lease" }
            begins++
            frozen = true
            return Lease(onReleased).also { lease = it }
        }
        fun release() {
            val current = checkNotNull(lease)
            val failure = runCatching { capture() }.exceptionOrNull()
            current.onReleased(failure)
        }
        private inner class Lease(val onReleased: (Throwable?) -> Unit) : EditorNativeHandoffLease {
            private var committed = false
            override fun commit() {
                if (lease === this && !committed) { committed = true; commits++ }
            }
            override fun abort() {
                if (lease === this && !committed) {
                    lease = null
                    frozen = false
                    aborts++
                }
            }
        }
    }

    private class Fixture(
        val source: File, val state: EditorWorkspaceState, val document: EditorDocument,
        val store: EditorStateStore, val cipher: IdentityCipher, val manifest: File, val native: FakeNative,
    ) {
        @Volatile var afterSync: ((File) -> Unit)? = null
    }

    private suspend fun fixture(text: String = "saved A"): Fixture {
        val app = temp.newFolder()
        val project = File(app, "projects/A").apply { check(mkdirs()) }
        val source = File(project, PATH).apply { writeText(text) }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = app
        }
        val cipher = IdentityCipher()
        lateinit var fixture: Fixture
        val store = EditorStateStore(context, "A", cipher) { directory ->
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
            fixture.afterSync?.invoke(directory)
        }
        val state = EditorWorkspaceState()
        val document = state.document(PATH)
        document.ensureLoaded(FileRepository(project))
        state.retainNavigation(listOf(PATH), PATH, 1)
        state.completeRecovery()
        val native = FakeNative(document)
        state.bindProjectHandoffCapture(native::capture) { native.binding }
        fixture = Fixture(source, state, document, store, cipher,
            File(app, ".droide/editor-recovery/A.bin"), native)
        return fixture
    }

    private fun emptySnapshot() = EditorRecoverySnapshot(emptyList(), "", emptyMap())

    companion object {
        private const val PATH = "keep.txt"
        private const val CONFLICT = "older-copy.txt"
    }
}
