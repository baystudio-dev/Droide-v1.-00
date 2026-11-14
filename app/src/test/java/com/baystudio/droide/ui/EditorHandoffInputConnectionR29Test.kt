package com.baystudio.droide.ui

import android.app.Application
import android.content.ClipDescription
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi
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

/** Recording targets prove non-forwarding, independently of Sora's own text guards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorHandoffInputConnectionR29Test {
    @get:Rule val errors = ErrorCollector()

    private fun editor(): DroideCodeEditor {
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "plaintext",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test"))
        try {
            editor.setText("abcdef")
            editor.setSelection(0, 6)
            editor.nativeCapture = {}
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

    private fun rejected(editor: DroideCodeEditor, target: RecordingConnection, name: String, action: () -> Unit) {
        val before = editor.text.toString()
        val calls = target.calls
        val failure = runCatching(action).exceptionOrNull()
        errors.checkThat("$name must not crash", failure, nullValue())
        errors.checkThat("$name must not forward", target.calls, equalTo(calls))
        errors.checkThat("$name must not mutate", editor.text.toString(), equalTo(before))
    }

    @Test(timeout = 15_000)
    @Config(sdk = [29, 35])
    fun oldApiMutatorsStopBeforeDelegateAndResumeOnlyOnAbort() {
        val editor = editor()
        try {
            val target = RecordingConnection(editor)
            val connection = editorHandoffInputConnection(editor, target)
            val content = InputContentInfo(Uri.parse("content://handoff/test"),
                ClipDescription("test", arrayOf("text/plain")), null)
            val commands = listOf<Pair<String, () -> Boolean>>(
                "commit" to { connection.commitText("late", 1) },
                "compose" to { connection.setComposingText("late", 1) },
                "composing region" to { connection.setComposingRegion(0, 1) },
                "finish composing" to { connection.finishComposingText() },
                "selection" to { connection.setSelection(0, 1) },
                "delete" to { connection.deleteSurroundingText(1, 0) },
                "delete code points" to { connection.deleteSurroundingTextInCodePoints(1, 0) },
                "completion" to { connection.commitCompletion(CompletionInfo(1L, 0, "late")) },
                "correction" to { connection.commitCorrection(CorrectionInfo(0, "a", "b")) },
                "content" to { connection.commitContent(content, 0, null) },
                "begin batch" to { connection.beginBatchEdit() },
                "end batch" to { connection.endBatchEdit() },
                "key" to { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) },
                "editor action" to { connection.performEditorAction(EditorInfo.IME_ACTION_DONE) },
                "context action" to { connection.performContextMenuAction(android.R.id.undo) },
                "private command" to { connection.performPrivateCommand("handoff.test", Bundle()) },
                "meta keys" to { connection.clearMetaKeyStates(0) },
                "cursor updates" to { connection.requestCursorUpdates(1) },
            )
            assertTrue(connection is InputConnectionWrapper)
            assertTrue("the external wrapper must have an immutable target",
                runCatching { (connection as InputConnectionWrapper).setTarget(target) }.exceptionOrNull() is SecurityException)
            commands.forEach { (name, command) ->
                val before = target.calls
                assertTrue("live $name forwards its result", command())
                assertEquals("live $name reaches the target once", before + 1, target.calls)
            }
            val beforeClose = target.calls
            connection.closeConnection()
            assertEquals(beforeClose + 1, target.calls)
            assertNotNull(connection.getExtractedText(ExtractedTextRequest(), 0))
            assertEquals(beforeClose + 2, target.calls)
            fun rejectAll() {
                commands.forEach { (name, command) -> rejected(editor, target, name) { assertFalse(command()) } }
                rejected(editor, target, "close") { connection.closeConnection() }
                rejected(editor, target, "extract monitor") {
                    assertNull(connection.getExtractedText(ExtractedTextRequest(), InputConnection.GET_EXTRACTED_TEXT_MONITOR))
                }
                assertFalse(editor.text.isInBatchEdit)
            }
            val tentative = editor.beginProjectHandoff {}
            rejectAll()
            tentative.abort()
            val beforeResume = target.calls
            assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
            assertEquals(beforeResume + 1, target.calls)
            val committed = editor.beginProjectHandoff {}
            committed.commit()
            rejectAll()
            editor.release()
            rejectAll()
            committed.abort()
            rejectAll()
        } finally { editor.release() }
    }

    private fun newerCommandsStayClosed(
        editor: DroideCodeEditor,
        target: RecordingConnection,
        commands: List<Pair<String, () -> Boolean>>,
    ) {
        commands.forEach { (name, command) ->
            val before = target.calls
            assertTrue("live $name forwards", command())
            assertEquals(before + 1, target.calls)
        }
        fun rejectAll() = commands.forEach { (name, command) ->
            rejected(editor, target, name) { assertFalse(command()) }
        }
        val lease = editor.beginProjectHandoff {}
        rejectAll()
        lease.commit()
        rejectAll()
        editor.release()
        rejectAll()
        assertFalse(editor.text.isInBatchEdit)
    }

    @Test(timeout = 15_000)
    @Config(sdk = [31])
    fun api31FeatureUpdatesCannotEnterRetiredDelegate() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val editor = editor()
            try {
                val target = RecordingConnection31(editor)
                val connection = editorHandoffInputConnection(editor, target)
                newerCommandsStayClosed(editor, target, listOf(
                    "IME consumes input" to { connection.setImeConsumesInput(true) },
                    "spell check" to { connection.performSpellCheck() },
                ))
            } finally { editor.release() }
        } else {
            fail("Robolectric fixture requires API 31 or newer; configured SDK 31 must execute this test")
        }
    }

    @Test(timeout = 15_000)
    @Config(sdk = [33])
    fun api33OverloadsDoNotBypassTheOldMethodGate() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val editor = editor()
            try {
                val target = RecordingConnection33(editor)
                val connection = editorHandoffInputConnection(editor, target)
                newerCommandsStayClosed(editor, target, listOf(
                    "attributed commit" to { connection.commitText("late", 1, null) },
                    "attributed compose" to { connection.setComposingText("late", 1, null) },
                    "attributed region" to { connection.setComposingRegion(0, 1, null) },
                    "filtered cursor updates" to { connection.requestCursorUpdates(1, 0) },
                ))
            } finally { editor.release() }
        } else {
            fail("Robolectric fixture requires API 33 or newer; configured SDK 33 must execute this test")
        }
    }

    @Test(timeout = 15_000)
    @Config(sdk = [35])
    fun api34ReplacementDoesNotReachTheTargetsBatchOrText() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val editor = editor()
            try {
                val target = RecordingConnection34(editor)
                val connection = editorHandoffInputConnection(editor, target)
                newerCommandsStayClosed(editor, target, listOf(
                    "replace range" to { connection.replaceText(0, 1, "late", 1, null) },
                ))
            } finally { editor.release() }
        } else {
            fail("Robolectric fixture requires API 34 or newer; configured SDK 35 must execute this test")
        }
    }

    // Every forwarded method deliberately changes the editor's real Content. A missing
    // wrapper override fails both the call-count and text-unchanged assertions.
    private open class RecordingConnection(protected val editor: DroideCodeEditor) : BaseInputConnection(editor, true) {
        var calls = 0
            private set

        protected fun record(): Boolean {
            calls++
            editor.text.insert(0, editor.text.length, "x")
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = record()
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = record()
        override fun setComposingRegion(start: Int, end: Int): Boolean = record()
        override fun finishComposingText(): Boolean = record()
        override fun setSelection(start: Int, end: Int): Boolean = record()
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = record()
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = record()
        override fun commitCompletion(text: CompletionInfo?): Boolean = record()
        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = record()
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = record()
        override fun beginBatchEdit(): Boolean = record()
        override fun endBatchEdit(): Boolean = record()
        override fun sendKeyEvent(event: KeyEvent?): Boolean = record()
        override fun performEditorAction(editorAction: Int): Boolean = record()
        override fun performContextMenuAction(id: Int): Boolean = record()
        override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = record()
        override fun clearMetaKeyStates(states: Int): Boolean = record()
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = record()
        override fun closeConnection() { record() }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            record()
            return ExtractedText()
        }
    }

    @RequiresApi(31)
    private open class RecordingConnection31(editor: DroideCodeEditor) : RecordingConnection(editor) {
        override fun setImeConsumesInput(imeConsumesInput: Boolean): Boolean = record()
        override fun performSpellCheck(): Boolean = record()
    }

    @RequiresApi(33)
    private open class RecordingConnection33(editor: DroideCodeEditor) : RecordingConnection31(editor) {
        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean = record()
        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean = record()
        override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean = record()
        override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean = record()
    }

    @RequiresApi(34)
    private class RecordingConnection34(editor: DroideCodeEditor) : RecordingConnection33(editor) {
        override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean = record()
    }
}
