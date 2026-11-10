package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.text.Editable
import android.text.Selection
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** authored regression candidates: not compiled or run in the source-only review. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class NativeTerminalInputConnectionTest {
    private lateinit var activity: ActivityController<Activity>
    private lateinit var target: View
    private val owner = TerminalImeOwner()
    private val session = Any()
    private lateinit var connection: NativeTerminalInputConnection
    private val commits = mutableListOf<CharSequence>()
    private val keys = mutableListOf<KeyEvent>()
    private val deliveryOrder = mutableListOf<String>()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup()
        target = View(activity.get())
        activity.get().setContentView(target)
        connection = connect()
    }

    @After
    fun tearDown() {
        connection.closeConnection()
        activity.pause().stop().destroy()
    }

    private fun connect(): NativeTerminalInputConnection = NativeTerminalInputConnection(
        target = target,
        lease = owner.connect(session, currentSession = { session }, eligible = { true }),
        commitToTerminal = { text ->
            commits.add(text)
            deliveryOrder.add("text:$text")
            true
        },
        dispatchToTerminal = { event ->
            keys.add(event)
            deliveryOrder.add("key:${event.action}:${event.keyCode}")
            true
        },
    )

    private fun editable(): Editable = checkNotNull(connection.editable)

    private fun assertLocalText(expected: String) {
        assertEquals(expected, editable().toString())
        assertTrue("local editing must not send terminal text", commits.isEmpty())
        assertTrue("local editing must not synthesize terminal keys", keys.isEmpty())
    }

    private fun assertCommits(vararg expected: String) {
        assertEquals(expected.toList(), commits.map { it.toString() })
    }

    private fun pendingTextWithoutComposition(text: String) {
        assertTrue(connection.setComposingText(text, 1))
        BaseInputConnection.removeComposingSpans(editable())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editable()))
    }

    private fun assertPairs(vararg expectedKeyCodes: Int) {
        assertEquals("one DOWN/UP pair per requested key", expectedKeyCodes.size * 2, keys.size)
        expectedKeyCodes.forEachIndexed { index, keyCode ->
            val down = keys[index * 2]
            val up = keys[index * 2 + 1]
            assertEquals(KeyEvent.ACTION_DOWN, down.action)
            assertEquals(KeyEvent.ACTION_UP, up.action)
            assertEquals(keyCode, down.keyCode)
            assertEquals(keyCode, up.keyCode)
            assertEquals(0, down.repeatCount)
            assertEquals(0, up.repeatCount)
            assertEquals(0, down.metaState)
            assertEquals(0, up.metaState)
            assertEquals(KeyCharacterMap.VIRTUAL_KEYBOARD, down.deviceId)
            assertEquals(down.deviceId, up.deviceId)
            assertTrue(down.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0)
            assertEquals(down.flags, up.flags)
            assertEquals(down.downTime, up.downTime)
            assertEquals(down.eventTime, up.eventTime)
        }
    }

    @Test
    fun composingUpdatesReplaceRatherThanAppendAndFinalCommitEmitsOnlyFinalText() {
        assertTrue(connection.setComposingText("k", 1))
        assertLocalText("k")
        assertTrue(connection.setComposingText("ka", 1))
        assertLocalText("ka")
        assertTrue(connection.setComposingText("\u304b", 1))
        assertLocalText("\u304b")
        assertEquals(0, BaseInputConnection.getComposingSpanStart(editable()))
        assertEquals(1, BaseInputConnection.getComposingSpanEnd(editable()))

        assertTrue(connection.commitText("\u304b\u306a", 1))
        assertCommits("\u304b\u306a")
        assertEquals("", editable().toString())
        assertTrue(connection.finishComposingText())
        assertCommits("\u304b\u306a")
        assertTrue(keys.isEmpty())
    }

    @Test
    fun finishCompositionFlushesExactlyOnceAndLaterCompositionStartsFresh() {
        assertTrue(connection.setComposingText("caf\u00e9\uD83D\uDE42", 1))
        assertLocalText("caf\u00e9\uD83D\uDE42")
        assertTrue(connection.finishComposingText())
        assertTrue(connection.finishComposingText())
        assertTrue(connection.finishComposingText())
        assertCommits("caf\u00e9\uD83D\uDE42")
        assertEquals("", editable().toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editable()))

        assertTrue(connection.setComposingText("next", 1))
        assertTrue(connection.finishComposingText())
        assertCommits("caf\u00e9\uD83D\uDE42", "next")
        assertTrue(keys.isEmpty())
    }

    @Test
    fun baseBatchBoundariesNeitherFlushCompositionNorDuplicateCommits() {
        // BaseInputConnection reports batching unsupported. Do not assert invented buffering.
        assertFalse(connection.beginBatchEdit())
        assertFalse(connection.beginBatchEdit())
        assertTrue(connection.setComposingText("dr", 1))
        assertTrue(connection.setComposingText("draft", 1))
        assertFalse(connection.endBatchEdit())
        assertLocalText("draft")

        assertTrue(connection.commitText("final", 1))
        assertCommits("final")
        assertFalse(connection.endBatchEdit())
        assertTrue(connection.finishComposingText())
        assertFalse(connection.endBatchEdit())
        assertCommits("final")
        assertTrue(keys.isEmpty())
    }

    @Test
    fun commitPreservesExactUnicodeNewlineCarriageReturnTabAndControlCharacters() {
        val text: CharSequence = "\u03bb\uD83D\uDE42\n\r\t\u0003\u001b"
        assertTrue(connection.commitText(text, 1))
        assertEquals(1, commits.size)
        assertEquals(text.length, commits.single().length)
        text.indices.forEach { index -> assertEquals(text[index], commits.single()[index]) }
        assertTrue("the upstream sink owns text-to-terminal conversion", keys.isEmpty())
        assertEquals("", editable().toString())
    }

    @Test
    fun composingRegionReplacesOnlyItsRangeBeforeOneFullBufferFlush() {
        assertTrue(connection.setComposingText("abXXcd", 1))
        assertTrue(connection.setComposingRegion(2, 4))
        assertTrue(connection.setComposingText("\u03bb", 1))
        assertLocalText("ab\u03bbcd")
        assertEquals(2, BaseInputConnection.getComposingSpanStart(editable()))
        assertEquals(3, BaseInputConnection.getComposingSpanEnd(editable()))
        assertEquals(3, Selection.getSelectionStart(editable()))
        assertTrue(connection.finishComposingText())
        assertCommits("ab\u03bbcd")
    }

    @Test
    fun forwardAndReversedSelectionsAreReplacedUsingBaseEditableSemantics() {
        for ((start, end) in listOf(2 to 4, 4 to 2)) {
            pendingTextWithoutComposition("abXXcd")
            assertTrue(connection.setSelection(start, end))
            assertEquals("ab", connection.getTextBeforeCursor(10, 0).toString())
            assertEquals("XX", connection.getSelectedText(0).toString())
            assertEquals("cd", connection.getTextAfterCursor(10, 0).toString())
            assertTrue(connection.commitText("\u03bb", 1))
        }
        assertCommits("ab\u03bbcd", "ab\u03bbcd")
        assertTrue(keys.isEmpty())
    }

    @Test
    fun localCodeUnitDeleteHonorsSelectionAndDoesNotSendTerminalKeys() {
        pendingTextWithoutComposition("abXYcd")
        assertTrue(connection.setSelection(2, 4))
        assertTrue(connection.deleteSurroundingText(1, 1))
        assertLocalText("aXYd")
        assertEquals("XY", connection.getSelectedText(0).toString())
        assertEquals(1, Selection.getSelectionStart(editable()))
        assertEquals(3, Selection.getSelectionEnd(editable()))
        assertTrue(connection.finishComposingText())
        assertCommits("aXYd")
    }

    @Test
    fun localDeletePreservesActiveComposingRegionAndDeletesOnlyOutsideIt() {
        assertTrue(connection.setComposingText("aXYb", 1))
        assertTrue(connection.setComposingRegion(1, 3))
        assertTrue(connection.setSelection(2, 2))
        assertTrue(connection.deleteSurroundingText(1, 1))
        assertLocalText("XY")
        assertEquals(0, BaseInputConnection.getComposingSpanStart(editable()))
        assertEquals(2, BaseInputConnection.getComposingSpanEnd(editable()))
        assertTrue(connection.finishComposingText())
        assertCommits("XY")
    }

    @Test
    fun codePointDeleteRemovesWholeSupplementaryCharactersOnBothSides() {
        pendingTextWithoutComposition("A\uD83D\uDE42\uD83D\uDE80B")
        assertTrue(connection.setSelection(3, 3))
        assertTrue(connection.deleteSurroundingTextInCodePoints(1, 1))
        assertLocalText("AB")
        assertEquals(1, Selection.getSelectionStart(editable()))
        assertTrue(connection.finishComposingText())
        assertCommits("AB")
    }

    @Test
    fun codeUnitDeleteUsesUtf16LengthsRatherThanCodePointLengths() {
        pendingTextWithoutComposition("A\uD83D\uDE42B")
        assertTrue(connection.setSelection(3, 3))
        assertTrue(connection.deleteSurroundingText(2, 0))
        assertLocalText("AB")
        assertTrue(connection.finishComposingText())
        assertCommits("AB")
    }

    @Test
    fun invalidSurrogateCodePointDeleteLeavesBothSidesUntouched() {
        // A low surrogate without a preceding high surrogate invalidates the whole operation.
        pendingTextWithoutComposition("A\uDE42B")
        assertTrue(connection.setSelection(2, 2))
        connection.deleteSurroundingTextInCodePoints(1, 1)
        assertLocalText("A\uDE42B")
        assertEquals(2, Selection.getSelectionStart(editable()))
    }

    @Test
    fun negativeLengthsAreRejectedBeforeAnyLocalOrTerminalMutation() {
        val invalidLengths = listOf(-1 to 0, 0 to -1, -1 to -1, Int.MIN_VALUE to 1)
        for ((before, after) in invalidLengths) {
            assertFalse(connection.deleteSurroundingText(before, after))
            assertFalse(connection.deleteSurroundingTextInCodePoints(before, after))
        }
        assertLocalText("")
        pendingTextWithoutComposition("abc")
        assertTrue(connection.setSelection(1, 1))
        for ((before, after) in invalidLengths) {
            assertFalse(connection.deleteSurroundingText(before, after))
            assertFalse(connection.deleteSurroundingTextInCodePoints(before, after))
            assertLocalText("abc")
            assertEquals(1, Selection.getSelectionStart(editable()))
        }
    }

    @Test
    fun emptyBufferDeletionSendsBackspaceThenForwardDeleteDownUpPairs() {
        assertTrue(connection.deleteSurroundingText(2, 1))
        assertPairs(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)
        assertTrue(commits.isEmpty())
        assertEquals("", editable().toString())
    }

    @Test
    fun emptyBufferCodePointDeletionUsesTheSameOrderedKeyPairs() {
        assertTrue(connection.deleteSurroundingTextInCodePoints(1, 2))
        assertPairs(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_FORWARD_DEL)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun zeroLengthDeletionDoesNotEmitTextOrKeys() {
        assertTrue(connection.deleteSurroundingText(0, 0))
        assertTrue(connection.deleteSurroundingTextInCodePoints(0, 0))
        assertLocalText("")
        pendingTextWithoutComposition("abc")
        assertTrue(connection.setSelection(1, 1))
        assertTrue(connection.deleteSurroundingText(0, 0))
        assertTrue(connection.deleteSurroundingTextInCodePoints(0, 0))
        assertLocalText("abc")
    }

    @Test
    fun editorActionFlushesCompositionBeforeOneEnterPairWithoutTextualNewline() {
        assertTrue(connection.setComposingText("echo hello", 1))
        assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
        assertCommits("echo hello")
        assertPairs(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf(
            "text:echo hello",
            "key:${KeyEvent.ACTION_DOWN}:${KeyEvent.KEYCODE_ENTER}",
            "key:${KeyEvent.ACTION_UP}:${KeyEvent.KEYCODE_ENTER}",
        ), deliveryOrder)
        assertTrue(connection.finishComposingText())
        assertCommits("echo hello")
        assertPairs(KeyEvent.KEYCODE_ENTER)
    }

    @Test
    fun emptyEditorActionStillSendsOnlyOneEnterPair() {
        assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_GO))
        assertPairs(KeyEvent.KEYCODE_ENTER)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun directEnterDownFlushesCompositionBeforeExactEventAndUpDoesNotRepeat() {
        assertTrue(connection.setComposingText("echo composed", 1))
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        assertTrue(connection.sendKeyEvent(down))
        assertTrue(connection.sendKeyEvent(up))
        assertCommits("echo composed")
        assertEquals(2, keys.size)
        assertSame(down, keys[0])
        assertSame(up, keys[1])
        assertEquals(listOf("text:echo composed", "key:0:66", "key:1:66"), deliveryOrder)
    }

    @Test
    fun rawDownUpAndMultipleEventsPreserveIdentityModifiersRepeatsAndCharacters() {
        val modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON
        val down = KeyEvent(10L, 20L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 3, modifiers,
            7, 46, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        @Suppress("DEPRECATION")
        val multiple = KeyEvent(30L, "\u03bb\uD83D\uDE42\n", 7, KeyEvent.FLAG_SOFT_KEYBOARD)
        val events = listOf(down, up, multiple)
        events.forEach { assertTrue(connection.sendKeyEvent(it)) }

        assertEquals(3, keys.size)
        events.forEachIndexed { index, original -> assertSame(original, keys[index]) }
        assertEquals(modifiers, keys[0].metaState)
        assertEquals(3, keys[0].repeatCount)
        assertEquals(KeyEvent.ACTION_MULTIPLE, keys[2].action)
        assertEquals("\u03bb\uD83D\uDE42\n", keys[2].characters)
        assertTrue(commits.isEmpty())
        assertEquals("", editable().toString())
    }

    private fun assertRetiredConnectionRejectsWrites(retired: NativeTerminalInputConnection) {
        assertNull(retired.editable)
        assertFalse(retired.commitText("late", 1))
        assertFalse(retired.setComposingText("late", 1))
        assertFalse(retired.setComposingRegion(0, 1))
        assertFalse(retired.setSelection(0, 0))
        assertFalse(retired.finishComposingText())
        assertFalse(retired.deleteSurroundingText(1, 1))
        assertFalse(retired.deleteSurroundingTextInCodePoints(1, 1))
        assertFalse(retired.performEditorAction(EditorInfo.IME_ACTION_DONE))
        assertFalse(retired.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        assertFalse(retired.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)))
    }

    @Test
    fun closeDiscardsPendingCompositionAndRepeatedCloseCannotFlushOrWrite() {
        assertTrue(connection.setComposingText("must never reach terminal", 1))
        val heldEditable = editable()
        connection.closeConnection()
        assertEquals("", heldEditable.toString())
        assertRetiredConnectionRejectsWrites(connection)
        connection.closeConnection()
        assertRetiredConnectionRejectsWrites(connection)
        assertTrue(commits.isEmpty())
        assertTrue(keys.isEmpty())
    }

    @Test
    fun replacingOwnerRejectsOldCallbacksAndOldCloseDoesNotRevokeReplacement() {
        assertTrue(connection.setComposingText("stale composition", 1))
        val retired = connection
        connection = connect()
        assertRetiredConnectionRejectsWrites(retired)
        retired.closeConnection()
        assertRetiredConnectionRejectsWrites(retired)
        assertTrue(commits.isEmpty())
        assertTrue(keys.isEmpty())

        assertTrue(connection.setComposingText("current", 1))
        assertTrue(connection.commitText("current text", 1))
        assertCommits("current text")
        assertTrue(connection.deleteSurroundingText(1, 0))
        assertPairs(KeyEvent.KEYCODE_DEL)
    }

    @Test
    fun explicitOwnerInvalidationRejectsLateCompositionAndTerminalKeys() {
        assertTrue(connection.setComposingText("pending", 1))
        owner.invalidate()
        assertRetiredConnectionRejectsWrites(connection)
        connection.closeConnection()
        assertTrue(commits.isEmpty())
        assertTrue(keys.isEmpty())
    }
}
