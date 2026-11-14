package com.baystudio.droide.ui

import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import androidx.annotation.RequiresApi
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorReleaseEvent
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.completion.SimpleSnippetCompletionItem
import io.github.rosemoe.sora.lang.completion.SnippetDescription
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Source-only candidate. Six characters, inert Application, no Activity or app startup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeProjectHandoffGateR29Test {
    @get:Rule val errors = ErrorCollector()
    private val style = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test")

    private fun editor(): DroideCodeEditor {
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "plaintext", codeStyle = style)
        try {
            editor.setText("abcdef")
            editor.setSelection(0, 6)
            editor.nativeCapture = {}
            // Sora even measures its one-line layout on a worker. Let its genuine Main
            // callback clear layoutBusy; never force setLayoutBusy(false) in this fixture.
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!editor.isEditable && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertTrue("fixture editor must be enabled", editor.isEnabled)
            assertTrue("fixture edit policy must be enabled", editor.getEditable())
            assertFalse("fixture formatter must be idle", editor.isFormatting)
            assertTrue("fixture layout must become editable within 3 seconds", editor.isEditable)
            return editor
        } catch (failure: Throwable) {
            editor.release()
            throw failure
        }
    }

    private fun unchanged(editor: DroideCodeEditor, expected: String, name: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        errors.checkThat("$name must not crash", failure, nullValue())
        errors.checkThat("$name must not mutate", editor.text.toString(), equalTo(expected))
    }

    @Test(timeout = 15_000)
    @Config(sdk = [29, 35])
    fun heldWrappedSoraConnectionFinishesCompositionThenRejectsMutationThroughRelease() {
        val editor = editor()
        try {
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            assertTrue(connection is InputConnectionWrapper)
            // The actual Sora delegate, not a synthetic Editable, owns this composition.
            assertTrue(connection.setComposingText("g", 1))
            assertTrue(editor.hasComposingText())
            assertEquals("abcdefg", editor.text.toString())
            var captured = ""
            editor.nativeCapture = { captured = editor.text.toString() }
            var released = 0
            val lease = editor.beginProjectHandoff { released++ }
            assertEquals("abcdefg", captured)
            assertFalse(editor.hasComposingText())
            assertFalse(editor.text.isInBatchEdit)
            assertFalse(editor.isEditable)
            assertNull(editor.onCreateInputConnection(EditorInfo()))
            fun rejectedCommands() {
                unchanged(editor, captured, "held commit") { connection.commitText("late", 1) }
                unchanged(editor, captured, "held compose") { connection.setComposingText("late", 1) }
                unchanged(editor, captured, "held composing region") { connection.setComposingRegion(0, 2) }
                unchanged(editor, captured, "held selection") { connection.setSelection(0, 2) }
                unchanged(editor, captured, "held finish composition") { connection.finishComposingText() }
                unchanged(editor, captured, "held deletion") { connection.deleteSurroundingText(2, 0) }
                unchanged(editor, captured, "held undo") { connection.performContextMenuAction(android.R.id.undo) }
                unchanged(editor, captured, "held redo") { connection.performContextMenuAction(android.R.id.redo) }
                unchanged(editor, captured, "held begin batch") { assertFalse(connection.beginBatchEdit()) }
                unchanged(editor, captured, "held end batch") { assertFalse(connection.endBatchEdit()) }
                assertFalse(editor.text.isInBatchEdit)
                // The recording-target test separately proves these no longer forward.
                // Keys sent to ViewRoot before admission still need attached-device coverage.
                unchanged(editor, captured, "held send key on inert view") {
                    assertFalse(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
                    assertFalse(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)))
                    shadowOf(Looper.getMainLooper()).idle()
                }
                unchanged(editor, captured, "held editor action") { assertFalse(connection.performEditorAction(EditorInfo.IME_ACTION_DONE)) }
                if (Build.VERSION.SDK_INT >= 34) {
                    unchanged(editor, captured, "held modern Sora overloads") { rejectModernCommands(connection) }
                    assertFalse(editor.text.isInBatchEdit)
                }
            }
            rejectedCommands()
            lease.commit()
            lease.abort()
            assertFalse(editor.acceptsMutations)
            assertEquals(0, released)
            editor.release()
            assertEquals(1, released)
            assertFalse("released view must drop its owner callback lease", editor.retainsNativeHandoff)
            assertNull("successful capture must not retain the old model callback", editor.nativeCapture)
            rejectedCommands()
            unchanged(editor, captured, "held close connection") { connection.closeConnection() }
            editor.release()
            assertEquals(1, released)
        } finally { editor.release() }
    }

    @RequiresApi(34)
    private fun rejectModernCommands(connection: android.view.inputmethod.InputConnection) {
        assertFalse(connection.commitText("late", 1, null))
        assertFalse(connection.setComposingText("late", 1, null))
        assertFalse(connection.setComposingRegion(0, 2, null))
        assertFalse(connection.replaceText(0, 2, "late", 1, null))
        assertFalse(connection.requestCursorUpdates(1, 0))
        assertFalse(connection.setImeConsumesInput(true))
        assertFalse(connection.performSpellCheck())
    }

    @Test(timeout = 15_000) fun directMutationRootsAndCompletionAcceptanceStayClosed() {
        val editor = editor()
        try {
            editor.commitText("g", false)
            assertTrue(editor.canUndo())
            val lease = editor.beginProjectHandoff {}
            lease.commit()
            val expected = editor.text.toString()
            val operations = listOf<Pair<String, () -> Unit>>(
                "one-argument commit" to { editor.commitText("x") },
                "smart commit" to { editor.commitText("x", false) },
                "three-argument commit" to { editor.commitText("x", false, false) },
                "insert" to { editor.insertText("x", 1) },
                "setText" to { editor.setText("x") },
                "setText bundle" to { editor.setText("x", Bundle()) },
                "setText root" to { editor.setText("x", false, Bundle()) },
                "undo" to { editor.undo() },
                "redo" to { editor.redo() },
                "delete" to { editor.deleteText() },
                "indent" to { editor.indentLines(false) },
                "unindent" to { editor.unindentSelection() },
                "tab" to { editor.indentOrCommitTab() },
                "paste" to { editor.pasteText("x") },
                "cut line" to { editor.cutLine() },
                "duplicate" to { editor.duplicateLine() },
                "hardware key" to { editor.onKeyDown(KeyEvent.KEYCODE_DEL, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) },
                "accessibility set text" to {
                    editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "late")
                    })
                },
                "completion selection" to { editor.getComponent(EditorAutoCompletion::class.java).select(0) },
                "direct local completion" to {
                    DroideGuardedCompletionItem(SimpleCompletionItem(0, "late"))
                        .performCompletion(editor, editor.text, 0, expected.length)
                },
                "direct snippet completion" to {
                    DroideGuardedCompletionItem(SimpleSnippetCompletionItem("snippet", "test",
                        SnippetDescription(0, CodeSnippetParser.parse("x${'$'}{1:y}"), true)))
                        .performCompletion(editor, editor.text, editor.cursor.right())
                },
            )
            operations.forEach { (name, operation) -> unchanged(editor, expected, name, operation) }
            editor.setEditable(true)
            editor.applyReviewMode(false)
            assertFalse("recomposition must not remove the lease", editor.isEditable)
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun abortIsExactTokenAndPreservesReviewPolicy() {
        val editor = editor()
        try {
            val first = editor.beginProjectHandoff {}
            first.abort()
            assertTrue(editor.isEditable)
            val second = editor.beginProjectHandoff {}
            first.abort()
            assertFalse(editor.isEditable)
            second.abort()
            assertTrue(editor.isEditable)
            editor.applyReviewMode(true)
            assertFalse(editor.getEditable())
            val review = editor.beginProjectHandoff {}
            review.abort()
            assertTrue(editor.acceptsMutations)
            assertFalse(editor.isEditable)
            assertFalse(editor.getEditable())
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun tentativeCallbacksResumeOnAbortButCommittedFormatterCannotRun() {
        val editor = editor()
        try {
            var delivered = 0
            val cancelled = Runnable { error("removed callback was delivered") }
            val tentative = editor.beginProjectHandoff {}
            editor.postInLifecycle(Runnable { delivered++ })
            editor.postInLifecycle(cancelled)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, delivered)
            editor.removeCallbacks(cancelled)
            tentative.abort()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, delivered)
            editor.onFormatSucceed("late formatter", null)
            val committed = editor.beginProjectHandoff {}
            committed.commit()
            unchanged(editor, "abcdef", "queued formatter") { shadowOf(Looper.getMainLooper()).idle() }
            editor.onFormatSucceed("later formatter", null)
            unchanged(editor, "abcdef", "retired formatter") { shadowOf(Looper.getMainLooper()).idle() }
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun rawDeltaIsCapturedAndReleaseObserverOutlivesPaneCleanup() {
        val editor = editor()
        try {
            var captured = ""
            editor.nativeCapture = { captured = editor.text.toString() }
            var events = 0
            editor.nativeContentSubscription = editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ -> events++ }
            var released = 0
            var releaseFailure: Throwable? = null
            val lease = editor.beginProjectHandoff { failure -> released++; releaseFailure = failure }
            // The pane may now clear its controllers; this receipt still belongs to the view.
            editor.text.insert(0, 6, "g")
            assertEquals("abcdefg", captured)
            assertTrue(editor.nativeUnexpectedDelta)
            assertNull(editor.nativeHandoffFailure)
            assertEquals(1, events)
            // Coordinator must refuse publication on the changed model. Test a later
            // committed release separately, after abort and a fresh captured checkpoint.
            lease.abort()
            val next = editor.beginProjectHandoff { failure -> released++; releaseFailure = failure }
            next.commit()
            editor.subscribeEvent(EditorReleaseEvent::class.java) { _, _ -> editor.text.insert(0, 7, "h") }
            editor.release()
            assertTrue(editor.isReleased)
            assertEquals("abcdefgh", captured)
            assertEquals(2, events)
            assertNull(editor.nativeContentSubscription)
            assertEquals(1, released)
            assertTrue(editor.nativeUnexpectedDelta)
            assertNull(releaseFailure)
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun unfinishedBatchAndCaptureFailureRefuseWithoutClosingInput() {
        val editor = editor()
        try {
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            connection.beginBatchEdit()
            val batchFailure = runCatching { editor.beginProjectHandoff {} }.exceptionOrNull()
            assertNotNull(batchFailure)
            assertTrue(editor.acceptsMutations)
            connection.endBatchEdit()
            editor.nativeCapture = { error("capture failed") }
            val captureFailure = runCatching { editor.beginProjectHandoff {} }.exceptionOrNull()
            assertEquals("capture failed", captureFailure?.message)
            assertTrue(editor.isEditable)
            editor.nativeCapture = {}
            assertTrue(connection.commitText("g", 1))
            assertEquals("abcdefg", editor.text.toString())
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun releaseFailureIsAcknowledgedOnceAndCannotReopenInput() {
        val editor = editor()
        try {
            var rejectCapture = false
            editor.nativeCapture = { if (rejectCapture) error("final capture failed") }
            var released = 0
            var failure: Throwable? = null
            val lease = editor.beginProjectHandoff { released++; failure = it }
            lease.commit()
            rejectCapture = true
            unchanged(editor, "abcdef", "failing release capture") { editor.release() }
            assertEquals(1, released)
            assertEquals("final capture failed", failure?.message)
            assertNotNull(editor.nativeCapture)
            lease.abort()
            assertFalse(editor.acceptsMutations)
            unchanged(editor, "abcdef", "insert after failed final capture") { editor.insertText("x", 1) }
            editor.release()
            assertEquals(1, released)
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun tentativeReleaseRemainsClosedAndDefersAcknowledgementUntilCommit() {
        val editor = editor()
        try {
            var released = 0
            val lease = editor.beginProjectHandoff { released++ }
            editor.release()
            assertTrue(editor.isReleased)
            assertFalse(editor.acceptsMutations)
            assertTrue("tentative release retains its decision receipt", editor.retainsNativeHandoff)
            assertEquals(0, released)
            // Real coordinator must detect isReleased before publication. Even an erroneous
            // late commit cannot make acknowledgement happen twice or revive this view.
            lease.commit()
            lease.commit()
            assertEquals(1, released)
            assertFalse(editor.retainsNativeHandoff)
            lease.abort()
            assertFalse(editor.isEditable)
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun surfaceFailureSealsHeldConnectionAndDirectRoots() {
        val editor = editor()
        try {
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            editor.onEditorFailure = {}
            editor.reportSurfaceFailure(IllegalStateException("surface failed"))
            assertFalse(editor.isEditable)
            unchanged(editor, "abcdef", "failed surface held delete") { connection.deleteSurroundingText(2, 0) }
            unchanged(editor, "abcdef", "failed surface insert") { editor.insertText("x", 1) }
            unchanged(editor, "abcdef", "failed surface setText") { editor.setText("late") }
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun ordinaryEditAvoidsFullCaptureAndAcknowledgementFailureIsRetained() {
        val editor = editor()
        try {
            var captures = 0
            editor.nativeCapture = { captures++ }
            editor.commitText("g", false)
            assertEquals("abcdefg", editor.text.toString())
            assertEquals("ordinary content events must keep incremental capture", 0, captures)
            var released = 0
            val lease = editor.beginProjectHandoff { released++; error("acknowledgement failed") }
            lease.commit()
            unchanged(editor, "abcdefg", "throwing release acknowledgement") { editor.release() }
            assertEquals(1, released)
            assertEquals("acknowledgement failed", editor.nativeHandoffFailure?.message)
            assertNull("callback failure must not retain an already captured model", editor.nativeCapture)
            assertFalse(editor.retainsNativeHandoff)
            assertFalse(editor.acceptsMutations)
            assertTrue("both release snapshots must still be attempted", captures >= 3)
            editor.release()
            assertEquals(1, released)
        } finally { editor.release() }
    }
}
