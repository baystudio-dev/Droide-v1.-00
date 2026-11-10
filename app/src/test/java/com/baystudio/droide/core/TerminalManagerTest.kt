package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class TerminalManagerTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private open class Session(
        val startFailure: Throwable? = null,
        val closeFailure: Throwable? = null,
    ) : ITerminalSession, TerminalMetadataSession {
        override val output = MutableStateFlow("")
        override val terminalTitle = MutableStateFlow<String?>(null)
        var started = 0
        var destroyed = 0
        val executedCommands = mutableListOf<String>()
        override fun start() { started++; startFailure?.let { throw it } }
        override fun destroy() { destroyed++; closeFailure?.let { throw it } }
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            executedCommands += cmd
            return ExecResult(0, "", false)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun manager(scope: CoroutineScope, factory: () -> Session = { Session() }) =
        TerminalManager(scope, File("terminal-tests")) { _, _, _ -> factory() }

    @Test fun commandBoundToUserTabCannotJumpToNewTabAfterSwitch() = runBlocking {
        val scope = scope()
        val manager = manager(scope)
        val router = ExecutionTerminalRouter(manager, Session())
        try {
            val ubuntu = manager.createLocal()
            val other = manager.createLocal()
            manager.switch(ubuntu.id)
            val pinned = router.bindCommandSession()
            manager.switch(other.id)
            val outcome = pinned.execOnce("echo pinned", 5_000)
            assertEquals(0, outcome.exitCode)
            assertEquals(listOf("echo pinned"), (ubuntu.session as Session).executedCommands)
            assertTrue((other.session as Session).executedCommands.isEmpty())
            assertEquals(2, manager.tabs.value.size)
            assertEquals(0, router.execOnce("echo live", 5_000).exitCode)
            assertEquals(listOf("echo live"), (other.session as Session).executedCommands)
            pinned.interrupt()
            assertEquals(0, (ubuntu.session as Session).destroyed)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun pendingAgentJobStaysOnOriginalTabWhenUserSwitches() = runBlocking {
        val scope = scope()
        val manager = manager(scope)
        val router = ExecutionTerminalRouter(manager, Session())
        val commands = BackgroundCommandManager(router)
        try {
            val first = manager.createLocal()
            val second = manager.createLocal()
            manager.switch(first.id)
            val started = commands.start("echo owned-session", timeoutMs = 3_000)
            manager.switch(second.id)
            val finished = withTimeout(5_000) {
                var state = requireNotNull(commands.snapshot(started.id))
                while (state.state == BackgroundCommandManager.State.RUNNING) {
                    delay(10)
                    state = requireNotNull(commands.snapshot(started.id))
                }
                state
            }
            assertEquals(BackgroundCommandManager.State.EXITED, finished.state)
            assertEquals(listOf("echo owned-session"), (first.session as Session).executedCommands)
            assertTrue((second.session as Session).executedCommands.isEmpty())
            assertEquals(2, manager.tabs.value.size)
        } finally {
            commands.close()
            manager.dispose(false)
            scope.cancel()
        }
    }

    @Test fun stdinEnabledJobChecksAndUsesOnePinnedUserTab() = runBlocking {
        val scope = scope()
        var factoryCalls = 0
        val targetCommands = mutableListOf<String>()
        val manager = manager(scope) {
            factoryCalls++
            if (factoryCalls == 1) object : Session() {
                override val supportsCommandInput: Boolean get() = true
                override suspend fun execStreamingWithInput(
                    cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
                    onOutput: (String) -> Unit,
                ): ExecResult {
                    targetCommands += cmd
                    return ExecResult(0, "pinned input", false)
                }
            } else Session()
        }
        val jobs = BackgroundCommandManager(ExecutionTerminalRouter(manager, Session()))
        try {
            val accepts = manager.createLocal()
            val rejects = manager.createLocal()
            manager.switch(accepts.id)
            val started = jobs.start("read pinned", keepStdinOpen = true)
            manager.switch(rejects.id)
            val done = withTimeout(3_000) {
                var value = requireNotNull(jobs.snapshot(started.id))
                while (value.state == BackgroundCommandManager.State.RUNNING) {
                    delay(10)
                    value = requireNotNull(jobs.snapshot(started.id))
                }
                value
            }
            assertEquals(BackgroundCommandManager.State.EXITED, done.state)
            assertEquals(listOf("read pinned"), targetCommands)
            assertTrue((rejects.session as Session).executedCommands.isEmpty())
            assertTrue(runCatching {
                jobs.start("never launch stdin on unsupported tab", keepStdinOpen = true)
            }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf("read pinned"), targetCommands)
        } finally {
            jobs.close()
            manager.dispose(false)
            scope.cancel()
        }
    }

    @Test fun inputCapabilityIsPinnedToTheOriginalBackend() = runBlocking {
        val scope = scope()
        var index = 0
        val manager = manager(scope) {
            index++
            if (index == 1) object : Session() {
                override val supportsCommandInput: Boolean get() = true
                override suspend fun prepareCommandInput(): Boolean = true
            } else Session()
        }
        try {
            val yes = manager.createLocal()
            val no = manager.createLocal()
            val router = ExecutionTerminalRouter(manager, Session())
            manager.switch(yes.id)
            val pinned = router.bindCommandSession()
            manager.switch(no.id)
            assertTrue(pinned.supportsCommandInput)
            assertTrue(pinned.prepareCommandInput())
            assertFalse(router.supportsCommandInput)
            assertFalse(router.prepareCommandInput())
            val unsupported = router.bindCommandSession()
            manager.switch(yes.id)
            assertFalse(unsupported.supportsCommandInput)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun agentRouterUsesVisibleTabWithoutCreatingHiddenLinuxTab() = runBlocking {
        val scope = scope()
        val manager = manager(scope)
        val router = ExecutionTerminalRouter(manager, Session())
        try {
            val one = manager.createLocal()
            val two = manager.createLocal()
            assertEquals(0, router.execOnce("printf second", 1_000).exitCode)
            assertEquals(2, manager.tabs.value.size)
            manager.switch(one.id)
            assertEquals(0, router.execOnce("printf first", 1_000).exitCode)
            assertEquals(listOf("printf first"), (one.session as Session).executedCommands)
            assertEquals(listOf("printf second"), (two.session as Session).executedCommands)
            assertEquals(2, manager.tabs.value.size)
            manager.close(one.id)
            manager.close(two.id)
            val unavailable = router.execOnce("printf unavailable", 1_000)
            assertEquals(126, unavailable.exitCode)
            assertTrue(unavailable.output.contains("No active terminal"))
            assertTrue(manager.tabs.value.isEmpty())
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun agentInterruptNeverSendsCtrlCToHumanShell() = runBlocking {
        val scope = scope()
        var interrupted = 0
        val manager = manager(scope) {
            object : Session() {
                override fun interrupt() { interrupted++ }
            }
        }
        try {
            manager.createLocal()
            val router = ExecutionTerminalRouter(manager, Session())
            router.interrupt()
            assertEquals(0, interrupted)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun secondTabCanBeCreatedSelectedAndClosedWithoutLosingFirstSession() {
        val scope = scope()
        val manager = manager(scope)
        try {
            val first = manager.createLocal()
            val second = manager.createLocal()
            assertEquals(second.id, manager.snapshot.value.activeId)
            assertTrue(manager.close(second.id).isSuccess)
            assertEquals(first.id, manager.snapshot.value.activeId)
            assertSame(first.session, manager.activeSession())
            assertEquals(0, (first.session as Session).destroyed)
            assertEquals(1, (second.session as Session).destroyed)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun closingInactiveTabKeepsSelectionAndRepeatedCloseIsHarmless() {
        val scope = scope()
        val manager = manager(scope)
        try {
            val first = manager.createLocal()
            val second = manager.createLocal()
            manager.close(first.id)
            manager.close(first.id)
            manager.switch(first.id)
            assertEquals(second.id, manager.snapshot.value.activeId)
            assertEquals(1, (first.session as Session).destroyed)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun lastTabCanBeClosedAndNewTabOpenedAfterwards() {
        val scope = scope()
        val manager = manager(scope)
        try {
            manager.close(manager.createLocal().id)
            assertTrue(manager.snapshot.value.tabs.isEmpty())
            assertNull(manager.snapshot.value.activeId)
            assertNull(manager.activeSession())
            assertTrue(manager.canCreateTab)
            assertEquals(manager.createLocal().id, manager.snapshot.value.activeId)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun closingActiveTabSelectsItsNeighbor() {
        val scope = scope()
        val manager = manager(scope)
        try {
            val tabs = (1..3).map { manager.createLocal() }
            manager.switch(tabs[1].id)
            manager.close(tabs[1].id)
            assertEquals(tabs[2].id, manager.snapshot.value.activeId)
            manager.close(tabs[2].id)
            assertEquals(tabs[0].id, manager.snapshot.value.activeId)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun failedStartCleansUpWithoutPublishingBrokenTabOrMaskingFailure() {
        val scope = scope()
        val start = IOException("PTY unavailable")
        val cleanup = IOException("cleanup unavailable")
        val session = Session(start, cleanup)
        val manager = manager(scope) { session }
        try {
            val thrown = runCatching { manager.createLocal() }.exceptionOrNull()
            assertSame(start, thrown)
            assertEquals(listOf(cleanup), start.suppressed.toList())
            assertEquals(1, session.destroyed)
            assertTrue(manager.snapshot.value.tabs.isEmpty())
            assertNull(manager.snapshot.value.activeId)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun constructionFailureLeavesExistingTerminalUsable() {
        val scope = scope()
        var fail = false
        val manager = manager(scope) { if (fail) throw IOException("No descriptors") else Session() }
        try {
            val first = manager.createLocal()
            fail = true
            assertTrue(runCatching { manager.createLocal() }.isFailure)
            assertEquals(listOf(first), manager.snapshot.value.tabs)
            assertSame(first.session, manager.activeSession())
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun failedCleanupStillRemovesClosedTabAndReportsFailure() {
        val scope = scope()
        val manager = manager(scope) { Session(closeFailure = IOException("Cannot stop process")) }
        try {
            val tab = manager.createLocal()
            assertEquals("Cannot stop process", manager.close(tab.id).exceptionOrNull()?.message)
            assertTrue(manager.snapshot.value.tabs.isEmpty())
            assertNull(manager.snapshot.value.activeId)
            assertTrue(manager.close(tab.id).isSuccess)
            assertEquals(1, (tab.session as Session).destroyed)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun disposalAttemptsEverySessionEvenWhenSomeCleanupFails() {
        val scope = scope()
        val sessions = (1..3).map { Session(closeFailure = IOException("close $it")) }
        var next = 0
        val manager = manager(scope) { sessions[next++] }
        try {
            repeat(3) { manager.createLocal() }
            val failure = manager.dispose(false).exceptionOrNull()
            assertEquals("close 1", failure?.message)
            assertEquals(2, failure?.suppressed?.size)
            assertTrue(sessions.all { it.destroyed == 1 })
            assertTrue(manager.snapshot.value.tabs.isEmpty())
            assertFalse(manager.canCreateTab)
            assertTrue(manager.dispose(false).isSuccess)
        } finally { scope.cancel() }
    }

    @Test fun preservationDoesNotRetainAnUnhostedFallbackSession() {
        val scope = scope()
        val manager = manager(scope)
        val tab = manager.createLocal()
        try {
            assertTrue(manager.dispose(true).isSuccess)
            assertEquals(1, (tab.session as Session).destroyed)
        } finally { scope.cancel() }
    }

    @Test fun capacityLimitDoesNotCreateAnExtraSessionAndRecoversAfterClose() {
        val scope = scope()
        var made = 0
        val manager = manager(scope) { made++; Session() }
        try {
            val tabs = (1..16).map { manager.createLocal() }
            assertFalse(manager.canCreateTab)
            assertTrue(runCatching { manager.createLocal() }.isFailure)
            assertEquals(16, made)
            manager.close(tabs[4].id)
            manager.createLocal()
            assertEquals(17, made)
            assertEquals(16, manager.snapshot.value.tabs.size)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun snapshotsStayCoherentDuringRapidCreationSwitchTitleAndClosure() = runBlocking {
        val scope = scope()
        val manager = manager(scope)
        var observed = 0
        val observer = scope.launch {
            manager.snapshot.collect { state ->
                observed++
                assertEquals(state.tabs.isEmpty(), state.activeId == null)
                assertTrue(state.activeId == null || state.tabs.any { it.id == state.activeId })
            }
        }
        try {
            repeat(80) {
                val first = manager.createLocal(activate = false)
                assertEquals(first.id, manager.snapshot.value.activeId)
                val second = manager.createLocal()
                manager.switch(first.id)
                (first.session as Session).terminalTitle.value = "working $it"
                manager.close(first.id)
                first.session.terminalTitle.value = "stale title"
                assertEquals(second.id, manager.snapshot.value.activeId)
                manager.close(second.id)
            }
            assertTrue(observed > 300)
        } finally { observer.cancel(); manager.dispose(false); scope.cancel() }
    }

    @Test fun cancelledWorkspaceCannotCreateAnotherSession() {
        val scope = scope()
        var made = 0
        val manager = manager(scope) { made++; Session() }
        scope.cancel()
        assertFalse(manager.canCreateTab)
        assertTrue(runCatching { manager.createLocal() }.exceptionOrNull() is CancellationException)
        assertEquals(0, made)
    }

    @Test fun closingWorkspaceCancelsQemuSetupBeforeItCanPublishATerminal() = runBlocking {
        val scope = scope()
        val entered = CompletableDeferred<Unit>()
        var created = 0
        val manager = TerminalManager(scope, File("terminal-tests"),
            linuxLaunchSpecFactory = { _, qemu ->
                assertTrue(qemu)
                entered.complete(Unit)
                awaitCancellation()
            }, localSessionFactory = { _, _, _ -> created++; Session() })
        val setup = async { manager.openLinuxTerminal(qemu = true) }
        try {
            entered.await()
            manager.dispose(false)
            assertTrue(runCatching { setup.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, created)
            assertTrue(manager.snapshot.value.tabs.isEmpty())
            assertTrue(currentCoroutineContext().isActive)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun lateQemuPreparationCannotStartAChildAfterWorkspaceWasClosed() = runBlocking {
        val scope = scope()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var created = 0
        val manager = TerminalManager(scope, File("terminal-tests"),
            linuxLaunchSpecFactory = { _, _ ->
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                TerminalLaunchSpec(prefix = listOf("/usr/bin/env"), shell = "/bin/sh")
            }, localSessionFactory = { _, _, _ -> created++; Session() })
        val setup = async { manager.openLinuxTerminal(qemu = true) }
        try {
            entered.await()
            manager.dispose(false)
            release.complete(Unit)
            assertTrue(runCatching { setup.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, created)
            assertTrue(manager.snapshot.value.tabs.isEmpty())
        } finally { release.complete(Unit); manager.dispose(false); scope.cancel() }
    }

    @Test fun fullTerminalCapacityRejectsQemuBeforeExpensiveGuestPreparation() = runBlocking {
        val scope = scope()
        var prepared = 0
        val manager = TerminalManager(scope, File("terminal-tests"),
            linuxLaunchSpecFactory = { _, _ -> prepared++; TerminalLaunchSpec(prefix = listOf("/usr/bin/env"), shell = "/bin/sh") },
            localSessionFactory = { _, _, _ -> Session() })
        try {
            repeat(16) { manager.createLocal() }
            assertTrue(runCatching { manager.openLinuxTerminal(qemu = true) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, prepared)
            assertEquals(16, manager.snapshot.value.tabs.size)
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun queuedUbuntuAndQemuPreparationsAreCanceledWhenWorkspaceCloses() = runBlocking {
        val scope = scope()
        val entered = CompletableDeferred<Unit>()
        var prepared = 0
        val manager = TerminalManager(scope, File("terminal-tests"),
            linuxLaunchSpecFactory = { _, _ -> prepared++; entered.complete(Unit); awaitCancellation() },
            localSessionFactory = { _, _, _ -> Session() })
        val first = async { manager.openLinuxTerminal(qemu = true) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { manager.openLinuxTerminal(qemu = false) }
        try {
            manager.dispose(false)
            listOf(first, second).forEach { assertTrue(runCatching { it.await() }.exceptionOrNull() is CancellationException) }
            assertEquals(1, prepared)
            assertTrue(manager.snapshot.value.tabs.isEmpty())
        } finally { manager.dispose(false); scope.cancel() }
    }

    @Test fun repeatedUbuntuAlpineSwitchingPreservesProcessesAndCorrectBackendProfiles() = runBlocking {
        val scope = scope()
        val prepared = mutableListOf<Boolean>()
        val manager = TerminalManager(scope, File("terminal-tests"),
            linuxLaunchSpecFactory = { _, qemu ->
                prepared += qemu
                TerminalLaunchSpec(prefix = listOf("/usr/bin/env"), shell = if (qemu) "/bin/sh" else "/bin/bash")
            }, localSessionFactory = { _, _, _ -> Session() })
        try {
            repeat(100) {
                val ubuntu = manager.openLinuxTerminal()
                val alpine = manager.openLinuxTerminal(qemu = true)
                manager.switch(ubuntu.id)
                assertSame(ubuntu.session, manager.activeSession())
                assertEquals(TerminalManager.Profile.LOCAL_LINUX_ARM64, manager.activeTab()?.profile)
                manager.switch(alpine.id)
                assertSame(alpine.session, manager.activeSession())
                assertEquals(TerminalManager.Profile.LOCAL_QEMU, manager.activeTab()?.profile)
                assertEquals(0, (ubuntu.session as Session).destroyed)
                manager.close(alpine.id)
                manager.close(ubuntu.id)
                assertTrue(manager.snapshot.value.tabs.isEmpty())
                assertEquals(1, ubuntu.session.destroyed)
                assertEquals(1, (alpine.session as Session).destroyed)
            }
            assertEquals(200, prepared.size)
        } finally { manager.dispose(false); scope.cancel() }
    }
}
