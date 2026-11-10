package com.baystudio.droide.ui

import android.app.ApplicationExitInfo
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AbnormalStartupEditorRecoveryTest {
    @Test fun abnormalRestoreAndRepeatedCheckpointCapturePreserveEveryDeferredDirtyBlobWithoutReadingIt() = runBlocking {
        val root = Files.createTempDirectory("droide-abnormal-recovery-").toFile()
        try {
            var reads = 0
            val refs = (1..40).associate { index ->
                val path = "unsaved$index.kt"
                path to EditorRecoveryBuffer(path, "blob$index.bin", null, Any()) { reads++; "unsaved $index" }
            }
            val selections = refs.keys.associateWith { EditorSelectionSnapshot(2, 5) }
            val recovered = EditorRecoverySnapshot(refs.keys.toList(), "unsaved20.kt", emptyMap(),
                selections, setOf("unsaved20.kt"), refs)
            val policy = StartupRecoveryPolicy.decide(PreviousProcessExit(1_000, 101, ApplicationExitInfo.REASON_LOW_MEMORY), null)
            val state = EditorWorkspaceState()
            val tabs = EditorTabRetention.restoreDocuments(recovered, FileRepository(root), state)
            val active = policy.recoveredEditorPath(recovered.activeFile, tabs)
            assertEquals("", active)
            assertEquals(40, tabs.size)
            assertTrue(state.allDocuments().none { it.loaded })

            repeat(2) {
                val checkpoint = checkNotNull(state.recoverySnapshot(tabs, active))
                assertEquals(refs.keys, checkpoint.dirtyPaths)
                assertEquals("", checkpoint.activeFile)
                assertTrue(checkpoint.dirtyBuffers.isEmpty())
                assertEquals(selections, checkpoint.selections)
                assertEquals(setOf("unsaved20.kt"), checkpoint.reviewFiles)
                refs.forEach { (path, ref) -> assertSame(ref, checkpoint.deferredBuffers[path]) }
                val retainedTabs = EditorTabRetention.restoreDocuments(checkpoint, FileRepository(root), state)
                assertEquals(tabs, retainedTabs)
            }
            assertEquals(0, reads)
            assertEquals(40, state.dirtyDocuments().size)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun explicitReopenLoadsOnlyTheChosenBodyAndPreservesOtherDeferredBuffersInTheNextCheckpoint() = runBlocking {
        val root = Files.createTempDirectory("droide-recovery-explicit-open-").toFile()
        try {
            root.resolve("one.kt").writeText("saved one")
            var oneReads = 0
            var twoReads = 0
            val first = EditorRecoveryBuffer("one.kt", "one.bin", null, Any()) { oneReads++; "unsaved one" }
            val second = EditorRecoveryBuffer("two.kt", "two.bin", null, Any()) { twoReads++; "unsaved two" }
            val state = EditorWorkspaceState()
            val files = FileRepository(root)
            val recovered = EditorRecoverySnapshot(listOf("one.kt", "two.kt"), "one.kt", emptyMap(),
                deferredBuffers = mapOf("one.kt" to first, "two.kt" to second))
            val tabs = EditorTabRetention.restoreDocuments(recovered, files, state)
            state.completeRecovery()
            state.document("one.kt").ensureLoaded(files)
            val checkpoint = checkNotNull(state.recoverySnapshot(tabs, "one.kt"))
            assertEquals("one.kt", checkpoint.activeFile)
            assertEquals(mapOf("one.kt" to "unsaved one"), checkpoint.dirtyBuffers)
            assertSame(second, checkpoint.deferredBuffers["two.kt"])
            assertEquals(1, oneReads)
            assertEquals(0, twoReads)
            assertEquals("saved one", root.resolve("one.kt").readText())
            assertTrue(state.recoveryInitialized)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun emptyVisibleTabsDoNotClearADeferredDirtyDocument() {
        val state = EditorWorkspaceState()
        val ref = EditorRecoveryBuffer("unsaved.kt", "blob.bin", null, Any()) { error("must stay deferred") }
        state.document("unsaved.kt").restoreDeferred(ref)
        val checkpoint = checkNotNull(state.recoverySnapshot(emptyList(), ""))
        assertSame(ref, checkpoint.deferredBuffers["unsaved.kt"])
        assertEquals(setOf("unsaved.kt"), checkpoint.dirtyPaths)
        assertNull(EditorWorkspaceState().recoverySnapshot(emptyList(), ""))
    }
}
