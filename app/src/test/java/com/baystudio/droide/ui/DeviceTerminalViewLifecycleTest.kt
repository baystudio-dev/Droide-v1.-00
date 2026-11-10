package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.baystudio.droide.core.DevicePtySessionHandle
import com.baystudio.droide.core.DeviceTerminalEmulator
import com.baystudio.droide.core.ExecResult
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceTerminalViewLifecycleTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    private class Session : DevicePtySessionHandle {
        override val terminalEmulator = DeviceTerminalEmulator({}, 40, 24)
        override val output = MutableStateFlow("")
        override val terminalTitle = terminalEmulator.title
        override val workingDirectory = terminalEmulator.workingDirectory
        override val profileLabel = "test PTY"
        override val status = MutableStateFlow("Ready")
        var destroyed = 0
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override fun writeRaw(text: String) = Unit
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
        view.attachSession(session)
        container.addView(view, FrameLayout.LayoutParams(320, 180))
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 320, 180)
        return view
    }

    @Test fun releasingOldViewKeepsReplacementAttachedToTheRealTerminalEmulator() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        val session = Session()
        try {
            val old = mount(container, session)
            val replacement = mount(container, session)
            old.release()
            container.removeView(old)
            session.terminalEmulator.appendFromWorker("\u001b]2;replacement alive\u0007hello 日本語 😀\r\n")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertEquals("replacement alive", replacement.contentDescription.toString())
            assertTrue(session.terminalEmulator.snapshotText().contains("hello 日本語 😀"))
            assertEquals(0, session.destroyed)
            replacement.release()
            container.removeView(replacement)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun repeatedAttachmentAndOutputBurstsRetainOnlyTheActiveViewLifecycle() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        val sessions = listOf(Session(), Session())
        try {
            repeat(30) { index ->
                val session = sessions[index % 2]
                val view = mount(container, session)
                repeat(100) { session.terminalEmulator.appendFromWorker("$index:$it output\r\n") }
                session.terminalEmulator.appendFromWorker("\u001b]2;active $index\u0007")
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                assertEquals("active $index", view.contentDescription.toString())
                view.release()
                container.removeView(view)
                session.terminalEmulator.appendFromWorker("detached output $index\r\n")
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                assertEquals("active $index", view.contentDescription.toString())
                assertTrue(session.terminalEmulator.snapshotText().contains("detached output $index"))
            }
            assertTrue(sessions.all { it.destroyed == 0 })
        } finally { controller.pause().stop().destroy() }
    }
}

/** Test fixture only: production PTY readers decode off the Android Looper. */
private fun com.baystudio.droide.core.DeviceTerminalEmulator.appendFromWorker(text: String) =
    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) { appendText(text) }
