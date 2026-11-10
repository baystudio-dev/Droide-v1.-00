package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Process
import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import com.baystudio.droide.ui.EditorWorkspaceState
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowProcess

/** Real coordinator publication and real service owner; no model run or executable fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [R38NoProvider::class, R38NoProcess::class, R38NoStdioProcess::class, R38NoNativeTerminal::class, R38NoCommandTerminal::class],
    instrumentedPackages = ["com.baystudio.droide.core.LlmClient", "com.baystudio.droide.core.LocalProcessSupervisor",
        "com.baystudio.droide.core.LocalLinuxProcessHost", "com.baystudio.droide.core.NativePtyTerminalSession", "com.baystudio.droide.core.TerminalSession"])
class WorkspaceApprovalHandoffTest {
    companion object {
        private val appRoot = Files.createTempDirectory("r38-approval-handoff-").toFile().canonicalFile
        @JvmStatic @AfterClass fun removeFixture() { PathSecurity.deleteTreeNoFollow(appRoot) }
    }
    private var previousPid = 0
    @Before fun prepare() {
        previousPid = Process.myPid(); ShadowProcess.setPid(4242)
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        R38ForbiddenExecution.calls.set(0)
        assertTrue(Shadow.extract<Any>(LocalProcessSupervisor) is R38NoProcess)
        assertTrue(Shadow.extract<Any>(LlmClient()) is R38NoProvider)
        assertTrue(Shadow.extract<Any>(LocalLinuxProcessHost(appRoot, CoroutineScope(Dispatchers.Unconfined))) is R38NoStdioProcess)
        // Exact suspend signatures include Continuation. A signature mismatch must fail before the real
        // coordinator is built, rather than silently falling through.
        for ((real, fake, names) in listOf(
            Triple(LocalProcessSupervisor::class.java, R38NoProcess::class.java, setOf("capture")),
            Triple(LocalLinuxProcessHost::class.java, R38NoStdioProcess::class.java, setOf("start", "startInWorkspace", "resolveExecutable")),
            Triple(LlmClient::class.java, R38NoProvider::class.java, setOf("chatCompletions", "chatCompletionsStream")),
        )) for (method in real.declaredMethods.filter { it.name in names }) {
            val replacement = fake.getDeclaredMethod(method.name, *method.parameterTypes)
            assertEquals(method.returnType, replacement.returnType)
            assertNotNull(replacement.getAnnotation(Implementation::class.java))
        }
    }
    @After fun restore() {
        ShadowProcess.setPid(previousPid)
        assertEquals("No provider, process, or terminal path may execute", 0, R38ForbiddenExecution.calls.get())
    }

    private class Store(private val records: List<DroideProject>) : ProjectStore {
        var selected = "default"
        val beforeCommit = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var failCommit = false
        override suspend fun load(): List<DroideProject> = records
        override suspend fun loadActiveId(): String = selected
        override suspend fun save(all: List<DroideProject>) = Unit
        override suspend fun saveActiveId(id: String) {
            if (id == "second") {
                beforeCommit.complete(Unit); releaseCommit.await()
                if (failCommit) throw IOException("fixture selection did not commit")
            }
            selected = id
        }
    }
    private class Recents : ProjectRecentsStore {
        override suspend fun load(): List<String> = listOf("default", "second")
        override suspend fun save(ids: List<String>) = Unit
    }

    @Test(timeout = 20_000) fun allowOnceCannotResumeAWhilePublishedBWaitsForOldCleanup() =
        scenario(ApprovalResolution.ALLOW_ONCE)
    @Test(timeout = 20_000) fun allowSessionCannotGrantAWhilePublishedBWaitsForOldCleanup() =
        scenario(ApprovalResolution.ALLOW_SESSION)
    @Test(timeout = 20_000) fun lateDirtyEditorAbortPreservesAApproval() =
        scenario(ApprovalResolution.ALLOW_ONCE, lateEdit = true)
    @Test(timeout = 20_000) fun failedSelectionAbortPreservesAApproval() =
        scenario(ApprovalResolution.ALLOW_SESSION, failedSelection = true)

    private fun scenario(resolution: ApprovalResolution, lateEdit: Boolean = false, failedSelection: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failure: Throwable? = null
        val test = scope.launch {
            try { withTimeout(12_000) {
                val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                    override fun getApplicationContext(): Context = this
                    override fun getFilesDir(): File = appRoot
                }
                val source = File(appRoot, "projects/default/keep.kt").apply {
                    parentFile!!.mkdirs(); writeText("saved source")
                }
                File(appRoot, "projects/second/keep.kt").apply { parentFile!!.mkdirs(); writeText("target source") }
                val store = Store(listOf("default", "second").map {
                    DroideProject(it, it, File(appRoot, "projects/$it").path)
                }).apply { failCommit = failedSelection }
                val projects = ProjectManager(appRoot, store, Recents())
                val approvals = ApprovalManager()
                val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
                    configureIdentity = {}, create = { forbidden("transport") },
                    pair = { _, _, _ -> forbidden("pairing") }, discovery = { forbidden("discovery") },
                ))
                var normalStartupStops = 0
                val coordinator = WorkspaceCoordinator(context, scope, projects, approvals = approvals, deviceBridge = bridge,
                    processReconciliation = { normalStartupStops++; error("Stop before installed-component startup") },
                    initialUbuntuPreparation = { forbidden("Ubuntu preparation") })
                val taskEntered = CompletableDeferred<Unit>()
                val cleanupEntered = CompletableDeferred<Unit>()
                val releaseCleanup = CompletableDeferred<Unit>()
                var binding: WorkspaceDocumentAuthorityBridge.Binding? = null
                var switching: Deferred<Result<Unit>>? = null
                var askingA: Deferred<ApprovalDecision>? = null
                var queuedA: Deferred<ApprovalDecision>? = null
                var askingB: Deferred<ApprovalDecision>? = null
                var oldRuntime: WorkspaceRuntime? = null
                try {
                    assertNotNull(runCatching { coordinator.init() }.exceptionOrNull())
                    coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS)
                    val a = checkNotNull(coordinator.runtime.value).also { oldRuntime = it }
                    assertEquals(WorkspaceStartupMode.TEMPORARY_BUILTINS, a.startupMode)
                    assertFalse(a.extensions.installedComponentsEnabled)
                    assertTrue(a.terminals.tabs.value.isEmpty())
                    assertSame(AgentPluginSource.EMPTY, a.agent.mcpPluginSource())
                    val editor = EditorWorkspaceState()
                    val document = editor.document("keep.kt").also { it.ensureLoaded(a.files) }
                    binding = a.documentAuthority.bind(EditorWorkspaceDocumentAuthority(editor, a.files, a.lsp, Dispatchers.Main.immediate))

                    // Local session creation/load only. Capture the same private production predicate used by normal
                    // native tool/hook approval, not an always-true owner.
                    val owner = prepareOwner(a.agent)
                    val sessionId = checkNotNull(a.agent.currentSessionId)
                    val sessionBytes = File(a.files.root, ".droide/sessions/$sessionId.json").readBytes()
                    val earlierGrant = async(start = CoroutineStart.UNDISPATCHED) { ask(approvals, owner, "A earlier grant", "r38/prior") }
                    assertTrue(approvals.resolve(checkNotNull(approvals.pending.value).id, ApprovalResolution.ALLOW_SESSION))
                    assertSame(ApprovalDecision.Approved, earlierGrant.await())
                    assertTrue(approvals.hasSessionApproval(sessionId, owner.permissionScope, "r38_inert", "r38/prior"))
                    val dispatched = AtomicInteger()
                    val requestA = async(start = CoroutineStart.UNDISPATCHED) {
                        ask(approvals, owner, "A", "r38/inert").also {
                            if (it == ApprovalDecision.Approved) dispatched.incrementAndGet()
                        }
                    }.also { askingA = it }
                    val pendingA = checkNotNull(approvals.pending.value)
                    assertTrue(owner.isStillCurrent())
                    assertTrue(pendingA.canAllowForSession)
                    if (!lateEdit && !failedSelection) {
                        queuedA = async(start = CoroutineStart.UNDISPATCHED) { ask(approvals, owner, "A queued", "r38/queued") }
                        assertFalse(checkNotNull(queuedA).isCompleted)
                        assertSame(pendingA, approvals.pending.value)
                    }

                    val taskManager = TaskManager(a.files.root, taskExecutor = { _, timeout ->
                        assertEquals(0L, timeout)
                        taskEntered.complete(Unit)
                        try { awaitCancellation() } finally {
                            withContext(NonCancellable) {
                                cleanupEntered.complete(Unit)
                                withTimeout(8_000) { releaseCleanup.await() }
                            }
                        }
                    })
                    assertTrue(a.execution.projectTasks.start(listOf(IdeTask("R38 inert barrier", listOf("r38-never-execute"), a.files.root)), taskManager, {}))
                    taskEntered.await()
                    val switch = async { runCatching { coordinator.switchProject("second") } }.also { switching = it }
                    store.beforeCommit.await()
                    assertSame(a, coordinator.runtime.value)
                    assertSame(pendingA, approvals.pending.value)
                    assertTrue(owner.isStillCurrent())
                    if (lateEdit) document.capture("late edit preserved")
                    store.releaseCommit.complete(Unit)

                    if (lateEdit || failedSelection) {
                        assertTrue(switch.await().isFailure)
                        assertSame(a, coordinator.runtime.value)
                        assertEquals("default", projects.active()?.id)
                        assertEquals("default", store.selected)
                        assertTrue(owner.isStillCurrent())
                        assertFalse(cleanupEntered.isCompleted)
                        assertSame(pendingA, approvals.pending.value)
                        assertTrue(approvals.resolve(pendingA.id, resolution))
                        assertSame(ApprovalDecision.Approved, requestA.await())
                        assertEquals(1, dispatched.get())
                        if (lateEdit) {
                            assertTrue(document.dirty)
                            assertEquals("late edit preserved", a.documentAuthority.dirtySnapshots().single().content)
                        }
                    } else {
                        val b = checkNotNull(coordinator.runtime.first { it?.project?.id == "second" })
                        val pendingWhenBPublished = approvals.pending.value
                        val ownerCurrentWhenBPublished = owner.isStillCurrent()
                        cleanupEntered.await()
                        assertFalse("Switch must still be waiting for old task cleanup", switch.isCompleted)
                        assertTrue("Full Agent shutdown must remain after ordered resource cleanup", lifecycle(a.agent).isActive)
                        // A stale callback may be consumed as DENY or already withdrawn. Neither return value alone
                        // proves permission; inspect the actual decision/grant.
                        approvals.resolve(pendingA.id, resolution)
                        val decision = requestA.await()
                        val hasGrant = approvals.hasSessionApproval(sessionId, pendingA.permissionScope,
                            pendingA.sessionGrantAction, pendingA.sessionGrantResource)

                        val queued = checkNotNull(queuedA)
                        while (!queued.isCompleted && approvals.pending.value == null) yield()
                        val queuedWasShown = approvals.pending.value != null
                        approvals.pending.value?.let { approvals.resolve(it.id, ApprovalResolution.ALLOW_SESSION) }
                        val queuedDecision = queued.await()
                        val oldGrantReuse = ask(approvals, owner, "A late cached request", "r38/prior")

                        // B owns a distinct manager after handoff. Old manager callbacks cannot answer its prompt, even
                        // if manager-local request IDs are reused.
                        val approvalsB = coordinator.approvals()
                        assertSame(b.agent.approvals, approvalsB)
                        assertNotSame(approvals, approvalsB)
                        val ownerB = prepareOwner(b.agent)
                        val requestB = async(start = CoroutineStart.UNDISPATCHED) { ask(approvalsB, ownerB, "B", "r38/b") }
                            .also { askingB = it }
                        val pendingB = checkNotNull(approvalsB.pending.value)
                        assertFalse(approvals.resolve(pendingA.id, ApprovalResolution.ALLOW_SESSION))
                        assertSame(pendingB, approvalsB.pending.value)
                        releaseCleanup.complete(Unit)
                        assertTrue(switch.await().isSuccess)
                        assertFalse(lifecycle(a.agent).isActive)
                        assertSame("Old cleanup must preserve an unrelated B pending request", pendingB, approvalsB.pending.value)
                        assertTrue(approvalsB.resolve(pendingB.id, ApprovalResolution.ALLOW_ONCE))
                        assertSame(ApprovalDecision.Approved, requestB.await())
                        assertTrue(ownerB.isStillCurrent())
                        assertTrue(b.terminals.tabs.value.isEmpty())
                        assertArrayEquals("Retirement must not clear old conversation storage", sessionBytes,
                            File(a.files.root, ".droide/sessions/$sessionId.json").readBytes())
                        assertSame("Old permission cannot be accepted after B is published", ApprovalDecision.Denied, decision)
                        assertFalse("Retired A must not retain permission at B publication", ownerCurrentWhenBPublished)
                        assertNull("Retired A prompt must be withdrawn before B publication", pendingWhenBPublished)
                        assertFalse("No stale session authority may be inserted", hasGrant)
                        assertEquals("No old inert action may dispatch", 0, dispatched.get())
                        assertFalse("Queued A must not publish after retirement", queuedWasShown)
                        assertSame(ApprovalDecision.Denied, queuedDecision)
                        assertSame("A grant accepted before retirement cannot revive its old owner", ApprovalDecision.Denied, oldGrantReuse)
                    }
                    assertEquals("saved source", source.readText())
                    assertEquals(1, normalStartupStops)
                    assertEquals(0, R38ForbiddenExecution.calls.get())
                } finally {
                    store.releaseCommit.complete(Unit); releaseCleanup.complete(Unit)
                    try {
                        withContext(NonCancellable) { withTimeout(3_000) {
                            askingA?.cancelAndJoin(); queuedA?.cancelAndJoin(); askingB?.cancelAndJoin(); switching?.cancelAndJoin()
                            oldRuntime?.execution?.projectTasks?.closeAndJoin()
                        } }
                    } finally {
                        try { binding?.close() } finally {
                            try { coordinator.close() } finally { bridge.close() }
                        }
                    }
                }
            } } catch (caught: Throwable) { failure = caught }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(16)
            while (!test.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertTrue("Bounded fixture did not settle; timeout is not defect evidence", test.isCompleted)
            failure?.let { throw it }
        } finally { test.cancel(); scope.cancel() }
    }

    private suspend fun prepareOwner(agent: AgentService): HookInvocationAuthority {
        agent.loadSession(agent.sessions.create("R38 local owner fixture"))
        val generation = AgentService::class.java.getDeclaredField("runGeneration").apply { isAccessible = true }
            .get(agent) as AtomicLong
        val capture = AgentService::class.java.declaredMethods.single { it.name == "captureHookAuthority" }
            .apply { isAccessible = true }
        return (capture.invoke(agent, generation.get(), checkNotNull(agent.currentSessionId), null, null) as HookInvocationAuthority)
            .also { checkNotNull(it.approvalAdmission); assertTrue(it.isStillCurrent()) }
    }
    private fun lifecycle(agent: AgentService): CoroutineScope = AgentService::class.java.getDeclaredField("lifecycleScope")
        .apply { isAccessible = true }.get(agent) as CoroutineScope
    private suspend fun ask(manager: ApprovalManager, owner: HookInvocationAuthority, label: String, resource: String) =
        manager.withApprovalOwner(checkNotNull(owner.approvalAdmission)) {
            manager.requestApproval("r38_inert", label, "No executable payload", action = "r38_inert", resource = resource,
                permissionScope = owner.permissionScope, provenance = owner.provenance)
        }
    private fun forbidden(label: String): Nothing = R38ForbiddenExecution.reject(label)
}

private object R38ForbiddenExecution {
    val calls = AtomicInteger()
    fun reject(label: String): Nothing { calls.incrementAndGet(); throw AssertionError("Forbidden fixture route: $label") }
}

/** Every public Llm entrypoint fails before URL validation, DNS, credentials, or sockets. */
@Implements(value = LlmClient::class, isInAndroidSdk = false, callThroughByDefault = false)
class R38NoProvider {
    @Implementation suspend fun chatCompletions(baseUrl: String, apiKey: String, body: String): String = R38ForbiddenExecution.reject("provider")
    @Implementation suspend fun chatCompletions(providerId: String, baseUrl: String, apiKey: String, expectedModelId: String, body: String): String =
        R38ForbiddenExecution.reject("provider")
    @Implementation suspend fun chatCompletionsStream(providerId: String, baseUrl: String, apiKey: String, expectedModelId: String,
        body: String, onEvent: suspend (LlmStreamEvent) -> Unit): LlmStreamResult = R38ForbiddenExecution.reject("provider stream")
}

