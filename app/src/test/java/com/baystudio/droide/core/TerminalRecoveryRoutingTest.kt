package com.baystudio.droide.core

import android.app.ApplicationExitInfo
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class TerminalRecoveryRoutingTest {
    @get:org.junit.Rule
    val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private class Session : ITerminalSession {
        override val output = MutableStateFlow("")
        var starts = 0
        val commands = mutableListOf<String>()
        override fun start() { starts++ }
        override fun destroy() = Unit
        override fun send(cmd: String) { commands += cmd }
        override fun interrupt() = Unit
        override fun clear() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            commands += cmd
            return ExecResult(0, "", false)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = execOnce(argv.joinToString(" "), timeoutMs)
    }

    private val abnormalExits = listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY)
    private val root = File("recovery-workspace")
    private val launch = TerminalLaunchSpec(prefix = listOf("/usr/bin/env"), shell = "/bin/bash")

    @Test fun abnormalRestartDoesNotSetUpUbuntuOrStartAnyShellAutomatically() = runBlocking {
        for (reason in abnormalExits) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var preparations = 0
            val sessions = mutableListOf<Session>()
            val manager = TerminalManager(scope, root, ubuntuOnly = true,
                linuxLaunchSpecFactory = { _, _ -> preparations++; launch },
                localSessionFactory = { _, _, _ -> Session().also(sessions::add) },
                startupRecovery = StartupRecoveryPolicy.decide(PreviousProcessExit(100, 10, reason), null))
            try {
                repeat(5) { manager.prepareInitialUbuntuTerminal() }
                scope.coroutineContext[Job]!!.children.toList().joinAll()
                assertEquals("reason=$reason must not prepare a rootfs", 0, preparations)
                assertTrue(sessions.isEmpty())
                assertTrue(manager.tabs.value.isEmpty())
                assertFalse(manager.ubuntuPreparation.value.preparing)
                assertNull(manager.ubuntuPreparation.value.error)
            } finally { manager.dispose(false); scope.cancel() }
        }
    }

    @Test fun explicitTerminalAfterRecoveryKeepsWorkspaceAndIsReusedByAgent() = runBlocking {
        for (reason in abnormalExits) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var preparations = 0
            val session = Session()
            val manager = TerminalManager(scope, root, ubuntuOnly = true,
                linuxLaunchSpecFactory = { dir, qemu -> assertEquals(root, dir); assertFalse(qemu); preparations++; launch },
                localSessionFactory = { dir, _, spec -> assertEquals(root, dir); assertSame(launch, spec); session },
                startupRecovery = StartupRecoveryPolicy.decide(PreviousProcessExit(100, 10, reason), null))
            try {
                val tab = manager.openLinuxTerminal()
                assertSame(tab, manager.activeTab())
                repeat(5) { assertSame(session, manager.automatedExecutionSession()) }
                assertEquals(1, preparations)
                assertEquals(1, session.starts)
                assertEquals(1, manager.tabs.value.size)
                assertTrue(session.commands.isEmpty())
                val routed = ExecutionTerminalRouter(manager, Session()).bindCommandSession()
                routed.execOnce("echo requested", 1_000)
                assertEquals(listOf("echo requested"), session.commands)
            } finally { manager.dispose(false); scope.cancel() }
        }
    }
}
