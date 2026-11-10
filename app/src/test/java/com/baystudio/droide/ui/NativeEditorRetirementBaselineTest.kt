package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.inputmethod.EditorInfo
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
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

/** Original public APIs only. Six characters, no Activity, application startup, terminal or process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeEditorRetirementBaselineTest {
    @get:Rule val errors = ErrorCollector()
    private val style = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test")

    private fun editor(): DroideCodeEditor {
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "plaintext", codeStyle = style)
        try {
            editor.setText("abcdef")
            editor.setSelection(0, 6)
            // Pinned Sora builds even tiny LineBreakLayout on a worker, then clears layoutBusy via Main.
            // Observe that public readiness; do not force it.
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

    private fun assertUnchangedWithoutCrash(editor: DroideCodeEditor, expected: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        // Evaluate both assertions: a crash must never turn a missed mutation into a passing test.
        errors.checkThat("retired editor operation must not crash", failure, nullValue())
        errors.checkThat("retired editor operation must not mutate text", editor.text.toString(), equalTo(expected))
    }

    @Test(timeout = 15_000) fun heldInputConnectionCannotDeleteAfterRelease() {
        val editor = editor()
        try {
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            editor.release()
            assertTrue(editor.isReleased)
            assertUnchangedWithoutCrash(editor, "abcdef") {
                connection.deleteSurroundingText(2, 0)
            }
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun inheritedUndoCannotMutateAfterRelease() {
        val editor = editor()
        try {
            editor.commitText("g", false)
            assertEquals("abcdefg", editor.text.toString())
            assertTrue("fixture must install an undoable edit", editor.canUndo())
            editor.release()
            assertUnchangedWithoutCrash(editor, "abcdefg") { editor.undo() }
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun liveInputConnectionStillDeletes() {
        val editor = editor()
        try {
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.deleteSurroundingText(2, 0))
            assertEquals("abcd", editor.text.toString())
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun existingSmartCommitReleaseGuardPreservesText() {
        val editor = editor()
        try {
            editor.release()
            assertUnchangedWithoutCrash(editor, "abcdef") { editor.commitText("g", false) }
        } finally { editor.release() }
    }
}
