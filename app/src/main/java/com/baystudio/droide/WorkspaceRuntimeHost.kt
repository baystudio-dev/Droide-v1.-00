package com.baystudio.droide

import android.app.Application
import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.baystudio.droide.core.EditorRecoveryRequest
import com.baystudio.droide.core.EditorStateStore
import com.baystudio.droide.core.ModelsDevCatalog
import com.baystudio.droide.core.WorkspaceCoordinator
import com.baystudio.droide.core.WorkspaceRuntime
import com.baystudio.droide.core.WorkspaceStartupMode
import com.baystudio.droide.core.WorkspaceStartupPhase
import com.baystudio.droide.core.StartupRecoveryPolicy
import com.baystudio.droide.core.BrowserApplicationVisibility
import com.baystudio.droide.core.BrowserApplicationTimers
import com.baystudio.droide.ui.EditorRecoveryDiscardController
import com.baystudio.droide.ui.EditorWorkspaceState
import com.baystudio.droide.ui.ExplorerWorkspaceState
import com.baystudio.droide.ui.ExplorerRefreshController
import com.baystudio.droide.ui.AndroidExplorerDirectoryWatch
import kotlinx.coroutines.sync.Mutex
import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import com.baystudio.droide.core.runSuspendCatching
import com.baystudio.droide.core.WorkspaceFailureBoundary
import com.baystudio.droide.core.WorkspaceFailureNotice
import com.baystudio.droide.core.WorkspaceFailureEvent
import com.baystudio.droide.core.WorkspaceFailureReason
import com.baystudio.droide.core.consumeWorkspaceFailure
import com.baystudio.droide.core.publishWorkspaceFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import com.baystudio.droide.core.ApplicationWorkAdmission
import com.baystudio.droide.core.ApplicationExitSequence
import com.baystudio.droide.core.ExitOwnerBarrier
import com.baystudio.droide.core.ExitHostedProcessRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// The process owns work. An Activity/ViewModel owns only a view of this single runtime.


