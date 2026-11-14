package com.baystudio.droide.ui

import com.baystudio.droide.core.EditorLargeFilePolicy
import org.junit.Assert.*
import org.junit.Test

/** Proposed baseline: bounded model-only capture; no Android editor, storage, or native process. */
class EditorRecoveryCaptureR29Test {
    @Test(timeout = 15_000)
    fun throwingCaptureCannotProduceAStaleRecoveryCheckpoint() {
        val state = pendingNativeState()
        val bridge = ActiveEditorBridge().apply { snapshot = { error("fixture capture failed") } }
        assertFalse(bridge.capture())
        assertTrue("Recovery must refuse stale model text after capture fails",
            runCatching { state.recoverySnapshot(listOf("keep.txt"), "keep.txt") }.isFailure)
        assertTrue(state.document("keep.txt").dirty)
        assertTrue(state.document("keep.txt").requiresBufferCapture)
    }

    @Test(timeout = 15_000)
    fun missingCaptureCannotProduceAStaleRecoveryCheckpoint() {
        val state = pendingNativeState()
        assertFalse(ActiveEditorBridge().capture())
        assertTrue("An absent capture callback must not checkpoint an incomplete optimized buffer",
            runCatching { state.recoverySnapshot(listOf("keep.txt"), "keep.txt") }.isFailure)
        assertTrue(state.document("keep.txt").requiresBufferCapture)
    }

    @Test(timeout = 15_000)
    fun completedCaptureAllowsTheExactNewestTextToBeCheckpointed() {
        val state = pendingNativeState()
        val document = state.document("keep.txt")
        val actualText = "native-only edit" + document.content
        val bridge = ActiveEditorBridge().apply { snapshot = { document.capture(actualText) } }
        assertTrue(bridge.capture())
        assertFalse(document.requiresBufferCapture)
        assertEquals(actualText, state.recoverySnapshot(listOf("keep.txt"), "keep.txt")!!.dirtyBuffers["keep.txt"])
    }

    @Test(timeout = 15_000)
    fun ordinaryCompleteBufferCanCheckpointWithoutAnAttachedNativeEditor() {
        val state = EditorWorkspaceState()
        state.document("keep.txt").restoreUnsaved("complete text")
        assertFalse(ActiveEditorBridge().capture())
        assertEquals("complete text", state.recoverySnapshot(listOf("keep.txt"), "keep.txt")!!.dirtyBuffers["keep.txt"])
    }

    private fun pendingNativeState(): EditorWorkspaceState {
        val state = EditorWorkspaceState()
        val document = state.document("keep.txt")
        // About 300 KB; the line-count boundary selects optimized mode without a multi-MiB fixture.
        document.restoreUnsaved("\n".repeat(EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES))
        assertTrue(document.largeFileOptimized)
        assertTrue(document.captureInsert(0, "native-only edit"))
        assertTrue(document.requiresBufferCapture)
        assertFalse(document.content.startsWith("native-only edit"))
        return state
    }
}
