package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.baystudio.droide.core.DeviceTerminalEmulator
import com.termux.terminal.TerminalEmulator
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Authored/unrun: actual pinned TerminalView selection/touch inside the app container, no JNI process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeTerminalImeFocusR61Test {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    private fun surface(body: (DroideNativeTerminalView, DroideTerminalViewClient, DeviceTerminalEmulator) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = DroideNativeTerminalView(activity.get())
        val model = DeviceTerminalEmulator({}, 80, 24)
        val emulator = DeviceTerminalEmulator::class.java.getDeclaredField("emulator").let {
            it.isAccessible = true
            it.get(model) as TerminalEmulator
        }
        val client = DroideTerminalViewClient(activity.get(), { view.terminalView }, inputViewProvider = { view })
        view.setTerminalViewClient(client)
        view.setTextSize(14)
        val root = FrameLayout(activity.get())
        root.addView(view, FrameLayout.LayoutParams(320, 480))
        activity.get().setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 320, 480)
        view.terminalView.mEmulator = emulator
        activity.windowFocusChanged(true)
        try { body(view, client, model) } finally {
            view.terminalView.stopTextSelectionMode()
            view.release()
            root.removeAllViews()
            activity.pause().stop().destroy()
        }
    }

    @Test fun childCanStillAcquireFocusAndStartRealUpstreamSelection() = surface { view, _, model ->
        runBlocking(Dispatchers.Default) { model.appendText("select this text") }
        assertTrue(view.requestFocus())
        assertTrue(view.terminalView.requestFocus())
        val renderer = view.terminalView.mRenderer
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
            renderer.fontWidth * 3.5f, renderer.fontLineSpacing * 0.5f, 0)
        try { view.terminalView.startTextSelectionMode(event) } finally { event.recycle() }
        assertTrue("Container routing must not disable copy/selection", view.terminalView.isSelectingText)
    }

    @Test fun ordinaryTapClientTargetsContainerAfterChildFocus() = surface { view, client, _ ->
        assertTrue(view.terminalView.requestFocus())
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 10f, 10f, 0)
        try { client.onSingleTapUp(event) } finally { event.recycle() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        assertTrue(view.isFocused)
        assertFalse(view.terminalView.isFocused)
        assertTrue("Legacy selection child proxy remains explicitly supported", view.checkInputConnectionProxy(view.terminalView))
    }

    @Test fun hiddenWorkspaceRestoresOriginalSelectionAndContainerFocusPolicy() = surface { view, _, _ ->
        val gate = WorkspaceNativePresentationGate()
        gate.update(view, false)
        assertFalse(view.requestFocus())
        assertFalse(view.terminalView.requestFocus())
        gate.update(view, true)
        assertTrue(view.terminalView.requestFocus())
        assertTrue(view.requestFocus())
        assertTrue(view.isFocused)
    }
}
