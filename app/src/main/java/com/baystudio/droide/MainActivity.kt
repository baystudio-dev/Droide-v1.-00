package com.baystudio.droide

import com.baystudio.droide.core.deliverWorkspaceFailure

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import com.baystudio.droide.core.ApplicationWorkAdmission
import com.baystudio.droide.core.ApplicationExitSequence
import com.baystudio.droide.core.DevelopmentForegroundService
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import com.baystudio.droide.core.EditorRecoveryBridge
import com.baystudio.droide.core.SafMirrorPhase
import com.baystudio.droide.core.SafMirrorProgress
import com.baystudio.droide.core.runSuspendCatching
import com.baystudio.droide.ui.IdeScreen
import com.baystudio.droide.ui.DroideTheme
import com.baystudio.droide.ui.DroideWindowInsetsHost
import com.baystudio.droide.ui.ProjectCatalogRecoveryEntry
import com.baystudio.droide.ui.WorkspaceLoadingScreen
import com.baystudio.droide.ui.WorkspaceStoppedScreen
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

class MainActivity : ComponentActivity() {
    private lateinit var workspaceModel: WorkspaceViewModel
    private val editorRecoveryBridge = EditorRecoveryBridge()
    private val folderImportProgress = MutableStateFlow<SafMirrorProgress?>(null)
    private var folderImportJob: Job? = null
    private var hidePortraitEditorStatusBar by mutableStateOf(false)
    private var reviewExitDrafts by mutableStateOf(false)
    private val appOwner get() = application as DroideApplication

