package com.baystudio.droide.core

import android.app.Application
import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserNativeInputTest {
    private class InputView(context: Context) : WebView(context) {
        data class Touch(val action: Int, val x: Float, val y: Float, val tool: Int)
        val events = mutableListOf<Touch>()
        val keys = mutableListOf<Pair<Int, Int>>()
        override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?) = true
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            events += Touch(event.actionMasked, event.x, event.y, event.getToolType(0)); return true
        }
        override fun dispatchGenericMotionEvent(event: MotionEvent) = dispatchTouchEvent(event)
        override fun dispatchKeyEvent(event: KeyEvent): Boolean { keys += event.action to event.keyCode; return true }
    }
    private fun view(): InputView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return InputView(activity).apply {
            activity.setContentView(this); setPadding(10, 20, 10, 20)
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 800)
            // ShadowWebView does not run Chromium layout. Set the real View bounds for this input transport fixture.
            left = 0; top = 0; right = 400; bottom = 800
            assertEquals(400, width); assertEquals(800, height)
        }
    }

    @Test fun clickUsesOneNativeDownUpAtTheViewportPoint() = runBlocking {
        val view = view()
        try {
            AgentBrowserNativeInput.gesture(view, AgentBrowserNativeInput.Point(.5f, .25f), dispatch = { it() }, cancel = { it() })
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), view.events.map { it.action })
            assertEquals(200f, view.events[0].x, .01f); assertEquals(210f, view.events[0].y, .01f)
            assertEquals(MotionEvent.TOOL_TYPE_FINGER, view.events[0].tool)
        } finally { view.destroy() }
    }

    @Test fun swipeUsesOrderedMotionAndDragUsesMouseInput() = runBlocking {
        for (mouse in listOf(false, true)) {
            val view = view()
            try {
                AgentBrowserNativeInput.gesture(view, AgentBrowserNativeInput.Point(.2f, .8f), AgentBrowserNativeInput.Point(.8f, .2f),
                    durationMs = 100, mouse = mouse, dispatch = { it() }, cancel = { it() })
                assertEquals(MotionEvent.ACTION_DOWN, view.events.first().action)
                assertEquals(MotionEvent.ACTION_UP, view.events.last().action)
                assertTrue(view.events.count { it.action == MotionEvent.ACTION_MOVE } >= 2)
                assertEquals(if (mouse) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER, view.events.first().tool)
                assertTrue(view.events.first().y > view.events.last().y)
            } finally { view.destroy() }
        }
    }

    @Test fun revokedAuthorityCancelsPartlyDeliveredGestureWithoutAnUpOrReplay() = runBlocking {
        val view = view(); var calls = 0
        try {
            val result = runCatching {
                AgentBrowserNativeInput.gesture(view, AgentBrowserNativeInput.Point(.2f, .8f), AgentBrowserNativeInput.Point(.8f, .2f),
                    durationMs = 100, dispatch = { block -> if (++calls > 1) throw CancellationException("revoked"); block() }, cancel = { it() })
            }
            assertTrue(result.isFailure)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), view.events.map { it.action })
        } finally { view.destroy() }
    }

    @Test fun keyboardUsesNativeEventsAndDoesNotExposeSystemNavigationKeys() {
        val view = view()
        try {
            val selectAll = AgentBrowserNativeInput.key("Ctrl+A")
            assertEquals(KeyEvent.META_CTRL_ON, selectAll.modifiers)
            AgentBrowserNativeInput.press(view, selectAll)
            assertEquals(listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_A), view.keys)
            assertEquals(KeyEvent.KEYCODE_MOVE_HOME, AgentBrowserNativeInput.key("Home").code)
            for (invalid in listOf("Back", "Power", "a", "Ctrl+", "Unknown+Enter", "Ctrl+V", "Meta+C", "Ctrl+Shift+X", "Shift+Delete"))
                assertTrue(invalid, runCatching { AgentBrowserNativeInput.key(invalid) }.isFailure)
        } finally { view.destroy() }
    }

    @Test fun outOfRangeAndNonFiniteCoordinatesAreRejected() {
        for (point in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f))
            assertTrue(runCatching { AgentBrowserNativeInput.Point(point, .5f) }.isFailure)
    }

    @Test fun shortGestureCannotBecomeAnAccidentalClick() = runBlocking {
        val view = view()
        try {
            assertTrue(runCatching {
                AgentBrowserNativeInput.gesture(view, AgentBrowserNativeInput.Point(.5f, .5f), AgentBrowserNativeInput.Point(.501f, .5f),
                    durationMs = 100, dispatch = { it() }, cancel = { it() })
            }.isFailure)
            assertTrue(view.events.isEmpty())
        } finally { view.destroy() }
    }

    @Test fun changedTargetBeforeDispatchDoesNotSendAPointer() = runBlocking {
        val view = view()
        try {
            val prepared = "{\"ok\":true,\"target\":\"d1\",\"prepared\":true,\"point\":{\"x\":0.5,\"y\":0.5},\"bounds\":{}}"
            val runner = AgentBrowserActions(view, evaluate = { script ->
                if (script.contains("const r=D.resolve")) "{\"ok\":false,\"code\":\"target_changed\"}" else prepared
            }, dispatch = { it() }, cancel = { it() })
            assertEquals("false", runner.execute("click", "#target", null, false, BrowserActionOptions())["ok"].toString())
            assertTrue(view.events.isEmpty())
        } finally { view.destroy() }
    }
}