@Implements(value = LocalProcessSupervisor::class, isInAndroidSdk = false, callThroughByDefault = false)
class R38NoProcess {
    @Implementation suspend fun capture(argv: List<String>, cwd: File, environment: Map<String, String>, maxOutputBytes: Int,
        timeoutMs: Long, input: ((OutputStream) -> Unit)?, cleanup: (() -> Unit)?, onOutput: ((String) -> Unit)?,
        keepTail: Boolean, streamInput: ProcessInputBuffer?, onCleanupCompleted: ((Throwable?) -> Unit)?): ExecResult =
        R38ForbiddenExecution.reject("process capture")
    @Implementation fun terminate(process: java.lang.Process) { R38ForbiddenExecution.reject("process terminate") }
}

/** Persistent stdio uses ProcessBuilder directly, so guard it independently of capture. */
@Implements(value = LocalLinuxProcessHost::class, isInAndroidSdk = false, callThroughByDefault = false)
class R38NoStdioProcess {
    @Implementation suspend fun resolveExecutable(command: String): String? = R38ForbiddenExecution.reject("stdio lookup")
    @Implementation suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess =
        R38ForbiddenExecution.reject("stdio process")
    @Implementation suspend fun startInWorkspace(argv: List<String>, environment: Map<String, String>, remoteCwd: String): HostedStdioProcess =
        R38ForbiddenExecution.reject("workspace stdio process")
    @Implementation suspend fun startInWorkspace(argv: List<String>, environment: Map<String, String>, remoteCwd: String,
        resourceLimits: ProcessResourceLimits?): HostedStdioProcess = R38ForbiddenExecution.reject("workspace stdio process")
}

@Implements(value = NativePtyTerminalSession::class, isInAndroidSdk = false, callThroughByDefault = false)
class R38NoNativeTerminal {
    @Implementation fun __constructor__(context: Context, workDir: File, scope: CoroutineScope, launchSpec: TerminalLaunchSpec) {
        R38ForbiddenExecution.reject("native terminal construction")
    }
}

@Implements(value = TerminalSession::class, isInAndroidSdk = false, callThroughByDefault = false)
class R38NoCommandTerminal {
    @Implementation fun __constructor__(workDir: File, scope: CoroutineScope, launchSpec: TerminalLaunchSpec, commandModeReason: String?) {
        R38ForbiddenExecution.reject("command terminal construction")
    }
}
