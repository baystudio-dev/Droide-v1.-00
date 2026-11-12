package com.baystudio.droide.ui
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import java.io.File
import java.net.URI

/** Recovery must not auto-probe installed Agent CLIs when the pane first composes. */
internal fun workspaceAgentPluginsForStartup(
    installedComponentsEnabled: Boolean,
    host: WorkspaceAgentPluginHost,
): WorkspaceAgentPluginHost? = host.takeIf { installedComponentsEnabled }

/** The visible sidebar owns the browser; the main region keeps the project editor in that case. */
@Composable
internal fun FocusedWorkbenchBrowser(
    sidebarOwnsBrowser: Boolean,
    browser: @Composable () -> Unit,
    editor: @Composable () -> Unit,
) {
    if (sidebarOwnsBrowser) editor() else browser()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class) @Composable
fun IdeScreen(
    files: FileRepository,
    imports: WorkspaceImportController,
    exports: ExportSourceAdmission,
    exportWorkspaceName: String,
    documents: WorkspaceDocumentAuthority,
    terminalManager: TerminalManager,
    git: GitManager,
    lsp: LspManager,
    debugger: DebugManager,
    agent: AgentService,
    agentUiState: AgentWorkspaceUiState,
    agentBrowser: AgentBrowserController,
    agentPlugins: WorkspaceAgentPluginHost,
    githubAccount: GitHubAccountManager, providerConnections: ProviderConnectionBackend,
    localLlamaModels: LocalLlamaAgentModelController,
    androidDevelopment: AndroidDevelopmentManager,
    extensions: DevelopmentExtensionsManager,
    execution: BuildRunDebugCoordinator,
    deviceBridge: DeviceBridgeManager,
    approvals: ApprovalManager,
    projectManager: ProjectManager,
    recoveryBridge: EditorRecoveryBridge,
    recoveryStore: EditorStateStore,
    editorState: EditorWorkspaceState,
    explorerState: ExplorerWorkspaceState,
    explorerRefresh: ExplorerRefreshController,
    startupRecovery: StartupRecoveryPolicy = StartupRecoveryPolicy.NORMAL,
    onPortraitEditorStatusBarHiddenChanged: (Boolean) -> Unit,
    onPickSaf: () -> Unit, safLinked: Boolean, onReconcileSaf: suspend () -> String?,
    onProjectSwitch: (String) -> Unit, onProjectCreate: suspend (String, String) -> Pair<DroideProject, String?>,
    onPermissionRuleChange: suspend (String, PermEffect) -> PermissionPolicyDocument,
    onNotificationSettings: () -> Unit = {},
) {
    val workspaceKey = files.root.absolutePath
    val uiStarted = rememberUiStarted()
    val uiResumed = rememberUiResumed()
    val initialFile = remember(workspaceKey) {
        if (!startupRecovery.deferEditorReopen && runCatching { files.resolveChecked("README.md").isFile }.getOrDefault(false)) "README.md" else ""
    }
    var workspaceTab by rememberSaveable(workspaceKey) { mutableIntStateOf(editorState.navigation?.tab ?: if (initialFile.isNotBlank()) 1 else 0) }
    var openFiles by rememberSaveable(workspaceKey) { mutableStateOf(editorState.navigation?.openFiles ?: if (initialFile.isBlank()) emptyList() else listOf(initialFile)) }
    var activeFile by rememberSaveable(workspaceKey) { mutableStateOf(editorState.navigation?.activeFile ?: initialFile) }
    SideEffect { editorState.retainNavigation(openFiles, activeFile, workspaceTab) }
    val activeProject by projectManager.activeId.collectAsState(); val projects by projectManager.projects.collectAsState()
    val projectName = projects.firstOrNull { it.id == activeProject }?.name ?: "Droide"
    var drawer by rememberSaveable { mutableStateOf(false) }
    var showPalette by rememberSaveable { mutableStateOf(false) }
    var showQuickOpen by rememberSaveable { mutableStateOf(false) }
    var showWorkspaceSymbols by rememberSaveable { mutableStateOf(false) }
    var showGoToLine by rememberSaveable(workspaceKey) { mutableStateOf(false) }
    var goToLineInput by rememberSaveable(workspaceKey) { mutableStateOf("") }
    var showSessions by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }; var settingsInitialCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var showAndroidDevelopment by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showConnect by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showBilling by rememberSaveable { mutableStateOf(false) }
    var showProblems by rememberSaveable { mutableStateOf(false) }; var professionalSheet by remember { mutableStateOf<ProfessionalSheet?>(null) }
    var isChatVisible by rememberSaveable { mutableStateOf(false) }; var agentComposerFocused by remember { mutableStateOf(false) }
    var approvalAttention by remember(workspaceKey) { mutableStateOf(WorkspaceApprovalSurface.OTHER) }
    var approvalReviewRequest by remember(workspaceKey) { mutableStateOf<Pair<Long, Long>?>(null) }
    var approvalReviewSequence by remember(workspaceKey) { mutableLongStateOf(0L) }
    val pendingWorkspaceApproval by approvals.pending.collectAsState()
    var browserUrl by rememberSaveable(workspaceKey) { mutableStateOf("https://github.com") }
    var browserExpanded by rememberSaveable(workspaceKey) { mutableStateOf(false) }
    val browserPlacement = remember(agentBrowser, workspaceKey) { WorkspaceBrowserPlacement() }
    val browserRetention by agentBrowser.retention.collectAsState()
    var runnerMsg by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var runnerPath by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var androidBuildDiagnostics by remember(workspaceKey) { mutableStateOf<List<BuildDiagnostic>>(emptyList()) }
    var lastAndroidApk by remember(workspaceKey) { mutableStateOf<File?>(null) }
    var androidOutputTitle by remember(workspaceKey) { mutableStateOf("Android Output") }
    var androidOutputText by remember(workspaceKey) { mutableStateOf("") }
    var showAndroidOutput by remember(workspaceKey) { mutableStateOf(false) }
    val ctx = LocalContext.current
    val workbenchRead = rememberPreferenceReadRecovery(ctx, WorkbenchPreferences.Snapshot()) { WorkbenchPreferences.flow(ctx) }
    val workbenchPrefs = workbenchRead.state.value
    val providerRead = rememberPreferenceReadRecovery(ctx, AgentPreferences.Snapshot("pollinations", "", "", null)) { AgentPreferences.observe(ctx) }
    val wordwrap = workbenchPrefs.wordWrap
    val hardwareShortcuts = workbenchPrefs.hardwareShortcuts
    var currentProvider by rememberSaveable { mutableStateOf("pollinations") }
    var sidebarToolName by rememberSaveable { mutableStateOf(DroideRailTool.Files.name) }
    var sidebarVisible by rememberSaveable { mutableStateOf(true) }
    var agentExtensionFamilyId by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var bottomToolName by rememberSaveable { mutableStateOf(DroideBottomTool.Terminal.name) }
    var bottomPanelVisible by rememberSaveable { mutableStateOf(true) }
    var bottomPanelHeightDp by rememberSaveable { mutableFloatStateOf(210f) }
    var agentPanelWidthDp by rememberSaveable { mutableFloatStateOf(336f) }
    var expandedWorkbench by remember { mutableStateOf(false) }
    var sidePanelsCanShare by remember(workspaceKey) { mutableStateOf(true) }
    fun openSidebarTool(tool: DroideRailTool, selectForApproval: Boolean = true) {
        if (selectForApproval) approvalAttention = if (tool == DroideRailTool.Web) WorkspaceApprovalSurface.BROWSER else WorkspaceApprovalSurface.OTHER
        if (!sidePanelsCanShare) {
            isChatVisible = false

            if (!expandedWorkbench) {
                showSearch = tool == DroideRailTool.Search
                workspaceTab = when (tool) {
                    DroideRailTool.Files, DroideRailTool.Search -> 0
                    DroideRailTool.Git -> 3
                    DroideRailTool.Web -> 4
                    DroideRailTool.Run -> 5
                    DroideRailTool.Extensions -> 6
                }
            }
        }
        sidebarToolName = tool.name
        sidebarVisible = true
    }
    fun openAgentPane() {
        approvalAttention = WorkspaceApprovalSurface.AGENT
        if (!sidePanelsCanShare) sidebarVisible = false
        isChatVisible = true
    }
    val editorBridge = remember(workspaceKey) { ActiveEditorBridge() }; val accessoryInputFocus = remember(workspaceKey) { AccessoryInputFocusController() }; var editorAccessoryKeysExpanded by rememberSaveable(workspaceKey) { mutableStateOf(true) }; var terminalAccessoryKeysExpanded by rememberSaveable(workspaceKey) { mutableStateOf(true) }
    val saves = remember(editorState, files, editorBridge, lsp) { EditorSaveCoordinator(editorState, files, editorBridge, lsp) }
    val exportUi = rememberExplorerExportUi(exports, exportWorkspaceName, safLinked, saves::saveScope, editorBridge::captureForSave)
    DisposableEffect(editorState, editorBridge) {
        val binding = editorState.bindProjectHandoffCapture(editorBridge::captureForSave,
            { editorBridge.nativeHandoff }, { editorBridge.recoveryLifecycleGeneration })
        onDispose { binding.close() }
    }
    val markdownModes = remember(workspaceKey) { mutableStateMapOf<String, MarkdownPreviewMode>() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val browserImeVisible = WindowInsets.isImeVisible
    fun changeBrowserExpanded(value: Boolean) {
        if (browserExpanded == value) return
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        approvalAttention = WorkspaceApprovalSurface.BROWSER
        browserExpanded = value
    }
    var pendingCloseFile by rememberSaveable(workspaceKey) { mutableStateOf<String?>(null) }
    var pendingProjectSwitch by rememberSaveable(workspaceKey) { mutableStateOf<String?>(null) }
    var pendingSafPick by rememberSaveable(workspaceKey) { mutableStateOf(false) }; var consumedPathMutationSequence by remember(workspaceKey) { mutableLongStateOf(0L) }
    var navigationTarget by remember(workspaceKey) { mutableStateOf<Triple<String, Int, Int>?>(null) }
    var navigationToken by remember(workspaceKey) { mutableIntStateOf(0) }
    var navigationBack by remember(workspaceKey) { mutableStateOf<List<Triple<String, Int, Int>>>(emptyList()) }
    var navigationForward by remember(workspaceKey) { mutableStateOf<List<Triple<String, Int, Int>>>(emptyList()) }
    val scope = rememberWorkspaceCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showEditorRecoveryCopies by remember(files, editorState) { mutableStateOf(false) }
    var showRetainedWork by remember(files) { mutableStateOf(false) }
    RetainedWorkHost(files, snackbarHostState, uiStarted, showRetainedWork) { showRetainedWork = it }
    fun showActionFailure(prefix: String, error: Throwable) {
        scope.launchUiCatching { snackbarHostState.showSnackbar("$prefix: ${error.message ?: error::class.java.simpleName}") }
    }
    fun launchUserAction(block: suspend () -> Unit) = scope.launchUiCatching(
        onError = { snackbarHostState.showSnackbar("Action failed: ${it.message ?: it::class.java.simpleName}") },
        block = block,
    )
    val explorerLease = remember(explorerRefresh) { explorerRefresh.newUiLease() }
    DisposableEffect(explorerRefresh, explorerLease) { onDispose { explorerRefresh.detach(explorerLease) } }
    val visibleFiles = remember(files, documents, editorState, explorerState, explorerRefresh, explorerLease) {
        WorkspaceVisibilityReconciler(
            scope, documents, captureEditor = { editorBridge.capture() },
            refreshExplorer = { explorerRefresh.requestAppearance(explorerLease) },
            onFailure = { path, failure ->
                if (failure is WorkspaceDocumentConflictException) {
                    editorState.peek(path)?.status = "Changed on disk · unsaved edits kept. Review or reload before saving."
                } else scope.launchUiCatching {
                    snackbarHostState.showSnackbar("Could not refresh file: ${failure.message ?: "unavailable"}")
                }
            },
            watchFactory = AndroidWorkspaceDocumentWatch(files.root),
        )
    }
    DisposableEffect(visibleFiles) { onDispose { visibleFiles.close() } }
    val terminalReadiness = remember(workspaceKey, terminalManager, lsp) {
        TerminalReadinessInvalidation(scope, terminalManager.snapshot)
    }
    DisposableEffect(terminalReadiness) { onDispose { terminalReadiness.close() } }
    val terminalEnvironmentRevision by terminalReadiness.revision.collectAsState()
    LaunchedEffect(lsp, terminalReadiness, terminalEnvironmentRevision) {
        if (terminalEnvironmentRevision > 0L) lsp.environmentInvalidated()
    }
    val terminalSnapshot by terminalManager.snapshot.collectAsState()
    val activeSession = terminalSnapshot.activeTab?.session
    val mode by agent.mode.collectAsState(); val mcpHealth by agent.mcpHealth.collectAsState()
    val browserDialog by agentBrowser.pendingDialog.collectAsState()
    val tokenWarning by agent.tokens.warning.collectAsState()
    val diagnostics by lsp.diagnostics.collectAsState()
    val deviceBridgeState by deviceBridge.state.collectAsState()
    val androidDevState by androidDevelopment.status.collectAsState()
    val androidOperation by androidDevelopment.operation.collectAsState()
    val executionState by execution.state.collectAsState()
    val userTaskState by execution.projectTasks.state.collectAsState()
    fun cancelUserOperation(): Boolean = if (userTaskState.busy) execution.projectTasks.stop() else execution.cancelActive()
    val workspaceEnvironment = remember(workspaceKey, activeFile) { WorkspaceEnvironmentDetector.detect(files.root, activeFile) }
    val activeLanguageName = remember(activeFile) { LanguageRegistry.forFile(activeFile)?.name ?: if (activeFile.isBlank()) "No file" else "Plain Text" }
    val themes = remember(workspaceKey) { ThemeManager(files.root) }
    var themeTick by remember(workspaceKey) { mutableIntStateOf(0) }; val formatters = remember(workspaceKey, terminalManager) { FormatterManager(files.root, terminalManager) }; val commands = remember(workspaceKey) { CommandManager(files.root) }
    val taskManager = remember(workspaceKey, activeFile) {
        val selectedPath = activeFile
        TaskManager(files.root, terminalManager, activeFile = { selectedPath })
    }; val worktreeManager = remember(workspaceKey) { WorktreeManager(files.root) }
    val archivedRecoveryIssues by recoveryStore.archivedEvidenceIssues.collectAsState()
    var recoveryReady by remember(recoveryStore) { mutableStateOf(editorState.recoveryInitialized) }
    val themeSnapshot = remember(workspaceKey, themeTick) { themes.snapshot() }
    SideEffect {
        DroideColors.install(themeSnapshot.ui)
        TerminalThemeAuthority.installDefaults(themeSnapshot.terminal)
    }
    val scheme = themeSnapshot.colorScheme
    val toolHealthRead = rememberPreferenceReadRecovery(Pair(workspaceKey, uiStarted), Unit) {
        kotlinx.coroutines.flow.flow {
            if (uiStarted) while (true) {
                withContext(Dispatchers.IO) { agent.syncMcpHealth() }
                emit(Unit)
                kotlinx.coroutines.delay(15_000)
            }
        }
    }
    val hasTransientUi = drawer || showEditorRecoveryCopies || showPalette || showQuickOpen || showWorkspaceSymbols || showGoToLine || showSessions || showSettings || showAndroidDevelopment || workspaceSearchConsumesBack(browserExpanded, showSearch) || showConnect || showModels || showBilling || showProblems || professionalSheet != null
    val portraitEditorOwnsStatusBar = !browserExpanded && workspaceTab == 1 && !isChatVisible && !hasTransientUi
    SideEffect { onPortraitEditorStatusBarHiddenChanged(portraitEditorOwnsStatusBar) }
    DisposableEffect(Unit) { onDispose { onPortraitEditorStatusBarHiddenChanged(false) } }
    fun captureActiveForLsp() {
        editorBridge.capture()
        val path = activeFile
        val doc = editorState.peek(path)
        if (path.isNotBlank() && doc?.loaded == true && doc.fullIntelligence) {
            launchUserAction { runSuspendCatching { lsp.didChange(path, doc.content) } }
        }
    }
    fun open(path: String): Boolean = runUiCatching(onError = { showActionFailure("Cannot open file", it) }) {
        captureActiveForLsp()
        val nextTabs = EditorTabRetention.open(openFiles, path, activeFile, allowExistingDirtyOverflow = true) { editorState.peek(it)?.dirty == true }
        if (nextTabs == null) {
            launchUserAction { snackbarHostState.showSnackbar("All editor tabs contain unsaved changes. Save or close a tab before opening another file.") }
            return@runUiCatching false
        }
        EditorTabRetention.closeEvicted(openFiles, nextTabs, editorState) { evicted -> launchUserAction { runSuspendCatching { lsp.didClose(evicted) } } }
        openFiles = nextTabs
        activeFile = path
        workspaceTab = 1
        true
    } ?: false
    // Release clean inactive buffers before opening another large file. Tab identity, cursor and
    // review state remain; reopening reads the current authoritative disk bytes.
    SideEffect { editorState.protectActiveEditor(activeFile) }
    LaunchedEffect(editorState, activeFile) {
        editorState.protectActiveEditor(activeFile)
        editorState.trimInactiveCleanDocuments().forEach { path -> runSuspendCatching { lsp.didClose(path) } }
    }
    fun currentEditorLocation(): Triple<String, Int, Int>? {
        if (activeFile.isBlank()) return null
        val (line, column) = editorBridge.currentCursorLocation() ?: (1 to 1)
        return Triple(activeFile, line.coerceAtLeast(1), column.coerceAtLeast(1))
    }
    fun openLocation(path: String, line: Int, column: Int, recordHistory: Boolean = true): Boolean {
        val target = Triple(path, line.coerceAtLeast(1), column.coerceAtLeast(1))
        val origin = if (recordHistory) currentEditorLocation()?.takeIf { it != target } else null
        if (!open(path)) return false
        origin?.let { previous ->
            navigationBack = (navigationBack + previous).takeLast(100)
            navigationForward = emptyList()
        }
        navigationTarget = target
        navigationToken++
        return true
    }
    fun navigateBack() {
        val target = navigationBack.lastOrNull() ?: return
        val current = currentEditorLocation()
        if (!openLocation(target.first, target.second, target.third, recordHistory = false)) return
        navigationBack = navigationBack.dropLast(1)
        if (current != null) navigationForward = (navigationForward + current).takeLast(100)
    }
    fun navigateForward() {
        val target = navigationForward.lastOrNull() ?: return
        val current = currentEditorLocation()
        if (!openLocation(target.first, target.second, target.third, recordHistory = false)) return
        navigationForward = navigationForward.dropLast(1)
        if (current != null) navigationBack = (navigationBack + current).takeLast(100)
    }
    BackHandler(enabled = hasTransientUi || browserExpanded || isChatVisible || navigationBack.isNotEmpty()) {
        when {
            drawer -> drawer = false
            showEditorRecoveryCopies -> showEditorRecoveryCopies = false
            showPalette -> showPalette = false
            showQuickOpen -> showQuickOpen = false
            showWorkspaceSymbols -> showWorkspaceSymbols = false
            showGoToLine -> showGoToLine = false
            showSessions -> showSessions = false
            showSettings -> showSettings = false
            showAndroidDevelopment -> showAndroidDevelopment = false
            workspaceSearchConsumesBack(browserExpanded, showSearch) -> showSearch = false
            showConnect -> showConnect = false
            showModels -> showModels = false
            showBilling -> showBilling = false
            showProblems -> showProblems = false
            professionalSheet != null -> professionalSheet = null
            browserExpanded -> when (expandedBrowserBackAction(browserExpanded, browserImeVisible)) {
                ExpandedBrowserBackAction.HIDE_KEYBOARD -> {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                }
                ExpandedBrowserBackAction.EXIT_EXPANDED -> changeBrowserExpanded(false)
                ExpandedBrowserBackAction.NONE -> Unit
            }
            isChatVisible -> isChatVisible = false
            navigationBack.isNotEmpty() -> navigateBack()
        }
    }
    fun problemLocations(): List<Triple<String, Int, Int>> = diagnostics.entries.flatMap { (uri, list) ->
        val rel = runCatching {
            val file = File(URI(uri)).canonicalFile
            if (!PathSecurity.contains(files.root, file)) return@runCatching null
            file.relativeTo(files.root.canonicalFile).invariantSeparatorsPath
        }.getOrNull() ?: return@flatMap emptyList()
        list.filter { it.reportGuard?.accepts() != false }.map { Triple(rel, it.line.coerceAtLeast(1), it.column.coerceAtLeast(1)) }
    }.distinct().sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    fun navigateProblem(forward: Boolean) {
        val items = problemLocations()
        if (items.isEmpty()) {
            launchUserAction { snackbarHostState.showSnackbar("No LSP problems") }
            return
        }
        val current = currentEditorLocation() ?: Triple("", 1, 1)
        val index = if (forward) {
            items.indexOfFirst { it.first > current.first || (it.first == current.first && (it.second > current.second || (it.second == current.second && it.third > current.third))) }
                .let { if (it >= 0) it else 0 }
        } else {
            items.indexOfLast { it.first < current.first || (it.first == current.first && (it.second < current.second || (it.second == current.second && it.third < current.third))) }
                .let { if (it >= 0) it else items.lastIndex }
        }
        val target = items[index]
        openLocation(target.first, target.second, target.third)
    }
    fun closeFileNow(path: String) {
        if (path == activeFile) captureActiveForLsp()
        val index = openFiles.indexOf(path)
        openFiles = openFiles.filterNot { it == path }
        launchUserAction { runSuspendCatching { lsp.didClose(path) } }
        editorState.forgetAtOrUnder(path)
        if (activeFile == path) {
            activeFile = when {
                openFiles.isEmpty() -> ""
                index in openFiles.indices -> openFiles[index]
                else -> openFiles.last()
            }
        }
    }
    fun requestClose(path: String) {
        if (path == activeFile) captureActiveForLsp()
        if (editorState.peek(path)?.let { it.persistenceRequired || it.saveSynchronizationPending } == true) pendingCloseFile = path else closeFileNow(path)
    }
    fun requestProjectSwitch(id: String) {
        if (id == activeProject) return
        captureActiveForLsp()
        if (editorState.allDocuments().any { it.persistenceRequired || it.saveSynchronizationPending }) pendingProjectSwitch = id else onProjectSwitch(id)
    }
    fun onPathRenamed(oldPath: String, newPath: String) {
        val prefix = oldPath.trimEnd('/') + "/"
        fun remap(path: String): String = when {
            path == oldPath -> newPath
            path.startsWith(prefix) -> newPath.trimEnd('/') + "/" + path.removePrefix(prefix)
            else -> path
        }
        openFiles = openFiles.map(::remap).distinct()
        activeFile = remap(activeFile)
        navigationTarget = navigationTarget?.let { Triple(remap(it.first), it.second, it.third) }; navigationBack = navigationBack.map { Triple(remap(it.first), it.second, it.third) }; navigationForward = navigationForward.map { Triple(remap(it.first), it.second, it.third) }
    }
    fun onPathDeleted(path: String) {
        val prefix = path.trimEnd('/') + "/"
        fun removed(candidate: String): Boolean = candidate == path || candidate.startsWith(prefix)
        val removedActive = removed(activeFile)
        openFiles = openFiles.filterNot(::removed)
        navigationTarget = navigationTarget?.takeUnless { removed(it.first) }; navigationBack = navigationBack.filterNot { removed(it.first) }; navigationForward = navigationForward.filterNot { removed(it.first) }
        if (removedActive) activeFile = openFiles.lastOrNull().orEmpty()
    }
    fun applyPathMutation(mutation: EditorWorkspacePathMutation) { if (mutation.to == null) onPathDeleted(mutation.from) else onPathRenamed(mutation.from, mutation.to); consumedPathMutationSequence = mutation.sequence }
    DisposableEffect(editorState, workspaceKey) {
        editorState.pathMutationsAfter(consumedPathMutationSequence).forEach(::applyPathMutation); val binding = editorState.bindPathMutationListener(::applyPathMutation)
        onDispose { binding.close() }
    }
    suspend fun persistEditorState() {
        check(recoveryReady) { "Recovery is not ready; previous encrypted data was preserved" }
        editorBridge.captureForSave()
        val snapshot = editorState.recoverySnapshot(openFiles, activeFile) ?: editorState.emptyRecoverySnapshot()
        recoveryStore.save(snapshot)
    }
    suspend fun saveAllDirty(): Pair<String, Throwable>? {
        // The coordinator rechecks native/editor changes after recovery persistence suspends,
        // immediately before Build/Run/Debug/close or project handoff.
        return saves.saveAllBefore { persistEditorState() }.continuationFailure()
    }
    suspend fun workstationSupported(): Boolean { if (deviceBridgeState.supported) return true; snackbarHostState.showSnackbar("Wireless Debugging requires Android 11 or newer. Local Linux Run and Tasks remain available on supported ARM64 devices."); return false }
    suspend fun runAndroidTask(
        title: String,
        action: suspend () -> AndroidDevelopmentManager.BuildResult,
    ): AndroidDevelopmentManager.BuildResult? {
        val saveFailure = saveAllDirty()
        if (saveFailure != null) {
            snackbarHostState.showSnackbar("Build cancelled: save needs attention for ${saveFailure.first}: ${saveFailure.second.message}")
            return null
        }
        // Do not delay execution for the snackbar's display duration after save admission.
        scope.launch { snackbarHostState.showSnackbar("$title started") }
        val result = runSuspendCatching { action() }.getOrElse { error ->
            androidOutputTitle = title
            androidOutputText = error.stackTraceToString().takeLast(300_000)
            showAndroidOutput = true
            snackbarHostState.showSnackbar("$title failed: ${error.message ?: error::class.java.simpleName}")
            return null
        }
        androidBuildDiagnostics = result.diagnostics
        androidOutputTitle = title
        androidOutputText = result.output
        result.localArtifacts.firstOrNull { it.extension.equals("apk", ignoreCase = true) }?.let { lastAndroidApk = it }
        val seconds = result.durationMs / 1000.0
        if (result.success) {
            val artifactText = if (result.localArtifacts.isEmpty()) "" else " · ${result.localArtifacts.size} artifact(s)"
            snackbarHostState.showSnackbar("$title succeeded in ${"%.1f".format(seconds)}s$artifactText")
        } else {
            showProblems = result.diagnostics.isNotEmpty()
            showAndroidOutput = result.diagnostics.isEmpty()
            snackbarHostState.showSnackbar("$title failed (exit ${result.exitCode}) · ${result.diagnostics.size} problem(s)")
        }
        return result
    }
    suspend fun runCurrentFile(runPath: String, presentAsSheet: Boolean = true) {
        if (runPath.isBlank()) { snackbarHostState.showSnackbar("Open a file first"); return }
        val failure = saveAllDirty()
        if (failure != null) { snackbarHostState.showSnackbar("Cannot run: save needs attention for ${failure.first}"); return }
        if (isMarkdownPreviewPath(runPath)) {
            markdownModes[runPath] = MarkdownPreviewMode.PREVIEW
            workspaceTab = 1
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            return
        }
        if (WorkspaceHtmlPreview.isHtml(runPath)) {
            runSuspendCatching {
                val url = withContext(Dispatchers.IO) { agentBrowser.htmlPreviewUrl(runPath) }
                browserUrl = url
                runnerMsg = null
                agentBrowser.execute("open", url = url)
            }
                .onFailure { snackbarHostState.showSnackbar("Preview failed: ${it.message}") }
            return
        }
        runSuspendCatching { execution.runFile(runPath) }
            .onSuccess { output ->
                runnerMsg = output
                runnerPath = runPath
                androidOutputTitle = "Run: ${runPath.substringAfterLast('/')}"
                androidOutputText = output
                if (presentAsSheet) showAndroidOutput = true else { bottomToolName = DroideBottomTool.Output.name; bottomPanelVisible = true }
            }
            .onFailure { snackbarHostState.showSnackbar("Run failed: ${it.message}") }
    }
    suspend fun debugAndroidApp() { if (!workstationSupported()) return
        val failure = saveAllDirty()
        if (failure != null) {
            snackbarHostState.showSnackbar("Debug cancelled: save needs attention for ${failure.first}: ${failure.second.message}")
            return
        }
        runSuspendCatching { execution.debugAndroid(activeFile) }
            .onSuccess { result ->
                androidBuildDiagnostics = result.build.diagnostics
                androidOutputTitle = "Android: Debug App"
                androidOutputText = result.build.output
                lastAndroidApk = result.apk
                workspaceTab = 5
                snackbarHostState.showSnackbar("Attached ${result.configuration.name} to PID ${result.launch.pid}")
            }
            .onFailure { if (it.message?.contains("DAP adapter", ignoreCase = true) == true) workspaceTab = 5; snackbarHostState.showSnackbar("Android debug failed: ${it.message}") }
    }
    suspend fun installAndRunAndroid() { if (!workstationSupported()) return
        val failure = saveAllDirty()
        if (failure != null) {
            snackbarHostState.showSnackbar("Install/Run cancelled: save needs attention for ${failure.first}: ${failure.second.message}")
            return
        }
        val reusableDebugApk = lastAndroidApk?.takeIf { it.isFile && it.name.contains("debug", ignoreCase = true) }
        runSuspendCatching { execution.installAndRun(reusableDebugApk) }
            .onSuccess { result ->
                result.build?.let { build ->
                    androidBuildDiagnostics = build.diagnostics
                    androidOutputTitle = "Android: Install & Run"
                    androidOutputText = build.output
                }
                result.apk?.let { lastAndroidApk = it }
                when {
                    result.launchMessage != null -> snackbarHostState.showSnackbar(result.launchMessage)
                    result.build?.success == false -> {
                        showProblems = result.build.diagnostics.isNotEmpty()
                        showAndroidOutput = result.build.diagnostics.isEmpty()
                        snackbarHostState.showSnackbar("Install/Run stopped because the debug build failed")
                    }
                    else -> snackbarHostState.showSnackbar("No installable debug APK was produced")
                }
            }
            .onFailure { snackbarHostState.showSnackbar("Install/Run failed: ${it.message}") }
    }
    suspend fun loadAndroidLogcat(presentAsSheet: Boolean = true) {
        runSuspendCatching { androidDevelopment.logs() }
            .onSuccess { logs ->
                androidOutputTitle = "Android: Logcat"
                androidOutputText = logs
                if (presentAsSheet) showAndroidOutput = true else { bottomToolName = DroideBottomTool.Output.name; bottomPanelVisible = true }
            }
            .onFailure { snackbarHostState.showSnackbar("Logcat failed: ${it.message}") }
    }
    suspend fun saveActive(): Result<Unit> {
        val path = activeFile
        if (path.isBlank()) return Result.success(Unit)
        val doc = editorState.peek(path) ?: return Result.success(Unit)
        val failure = saves.saveDocument(doc).continuationFailure()
        return failure?.let { Result.failure(it.second) } ?: Result.success(Unit)
    }
    suspend fun applyWorkspaceEdit(edit: LspWorkspaceEdit): Result<Int> = runSuspendCatching {
        editorBridge.capture()
        val updates = LspWorkspaceEditPreflight.prepare(edit, editorState, files)
        editorState.requireMutableOwner()
        val plannedTabs = EditorTabRetention.planWorkspaceEdit(openFiles, updates.keys, activeFile) { editorState.peek(it)?.dirty == true }
        // Final pure guard after all-edit preflight, before the first mutation. This safe preparation is
        // not rollback; applying this batch may retire its captured owner.
        LspWorkspaceEditPreflight.requireCurrent(edit)
        updates.forEach { (path, updated) ->
            editorState.document(path).applyWorkspaceText(updated, "Language-server rename — review before saving")
        }
        EditorTabRetention.closeEvicted(openFiles, plannedTabs, editorState) { evicted -> launchUserAction { runSuspendCatching { lsp.didClose(evicted) } } }
        openFiles = plannedTabs
        persistEditorState()
        updates.forEach { (path, updated) -> if (editorState.peek(path)?.fullIntelligence == true) runSuspendCatching { lsp.didChange(path, updated) } }
        updates.size
    }
    DisposableEffect(recoveryBridge, recoveryStore, workspaceKey) {
        val owner = Any()
        recoveryBridge.install(owner) {
            check(recoveryReady) { "Recovery is not ready" }
            editorBridge.captureForSave()
            val snapshot = editorState.recoverySnapshot(openFiles, activeFile) ?: editorState.emptyRecoverySnapshot()
            EditorRecoveryRequest(recoveryStore, snapshot)
        }
        onDispose { recoveryBridge.uninstall(owner) }
    }
    LaunchedEffect(recoveryStore, workspaceKey) {
        if (editorState.recoveryInitialized) {
            recoveryReady = true
            return@LaunchedEffect
        }
        editorState.retainNavigation(openFiles, activeFile, workspaceTab)
        val admission = editorState.beginRecovery(EditorWorkspaceState.Navigation(openFiles, activeFile, workspaceTab))
        val outcome = runSuspendCatching {
            recoveryStore.load()?.let { recovered ->
                val visible = EditorTabRetention.restoreDocuments(recovered, files, editorState, admission)
                val next = EditorTabRetention.recoveryNavigation(
                    editorState, admission, EditorWorkspaceState.Navigation(openFiles, activeFile, workspaceTab),
                    recovered, visible, startupRecovery.recoveredEditorPath(recovered.activeFile, visible),
                )
                openFiles = next.openFiles
                activeFile = next.activeFile
                workspaceTab = next.tab
                if (visible.isNotEmpty()) {
                    val count = recovered.dirtyPaths.size
                    if (count > 0) launchUserAction {
                        snackbarHostState.showSnackbar(if (startupRecovery.deferEditorReopen)
                            "Recovered $count unsaved file(s) · select a file to reopen after the previous interruption"
                        else "Recovered $count unsaved file(s)")
                    }
                }
            }
        }
        if (outcome.isSuccess && editorState.isRecoveryCurrent(admission)) {
            editorState.completeRecovery()
            recoveryReady = true
        }
        else launchUserAction { snackbarHostState.showSnackbar("Editor recovery unavailable; saved data was kept: ${outcome.exceptionOrNull()?.message}") }
    }
    LaunchedEffect(recoveryStore, archivedRecoveryIssues) {
        if (archivedRecoveryIssues.isNotEmpty()) snackbarHostState.showSnackbar(
            "${archivedRecoveryIssues.size} archived recovery item(s) need attention. Healthy drafts remain available; see Older editor recovery copies.",
        )
    }
    LaunchedEffect(recoveryStore, workspaceKey, uiStarted) {
        if (!uiStarted) return@LaunchedEffect
        var persistedSignature = ""; var observedSignature = ""; var quietTicks = 0
        while (true) {
            kotlinx.coroutines.delay(2_000)
            if (!recoveryReady) continue
            editorState.trimInactiveCleanDocuments().forEach { path -> runSuspendCatching { lsp.didClose(path) } }
            val dirty = editorState.dirtyDocuments()
            val signature = editorState.recoveryCheckpointSignature(openFiles, activeFile)
            quietTicks = if (signature == observedSignature) quietTicks + 1 else 0; observedSignature = signature
            val hasDirtyLargeFile = dirty.any { it.largeFileOptimized }
            val idleEnoughForLargeSnapshot = !hasDirtyLargeFile || quietTicks >= 2
            if (signature != persistedSignature && idleEnoughForLargeSnapshot) {
                runSuspendCatching { persistEditorState() }.onSuccess { persistedSignature = signature }.onFailure {
                    // A superseded/pending recovery checkpoint retries on the next tick; real IO errors remain
                    // visible.
                    if (it !is com.baystudio.droide.core.EditorRecoveryCheckpointDeferredException)
                        snackbarHostState.showSnackbar("Editor recovery save failed: ${it.message ?: "unknown error"}")
                }
            }
        }
    }
    LaunchedEffect(providerRead.state) {
        if (providerRead.state.ready) currentProvider = providerRead.state.value.providerId
    }
    LaunchedEffect(tokenWarning) {
        tokenWarning?.let { snackbarHostState.showSnackbar(it) }
    }
    fun openBottomTool(tool: DroideBottomTool) {
        approvalAttention = WorkspaceApprovalSurface.OTHER
        bottomToolName = tool.name
        if (expandedWorkbench) bottomPanelVisible = true
        else if (tool == DroideBottomTool.Terminal) workspaceTab = 2
    }
    var linuxSetupJob by remember(terminalManager) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var showUbuntuDownloadConsent by remember { mutableStateOf(false) }
    val ubuntuPreparation by terminalManager.ubuntuPreparation.collectAsState()
    val substrateState by LocalExecutionSubstrate.state.collectAsState()
    fun reportLinuxSetupFailure(failure: Throwable) {
        val message = failure.message ?: "Linux setup failed"
        launchUserAction { snackbarHostState.showSnackbar(message) }
    }
    fun openLinuxTerminal() {
        if (linuxSetupJob != null || ubuntuPreparation.preparing || !terminalManager.canCreateTab) return
        if (FdroidUbuntuConsent.requiresConsentForFreshInstall(ctx)) {
            showUbuntuDownloadConsent = true
            return
        }
        openBottomTool(DroideBottomTool.Terminal)
        terminalManager.dismissUbuntuPreparationError()
        linuxSetupJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try { terminalManager.openLinuxTerminal() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) {
                reportLinuxSetupFailure(failure)
            }
            catch (failure: LinkageError) {
                reportLinuxSetupFailure(failure)
            }
            finally { linuxSetupJob = null }
        }
        linuxSetupJob?.start()
    }
    DisposableEffect(terminalManager) { onDispose { linuxSetupJob?.cancel() } }
    MaterialTheme(colorScheme = scheme) {
        if (showUbuntuDownloadConsent) {
            AlertDialog(
                onDismissRequest = { showUbuntuDownloadConsent = false },
                title = { Text("Install Ubuntu for Droide?") },
                text = {
                    Text(
                        "The F-Droid edition can download Ubuntu Base 24.04.5 ARM64 (~30 MB) " +
                            "from Canonical over HTTPS to enable the Linux terminal and toolchains. " +
                            "This contains executable components downloaded outside F-Droid's " +
                            "build and security checks. Droide verifies the exact archive SHA-256 " +
                            "before installation. This download happens only if you agree. " +
                            "You can decline and continue using the editor."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            FdroidUbuntuConsent.approveCanonicalArchive(ctx)
                            showUbuntuDownloadConsent = false
                            openLinuxTerminal()
                        } catch (failure: Exception) {
                            reportLinuxSetupFailure(failure)
                        }
                    }) { Text("Download and install") }
                },
                dismissButton = {
                    TextButton(onClick = { showUbuntuDownloadConsent = false }) { Text("Not now") }
                },
            )
        }
        val physicalLandscape = droidePhysicalLandscape()
        BoxWithConstraints(
            Modifier.fillMaxSize().landscapeWorkbenchImePadding(physicalLandscape).onGloballyPositioned {
                browserPlacement.updateRoot(Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())))
            }.onPreviewKeyEvent { composeEvent ->
                val event = composeEvent.nativeKeyEvent
                if (browserExpanded || !hardwareShortcuts || event.action != AndroidKeyEvent.ACTION_DOWN || event.repeatCount > 0) return@onPreviewKeyEvent false
                val ctrl = event.isCtrlPressed
                val shift = event.isShiftPressed
                when {
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_SPACE -> { val ok = if (shift) editorBridge.requestSignatureHelp() else editorBridge.requestCompletion(); if (!ok) launchUserAction { snackbarHostState.showSnackbar(if (shift) "Open a text editor for parameter info" else "Open a text editor for completion") }; true }
                    ctrl && shift && event.keyCode == AndroidKeyEvent.KEYCODE_P -> { showPalette = true; true }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_P -> { showQuickOpen = true; true }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_T -> { showWorkspaceSymbols = true; true }
                    ctrl && !shift && event.keyCode == AndroidKeyEvent.KEYCODE_F -> { if (!editorBridge.requestFind()) launchUserAction { snackbarHostState.showSnackbar("Open a text editor to find") }; true }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_W -> { if (activeFile.isNotBlank()) requestClose(activeFile); true }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_PERIOD -> {
                        if (!editorBridge.requestQuickFix()) launchUserAction { snackbarHostState.showSnackbar("Open a text editor for Quick Fix") }
                        true
                    }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_G -> {
                        if (activeFile.isNotBlank()) { goToLineInput = ""; showGoToLine = true }
                        else launchUserAction { snackbarHostState.showSnackbar("Open a file first") }
                        true
                    }
                    ctrl && shift && event.keyCode == AndroidKeyEvent.KEYCODE_O -> {
                        if (!editorBridge.requestDocumentSymbols()) launchUserAction { snackbarHostState.showSnackbar("Open a text editor for symbols") }
                        true
                    }
                    ctrl && shift && event.keyCode == AndroidKeyEvent.KEYCODE_M -> { showProblems = true; true }
                    shift && event.keyCode == AndroidKeyEvent.KEYCODE_F12 -> {
                        if (!editorBridge.requestFindReferences()) launchUserAction { snackbarHostState.showSnackbar("Open a text editor for references") }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F12 -> {
                        if (!editorBridge.requestGoToDefinition()) launchUserAction { snackbarHostState.showSnackbar("Open a text editor for definition") }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F2 -> {
                        if (!editorBridge.requestRenameSymbol()) launchUserAction { snackbarHostState.showSnackbar("Open a text editor to rename a symbol") }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F8 -> { navigateProblem(forward = !shift); true }
                    shift && event.isAltPressed && event.keyCode == AndroidKeyEvent.KEYCODE_F -> {
                        if (!editorBridge.requestFormatDocument()) launchUserAction { snackbarHostState.showSnackbar("Open a text editor to format") }
                        true
                    }
                    event.isAltPressed && event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { navigateBack(); true }
                    event.isAltPressed && event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { navigateForward(); true }
                    ctrl && shift && event.keyCode == AndroidKeyEvent.KEYCODE_F -> {
                        if (expandedWorkbench || isChatVisible) {
                            openSidebarTool(DroideRailTool.Search)
                        } else { showSearch = true; workspaceTab = 0 }
                        true
                    }
                    ctrl && shift && event.keyCode == AndroidKeyEvent.KEYCODE_S -> {
                        launchUserAction {
                            val failure = saveAllDirty()
                            if (failure == null) { snackbarHostState.showSnackbar("All files saved") }
                            else snackbarHostState.showSnackbar("Save needs attention for ${failure.first}: ${failure.second.message}")
                        }
                        true
                    }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_S -> {
                        launchUserAction { saveActive().onFailure { snackbarHostState.showSnackbar("Save needs attention: ${it.message}") } }
                        true
                    }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_GRAVE -> {
                        approvalAttention = WorkspaceApprovalSurface.OTHER
                        if (expandedWorkbench) { bottomToolName = DroideBottomTool.Terminal.name; bottomPanelVisible = true } else workspaceTab = 2
                        true
                    }
                    shift && event.keyCode == AndroidKeyEvent.KEYCODE_F5 -> {
                        launchUserAction { runSuspendCatching { execution.stopDebug() }.onFailure { snackbarHostState.showSnackbar("Stop failed: ${it.message}") } }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F5 -> {
                        launchUserAction {
                            runSuspendCatching {
                                when (debugger.state.value) {
                                    DebugState.STOPPED -> debugger.continueExecution()
                                    DebugState.RUNNING, DebugState.STARTING -> Unit
                                    else -> {
                                        check(activeFile.isNotBlank()) { "Open a file to debug" }
                                        val failure = saveAllDirty()
                                        check(failure == null) { "Save needs attention for ${failure?.first}" }
                                        execution.debugFile(activeFile)
                                    }
                                }
                            }.onFailure { snackbarHostState.showSnackbar("Debug: ${it.message}") }
                        }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F10 -> {
                        if (debugger.state.value == DebugState.STOPPED) launchUserAction { runSuspendCatching { debugger.next() }.onFailure { snackbarHostState.showSnackbar("Step over failed: ${it.message}") } }
                        true
                    }
                    shift && event.keyCode == AndroidKeyEvent.KEYCODE_F11 -> {
                        if (debugger.state.value == DebugState.STOPPED) launchUserAction { runSuspendCatching { debugger.stepOut() }.onFailure { snackbarHostState.showSnackbar("Step out failed: ${it.message}") } }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F11 -> {
                        if (debugger.state.value == DebugState.STOPPED) launchUserAction { runSuspendCatching { debugger.stepIn() }.onFailure { snackbarHostState.showSnackbar("Step into failed: ${it.message}") } }
                        true
                    }
                    else -> false
                }
            }
        ) {
            val imeVisible = rememberDroideImeVisible(); val typingImeActive = droideTypingImeActive(imeVisible, accessoryInputFocus.activeTarget); val editorTypingImeActive = imeVisible && accessoryInputFocus.activeTarget == AccessoryInputTarget.EDITOR
            // Keep orientation/pane grace above, but release compact navigation as soon as the actual IME is
            // hidden. Native travel still owns every intermediate pixel.
            val navigationTypingImeActive = compactNavigationHiddenForIme(
                typingTarget = accessoryInputFocus.activeTarget.keyProfile() != null,
                rawImeVisible = WindowInsets.isImeVisible,
                imeBottomPresent = rememberImeBottomPresent(WindowInsets.ime),
            )
            val terminalInputFocused = accessoryInputFocus.activeTarget.keyProfile() == AccessoryKeyProfile.TERMINAL
            val agentFocusOwner = remember(workspaceKey) { accessoryInputFocus.owner(AccessoryInputTarget.NONE) }
            val terminalImeFocus = rememberLandscapeTerminalImeFocus(workspaceKey, physicalLandscape, imeVisible,
                terminalInputFocused, otherInputFocused = !terminalInputFocused && (editorTypingImeActive ||
                    agentComposerFocused || approvalAttention == WorkspaceApprovalSurface.BROWSER))
            // The optional manual focus preference applies to editor typing, never chat input.
            val effectiveLandscapeFocusMode = workbenchPrefs.landscapeFocusMode && editorTypingImeActive
            val imePresentation = droideImePresentation(physicalLandscape, imeVisible, effectiveLandscapeFocusMode, sidebarVisible, isChatVisible)
            val adaptiveFromConstraints = droideWorkbenchLayout(maxWidth, maxHeight, bottomPanelHeightDp.dp, physicalLandscape = physicalLandscape); val adaptive = droideImeStableWorkbenchLayout(adaptiveFromConstraints, maxWidth, bottomPanelHeightDp.dp, imePresentation)
            val compact = adaptive.compact; val medium = adaptive.medium; val expanded = adaptive.expanded || physicalLandscape
            val agentFullscreen = droidePortraitAgentFullscreen(imePresentation.landscape, isChatVisible) ||
                (!physicalLandscape && isChatVisible && maxWidth < DroideDimensions.ActivityRail + adaptive.agentPanelMinWidth + 190.dp)
            // Match the surface priority below: only the visible rail owns its Projects button.
            val activityRailVisible = !agentFullscreen &&
                (expanded || isChatVisible || medium)
            SideEffect { expandedWorkbench = expanded }
            val sidebarTool = runCatching { DroideRailTool.valueOf(sidebarToolName) }.getOrDefault(DroideRailTool.Files)
            val bottomTool = runCatching { DroideBottomTool.valueOf(bottomToolName) }.getOrDefault(DroideBottomTool.Terminal)
            val focusLayout = rememberDroideLandscapeImeFocusLayout(adaptive, maxWidth, sidebarTool, imePresentation, effectiveLandscapeFocusMode, sidebarVisible, isChatVisible, agentPanelWidthDp.dp); val userPaneFit = focusLayout.userPaneFit
            // Use the same visibility decision for mounting the sidebar and choosing the browser owner. A
            // hidden/zero-width/typing-suppressed sidebar must not steal the main browser.
            val sidebarRendered = !agentFullscreen &&
                (expanded || isChatVisible) && sidebarVisible && userPaneFit.sidebarWidth > 0.dp
            val sidebarOwnsBrowser = sidebarRendered && sidebarTool == DroideRailTool.Web
            val normalBrowserSlot = when {
                agentFullscreen -> null
                sidebarOwnsBrowser -> WorkspaceBrowserSlot.SIDEBAR.takeIf { focusLayout.sidebarRegionVisible }
                !expanded && workspaceTab == 4 -> WorkspaceBrowserSlot.MAIN
                else -> null
            }
            val selectedBrowserSlot = selectedWorkspaceBrowserSlot(browserExpanded, normalBrowserSlot)
            val browserVisible = browserPlacement.bounds(selectedBrowserSlot) != null
            val revealBrowser by rememberUpdatedState<() -> Unit>({
                if (!browserVisible || !browserRetention.captureReady) {
                    terminalImeFocus.exit()
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    if (!browserVisible && !browserExpanded) {
                        if (agentFullscreen) isChatVisible = false
                        if (expanded || isChatVisible) openSidebarTool(DroideRailTool.Web, selectForApproval = false)
                        else workspaceTab = 4
                    }
                }
            })
            DisposableEffect(agentBrowser, workspaceKey) {
                val binding = agentBrowser.bindOpenRequest { revealBrowser() }
                onDispose { agentBrowser.unbindOpenRequest(binding) }
            }
            val openBrowserFromUser: () -> Unit = { approvalAttention = WorkspaceApprovalSurface.BROWSER; revealBrowser() }
            val activeApprovalSurface = workspaceApprovalSurface(
                approvalAttention,
                chatVisible = !browserExpanded && isChatVisible && (agentFullscreen || focusLayout.agentRegionVisible),
                chatFullscreen = !browserExpanded && agentFullscreen,
                browserVisible = browserVisible,
                browserExclusive = browserExpanded || (!expanded && !isChatVisible && workspaceTab == 4),
                otherUiActive = hasTransientUi || (!browserExpanded && terminalImeFocus.active),
            )
            val workspaceContentVisible = !browserExpanded && !agentFullscreen
            val visibleEditor = workspaceContentVisible && !terminalImeFocus.active && (expanded || workspaceTab == 1 ||
                (workspaceTab == 4 && sidebarOwnsBrowser) || workspaceTab !in 0..6)
            val visibleExplorer = !browserExpanded && ((sidebarRendered && sidebarTool == DroideRailTool.Files) ||
                (workspaceContentVisible && !expanded && workspaceTab == 0 && !showSearch))
            SideEffect {
                explorerRefresh.updateVisibility(explorerLease, uiResumed && recoveryReady,
                    visibleExplorer, explorerTreeMode(explorerState.query))
                visibleFiles.update(WorkspaceVisibilityReconciler.Visibility(
                    resumed = uiResumed && recoveryReady,
                    editor = visibleEditor,
                    explorer = visibleExplorer,
                    activePath = activeFile,
                    editorFocused = visibleEditor && accessoryInputFocus.activeTarget == AccessoryInputTarget.EDITOR,
                ))
            }
            val sharedPaneFit = droidePaneFit(adaptive, maxWidth, sidebarTool, true, true, agentPanelWidthDp.dp)
            SideEffect { sidePanelsCanShare = physicalLandscape || sharedPaneFit.sidebarWidth > 0.dp }
            LaunchedEffect(physicalLandscape, browserExpanded, maxWidth, isChatVisible, sidebarVisible, userPaneFit.sidebarWidth) {
                if (!physicalLandscape && !browserExpanded && isChatVisible && sidebarVisible && userPaneFit.sidebarWidth == 0.dp) sidebarVisible = false
            }
            if (!browserExpanded && !physicalLandscape) DroideAdaptiveWorkbenchEffects(adaptive, bottomPanelHeightDp.dp) { bottomPanelHeightDp = it.value }
            @Composable
            fun EditorTabs() = DroideEditorTabs(
                openFiles = openFiles, activeFile = activeFile,
                isDirty = { editorState.peek(it)?.dirty == true },
                onSelect = { captureActiveForLsp(); activeFile = it },
                onClose = ::requestClose,
            )
            @Composable
            fun EditorSurface() {
                Column(Modifier.fillMaxSize().workspaceApprovalAttention(WorkspaceApprovalSurface.OTHER) { approvalAttention = it }
                    .droideContentMotion(activeFile, enabled = !typingImeActive).background(DroideColors.Background)) {
                    EditorTabs()
                    val markdownPath = activeFile.takeIf(::isMarkdownPreviewPath)
                    val markdownMode = markdownPath?.let { markdownModes[it] ?: MarkdownPreviewMode.EDITOR }
                    DroideEditorToolbar(
                        path = activeFile,
                        canBack = navigationBack.isNotEmpty(),
                        canForward = navigationForward.isNotEmpty(),
                        onBack = ::navigateBack,
                        onForward = ::navigateForward,
                        onSave = { launchUserAction { saveActive().onFailure { snackbarHostState.showSnackbar("Save needs attention: ${it.message}") } } },
                        onSymbols = { showWorkspaceSymbols = true },
                        onResetZoom = if (
                            editorState.peek(activeFile)?.let { it.loaded && it.kind == EditorDocumentKind.TEXT } == true &&
                            markdownMode != MarkdownPreviewMode.PREVIEW
                        ) ({ editorBridge.requestResetZoom() }) else null,
                        onGoToLine = { if (activeFile.isNotBlank()) { goToLineInput = ""; showGoToLine = true } },
                        markdownPreviewMode = markdownMode,
                        onMarkdownPreviewModeChange = markdownPath?.let { path ->
                            { mode ->
                                editorBridge.capture()
                                markdownModes[path] = mode
                                if (mode == MarkdownPreviewMode.PREVIEW) {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                }
                            }
                        },
                    )
                    runnerMsg?.takeIf { runnerPath == activeFile }?.let {
                        Row(Modifier.fillMaxWidth().background(DroideColors.Surface2).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(it.take(2_000), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 2)
                            IconButton(onClick = { runnerMsg = null }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Close run output") }
                        }
                    }
                    if (activeFile.isNotBlank()) {
                        val document = editorState.document(activeFile)
                        val markdownMode = if (isMarkdownPreviewPath(activeFile)) markdownModes[activeFile] ?: MarkdownPreviewMode.EDITOR else MarkdownPreviewMode.EDITOR
                        @Composable
                        fun ActiveEditorPane(modifier: Modifier = Modifier) {
                            Box(modifier) {
                                // AndroidView's factory/listeners belong to exactly one document. A tab switch must create a new
                                // Sora view, not reuse old closures.
                                key(workspaceKey, activeFile, document) {
                                EditorPane(
                                    files = files,
                                    path = activeFile,
                                    document = document,
                                    bridge = editorBridge,
                                    terminal = terminalManager.activeSession(),
                                    lsp = lsp,
                                    formatter = formatters,
                                    theme = themeSnapshot,
                                    codeStyleDefaults = workbenchPrefs.codeStyleDefaults,
                                    wordwrap = wordwrap,
                                    codingKeyboardMode = workbenchPrefs.codingKeyboardMode,
                                    accessoryKeysComfortable = workbenchPrefs.accessoryKeysComfortable, accessoryInputFocus = accessoryInputFocus, accessoryKeysExpanded = editorAccessoryKeysExpanded, onAccessoryKeysExpandedChange = { editorAccessoryKeysExpanded = it },
                                    onToggleBreakpoint = { line -> debugger.toggleBreakpoint(activeFile, line) },
                                    onOpenLocation = ::openLocation,
                                    onApplyWorkspaceEdit = { edit ->
                                        launchUserAction {
                                            applyWorkspaceEdit(edit)
                                                .onSuccess { count -> snackbarHostState.showSnackbar("Language-server edits applied to $count file(s). Review and Save All when ready.") }
                                                .onFailure { snackbarHostState.showSnackbar("Language-server edit failed: ${it.message}") }
                                        }
                                    },
                                    navigateLine = navigationTarget?.takeIf { it.first == activeFile }?.second,
                                    navigateColumn = navigationTarget?.takeIf { it.first == activeFile }?.third ?: 1,
                                    navigationToken = navigationToken,
                                    onNavigationConsumed = { if (navigationTarget?.first == activeFile) navigationTarget = null },
                                )
                                }
                            }
                        }

                        when (markdownMode) {
                            MarkdownPreviewMode.EDITOR -> ActiveEditorPane(Modifier.fillMaxSize())
                            MarkdownPreviewMode.PREVIEW -> {
                                LaunchedEffect(activeFile, document.pendingRecoveryBuffer) { document.ensureLoaded(files) }
                                MarkdownFilePreview(document, Modifier.fillMaxSize())
                            }
                            MarkdownPreviewMode.SPLIT -> BoxWithConstraints(Modifier.fillMaxSize()) {
                                if (maxWidth >= 720.dp) {
                                    Row(Modifier.fillMaxSize()) {
                                        ActiveEditorPane(Modifier.weight(1f).fillMaxHeight())
                                        VerticalDivider(color = DroideColors.Border)
                                        MarkdownFilePreview(document, Modifier.weight(1f).fillMaxHeight())
                                    }
                                } else {
                                    Column(Modifier.fillMaxSize()) {
                                        ActiveEditorPane(Modifier.weight(1f).fillMaxWidth())
                                        HorizontalDivider(color = DroideColors.Border)
                                        MarkdownFilePreview(document, Modifier.weight(1f).fillMaxWidth())
                                    }
                                }
                            }
                        }
                    } else Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Code, null, Modifier.size(34.dp), tint = DroideColors.Muted)
                            Spacer(Modifier.height(8.dp))
                            Text("Open a file to start editing", color = DroideColors.Muted)
                        }
                    }
                }
            }
            @Composable
            fun TerminalSurface(showCreate: Boolean = true, typingFocus: Boolean = false) {
                Column(Modifier.fillMaxSize().workspaceApprovalAttention(WorkspaceApprovalSurface.OTHER) { approvalAttention = it }.background(DroideColors.Background)) {
                    if (!typingFocus || showCreate) {
                        TerminalTabs(terminalSnapshot,
                            onSelect = { terminalManager.switch(it) },
                            onClose = { id -> terminalManager.close(id).onFailure { showActionFailure("Terminal cleanup failed", it) } },
                            onCreate = if (showCreate) ({ openLinuxTerminal() }) else null,
                            createEnabled = terminalManager.canCreateTab && linuxSetupJob == null && !ubuntuPreparation.preparing)
                    }
                    if (linuxSetupJob != null || ubuntuPreparation.preparing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(if (substrateState.ubuntuAvailable) "Opening Ubuntu…" else "Preparing Ubuntu for first use…", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            OutlinedButton(onClick = { linuxSetupJob?.cancel(); terminalManager.cancelUbuntuPreparation() }) { Text("Cancel setup") }
                        }
                    }
                    ubuntuPreparation.error?.let { message ->
                        Row(Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                Text(message.take(2_000), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            OutlinedButton(onClick = { openLinuxTerminal() }, enabled = !ubuntuPreparation.preparing && linuxSetupJob == null && terminalManager.canCreateTab) { Text("Retry") }
                            IconButton(onClick = { terminalManager.dismissUbuntuPreparationError() }) { Icon(Icons.Default.Close, "Dismiss Ubuntu setup error") }
                        }
                    }
                    terminalSnapshot.activeTab?.let { selected ->
                        TerminalPane(
                            session = selected.session,
                            profile = selected.profile,
                            onOpenLinux = if (linuxSetupJob?.isActive != true && !ubuntuPreparation.preparing && terminalManager.canCreateTab) {
                                { openLinuxTerminal() }
                            } else null,
                            theme = themeSnapshot,
                            accessoryKeysComfortable = workbenchPrefs.accessoryKeysComfortable,
                            accessoryInputFocus = accessoryInputFocus,
                            accessoryKeysExpanded = terminalAccessoryKeysExpanded,
                            typingFocusMode = typingFocus,
                            codingKeyboardMode = workbenchPrefs.codingKeyboardMode,
                            onAccessoryKeysExpandedChange = { terminalAccessoryKeysExpanded = it },
                        )
                    }
                        ?: Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("No terminal session", color = DroideColors.Muted) }
                }
            }
            @Composable
            fun DebugSurface() {
                DebugPanel(
                    debugger = debugger,
                    activeFile = activeFile,
                    onEnsureSaved = {
                        val failure = saveAllDirty()
                        if (failure == null) { true }
                        else { snackbarHostState.showSnackbar("Save needs attention for ${failure.first}: ${failure.second.message}"); false }
                    },
                    onStartDebug = { config, sourcePath -> execution.debugPreparedConfiguration(sourcePath, config) },
                    onStopDebug = { execution.stopDebug() },
                    onOpenLocation = ::openLocation,
                    onOpenExtensions = { if (expanded || isChatVisible) { openSidebarTool(DroideRailTool.Extensions) } else workspaceTab = 6 },
                )
            }
            @Composable
            fun FocusedWorkspace() {
                // The stable browser host and its placeholder must use the same geometry.
                Box(Modifier.fillMaxSize().workspaceApprovalAttention(WorkspaceApprovalSurface.OTHER) { approvalAttention = it }
                    .droideContentMotion(Pair(workspaceTab, showSearch), enabled = !typingImeActive && workspaceTab != 4)) {
                    when (workspaceTab) {
                        0 -> if (showSearch) SearchPanel(files, documents, { editorBridge.capture() }, onOpen = ::openLocation) else FileExplorer(
                            files = files,
                            state = explorerState,
                            refreshController = explorerRefresh,
                            refreshLease = explorerLease,
                            imports = imports,
                            onExport = if (ExportRolloutPolicy.newExportsEnabled) exportUi.open else null,
                            onExportStatus = exportUi.showStatus, exportStatusLabel = exportUi.statusLabel,
                            onDismissExportStatus = exportUi.dismissStatus,
                            onBeforeImport = { editorBridge.capture() },
                            onOpen = { open(it) },
                            canMutatePath = { path -> editorBridge.capture(); !editorState.hasPendingPathMutationAtOrUnder(path) },
                            preparePathMutation = { paths ->
                                saves.finishSavedPathWork(paths).continuationFailure()?.let { (path, error) ->
                                    throw IllegalStateException("Cannot change /$path: ${error.message}", error)
                                }
                            },
                            onRenamed = ::onPathRenamed,
                            onDeleted = ::onPathDeleted, safLinked = safLinked, onReconcileSaf = onReconcileSaf,
                        )
                        1 -> EditorSurface()
                        2 -> TerminalSurface()
                        3 -> GitPanel(git, onOpen = { open(it) }, onWorkspaceChanged = explorerState::requestRefresh)
                        4 -> FocusedWorkbenchBrowser(
                            sidebarOwnsBrowser = sidebarOwnsBrowser,
                            browser = { WorkspaceBrowserPlaceholder(browserPlacement, WorkspaceBrowserSlot.MAIN) },
                            editor = { EditorSurface() },
                        )
                        5 -> DebugSurface()
                        6 -> ExtensionsPane(extensions, androidDevelopment, deviceBridge, activeFile, agentExtensionFamilyId, environmentRevision = terminalEnvironmentRevision) { agentExtensionFamilyId = null }
                        else -> EditorSurface()
                    }
                }
            }
            @Composable
            fun SidebarSurface(widthOverride: androidx.compose.ui.unit.Dp? = null) {
                val title = when (sidebarTool) {
                    DroideRailTool.Files -> projectName
                    DroideRailTool.Search -> "Search"
                    DroideRailTool.Git -> "Source Control"
                    DroideRailTool.Run -> "Run & Debug"
                    DroideRailTool.Web -> "Web"
                    DroideRailTool.Extensions -> "Extensions"
                }
                val sideWidth = widthOverride ?: if (sidebarTool == DroideRailTool.Files || sidebarTool == DroideRailTool.Search) adaptive.sidebarWidth else adaptive.wideSidebarWidth
                DroideSidebarFrame(title, sideWidth, onClose = { sidebarVisible = false }) {
                    Box(Modifier.fillMaxSize().workspaceApprovalAttention(WorkspaceApprovalSurface.OTHER) { approvalAttention = it }
                        .droideContentMotion(sidebarTool, enabled = !typingImeActive && sidebarTool != DroideRailTool.Web)) {
                        when (sidebarTool) {
                            DroideRailTool.Files -> FileExplorer(
                                files = files,
                                state = explorerState,
                                refreshController = explorerRefresh,
                                refreshLease = explorerLease,
                                imports = imports,
                            onExport = if (ExportRolloutPolicy.newExportsEnabled) exportUi.open else null,
                            onExportStatus = exportUi.showStatus, exportStatusLabel = exportUi.statusLabel,
                            onDismissExportStatus = exportUi.dismissStatus,
                                onBeforeImport = { editorBridge.capture() },
                                onOpen = { open(it) },
                                canMutatePath = { path -> editorBridge.capture(); !editorState.hasPendingPathMutationAtOrUnder(path) },
                                preparePathMutation = { paths ->
                                    saves.finishSavedPathWork(paths).continuationFailure()?.let { (path, error) ->
                                        throw IllegalStateException("Cannot change /$path: ${error.message}", error)
                                    }
                                },
                                onRenamed = ::onPathRenamed,
                                onDeleted = ::onPathDeleted, safLinked = safLinked, onReconcileSaf = onReconcileSaf,
                            )
                            DroideRailTool.Search -> SearchPanel(files, documents, { editorBridge.capture() }, onOpen = ::openLocation)
                            DroideRailTool.Git -> GitPanel(git, onOpen = { open(it) }, onWorkspaceChanged = explorerState::requestRefresh)
                            DroideRailTool.Run -> DebugSurface()
                            DroideRailTool.Web -> WorkspaceBrowserPlaceholder(browserPlacement, WorkspaceBrowserSlot.SIDEBAR)
                            DroideRailTool.Extensions -> ExtensionsPane(extensions, androidDevelopment, deviceBridge, activeFile, agentExtensionFamilyId, environmentRevision = terminalEnvironmentRevision) { agentExtensionFamilyId = null }
                        }
                    }
                }
            }
            @Composable
            fun BottomPanelContent(terminalTypingFocus: Boolean = false) {
                Box(Modifier.fillMaxSize().workspaceApprovalAttention(WorkspaceApprovalSurface.OTHER) { approvalAttention = it }.droideContentMotion(bottomTool, enabled = !typingImeActive)) {
                    when (bottomTool) {
                        DroideBottomTool.Terminal -> TerminalSurface(showCreate = !terminalTypingFocus, typingFocus = terminalTypingFocus)
                        DroideBottomTool.Problems -> ProblemsSheet(
                            files = files,
                            lsp = lsp,
                            buildDiagnostics = androidBuildDiagnostics,
                            onOpen = { path, line, col -> openLocation(path, line, col) },
                            onApplyWorkspaceEdit = { edit -> launchUserAction { applyWorkspaceEdit(edit) } },
                        )
                        DroideBottomTool.Output -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
                            Text((if (androidOutputText.isNotBlank()) androidOutputText else runnerMsg ?: "No output yet").take(300_000), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                        DroideBottomTool.Build -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (androidOperation.running) androidOperation.message else androidOutputTitle, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("${androidBuildDiagnostics.size} problem(s)", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted); lastAndroidApk?.let { apk -> FileIdentityLabel(apk.absolutePath, apk.name, iconSize = 14.dp, color = DroideColors.Muted) } }
                            if (androidOperation.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (androidOutputText.isNotBlank()) Text(androidOutputText.takeLast(60_000), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            @Composable
            fun AgentSurface() {
                Box(Modifier.fillMaxSize().workspaceApprovalAttention(WorkspaceApprovalSurface.AGENT) { approvalAttention = it }.droideContentMotion("agent", enabled = !typingImeActive)) {
                    AgentPane(agent = agent, uiState = agentUiState, providerConnections = providerConnections, localLlamaModels = localLlamaModels, agentPlugins = workspaceAgentPluginsForStartup(extensions.installedComponentsEnabled, agentPlugins), approvals = approvals, captureIdeContext = { editorBridge.capture(); captureAgentIdeContext(files.root, activeFile, activeLanguageName, openFiles, editorState, editorBridge.currentCursorLocation(), diagnostics, androidBuildDiagnostics) }, onSessions = { showSessions = true }, onUiError = { message -> scope.launchUiCatching { snackbarHostState.showSnackbar(message) } }, onComposerFocusChanged = { agentComposerFocused = it; if (physicalLandscape) accessoryInputFocus.onFocusChanged(agentFocusOwner, it); if (it) { editorBridge.yieldToAgentComposer(); approvalAttention = WorkspaceApprovalSurface.AGENT } }, onClose = { isChatVisible = false },
                        onManageMcp = { settingsInitialCategoryName = "TOOLS"; showSettings = true },
                        onManagePlugins = { settingsInitialCategoryName = "PLUGINS"; showSettings = true },
                        onManageSkills = { settingsInitialCategoryName = "SKILLS"; showSettings = true },
                        onOpenAgentExtensions = { familyId ->
                        agentExtensionFamilyId = familyId
                        if (expanded) { openSidebarTool(DroideRailTool.Extensions) }
                        else { isChatVisible = false; workspaceTab = 6 }
                    })
                }
            }
            fun selectRail(tool: DroideRailTool) {
                if (sidebarTool == tool && sidebarVisible) { sidebarVisible = false; approvalAttention = WorkspaceApprovalSurface.OTHER }
                else openSidebarTool(tool)
            }
            fun selectMediumTool(tool: DroideRailTool) {
                approvalAttention = if (tool == DroideRailTool.Web) WorkspaceApprovalSurface.BROWSER else WorkspaceApprovalSurface.OTHER
                when (tool) {
                    DroideRailTool.Files -> { showSearch = false; workspaceTab = 0 }
                    DroideRailTool.Search -> { showSearch = true; workspaceTab = 0 }
                    DroideRailTool.Git -> workspaceTab = 3
                    DroideRailTool.Run -> workspaceTab = 5
                    DroideRailTool.Web -> workspaceTab = 4
                    DroideRailTool.Extensions -> workspaceTab = 6
                }
            }
            @Composable
            fun TopChrome(compactTop: Boolean) {
                val hasOutput = androidOutputText.isNotBlank() || !runnerMsg.isNullOrBlank(); val busy = executionState.busy || androidOperation.running || userTaskState.busy
                DroideIdeHeader(
                    state = DroideIdeHeaderState(projectName, activeFile, compactTop, adaptive.wideHeader, isChatVisible, imePresentation.landscape, workbenchPrefs.landscapeFocusMode, busy, workspaceEnvironment.kind == WorkspaceKind.ANDROID_GRADLE, deviceBridgeState.connected != null, hasOutput, lastAndroidApk?.isFile == true, executionState.busy || userTaskState.busy, mcpHealth, terminalManager.canCreateTab, linuxSetupJob != null || ubuntuPreparation.preparing, showProjectsButton = !activityRailVisible),
                    actions = DroideIdeHeaderActions(
                        onProjects = { drawer = true },
                        onRun = {
                            val runPath = activeFile
                            val target = primaryRunTarget(runPath, workspaceEnvironment.kind == WorkspaceKind.ANDROID_GRADLE)
                            launchUserAction {
                                if (target != PrimaryRunTarget.ANDROID_APP ||
                                    withContext(Dispatchers.IO) { AndroidProjectDetector.detect(files.root) } == null) {
                                    runCurrentFile(runPath, presentAsSheet = !expanded)
                                } else if (deviceBridgeState.connected != null) {
                                    installAndRunAndroid()
                                } else if (snackbarHostState.showSnackbar(
                                        "Connect Wireless Debugging to install and run this Android app.",
                                        actionLabel = "Connect", withDismissAction = true,
                                    ) == SnackbarResult.ActionPerformed) {
                                    showAndroidDevelopment = true
                                }
                            }
                        },
                        onBuildDebug = { openBottomTool(DroideBottomTool.Build); launchUserAction { runAndroidTask("Android: Build Debug APK") { execution.buildDebug() } } },
                        onTest = { openBottomTool(DroideBottomTool.Build); launchUserAction { runAndroidTask("Android: Test") { execution.test() } } }, onTestExplorer = { professionalSheet = ProfessionalSheet.TEST_EXPLORER },
                        onLint = { openBottomTool(DroideBottomTool.Build); launchUserAction { runAndroidTask("Android: Lint") { execution.lint() } } },
                        onReleaseApk = { openBottomTool(DroideBottomTool.Build); launchUserAction { runAndroidTask("Android: Build Release APK") { execution.buildReleaseApk() } } },
                        onReleaseBundle = { openBottomTool(DroideBottomTool.Build); launchUserAction { runAndroidTask("Android: Build Release Bundle") { execution.buildReleaseBundle() } } },
                        onBuildOutput = { if (expanded) openBottomTool(DroideBottomTool.Output) else { if (androidOutputText.isBlank() && !runnerMsg.isNullOrBlank()) { androidOutputTitle = "Run Output"; androidOutputText = runnerMsg.orEmpty() }; showAndroidOutput = true } },
                        onApkAnalyzer = { professionalSheet = ProfessionalSheet.APK_ANALYZER },
                        onCancel = { val canceled = cancelUserOperation(); launchUserAction { snackbarHostState.showSnackbar(if (canceled) "Cancel requested" else "No cancellable operation is active") } },
                        onDebugAndroid = { launchUserAction { debugAndroidApp() } },
                        onInstallRun = { launchUserAction { installAndRunAndroid() } },
                        onLogcat = { launchUserAction { loadAndroidLogcat(presentAsSheet = !expanded) } },
                        onLocalLinux = { openLinuxTerminal() },
                        onAndroidDevelopment = { showAndroidDevelopment = true },
                        onQuickOpen = { showQuickOpen = true },
                        onSearchFiles = { if (expanded || isChatVisible) { openSidebarTool(DroideRailTool.Search) } else { showSearch = true; workspaceTab = 0 } },
                        onWorkspaceSymbols = { showWorkspaceSymbols = true },
                        onProblems = { if (expanded) openBottomTool(DroideBottomTool.Problems) else showProblems = true },
                        onTerminal = { openBottomTool(DroideBottomTool.Terminal) },
                        onGit = { showSearch = false; if (expanded || isChatVisible) { openSidebarTool(DroideRailTool.Git) } else workspaceTab = 3 }, onReviewChanges = { professionalSheet = ProfessionalSheet.CHANGE_REVIEW }, onIsolatedWorkspaces = { professionalSheet = ProfessionalSheet.ISOLATED_WORKSPACES }, onCapabilityHealth = { professionalSheet = ProfessionalSheet.CAPABILITY_HEALTH },
                        onTasks = { professionalSheet = ProfessionalSheet.TASKS },
                        onAgent = { if (isChatVisible) isChatVisible = false else openAgentPane() }, onFocusMode = { launchUserAction { WorkbenchPreferences.setLandscapeFocusMode(ctx, !workbenchPrefs.landscapeFocusMode) } },
                        onCommandPalette = { showPalette = true },
                        onRunDebug = { if (expanded || isChatVisible) { openSidebarTool(DroideRailTool.Run) } else workspaceTab = 5 },
                        onSessions = { showSessions = true },
                        onExtensions = { if (expanded || isChatVisible) { openSidebarTool(DroideRailTool.Extensions) } else workspaceTab = 6 },
                        onConnectProvider = { showConnect = true },
                        onModels = { showModels = true },
                        onBilling = { showBilling = true },
                        onSettings = { settingsInitialCategoryName = null; showSettings = true },
                        onMcpRetry = { settingsInitialCategoryName = "TOOLS"; showSettings = true },
                        onManageMcp = { settingsInitialCategoryName = "TOOLS"; showSettings = true },
                        onNotificationSettings = onNotificationSettings,
                    ),
                )
            }
            Scaffold(containerColor = DroideColors.Background,
                contentWindowInsets = if (browserExpanded) expandedBrowserContentInsets(
                    WindowInsets.systemBars, WindowInsets.displayCutout,
                ) else ScaffoldDefaults.contentWindowInsets,
                bottomBar = {
                InstallerProgressHost(extensions.installProgress) {
                WorkspaceApprovalHost(
                    approvals = approvals,
                    browserVisible = browserVisible,
                    browserDialogPending = browserDialog != null,
                    activeSurface = activeApprovalSurface,
                    reviewRequest = approvalReviewRequest,
                    compact = browserExpanded,
                    onOpenBrowser = openBrowserFromUser,
                )
                }
            }) { pad ->
                Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
                RetainedWorkspaceContent(presented = !browserExpanded) {
                Column(Modifier.fillMaxSize()) {
                    PreferenceReadFailureRow("Workspace preferences", workbenchRead.state.failed, workbenchRead::retry)
                    PreferenceReadFailureRow("Provider preferences", providerRead.state.failed, providerRead::retry)
                    PreferenceReadFailureRow("Tool health configuration", toolHealthRead.state.failed, toolHealthRead::retry)

                    if (!droideHideLandscapeHeader(physicalLandscape, editorTypingImeActive, terminalImeFocus.active) && !agentFullscreen) {
                        TopChrome(compactTop = compact || adaptive.shortHeight)
                    }
                    if (browserRetention.keepActive && !browserVisible) BrowserRetentionNotice(browserRetention.attached, browserDialog != null, openBrowserFromUser)
                    when {
                        agentFullscreen -> {
                            Box(Modifier.weight(1f).fillMaxWidth()) { AgentSurface() }
                        }
                        expanded -> {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                DroideActivityRail(
                                    selected = sidebarTool,
                                    sidebarVisible = imePresentation.sidebarVisible,
                                    bottomPanelVisible = bottomPanelVisible,
                                    onTool = ::selectRail,
                                    onTerminal = { approvalAttention = WorkspaceApprovalSurface.OTHER; bottomToolName = DroideBottomTool.Terminal.name; bottomPanelVisible = !bottomPanelVisible },
                                    onProjects = { drawer = true },
                                    onSettings = { settingsInitialCategoryName = null; showSettings = true },
                                )
                                Row(Modifier.weight(1f).fillMaxHeight()) {
                                    if (sidebarRendered) { DroideClippedPaneRegion({ focusLayout.sidebarRegionWidth }, focusLayout.sidebarRegionVisible, userPaneFit.sidebarWidth + 1.dp) { Row { SidebarSurface(userPaneFit.sidebarWidth); VerticalDivider(color = DroideColors.Border) } } }
                                    if (physicalLandscape) {
                                        LandscapeEditorArea(
                                            workspaceKey = workspaceKey, activeTool = bottomTool,
                                            panelVisible = bottomPanelVisible, panelHeight = adaptive.effectiveBottomPanelHeight,
                                            panelMaxHeight = adaptive.bottomPanelMaxHeight,
                                            editorTyping = editorTypingImeActive,
                                            terminalExpanded = terminalImeFocus.active && bottomTool == DroideBottomTool.Terminal,
                                            onPanelHeightChange = { bottomPanelHeightDp = it.value },
                                            onSelectTool = { tool ->
                                                approvalAttention = WorkspaceApprovalSurface.OTHER
                                                if (tool != DroideBottomTool.Terminal && terminalImeFocus.active) terminalImeFocus.exit()
                                                bottomToolName = tool.name
                                            },
                                            onOpenPanel = { approvalAttention = WorkspaceApprovalSurface.OTHER; bottomPanelVisible = true },
                                            onClosePanel = { if (terminalImeFocus.active) terminalImeFocus.exit(); approvalAttention = WorkspaceApprovalSurface.OTHER; bottomPanelVisible = false },
                                            onRestoreTerminal = terminalImeFocus.exit,
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            panelActions = {
                                                if (bottomTool == DroideBottomTool.Terminal) LandscapeTerminalSessions(
                                                    terminalSnapshot,
                                                    createEnabled = terminalManager.canCreateTab && linuxSetupJob == null && !ubuntuPreparation.preparing,
                                                    onSelect = terminalManager::switch,
                                                    onClose = { id ->
                                                        terminalManager.close(id)
                                                            .onSuccess { if (terminalSnapshot.tabs.size == 1 && terminalImeFocus.active) terminalImeFocus.exit() }
                                                            .onFailure { showActionFailure("Terminal cleanup failed", it) }
                                                    },
                                                    onCreate = { openLinuxTerminal() },
                                                    onInterrupt = { runUiCatching(onError = { showActionFailure("Terminal interrupt failed", it) }) { terminalSnapshot.activeTab?.session?.interrupt() } },
                                                )
                                            },
                                            editor = { EditorSurface() }, panel = { BottomPanelContent(terminalTypingFocus = true) },
                                        )
                                    } else {
                                        Column(Modifier.weight(1f).fillMaxHeight()) {
                                            Box(Modifier.weight(1f).fillMaxWidth()) { EditorSurface() }
                                            if (bottomPanelVisible && !editorTypingImeActive) {
                                                HorizontalDivider(color = DroideColors.Border)
                                                DroideBottomPanelFrame(
                                                    active = bottomTool,
                                                    height = adaptive.effectiveBottomPanelHeight,
                                                    maxHeight = adaptive.bottomPanelMaxHeight,
                                                    onHeightChange = { bottomPanelHeightDp = it.coerceAtMost(adaptive.bottomPanelMaxHeight).value },
                                                    onSelect = { approvalAttention = WorkspaceApprovalSurface.OTHER; bottomToolName = it.name },
                                                    onCollapse = { approvalAttention = WorkspaceApprovalSurface.OTHER; bottomPanelVisible = false },
                                                ) { BottomPanelContent() }
                                            } else if (!typingImeActive) {
                                                Surface(color = DroideColors.Surface) {
                                                    Row(Modifier.fillMaxWidth().heightIn(min = 35.dp).horizontalScroll(rememberScrollState()), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                        DroideBottomTool.entries.forEach { tool -> TextButton(onClick = { approvalAttention = WorkspaceApprovalSurface.OTHER; bottomToolName = tool.name; bottomPanelVisible = true }) { Text(tool.label, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) } }
                                                        Spacer(Modifier.weight(1f))
                                                        IconButton(onClick = { approvalAttention = WorkspaceApprovalSurface.OTHER; bottomPanelVisible = true }, Modifier.size(34.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Open bottom panel") }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (isChatVisible && userPaneFit.agentWidth > 0.dp) {
                                        val maxAgent = adaptive.agentPanelMaxWidth
                                        DroideClippedPaneRegion({ focusLayout.agentRegionWidth }, focusLayout.agentRegionVisible, userPaneFit.agentWidth + 10.dp, androidx.compose.ui.Alignment.CenterEnd) { DroideResizableAgentPanel(userPaneFit.agentWidth, userPaneFit.agentMinWidth, minOf(maxAgent, userPaneFit.agentMaxWidth), { agentPanelWidthDp = it.value }) { AgentSurface() } }
                                    }
                                }
                            }
                        }
                        isChatVisible -> {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                DroideActivityRail(sidebarTool, imePresentation.sidebarVisible, false, ::selectRail, { approvalAttention = WorkspaceApprovalSurface.OTHER; workspaceTab = 2 }, { drawer = true }, { settingsInitialCategoryName = null; showSettings = true })
                                Row(Modifier.weight(1f).fillMaxHeight()) {
                                    if (sidebarRendered) { DroideClippedPaneRegion({ focusLayout.sidebarRegionWidth }, focusLayout.sidebarRegionVisible, userPaneFit.sidebarWidth + 1.dp) { Row { SidebarSurface(userPaneFit.sidebarWidth); VerticalDivider(color = DroideColors.Border) } } }
                                    Column(Modifier.weight(1f).fillMaxHeight()) { Box(Modifier.weight(1f).fillMaxWidth()) { FocusedWorkspace() } }
                                    DroideClippedPaneRegion({ focusLayout.agentRegionWidth }, focusLayout.agentRegionVisible, userPaneFit.agentWidth + 10.dp, androidx.compose.ui.Alignment.CenterEnd) { DroideResizableAgentPanel(userPaneFit.agentWidth, userPaneFit.agentMinWidth, userPaneFit.agentMaxWidth, { agentPanelWidthDp = it.value }) { AgentSurface() } }
                                }
                            }
                        }
                        medium -> Row(Modifier.weight(1f).fillMaxWidth()) {
                            DroideActivityRail(when (workspaceTab) { 3 -> DroideRailTool.Git; 4 -> DroideRailTool.Web; 5 -> DroideRailTool.Run; 6 -> DroideRailTool.Extensions; else -> if (showSearch) DroideRailTool.Search else DroideRailTool.Files }, true, workspaceTab == 2, ::selectMediumTool, { approvalAttention = WorkspaceApprovalSurface.OTHER; workspaceTab = 2 }, { drawer = true }, { settingsInitialCategoryName = null; showSettings = true })
                            Column(Modifier.weight(1f).fillMaxHeight()) { Box(Modifier.weight(1f)) { FocusedWorkspace() } }
                        }
                        else -> {
                            Box(Modifier.weight(1f).fillMaxWidth()) { FocusedWorkspace() }
                            CompactNavigationImeReveal(
                                typingTarget = accessoryInputFocus.activeTarget.keyProfile() != null,
                                inputEnabled = !navigationTypingImeActive,
                            ) {
                                DroideCompactNavigation(workspaceTab, enabled = !navigationTypingImeActive) { id -> showSearch = false; workspaceTab = id; approvalAttention = if (id == 4) WorkspaceApprovalSurface.BROWSER else WorkspaceApprovalSurface.OTHER }
                            }
                        }
                    }
                }
                }
                if (browserExpanded) {
                    // Scaffold owns system-bar/footer insets; IME padding consumes only the remainder.
                    Box(Modifier.fillMaxSize().imePadding()) {
                        WorkspaceBrowserPlaceholder(browserPlacement, WorkspaceBrowserSlot.EXPANDED)
                    }
                }
                }
            }
            WorkspaceBrowserHost(browserPlacement, selectedBrowserSlot, browserRetention.keepActive, browserRetention.stopping) { presented, captureReady ->
                Box(Modifier.fillMaxSize().workspaceApprovalAttention(WorkspaceApprovalSurface.BROWSER) { if (presented) approvalAttention = it }) {
                    BrowserPane(browserUrl, onUrlChange = { browserUrl = it }, agentBrowser = agentBrowser,
                        presented = presented, captureReady = captureReady,
                        expanded = browserExpanded, onExpandedChange = ::changeBrowserExpanded,
                        onUserFocus = { if (presented) approvalAttention = WorkspaceApprovalSurface.BROWSER },
                        onReviewAgentApproval = pendingWorkspaceApproval?.let { request -> {
                            if (approvals.pending.value?.id == request.id) {
                                approvalReviewSequence++
                                approvalReviewRequest = request.id to approvalReviewSequence
                            }
                        } })
                }
            }
            DismissibleSnackbarHost(snackbarHostState, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding().zIndex(3f))
        }
        if (drawer) DroideModalBottomSheet(onDismissRequest = { drawer = false }) { dismiss ->
            ProjectDrawer(
                manager = projectManager,
                onPickSaf = {
                    drawer = false
                    captureActiveForLsp()
                    if (editorState.dirtyDocuments().isNotEmpty()) pendingSafPick = true else onPickSaf()
                },
                onSwitch = { id -> drawer = false; requestProjectSwitch(id) }, onCreateProject = { name, template -> captureActiveForLsp(); check(editorState.dirtyDocuments().isEmpty()) { "Save or discard unsaved editor changes before creating a project" }; onProjectCreate(name, template).also { drawer = false } },
                onDismiss = dismiss,
            )
        }
        if (showGoToLine) {
            AlertDialog(
                onDismissRequest = { showGoToLine = false },
                title = { Text("Go to Line") },
                text = {
                    OutlinedTextField(
                        value = goToLineInput,
                        onValueChange = { goToLineInput = it.filter(Char::isDigit).take(9) },
                        label = { Text("Line number") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val line = goToLineInput.toIntOrNull()?.coerceAtLeast(1) ?: return@Button
                            showGoToLine = false
                            openLocation(activeFile, line, 1)
                        },
                        enabled = goToLineInput.toIntOrNull()?.let { it > 0 } == true,
                    ) { Text("Go") }
                },
                dismissButton = { TextButton(onClick = { showGoToLine = false }) { Text("Cancel") } },
            )
        }
        if (showProblems) DroideModalBottomSheet(onDismissRequest = { showProblems = false }) { dismiss ->
            ProblemsSheet(
                files = files,
                lsp = lsp,
                buildDiagnostics = androidBuildDiagnostics,
                onOpen = { path, line, col ->
                    showProblems = false
                    openLocation(path, line, col)
                },
                onApplyWorkspaceEdit = { edit ->
                    launchUserAction {
                        val result = applyWorkspaceEdit(edit)
                        result.onSuccess { count -> snackbarHostState.showSnackbar("Applied $count code-action edit(s) to buffers") }
                            .onFailure { snackbarHostState.showSnackbar("Quick Fix failed: ${it.message}") }
                    }
                },
                onClose = dismiss,
            )
        }
        ProfessionalSheetHost(professionalSheet, taskManager, git, worktreeManager, androidDevelopment, deviceBridge, debugger, extensions, activeFile, lastAndroidApk, onOpenSourceControl = { professionalSheet = null; workspaceTab = 3; if (expandedWorkbench || isChatVisible) { openSidebarTool(DroideRailTool.Git) } },
            beforeTaskRun = { saveAllDirty()?.let { throw IllegalStateException("Save needs attention for ${it.first}: ${it.second.message}", it.second) } }, taskController = execution.projectTasks, onDismiss = { professionalSheet = null })
        if (showQuickOpen) QuickOpenDialog(
            files = files,
            onOpen = { path -> open(path) },
            onDismiss = { showQuickOpen = false },
        )
        if (showWorkspaceSymbols) WorkspaceSymbolsDialog(
            lsp = lsp,
            pathHint = activeFile,
            onOpen = { path, line, col -> openLocation(path, line, col) },
            onDismiss = { showWorkspaceSymbols = false },
        )
        if (showEditorRecoveryCopies) EditorRecoveryCopiesDialog(
            editorState, files, allowed = uiResumed && recoveryReady,
            onOpen = { prepared ->
                check(uiResumed && recoveryReady) { "Workspace is not active" }
                val previousTabs = openFiles
                val next = openRecoveryCopyTab(editorState, prepared, openFiles, activeFile, editorBridge::captureForSave)
                openFiles = next
                activeFile = prepared.destination
                workspaceTab = 1
                previousTabs.filterNot { it in next }.forEach { path ->
                    launchUserAction { runSuspendCatching { lsp.didClose(path) } }
                }
            },
            onDismiss = { showEditorRecoveryCopies = false },
            archivedIssues = archivedRecoveryIssues,
            discardActions = editorState.recoveryDiscardActions,
        )
        if (showPalette) CommandPalette(files, commands, onPick = { cmd ->
            val clean = cmd.removePrefix("/").trim().substringBefore(" ")
            when (clean) {
                "connect" -> { showConnect = true }
                "models" -> { showModels = true }
                "open-file" -> { showQuickOpen = true }
                "workspace-symbols" -> { showWorkspaceSymbols = true }
                "search" -> { if (expandedWorkbench || isChatVisible) { openSidebarTool(DroideRailTool.Search) } else { showSearch = true; workspaceTab = 0 } }
                "problems" -> { if (expandedWorkbench) openBottomTool(DroideBottomTool.Problems) else showProblems = true }
                "settings" -> { showSettings = true }
                "explorer" -> { showSearch = false; if (expandedWorkbench || isChatVisible) { openSidebarTool(DroideRailTool.Files) } else workspaceTab = 0 }
                "editor-recovery-copies" -> runUiCatching(onError = { showActionFailure("Cannot capture current editor", it) }) {
                    editorBridge.captureForSave(); showEditorRecoveryCopies = true
                }
                "retained-work" -> { showRetainedWork = true }
                "source-control" -> { if (expandedWorkbench || isChatVisible) { openSidebarTool(DroideRailTool.Git) } else workspaceTab = 3 }
                "terminal" -> { openBottomTool(DroideBottomTool.Terminal) }
                "debug" -> { if (expandedWorkbench || isChatVisible) { openSidebarTool(DroideRailTool.Run) } else workspaceTab = 5 }
                "browser" -> { if (expandedWorkbench || isChatVisible) { openSidebarTool(DroideRailTool.Web) } else workspaceTab = 4 }
                "tasks" -> { professionalSheet = ProfessionalSheet.TASKS }
                "extensions" -> { if (expandedWorkbench || isChatVisible) { openSidebarTool(DroideRailTool.Extensions) } else workspaceTab = 6 }
                "android-development" -> { showAndroidDevelopment = true }
                "android-build-debug" -> launchUserAction { runAndroidTask("Android: Build Debug APK") { execution.buildDebug() } }
                "android-debug-app" -> launchUserAction { debugAndroidApp() }
                "android-build-release-apk" -> launchUserAction { runAndroidTask("Android: Build Release APK") { execution.buildReleaseApk() } }
                "android-build-release-bundle" -> launchUserAction { runAndroidTask("Android: Build Release Bundle") { execution.buildReleaseBundle() } }
                "android-test" -> launchUserAction { runAndroidTask("Android: Test") { execution.test() } }
                "android-lint" -> launchUserAction { runAndroidTask("Android: Lint") { execution.lint() } }
                "android-install-run" -> launchUserAction { installAndRunAndroid() }
                "android-logcat" -> launchUserAction { loadAndroidLogcat() }
                "android-build-output" -> {
                    if (androidOutputText.isBlank()) launchUserAction { snackbarHostState.showSnackbar("No Android build output yet") }
                    else showAndroidOutput = true
                }
                "build-cancel" -> {
                    val canceled = cancelUserOperation()
                    launchUserAction { snackbarHostState.showSnackbar(if (canceled) "Cancel requested" else "No active Build/Run/Test operation") }
                }
                "save-all" -> launchUserAction {
                    val failure = saveAllDirty()
                    if (failure == null) {
                        snackbarHostState.showSnackbar("All files saved")
                    } else {
                        snackbarHostState.showSnackbar("Save needs attention for ${failure.first}: ${failure.second.message}")
                    }
                }
                "undo" -> launchUserAction { snackbarHostState.showSnackbar(agent.undoLast()) }
                "compact" -> launchUserAction {
                    val cfg = AgentPreferences.load(ctx)
                    if (cfg.model.isBlank()) {
                        snackbarHostState.showSnackbar("Select a validated model first")
                    } else {
                        val resolved = localLlamaModels.resolveAgentConfig(cfg.providerId, cfg.model, cfg.reasoningEffort)
                        snackbarHostState.showSnackbar(agent.compactCurrent(resolved))
                    }
                }
                else -> {
                    val requestAdmittedAtNanos = System.nanoTime()
                    launchUserAction {
                        val resolved = withContext(Dispatchers.IO) { commands.resolve(cmd.removePrefix("/"), "") } ?: cmd
                        val cfg = AgentPreferences.load(ctx)
                        if (cfg.model.isBlank()) {
                            snackbarHostState.showSnackbar("Select a validated model first")
                        } else {
                            val config = localLlamaModels.resolveAgentConfig(cfg.providerId, cfg.model, cfg.reasoningEffort)
                            agent.submitAsync(
                                scope = scope,
                                userText = resolved,
                                config = config,
                                requestAdmittedAtNanos = requestAdmittedAtNanos,
                            )
                        }
                    }
                }
            }
        }, onDismiss = { showPalette = false })
        if (showConnect) {
            ProviderConnectionSheet(connectionBackend = providerConnections, initialProviderId = currentProvider, onConnected = { id, validatedModel ->
                scope.launchUiCatching(onError = { snackbarHostState.showSnackbar("Provider connected but preferences could not be saved: ${it.message ?: it::class.java.simpleName}") }) {
                    ExportWriterAdmission.withSuspendingWriter(files.root, "Provider preference and legacy workspace cleanup") {
                        localLlamaModels.selectRemote(id, validatedModel)
                        ProviderAuthManager.cleanupLegacyWorkspaceSecrets(files.root)
                        currentProvider = id
                    }
                    snackbarHostState.showSnackbar("Connected to ${ProviderRegistry.byId(id).name} · $validatedModel")
                }
            }, onDisconnected = { id -> launchUserAction { snackbarHostState.showSnackbar("Disconnected ${ProviderRegistry.byId(id).name}") } }, onDismiss = { showConnect = false })
        }
        if (showModels) DroideModalBottomSheet(onDismissRequest = { showModels = false }) { dismiss ->
            if (!providerRead.state.ready) {
                Text("Provider preferences are not available yet.", Modifier.padding(16.dp))
                PreferenceReadFailureRow("Provider preferences", providerRead.state.failed, providerRead::retry)
            } else ModelPickerSheet(providerId = currentProvider, connectionBackend = providerConnections,
                onPick = { m -> scope.launchUiCatching(onError = { snackbarHostState.showSnackbar("Could not select model: ${it.message ?: it::class.java.simpleName}") }) { localLlamaModels.selectRemote(currentProvider, m) } },
                onConnectProvider = { showModels = false; showConnect = true }, onDismiss = dismiss)
        }
        if (showSessions) DroideModalBottomSheet(onDismissRequest = { showSessions = false }) { dismiss ->
            val historyAgentBusy by agent.busy.collectAsState()
            AgentHistoryDrawer(
                manager = agent.sessions,
                pluginHistory = agentUiState.pluginHistory,
                selectedBackendId = agentUiState.backendId.value,
                selectionEnabled = !historyAgentBusy && !agentUiState.externalController.busy && agentUiState.preflightJob.value?.isActive != true,
                onPickDroide = { saved -> launchUserAction {
                    check(!agentUiState.externalController.busy && agentUiState.preflightJob.value?.isActive != true) { "Finish the active task or use Stop before opening another conversation." }
                    agent.loadSession(saved, requireIdle = true)
                    agentUiState.backendId.value = "droide"
                    showSessions = false
                    openAgentPane()
                } },
                onPickPlugin = { entry -> launchUserAction {
                    check(!agent.busy.value && !agentUiState.externalController.busy && agentUiState.preflightJob.value?.isActive != true) { "Finish the active task or use Stop before opening another conversation." }
                    val saved = agentUiState.externalController.openSaved(entry.id, entry.agentId)
                    check(!agent.busy.value && agentUiState.preflightJob.value?.isActive != true) { "The active task changed; finish it or use Stop before opening another conversation." }
                    agentUiState.backendId.value = saved.summary.agentId
                    agent.setMode(if (saved.summary.mode == WorkspaceAgentMode.PLAN) AgentMode.PLAN else AgentMode.BUILD)
                    showSessions = false
                    openAgentPane()
                } },
                onDismiss = dismiss,
                onDroideDeleted = { deletedId -> resetDeletedAgentConversation(agent, agentUiState, deletedId) },
                onPluginDeleted = { entry -> agentUiState.externalController.resetDeletedConversation(entry.id, entry.agentId, agentPlugins) },
            )
        }
        if (showSettings) key(activeProject) { SettingsScreen(
            themes = themes, formatters = formatters, wordwrap = wordwrap, hardwareShortcuts = hardwareShortcuts,
            accessoryKeysComfortable = workbenchPrefs.accessoryKeysComfortable, codeStyleDefaults = workbenchPrefs.codeStyleDefaults, androidDevelopment = androidDevelopment,
            codingKeyboardMode = workbenchPrefs.codingKeyboardMode,
            deviceBridge = deviceBridge, githubAccount = githubAccount, projectName = projectName, initialPermissionPolicy = agent.perms.policySnapshot(),
            onWordwrap = { enabled -> WorkbenchPreferences.setWordWrap(ctx, enabled) },
            onHardwareShortcuts = { enabled -> WorkbenchPreferences.setHardwareShortcuts(ctx, enabled) },
            onAccessoryKeysComfortable = { enabled -> WorkbenchPreferences.setAccessoryKeysComfortable(ctx, enabled) },
            onCodingKeyboardMode = { mode -> WorkbenchPreferences.setCodingKeyboardMode(ctx, mode) },
            onDetectIndentation = { enabled -> WorkbenchPreferences.setDetectIndentation(ctx, enabled) },
            onIndentStyle = { style -> WorkbenchPreferences.setIndentStyle(ctx, style) },
            onTabWidth = { value -> WorkbenchPreferences.setTabWidth(ctx, value) },
            onIndentSize = { value -> WorkbenchPreferences.setIndentSize(ctx, value) },
            onContinuationIndent = { value -> WorkbenchPreferences.setContinuationIndent(ctx, value) },
            onTheme = { theme -> withContext(Dispatchers.IO) { themes.select(theme) }; themeTick++ }, onPermissionRuleChange = onPermissionRuleChange,
            onOpenProviders = { showSettings = false; showConnect = true }, onOpenModels = { showSettings = false; showModels = true },
            onOpenExtensions = { showSettings = false; if (expandedWorkbench || isChatVisible) { openSidebarTool(DroideRailTool.Extensions) } else workspaceTab = 6 },
            onOpenFiles = { showSettings = false; showSearch = false; if (expandedWorkbench || isChatVisible) { openSidebarTool(DroideRailTool.Files) } else workspaceTab = 0 },
            onExportWorkspace = if (ExportRolloutPolicy.newExportsEnabled) ({ showSettings = false; exportUi.open("", true) }) else null,
            onExportStatus = exportUi.showStatus?.let { status -> { showSettings = false; status() } },
            onOpenTerminal = { showSettings = false; openBottomTool(DroideBottomTool.Terminal) },
            onOpenGit = { showSettings = false; showSearch = false; if (expandedWorkbench || isChatVisible) { openSidebarTool(DroideRailTool.Git) } else workspaceTab = 3 },
            onOpenRunDebug = { showSettings = false; if (expandedWorkbench || isChatVisible) { openSidebarTool(DroideRailTool.Run) } else workspaceTab = 5 },
            onOpenAgent = { showSettings = false; openAgentPane() }, onOpenAgentSessions = { showSettings = false; showSessions = true },
            onOpenCapabilityHealth = { showSettings = false; professionalSheet = ProfessionalSheet.CAPABILITY_HEALTH },
            mcpHealth = mcpHealth,
            mcpManagement = remember(files, agent) { agent.mcpManagement(files) },
            skillManagement = agent.skillManagement,
            onRetryMcp = { server -> retryMcpForUi(agent, server) },
            onOpenMcpConfig = {
                ensureMcpWorkspaceConfig(files)
                check(open(MCP_WORKSPACE_CONFIG)) { "The editor could not open the configuration. Check workspace availability and open tabs." }
                showSettings = false
            },
            initialCategoryName = settingsInitialCategoryName,
            onDismiss = { showSettings = false; settingsInitialCategoryName = null },
        )
        }
        if (showAndroidDevelopment) DroideModalBottomSheet(onDismissRequest = { showAndroidDevelopment = false }) { dismiss ->
            AndroidDevelopmentSheet(androidDevelopment, deviceBridge, onDismiss = dismiss)
        }
        if (showBilling) DroideModalBottomSheet(onDismissRequest = { showBilling = false }) { dismiss ->
            BillingSheet(tracker = agent.tokens, onDismiss = dismiss)
        }
        if (showAndroidOutput) DroideModalBottomSheet(onDismissRequest = { showAndroidOutput = false }) { dismiss ->
            AndroidOutputSheet(androidOutputTitle, androidOutputText, onDismiss = dismiss)
        }
        pendingCloseFile?.let { path ->
            val doc = editorState.peek(path)
            AlertDialog(
                onDismissRequest = { pendingCloseFile = null },
                title = { Text("Unsaved changes") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { FileIdentityLabel(path, path.substringAfterLast('/'), iconSize = 18.dp); Text("This file has unsaved changes.") } },
                confirmButton = {
                    Button(onClick = {
                        launchUserAction {
                            val report = doc?.let { saves.saveDocument(it) } ?: EditorSaveReport()
                            val finalReport = if (report.ready) {
                                if (doc != null) saves.continueIfReady(doc, expectedPath = path) {
                                    pendingCloseFile = null
                                    closeFileNow(path)
                                } else {
                                    pendingCloseFile = null
                                    closeFileNow(path)
                                    report
                                }
                            } else report
                            finalReport.continuationFailure()?.let { (_, failure) ->
                                snackbarHostState.showSnackbar("Save needs attention: ${failure.message}")
                            }
                        }
                    }) { Text("Save & close") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            doc?.discardUnsaved()
                            pendingCloseFile = null
                            closeFileNow(path)
                        }) { Text("Discard") }
                        OutlinedButton(onClick = { pendingCloseFile = null }) { Text("Cancel") }
                    }
                },
            )
        }
        pendingProjectSwitch?.let { targetId ->
            val dirty = editorState.dirtyDocuments()
            val targetName = projects.firstOrNull { it.id == targetId }?.name ?: "project"
            AlertDialog(
                onDismissRequest = { pendingProjectSwitch = null },
                title = { Text("Unsaved changes") },
                text = { Text("${dirty.size} file(s) have unsaved changes. Save them before switching to $targetName?") },
                confirmButton = {
                    Button(onClick = {
                        launchUserAction {
                            val failure = saveAllDirty()
                            if (failure == null) {
                                pendingProjectSwitch = null
                                onProjectSwitch(targetId)
                            } else snackbarHostState.showSnackbar("Save needs attention for ${failure.first}: ${failure.second.message}")
                        }
                    }) { Text("Save & switch") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            launchUserAction {
                                editorState.dirtyDocuments().forEach { it.discardUnsaved() }
                                persistEditorState()
                                pendingProjectSwitch = null
                                onProjectSwitch(targetId)
                            }
                        }) { Text("Discard & switch") }
                        OutlinedButton(onClick = { pendingProjectSwitch = null }) { Text("Cancel") }
                    }
                },
            )
        }
        if (pendingSafPick) {
            val dirty = editorState.dirtyDocuments()
            AlertDialog(
                onDismissRequest = { pendingSafPick = false },
                title = { Text("Unsaved changes") },
                text = { Text("${dirty.size} file(s) have unsaved changes. Save them before opening another folder?") },
                confirmButton = {
                    Button(onClick = {
                        launchUserAction {
                            val failure = saveAllDirty()
                            if (failure == null) {
                                pendingSafPick = false
                                onPickSaf()
                            } else snackbarHostState.showSnackbar("Save needs attention for ${failure.first}: ${failure.second.message}")
                        }
                    }) { Text("Save & open") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            launchUserAction {
                                editorState.dirtyDocuments().forEach { it.discardUnsaved() }
                                persistEditorState()
                                pendingSafPick = false
                                onPickSaf()
                            }
                        }) { Text("Discard & open") }
                        OutlinedButton(onClick = { pendingSafPick = false }) { Text("Cancel") }
                    }
                },
            )
        }
    }
}
