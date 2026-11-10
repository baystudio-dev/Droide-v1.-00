package com.baystudio.droide.ui

import org.junit.Before

import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Looper
import com.baystudio.droide.core.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises Android-context backend selection with fake sessions, never JNI or PRoot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TerminalRecoveryBackendRoutingTest {
    @Before
    fun openApplicationWork() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    private class Session(private val startFailure: Throwable? = null) : ITerminalSession {
        override val output = MutableStateFlow("")
        var starts = 0
        var destroys = 0
        val commands = mutableListOf<String>()
        override fun start() { starts++; startFailure?.let { throw it } }
        override fun destroy() { destroys++ }
        override fun send(cmd: String) { commands += cmd }
        override fun interrupt() = Unit
        override fun clear() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            commands += cmd
            return ExecResult(0, "", false)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = execOnce(argv.joinToString(" "), timeoutMs)
    }

    private val launch = TerminalLaunchSpec(prefix = listOf("/usr/bin/env"), shell = "/bin/bash")

    @Test fun javaCrashSuppressesAutomaticStartupButExplicitTabSelectsNativeBackend() = verifyRecoveredNativeRouting(ApplicationExitInfo.REASON_CRASH)
    @Test fun nativeCrashSuppressesAutomaticStartupButExplicitTabSelectsNativeBackend() = verifyRecoveredNativeRouting(ApplicationExitInfo.REASON_CRASH_NATIVE)
    @Test fun anrSuppressesAutomaticStartupButExplicitTabSelectsNativeBackend() = verifyRecoveredNativeRouting(ApplicationExitInfo.REASON_ANR)
    @Test fun lowMemorySuppressesAutomaticStartupButExplicitTabSelectsNativeBackend() = verifyRecoveredNativeRouting(ApplicationExitInfo.REASON_LOW_MEMORY)

    private fun verifyRecoveredNativeRouting(reason: Int) = runBlocking {
        assertSame("Routing must run on Android's main Looper", Looper.getMainLooper().thread, Thread.currentThread())
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "recovery-routing-$reason").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val native = Session()
        var preparations = 0
        var nativeCreates = 0
        var pipeCreates = 0
        val manager = TerminalManager(
            scope = scope, defaultDir = root, context = context,
            startupRecovery = StartupRecoveryPolicy.decide(PreviousProcessExit(100, 10, reason), null),
            linuxLaunchSpecFactory = { dir, qemu ->
                assertEquals(root, dir); assertFalse(qemu); preparations++; launch
            },
            nativeSessionFactory = { dir, ownerScope, spec ->
                assertEquals(root, dir); assertSame(scope, ownerScope); assertSame(launch, spec)
                nativeCreates++; native
            },
            commandPipeSessionFactory = { _, _, _ -> pipeCreates++; Session() },
        )
        try {
            repeat(5) { manager.prepareInitialUbuntuTerminal() }
            assertEquals(0, preparations)
            assertEquals(0, nativeCreates)
            assertEquals(0, pipeCreates)
            assertTrue(manager.tabs.value.isEmpty())

            val tab = manager.openLinuxTerminal()
            assertSame(native, tab.session)
            assertSame(tab, manager.activeTab())
            repeat(5) { assertSame(native, manager.automatedExecutionSession()) }
            assertEquals(1, preparations)
            assertEquals(1, nativeCreates)
            assertEquals(1, native.starts)
            assertEquals(0, pipeCreates)
            assertEquals(1, manager.tabs.value.size)
            assertTrue(native.commands.isEmpty())

            ExecutionTerminalRouter(manager).bindCommandSession().execOnce("echo requested", 1_000)
            assertEquals(listOf("echo requested"), native.commands)
        } finally {
            manager.dispose(false)
            scope.cancel()
        }
    }

    @Test fun nativeFailureFallsBackOnceAndNextExplicitTabAttemptsNativeWithoutReplayingCommands() = runBlocking {
        assertSame(Looper.getMainLooper().thread, Thread.currentThread())
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "recovery-routing-transient").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val failedNative = Session(NativePtyUnavailableException(IOException("Transient PTY failure")))
        val readyNative = Session()
        val pipe = Session()
        var preparations = 0
        var nativeCreates = 0
        var pipeCreates = 0
        val manager = TerminalManager(
            scope = scope, defaultDir = root, context = context,
            startupRecovery = StartupRecoveryPolicy.decide(PreviousProcessExit(100, 10, ApplicationExitInfo.REASON_CRASH), null),
            linuxLaunchSpecFactory = { dir, qemu ->
                assertEquals(root, dir); assertFalse(qemu); preparations++; launch
            },
            nativeSessionFactory = { dir, ownerScope, spec ->
                assertEquals(root, dir); assertSame(scope, ownerScope); assertSame(launch, spec)
                if (++nativeCreates == 1) failedNative else readyNative
            },
            commandPipeSessionFactory = { dir, ownerScope, spec ->
                assertEquals(root, dir); assertSame(scope, ownerScope); assertSame(launch, spec)
                pipeCreates++; pipe
            },
        )
        try {
            manager.prepareInitialUbuntuTerminal()
            val fallbackTab = manager.openLinuxTerminal()
            assertSame(pipe, fallbackTab.session)
            assertEquals(1, failedNative.starts)
            assertEquals(1, failedNative.destroys)
            assertEquals(1, pipe.starts)
            assertEquals(1, nativeCreates)
            assertEquals(1, pipeCreates)
            assertEquals(1, preparations)
            assertTrue(failedNative.commands.isEmpty())
            assertTrue(pipe.commands.isEmpty())

            pipe.send("echo only-in-first-tab")
            repeat(5) { manager.prepareInitialUbuntuTerminal() }
            val nativeTab = manager.openLinuxTerminal()
            assertSame(readyNative, nativeTab.session)
            assertSame(nativeTab, manager.activeTab())
            assertEquals(2, nativeCreates)
            assertEquals(1, pipeCreates)
            assertEquals(2, preparations)
            assertEquals(1, readyNative.starts)
            assertEquals(2, manager.tabs.value.size)
            assertTrue(readyNative.commands.isEmpty())
            assertTrue(failedNative.commands.isEmpty())
            assertEquals(listOf("echo only-in-first-tab"), pipe.commands)
        } finally {
            manager.dispose(false)
            scope.cancel()
        }
    }
}
