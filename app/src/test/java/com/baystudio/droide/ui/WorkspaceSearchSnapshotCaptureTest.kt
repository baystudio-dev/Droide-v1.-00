package com.baystudio.droide.ui

import androidx.compose.runtime.MutableState
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSearchSnapshotCaptureTest {
    private fun budget(limits: WorkspaceSearchLimits = WorkspaceSearchLimits()) = WorkspaceSearchBudget(
        limits, EmptyCoroutineContext, WorkspaceRequestDeadline(limits.durationNanos) { 0L },
    )
    private fun authority(state: EditorWorkspaceState) = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
    private suspend fun stop(block: suspend () -> Unit): WorkspaceSearchStopReason {
        try { block() } catch (limit: WorkspaceSearchLimitException) { return limit.reason }
        throw AssertionError("Expected bounded producer admission to stop")
    }

    @Test(timeout = 15_000) fun capturesCachedDirtyDocumentAndKeepsOneRevisionTuple(): Unit = runBlocking {
        val state = EditorWorkspaceState()
        state.protectActiveEditor("active.kt")
        val cached = state.document("cached.kt")
        cached.restoreUnsaved("€𐐀")
        cached.captureSelection(1, 3)
        val revision = cached.revision
        val version = cached.changeVersion
        val work = budget(WorkspaceSearchLimits(snapshotChars = 3))
        val captured = authority(state).captureSearchSnapshots(WorkspaceSearchCaptureRequest(work)).snapshots.single()
        cached.capture("later")
        assertEquals("cached.kt", captured.path)
        assertEquals("€𐐀", captured.content)
        assertEquals(revision, captured.revision)
        assertEquals(version, captured.changeVersion)
        assertEquals(1, captured.selectionStart)
        assertEquals(3, captured.selectionEnd)
        assertTrue(captured.dirty)
        assertTrue(captured.editable)
        assertEquals(3L, work.stats.snapshotChars)
        assertEquals(7L, EditorLargeFilePolicy.utf8Bytes(captured.content))
    }

    @Test(timeout = 15_000) fun actualProducerAdmitsExactNAndStopsBeforeNextWrapper(): Unit = runBlocking {
        val state = EditorWorkspaceState()
        state.document("a.kt").restoreUnsaved("abc")
        val exact = budget(WorkspaceSearchLimits(documents = 1, snapshots = 1, snapshotChars = 3))
        assertEquals(1, authority(state).captureSearchSnapshots(WorkspaceSearchCaptureRequest(exact)).snapshots.size)
        state.document("b.kt").restoreUnsaved("def")
        val documentLimit = budget(WorkspaceSearchLimits(documents = 1))
        assertEquals(WorkspaceSearchStopReason.DOCUMENT_LIMIT, stop {
            authority(state).captureSearchSnapshots(WorkspaceSearchCaptureRequest(documentLimit))
        })
        assertEquals(1, documentLimit.stats.documentsInspected)
        assertEquals(1, documentLimit.stats.snapshotsCaptured)
        val wrapperLimit = budget(WorkspaceSearchLimits(snapshots = 1))
        assertEquals(WorkspaceSearchStopReason.SNAPSHOT_LIMIT, stop {
            authority(state).captureSearchSnapshots(WorkspaceSearchCaptureRequest(wrapperLimit))
        })
        assertEquals(2, wrapperLimit.stats.documentsInspected)
        assertEquals(1, wrapperLimit.stats.snapshotsCaptured)
        val characters = budget(WorkspaceSearchLimits(snapshotChars = 5))
        assertEquals(WorkspaceSearchStopReason.SNAPSHOT_TEXT_LIMIT, stop {
            authority(state).captureSearchSnapshots(WorkspaceSearchCaptureRequest(characters))
        })
        assertEquals(1, characters.stats.snapshotsCaptured)
        assertEquals(3L, characters.stats.snapshotChars)
    }

    @Test(timeout = 15_000) fun dirtyEqualityIsAdmittedBeforeComparingCapturedText(): Unit = runBlocking {
        val state = EditorWorkspaceState()
        val document = state.document("a.kt")
        document.restoreUnsaved("aaaa")
        document.acceptPersistedText("aaaa", "fixture baseline")
        document.applyWorkspaceText("aaab") // Keeps optimizedBufferDirty false: dirty requires equality.
        val short = budget(WorkspaceSearchLimits(dirtyComparisonUnits = 3))
        assertEquals(WorkspaceSearchStopReason.DIRTY_COMPARISON_LIMIT, stop {
            authority(state).captureSearchSnapshots(WorkspaceSearchCaptureRequest(short))
        })
        assertEquals(0L, short.stats.dirtyComparisonUnits)
        assertEquals(0, short.stats.snapshotsCaptured)
        val exact = budget(WorkspaceSearchLimits(dirtyComparisonUnits = 4))
        val captured = authority(state).captureSearchSnapshots(WorkspaceSearchCaptureRequest(exact)).snapshots.single()
        assertTrue(captured.dirty)
        assertEquals("aaab", captured.content)
        assertEquals(4L, exact.stats.dirtyComparisonUnits)
    }

    @Test(timeout = 15_000) fun pathsAreAdmittedBeforeSnapshotConstruction(): Unit = runBlocking {
        val state = EditorWorkspaceState()
        state.document("long.kt").restoreUnsaved("x")
        val work = budget(WorkspaceSearchLimits(pathChars = 6))
        assertEquals(WorkspaceSearchStopReason.SNAPSHOT_PATH_LIMIT, stop {
            authority(state).captureSearchSnapshots(WorkspaceSearchCaptureRequest(work))
        })
        assertEquals(0, work.stats.snapshotsCaptured)
        assertEquals(0L, work.stats.snapshotChars)
    }

    @Test(timeout = 15_000) fun deferredNativePendingAndReviewBuffersStayUnavailableAndSuppressed(): Unit = runBlocking {
        val root = Files.createTempDirectory("droide-search-pending-").toFile()
        try {
            val state = EditorWorkspaceState()
            var recoveryReads = 0
            state.document("deferred.kt").restoreDeferred(
                EditorRecoveryBuffer("deferred.kt", "fixture.bin", null, Any()) { recoveryReads++; "needle recovered" },
                EditorSelectionSnapshot(3, 8), review = true,
            )
            val native = state.document("native.kt")
            native.restoreUnsaved("needle cached")
            native.acceptPersistedText("needle cached", "fixture baseline")
            // This does not test the size threshold and avoids allocating a multi-megabyte stress buffer.

            @Suppress("UNCHECKED_CAST")
            val mode = EditorDocument::class.java.getDeclaredField("performanceMode\$delegate")
                .apply { isAccessible = true }.get(native) as MutableState<EditorFilePerformanceMode>
            mode.value = EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED
            val priorVersion = native.changeVersion
            assertTrue(native.captureInsert(0, "new"))
            assertTrue(native.requiresBufferCapture)
            assertTrue(native.changeVersion > priorVersion)
            assertEquals("needle cached", native.content)
            val review = state.document("review.kt")
            review.restoreUnsaved("needle review")
            review.setReviewMode(true)
            listOf("deferred.kt", "native.kt", "review.kt").forEach { root.resolve(it).writeText("needle saved\n") }
            val documents = authority(state)
            val captured = documents.captureSearchSnapshots(WorkspaceSearchCaptureRequest(budget())).snapshots.associateBy { it.path }
            val deferred = captured.getValue("deferred.kt")
            assertTrue(deferred.dirty)
            assertFalse(deferred.loaded)
            assertFalse(deferred.editable)
            assertEquals("", deferred.content)
            assertEquals(0, deferred.selectionStart)
            val pending = captured.getValue("native.kt")
            assertEquals(WorkspaceDocumentKind.LARGE, pending.kind)
            assertEquals(native.changeVersion, pending.changeVersion)
            assertFalse(pending.editable)
            assertFalse(captured.getValue("review.kt").editable)
            val page = FileRepository(root).searchPage("needle", documents = documents)
            assertTrue(page.hits.isEmpty())
            assertEquals(0, page.dirtyOverlays)
            assertEquals(3, page.diskExcluded)
            assertEquals(3, page.skippedDirtyBuffers)
            assertTrue(page.dirtyCaptureComplete)
            assertEquals(0, recoveryReads)
            assertEquals("needle saved\n", FileRepository(root).readText("native.kt"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
