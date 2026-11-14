package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.InputDevice
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authored/unrun Android regressions; no process, PTY, APK or physical mouse is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class NativeTerminalClipboardRoutesR67Test {
    @Test fun copyKeepsExactTextAndClosesSelectionOnceOnlyAfterSuccess() = withActivity { context ->
        val upstream = RecordingCallback()
        val mode = TestMode(context)
        val copied = mutableListOf<String>()
        var stopped = 0
        val text = "  \u03bb\uD83D\uDE00\n\te\u0301\n"
        val callback = NativeTerminalSelectionCallback(upstream, { true }, { true }, { text },
            { copied += it; true }, { stopped++ })
        assertTrue(callback.onCreateActionMode(mode, mode.menu))
        assertFalse(callback.onPrepareActionMode(mode, mode.menu))
        val item = mode.menu.add(Menu.NONE, 1, 0, "Copy")
        assertTrue(callback.onActionItemClicked(mode, item))
        assertTrue(callback.onActionItemClicked(mode, item))
        assertEquals(listOf(text), copied)
        assertEquals(1, stopped)
        assertEquals(0, upstream.clicks)
        assertEquals(1, upstream.created)
        assertEquals(1, upstream.prepared)
    }

    @Test fun rejectedCopyRetainsSelectionAndCanBeRetried() = withActivity { context ->
        val mode = TestMode(context)
        var accepted = false
        var attempts = 0
        var stopped = 0
        val callback = NativeTerminalSelectionCallback(RecordingCallback(), { true }, { true }, { "  " },
            { attempts++; accepted }, { stopped++ })
        val item = mode.menu.add(Menu.NONE, 1, 0, "Copy")
        callback.onActionItemClicked(mode, item)
        assertEquals(1, attempts)
        assertEquals(0, stopped)
        accepted = true
        callback.onActionItemClicked(mode, item)
        assertEquals(2, attempts)
        assertEquals(1, stopped)
    }

    @Test fun unrelatedActionsAndContentRectKeepOriginalCallback() = withActivity { context ->
        val mode = TestMode(context)
        val upstream = RecordingCallback()
        val callback = NativeTerminalSelectionCallback(upstream, { true }, { true }, { "" },
            { fail("Paste/More must keep upstream dispatch"); false }, {})
        for (id in listOf(2, 3, 77)) {
            assertFalse(callback.onActionItemClicked(mode, mode.menu.add(Menu.NONE, id, 0, "item")))
        }
        assertEquals(3, upstream.clicks)
        val rect = Rect()
        callback.onGetContentRect(mode, View(context), rect)
        assertEquals(Rect(1, 2, 30, 40), rect)
        callback.onDestroyActionMode(mode)
        assertEquals(1, upstream.destroyed)
    }

    @Test fun staleRetiredAndInactiveMenusCannotCopyOrPaste() = withActivity { context ->
        val mode = TestMode(context)
        val upstream = RecordingCallback()
        var current = true
        var selecting = false
        val callback = NativeTerminalSelectionCallback(upstream, { current }, { selecting },
            { fail("No stale text read"); null }, { fail("No stale copy"); false }, { fail("No new selection stopped") })
        val copy = mode.menu.add(Menu.NONE, 1, 0, "Copy")
        val paste = mode.menu.add(Menu.NONE, 2, 0, "Paste")
        callback.onActionItemClicked(mode, copy)
        selecting = true
        current = false
        callback.onActionItemClicked(mode, paste)
        assertFalse(callback.onPrepareActionMode(mode, mode.menu))
        current = true
        callback.onDestroyActionMode(mode)
        callback.onActionItemClicked(mode, copy)
        callback.onActionItemClicked(mode, paste)
        assertEquals(0, upstream.clicks)
    }

    @Test fun emptyRenderedSelectionIsCopiedWithoutBlankFiltering() = withActivity { context ->
        val mode = TestMode(context)
        val values = mutableListOf<String>()
        val callback = NativeTerminalSelectionCallback(RecordingCallback(), { true }, { true }, { "" },
            { values += it; true }, {})
        callback.onActionItemClicked(mode, mode.menu.add(Menu.NONE, 1, 0, "Copy"))
        assertEquals(listOf(""), values)
    }

    @Test fun middleDownGenericPressMovesReleaseAndUpAdmitExactlyOnce() {
        val route = NativeTerminalMiddleMousePaste()
        var pastes = 0
        fun deliver(action: Int, buttons: Int, actionButton: Int = 0, down: Long = 1) =
            mouse(action, buttons, actionButton, down) { route.handle(it, false) { pastes++ } }
        assertTrue(deliver(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_TERTIARY))
        assertTrue(deliver(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_TERTIARY, MotionEvent.BUTTON_TERTIARY))
        repeat(5) { assertTrue(deliver(MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_TERTIARY)) }
        assertTrue(deliver(MotionEvent.ACTION_BUTTON_RELEASE, 0, MotionEvent.BUTTON_TERTIARY))
        assertTrue(deliver(MotionEvent.ACTION_UP, 0))
        assertEquals(1, pastes)
        assertTrue(deliver(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_TERTIARY, down = 2))
        assertEquals(2, pastes)
    }

    @Test fun genericPressBeforeTouchDownDoesNotDuplicateAndGenericOnlyPressWorks() {
        val route = NativeTerminalMiddleMousePaste()
        var pastes = 0
        fun deliver(action: Int, down: Long = 1) = mouse(action, MotionEvent.BUTTON_TERTIARY,
            MotionEvent.BUTTON_TERTIARY, down) { route.handle(it, false) { pastes++ } }
        assertTrue(deliver(MotionEvent.ACTION_BUTTON_PRESS))
        assertTrue(deliver(MotionEvent.ACTION_DOWN))
        assertEquals(1, pastes)
        deliver(MotionEvent.ACTION_BUTTON_RELEASE)
        deliver(MotionEvent.ACTION_UP)
        assertTrue(deliver(MotionEvent.ACTION_BUTTON_PRESS, down = 2))
        assertEquals(2, pastes)
    }

    @Test fun cancelAndFreshDownRecoverButStrayMoveNeverPastes() {
        val route = NativeTerminalMiddleMousePaste()
        var pastes = 0
        fun deliver(action: Int, down: Long = 1) = mouse(action, MotionEvent.BUTTON_TERTIARY,
            down = down) { route.handle(it, false) { pastes++ } }
        assertTrue(deliver(MotionEvent.ACTION_MOVE))
        assertEquals(0, pastes)
        deliver(MotionEvent.ACTION_DOWN)
        assertTrue(deliver(MotionEvent.ACTION_CANCEL))
        deliver(MotionEvent.ACTION_MOVE)
        assertEquals(1, pastes)
        deliver(MotionEvent.ACTION_DOWN, 2)
        deliver(MotionEvent.ACTION_DOWN, 3) // missing release from the previous gesture
        assertEquals(3, pastes)
    }

    @Test fun activeSelectionAndOtherGesturesKeepUpstreamDispatch() {
        val route = NativeTerminalMiddleMousePaste()
        var pastes = 0
        mouse(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_TERTIARY) {
            assertFalse(route.handle(it, true) { pastes++ })
        }
        mouse(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_TERTIARY, MotionEvent.BUTTON_TERTIARY) {
            assertTrue(route.handle(it, false) { pastes++ }) // same press after selection ended
        }
        assertEquals(0, pastes)
        for ((action, buttons) in listOf(MotionEvent.ACTION_DOWN to MotionEvent.BUTTON_PRIMARY,
                MotionEvent.ACTION_MOVE to MotionEvent.BUTTON_PRIMARY,
                MotionEvent.ACTION_DOWN to MotionEvent.BUTTON_SECONDARY,
                MotionEvent.ACTION_SCROLL to MotionEvent.BUTTON_TERTIARY,
                MotionEvent.ACTION_HOVER_MOVE to 0)) {
            mouse(action, buttons, down = 9) { assertFalse(route.handle(it, false) { pastes++ }) }
        }
        mouse(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_TERTIARY, source = InputDevice.SOURCE_TOUCHSCREEN) {
            assertFalse(route.handle(it, false) { pastes++ })
        }
        assertEquals(0, pastes)
    }

    private fun withActivity(body: (Context) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try { body(activity.get()) } finally { activity.pause().stop().destroy() }
    }

    private class RecordingCallback : ActionMode.Callback2() {
        var created = 0
        var prepared = 0
        var clicks = 0
        var destroyed = 0
        override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true.also { created++ }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false.also { prepared++ }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false.also { clicks++ }
        override fun onDestroyActionMode(mode: ActionMode) { destroyed++ }
        override fun onGetContentRect(mode: ActionMode, view: View, rect: Rect) { rect.set(1, 2, 30, 40) }
    }
}

