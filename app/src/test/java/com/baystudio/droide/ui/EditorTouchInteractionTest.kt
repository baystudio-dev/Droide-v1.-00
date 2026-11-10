package com.baystudio.droide.ui

import android.app.Application
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorTouchInteractionTest {
    @Test fun horizontalDragScrollsCodeAndUpOrCancelReturnsTouchToParent() {
        val context = RuntimeEnvironment.getApplication()
        val requests = mutableListOf<Boolean>()
        val parent = object : FrameLayout(context) {
            override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                requests += disallowIntercept
                super.requestDisallowInterceptTouchEvent(disallowIntercept)
            }
        }
        val editor = DroideCodeEditor(context, "plaintext",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test"))
        parent.addView(editor)
        editor.setText("long code ".repeat(300))
        parent.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 400, 220)
        fun touch(action: Int, x: Float, time: Long) {
            val event = MotionEvent.obtain(100, time, action, x, 80f, 0)
            try { assertTrue(editor.onTouchEvent(event)) } finally { event.recycle() }
        }
        try {
            touch(MotionEvent.ACTION_DOWN, 350f, 100)
            assertTrue(requests.last())
            touch(MotionEvent.ACTION_MOVE, 240f, 180)
            touch(MotionEvent.ACTION_MOVE, 120f, 260)
            editor.scroller.computeScrollOffset()
            assertTrue("Dragging must move the editor horizontally", editor.offsetX > 0)
            touch(MotionEvent.ACTION_UP, 120f, 340)
            assertFalse(requests.last())
            touch(MotionEvent.ACTION_DOWN, 250f, 500)
            touch(MotionEvent.ACTION_CANCEL, 250f, 550)
            assertFalse(requests.last())
        } finally { editor.release() }
        val textAfterRelease = editor.text.toString()
        editor.commitText("late IME input", false)
        editor.deleteText()
        assertEquals("Late IME edits must not mutate a released buffer", textAfterRelease, editor.text.toString())
        val event = MotionEvent.obtain(600, 600, MotionEvent.ACTION_DOWN, 100f, 80f, 0)
        try { assertFalse("Released editor must reject late touches", editor.onTouchEvent(event)) }
        finally { event.recycle() }
    }
}
