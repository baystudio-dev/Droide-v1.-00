package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.baystudio.droide.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceTerminalLinkInteractionTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    private class Session : DevicePtySessionHandle {
        val input = mutableListOf<String>()
        override val terminalEmulator = DeviceTerminalEmulator({ bytes -> input += bytes.decodeToString() }, 40, 24)
        override val output = MutableStateFlow("")
        override val terminalTitle = terminalEmulator.title
        override val workingDirectory = terminalEmulator.workingDirectory
        override val profileLabel = "test PTY"
        override val status = MutableStateFlow("Ready")
        var destroyed = 0
        override fun start() = Unit
        override fun send(cmd: String) { input += cmd }
        override fun writeRaw(text: String) { input += text }
        override fun sendKeyCode(keyCode: Int, modifiers: Int) = Unit
        override fun resize(columns: Int, rows: Int) { terminalEmulator.resize(columns, rows) }
        override suspend fun refreshWorkspace() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
        override fun interrupt() = Unit
        override fun clear() = terminalEmulator.resetAndClear()
        override fun destroy() { destroyed++ }
    }

    private fun mount(container: FrameLayout, session: Session): DeviceTerminalView {
        val view = DeviceTerminalView(container.context)
        container.addView(view, FrameLayout.LayoutParams(480, 300))
        view.attachSession(session)
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 480, 300)
        return view
    }

    private fun tapUrl(view: DeviceTerminalView, session: Session, inTopMargin: Boolean = false) {
        val x = (view.width.toFloat() / session.terminalEmulator.columns()) * 8.5f
        val y = if (inTopMargin) 0f else (view.height.toFloat() / session.terminalEmulator.rows()) * 0.7f
        val now = SystemClock.uptimeMillis()
        for ((action, delta) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 20L)) {
            val event = MotionEvent.obtain(now, now + delta, action, x, y, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
    }

    @Test fun browserRoundTripAndViewReplacementKeepPendingInputInTheSameSession() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val container = FrameLayout(controller.get()).also { controller.get().setContentView(it) }
        val session = Session()
        var view = mount(container, session)
        val url = "https://accounts.example.test/auth?state=A%2BB&redirect_uri=http%3A%2F%2Flocalhost%2F"
        try {
            session.terminalEmulator.appendFromWorker("$url\r\nEnter authorization code:")
            tapUrl(view, session)
            assertEquals(url, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.dataString)
            assertTrue(session.input.isEmpty())
            controller.pause().stop()
            view.release(); container.removeView(view)
            controller.start().resume().visible()
            view = mount(container, session)
            view.onCreateInputConnection(EditorInfo()).commitText("sample-auth-code\n", 1)
            assertEquals("sample-auth-code\r", session.input.joinToString(""))
            assertEquals(0, session.destroyed)
            assertTrue(session.terminalEmulator.snapshotText().contains("Enter authorization code:"))
        } finally { view.release(); container.removeView(view); controller.pause().stop().destroy() }
    }

    @Test fun mouseOwnedTuiCannotAccidentallyOpenItsPrintedUrl() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val container = FrameLayout(controller.get()).also { controller.get().setContentView(it) }
        val session = Session()
        val view = mount(container, session)
        try {
            session.terminalEmulator.appendFromWorker("https://example.test/\u001b[?1000h")
            tapUrl(view, session)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertEquals("TUI must receive one press and one release", 2, session.input.size)
            assertTrue(session.input.all { it.startsWith("\u001b[M") })
            assertEquals(0, session.destroyed)
        } finally { view.release(); container.removeView(view); controller.pause().stop().destroy() }
    }

    @Test fun topMarginAndHiddenOrDetachedViewCannotOpenBrowser() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val container = FrameLayout(controller.get()).also { controller.get().setContentView(it) }
        val session = Session()
        val view = mount(container, session)
        try {
            session.terminalEmulator.appendFromWorker("https://example.test/")
            tapUrl(view, session, inTopMargin = true)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            view.visibility = View.INVISIBLE
            tapUrl(view, session)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            view.visibility = View.VISIBLE
            container.removeView(view)
            tapUrl(view, session)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertTrue(session.input.isEmpty())
        } finally { view.release(); controller.pause().stop().destroy() }
    }

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

    private fun withTerminal(block: (FrameLayout, DeviceTerminalView, Session, InputMethodManager) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val container = FrameLayout(controller.get()).also {
            it.isFocusableInTouchMode = true
            controller.get().setContentView(it)
        }
        val session = Session()
        val view = mount(container, session)
        val ime = controller.get().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        try {
            shadowOf(Looper.getMainLooper()).idle()
            container.requestFocus()
            ime.hideSoftInputFromWindow(view.windowToken, 0)
            assertFalse(shadowOf(ime).isSoftInputVisible)
            block(container, view, session, ime)
        } finally {
            view.release()
            container.removeView(view)
            controller.pause().stop().destroy()
        }
    }

    @Test fun accessibilityClickFocusesTerminalWithoutInventingTuiCoordinatesOrOpeningUrl() = withTerminal { _, view, session, ime ->
        session.terminalEmulator.appendFromWorker("https://example.test/\u001b[?1000h")
        val info = view.createAccessibilityNodeInfo()
        try {
            assertTrue(info.isClickable)
            assertTrue(info.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
        } finally { info.recycle() }
        val clicks = ClickProbe(view)
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, clicks.listeners)
        assertEquals(1, clicks.events)
        assertTrue(view.isFocused)
        assertTrue(shadowOf(ime).isSoftInputVisible)
        assertTrue("Activation has no cell coordinates and must not write TUI input", session.input.isEmpty())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun touchTuiClickNotifiesOnceWithoutAddingKeyboardOrAnotherPressRelease() = withTerminal { _, view, session, ime ->
        session.terminalEmulator.appendFromWorker("https://example.test/\u001b[?1000h")
        val clicks = ClickProbe(view)
        tapUrl(view, session)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, clicks.listeners)
        assertEquals(1, clicks.events)
        assertEquals(2, session.input.size)
        assertEquals("\u001b[M", session.input[0].take(3))
        assertEquals(' '.code, session.input[0][3].code)
        assertEquals('#'.code, session.input[1][3].code)
        assertFalse(shadowOf(ime).isSoftInputVisible)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun urlTapNotifiesOnceWithoutSummoningKeyboard() = withTerminal { _, view, session, ime ->
        val url = "https://example.test/"
        session.terminalEmulator.appendFromWorker(url)
        val clicks = ClickProbe(view)
        tapUrl(view, session)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, clicks.listeners)
        assertEquals(1, clicks.events)
        assertEquals(url, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.dataString)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertFalse(shadowOf(ime).isSoftInputVisible)
        assertTrue(session.input.isEmpty())
    }

    @Test fun ordinaryTerminalTapNotifiesOnceAndShowsKeyboard() = withTerminal { _, view, session, ime ->
        val clicks = ClickProbe(view)
        tapUrl(view, session, inTopMargin = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, clicks.listeners)
        assertEquals(1, clicks.events)
        assertTrue(view.isFocused)
        assertTrue(shadowOf(ime).isSoftInputVisible)
        assertTrue(session.input.isEmpty())
    }

    @Test fun osc8AuthenticationLabelOpensWithoutWritingToTerminal() = withTerminal { _, view, session, ime ->
        val url = "https://accounts.example.test/auth?state=original%2Bstate%3D"
        session.terminalEmulator.appendFromWorker("\u001b]8;;$url\u0007Click here to authenticate\u001b]8;;\u0007")
        tapUrl(view, session)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(url, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.dataString)
        assertFalse(shadowOf(ime).isSoftInputVisible)
        assertTrue(session.input.isEmpty())
    }

    @Test fun selectedLoginFragmentResolvesToWholeUrlWithoutChangingTextSelection() = withTerminal { _, _, session, _ ->
        val url = "https://accounts.example.test/auth?state=" + "a%2Bb%3D".repeat(25)
        val model = session.terminalEmulator
        model.appendFromWorker("\u001b[4;34m" + url.chunked(model.columns()).joinToString("\r\n") + "\u001b[0m")
        val selection = DeviceTerminalEmulator.Selection(2, 1, 8, 1)
        assertEquals(url, model.selectedLink(selection))
        assertEquals(7, model.selectedText(selection).length)
        assertTrue(session.input.isEmpty())
    }

    @Test fun hiddenDetachedDisabledAndReleasedTerminalsRejectActivation() = withTerminal { container, view, session, ime ->
        session.terminalEmulator.appendFromWorker("https://example.test/\u001b[?1000h")
        val clicks = ClickProbe(view)
        fun assertRejected() {
            assertFalse(view.performClick())
            assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            tapUrl(view, session)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, clicks.listeners)
            assertEquals(0, clicks.events)
            assertFalse(shadowOf(ime).isSoftInputVisible)
            assertTrue(session.input.isEmpty())
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        }
        view.visibility = View.INVISIBLE
        assertRejected()
        view.visibility = View.VISIBLE
        view.isEnabled = false
        assertRejected()
        view.isEnabled = true
        container.removeView(view)
        assertRejected()
        container.addView(view)
        view.release()
        assertRejected()
    }

    @Test fun queuedTerminalActivationCannotReopenKeyboardAfterHideDetachOrRelease() = withTerminal { container, view, _, ime ->
        assertTrue(view.performClick())
        view.visibility = View.INVISIBLE
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(ime).isSoftInputVisible)
        view.visibility = View.VISIBLE
        assertTrue(view.performClick())
        container.removeView(view)
        container.addView(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Detach invalidates even a quick reattach", shadowOf(ime).isSoftInputVisible)
        assertTrue(view.performClick())
        view.release()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(ime).isSoftInputVisible)
    }

    @Test fun terminalDragAndCancelDoNotPublishClicksOrOpenKeyboard() = withTerminal { _, view, session, ime ->
        val clicks = ClickProbe(view)
        val now = SystemClock.uptimeMillis()
        for ((action, x, delta) in listOf(
            Triple(MotionEvent.ACTION_DOWN, 200f, 0L),
            Triple(MotionEvent.ACTION_MOVE, 80f, 40L),
            Triple(MotionEvent.ACTION_UP, 80f, 80L),
        )) {
            val event = MotionEvent.obtain(now, now + delta, action, x, 80f, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
            val event = MotionEvent.obtain(now + 600, now + 600 + action * 10L, action, 80f, 80f, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, clicks.listeners)
        assertEquals(0, clicks.events)
        assertFalse(shadowOf(ime).isSoftInputVisible)
        assertTrue(session.input.isEmpty())
    }

    @Test fun physicalMouseKeepsItsOwnPressReleasePath() = withTerminal { _, view, session, ime ->
        session.terminalEmulator.appendFromWorker("https://example.test/\u001b[?1000h")
        val clicks = ClickProbe(view)
        val now = SystemClock.uptimeMillis()
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { x = 20f; y = 20f; pressure = 1f; size = 1f }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + action * 20L, action, 1, arrayOf(pointer), arrayOf(coords),
                0, if (action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, session.input.size)
        assertEquals(0, clicks.listeners)
        assertEquals(0, clicks.events)
        assertFalse(shadowOf(ime).isSoftInputVisible)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

}

/** Test fixture only: production PTY readers decode off the Android Looper. */
private fun com.baystudio.droide.core.DeviceTerminalEmulator.appendFromWorker(text: String) =
    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) { appendText(text) }