internal class TestMode(private val context: Context) : ActionMode() {
    private val items = PopupMenu(context, View(context)).menu
    var finished = false
    override fun getMenu(): Menu = items
    override fun getMenuInflater() = MenuInflater(context)
    override fun finish() { finished = true }
    override fun invalidate() {}
    override fun setTitle(title: CharSequence?) {}
    override fun setTitle(resId: Int) {}
    override fun setSubtitle(subtitle: CharSequence?) {}
    override fun setSubtitle(resId: Int) {}
    override fun setCustomView(view: View?) {}
    override fun getTitle(): CharSequence? = null
    override fun getSubtitle(): CharSequence? = null
    override fun getCustomView(): View? = null
}

internal fun <T> mouse(action: Int, buttons: Int, actionButton: Int = 0, down: Long = 1,
    source: Int = InputDevice.SOURCE_MOUSE, body: (MotionEvent) -> T): T {
    val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
    val coords = MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f }
    val event = MotionEvent.obtain(down, down + 1, action, 1, arrayOf(properties), arrayOf(coords),
        0, buttons, 1f, 1f, 7, 0, source, 0)
    // Test-only access: Android hides this setter from the public SDK used to compile tests.
    MotionEvent::class.java.getDeclaredMethod("setActionButton", Integer.TYPE).invoke(event, actionButton)
    try { return body(event) } finally { event.recycle() }
}
