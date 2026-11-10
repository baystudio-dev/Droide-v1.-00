package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.view.ActionMode
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.baystudio.droide.core.DeviceTerminalEmulator
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.DroideSelectionText
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Authored/unrun: real pinned selection controller and app container; no JNI or terminal process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeTerminalSelectionTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    @Test fun nativeCopyUsesRenderedCoordinatesWithoutTheMenusExtraTrim() = surface { s ->
        runBlocking(Dispatchers.Default) { s.model.appendText("  \u03bb\uD83D\uDE00\r\n\r\nend  ") }
        s.select(0, 0, 5, 2)
        val expected = s.emulator.getSelectedText(0, 0, 5, 2)
        assertTrue(expected.startsWith("  "))
        assertEquals(expected, DroideSelectionText.getSelectedText(s.view.terminalView))
        s.click(1)
        assertEquals(listOf(s.session to expected), s.copied)
        assertFalse(s.view.terminalView.isSelectingText)

    }

    @Test fun failedCopyKeepsNativeHandlesAndRetrySucceeds() = surface { s ->
        runBlocking(Dispatchers.Default) { s.model.appendText("  text") }
        s.acceptCopy = false
        s.select(0, 0, 5, 0)
        s.click(1)
        assertTrue(s.view.terminalView.isSelectingText)
        assertEquals(1, s.copied.size)
        s.acceptCopy = true
        s.click(1)
        assertEquals(2, s.copied.size)
        assertFalse(s.view.terminalView.isSelectingText)
    }

    @Test fun oldMenuCannotCopyOrPasteAfterSessionSwitchAndReturn() = surface { s ->
        s.select(0, 0, 1, 0)
        val callback = s.root.callback!!
        val mode = s.root.mode!!
        val replacement = s.newSession()
        s.view.attachSession(replacement)
        s.view.attachSession(s.session)
        callback.onActionItemClicked(mode, mode.menu.findItem(1))
        callback.onActionItemClicked(mode, mode.menu.findItem(2))
        assertTrue(s.copied.isEmpty())
        assertTrue(s.pasted.isEmpty())
    }

    @Test fun oldMenuCannotCopyAfterCallbackReplacementHideDetachOrRelease() = surface { s ->
        s.select(0, 0, 1, 0)
        val callback = s.root.callback!!
        val mode = s.root.mode!!
        s.view.copySelection = { _, _ -> fail("Replacement must not receive old menu"); false }
        callback.onActionItemClicked(mode, mode.menu.findItem(1))
        s.view.visibility = View.GONE
        s.view.visibility = View.VISIBLE
        callback.onActionItemClicked(mode, mode.menu.findItem(2))
        s.root.removeView(s.view)
        callback.onActionItemClicked(mode, mode.menu.findItem(1))
        s.view.release()
        callback.onActionItemClicked(mode, mode.menu.findItem(2))
        assertTrue(s.copied.isEmpty())
        assertTrue(s.pasted.isEmpty())
    }

    @Test fun nativeMenuPasteKeepsOwnerCallbackAndNormalSelectionLifecycle() = surface { s ->
        s.select(0, 0, 1, 0)

        s.click(2)
        assertEquals(listOf(s.session), s.pasted)
        assertFalse(s.view.terminalView.isSelectingText)
        assertTrue(s.copied.isEmpty())
    }

    @Test fun middleMouseReroutesOnceAndCannotReplayIntoReplacementSession() = surface { s ->
        mouse(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_TERTIARY) { assertTrue(s.view.dispatchTouchEvent(it)) }
        assertEquals(listOf(s.session), s.pasted)
        s.view.attachSession(s.newSession())
        mouse(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_TERTIARY, MotionEvent.BUTTON_TERTIARY) {
            assertTrue(s.view.dispatchGenericMotionEvent(it))
        }
        repeat(4) {
            mouse(MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_TERTIARY) { assertTrue(s.view.dispatchTouchEvent(it)) }
        }
        mouse(MotionEvent.ACTION_UP, 0) { assertTrue(s.view.dispatchTouchEvent(it)) }
        assertEquals(listOf(s.session), s.pasted)
        s.root.removeView(s.view)
        mouse(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_TERTIARY, down = 2) { s.view.dispatchTouchEvent(it) }
        assertEquals(listOf(s.session), s.pasted)
    }

    private class CapturingRoot(context: android.content.Context) : FrameLayout(context) {
        var callback: ActionMode.Callback? = null
        var mode: TestMode? = null
        override fun startActionModeForChild(originalView: View, callback: ActionMode.Callback, type: Int): ActionMode? {
            assertEquals(ActionMode.TYPE_FLOATING, type)
            this.callback = callback
            val mode = TestMode(context)
            this.mode = mode
            return mode.takeIf { callback.onCreateActionMode(it, it.menu) }
        }
    }

    private class Surface(val view: DroideNativeTerminalView, val root: CapturingRoot,
        val model: DeviceTerminalEmulator, val emulator: TerminalEmulator) {
        val copied = mutableListOf<Pair<TerminalSession, String>>()
        val pasted = mutableListOf<TerminalSession>()
        var acceptCopy = true
        val session = newSession()
        fun newSession(): TerminalSession {
            val delegate = Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader,
                arrayOf(TerminalSessionClient::class.java)) { _, method, args ->
                if (method.name == "onPasteTextFromClipboard") pasted += args!![0] as TerminalSession
                null
            } as TerminalSessionClient
            val session = TerminalSession("unused", "/", emptyArray(), emptyArray(), 100, delegate)
            TerminalSession::class.java.getDeclaredField("mEmulator").apply { isAccessible = true }.set(session, emulator)
            return session
        }
        fun select(x1: Int, y1: Int, x2: Int, y2: Int) {
            assertTrue(view.terminalView.requestFocus())
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 4f, 4f, 0)
            try { view.terminalView.startTextSelectionMode(event) } finally { event.recycle() }
            assertTrue(view.terminalView.isSelectingText)
            // Fixture-only reflection positions real handles exactly; production bridge uses package API.
            val controller = view.terminalView.javaClass.getDeclaredMethod("getTextSelectionCursorController")
                .apply { isAccessible = true }.invoke(view.terminalView)
            for ((name, value) in mapOf("mSelX1" to x1, "mSelY1" to y1, "mSelX2" to x2, "mSelY2" to y2)) {
                controller.javaClass.getDeclaredField(name).apply { isAccessible = true }.setInt(controller, value)
            }
            controller.javaClass.getDeclaredField("mShowStartTime").apply { isAccessible = true }
                .setLong(controller, System.currentTimeMillis() - 1_000)
        }
        fun click(id: Int) {
            val mode = root.mode!!
            assertTrue(root.callback!!.onActionItemClicked(mode, mode.menu.findItem(id)))
        }
    }

    private fun surface(body: (Surface) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = DroideNativeTerminalView(activity.get())
        val model = DeviceTerminalEmulator({}, 80, 24)
        val emulator = DeviceTerminalEmulator::class.java.getDeclaredField("emulator")
            .apply { isAccessible = true }.get(model) as TerminalEmulator
        val root = CapturingRoot(activity.get())
        val s = Surface(view, root, model, emulator)
        view.setTerminalViewClient(DroideTerminalViewClient(activity.get(), { view.terminalView }, inputViewProvider = { view }))
        view.setTextSize(14)
        view.copySelection = { session, text -> s.copied += session to text; s.acceptCopy }
        root.addView(view, FrameLayout.LayoutParams(320, 480))
        activity.get().setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 320, 480)
        view.attachSession(s.session)
        activity.windowFocusChanged(true)
        try { body(s) } finally {
            view.terminalView.stopTextSelectionMode()
            view.release()
            root.removeAllViews()
            activity.pause().stop().destroy()
        }
    }
}