    private val notificationPermission = activityResultRegistry.register(
        "droide-notification-permission", this, ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && ApplicationWorkAdmission.isOpen) DevelopmentForegroundService.ensureSession(this)
        else if (!granted) Toast.makeText(this, "Allow Droide notifications to use Wake Lock and Exit from the notification shade.", Toast.LENGTH_LONG).show()
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }.onFailure { Toast.makeText(this, "Notification settings are unavailable", Toast.LENGTH_LONG).show() }
    }

    private val terminalHistoryExport = com.baystudio.droide.core.TerminalHistoryExportRequest()
    private val terminalHistoryPickers = mutableMapOf<String, androidx.activity.result.ActivityResultLauncher<String>>()
    private fun terminalHistoryPicker(requestId: String): androidx.activity.result.ActivityResultLauncher<String> {
        val launcher = activityResultRegistry.register("droide-terminal-history-$requestId",
            ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            terminalHistoryPickers.remove(requestId)?.unregister()
            completeTerminalHistoryPicker(requestId, uri)
        }

        if (terminalHistoryExport.pickerOutstanding == requestId) terminalHistoryPickers[requestId] = launcher
        else launcher.unregister()
        return launcher
    }
    private fun completeTerminalHistoryPicker(requestId: String, uri: Uri?) {
        val epoch = ApplicationWorkAdmission.currentEpoch.takeIf { ApplicationWorkAdmission.isOpen }
        val result = terminalHistoryExport.pickerResult(requestId, uri != null, epoch)
        result.message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        val ticket = result.ticket
        if (uri != null && ticket != null) {
            lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val owner = kotlinx.coroutines.currentCoroutineContext()
                        fun checkOwner() {
                            owner.ensureActive()
                            check(ApplicationWorkAdmission.isCurrentOpen(ticket.epoch)) { "Export owner retired" }
                        }
                        com.baystudio.droide.core.TerminalHistoryExportWriter.write(ticket.history,
                            open = { contentResolver.openOutputStream(uri, "wt") }, checkOwner = ::checkOwner)
                    }
                    Toast.makeText(this@MainActivity, "Retained terminal snapshot exported", Toast.LENGTH_LONG).show()
                } catch (cancelled: CancellationException) {

                    if (!isFinishing) Toast.makeText(this@MainActivity,
                        "Terminal export interrupted. The chosen file may be incomplete.", Toast.LENGTH_LONG).show()
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity,
                        "Terminal export failed. The chosen file may be incomplete; check it before retrying.", Toast.LENGTH_LONG).show()
                } finally {
                    terminalHistoryExport.finish(ticket)
                }
            }
        }
    }

    /** Explicit user export of immutable retained text; never recaptures the currently selected tab. */
    internal fun exportTerminalHistory(history: com.baystudio.droide.core.TerminalRetainedHistory) {
        val epoch = ApplicationWorkAdmission.currentEpoch
        ApplicationWorkAdmission.withOpen(epoch) {
            val previousRequest = terminalHistoryExport.pickerOutstanding
            if (terminalHistoryExport.retireUnavailableSnapshot()) {
                terminalHistoryPickers.remove(previousRequest)?.unregister()
                Toast.makeText(this, "The previous snapshot was lost. Starting a new export; its old destination may be empty.", Toast.LENGTH_LONG).show()
            }
            val ticket = terminalHistoryExport.begin(history, epoch)
            try { terminalHistoryPicker(ticket.requestId).launch("droide-terminal-history-${System.currentTimeMillis()}.txt") }
            catch (failure: Exception) {
                terminalHistoryPickers.remove(ticket.requestId)?.unregister()
                terminalHistoryExport.launchFailed(ticket)
                throw failure
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("terminal-history-picker-pending", terminalHistoryExport.pickerOutstanding)
        outState.putBoolean("terminal-history-write-active", terminalHistoryExport.writeActive)
        super.onSaveInstanceState(outState)
    }

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null && ApplicationWorkAdmission.isOpen) {
            val permission = runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            if (permission.isFailure) {
                Toast.makeText(this, "Cannot persist folder access: ${permission.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            if (folderImportJob?.isActive == true) return@registerForActivityResult
            folderImportProgress.value = SafMirrorProgress(SafMirrorPhase.PREPARING)
            folderImportJob = lifecycleScope.launch {
                try {
                    workspaceModel.coordinator.addSafProject(
                        uri = uri,
                        onProgress = { progress -> folderImportProgress.value = progress },
                    )
                } catch (_: CancellationException) {

                } catch (failure: Throwable) {
                    Toast.makeText(this@MainActivity, "Cannot open project: ${failure.message}", Toast.LENGTH_LONG).show()
                } finally {
                    folderImportProgress.value = null
                    folderImportJob = null
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        terminalHistoryExport.restoreOutstanding(savedInstanceState?.getString("terminal-history-picker-pending"))
        terminalHistoryExport.pickerOutstanding?.let(::terminalHistoryPicker)
        if (savedInstanceState?.getBoolean("terminal-history-write-active") == true) {
            Toast.makeText(this, "Previous terminal export result is unavailable. Check the chosen file before retrying.", Toast.LENGTH_LONG).show()
        }
        // Stable for the Activity lifetime. Compose owns main-window safe space on every supported API,
        // rather than changing decor fitting when the editor hides a bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {

            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        appOwner.attachUserInterface(this)
        if (ApplicationWorkAdmission.isOpen) lifecycleScope.coroutineContext[Job]?.let { appOwner.uiWorkOwner?.register(it) }
        workspaceModel = ViewModelProvider(this)[WorkspaceViewModel::class.java]
        applyDroideOrientationStatusBar(window, resources.configuration.orientation, hidePortraitEditorStatusBar)
        handleGitHubOAuthCallback(intent)
        handleExitIntent(intent)
        handleExplorerExportIntent(intent)

        setContent {
            val epoch by appOwner.interfaceEpoch.collectAsState()
            val exit by appOwner.exitState.collectAsState()
            val releaseUi by appOwner.releaseWorkspaceUi.collectAsState()
            val closeUi by appOwner.closeWorkspaceUi.collectAsState()
            val startFailure by appOwner.startupFailure.collectAsState()
            var finishWhenExitCompletes by remember { mutableStateOf(appOwner.mayPresentWorkspace(this@MainActivity) && !exit.completed) }
            LaunchedEffect(exit.started, exit.completed, closeUi) {
                if (exit.started && !exit.completed && appOwner.mayPresentWorkspace(this@MainActivity)) finishWhenExitCompletes = true
                if (closeUi && appOwner.shouldCloseUserInterface(this@MainActivity)) finishAndRemoveTask()
            }
            if (epoch == null) {
                DroideTheme { Surface(Modifier.fillMaxSize()) {
                    DroideWindowInsetsHost(
                        statusBarRequestedHidden = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
                    ) {
                        WorkspaceStoppedScreen(
                            canOpen = (!exit.started || exit.completed) && !(exit.completed && finishWhenExitCompletes),
                            failure = startFailure,
                            onOpen = open@{
                                // The retiring Activity owns a cancelled lifecycle scope; only a fresh stopped Activity may
                                // reopen after this one finishes.
                                if (exit.completed && finishWhenExitCompletes) return@open
                                try {
                                    appOwner.openFromUser()

                                    finishWhenExitCompletes = true
                                    appOwner.attachUserInterface(this@MainActivity)
                                    lifecycleScope.coroutineContext[Job]?.let { appOwner.uiWorkOwner?.register(it) }
                                }
                                catch (failure: Exception) { appOwner.startupFailure.value = failure.message?.take(300) ?: "Could not open Droide" }
                            },
                        )
                    }
                } }
                return@setContent
            }
            val runtime by workspaceModel.coordinator.runtime.collectAsState()
            val error by workspaceModel.initError.collectAsState()
            val runtimeFailure by workspaceModel.runtimeFailure.collectAsState()
            LaunchedEffect(runtimeFailure) {
                runtimeFailure?.let { event ->
                    // Report the failed operation without closing the active editor/terminal.
                    event.deliverWorkspaceFailure(
                        showMessage = { message -> Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() },
                        consume = workspaceModel::consumeRuntimeFailure,
                    )
                }
            }
            val importingFolder by folderImportProgress.collectAsState()
            DroideTheme {
                Surface(Modifier.fillMaxSize()) {
                    val orientation = LocalConfiguration.current.orientation
                    DroideWindowInsetsHost(
                        statusBarRequestedHidden = orientation == Configuration.ORIENTATION_LANDSCAPE ||
                            (orientation == Configuration.ORIENTATION_PORTRAIT && hidePortraitEditorStatusBar),
                        reclaimTopCutout = orientation == Configuration.ORIENTATION_PORTRAIT && hidePortraitEditorStatusBar,
                    ) {
                    val rt = runtime
                    if (releaseUi || !appOwner.mayPresentWorkspace(this@MainActivity)) {
                        ExitStatusContent(exit, appOwner::retryExit, onReviewDrafts = { if (appOwner.exitDraftUi != null) reviewExitDrafts = true })
                    } else if (rt == null) {
                        Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            if (error == null) {
                                // Subscribe only while no runtime exists. A ready/warm workspace skips welcome entirely;
                                // readiness never waits for UI animation.
                                val startup by workspaceModel.coordinator.startup.collectAsState()
                                WorkspaceLoadingScreen(startup.phase)
                            } else Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                WorkspaceStartupFailureContent(
                                    error = error ?: "Unknown error",
                                    onRetry = workspaceModel::initializeWorkspace,
                                    onTemporaryBuiltins = workspaceModel::initializeWithBuiltinsTemporarily,
                                )
                                ProjectCatalogRecoveryEntry(workspaceModel.coordinator.projects)
                            }
                        }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            if (rt.startupMode == com.baystudio.droide.core.WorkspaceStartupMode.TEMPORARY_BUILTINS) {
                                Text(
                                    "Temporary built-in startup. " + com.baystudio.droide.core.TEMPORARY_COMPONENTS_UNAVAILABLE +
                                        " Normal startup returns after the app process restarts.",
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Box(Modifier.weight(1f)) {
                        // Do not auto-remount a saved native editor/Web panel from the preceding process.

                        key(rt.project.id, rt.files.root.canonicalPath, workspaceModel.startupRecovery.deferEditorReopen,
                            workspaceModel.startupRecovery.recoveryGeneration) {
                            CompositionLocalProvider(com.baystudio.droide.ui.LocalExitUiWorkOwner provides appOwner.uiWorkOwner) {
                            IdeScreen(
                                files = rt.files,
                                imports = rt.imports,
                                exports = rt.exports,
                                exportWorkspaceName = rt.project.name,
                                documents = rt.documentAuthority,
                                terminalManager = rt.terminals,
                                git = rt.git,
                                lsp = rt.lsp,
                                debugger = rt.debugger,
                                agent = rt.agent,
                                agentUiState = workspaceModel.agentUiStateFor(rt),
                                agentBrowser = rt.agentBrowser,
                                agentPlugins = rt.agentPlugins,
                                githubAccount = workspaceModel.coordinator.githubAccount,
                                providerConnections = workspaceModel.coordinator.providerConnections,
                                localLlamaModels = workspaceModel.coordinator.localLlamaAgentModels,
                                androidDevelopment = rt.androidDevelopment,
                                extensions = rt.extensions,
                                execution = rt.execution,
                                deviceBridge = workspaceModel.coordinator.deviceBridge,
                                approvals = workspaceModel.coordinator.approvals(),
                                projectManager = workspaceModel.coordinator.projects,
                                recoveryBridge = editorRecoveryBridge,
                                recoveryStore = workspaceModel.editorRecoveryStoreFor(rt),
                                editorState = workspaceModel.editorStateFor(rt),
                                explorerState = workspaceModel.explorerStateFor(rt),
                                explorerRefresh = workspaceModel.explorerRefreshFor(rt),
                                startupRecovery = workspaceModel.startupRecovery,
                                onNotificationSettings = ::openNotificationSettings,
                                onPortraitEditorStatusBarHiddenChanged = { hidden ->
                                    if (hidePortraitEditorStatusBar != hidden) {
                                        hidePortraitEditorStatusBar = hidden
                                        applyDroideOrientationStatusBar(window, resources.configuration.orientation, hidden)
                                    }
                                },
                                onPickSaf = {
                                    runCatching { pickTree.launch(null) }.onFailure { failure ->
                                        Toast.makeText(this@MainActivity, "Cannot open folder picker: ${failure.message ?: "unavailable"}", Toast.LENGTH_LONG).show()
                                    }
                                },
                                safLinked = rt.safMirror != null,
                                onReconcileSaf = { workspaceModel.coordinator.reconcileActiveSaf() },
                                onProjectSwitch = { id ->
                                    lifecycleScope.launch {
                                        runSuspendCatching { workspaceModel.coordinator.switchProject(id) }
                                            .onFailure { Toast.makeText(this@MainActivity, "Cannot switch project: ${it.message}", Toast.LENGTH_LONG).show() }
                                    }
                                },
                                onProjectCreate = { name, templateId ->
                                    workspaceModel.coordinator.createProjectFromTemplateAndSwitch(name, templateId)
                                },
                                onPermissionRuleChange = { action, effect -> workspaceModel.coordinator.updateActivePermissionSetting(rt.project.id, action, effect) },
                            )
                            }
                        }
                            }
                        }
                    }
                    if (!exit.started) importingFolder?.let { progress ->
                        FolderImportProgressDialog(
                            progress = progress,
                            onCancel = {
                                if (progress.cancellable) {
                                    folderImportProgress.value = progress.copy(phase = SafMirrorPhase.CANCELLING)
                                    folderImportJob?.cancel()
                                }
                            },
                        )
                    }
                    }
                    if (reviewExitDrafts) appOwner.exitDraftUi?.let { draftState ->
                        com.baystudio.droide.ui.RecoveredExitDraftsDialog(draftState) { reviewExitDrafts = false }
                    }

                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGitHubOAuthCallback(intent)
        handleExitIntent(intent)
        handleExplorerExportIntent(intent)
    }

    override fun onDestroy() {
        terminalHistoryPickers.values.toList().forEach { it.unregister() }
        terminalHistoryPickers.clear()
        appOwner.beforeActivityDestroyed(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (ApplicationWorkAdmission.isOpen && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val preferences = getSharedPreferences("notification-access", MODE_PRIVATE)
            if (!preferences.getBoolean("requested", false)) {
                preferences.edit().putBoolean("requested", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun handleExitIntent(intent: Intent?) {
        if (intent?.action != ApplicationWorkAdmission.ACTION_REQUEST_EXIT) return
        val epoch = intent.getStringExtra(ApplicationWorkAdmission.EXTRA_EXIT_EPOCH).orEmpty()
        intent.action = null
        if (ApplicationWorkAdmission.isCurrentOpen(epoch)) appOwner.requestExit(epoch, this)
    }

    private fun handleExplorerExportIntent(intent: Intent?) {
        if (intent?.action != com.baystudio.droide.core.ExportForegroundService.ACTION_SHOW) return
        val id = intent.getStringExtra(com.baystudio.droide.core.ExportForegroundService.EXTRA_ID).orEmpty()
        val epoch = intent.getStringExtra(com.baystudio.droide.core.ExportForegroundService.EXTRA_EPOCH).orEmpty()
        // Consume one-shot navigation only. A stale notification never reopens work admission or
        // retargets an export to the currently selected project.
        intent.action = null
        if (id.isNotEmpty() && ApplicationWorkAdmission.isCurrentOpen(epoch)) {
            com.baystudio.droide.core.ExportController.requestOpen(id, epoch)
        }
    }

    private fun handleGitHubOAuthCallback(intent: Intent?) {
        if (intent?.action != GitHubOAuthCallbackActivity.ACTION_GITHUB_OAUTH_CALLBACK) return
        if (!ApplicationWorkAdmission.isOpen) { intent.action = null; intent.data = null; return }
        val callback = intent.data?.toString() ?: return
        // Activity recreation must not replay a one-shot authorization code after GitHubAccountManager
        // has already claimed it.

        intent.action = null
        intent.data = null
        lifecycleScope.launch { workspaceModel.coordinator.githubAccount.handleCallback(callback) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {

            applyDroideOrientationStatusBar(window, resources.configuration.orientation, hidePortraitEditorStatusBar)
        }
    }

    override fun onStop() {

        try {
            if (ApplicationWorkAdmission.isOpen && ::workspaceModel.isInitialized) {
                editorRecoveryBridge.captureRequest()?.let(workspaceModel::persistEditorRecovery)
                workspaceModel.persistTerminalRecovery()
            }
        } catch (error: Exception) {
            Log.w("DroideLifecycle", "Best-effort onStop recovery skipped", error)
        } finally {
            super.onStop()
        }
    }
}

@Composable
private fun FolderImportProgressDialog(
    progress: SafMirrorProgress,
    onCancel: () -> Unit,
) {
    val phaseLabel = when (progress.phase) {
        SafMirrorPhase.PREPARING -> "Preparing folder…"
        SafMirrorPhase.COPYING_EXTERNAL -> "Importing folder…"
        SafMirrorPhase.SCANNING_LOCAL -> "Checking workspace…"
        SafMirrorPhase.RESOLVING -> "Resolving changes…"
        SafMirrorPhase.CANCELLING -> "Cancelling safely…"
        SafMirrorPhase.APPLYING -> "Applying changes safely…"
        SafMirrorPhase.FINALIZING -> "Finishing safely…"
    }
    val detail = when (progress.phase) {
        SafMirrorPhase.COPYING_EXTERNAL -> "${progress.filesProcessed} files · ${formatImportBytes(progress.bytesProcessed)} copied"
        SafMirrorPhase.SCANNING_LOCAL -> "${progress.filesProcessed} files · ${formatImportBytes(progress.bytesProcessed)} checked"
        SafMirrorPhase.RESOLVING,
        SafMirrorPhase.APPLYING,
        SafMirrorPhase.FINALIZING -> "${progress.filesProcessed} files · ${formatImportBytes(progress.bytesProcessed)} prepared"
        else -> "Droide is keeping the workspace responsive while this runs."
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Opening folder") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(phaseLabel, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                progress.currentPath?.let { path ->
                    Text(
                        path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (progress.cancellable) TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

private fun formatImportBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> java.lang.String.format(java.util.Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> java.lang.String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> java.lang.String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Actions are explicit and displayed only while no runtime exists and startup has failed. */
@Composable
internal fun WorkspaceStartupFailureContent(
    error: String,
    onRetry: () -> Unit,
    onTemporaryBuiltins: () -> Unit,
) {
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Cannot initialize workspace", style = MaterialTheme.typography.titleMedium)
        Text(error, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onRetry) { Text("Retry normal startup") }
        Text(
            "You can open the saved project temporarily with built-in components. " +
                "Installed extensions, language servers and automatic tool setup will be unavailable. " +
                "Extensions and project data will not be deleted. Normal startup returns after the app process restarts.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onTemporaryBuiltins) { Text("Open temporarily with built-ins") }
    }
}

@Composable
private fun ExitStatusContent(state: ApplicationExitSequence.State, retry: () -> Unit, onReviewDrafts: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        Text(state.stage.ifBlank { "Preserving drafts and stopping work" }, style = MaterialTheme.typography.titleMedium)
        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.failure ?: "Saving drafts. Droide will close automatically.")
        if (!state.running && !state.completed) {
            Button(onClick = retry) { Text("Retry Exit") }
            TextButton(onClick = onReviewDrafts) { Text("Review saved chat drafts") }
        }
    }
}
