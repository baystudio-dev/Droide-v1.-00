package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exact constructed-candidate cancellation seam; no added production test hook. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [R38NoProvider::class, R38NoProcess::class, R38NoStdioProcess::class, R38NoNativeTerminal::class, R38NoCommandTerminal::class],
    instrumentedPackages = ["com.baystudio.droide.core.LlmClient", "com.baystudio.droide.core.LocalProcessSupervisor",
        "com.baystudio.droide.core.LocalLinuxProcessHost", "com.baystudio.droide.core.NativePtyTerminalSession", "com.baystudio.droide.core.TerminalSession"])
class WorkspaceApprovalCandidateRetryTest {
    companion object {
        private val appRoot = Files.createTempDirectory("r38-candidate-retry-").toFile().canonicalFile
        @JvmStatic @AfterClass fun removeFixture() {
            PathSecurity.deleteTreeNoFollow(appRoot)
            WorkspaceApprovalHandoffTest.removeFixture()
        }
    }
    // Reuse the exact signature-checked process/provider safety guards, not their scenario.
    private val guards = WorkspaceApprovalHandoffTest()
    @Before fun prepare() { guards.prepare() }
    @After fun restore() { guards.restore() }

    @Test(timeout = 20_000) fun cancelledFirstConstructedCandidateRetiresSeedAndRetryUsesFreshManager() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failure: Throwable? = null
        val work = scope.launch {
            try { withTimeout(12_000) {
                val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                    override fun getApplicationContext(): Context = this
                    override fun getFilesDir(): File = appRoot
                }
                val root = File(appRoot, "projects/default").apply { mkdirs() }
                val sentinel = File(root, "keep.kt").apply { writeText("saved source") }
                val store = object : ProjectStore {
                    override suspend fun load() = listOf(DroideProject("default", "default", root.path))
                    override suspend fun loadActiveId() = "default"
                    override suspend fun save(all: List<DroideProject>) = Unit
                    override suspend fun saveActiveId(id: String) = Unit
                }
                val recents = object : ProjectRecentsStore {
                    override suspend fun load() = listOf("default")
                    override suspend fun save(ids: List<String>) = Unit
                }
                val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
                    configureIdentity = {}, create = { error("No transport") },
                    pair = { _, _, _ -> error("No pairing") }, discovery = { error("No discovery") }))
                val seed = ApprovalManager()
                val constructed = CompletableDeferred<Unit>()
                val cancelFirst = AtomicBoolean(true)
                val coordinator = WorkspaceCoordinator(context, scope, ProjectManager(appRoot, store, recents), approvals = seed,
                    deviceBridge = bridge, processReconciliation = { error("Stop before installed startup") },
                    initialUbuntuPreparation = { error("No Ubuntu preparation") }, startupMeasurementObserver = { measurement ->
                        if (measurement.step == WorkspaceStartupStep.RUNTIME_BINDINGS && measurement.outcome == WorkspaceStartupOutcome.SUCCEEDED &&
                            cancelFirst.compareAndSet(true, false)) constructed.complete(Unit)
                    })
                var attempt: Job? = null
                var canceller: Job? = null
                var pendingSeed: Deferred<ApprovalDecision>? = null
                try {
                    assertNotNull(runCatching { coordinator.init() }.exceptionOrNull())
                    val seedRequest = async(start = CoroutineStart.UNDISPATCHED) { seed.requestApproval("inert", "seed", "No executable payload") }
                        .also { pendingSeed = it }
                    val first = launch(start = CoroutineStart.LAZY) { coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS) }
                        .also { attempt = it }
                    canceller = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        constructed.await(); first.cancel(CancellationException("Cancel constructed candidate before publication"))
                    }
                    first.start(); first.join(); canceller?.join()
                    assertTrue(first.isCancelled)
                    assertNull(coordinator.runtime.value)
                    assertSame(ApprovalDecision.Denied, seedRequest.await())
                    assertNull(seed.pending.value)
                    assertSame(ApprovalDecision.Denied, seed.requestApproval("inert", "late seed", "No executable payload"))
                    // Existing recovery gate requires a failed normal attempt after cancellation.
                    assertNotNull(runCatching { coordinator.init() }.exceptionOrNull())
                    coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS)
                    val live = checkNotNull(coordinator.runtime.value)
                    assertNotSame(seed, live.agent.approvals)
                    assertSame(live.agent.approvals, coordinator.approvals())
                    val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                        live.agent.approvals.requestApproval("inert", "replacement", "No executable payload")
                    }
                    try {
                        val pending = checkNotNull(live.agent.approvals.pending.value)
                        assertTrue(live.agent.approvals.resolve(pending.id, ApprovalResolution.ALLOW_ONCE))
                        assertSame(ApprovalDecision.Approved, replacement.await())
                    } finally { replacement.cancelAndJoin() }
                    assertTrue(live.terminals.tabs.value.isEmpty())
                    assertEquals("saved source", sentinel.readText())
                } finally {
                    constructed.complete(Unit)
                    try { withContext(NonCancellable) { withTimeout(3_000) {
                        pendingSeed?.cancelAndJoin(); attempt?.cancelAndJoin(); canceller?.cancelAndJoin()
                    } } } finally { try { coordinator.close() } finally { bridge.close() } }
                }
            } } catch (caught: Throwable) { failure = caught }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(16)
            while (!work.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertTrue("Bounded candidate fixture did not settle", work.isCompleted)
            failure?.let { throw it }
        } finally { work.cancel(); scope.cancel() }
    }
}
