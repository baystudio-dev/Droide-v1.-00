package com.baystudio.droide.core

import android.app.Application
import android.os.Looper
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.termux.terminal.TerminalSession as EngineSession
import com.termux.terminal.TerminalSessionClient

/** Actual app + fresh engine interface/reducer; deliberately never starts a native child. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class CurrentTerminalResizeBridgeContractTest {
    @Before fun bindSandboxInputs() { CurrentAppRuntimeIdentity.verify() }

    private fun delegate(events: MutableList<String> = mutableListOf()): TerminalSessionClient =
        Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader,
            arrayOf(TerminalSessionClient::class.java)) { _, method, _ ->
            events += method.name
            null
        } as TerminalSessionClient

    private fun inertSession() = EngineSession("/not-started", "/", emptyArray(), emptyArray(), 100, delegate())

    private class Surface(val owner: NativePtyTerminalSession, val callback: TerminalSessionClient) {
        val session get() = owner.nativePtySession
        fun emit(changed: EngineSession = session, generation: Long = 2, request: Long = 3) {
            // Compile and dispatch through the actual new engine interface, not reflection or a fake reducer.
            callback.onPtyResizeResult(changed, generation, request, 90, 30, 90, 30, 80, 24, 2)
        }
    }

    private fun surface(body: (Surface) -> Unit) {
        val application = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // Nonexistent workdir avoids opening a filesystem root; no start/updateSize method is called.
        val workDir = File(application.cacheDir, "unstarted-contract-${UUID.randomUUID()}")
        val owner = NativePtyTerminalSession(application, workDir, scope,
            TerminalLaunchSpec(prefix = listOf("/not-started-prefix")))
        val field = NativePtyTerminalSession::class.java.getDeclaredField("client").apply { isAccessible = true }
        val callback = field.get(owner) as TerminalSessionClient
        try {
            assertEquals(0, owner.nativePtySession.pid)
            assertNull(owner.nativePtySession.emulator)
            body(Surface(owner, callback))
            assertEquals(0, owner.nativePtySession.pid)
            assertNull(owner.nativePtySession.emulator)
        } finally {
            try { owner.destroy() } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun interfaceDispatchPreservesAllFieldsAndDelegateCalls() {
        val events = mutableListOf<String>()
        var received: List<Any>? = null
        val session = inertSession()
        val bridge: TerminalSessionClient = TerminalResizeClientBridge(delegate(events)) {
                changed, generation, request, requestedColumns, requestedRows,
                emulatorColumns, emulatorRows, acceptedColumns, acceptedRows, status ->
            received = listOf(changed, generation, request, requestedColumns, requestedRows,
                emulatorColumns, emulatorRows, acceptedColumns, acceptedRows, status)
        }
        bridge.onPtyResizeResult(session, 11, 12, 91, 31, 90, 30, 81, 21, 2)
        assertEquals(listOf(session, 11L, 12L, 91, 31, 90, 30, 81, 21, 2), received)
        bridge.onBell(session)
        assertEquals(listOf("onBell"), events)
    }

    @Test fun currentOwnerReceiptAndViewportAreCombined() = surface { s ->
        s.emit()
        val viewport = TerminalViewportGeometry(900, 600, 10f, 20, 90, 30, 90, 30)
        s.owner.reportViewport(s.session, viewport)
        assertSame(s.session, s.owner.geometry.value.owner)
        assertEquals(TerminalResizeReceipt(2, 3, 90, 30, 90, 30, 80, 24, 2), s.owner.geometry.value.receipt)
        assertEquals(viewport, s.owner.geometry.value.viewport)
    }

    @Test fun olderGenerationAndRequestCannotReplaceCurrentReceipt() = surface { s ->
        s.emit(generation = 4, request = 8)
        val accepted = s.owner.geometry.value
        s.emit(generation = 3, request = 100)
        s.emit(generation = 4, request = 7)
        assertEquals(accepted, s.owner.geometry.value)
    }

    @Test fun foreignSessionCannotStealDisplayedGeometryOwner() = surface { s ->
        s.emit()
        val accepted = s.owner.geometry.value
        val foreign = inertSession()
        s.emit(changed = foreign, generation = 99, request = 99)
        s.owner.reportViewport(foreign, TerminalViewportGeometry(1, 1, 1f, 1, 1, 1, 1, 1))
        assertEquals(accepted, s.owner.geometry.value)
    }

    @Test fun backgroundCallbackPublishesViaMainLooper() = surface { s ->
        val thread = Thread { s.emit() }
        thread.start(); thread.join(2000)
        assertFalse("Callback thread must finish", thread.isAlive)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(s.session, s.owner.geometry.value.owner)
        assertEquals(3L, s.owner.geometry.value.receipt?.requestId)
    }

    @Test fun destroyedOwnerRejectsLateReceipt() = surface { s ->
        s.emit(); s.owner.destroy(); s.emit(generation = 99, request = 99)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TerminalGeometrySnapshot(), s.owner.geometry.value)
    }
}
