package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-memory choice only. A fresh application process always starts in NORMAL. */
enum class WorkspaceStartupMode { NORMAL, TEMPORARY_BUILTINS }

data class WorkspaceRuntime(
    val project: DroideProject,
    val files: FileRepository,
    val imports: WorkspaceImportController,
    val documentAuthority: WorkspaceDocumentAuthorityBridge,
    val terminals: TerminalManager,
    val git: GitManager,
    val lsp: LspManager,
    val debugger: DebugManager,
    val agent: AgentService,
    val agentBrowser: AgentBrowserController,
    val agentPlugins: WorkspaceAgentPluginHost,
    val androidDevelopment: AndroidDevelopmentManager,
    val extensions: DevelopmentExtensionsManager,
    val capabilities: UniversalCapabilityRegistry,
    val managedPackageCandidates: ManagedPackageCandidateManager,
    val managedLanguageServers: ManagedLanguageServerProvisioner?,
    val managedLanguageServerCertification: ManagedLanguageServerCertificationRunner,
    val managedLanguageServerCertificationManager: ManagedLanguageServerCertificationManager,
    val managedDebugAdapters: ManagedDebugAdapterProvisioner,
    val debugCertification: DebugCertificationManager,
    val physicalDeviceCertification: PhysicalDeviceCertificationManager,
    val universalToolDiscoveryCertification: UniversalToolDiscoveryCertificationManager,
    val androidInspection: AndroidInspectionManager,
    val androidStability: AndroidStabilityRunner,
    val pluginRuntime: ManagedPluginRuntime,
    val extensionPlatform: ManagedExtensionPlatform,
    val execution: BuildRunDebugCoordinator,
    val qemuVm: QemuVmManager,
    val safMirror: SafMirror? = null,
    val startupMode: WorkspaceStartupMode = WorkspaceStartupMode.NORMAL,
    internal val exportOwnerSafe: () -> Unit = {},
    internal val providerConfiguration: ProviderRegistry.ConfiguredSnapshot =
        ProviderRegistry.prepareConfigured(emptyList()),
) {
    val exports: ExportSourceAdmission = ExportSourceAdmission(
        project.id, files.root, documentAuthority,
        requireOwnerSafe = {
            check(startupMode == WorkspaceStartupMode.NORMAL) { "Export is unavailable in temporary recovery mode" }
            exportOwnerSafe()
        },
        requireSourceSafe = { selected -> safMirror?.requireExportReadable(selected.relativePath) },
        sourceTreeUri = project.treeUri,
        knownWriterBlockers = { buildList {
            if (execution.state.value.busy) add("Build/Run/Debug task is active")
            if (agent.busy.value) add("Agent task is active")
        } },
    )
    private val diagnosticBinding = OwnedWorkspaceDiagnosticsRegistry.bind(extensions.workspaceId, agent.ownedProcessDiagnostics(), files.root)
    private val externalIdeBinding = if (startupMode == WorkspaceStartupMode.NORMAL)
        ExternalIdeToolRegistry.bind(extensions.workspaceId, files.root, agent.externalIdeTools()) else null

    suspend fun reconcileSaf(): String? {
        val mirror = safMirror ?: return null
        val dirty = documentAuthority.dirtySnapshots()
        check(dirty.isEmpty()) {
            "Save or discard unsaved editor changes before refreshing the external project mirror"
        }
        val message = try { mirror.refreshFromTree() }
        catch (failure: Throwable) {
            // A failed refresh may already have published some files. Retire the old index even when editor
            // reconciliation also fails or is cancelled.
            try { withContext(NonCancellable + Dispatchers.IO) { documentAuthority.reconcileAfterPersistedMutation() } }
            catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
        val reconciled = withContext(NonCancellable + Dispatchers.IO) { documentAuthority.reconcileAfterPersistedMutation() }
        check(reconciled.conflicts.isEmpty()) { "Linked folder synchronized, but editor conflicts need review: ${reconciled.conflicts.take(8).joinToString()}" }
        return message
    }

    /** No cancellation, continuation delivery, IO, or resource disposal in this boundary. */
    internal fun retireApprovalAuthority(): () -> Unit {
        val releaseExternalIde = ExternalIdeToolRegistry.retire(externalIdeBinding)
        exports.retire()
        agent.retireAuthority()
        val releaseAnalysis = lsp.retireProjectAnalysisAuthority()
        val releaseApprovals = agent.approvals.retire()
        return { try { releaseExternalIde() } finally { try { releaseAnalysis() } finally { releaseApprovals() } } }
    }

    internal suspend fun releaseExitMetadata() {
        OwnedWorkspaceDiagnosticsRegistry.unbind(diagnosticBinding)
        if (startupMode == WorkspaceStartupMode.NORMAL) withContext(Dispatchers.IO) {
            AgentPluginRuntime.cleanupWorkspace(File(project.rootPath))
        }
    }

    suspend fun shutdownGracefully() = withContext(NonCancellable) {
        OwnedResourceCleanup.runSuspend(
            { exports.retireAndDrain() },
            { retireApprovalAuthority().invoke() },
            { OwnedWorkspaceDiagnosticsRegistry.unbind(diagnosticBinding) },
            { imports.closeAndJoin() },
            { execution.projectTasks.closeAndJoin() },
            { extensions.close() }, { agent.shutdown() }, { agentPlugins.closeAndAwait() },
            { extensionPlatform.close() }, { qemuVm.shutdown() },
            { debugger.shutdown() }, { lsp.shutdown() }, { capabilities.close() },
            { withContext(Dispatchers.Main.immediate) { terminals.dispose(preserveLocalSessions = false).getOrThrow() } },
            { if (startupMode == WorkspaceStartupMode.NORMAL) AgentPluginRuntime.cleanupWorkspace(File(project.rootPath)) },
        ).onFailure { Log.e("DroideWorkspace", "Workspace cleanup failed", it) }
    }

    fun close(preserveLocalTerminals: Boolean = false) {
        OwnedResourceCleanup.run(
            { retireApprovalAuthority().invoke() },
            { OwnedWorkspaceDiagnosticsRegistry.unbind(diagnosticBinding) },
            { imports.close() },
            { execution.projectTasks.close() },
            { extensions.close() }, { agent.shutdown() }, { agentPlugins.close() },
            { extensionPlatform.close() }, { qemuVm.closeNow() },
            { debugger.close() }, { lsp.close() }, { capabilities.close() },
            { terminals.dispose(preserveLocalSessions = preserveLocalTerminals).getOrThrow() },
            { if (startupMode == WorkspaceStartupMode.NORMAL) AgentPluginRuntime.cleanupWorkspace(File(project.rootPath)) },
        ).onFailure { Log.e("DroideWorkspace", "Workspace cleanup failed", it) }
    }

}

// One authoritative, transactional owner for active project file/Git/terminal/agent services.
class WorkspaceCoordinator internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    val projects: ProjectManager = ProjectManager.forApplication(context.filesDir),
    private val approvals: ApprovalManager = ApprovalManager(),
    private val browserApplicationTimers: BrowserApplicationTimers? = null,
    val deviceBridge: DeviceBridgeManager = DeviceBridgeManager(context.applicationContext, scope),
    private val processReconciliation: suspend () -> Int = LocalExecutionSubstrate::reconcileStaleLocalProcesses,
    private val initialUbuntuPreparation: (TerminalManager) -> Unit = TerminalManager::prepareInitialUbuntuTerminal,
    private val startupMeasurementObserver: (WorkspaceStartupMeasurement) -> Unit = {},
) {
    private val permissionPolicies = PermissionPolicyStore(context.filesDir)
    // A failed first candidate must not donate its terminally retired manager to a retry.
    private var unassignedApprovals: ApprovalManager? = approvals
    private val _runtime = MutableStateFlow<WorkspaceRuntime?>(null)
    private val _startup = MutableStateFlow(WorkspaceStartupStatus())
    private val transitionMutex = Mutex()
    private val editorHandoff = WorkspaceHandoffGate()
    private var startupAttempt = 0L
    private var startupMode = WorkspaceStartupMode.NORMAL

    val githubAccount = GitHubAccountManager(context.applicationContext)

    init {
        projects.bindGitHubCredentialResolver(githubAccount::currentGitTransportCredential)
    }

    val providerConnections = ProviderConnectionBackend(context.applicationContext)
    val localLlamaServer = LocalLlamaServerManager(
        context = context.applicationContext,
        bridge = deviceBridge,
        providerBackend = providerConnections,
    )
    val localLlamaAgentModels = LocalLlamaAgentModelController(
        context = context.applicationContext,
        bridge = deviceBridge,
        providerBackend = providerConnections,
        server = localLlamaServer,
    )
    val runtime: StateFlow<WorkspaceRuntime?> = _runtime
    val startup: StateFlow<WorkspaceStartupStatus> = _startup

    internal suspend fun inspectCatalogRecovery(): CanonicalFileOperationJournal.RecoveryMetadata = readInRecoverySession {
        projects.inspectCatalogRecovery()
    }

    internal suspend fun inspectCatalogOperationRecovery(operationId: String): CanonicalFileOperationJournal.OperationRecoveryReview =
        readInRecoverySession {
            projects.inspectCatalogOperationRecovery(operationId)
        }

    internal suspend fun readCatalogRetainedContent(
        operationId: String,
        role: CanonicalFileOperationJournal.CustodyRole,
        expected: CanonicalFileSnapshot.Version,
    ): CanonicalFileOperationJournal.RetainedContent = readInRecoverySession {
        projects.readCatalogRetainedContent(operationId, role, expected)
    }

    private suspend fun <T> readInRecoverySession(read: suspend () -> T): T {
        val epoch = ApplicationWorkAdmission.requireOpen()
        fun requireSession() {
            check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "Application session ended during recovery reading" }
        }
        return transitionMutex.withLock {
            requireSession()
            val result = read()
            requireSession()
            result
        }
    }

    internal suspend fun copyRetainedContent(
        expected: WorkspaceRuntime,
        path: String,
        retained: CanonicalFileOperationJournal.RetainedContent,
        journal: CanonicalFileOperationJournal,
    ): FileRepository.RetainedCopyReceipt {
        val epoch = ApplicationWorkAdmission.requireOpen()
        return transitionMutex.withLock {
            fun requireOwner() {
                check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "Application session ended during recovery copy" }
                check(_runtime.value === expected && projects.activeId.value == expected.project.id) {
                    "Recovery copy belongs to a different or closed workspace"
                }
            }
            requireOwner()
            val receipt = expected.files.createRetainedCopy(path, retained, journal, ::requireOwner)
            try {
                requireOwner()
            } catch (failure: IllegalStateException) {
                throw RetainedCopyIncompleteException(receipt.relativePath, receipt.operationId, true, failure)
            }
            receipt
        }
    }

    suspend fun reconcileActiveSaf(): String? = transitionMutex.withLock {
        ApplicationWorkAdmission.requireOpen()
        _runtime.value?.reconcileSaf()
    }

    suspend fun init(requestedMode: WorkspaceStartupMode = WorkspaceStartupMode.NORMAL) = transitionMutex.withLock {
        ApplicationWorkAdmission.requireOpen()
        // Activity recreation/retry must not construct a second live runtime over the same workspace.
        if (_runtime.value != null) return@withLock
        check(requestedMode == WorkspaceStartupMode.NORMAL || _startup.value.phase == WorkspaceStartupPhase.FAILED) {
            "Temporary built-in recovery is available only after workspace startup fails"
        }
        startupMode = requestedMode
        val builtinsOnly = startupMode == WorkspaceStartupMode.TEMPORARY_BUILTINS
        val attempt = ++startupAttempt
        val unpublishedRuntime = WorkspaceStartupCandidate<WorkspaceRuntime> { it.shutdownGracefully() }

        suspend fun <T> stage(
            phase: WorkspaceStartupPhase,
            timeoutMs: Long,
            projectId: String? = null,
            block: suspend WorkspaceStartupTrace.() -> T,
        ): T {
            _startup.value = WorkspaceStartupStatus(attempt, phase, projectId)
            val trace = WorkspaceStartupTrace(attempt, phase, record = { measurement ->
                Log.i("DroideStartup", "attempt=${measurement.attempt} phase=${measurement.phase.name.lowercase()} " +
                    "step=${measurement.step?.name?.lowercase() ?: "phase"} " +
                    "outcome=${measurement.outcome.name.lowercase()} elapsed_ms=${measurement.elapsedMs}")
                startupMeasurementObserver(measurement)
            })
            return trace.phase { WorkspaceStartupPolicy.bounded(phase, timeoutMs, projectId) { trace.block() } }
        }

        try {
            withContext(Dispatchers.IO) {
                val inspectionContext = kotlinx.coroutines.currentCoroutineContext()
                LocalExecutionSubstrate.init(context.applicationContext) { inspectionContext.ensureActive() }
            }
            if (builtinsOnly) {
                // Clear only tracked in-memory contributions, even after partial registration.
                DeclarativeExtensionRuntime.suspendInstalledProjections()
            } else stage(WorkspaceStartupPhase.RUNTIME_REGISTRIES, WorkspaceStartupPolicy.RUNTIME_REGISTRIES_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    step(WorkspaceStartupStep.PROCESS_RECONCILIATION) { processReconciliation() }
                    step(WorkspaceStartupStep.DECLARATIVE_REGISTRY) {
                        val verificationContext = kotlinx.coroutines.currentCoroutineContext()
                        DeclarativeExtensionRuntime.initialize(context.applicationContext) { verificationContext.ensureActive() }
                    }
                    step(WorkspaceStartupStep.AGENT_REGISTRY) { AgentPluginRuntime.initialize(context.applicationContext) }
                }
            }
            stage(WorkspaceStartupPhase.PROJECT_CATALOG, WorkspaceStartupPolicy.PROJECT_CATALOG_TIMEOUT_MS) {
                step(WorkspaceStartupStep.PROJECT_STORE) { projects.init(readOnlyExisting = builtinsOnly) }
            }
            val active = projects.active() ?: error("No active project")
            var recoveredFrom: Throwable? = null
            val prepared = try {
                stage(
                    WorkspaceStartupPhase.ACTIVE_RUNTIME,
                    WorkspaceStartupPolicy.runtimeTimeoutMs(active),
                    active.id,
                ) { buildRuntime(active, refreshSaf = false, startupTrace = this).also(unpublishedRuntime::retain) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (firstError: Throwable) {

                unpublishedRuntime.discardUnlessPublished()
                if (builtinsOnly || active.id == "default") throw firstError
                recoveredFrom = firstError
                val fallback = projects.projects.value.firstOrNull { it.id == "default" }
                    ?: throw IllegalStateException("Default recovery project is unavailable", firstError)
                try {
                    stage(
                        WorkspaceStartupPhase.FALLBACK_RUNTIME,
                        WorkspaceStartupPolicy.FALLBACK_RUNTIME_TIMEOUT_MS,
                        fallback.id,
                    ) {
                        buildRuntime(fallback, refreshSaf = false, startupTrace = this).also(unpublishedRuntime::retain)
                    }
                } catch (fallbackError: Throwable) {
                    fallbackError.addSuppressed(firstError)
                    throw fallbackError
                }
            }
            val readyStatus = WorkspaceStartupStatus(
                attempt = attempt,
                phase = WorkspaceStartupPhase.READY,
                projectId = prepared.project.id,
                detail = recoveredFrom?.let { "Recovered from ${it::class.java.simpleName}: ${it.message.orEmpty().take(240)}" },
            )
            stage(
                WorkspaceStartupPhase.PUBLISH_RUNTIME,
                WorkspaceStartupPolicy.PUBLISH_RUNTIME_TIMEOUT_MS,
                prepared.project.id,
            ) { step(WorkspaceStartupStep.PUBLISH) {
                commitPrepared(null, prepared, unpublishedRuntime, onCommitted = { _startup.value = readyStatus })
            } }
            if (!builtinsOnly) scope.launch(Dispatchers.IO) {
                runSuspendCatching { projects.collectStartupGarbage() }
                    .onSuccess { failed -> if (failed.isNotEmpty()) Log.w("DroideWorkspace", "Deferred cleanup retained ${failed.size} source trees") }
                    .onFailure { Log.w("DroideWorkspace", "Deferred cleanup interrupted; source is preserved", it) }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // A stage deadline can discard the return after accepted publication. READY belongs to that live
            // runtime; never present it as a failed retry candidate.
            if (_runtime.value == null) {
                _startup.value = WorkspaceStartupStatus(
                    attempt = attempt,
                    phase = WorkspaceStartupPhase.FAILED,
                    projectId = _startup.value.projectId,
                    detail = failure.message?.take(500) ?: "Workspace initialization failed",
                )
            }
            throw failure
        } finally {
            // The stage deadline can discard a returned runtime at its own suspension boundary. Its
            // ownership remains here until publication.
            unpublishedRuntime.discardUnlessPublished(_runtime.value)
        }
    }

    suspend fun switchProject(id: String) = transitionMutex.withLock {
        ApplicationWorkAdmission.requireOpen()
        projects.requireSelectionCertain()
        val project = projects.projects.value.firstOrNull { it.id == id } ?: error("Project not found: $id")
        require(!project.workspaceRemoved) { "Restore the stored project before opening it" }
        val current = _runtime.value
        // Selecting the owner that is already active is not a lifecycle boundary.

        if (current?.project?.id == id) return@withLock
        awaitEditorHandoff(current)

        current?.reconcileSaf()
        val prepared = buildRuntime(project, refreshSaf = true)
        commitPrepared(current, prepared)
    }

    fun switchProjectAsync(id: String) { scope.launch { switchProject(id) } }

    suspend fun addSafProject(
        uri: Uri,
        name: String = "SAF project",
        onProgress: ((SafMirrorProgress) -> Unit)? = null,
    ): DroideProject = transitionMutex.withLock {
        ApplicationWorkAdmission.requireOpen()
        projects.requireSelectionCertain()
        val current = _runtime.value
        val existing = SafProjectIdentity.existing(projects.projects.value, uri, current?.project?.id)
        // Reopening the current folder is a selection no-op. Do not rebuild its runtime, flush its dirty
        // editor or repeat provider/setup work.
        if (existing != null && current?.project?.id == existing.id) return@withLock existing
        awaitEditorHandoff(current)
        current?.reconcileSaf()
        val project = if (existing?.workspaceRemoved == true) projects.restoreWorkspace(existing.id)
            else existing ?: projects.add(name = name, treeUri = uri.toString())

        val prepared = buildRuntime(project, refreshSaf = true, safProgress = onProgress)
        commitPrepared(current, prepared)
        project
    }

    suspend fun createProjectFromTemplateAndSwitch(name: String, templateId: String): Pair<DroideProject, String?> =
        transitionMutex.withLock {
        ApplicationWorkAdmission.requireOpen()
            projects.requireSelectionCertain()
            val current = _runtime.value
            awaitEditorHandoff(current)
            current?.reconcileSaf()
            val created = projects.createFromTemplate(name, templateId)
            val project = created.first
            val prepared = buildRuntime(project, refreshSaf = true)
            commitPrepared(current, prepared)
            created
        }

    private suspend fun buildRuntime(
        project: DroideProject,
        refreshSaf: Boolean,
        safProgress: ((SafMirrorProgress) -> Unit)? = null,
        startupTrace: WorkspaceStartupTrace? = null,
    ): WorkspaceRuntime = WorkspaceStartupResources.prepare(Dispatchers.IO) {
        val runtimeApprovals = unassignedApprovals?.also { unassignedApprovals = null } ?: ApprovalManager()
        own { runtimeApprovals.retire().invoke() }
        suspend fun <T> step(label: WorkspaceStartupStep, block: suspend () -> T): T {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return if (startupTrace != null) startupTrace.step(label, block) else block()
        }
        val builtinsOnly = startupMode == WorkspaceStartupMode.TEMPORARY_BUILTINS
        val root = File(project.rootPath)
        val (mirror, providerConfiguration) = step(WorkspaceStartupStep.WORKSPACE_FILES) {
            // Preparation consumes registered storage; a missing root requires recovery.
            val projectsRoot = ProjectWorkspacePolicy.projectsRoot(context.filesDir)
            ProjectWorkspacePolicy.requireDistinctRoots(projectsRoot, projects.projects.value)
            ProjectWorkspacePolicy.requireDirectChild(projectsRoot, root)
            check(root.isDirectory) {
                "Existing workspace is unavailable: ${root.path}. Restore the original folder before reopening; recovery data was preserved."
            }
            val mirror = project.treeUri?.let { SafMirror(context, it, root) }
            if (refreshSaf && mirror != null) mirror.refreshFromTree(onProgress = safProgress)
            if (!builtinsOnly) seedDefaultFiles(project, root)
            mirror to ProviderEndpointCatalog.prepare(root)
        }
        val mutationLsp = java.util.concurrent.atomic.AtomicReference<LspManager?>(null)
        val documentAuthority = WorkspaceDocumentAuthorityBridge { mutationLsp.get()?.workspaceFilesChanged() }
        val files = FileRepository(
            root,
            createRoot = false,
            beforeMutation = documentAuthority::validatePersistedMutation,
            onMutation = documentAuthority::persistedMutation,
            newFilePublisher = NewProjectFilePublisher.platform,
            participant = mirror?.repositoryParticipant(),
        )
        files.configureCopyRecoveryHome(File(context.filesDir, "copy-recovery-state"))
        val imports = WorkspaceImportController(context.applicationContext, files, scope, documentAuthority = documentAuthority)
        own { imports.closeAndJoin() }
        val capabilityRegistry = step(WorkspaceStartupStep.CAPABILITIES) { UniversalCapabilityRegistry(project.id, root) }
        own { capabilityRegistry.close() }
        val androidDevelopment = AndroidDevelopmentManager(context.applicationContext, root, deviceBridge)
        val extensions = step(WorkspaceStartupStep.EXTENSIONS) {
            DevelopmentExtensionsManager(context.applicationContext, root, androidDevelopment, deviceBridge,
                capabilityRegistry, installedComponentsEnabled = !builtinsOnly)
        }
        own { extensions.close() }
        val terminals = step(WorkspaceStartupStep.TERMINALS) {
            TerminalManager(
                scope = scope,
                defaultDir = root,
                context = context.applicationContext,
                deviceBridge = deviceBridge,
                androidDevelopment = androidDevelopment,
                projectKey = project.id,
            )
        }
        own { withContext(Dispatchers.Main.immediate) { terminals.dispose(preserveLocalSessions = false).getOrThrow() } }
        val syncLinkedFolder: (suspend () -> String?)? = if (mirror == null) null else ({
            check(documentAuthority.dirtySnapshots().isEmpty()) { "Save or discard unsaved editor changes before linked folder sync" }
            mirror.refreshFromTree()
        })
        val git = GitManager(
            root,
            documentAuthority,
            githubCredentialResolver = githubAccount::currentGitTransportCredential,
            syncLinkedFolder = syncLinkedFolder,
        )
        val automatedExecutionTerminal = ExecutionTerminalRouter(terminals, appContext = context.applicationContext, workspaceRoot = root)
        val remoteProcessHost: StdioProcessHost = LocalLinuxProcessHost(root, scope)
        val managedLanguageServers = if (builtinsOnly) null else ManagedLanguageServerProvisioner(
            context = context.applicationContext,
            scope = scope,
            workDir = root,
            bridge = deviceBridge,
            development = androidDevelopment,
            registry = extensions.registry,
        )
        val baseLsp: LspManager = step(WorkspaceStartupStep.LANGUAGE_SERVICES) {
            if (builtinsOnly) TemporaryRecoveryLspManager() else ProfessionalLspManager(
                scope = scope,
                files = files,

                fallback = CliLspManager(automatedExecutionTerminal, files),
                remoteProcessHost = remoteProcessHost,
                extraLanguageServers = capabilityRegistry::languageServers,
                approveProjectAnalysis = { request, isStillCurrent ->
                    runtimeApprovals.requestApproval(
                        kind = if (request.purpose == LspProjectAnalysisPurpose.ELP_METADATA_INSPECTION) "elp_metadata_inspection" else "lsp_project_analysis", summary = request.summary, detail = request.detail,
                        action = if (request.purpose == LspProjectAnalysisPurpose.ELP_METADATA_INSPECTION) "inspect_elp_metadata_once" else "enable_project_analysis", resource = request.projectPath,
                        permissionScope = "", approvalSessionId = "", sessionGrantAction = "", sessionGrantResource = "",
                        isStillCurrent = isStillCurrent,
                    ) == ApprovalDecision.Approved
                },
            )
        }
        own { baseLsp.shutdown() }
        val lsp: LspManager = if (builtinsOnly) baseLsp else ProvisioningLspManager(
            delegate = baseLsp,
            ensureLanguageServer = { serverId -> checkNotNull(managedLanguageServers).ensureAvailable(serverId) },
        )
        mutationLsp.set(lsp)
        val debugger = step(WorkspaceStartupStep.DEBUGGER) {
            DebugManager(
                scope = scope,
                files = files,
                terminalManager = terminals,
                stateStore = DebugStateStore(context.applicationContext, project.id),
                remoteProcessHost = remoteProcessHost,
                deviceBridge = deviceBridge,
                extraDebugAdapters = capabilityRegistry::debugAdapters,
            )
        }
        own { debugger.shutdown() }
        step(WorkspaceStartupStep.DEBUGGER_STATE) { debugger.restoreState() }
        val managedPackageCandidates = step(WorkspaceStartupStep.CERTIFICATION_SERVICES) {
            ManagedPackageCandidateManager(
                context = context.applicationContext,
                installer = ManagedPackageInstaller(
                    context = context.applicationContext,
                    bridge = deviceBridge,
                    registry = extensions.registry,
                ),
            )
        }
        val managedLanguageServerCertification = ManagedLanguageServerCertificationRunner(
            scope = scope,
            workDir = root,
            bridge = deviceBridge,
            development = androidDevelopment,
            registry = extensions.registry,
        )
        val managedLanguageServerCertificationManager = ManagedLanguageServerCertificationManager(
            context = context.applicationContext,
            projectId = project.id,
            runner = managedLanguageServerCertification,
        )
        val managedDebugAdapters = ManagedDebugAdapterProvisioner(
            context = context.applicationContext,
            bridge = deviceBridge,
            development = androidDevelopment,
            registry = extensions.registry,
        )
        val debugCertification = DebugCertificationManager(
            context = context.applicationContext,
            projectId = project.id,
            androidDevelopment = androidDevelopment,
            debugger = debugger,
            bridge = deviceBridge,
        )
        val physicalDeviceCertification = PhysicalDeviceCertificationManager(
            context = context.applicationContext,
            projectId = project.id,
            projectRoot = root,
            development = androidDevelopment,
            debugger = debugger,
            bridge = deviceBridge,
        )
        val androidInspection = AndroidInspectionManager(deviceBridge)
        val androidStability = AndroidStabilityRunner(androidInspection, AndroidDeviceIdentityCollector(deviceBridge))
        val pluginRuntime = ManagedPluginRuntime(
            scope = scope,
            workDir = root,
            files = files,
            processHost = remoteProcessHost,
        )
        var platformOwnsPluginRuntime = false
        own { if (!platformOwnsPluginRuntime) pluginRuntime.close() }
        val extensionPlatform = step(WorkspaceStartupStep.EXTENSION_PLATFORM) {
            ManagedExtensionPlatform(
                contributions = ExtensionContributionRegistry(
                    ExtensionLifecycleStore(context.filesDir, project.id),
                ),
                pluginRuntime = pluginRuntime,
                packages = extensions.registry,
                capabilities = capabilityRegistry,
            )
        }
        platformOwnsPluginRuntime = true
        own { extensionPlatform.close() }
        if (!builtinsOnly) {
            DeclarativeExtensionRuntime.manifests().forEach(extensionPlatform::registerManifest)
            extensions.marketplace.bindExtensionPlatform(extensionPlatform)
        }
        val execution = step(WorkspaceStartupStep.EXECUTION_SERVICES) {
            BuildRunDebugCoordinator(context.applicationContext, root, terminals, androidDevelopment, debugger, capabilityRegistry, scope)
        }
        own { execution.projectTasks.closeAndJoin() }
        val qemuVm = QemuVmManager(context.applicationContext, project.id)
        own { qemuVm.shutdown() }
        val universalToolDiscoveryCertification = UniversalToolDiscoveryCertificationManager(
            context = context.applicationContext,
            projectId = project.id,
            development = androidDevelopment,
            bridge = deviceBridge,
            capabilities = capabilityRegistry,
            execution = execution,
        )
        val agentCapabilities = AgentCapabilityActions(capabilityRegistry, execution)
        val agentTerminal = automatedExecutionTerminal
        val permissionEngine = PermissionEngine(policy = permissionPolicies.load(project.id))
        val agentBrowser = AgentBrowserController(root, browserApplicationTimers)
        val agentToolchains = AgentToolchainActions.forWorkspace(extensions, terminals)
        val agent = step(WorkspaceStartupStep.AGENT) {
            AgentService(
                files, agentTerminal, git, runtimeApprovals, lsp = lsp, perms = permissionEngine,
                agentPluginSource = if (builtinsOnly) AgentPluginSource.EMPTY else AgentPluginRuntime, documentAuthority = documentAuthority,
                ideActions = AgentIdeActions(execution, debugger),
                toolchainActions = agentToolchains,
                capabilityActions = agentCapabilities,
                browserController = agentBrowser,
                mcpProcessHost = remoteProcessHost,
                appContext = context.applicationContext,
                runtimeScope = scope,
                providerConfiguration = providerConfiguration,
            )
        }
        own { agent.shutdown() }
        val agentPlugins = step(WorkspaceStartupStep.AGENT_PLUGINS) {
            WorkspaceAgentPluginHost(
                projectRoot = root,
                cacheDir = context.cacheDir,
                files = files,

                processHost = LocalAgentWorkspaceProcessHost.forWorkspace(root, context.cacheDir, scope,
                    persistentWorkspaceStore = File(context.filesDir, "agent-workspaces")),
                androidDevelopment = androidDevelopment,
                bridge = deviceBridge,
                approvals = runtimeApprovals,
                permissions = permissionEngine,
                parentScope = scope,
                onFirstMediaImported = { ChatMediaCleanup.schedule(context.applicationContext, root); Unit },
            ).also { it.bindRecoveryDocuments(documentAuthority) }
        }
        own { agentPlugins.close() }
        val runtime = step(WorkspaceStartupStep.RUNTIME_BINDINGS) {
            WorkspaceRuntime(
                project = project,
                files = files,
                imports = imports,
                documentAuthority = documentAuthority,
                terminals = terminals,
                git = git,
                lsp = lsp,
                debugger = debugger,
                agent = agent,
                agentBrowser = agentBrowser,
                agentPlugins = agentPlugins,
                androidDevelopment = androidDevelopment,
                extensions = extensions,
                capabilities = capabilityRegistry,
                managedPackageCandidates = managedPackageCandidates,
                managedLanguageServers = managedLanguageServers,
                managedLanguageServerCertification = managedLanguageServerCertification,
                managedLanguageServerCertificationManager = managedLanguageServerCertificationManager,
                managedDebugAdapters = managedDebugAdapters,
                debugCertification = debugCertification,
                physicalDeviceCertification = physicalDeviceCertification,
                universalToolDiscoveryCertification = universalToolDiscoveryCertification,
                androidInspection = androidInspection,
                androidStability = androidStability,
                pluginRuntime = pluginRuntime,
                extensionPlatform = extensionPlatform,
                execution = execution,
                qemuVm = qemuVm,
                safMirror = mirror,
                startupMode = startupMode,
                exportOwnerSafe = {
                    projects.requireSelectionCertain()
                    check(_runtime.value?.project?.id == project.id) { "Export runtime is no longer active" }
                    ProjectWorkspacePolicy.requireDistinctRoots(ProjectWorkspacePolicy.projectsRoot(context.filesDir), projects.projects.value)
                    check(ProjectWorkspacePolicy.requireDirectChild(ProjectWorkspacePolicy.projectsRoot(context.filesDir), File(project.rootPath)) == files.root.canonicalFile)
                },
                providerConfiguration = providerConfiguration,
            )
        }
        consolidate { runtime.shutdownGracefully() }
        if (!builtinsOnly) initialUbuntuPreparation(runtime.terminals)
        runtime
    }

    // Observe on the existing owners; no transition lock, save, handoff or cancellation is
    // initiated.
    internal suspend fun observeCatalogEditor(): CatalogEditorReview {
        val captured = withContext(Dispatchers.Main.immediate) {
            val epoch = ApplicationWorkAdmission.requireOpen()
            val runtime = _runtime.value ?: throw WorkspaceRecoveryEditorUnavailableException("No active workspace editor owner")
            check(projects.activeId.value == runtime.project.id) { "Active project ownership is changing" }
            Triple(runtime, epoch, editorHandoff.observe())
        }
        val runtime = captured.first
        val root = withContext(Dispatchers.IO) {
            check(runtime.files.root.canonicalPath == File(runtime.project.rootPath).canonicalPath)
            WorkspaceDirectoryIdentity(runtime.files.root)
        }
        val owner = CatalogEditorOwnerValidator(checkOwner = {
            withContext(Dispatchers.Main.immediate) {
                check(ApplicationWorkAdmission.isCurrentOpen(captured.second)) { "Application work epoch changed during editor review" }
                check(_runtime.value === runtime && projects.activeId.value == runtime.project.id) { "Workspace owner changed during editor review" }
                editorHandoff.requireUnchanged(captured.third)
            }
        }, checkRoot = { withContext(Dispatchers.IO) { root.requireCurrent() } })
        suspend fun validateOwner() = owner.validate()
        validateOwner()
        val editor = runtime.documentAuthority.observeAllDocuments()
        validateOwner()
        val catalogRoot = withContext(Dispatchers.IO) { File(context.filesDir, ".droide").canonicalPath }
        val processes = withContext(Dispatchers.IO) { CatalogProcessObservation.capture(runtime.files.root) }
        val terminals = CatalogTerminalObservation.capture(runtime.terminals, PersistentLocalTerminalHost.existing())
        val children = runtime.agent.observeChildOwnership()
        return CatalogEditorReview(catalogRoot, editor, captured.third.retained, owner::validateIdentity, processes, terminals, children, ::validateOwner).also { it.validate() }
    }

    internal suspend fun reviewCatalogRelease(update: ProjectCatalogUpdate,
                                             selectedAt: CatalogReleasePreflight.Snapshot,
                                             selectedIds: List<String>): CatalogReleasePreflight.Result =
        observeCatalogEditor().review(update, selectedAt, selectedIds)

    private suspend fun awaitEditorHandoff(expected: WorkspaceRuntime?) {
        projects.requireCatalogReadyForTransition()
        withContext(Dispatchers.Main.immediate) {
            check(_runtime.value === expected) { "Workspace ownership changed before preserving late editor changes" }
            editorHandoff.retryPending(expected)
            check(_runtime.value === expected) { "Workspace ownership changed while preserving late editor changes" }
        }
    }

    private suspend fun commitPrepared(
        previous: WorkspaceRuntime?,
        next: WorkspaceRuntime,
        retained: WorkspaceStartupCandidate<WorkspaceRuntime>? = null,
        onCommitted: () -> Unit = {},
    ) {
        val owner = retained ?: WorkspaceStartupCandidate<WorkspaceRuntime> { it.shutdownGracefully() }
            .also { it.retain(next) }
        var committed = false
        var selectionChanged = false
        var retiredApprovalDelivery: (() -> Unit)? = null
        try {
            projects.requireCatalogReadyForTransition()
            // This drains local capture only, never picker/provider work. Capture does not acquire
            // transitionMutex; no root/transition lock inversion is introduced.
            previous?.exports?.pauseAndDrain()
            withContext(Dispatchers.Main.immediate) {
                projects.requireSelectionCertain()
                editorHandoff.requireClear()
                val admission = previous?.documentAuthority?.prepareProjectHandoff(editorHandoff)
                    ?: WorkspaceHandoffAdmission.Empty
                val validate: () -> Unit = {
                    try {
                        ApplicationWorkAdmission.requireOpen()
                        check(_runtime.value === previous) { "Workspace ownership changed during project switch" }
                        editorHandoff.requireClear()
                        admission()
                    } catch (failure: Throwable) {
                        admission.abort()
                        throw failure
                    }
                }
                val publish: () -> Unit = {

                    admission.commit()
                    retiredApprovalDelivery = previous?.retireApprovalAuthority()
                    ProviderRegistry.publishConfigured(next.providerConfiguration)
                    editorHandoff.publishOwner(next)
                    _runtime.value = next
                    committed = true
                    onCommitted()
                }
                if (projects.activeId.value == next.project.id) {
                    validate()
                    publish()
                } else {
                    selectionChanged = true
                    projects.selectAndPublish(next.project.id, validate, publish)?.let { warning ->
                        Log.w("DroideWorkspace", "Selected project committed after a storage return failure", warning)
                    }
                }
            }
        } finally {
            if (!committed) previous?.exports?.resume()
            // A reserved denial must settle even if a later publication callback fails. Clear before
            // delivery; resumed caller code runs outside the publication closure.
            val deliver = retiredApprovalDelivery
            retiredApprovalDelivery = null
            if (deliver != null) withContext(NonCancellable + Dispatchers.Main.immediate) {
                runCatching { deliver() }
                    .onFailure { Log.w("DroideWorkspace", "Retired approval delivery failed", it) }
            }
            // Record ownership before leaving Main: prompt cancellation must never discard B merely because
            // the caller did not receive the committed return value.
            owner.discardUnlessPublished(if (committed) next else _runtime.value)
            if (committed) withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (_runtime.value === next) {
                    runCatching { next.extensions.activateCli() }
                        .onFailure { Log.w("DroideWorkspace", "Workspace committed; CLI activation unavailable", it) }
                }
                if (previous != null) {
                    runSuspendCatching { previous.shutdownGracefully() }
                        .onFailure { Log.w("DroideWorkspace", "Previous workspace cleanup failed after committed swap", it) }
                }
            }
        }

        if (selectionChanged) projects.recordSelectedProject(next.project.id)
    }

    private fun seedDefaultFiles(project: DroideProject, root: File) {
        if (project.id != "default") return
        PathSecurity.resolveWithin(root, "README.md").apply { if (!exists()) writeText("# Welcome to Droide\n") }
    }

    // Install a data-only VSIX into the integrity-checked app-private extension store.
    suspend fun installDeclarativeVsix(uri: Uri): InstalledDeclarativeExtensionRecord = transitionMutex.withLock {
        ApplicationWorkAdmission.requireOpen()
        requireInstalledComponents()
        withContext(Dispatchers.IO) { DeclarativeExtensionRuntime.installFromUri(uri) }.also { record ->
            _runtime.value?.extensionPlatform?.registerManifest(record.manifest)
        }
    }

    suspend fun uninstallDeclarativeExtension(extensionId: String) = transitionMutex.withLock {
        ApplicationWorkAdmission.requireOpen()
        requireInstalledComponents()
        _runtime.value?.extensionPlatform?.unregister(extensionId)
        withContext(Dispatchers.IO) { DeclarativeExtensionRuntime.uninstall(extensionId) }
    }

    // Called only while transitionMutex owns the runtime binding. Admission must precede app-store
    // mutation, not fail later during source projection cleanup.
    private suspend fun <T> withPluginWorkspaceWriter(action: suspend () -> T): T {
        val root = _runtime.value?.project?.rootPath?.let(::File) ?: return action()
        return ExportWriterAdmission.withSuspendingWriter(root, "Agent plugin configuration") { action() }
    }

    suspend fun installAgentPlugin(uri: Uri): InstalledAgentPluginRecord = transitionMutex.withLock { withPluginWorkspaceWriter {
        ApplicationWorkAdmission.requireOpen()
        requireInstalledComponents()
        val record = withContext(Dispatchers.IO) { AgentPluginRuntime.installFromUri(uri) }
        _runtime.value?.let { AgentPluginRuntime.cleanupWorkspace(File(it.project.rootPath)) }
        record
    } }

    suspend fun uninstallAgentPlugin(id: String) = transitionMutex.withLock { withPluginWorkspaceWriter {
        ApplicationWorkAdmission.requireOpen()
        requireInstalledComponents()
        withContext(Dispatchers.IO) { AgentPluginRuntime.uninstall(id) }
        _runtime.value?.let { AgentPluginRuntime.cleanupWorkspace(File(it.project.rootPath)) }
    } }

    suspend fun setAgentPluginEnabled(id: String, enabled: Boolean) = transitionMutex.withLock { withPluginWorkspaceWriter {
        ApplicationWorkAdmission.requireOpen()
        requireInstalledComponents()
        withContext(Dispatchers.IO) { AgentPluginRuntime.setEnabled(id, enabled) }
        _runtime.value?.let { AgentPluginRuntime.cleanupWorkspace(File(it.project.rootPath)) }
    } }

    fun installedAgentPlugins(): List<InstalledAgentPluginRecord> {
        requireInstalledComponents()
        return AgentPluginRuntime.records()
    }

    private fun requireInstalledComponents() {
        check(startupMode == WorkspaceStartupMode.NORMAL) { TEMPORARY_COMPONENTS_UNAVAILABLE }
    }

    // Persist and activate a trusted, app-private policy for the current project.
    suspend fun replaceActivePermissionPolicy(policy: PermissionPolicyDocument) = transitionMutex.withLock {
        ApplicationWorkAdmission.requireOpen()
        val active = _runtime.value ?: error("No active workspace")
        val validated = policy.validated()
        permissionPolicies.save(active.project.id, validated)
        active.agent.perms.replacePolicy(validated)
    }

    suspend fun updateActivePermissionSetting(expectedProjectId: String, action: String, effect: PermEffect): PermissionPolicyDocument =
        updatePermissionSetting(transitionMutex, expectedProjectId, action, effect,
            active = { _runtime.value?.let { it.project.id to it.agent.perms } },
            persist = { projectId, policy -> permissionPolicies.save(projectId, policy) })

    fun activePermissionPolicy(): PermissionPolicyDocument =
        _runtime.value?.agent?.perms?.policySnapshot() ?: PermissionPolicyDocument.EMPTY

    fun approvals(): ApprovalManager = _runtime.value?.agent?.approvals ?: approvals
    internal suspend fun freezeTransitionsForExit(): WorkspaceRuntime? = transitionMutex.withLock {
        check(!ApplicationWorkAdmission.isOpen) { "Exit admission is still open" }
        _runtime.value
    }

    internal suspend fun stopLocalModelForExit() = localLlamaServer.stopForExit()

    internal suspend fun finishExitOwnership(expected: WorkspaceRuntime?) {
        check(_runtime.value === expected) { "Exit runtime ownership changed" }
        expected?.releaseExitMetadata()
        check(_runtime.value === expected) { "Exit runtime changed during resource cleanup" }
        editorHandoff.publishOwner(null)
        _runtime.value = null
    }

    fun close(preserveLocalTerminals: Boolean = false) {
        val previous = _runtime.value
        val releaseApprovals = previous?.retireApprovalAuthority()
        try {
            editorHandoff.publishOwner(null)
            _runtime.value = null
        } finally {
            OwnedResourceCleanup.run(
                { releaseApprovals?.invoke() },
                { previous?.close(preserveLocalTerminals = preserveLocalTerminals) },
                { deviceBridge.close() },
            ).onFailure { Log.e("DroideWorkspace", "Coordinator cleanup failed", it) }
        }
    }

}
