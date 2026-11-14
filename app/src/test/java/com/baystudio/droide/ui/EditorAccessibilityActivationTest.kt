package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorAccessibilityActivationTest {
    private class ClickProbe(view: View) : View.AccessibilityDelegate() {
        var listeners = 0
        var events = 0
        init {
            view.setOnClickListener { listeners++ }
            view.accessibilityDelegate = this
        }
        override fun sendAccessibilityEvent(host: View, eventType: Int) {
            if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) events++
            super.sendAccessibilityEvent(host, eventType)
        }
    }

    private fun withEditor(block: (FrameLayout, DroideCodeEditor, InputMethodManager) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val container = FrameLayout(controller.get()).also {
            it.isFocusableInTouchMode = true
            controller.get().setContentView(it)
        }
        val editor = DroideCodeEditor(controller.get(), "plaintext",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test"))
        val ime = controller.get().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        try {
            editor.setCursorAnimationEnabled(false)
            editor.setLineNumberEnabled(false)
            editor.setText("alpha beta gamma\nsecond line")
            container.addView(editor, FrameLayout.LayoutParams(400, 220))
            editor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY))
            editor.layout(0, 0, 400, 220)
            // Sora computes layout on a worker, then posts completion to Main. Draining
            // Main once does not guarantee that the editor can already accept input.
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            do {
                shadowOf(Looper.getMainLooper()).idle()
                if (editor.isEditable) break
                Thread.sleep(10)
            } while (System.nanoTime() < deadline)
            assertTrue("The real Sora layout must finish before testing input activation", editor.isEditable)
            container.requestFocus()
            ime.hideSoftInputFromWindow(editor.windowToken, 0)
            assertFalse(shadowOf(ime).isSoftInputVisible)
            block(container, editor, ime)
        } finally {
            container.removeView(editor)
            editor.release()
            controller.pause().stop().destroy()
        }
    }

    private fun touch(editor: DroideCodeEditor, down: Long, time: Long, action: Int, x: Float) {
        val event = MotionEvent.obtain(down, time, action, x, editor.rowHeight / 2f, 0)
        try { assertTrue(editor.onTouchEvent(event)) } finally { event.recycle() }
    }

    @Test fun accessibilityClickFocusesAndShowsImeWithoutMovingSelectionOrEditingText() = withEditor { _, editor, ime ->
        editor.setSelectionRegion(0, 1, 0, 4)
        val before = editor.text.toString()
        val info = editor.createAccessibilityNodeInfo()
        try {
            assertTrue(info.isClickable)
            assertTrue(info.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
        } finally { info.recycle() }
        val clicks = ClickProbe(editor)
        assertTrue(editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, clicks.listeners)
        assertEquals(1, clicks.events)
        assertTrue(editor.isFocused)
        assertTrue(shadowOf(ime).isSoftInputVisible)
        assertEquals(1, editor.cursor.left)
        assertEquals(4, editor.cursor.right)
        assertEquals(before, editor.text.toString())
    }

    @Test fun soraTapPublishesExactlyOneClickAndKeepsItsCaretAndImeBehavior() = withEditor { _, editor, ime ->
        val clicks = ClickProbe(editor)
        val now = SystemClock.uptimeMillis()
        val x = editor.getCharOffsetX(0, 3)
        touch(editor, now, now, MotionEvent.ACTION_DOWN, x)
        touch(editor, now, now + 30, MotionEvent.ACTION_UP, x)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals("Sora owns touch click dispatch; Droide must not repeat it", 1, clicks.listeners)
        assertEquals(1, clicks.events)
        assertTrue(editor.isFocused)
        assertTrue(shadowOf(ime).isSoftInputVisible)
        assertFalse(editor.cursor.isSelected)
        assertTrue("Sora should still place the caret in the tapped text", editor.cursor.left > 0)
        assertEquals("alpha beta gamma\nsecond line", editor.text.toString())
    }

    @Test fun movementAndCancellationDoNotBecomeClicksOrImeRetries() = withEditor { _, editor, ime ->
        val clicks = ClickProbe(editor)
        val now = SystemClock.uptimeMillis()
        touch(editor, now, now, MotionEvent.ACTION_DOWN, 250f)
        touch(editor, now, now + 40, MotionEvent.ACTION_MOVE, 120f)
        touch(editor, now, now + 80, MotionEvent.ACTION_UP, 120f)
        touch(editor, now + 600, now + 600, MotionEvent.ACTION_DOWN, 120f)
        touch(editor, now + 600, now + 620, MotionEvent.ACTION_CANCEL, 120f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertEquals(0, clicks.listeners)
        assertEquals(0, clicks.events)
        assertFalse(shadowOf(ime).isSoftInputVisible)
    }

    @Test fun longPressKeepsSelectionWithoutBecomingClickOrImeRetry() = withEditor { _, editor, ime ->
        val clicks = ClickProbe(editor)
        val now = SystemClock.uptimeMillis()
        val x = editor.getCharOffsetX(0, 2)
        touch(editor, now, now, MotionEvent.ACTION_DOWN, x)
        val hold = ViewConfiguration.getLongPressTimeout().toLong() + 1
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(hold))
        touch(editor, now, now + hold, MotionEvent.ACTION_UP, x)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, clicks.listeners)
        assertEquals(0, clicks.events)
        assertTrue(editor.cursor.isSelected)
        assertFalse(shadowOf(ime).isSoftInputVisible)
    }

    @Test fun readOnlyActivationFocusesWithoutKeyboardOrSelectionChange() = withEditor { _, editor, ime ->
        editor.applyReviewMode(true)
        editor.setSelectionRegion(0, 1, 0, 4)
        val clicks = ClickProbe(editor)
        assertTrue(editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, clicks.listeners)
        assertEquals(1, clicks.events)
        assertTrue(editor.isFocused)
        assertFalse(shadowOf(ime).isSoftInputVisible)
        assertEquals(1, editor.cursor.left)
        assertEquals(4, editor.cursor.right)
    }

    @Test fun hiddenDisabledDetachedFailedAndReleasedEditorsRejectActivation() = withEditor { container, editor, ime ->
        val clicks = ClickProbe(editor)
        fun assertRejected() {
            assertFalse(editor.performClick())
            assertFalse(editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, clicks.listeners)
            assertEquals(0, clicks.events)
            assertFalse(shadowOf(ime).isSoftInputVisible)
        }
        editor.visibility = View.INVISIBLE
        assertRejected()
        editor.visibility = View.VISIBLE
        editor.isEnabled = false
        assertRejected()
        editor.isEnabled = true
        container.removeView(editor)
        assertRejected()
        container.addView(editor)
        editor.onEditorFailure = {}
        editor.reportSurfaceFailure(IllegalStateException("Test surface failure"))
        assertRejected()
        editor.release()
        assertRejected()
    }

    @Test fun queuedActivationCannotReopenImeAfterHideDetachFocusLossOrRelease() = withEditor { container, editor, ime ->
        assertTrue(editor.performClick())
        editor.visibility = View.INVISIBLE
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(ime).isSoftInputVisible)
        editor.visibility = View.VISIBLE
        assertTrue(editor.performClick())
        container.removeView(editor)
        container.addView(editor)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("A stale request must not survive reattachment", shadowOf(ime).isSoftInputVisible)
        assertTrue(editor.performClick())
        container.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Losing focus cancels a queued tap request", shadowOf(ime).isSoftInputVisible)
        assertTrue(editor.performClick())
        editor.release()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(ime).isSoftInputVisible)
    }
}