internal class WorkspaceRuntimeHost(
    private val application: Application,
    coordinatorFactory: (Context, CoroutineScope, BrowserApplicationTimers) -> WorkspaceCoordinator = { context, scope, timers ->
        WorkspaceCoordinator(context, scope, browserApplicationTimers = timers)
    },
) : AutoCloseable {
    private val _runtimeFailure = MutableStateFlow<WorkspaceFailureEvent?>(null)
    val runtimeFailure: StateFlow<WorkspaceFailureEvent?> = _runtimeFailure
    private val failureNotice = WorkspaceFailureNotice()
    private val failureBoundary = WorkspaceFailureBoundary(::reportWorkspaceFailure)
    private val runtimeJob = SupervisorJob()
    internal val runtimeScope = CoroutineScope(runtimeJob + Dispatchers.Main.immediate + failureBoundary.handler)
    internal val environmentSetup = com.baystudio.droide.core.UbuntuEnvironmentSetup.create(application, runtimeScope)
    private var initJob: Job? = null
    internal val uiWorkOwner = com.baystudio.droide.core.ExitUiWorkOwner()

    val startupRecovery = StartupRecoveryPolicy.forProcess(application.applicationContext)

    private val browserTimers = BrowserApplicationTimers { failure ->
        android.util.Log.w("DroideBrowser", "Browser timer lifecycle could not be applied", failure)
    }
    val coordinator = coordinatorFactory(application.applicationContext, runtimeScope, browserTimers)

    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError


    private var editorRuntime: WorkspaceRuntime? = null
    private var exitEditorCheckpoint: com.baystudio.droide.ui.EditorExitCheckpoint? = null
    private var exitNativeRetirementFailure: Throwable? = null

    /** Called synchronously before the original Activity disposes its native surfaces. */
    internal fun beforeExitActivityDestroyed() {
        if (editorRuntime == null) return
        try {
            if (exitEditorCheckpoint == null) exitEditorCheckpoint = com.baystudio.droide.ui.prepareEditorExitCheckpointNow(
                retainedEditorState, checkNotNull(retainedEditorRecoveryStore))
            exitEditorCheckpoint?.allowNativeReleaseForRecreation()
        } catch (failure: Throwable) {
            exitNativeRetirementFailure = failure
        }
    }
    private var retainedEditorState = EditorWorkspaceState()
    private var retainedEditorRecoveryStore: EditorStateStore? = null
    private var retainedAgentRuntime: WorkspaceRuntime? = null
    private var retainedAgentUiState: com.baystudio.droide.ui.AgentWorkspaceUiState? = null
    private val retainedExplorerStates = linkedMapOf<String, ExplorerWorkspaceState>()
    // Historical UI state owns no worker. The single active binding survives Activity recreation.
    private val explorerReadLane = Mutex()
    private var explorerRuntime: WorkspaceRuntime? = null
    private var explorerStateBinding: ExplorerWorkspaceState? = null
    private var activeExplorerRefresh: ExplorerRefreshController? = null
    private var explorerChangeJob: Job? = null
    private fun closeExplorerRefresh() {
        explorerChangeJob?.cancel()
        explorerChangeJob = null
        activeExplorerRefresh?.close()
    }
    private var editorAuthorityBinding: com.baystudio.droide.core.WorkspaceDocumentAuthorityBridge.Binding? = null
    private val browserVisibility = BrowserApplicationVisibility { visible ->
        browserTimers.setApplicationVisible(visible)
        coordinator.runtime.value?.agentBrowser?.setApplicationVisible(visible)
    }
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() {
            trimEditorMemory()
            trimBrowserMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trimEditorMemory()
            trimBrowserMemory(level)
        }
    }

    private fun trimBrowserMemory(level: Int) {
        runtimeScope.launch { coordinator.runtime.value?.agentBrowser?.onMemoryPressure(level) }
    }

    private fun trimEditorMemory() {
        runtimeScope.launch {
            val lsp = editorRuntime?.lsp
            retainedEditorState.trimInactiveCleanDocuments().forEach { path ->
                runSuspendCatching { lsp?.didClose(path) }
            }
        }
    }

    fun agentUiStateFor(runtime: WorkspaceRuntime): com.baystudio.droide.ui.AgentWorkspaceUiState {
        if (retainedAgentRuntime !== runtime) {
            retainedAgentUiState?.close()
            retainedAgentRuntime = runtime
            retainedAgentUiState = com.baystudio.droide.ui.AgentWorkspaceUiState(runtimeScope, application, runtime.files.root, projectId = runtime.project.id)
        }
        return checkNotNull(retainedAgentUiState)
    }

    private fun reportWorkspaceFailure(failure: Throwable) {
        android.util.Log.e("DroideWorkspace", "A supervised workspace operation failed", failure)
        runtimeScope.launch {
            failureNotice.next()?.let { _runtimeFailure.publishWorkspaceFailure(it) }
        }
    }

    private fun reportEditorPreservationFailure(
        reason: WorkspaceFailureReason,
        failure: Throwable,
        isCurrent: () -> Boolean,
    ) {
        android.util.Log.e("DroideEditor", "Pending unsaved changes could not be preserved; project switching remains blocked", failure)
        runtimeScope.launch {
            if (isCurrent()) failureNotice.next(reason, isCurrent)?.let { _runtimeFailure.publishWorkspaceFailure(it) }
        }
    }

    fun editorStateFor(runtime: WorkspaceRuntime): EditorWorkspaceState {
        if (editorRuntime !== runtime) {
            editorAuthorityBinding?.close()
            editorRuntime = runtime
            retainedEditorState = EditorWorkspaceState()
            val recoveryStore = EditorStateStore(application, runtime.project.id, retainedEditorState.recoveryConflictAuthority)
            retainedEditorRecoveryStore = recoveryStore
            retainedEditorState.recoveryDiscardActions = EditorRecoveryDiscardController(retainedEditorState, recoveryStore, runtimeScope) {
                editorRuntime === runtime && ApplicationWorkAdmission.isOpen
            }
            editorAuthorityBinding = runtime.documentAuthority.bind(
                EditorWorkspaceDocumentAuthority(retainedEditorState, runtime.files, runtime.lsp, recoveryStore = recoveryStore,
                    preservationScope = runtimeScope, reportPreservationFailure = ::reportEditorPreservationFailure)
            )
        } else if (!runtime.documentAuthority.isBound) {
            editorAuthorityBinding = runtime.documentAuthority.bind(
                EditorWorkspaceDocumentAuthority(retainedEditorState, runtime.files, runtime.lsp,
                    recoveryStore = checkNotNull(retainedEditorRecoveryStore), preservationScope = runtimeScope,
                    reportPreservationFailure = ::reportEditorPreservationFailure)
            )
        }
        return retainedEditorState
    }

    fun editorRecoveryStoreFor(runtime: WorkspaceRuntime): EditorStateStore {
        editorStateFor(runtime)
        return checkNotNull(retainedEditorRecoveryStore)
    }

     
    fun explorerStateFor(runtime: WorkspaceRuntime): ExplorerWorkspaceState {
        val key = runtime.project.id
        retainedExplorerStates[key]?.let { return it }
        val created = ExplorerWorkspaceState()
        retainedExplorerStates[key] = created
        

        while (retainedExplorerStates.size > 12) {
            retainedExplorerStates.remove(retainedExplorerStates.keys.first())
        }
        return created
    }

    internal fun explorerRefreshFor(runtime: WorkspaceRuntime): ExplorerRefreshController {
        check(coordinator.runtime.value === runtime) { "Explorer runtime has been replaced" }
        val state = explorerStateFor(runtime)
        if (explorerRuntime !== runtime || explorerStateBinding !== state) {
            closeExplorerRefresh()
            explorerRuntime = runtime
            explorerStateBinding = state
            activeExplorerRefresh = ExplorerRefreshController(
                state, runtimeScope, explorerReadLane,
                load = { path, cursor, options, budget, traversal ->
                    runtime.files.listFilesPageBounded(path, cursor, 250, options, budget, traversal)
                },
                watchFactory = AndroidExplorerDirectoryWatch(runtime.files.root),
            )
            val controller = checkNotNull(activeExplorerRefresh)
            explorerChangeJob = runtimeScope.launch {
                runtime.documentAuthority.persistedRevision.collect {
                    if (explorerRuntime === runtime && activeExplorerRefresh === controller) controller.requestWorkspaceChange()
                }
            }
        }
        return checkNotNull(activeExplorerRefresh)
    }

    init {
        application.registerComponentCallbacks(memoryCallbacks)
        application.registerActivityLifecycleCallbacks(browserVisibility)
        browserTimers.setApplicationVisible(browserVisibility.isVisible)
        runtimeScope.launch(Dispatchers.IO) { runSuspendCatching { ModelsDevCatalog.initialize(application.applicationContext) } }
        runtimeScope.launch(Dispatchers.IO) { runSuspendCatching { com.baystudio.droide.core.ChatMediaCleanup.resume(application.applicationContext) } }
        runtimeScope.launch {
            coordinator.runtime.collect { runtime ->
                runtime?.agentBrowser?.setApplicationVisible(browserVisibility.isVisible)
                // Fence before replacement; the shared lane remains held until old IO really drains.
                if (explorerRuntime != null && explorerRuntime !== runtime) {
                    closeExplorerRefresh()
                    activeExplorerRefresh = null
                    explorerRuntime = null
                    explorerStateBinding = null
                }
                // Project replacement is a deliberate runtime transition. Closing a panel is not.
                if (retainedAgentRuntime != null && retainedAgentRuntime !== runtime) {
                    retainedAgentUiState?.close()
                    retainedAgentUiState = null
                    retainedAgentRuntime = null
                }
            }
        }
        initializeWorkspace()
    }

    fun initializeWorkspace(mode: WorkspaceStartupMode = WorkspaceStartupMode.NORMAL) {
        // A retry must never clear errors, change modes or replace a live dirty workspace.
        if (!ApplicationWorkAdmission.isOpen || initJob?.isActive == true || coordinator.runtime.value != null) return
        if (mode == WorkspaceStartupMode.TEMPORARY_BUILTINS &&
            (_initError.value == null || coordinator.startup.value.phase != WorkspaceStartupPhase.FAILED)) return
        initJob = runtimeScope.launch {
            _initError.value = null
            runSuspendCatching { coordinator.init(mode) }
                .onFailure { _initError.value = it.message ?: "Workspace initialization failed" }
        }
    }

    fun consumeRuntimeFailure(event: WorkspaceFailureEvent) {
        _runtimeFailure.consumeWorkspaceFailure(event)
    }

    fun persistEditorRecovery(request: EditorRecoveryRequest) {
        if (!ApplicationWorkAdmission.isOpen) return
        runtimeScope.launch {
            runSuspendCatching {
                request.store.save(checkNotNull(request.snapshot) { "A bound recovery checkpoint requires its captured conflict revision" })
            }
        }
    }

     
    fun persistTerminalRecovery() {
        runtimeScope.launch {
            runSuspendCatching { coordinator.runtime.value?.terminals?.flushReviveState() }
        }
    }

    internal val exitDraftUi get() = retainedAgentUiState

    internal fun exitSteps(
        exitScope: CoroutineScope,
        releaseUi: () -> Unit,
        closeUi: () -> Unit = {},
        beforeCloseUi: List<ApplicationExitSequence.Step> = emptyList(),
    ): List<ApplicationExitSequence.Step> {
        var owned: WorkspaceRuntime? = null
        var stops: ExitOwnerBarrier? = null
        var history: ExitOwnerBarrier? = null
        fun nativeDrafts() = owned?.agent?.let { agent ->
            agent.snapshotUncheckpointedInputsForExit() + agent.pendingInputs.value
        }.orEmpty()
        fun ownedStops(): ExitOwnerBarrier {
            stops?.let { return it }
            val rt = owned
            return ExitOwnerBarrier(exitScope, listOf(
                ApplicationExitSequence.Step("CLI tool server") { com.baystudio.droide.core.DroideCliServer.stopAndAwaitForExit() },
                ApplicationExitSequence.Step("Hosted processes") { ExitHostedProcessRegistry.stopAndAwaitAll() },
                ApplicationExitSequence.Step("Environment setup") { environmentSetup.closeAndJoin() },
                ApplicationExitSequence.Step("Device connection") { coordinator.deviceBridge.stopAndAwaitForExit() },
            ) + if (rt == null) emptyList() else listOf(
                ApplicationExitSequence.Step("Imports") { rt.imports.closeAndJoin() },
                ApplicationExitSequence.Step("Build and tasks") { rt.execution.stopAndJoinForExit() },
                ApplicationExitSequence.Step("Extensions") { rt.extensions.closeAndJoinForExit() },
                ApplicationExitSequence.Step("Browser") { rt.agentBrowser.stopForExit() },
                ApplicationExitSequence.Step("Virtual machine") { rt.qemuVm.shutdownForExit() },
                ApplicationExitSequence.Step("Debugger") { rt.debugger.shutdown() },
                ApplicationExitSequence.Step("Language servers") { rt.lsp.shutdown() },
                ApplicationExitSequence.Step("Local model server") { coordinator.stopLocalModelForExit() },
                // History finalizers can await these processes. Start their exact stop
                // requests with the other owners, before joining either history finalizer.
                ApplicationExitSequence.Step("Terminals") { rt.terminals.stopAndAwaitForExit() },
            )).also { stops = it }
        }
        fun historyStops(): ExitOwnerBarrier = history ?: ExitOwnerBarrier(exitScope, listOf(
            ApplicationExitSequence.Step("Agent history") { owned?.agent?.stopAndCheckpointForExit() },
            ApplicationExitSequence.Step("CLI history") {
                retainedAgentUiState?.externalController?.stopAndCheckpointForExit(owned?.agentPlugins)
            },
        )).also { history = it }
        return listOf(
            ApplicationExitSequence.Step("Freezing workspace admission") {
                initJob?.cancelAndJoin()
                owned = coordinator.freezeTransitionsForExit()
                closeExplorerRefresh()
            },
            ApplicationExitSequence.Step("Preserving unsaved editor drafts") {
                if (editorRuntime != null) {
                    check(editorRuntime === owned) { "Editor owner changed before Exit" }
                    check(exitNativeRetirementFailure == null) { "Editor capture during Activity retirement was not confirmed; Exit remains paused with recovery data retained" }
                    if (exitEditorCheckpoint == null) exitEditorCheckpoint = com.baystudio.droide.ui.prepareEditorExitCheckpoint(
                        retainedEditorState, checkNotNull(retainedEditorRecoveryStore))
                    exitEditorCheckpoint?.saveAndVerify()
                }
            },
            ApplicationExitSequence.Step("Preserving unsent chat drafts") {
                retainedAgentUiState?.preserveDraftsForExit(nativeDrafts())
                // Drafts are safe. Fan out independent cancellation now instead of letting
                // one slow owner leave agents/builds running behind a closing Activity.
                ownedStops().requestStop()
                historyStops().requestStop()
            },
            ApplicationExitSequence.Step("Finishing workspace UI actions") { uiWorkOwner.stopAndAwait() },
            ApplicationExitSequence.Step("Releasing editor and browser surfaces") {
                check(exitNativeRetirementFailure == null) { "Editor capture during Activity retirement was not confirmed; Exit remains paused" }
                exitEditorCheckpoint?.saveAndVerify()
                exitEditorCheckpoint?.commitForRelease()
                owned?.agentBrowser?.requestStopForExit()
                releaseUi()
                // A stopped Activity cannot provide the Compose frame that releases this sealed view.
                (exitEditorCheckpoint?.nativeOwnerToRelease as? com.baystudio.droide.ui.DroideCodeEditor)?.release()
                exitEditorCheckpoint?.awaitReleaseAndCheckpoint()
            },
            ApplicationExitSequence.Step("Stopping Agent and saving final history") {
                historyStops().stopAndAwait()
                retainedAgentUiState?.preserveDraftsForExit(nativeDrafts())
            },
            ApplicationExitSequence.Step("Stopping owned development work") {
                ownedStops().stopAndAwait()
            },
            ApplicationExitSequence.Step("Retiring protocol and terminal owners") {
                val rt = owned
                retainedAgentUiState?.closeForExit()
                if (rt != null) {
                    // History has been acknowledged before these methods revoke publication.
                    rt.retireApprovalAuthority().invoke()
                    rt.agent.shutdown(); rt.agentPlugins.close(); rt.extensionPlatform.close(); rt.capabilities.close()
                }
                runtimeJob.cancelAndJoin()
                com.baystudio.droide.core.McpManager.awaitIoIdleForExit()
            },
            ApplicationExitSequence.Step("Confirming document preview shutdown") {
                com.baystudio.droide.core.LocalPdfPreview.confirmStoppedForExit()
            },
        ) + beforeCloseUi + listOf(
            ApplicationExitSequence.Step("Confirming browser surfaces shutdown") {
                owned?.agentBrowser?.releaseNativeSurfacesForExit()
                owned?.agentBrowser?.awaitNativeSurfacesReleasedForExit()
            },
            ApplicationExitSequence.Step("Releasing workspace resources") {
                editorAuthorityBinding?.close(); editorAuthorityBinding = null
                application.unregisterComponentCallbacks(memoryCallbacks)
                application.unregisterActivityLifecycleCallbacks(browserVisibility)
                browserTimers.close()
                coordinator.finishExitOwnership(owned)
            },
            // Close only after the original surfaces and all workspace owners retired.
            // No pending frame or Activity destruction is needed to unblock cleanup.
            ApplicationExitSequence.Step("Closing workspace") { closeUi() },
        )
    }

    override fun close() {
        com.baystudio.droide.core.OwnedResourceCleanup.run(
            { application.unregisterComponentCallbacks(memoryCallbacks) },
            { application.unregisterActivityLifecycleCallbacks(browserVisibility) },
            { browserTimers.close() },
            { retainedAgentUiState?.close() },
            { closeExplorerRefresh() },
            { editorAuthorityBinding?.close() },
            { editorAuthorityBinding = null },
            { coordinator.close(preserveLocalTerminals = false) },
            { runtimeScope.cancel() },
        ).onFailure { android.util.Log.e("DroideWorkspace", "Application workspace cleanup failed", it) }
    }
}
