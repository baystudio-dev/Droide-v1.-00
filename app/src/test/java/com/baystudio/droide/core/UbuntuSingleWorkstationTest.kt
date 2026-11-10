package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule

class UbuntuSingleWorkstationTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()
    private class Session : ITerminalSession {
        override val output = MutableStateFlow("")
        var starts = 0
        var destroyed = 0
        val commands = mutableListOf<String>()
        override fun start() { starts++ }
        override fun destroy() { destroyed++ }
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            commands += cmd
            return ExecResult(0, cmd, false)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = execOnce(argv.joinToString(" "), timeoutMs)
    }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val launch = TerminalLaunchSpec(prefix = listOf("/usr/bin/env"), shell = "/bin/bash")
    private fun manager(scope: CoroutineScope, prepare: suspend () -> TerminalLaunchSpec = { launch }, create: () -> Session = { Session() }) =
        TerminalManager(scope, File("ubuntu-tests"), ubuntuOnly = true,
            linuxLaunchSpecFactory = { _, qemu -> check(!qemu); prepare() },
            localSessionFactory = { _, _, _ -> create() })
    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(1)
    }

    @Test fun productionConstructorEnforcesUbuntu() {
        val scope = scope()
        val manager = TerminalManager(scope, File("ubuntu-tests"))
        try {
            assertTrue(manager.ubuntuOnly)
            assertTrue(runCatching { manager.createLocal() }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { manager.createDeviceWorkstation() }.exceptionOrNull() is IllegalStateException)
            assertTrue(manager.snapshot.value.tabs.isEmpty())
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun rejectedBackendNeverPreparesOrStarts() = runBlocking {
        val scope = scope(); var preparations = 0; var starts = 0
        val manager = manager(scope, { preparations++; launch }, { starts++; Session() })
        try {
            assertTrue(runCatching { manager.openLinuxTerminal(qemu = true) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, preparations); assertEquals(0, starts)
            assertTrue(runCatching { manager.localExecutionSession() }.exceptionOrNull() is IllegalStateException)
            assertNull(manager.workstationExecutionSession())
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun initialPreparationDeduplicatesAndWarmRequestsReuseVisibleSession() = runBlocking {
        val scope = scope(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var preparations = 0
        val manager = manager(scope, { preparations++; entered.complete(Unit); release.await(); launch })
        try {
            manager.prepareInitialUbuntuTerminal(); withTimeout(3_000) { entered.await() }
            repeat(10) { manager.prepareInitialUbuntuTerminal() }
            assertTrue(manager.ubuntuPreparation.value.preparing)
            release.complete(Unit)
            awaitCondition { manager.tabs.value.size == 1 && !manager.ubuntuPreparation.value.preparing }
            val first = manager.activeTab()!!.session
            repeat(10) { assertSame(first, manager.automatedExecutionSession()) }
            assertEquals(1, preparations)
            assertEquals(TerminalManager.Profile.LOCAL_LINUX_ARM64, manager.activeTab()!!.profile)
        } finally { release.complete(Unit); manager.dispose(false); scope.cancel() }
    }

    @Test fun failedInitialSetupDoesNotLoopAndExplicitRetryWorks() = runBlocking {
        val scope = scope(); var attempts = 0
        val manager = manager(scope, { attempts++; if (attempts == 1) error("disk full"); launch })
        try {
            manager.prepareInitialUbuntuTerminal()
            awaitCondition { manager.ubuntuPreparation.value.error == "disk full" && !manager.ubuntuPreparation.value.preparing }
            assertTrue(manager.tabs.value.isEmpty()); assertEquals(1, attempts)
            manager.openLinuxTerminal()
            awaitCondition { manager.tabs.value.size == 1 && !manager.ubuntuPreparation.value.preparing }
            assertNull(manager.ubuntuPreparation.value.error); assertEquals(2, attempts)
            assertFalse(manager.automatedExecutionUnavailableReason().contains("disk full"))
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun disposedManagerRejectsLatePreparationPublication() = runBlocking {
        val scope = scope(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var starts = 0
        val manager = manager(scope, { withContext(NonCancellable) { entered.complete(Unit); release.await() }; launch }, { starts++; Session() })
        val setup = async { manager.openLinuxTerminal() }
        try {
            withTimeout(3_000) { entered.await() }; manager.dispose(false); release.complete(Unit)
            assertTrue(runCatching { setup.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, starts); assertTrue(manager.tabs.value.isEmpty())
            assertFalse(manager.ubuntuPreparation.value.preparing)
            assertTrue(currentCoroutineContext().isActive)
        } finally { release.complete(Unit); manager.dispose(false); scope.cancel() }
    }

    @Test fun cancelPreparationLeavesCallerAliveAndAllowsRetry() = runBlocking {
        val scope = scope(); val entered = CompletableDeferred<Unit>(); var attempts = 0
        val manager = manager(scope, { attempts++; if (attempts == 1) { entered.complete(Unit); awaitCancellation() }; launch })
        val setup = async { manager.openLinuxTerminal() }
        try {
            withTimeout(3_000) { entered.await() }; manager.cancelUbuntuPreparation()
            assertTrue(runCatching { setup.await() }.exceptionOrNull() is CancellationException)
            assertTrue(currentCoroutineContext().isActive)
            manager.openLinuxTerminal()
            assertEquals(1, manager.tabs.value.size); assertEquals(2, attempts)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun agentIsPinnedAndNeverFallsBackOrReplaysAfterClosingTab() = runBlocking {
        val scope = scope(); val fallback = Session(); val manager = manager(scope)
        try {
            val router = ExecutionTerminalRouter(manager, fallback)
            assertEquals(126, router.execOnce("before-ready", 1_000).exitCode)
            assertTrue(fallback.commands.isEmpty())
            val a = manager.openLinuxTerminal(); val pinned = router.bindCommandSession()
            val b = manager.openLinuxTerminal(); assertEquals(b.id, manager.activeTab()!!.id)
            assertEquals(0, pinned.execOnce("build-a", 1_000).exitCode)
            assertEquals(listOf("build-a"), (a.session as Session).commands)
            assertTrue((b.session as Session).commands.isEmpty())
            manager.close(a.id)
            assertEquals(126, pinned.execOnce("must-not-replay", 1_000).exitCode)
            assertTrue((b.session as Session).commands.isEmpty()); assertTrue(fallback.commands.isEmpty())
        } finally { manager.dispose(false); scope.cancel() }
    }
}
