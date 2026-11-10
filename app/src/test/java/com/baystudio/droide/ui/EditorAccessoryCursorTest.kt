package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorAccessoryCursorTest {
    private fun withEditor(body: (DroideCodeEditor) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val root = FrameLayout(activity.get())
        val editor = DroideCodeEditor(activity.get(), "plaintext",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "R73 fixture"))
        activity.get().setContentView(root)
        root.addView(editor, FrameLayout.LayoutParams(400, 320))
        editor.setCursorAnimationEnabled(false)
        editor.setLineNumberEnabled(false)
        editor.setWordwrap(false)
        editor.setText("alpha\nbravo\ncharlie\n🙂 é العربية")
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 320)
        assertTrue(editor.requestFocus())
        try { body(editor) } finally {
            root.removeView(editor)
            editor.release()
            activity.pause().stop().destroy()
        }
    }

    private fun send(editor: DroideCodeEditor, keyCode: Int, extra: Int = 0) {
        val key = editorPortraitEntries("plaintext").filterIsInstance<EditorFastEntry.Control>()
            .single { it.key.keyCode == keyCode }.key
        assertTrue(dispatchAccessoryKey(editor, requireNotNull(key.keyCode), key.forcedMetaState or extra))
    }

    @Test fun upAndDownMoveRealCaretAndLeaveTextIncludingUnicodeUntouched() = withEditor { editor ->
        val before = editor.text.toString()
        editor.setSelection(1, 2)
        send(editor, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(0, editor.cursor.leftLine)
        assertEquals(2, editor.cursor.leftColumn)
        send(editor, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(1, editor.cursor.leftLine)
        assertEquals(2, editor.cursor.leftColumn)
        repeat(10) { send(editor, KeyEvent.KEYCODE_DPAD_UP) }
        assertEquals(0, editor.cursor.leftLine)
        repeat(10) { send(editor, KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals(3, editor.cursor.leftLine)
        assertEquals(before, editor.text.toString())
    }

    @Test fun accessoryShiftDoesNotLeakIntoTheFollowingUnmodifiedArrow() = withEditor { editor ->
        val before = editor.text.toString()
        editor.setSelection(1, 2)
        send(editor, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_SHIFT_ON)
        assertTrue(editor.cursor.isSelected)
        assertFalse(editor.keyMetaStates.isShiftPressed)
        editor.setSelection(1, 2)
        send(editor, KeyEvent.KEYCODE_DPAD_DOWN)
        assertFalse(editor.cursor.isSelected)
        assertEquals(2, editor.cursor.leftLine)
        assertEquals(before, editor.text.toString())
    }

    @Test fun accessoryDispatchPreservesAnAlreadyHeldHardwareShift() = withEditor { editor ->
        val shift = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, KeyEvent.META_SHIFT_ON)
        editor.dispatchKeyEvent(shift)
        assertTrue(editor.keyMetaStates.isShiftPressed)
        try {
            editor.setSelection(1, 2)
            send(editor, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_SHIFT_ON)
            assertTrue(editor.cursor.isSelected)
            assertTrue("Accessory cleanup must not release a hardware modifier", editor.keyMetaStates.isShiftPressed)
        } finally {
            editor.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
            editor.keyMetaStates.clearMetaStates(KeyEvent.META_SHIFT_MASK)
        }
    }

    @Test fun terminalAndOtherViewsReceiveOnlyTheRequestedDownAndUpEvents() {
        val received = mutableListOf<KeyEvent>()
        val view = object : View(org.robolectric.RuntimeEnvironment.getApplication()) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean { received += event; return true }
        }
        val modifiers = KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_CTRL_ON
        assertTrue(dispatchAccessoryKey(view, KeyEvent.KEYCODE_DPAD_UP, modifiers))
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), received.map { it.action })
        assertTrue(received.all { it.keyCode == KeyEvent.KEYCODE_DPAD_UP && it.metaState == modifiers })
    }

    @Test fun failedEditorDispatchReleasesOnlyItsSyntheticModifiers() {
        val failure = IllegalStateException("injected key dispatch failure")
        var shiftAtDispatch = false
        var altAtDispatch = false
        val editor = object : io.github.rosemoe.sora.widget.CodeEditor(org.robolectric.RuntimeEnvironment.getApplication()) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                shiftAtDispatch = keyMetaStates.isShiftPressed
                altAtDispatch = keyMetaStates.isAltPressed
                throw failure
            }
        }
        try {
            try {
                dispatchAccessoryKey(editor, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON)
                fail("Dispatch failure must remain visible")
            } catch (actual: IllegalStateException) { assertSame(failure, actual) }
            assertTrue(shiftAtDispatch)
            assertTrue(altAtDispatch)
            assertFalse(editor.keyMetaStates.isShiftPressed)
            assertFalse(editor.keyMetaStates.isAltPressed)
        } finally { editor.release() }
    }

    @Test fun shiftVerticalNavigationUsesSoraSelectionWithoutRewritingContent() = withEditor { editor ->
        val before = editor.text.toString()
        editor.setSelection(1, 2)
        send(editor, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        assertTrue(editor.cursor.isSelected)
        assertEquals(0, editor.cursor.leftLine)
        assertEquals(1, editor.cursor.rightLine)
        assertEquals(before, editor.text.toString())
    }
}
