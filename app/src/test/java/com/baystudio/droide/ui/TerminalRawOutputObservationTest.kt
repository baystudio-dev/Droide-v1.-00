package com.baystudio.droide.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import com.baystudio.droide.core.DeviceTerminalEmulator
import com.baystudio.droide.core.TerminalRawOutputBridge
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class TerminalRawOutputObservationTest {
    @get:org.junit.Rule val memory = com.termux.terminal.TerminalMemoryBudgetTestRule()

    @Test fun rawOutputSurvivesAnsiParsingAndIsObservedExactlyOnce() {
        val client = Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader,
            arrayOf(TerminalSessionClient::class.java)) { _, method, _ ->
            if (method.name == "getTerminalCursorStyle") 0 else null
        } as TerminalSessionClient
        val session = TerminalSession("/bin/sh", "/", arrayOf("/bin/sh"), emptyArray(), 100, client)
        val model = DeviceTerminalEmulator({}, 80, 24)
        fun field(name: String) = TerminalSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("mEmulator").set(session, DeviceTerminalEmulator::class.java.getDeclaredField("emulator").apply { isAccessible = true }.get(model))
        val queue = field("mProcessToTerminalIOQueue").get(session)
        val handler = field("mMainThreadHandler").get(session) as Handler
        val captured = ByteArrayOutputStream()
        assertTrue(TerminalRawOutputBridge.attach(session) { bytes, count -> captured.write(bytes, 0, count) })
        val raw = "\u001b[31mcafé 世界\u001b[0m\r\n".repeat(20).toByteArray()
        val write = queue.javaClass.getDeclaredMethod("write", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        assertTrue(write.invoke(queue, raw, 0, raw.size) as Boolean)
        handler.handleMessage(Message.obtain(handler, 1))
        shadowOf(Looper.getMainLooper()).idle()
        handler.handleMessage(Message.obtain(handler, 1))
        assertArrayEquals(raw, captured.toByteArray())
    }
}
