package com.baystudio.droide.core

import android.system.Os
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [TermuxStartupPidGuardTest.RecordingOs::class])
class TermuxStartupPidGuardTest {
    @Implements(Os::class)
    class RecordingOs {
        companion object {
            val kills = mutableListOf<Int>()
            @JvmStatic @Implementation fun kill(pid: Int, signal: Int) { kills += pid }
        }
    }
    private fun session(): TerminalSession {
        val client = Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader,
            arrayOf(TerminalSessionClient::class.java)) { _, _, _ -> null } as TerminalSessionClient
        return TerminalSession("/not-started", "/", emptyArray(), emptyArray(), 100, client)
    }

    @Test fun cancellingAnUnstartedSessionNeverSignalsTheAppProcessGroup() {
        RecordingOs.kills.clear()
        session().finishIfRunning()
        assertTrue("kill(0) must never be called", RecordingOs.kills.isEmpty())
    }

    @Test fun numericPidWithoutOwnershipCannotSignalAndCleanupNeedsNoNativeFd() {
        RecordingOs.kills.clear()
        val terminal = session()
        TerminalSession::class.java.getDeclaredField("mShellPid").apply { isAccessible = true }.setInt(terminal, 4242)
        terminal.finishIfRunning()
        assertTrue("A numeric PID is not process ownership", RecordingOs.kills.isEmpty())
        TerminalSession::class.java.getDeclaredMethod("cleanupResources", Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(terminal, 127)
        terminal.finishIfRunning()
        assertTrue("A numeric PID is not process ownership", RecordingOs.kills.isEmpty())
        assertEquals(127, terminal.exitStatus)
    }
    @Test fun stopUsesTheOwnedGenerationHandleWithoutSignalingTheDiagnosticPid() {
        RecordingOs.kills.clear()
        val stops = mutableListOf<Long>()
        val cancelledLanes = mutableListOf<Int>()
        val ownerType = Class.forName("com.termux.terminal.TerminalProcessOwner")
        val backendType = Class.forName("com.termux.terminal.TerminalProcessOwner\$Backend")
        val workerType = Class.forName("com.termux.terminal.TerminalProcessOwner\$Worker")
        val backend = Proxy.newProxyInstance(backendType.classLoader, arrayOf(backendType)) { _, method, args ->
            when (method.name) {
                "apiVersion" -> 2
                "reserve" -> 73L
                "spawn" -> { assertEquals(73L, args!![0]); 4242 }
                "stop" -> { stops += args!![0] as Long; 0 }
                "cancelIo" -> { assertEquals(73L, args!![0]); cancelledLanes += args[1] as Int; null }
                else -> error("Unexpected native backend call: ${method.name}")
            }
        }
        val owner = ownerType.getDeclaredConstructor(backendType).apply { isAccessible = true }.newInstance(backend)
        val worker = Proxy.newProxyInstance(workerType.classLoader, arrayOf(workerType)) { _, method, _ ->
            check(method.name == "start")
            null
        }
        assertEquals(4242, ownerType.getDeclaredMethod("start", workerType, workerType, workerType)
            .apply { isAccessible = true }.invoke(owner, worker, worker, worker))
        val terminal = session()
        TerminalSession::class.java.getDeclaredField("mOwner").apply { isAccessible = true }.set(terminal, owner)
        TerminalSession::class.java.getDeclaredField("mShellPid").apply { isAccessible = true }.setInt(terminal, 4242)
        terminal.finishIfRunning()
        assertEquals(listOf(73L), stops)
        assertTrue(RecordingOs.kills.isEmpty())
        TerminalSession::class.java.getDeclaredMethod("cleanupResources", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(terminal, 127)
        assertEquals(listOf(3), cancelledLanes)
        assertEquals(-1, terminal.pid)
        assertEquals(127, terminal.exitStatus)
        assertTrue(RecordingOs.kills.isEmpty())
    }

    @Test fun successfulSpawnReceiptSurvivesFastExitWithoutRestoringASignalablePid() {
        RecordingOs.kills.clear()
        val terminal = session()
        TerminalSession::class.java.getDeclaredField("mSpawnedPid").apply { isAccessible = true }.setInt(terminal, 4242)
        TerminalSession::class.java.getDeclaredField("mShellPid").apply { isAccessible = true }.setInt(terminal, -1)
        assertEquals(4242, terminal.spawnedPid)
        assertEquals(-1, terminal.pid)
        assertFalse(terminal.isRunning)
        terminal.finishIfRunning()
        assertTrue(RecordingOs.kills.isEmpty())
    }

}
