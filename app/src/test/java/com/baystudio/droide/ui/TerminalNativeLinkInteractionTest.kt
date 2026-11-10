package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.baystudio.droide.core.DeviceTerminalEmulator
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TerminalNativeLinkInteractionTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    private val oauth = "https://accounts.example.test/o/oauth2/v2/auth?redirect_uri=https%3A%2F%2Fapp.test%2Fcallback" +
        "&scope=openid%20email&code_challenge=Fictional_ABC-123~xyz&state=A%2BB%2FC%3D&response_type=code"

    private class Surface(val activity: Activity, width: Int, height: Int, rows: Int) {
        val writes = mutableListOf<ByteArray>()
        val model = DeviceTerminalEmulator({ writes += it.copyOf() }, 80, rows)

        val emulator = DeviceTerminalEmulator::class.java.getDeclaredField("emulator").let {
            it.isAccessible = true
            it.get(model) as TerminalEmulator
        }
        val view = TerminalView(activity, null).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setTextSize(14)
        }
        val container = FrameLayout(activity)
        var currentView: TerminalView? = view
        val firstLineOffset = Paint(Paint.ANTI_ALIAS_FLAG).run {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            ceil(fontSpacing.toDouble()).toInt() + ceil(ascent().toDouble()).toInt()
        }

        init {
            val client = DroideTerminalViewClient(activity, { currentView })
            view.setTerminalViewClient(client)
            container.addView(view, FrameLayout.LayoutParams(width, height))
            activity.setContentView(container)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)

            assertTrue("Native font metrics must be used for cell hit testing", view.mRenderer.fontWidth > 1f)
            model.resize(floor(width / view.mRenderer.fontWidth).toInt().coerceAtLeast(4), rows)
            view.mEmulator = emulator
            view.requestFocus()
        }

        fun point(column: Int, visibleRow: Int): Pair<Float, Float> =
            (column + 0.5f) * view.mRenderer.fontWidth to
                firstLineOffset + (visibleRow + 0.5f) * view.mRenderer.fontLineSpacing

        fun touch(column: Int, visibleRow: Int) {
            val (x, y) = point(column, visibleRow)
            queueTouchAt(x, y)
            settleTouch()
        }

        fun queueTouch(column: Int, visibleRow: Int) {
            val (x, y) = point(column, visibleRow)
            queueTouchAt(x, y)
        }

        fun queueTouchAt(x: Float, y: Float) {
            val down = SystemClock.uptimeMillis()
            event(down, down, MotionEvent.ACTION_DOWN, x, y).useEvent(view::dispatchTouchEvent)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y).useEvent(view::dispatchTouchEvent)
        }

        fun settleTouch() {
            // Termux waits for a confirmed single tap so pinch/double-tap gestures do not activate links.
            // Advance only the virtual main Looper.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        }

        fun select(column: Int, visibleRow: Int) {
            val (x, y) = point(column, visibleRow)
            val now = SystemClock.uptimeMillis()
            event(now, now, MotionEvent.ACTION_DOWN, x, y).useEvent { view.startTextSelectionMode(it); true }
        }

        fun release() {
            view.setTerminalCursorBlinkerState(false, false)
            container.removeAllViews()
            currentView = null
            view.mEmulator = null
        }

        private fun event(down: Long, time: Long, action: Int, x: Float, y: Float) =
            MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }

        private fun MotionEvent.useEvent(block: (MotionEvent) -> Boolean) {
            try { block(this) } finally { recycle() }
        }
    }

    private fun withSurface(width: Int, height: Int, rows: Int = 24, block: (Surface) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val surface = Surface(controller.get(), width, height, rows)
        controller.windowFocusChanged(true)
        assertTrue("Fixture must own a focused window", surface.view.hasWindowFocus())
        try { block(surface) } finally {
            surface.release()
            controller.pause().stop().destroy()
        }
    }

    private fun assertOpened(surface: Surface, expected: String) {
        val intent = shadowOf(surface.activity).nextStartedActivity
        assertNotNull("A tap on terminal URL should open a browser", intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(expected, intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull("One tap must not open twice", shadowOf(surface.activity).nextStartedActivity)
    }

    @Test fun portraitTapOnWrappedAuthorizationUrlPreservesTheExactDestination() = withSurface(320, 640) { surface ->
        surface.model.appendFromWorker(oauth + "\r\nEnter authorization code:")
        assertTrue(oauth.length > surface.emulator.mColumns)
        surface.touch(8, 1)
        assertOpened(surface, oauth)
        assertTrue("Opening a URL must not send bytes to the shell", surface.writes.isEmpty())
    }

    @Test fun landscapeTapOnWrappedAuthorizationUrlPreservesTheExactDestination() = withSurface(740, 320) { surface ->
        surface.model.appendFromWorker(oauth + "\r\nEnter authorization code:")
        assertTrue(oauth.length > surface.emulator.mColumns)
        surface.touch(8, 1)
        assertOpened(surface, oauth)
        assertTrue(surface.writes.isEmpty())
    }

    @Test fun nativeScrollbackCoordinatesOpenTheOlderWrappedLink() = withSurface(320, 320, rows = 4) { surface ->
        surface.model.appendFromWorker(oauth + "\r\nEnter authorization code:\r\n")
        val history = surface.emulator.screen.activeTranscriptRows
        assertTrue(history > 0)
        surface.view.setTopRow(-history)
        surface.touch(8, 0)
        assertOpened(surface, oauth)
    }

    @Test fun tappingToEndSelectionDoesNotAlsoActivateTheUrl() = withSurface(320, 640) { surface ->
        val url = "https://example.test/login"
        surface.model.appendFromWorker(url)
        surface.select(8, 0)
        assertTrue(surface.view.isSelectingText)

        Thread.sleep(320)
        surface.touch(8, 0)
        assertFalse(surface.view.isSelectingText)
        assertNull(shadowOf(surface.activity).nextStartedActivity)
        surface.touch(8, 0)
        assertOpened(surface, url)
    }

    @Test fun mouseTrackingOwnsTheGestureAndReceivesMouseBytesInsteadOfOpeningBrowser() = withSurface(320, 640) { surface ->
        val url = "https://example.test/login"
        surface.model.appendFromWorker(url + "\u001b[?1000h")
        assertTrue(surface.emulator.isMouseTrackingActive)
        surface.touch(8, 0)
        assertNull(shadowOf(surface.activity).nextStartedActivity)
        assertTrue("The TUI must receive the mouse press/release", surface.writes.size >= 2)
        assertTrue(surface.writes.all { String(it, Charsets.UTF_8).startsWith("\u001b[M") })
        surface.model.appendFromWorker("\u001b[?1000l")
        surface.touch(8, 0)
        assertOpened(surface, url)
    }

    @Test fun pendingTapCannotBeRedirectedToAReplacementView() = withSurface(320, 640) { surface ->
        val url = "https://example.test/login"
        surface.model.appendFromWorker(url)
        surface.queueTouch(8, 0)
        assertNull(shadowOf(surface.activity).nextStartedActivity)
        val replacement = TerminalView(surface.activity, null).apply { setTextSize(14) }
        replacement.setTerminalViewClient(DroideTerminalViewClient(surface.activity, { replacement }))
        replacement.mEmulator = surface.emulator
        surface.container.addView(replacement, FrameLayout.LayoutParams(320, 640))
        replacement.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        replacement.layout(0, 0, 320, 640)
        surface.currentView = replacement
        // Both views remain attached: identity, rather than detach alone, must prevent the old
        // GestureDetector from targeting a newly displayed URL.
        assertTrue(surface.view.isAttachedToWindow)
        assertTrue(replacement.isAttachedToWindow)
        surface.settleTouch()
        assertNull(shadowOf(surface.activity).nextStartedActivity)
        surface.container.removeView(replacement)
        surface.currentView = surface.view
        surface.touch(8, 0)
        assertOpened(surface, url)
    }

    @Test fun pendingTapIsDiscardedWhenItsViewBecomesHidden() = withSurface(320, 640) { surface ->
        val url = "https://example.test/login"
        surface.model.appendFromWorker(url)
        surface.queueTouch(8, 0)
        surface.view.visibility = View.INVISIBLE
        surface.settleTouch()
        assertNull(shadowOf(surface.activity).nextStartedActivity)
        surface.view.visibility = View.VISIBLE
        surface.touch(8, 0)
        assertOpened(surface, url)
    }

    @Test fun pendingTapIsDiscardedAfterDetachingItsView() = withSurface(320, 640) { surface ->
        val url = "https://example.test/login"
        surface.model.appendFromWorker(url)
        surface.queueTouch(8, 0)
        surface.container.removeView(surface.view)
        assertFalse(surface.view.isAttachedToWindow)
        surface.settleTouch()
        assertNull(shadowOf(surface.activity).nextStartedActivity)
        surface.container.addView(surface.view, FrameLayout.LayoutParams(320, 640))
        surface.touch(8, 0)
        assertOpened(surface, url)
    }

    @Test fun blankTopInsetDoesNotClampOntoTheFirstLinkRow() = withSurface(320, 640) { surface ->
        val url = "https://example.test/login"
        surface.model.appendFromWorker(url)
        assertTrue("Fixture must have a real top inset", surface.firstLineOffset > 0)
        val x = surface.point(8, 0).first
        surface.queueTouchAt(x, surface.firstLineOffset / 2f)
        surface.settleTouch()
        assertNull(shadowOf(surface.activity).nextStartedActivity)
        surface.touch(8, 0)
        assertOpened(surface, url)
    }
}

/** Test fixture only: production PTY readers decode off the Android Looper. */
private fun com.baystudio.droide.core.DeviceTerminalEmulator.appendFromWorker(text: String) =
    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) { appendText(text) }
