package com.baystudio.droide.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.BuildConfig
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsScope { USER, WORKSPACE }

private enum class SettingsCategory(val icon: ImageVector) {
    GENERAL(Icons.Default.Settings),
    APPEARANCE(Icons.Default.Palette),
    EDITOR(Icons.Default.Edit),
    LANGUAGES(Icons.Default.Code),
    FILES(Icons.Default.Folder),
    TERMINAL(Icons.Default.Terminal),
    GIT(Icons.Default.AccountTree),
    RUN(Icons.Default.PlayArrow),
    AI(Icons.Default.AutoAwesome),
    PROVIDERS(Icons.Default.Cloud),
    PERMISSIONS(Icons.Default.AdminPanelSettings),
    TOOLS(Icons.Default.Build),
    PLUGINS(Icons.Default.Extension),
    SKILLS(Icons.Default.AutoStories),
    KEYBOARD(Icons.Default.Keyboard),
    SECURITY(Icons.Default.Security),
    PERFORMANCE(Icons.Default.Speed),
    NOTIFICATIONS(Icons.Default.Notifications),
    ACCOUNTS(Icons.Default.Person),
    ABOUT(Icons.Default.Info);

    private val entry get() = SettingsDiscovery.entries.getValue(name)
    val title get() = entry.title
    val subtitle get() = entry.subtitle
}

private enum class LegalRoute { OPEN_SOURCE, THIRD_PARTY, BRAND_ASSETS, DROIDE_LICENSE, PRIVACY, TERMS, UPDATES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themes: ThemeManager,
    formatters: FormatterManager,
    wordwrap: Boolean,
    hardwareShortcuts: Boolean,
    accessoryKeysComfortable: Boolean,
    codingKeyboardMode: CodingKeyboardMode,
    codeStyleDefaults: CodeStyleDefaults,
    androidDevelopment: AndroidDevelopmentManager,
    deviceBridge: DeviceBridgeManager,
    githubAccount: GitHubAccountManager,
    projectName: String,
    initialPermissionPolicy: PermissionPolicyDocument,
    onWordwrap: suspend (Boolean) -> Unit,
    onHardwareShortcuts: suspend (Boolean) -> Unit,
    onAccessoryKeysComfortable: suspend (Boolean) -> Unit,
    onCodingKeyboardMode: suspend (CodingKeyboardMode) -> Unit,
    onDetectIndentation: suspend (Boolean) -> Unit,
    onIndentStyle: suspend (IndentStyle) -> Unit,
    onTabWidth: suspend (Int) -> Unit,
    onIndentSize: suspend (Int) -> Unit,
    onContinuationIndent: suspend (Int) -> Unit,
    onTheme: suspend (String) -> Unit,
    onPermissionRuleChange: suspend (String, PermEffect) -> PermissionPolicyDocument,
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenRunDebug: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAgentSessions: () -> Unit,
    onOpenCapabilityHealth: () -> Unit,
    mcpHealth: List<McpHealthSnapshot>,
    onRetryMcp: suspend (String) -> String,
    onOpenMcpConfig: suspend () -> Unit,
    mcpManagement: McpManagement? = null,
    skillManagement: SkillManagement? = null,
    onExportWorkspace: (() -> Unit)? = null,
    onExportStatus: (() -> Unit)? = null,
    initialCategoryName: String? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val bugReport = rememberSettingsBugReportModel()
    val scope = rememberWorkspaceCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val officialLinkActions = remember(context, scope, snackbar, clipboard) {
        SettingsOfficialLinkActions(scope,
            dispatch = { link -> SettingsOfficialLinkLauncher.open(context, link) },
            reportFailure = { link, message ->
                if (snackbar.showSnackbar(message, actionLabel = "Copy address", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                    val copied = try {
                        clipboard.setText(AnnotatedString(link.targetLabel))
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { false }
                    snackbar.showSnackbar(if (copied) "Address copied" else "Could not copy the address. You can read it in Settings.")
                }
            })
    }
    DisposableEffect(officialLinkActions) { onDispose { officialLinkActions.close() } }
    var selected by rememberSaveable(initialCategoryName) {
        mutableStateOf(initialCategoryName?.let { name -> SettingsCategory.entries.firstOrNull { it.name == name } })
    }
    var settingsScope by rememberSaveable { mutableStateOf(if (initialCategoryName == "TOOLS") SettingsScope.WORKSPACE else SettingsScope.USER) }
    var query by rememberSaveable { mutableStateOf("") }
    var legalRoute by rememberSaveable { mutableStateOf<LegalRoute?>(null) }
    var selectedLicenseId by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionPolicy by remember(projectName) { mutableStateOf(initialPermissionPolicy) }
    val permissionPending = remember(projectName) { mutableStateMapOf<String, Boolean>() }
    fun changePermission(action: String, effect: PermEffect) {
        if (permissionPending[action] == true) return
        permissionPending[action] = true
        scope.launchUiCatching(onError = { snackbar.showSnackbar("Permission update failed: ${it.message ?: "unknown error"}") }) {
            val saved = try { onPermissionRuleChange(action, effect) }
                finally { permissionPending.remove(action) }
            permissionPolicy = saved
            snackbar.showSnackbar("Permission policy saved")
        }
    }
    var actionPending by remember { mutableStateOf(emptySet<String>()) }
    val settingsActions = remember(scope, snackbar) {
        SettingsActionRunner(scope,
            pendingChanged = { actionPending = it },
            reportFailure = { label, failure -> snackbar.showSnackbar("$label: ${failure.message ?: failure::class.java.simpleName}") },
            reportResult = { message -> snackbar.showSnackbar(message) })
    }
    fun savePreference(key: String, label: String, action: suspend () -> Unit) {
        settingsActions.launch(key, label) { action(); null }
    }
    fun retryMcp(server: String) { settingsActions.launch("mcp:retry:$server", "MCP retry failed") { onRetryMcp(server) } }
    fun openMcpConfig() { settingsActions.launch("mcp:config", "Could not open MCP configuration") { onOpenMcpConfig(); null } }
    var themeSaving by remember(themes) { mutableStateOf(false) }
    fun changeTheme(name: String) {
        if (themeSaving) return
        themeSaving = true
        scope.launchUiCatching(onError = { snackbar.showSnackbar("Could not save theme: ${it.message ?: "unknown error"}") }) {
            try { onTheme(name) } finally { themeSaving = false }
        }
    }
    fun openNotificationSettings() {
        runUiCatching(onError = { failure ->
            scope.launch { snackbar.showSnackbar("Could not open Android notification settings: ${failure.message ?: "unavailable on this device"}") }
        }) {
            context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
    var fmt by remember(formatters) { mutableStateOf(formatters.isEnabled()) }
    var formatterSaving by remember(formatters) { mutableStateOf(false) }
    var keyboardModeSaving by remember { mutableStateOf(false) }
    fun changeCodingKeyboardMode(mode: CodingKeyboardMode) {
        if (keyboardModeSaving || mode == codingKeyboardMode) return
        keyboardModeSaving = true
        scope.launchUiCatching(onError = {
            snackbar.showSnackbar("Could not save keyboard setting: ${it.message ?: "unknown error"}")
        }) {
            try {
                onCodingKeyboardMode(mode)
            } finally { keyboardModeSaving = false }
        }
    }
    fun changeFormatter(enabled: Boolean) {
        if (formatterSaving) return
        formatterSaving = true
        scope.launchUiCatching(onError = { snackbar.showSnackbar("Could not save formatter setting: ${it.message}") }) {
            try {
                formatters.setEnabled(enabled)
                fmt = formatters.isEnabled()
            } finally { formatterSaving = false }
        }
    }
    val preferenceRead = rememberPreferenceReadRecovery(context, AgentPreferences.Snapshot("pollinations", "", "Unavailable", null)) { AgentPreferences.observe(context) }
    val agentPrefs = preferenceRead.state.value
    val githubAccountState by githubAccount.snapshot.collectAsState()
    val connectGitHub: () -> Unit = {
        githubAccount.beginAuthorization().fold(
            onSuccess = { authorizationUrl ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl))
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                    )
                }.onFailure { failure ->
                    githubAccount.browserLaunchFailed("Could not open a browser for GitHub sign-in")
                    scope.launch { snackbar.showSnackbar("Could not open GitHub sign-in: ${failure.message ?: "no browser available"}") }
                }
            },
            onFailure = { failure ->
                scope.launch { snackbar.showSnackbar(failure.message ?: "GitHub sign-in is unavailable") }
            },
        )
    }
    val manageGitHubRepositoryAccess: () -> Unit = {
        val accessUrl = githubAccount.repositoryAccessUrl()
        if (accessUrl == null) {
            scope.launch { snackbar.showSnackbar("This build has no GitHub App installation URL configured") }
        } else {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(accessUrl))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                )
            }.onFailure { failure ->
                scope.launch { snackbar.showSnackbar("Could not open GitHub repository access: ${failure.message ?: "no browser available"}") }
            }
        }
    }
    val disconnectGitHub: () -> Unit = {
        scope.launch {
            githubAccount.disconnect().fold(
                onSuccess = { outcome ->
                    snackbar.showSnackbar(
                        if (outcome.remoteRevoked) "Droide's GitHub authorization was revoked"
                        else "Local GitHub connection was removed"
                    )
                },
                onFailure = { failure -> snackbar.showSnackbar("GitHub disconnect needs attention: ${failure.message ?: "unknown error"}") },
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        

        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
        val compactHeight = maxHeight < 480.dp * fontScale
        val twoPane = maxWidth >= 720.dp * fontScale && !compactHeight
        val showSearchField = twoPane || selected == null || query.isNotBlank()
        fun navigateBack() {
            when {
                selectedLicenseId != null -> selectedLicenseId = null
                legalRoute != null -> legalRoute = null
                !twoPane && selected != null -> selected = null
                else -> onDismiss()
            }
        }
        BackHandler(onBack = ::navigateBack)

        Scaffold(
            modifier = Modifier.fillMaxSize().droideContentMotion("settings")
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            snackbarHost = { DismissibleSnackbarHost(snackbar) },
            topBar = {
                Surface(tonalElevation = 2.dp) {
                    // A large font or keyboard may make the controls taller than the window.
                    // Bound and scroll the header so the settings list always retains room.
                    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight * .5f)
                        .verticalScroll(rememberScrollState()).statusBarsPadding()) {
                        PreferenceReadFailureRow("Provider preferences", preferenceRead.state.failed, preferenceRead::retry)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = if (compactHeight) 48.dp else 56.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!twoPane && selected != null) {
                                IconButton(onClick = ::navigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                            } else {
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (!twoPane && selected != null) selected!!.title else "Settings",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close settings") }
                        }
                        if (showSearchField) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compactHeight) 2.dp else 4.dp),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") } }) else null,
                                placeholder = { Text("Search settings") },
                            )
                        }
                        ScopeSelector(settingsScope, compact = compactHeight, onSelected = { settingsScope = it })
                    }
                }
            },
        ) { padding ->
            val filtered = remember(query) {
                val q = query.trim().lowercase()
                if (q.isBlank()) SettingsCategory.entries else SettingsCategory.entries.filter {
                    SettingsDiscovery.matches(it.name, q)
                }
            }
            if (twoPane) {
                Row(Modifier.fillMaxSize().padding(padding)) {
                    SettingsCategoryList(
                        categories = filtered,
                        selected = selected,
                        onSelect = { selected = it; legalRoute = null; selectedLicenseId = null },
                        modifier = Modifier.widthIn(min = 220.dp, max = 310.dp).fillMaxHeight(),
                    )
                    VerticalDivider()
                    SettingsDetail(
                        category = selected ?: SettingsCategory.GENERAL,
                        settingsScope = settingsScope,
                        themes = themes,
                        formattersEnabled = fmt,
                        formatterSaving = formatterSaving,
                        wordwrap = wordwrap,
                        hardwareShortcuts = hardwareShortcuts,
                        accessoryKeysComfortable = accessoryKeysComfortable,
                        codingKeyboardMode = codingKeyboardMode,
                        keyboardModeSaving = keyboardModeSaving,
                        codeStyleDefaults = codeStyleDefaults,
                        androidDevelopment = androidDevelopment,
                        deviceBridge = deviceBridge,
                        projectName = projectName,
                        agentPrefs = agentPrefs,
                        agentPreferencesReady = preferenceRead.state.ready,
                        githubAccountState = githubAccountState,
                        githubRepositoryAccessAvailable = githubAccount.repositoryAccessUrl() != null,
                        permissionPolicy = permissionPolicy,
                        legalRoute = legalRoute,
                        selectedLicenseId = selectedLicenseId,
                        showNestedBack = twoPane,
                        onFormatter = ::changeFormatter,
                        onWordwrap = { value -> savePreference("wordwrap", "Could not save word wrap") { onWordwrap(value) } },
                        onHardwareShortcuts = { value -> savePreference("hardware-shortcuts", "Could not save hardware shortcuts") { onHardwareShortcuts(value) } },
                        onAccessoryKeysComfortable = { value -> savePreference("comfortable-keys", "Could not save accessory keys") { onAccessoryKeysComfortable(value) } },
                        onCodingKeyboardMode = ::changeCodingKeyboardMode,
                        onDetectIndentation = { value -> savePreference("detect-indentation", "Could not save indent detection") { onDetectIndentation(value) } },
                        onIndentStyle = { value -> savePreference("indent-style", "Could not save indent style") { onIndentStyle(value) } },
                        onTabWidth = { value -> savePreference("tab-width", "Could not save tab width") { onTabWidth(value) } },
                        onIndentSize = { value -> savePreference("indent-size", "Could not save indent size") { onIndentSize(value) } },
                        onContinuationIndent = { value -> savePreference("continuation-indent", "Could not save continuation indent") { onContinuationIndent(value) } },
                        onTheme = ::changeTheme,
                        themeSaving = themeSaving,
                        onOpenNotificationSettings = ::openNotificationSettings,
                        onPermission = ::changePermission,
                        permissionPending = permissionPending.keys.toSet(),
                        actionPending = actionPending,
                        onLegalRoute = { legalRoute = it; selectedLicenseId = null },
                        onLicense = { selectedLicenseId = it },
                        onLegalBack = ::navigateBack,
                        onOpenOfficialLink = officialLinkActions::open,
                        onReportBug = bugReport::open,
                        onOpenProviders = onOpenProviders,
                        onOpenModels = onOpenModels,
                        onConnectGitHub = connectGitHub,
                        onRetryGitHubState = githubAccount::retryStoredState,
                        onManageGitHubRepositoryAccess = manageGitHubRepositoryAccess,
                        onDisconnectGitHub = disconnectGitHub,
                        onOpenExtensions = onOpenExtensions,
                        onOpenFiles = onOpenFiles,
                        onExportWorkspace = onExportWorkspace,
                        onExportStatus = onExportStatus,
                        onOpenTerminal = onOpenTerminal,
                        onOpenGit = onOpenGit,
                        onOpenRunDebug = onOpenRunDebug,
                        onOpenAgent = onOpenAgent,
                        onOpenAgentSessions = onOpenAgentSessions,
                        onOpenCapabilityHealth = onOpenCapabilityHealth,
                        onSettingsScopeChange = { settingsScope = it },
                        mcpHealth = mcpHealth,
                        mcpManagement = mcpManagement,
                        skillManagement = skillManagement,
                        onRetryMcp = ::retryMcp,
                        onOpenMcpConfig = ::openMcpConfig,
                        onOpenMcpPermissions = { settingsScope = SettingsScope.WORKSPACE; selected = SettingsCategory.PERMISSIONS },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else if (selected == null || query.isNotBlank()) {
                SettingsCategoryList(
                    categories = filtered,
                    selected = null,
                    onSelect = { selected = it; query = ""; legalRoute = null; selectedLicenseId = null },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            } else {
                SettingsDetail(
                    category = selected!!,
                    settingsScope = settingsScope,
                    themes = themes,
                    formattersEnabled = fmt,
                        formatterSaving = formatterSaving,
                    wordwrap = wordwrap,
                    hardwareShortcuts = hardwareShortcuts,
                    accessoryKeysComfortable = accessoryKeysComfortable,
                    codingKeyboardMode = codingKeyboardMode,
                    keyboardModeSaving = keyboardModeSaving,
                    codeStyleDefaults = codeStyleDefaults,
                    androidDevelopment = androidDevelopment,
                    deviceBridge = deviceBridge,
                    projectName = projectName,
                    agentPrefs = agentPrefs,
                        agentPreferencesReady = preferenceRead.state.ready,
                    githubAccountState = githubAccountState,
                        githubRepositoryAccessAvailable = githubAccount.repositoryAccessUrl() != null,
                    permissionPolicy = permissionPolicy,
                    legalRoute = legalRoute,
                    selectedLicenseId = selectedLicenseId,
                    showNestedBack = twoPane,
                    onFormatter = ::changeFormatter,
                    onWordwrap = { value -> savePreference("wordwrap", "Could not save word wrap") { onWordwrap(value) } },
                    onHardwareShortcuts = { value -> savePreference("hardware-shortcuts", "Could not save hardware shortcuts") { onHardwareShortcuts(value) } },
                    onAccessoryKeysComfortable = { value -> savePreference("comfortable-keys", "Could not save accessory keys") { onAccessoryKeysComfortable(value) } },
                    onCodingKeyboardMode = ::changeCodingKeyboardMode,
                    onDetectIndentation = { value -> savePreference("detect-indentation", "Could not save indent detection") { onDetectIndentation(value) } },
                    onIndentStyle = { value -> savePreference("indent-style", "Could not save indent style") { onIndentStyle(value) } },
                    onTabWidth = { value -> savePreference("tab-width", "Could not save tab width") { onTabWidth(value) } },
                    onIndentSize = { value -> savePreference("indent-size", "Could not save indent size") { onIndentSize(value) } },
                    onContinuationIndent = { value -> savePreference("continuation-indent", "Could not save continuation indent") { onContinuationIndent(value) } },
                    onTheme = ::changeTheme,
                    themeSaving = themeSaving,
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onPermission = ::changePermission,
                    permissionPending = permissionPending.keys.toSet(),
                        actionPending = actionPending,
                    onLegalRoute = { legalRoute = it; selectedLicenseId = null },
                    onLicense = { selectedLicenseId = it },
                    onLegalBack = ::navigateBack,
                    onOpenOfficialLink = officialLinkActions::open,
                        onReportBug = bugReport::open,
                    onOpenProviders = onOpenProviders,
                    onOpenModels = onOpenModels,
                    onConnectGitHub = connectGitHub,
                        onRetryGitHubState = githubAccount::retryStoredState,
                    onManageGitHubRepositoryAccess = manageGitHubRepositoryAccess,
                    onDisconnectGitHub = disconnectGitHub,
                    onOpenExtensions = onOpenExtensions,
                    onOpenFiles = onOpenFiles,
                    onExportWorkspace = onExportWorkspace,
                    onExportStatus = onExportStatus,
                    onOpenTerminal = onOpenTerminal,
                    onOpenGit = onOpenGit,
                    onOpenRunDebug = onOpenRunDebug,
                    onOpenAgent = onOpenAgent,
                    onOpenAgentSessions = onOpenAgentSessions,
                    onOpenCapabilityHealth = onOpenCapabilityHealth,
                    onSettingsScopeChange = { settingsScope = it },
                    mcpHealth = mcpHealth,
                    mcpManagement = mcpManagement,
                    skillManagement = skillManagement,
                    onRetryMcp = ::retryMcp,
                    onOpenMcpConfig = ::openMcpConfig,
                    onOpenMcpPermissions = { settingsScope = SettingsScope.WORKSPACE; selected = SettingsCategory.PERMISSIONS },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }
    SettingsBugReportHost(bugReport, onOpenPrivacy = { officialLinkActions.open(SettingsOfficialLink.PRIVACY) })
}

@Composable
private fun ScopeSelector(scope: SettingsScope, compact: Boolean = false, onSelected: (SettingsScope) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = scope == SettingsScope.USER, onClick = { onSelected(SettingsScope.USER) }, label = { Text("User") })
        FilterChip(selected = scope == SettingsScope.WORKSPACE, onClick = { onSelected(SettingsScope.WORKSPACE) }, label = { Text("Workspace") })
    }
}

@Composable
private fun SettingsCategoryList(
    categories: List<SettingsCategory>,
    selected: SettingsCategory?,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        if (categories.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("No matching settings", style = MaterialTheme.typography.titleMedium)
                Text("Try a different search.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(categories, key = { it.name }) { category ->
            ListItem(
                headlineContent = { Text(category.title) },
                supportingContent = { Text(category.subtitle, maxLines = 1) },
                leadingContent = { Icon(category.icon, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = if (category == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                modifier = Modifier.heightIn(min = 48.dp).selectable(selected = selected == category, role = Role.Button) { onSelect(category) },
            )
        }
    }
}

@Composable
private fun SettingsDetail(
    category: SettingsCategory,
    settingsScope: SettingsScope,
    themes: ThemeManager,
    formattersEnabled: Boolean,
    formatterSaving: Boolean,
    wordwrap: Boolean,
    hardwareShortcuts: Boolean,
    accessoryKeysComfortable: Boolean,
    codingKeyboardMode: CodingKeyboardMode,
    keyboardModeSaving: Boolean,
    codeStyleDefaults: CodeStyleDefaults,
    androidDevelopment: AndroidDevelopmentManager,
    deviceBridge: DeviceBridgeManager,
    projectName: String,
    agentPrefs: AgentPreferences.Snapshot,
    agentPreferencesReady: Boolean,
    githubAccountState: GitHubAccountManager.Snapshot,
    githubRepositoryAccessAvailable: Boolean,
    permissionPolicy: PermissionPolicyDocument,
    legalRoute: LegalRoute?,
    selectedLicenseId: String?,
    showNestedBack: Boolean,
    onFormatter: (Boolean) -> Unit,
    onWordwrap: (Boolean) -> Unit,
    onHardwareShortcuts: (Boolean) -> Unit,
    onAccessoryKeysComfortable: (Boolean) -> Unit,
    onCodingKeyboardMode: (CodingKeyboardMode) -> Unit,
    onDetectIndentation: (Boolean) -> Unit,
    onIndentStyle: (IndentStyle) -> Unit,
    onTabWidth: (Int) -> Unit,
    onIndentSize: (Int) -> Unit,
    onContinuationIndent: (Int) -> Unit,
    onTheme: (String) -> Unit,
    themeSaving: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onPermission: (String, PermEffect) -> Unit,
    permissionPending: Set<String>,
    actionPending: Set<String>,
    onLegalRoute: (LegalRoute) -> Unit,
    onLicense: (String) -> Unit,
    onLegalBack: () -> Unit,
    onOpenOfficialLink: (SettingsOfficialLink) -> Unit,
    onReportBug: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit,
    onConnectGitHub: () -> Unit,
    onRetryGitHubState: () -> Unit,
    onManageGitHubRepositoryAccess: () -> Unit,
    onDisconnectGitHub: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenFiles: () -> Unit,
    onExportWorkspace: (() -> Unit)?,
    onExportStatus: (() -> Unit)?,
    onOpenTerminal: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenRunDebug: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAgentSessions: () -> Unit,
    onOpenCapabilityHealth: () -> Unit,
    onSettingsScopeChange: (SettingsScope) -> Unit,
    mcpHealth: List<McpHealthSnapshot>,
    mcpManagement: McpManagement?,
    skillManagement: SkillManagement?,
    onRetryMcp: (String) -> Unit,
    onOpenMcpConfig: () -> Unit,
    onOpenMcpPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (category == SettingsCategory.ABOUT && legalRoute != null) {
        LegalSettingsContent(legalRoute, selectedLicenseId, onLicense, onLegalBack, onOpenOfficialLink, showNestedBack, modifier.droideContentMotion(Pair(legalRoute, selectedLicenseId)))
        return
    }
    // Category and scope own their scroll state. Do not reveal a new page at the old page's
    // bottom, or reset the user's position just because a setting value changed.
    val detailScroll = rememberSaveable(category, settingsScope, saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    Column(modifier.droideContentMotion(Pair(category, settingsScope)).verticalScroll(detailScroll).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(category.title, style = MaterialTheme.typography.headlineSmall)
        Text(category.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        when (category) {
            SettingsCategory.GENERAL -> {
                SectionTitle("Workbench")
                InfoRow("Project", projectName)
                InfoRow("Settings scope", if (settingsScope == SettingsScope.USER) "Applies across projects" else "Applies to this project")
                InfoRow("Session restoration", "Preserves editor, terminal and Agent state")
            }
            SettingsCategory.APPEARANCE -> {
                SectionTitle("Theme")
                if (settingsScope == SettingsScope.WORKSPACE) {
                    Column(Modifier.selectableGroup()) {
                        themes.list().forEach { theme -> ChoiceRow(theme.name, theme.name == themes.current().name, enabled = !themeSaving) { onTheme(theme.name) } }
                    }
                    InfoRow("Storage", ".droide/theme.json")
                } else ActionRow("Switch to Workspace settings", "Store theme for this workspace", { onSettingsScopeChange(SettingsScope.WORKSPACE) })
            }
            SettingsCategory.EDITOR -> {
                if (settingsScope == SettingsScope.USER) {
                    SectionTitle("Text editor")
                    SwitchRow("Word wrap", "Wrap editor lines; large-file mode keeps wrapping off for responsiveness", wordwrap, onWordwrap, enabled = "wordwrap" !in actionPending)
                    SwitchRow("Detect indentation", "Detect tabs, spaces and width from the file", codeStyleDefaults.detectIndentation, onDetectIndentation, enabled = "detect-indentation" !in actionPending)
                    SectionTitle("Default indentation")
                    Column(Modifier.selectableGroup()) {
                    ChoiceRow("Spaces", codeStyleDefaults.indentStyle == IndentStyle.SPACES, enabled = "indent-style" !in actionPending) { onIndentStyle(IndentStyle.SPACES) }
                    ChoiceRow("Tabs", codeStyleDefaults.indentStyle == IndentStyle.TABS, enabled = "indent-style" !in actionPending) { onIndentStyle(IndentStyle.TABS) }
                    }
                    Text("Tab width", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(2, 4, 8).forEach { n -> FilterChip(selected = codeStyleDefaults.tabWidth == n, enabled = "tab-width" !in actionPending, onClick = { onTabWidth(n) }, label = { Text(n.toString()) }) } }
                    Text("Indent size", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(2, 4, 8).forEach { n -> FilterChip(selected = codeStyleDefaults.indentSize == n, enabled = "indent-size" !in actionPending, onClick = { onIndentSize(n) }, label = { Text(n.toString()) }) } }
                    Text("Continuation indent", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(4, 8, 12).forEach { n -> FilterChip(selected = codeStyleDefaults.continuationIndent == n, enabled = "continuation-indent" !in actionPending, onClick = { onContinuationIndent(n) }, label = { Text(n.toString()) }) } }
                    InfoRow("Authority", ".editorconfig > detected style > user defaults")
                    InfoRow("Smart typing & completion", "Follows the active language and LSP")
                } else {
                    SectionTitle("Workspace formatting")
                    SwitchRow("External formatter fallback", "Use configured formatters when LSP formatting is unavailable; LSP formatting stays enabled", formattersEnabled, onFormatter, enabled = !formatterSaving)
                    InfoRow("Storage", ".droide/formatter.json")
                    InfoRow("Indentation authority", "Nearest matching .editorconfig overrides user defaults for each file")
                }
            }
            SettingsCategory.LANGUAGES -> {
                SectionTitle("Language intelligence")
                InfoRow("Detection", "LanguageRegistry selects language support from the active file")
                InfoRow("LSP", "Diagnostics, completion, symbols and navigation")
                InfoRow("Grammar", "TextMate grammars are built in")
                ActionRow("Manage language extensions", "Open the Extensions workbench", onOpenExtensions)
            }
            SettingsCategory.FILES -> {
                SectionTitle("Scope")
                InfoRow("Active workspace", projectName)
                InfoRow("User vs Workspace", "Workspace values override supported user defaults")
                InfoRow("File access", "Workspace paths are sandboxed and validated")
                ActionRow("Open Files workbench", "Browse and manage the active workspace", onOpenFiles)
                onExportWorkspace?.let {
                    ActionRow("Export all", "Save the entire active workspace to device storage. Choose individual files or folders in Explorer.", it)
                }
                onExportStatus?.let {
                    ActionRow("Export status", "View progress, results and previous exports", it)
                }
            }
            SettingsCategory.TERMINAL -> {
                SectionTitle("Input")
                if (settingsScope == SettingsScope.USER) {
                    CodingKeyboardSettings(codingKeyboardMode, !keyboardModeSaving, onCodingKeyboardMode)
                    ComfortableAccessoryKeysRow(accessoryKeysComfortable, onAccessoryKeysComfortable, enabled = "comfortable-keys" !in actionPending)
                    SwitchRow("Hardware keyboard shortcuts", "Enable Droide's IDE keyboard shortcut routing", hardwareShortcuts, onHardwareShortcuts, enabled = "hardware-shortcuts" !in actionPending)
                } else ActionRow("Switch to User settings", "Applies across workspaces", { onSettingsScopeChange(SettingsScope.USER) })
                InfoRow("Terminal engine", "Termux terminal components")
                ActionRow("Open Terminal", "Return to the live terminal workbench", onOpenTerminal)
            }
            SettingsCategory.GIT -> {
                SectionTitle("Source control")
                InfoRow("Git engine", "Eclipse JGit, workspace-scoped")
                InfoRow("Safety", "Agent permissions apply to mutating Git and shell actions")
                InfoRow("Repository state", "Uses the active workspace repository")
                ActionRow("Open Source Control", "Inspect status, changes, branches and repository actions", onOpenGit)
            }
            SettingsCategory.RUN -> {
                SectionTitle("Android & Device")
                if (settingsScope == SettingsScope.WORKSPACE) AndroidDevelopmentSettingsSection(androidDevelopment, deviceBridge)
                else ActionRow("Switch to Workspace settings", "Build and toolchain settings are workspace-scoped", { onSettingsScopeChange(SettingsScope.WORKSPACE) })
                ActionRow("Open Run & Debug", "Return to the workspace execution tools", onOpenRunDebug)
            }
            SettingsCategory.AI -> {
                SectionTitle("Current agent configuration")
                InfoRow("Provider", if (agentPreferencesReady) ProviderRegistry.byId(agentPrefs.providerId).name else "Unavailable")
                InfoRow("Model", if (agentPreferencesReady) agentPrefs.model else "Unavailable")
                InfoRow("Reasoning", if (agentPreferencesReady) agentPrefs.reasoningEffort?.label ?: "Provider default" else "Unavailable")
                InfoRow("Context safety", "Project-scoped permission gates")
                ActionRow("Open Agent", "Return to the active Agent conversation", onOpenAgent)
                ActionRow("Agent sessions", "Browse and resume previous Agent sessions", onOpenAgentSessions)
                ActionRow("Choose model", "Change the active provider model", onOpenModels)
            }
            SettingsCategory.PROVIDERS -> {
                SectionTitle("Active")
                InfoRow("Provider", if (agentPreferencesReady) ProviderRegistry.byId(agentPrefs.providerId).name else "Unavailable")
                InfoRow("Model", if (agentPreferencesReady) agentPrefs.model else "Unavailable")
                if (settingsScope == SettingsScope.USER) {
                    ActionRow("Connect or manage provider", "Connect provider accounts and credentials", onOpenProviders)
                    ActionRow("Choose model", "Select a model for the active provider", onOpenModels)
                    Spacer(Modifier.height(12.dp))
                    LocalModelsSettingsSection(deviceBridge)
                } else ActionRow("Switch to User settings", "Provider accounts and model defaults are user settings", { onSettingsScopeChange(SettingsScope.USER) })
            }
            SettingsCategory.PERMISSIONS -> {
                if (settingsScope == SettingsScope.WORKSPACE) PermissionSettings(permissionPolicy, permissionPending, onPermission)
                else { SectionTitle("Project policy"); ActionRow("Switch to Workspace settings", "Agent permissions are project-scoped", { onSettingsScopeChange(SettingsScope.WORKSPACE) }) }
            }
            SettingsCategory.TOOLS -> {
                if (mcpManagement == null) SectionTitle("Tool authority")
                if (mcpManagement != null) McpManagementSection(
                    management = mcpManagement,
                    health = mcpHealth,
                    onOpenConfig = onOpenMcpConfig,
                    onOpenPermissions = onOpenMcpPermissions,
                    pendingActions = actionPending,
                ) else McpSettingsSection(
                    health = mcpHealth,
                    onRetry = onRetryMcp,
                    onOpenConfig = onOpenMcpConfig,
                    onOpenPermissions = onOpenMcpPermissions,
                    pendingActions = actionPending,
                )
                SectionTitle("Other tools")
                InfoRow("Custom/plugin tools", "Dynamic tools use Agent permission checks")
                ActionRow("Agent permission controls", "Review workspace tool permissions", onOpenMcpPermissions)
            }
            SettingsCategory.SKILLS -> {
                if (skillManagement != null) key(settingsScope) {
                    SkillManagementSection(skillManagement, if (settingsScope == SettingsScope.USER) "user" else "workspace")
                } else InfoRow("Skills unavailable", "Open a workspace to manage skills")
            }
            SettingsCategory.PLUGINS -> {
                SectionTitle("Extensions")
                InfoRow("Runtime", "Extensions follow Droide permission boundaries")
                ActionRow("Open Extensions workbench", "Browse and manage extensions", onOpenExtensions)
            }
            SettingsCategory.KEYBOARD -> {
                SectionTitle("Keyboard")
                if (settingsScope == SettingsScope.USER) {
                    CodingKeyboardSettings(codingKeyboardMode, !keyboardModeSaving, onCodingKeyboardMode)
                    SwitchRow("Hardware keyboard shortcuts", "Enable IDE shortcuts for hardware keyboards", hardwareShortcuts, onHardwareShortcuts, enabled = "hardware-shortcuts" !in actionPending)
                    ComfortableAccessoryKeysRow(accessoryKeysComfortable, onAccessoryKeysComfortable, enabled = "comfortable-keys" !in actionPending)
                } else ActionRow("Switch to User settings", "Keyboard and input preferences apply across workspaces", { onSettingsScopeChange(SettingsScope.USER) })
                InfoRow("Context-aware keys", "Accessory keys follow editor or terminal focus")
                InfoRow("IDE shortcuts", "Ctrl+Space Completion · Ctrl+P Quick Open · Ctrl+Shift+P Commands · Ctrl+S Save · Ctrl+` Terminal · F2 Rename · F5 Debug")
            }
            SettingsCategory.SECURITY -> {
                SectionTitle("Security boundaries")
                InfoRow("Secrets", "Stored outside workspace files")
                InfoRow("Agent policy", "Stored per project")
                InfoRow("Sensitive files", "Sensitive paths default to Ask")
                InfoRow("Distribution", "Public release is blocked until compliance is complete")
                ActionRow("Agent Permissions", "Review workspace permissions", onOpenMcpPermissions)
                ActionRow("Provider credentials", "Manage provider accounts", onOpenProviders)
            }
            SettingsCategory.PERFORMANCE -> {
                SectionTitle("Runtime")
                InfoRow("Workspace lifetime", "Editor, terminal and Agent stay active")
                InfoRow("Indexing", "Workspace language services stay active")
                InfoRow("Storage", "Projects are local-first; build caches are managed separately")
                ActionRow("Capability Health", "Inspect runtime and toolchain status", onOpenCapabilityHealth)
            }
            SettingsCategory.NOTIFICATIONS -> {
                SectionTitle("Current support")
                InfoRow("IDE feedback", "Uses in-app status and notifications")
                ActionRow("Android notification settings", "Open Android notification settings", onOpenNotificationSettings)
            }
            SettingsCategory.ACCOUNTS -> {
                if (settingsScope == SettingsScope.USER) {
                    SectionTitle("GitHub")
                    when (githubAccountState.status) {
                        GitHubAccountManager.Status.UNAVAILABLE -> {
                            if (githubAccountState.storageReadFailed) ActionRow(
                                "Retry account data", "Read stored GitHub account data again without clearing it", onRetryGitHubState,
                            ) else InfoRow(
                                "GitHub sign-in unavailable",
                                "GitHub sign-in is not configured in this build",
                            )
                        }
                        GitHubAccountManager.Status.DISCONNECTED -> {
                            ActionRow("Connect GitHub", "Sign in with GitHub", onConnectGitHub)
                        }
                        GitHubAccountManager.Status.WAITING_BROWSER -> {
                            InfoRow("GitHub", githubAccountState.login?.let { "@$it connected · waiting for browser sign-in" } ?: "Waiting for browser authorization")
                            ActionRow("Restart GitHub sign-in", "Start a new GitHub sign-in", onConnectGitHub)
                        }
                        GitHubAccountManager.Status.COMPLETING -> {
                            InfoRow("GitHub", githubAccountState.login?.let { "Validating a replacement for @$it" } ?: "Validating the authorized GitHub account")
                        }
                        GitHubAccountManager.Status.REFRESHING -> {
                            InfoRow("GitHub", githubAccountState.login?.let { "Refreshing the session for @$it" } ?: "Refreshing the GitHub session")
                        }
                        GitHubAccountManager.Status.DISCONNECTING -> {
                            InfoRow("GitHub", githubAccountState.login?.let { "Revoking Droide authorization for @$it" } ?: "Revoking Droide's GitHub authorization")
                        }
                        GitHubAccountManager.Status.CONNECTED -> {
                            InfoRow("GitHub", "Connected as @${githubAccountState.login ?: "unknown"}")
                            InfoRow("Repository access", "Private access follows GitHub App permissions")
                            githubAccountState.detail?.let { InfoRow("Last sign-in", it) }
                            if (githubRepositoryAccessAvailable) {
                                ActionRow("Grant repository access", "Choose repositories on GitHub", onManageGitHubRepositoryAccess)
                            } else {
                                InfoRow("Repository access management unavailable", "This build has no GitHub App installation URL configured")
                            }
                            ActionRow("Reconnect GitHub", "Sign in again", onConnectGitHub)
                            ActionRow("Disconnect GitHub", "Revoke GitHub access", onDisconnectGitHub)
                        }
                        GitHubAccountManager.Status.REAUTH_REQUIRED -> {
                            InfoRow("GitHub", "@${githubAccountState.login ?: "unknown"} needs sign-in again")
                            ActionRow("Reconnect GitHub", "Sign in again with GitHub", onConnectGitHub)
                            ActionRow("Disconnect GitHub", "Remove the expired credential", onDisconnectGitHub)
                        }
                    }
                    githubAccountState.detail?.takeIf { githubAccountState.status != GitHubAccountManager.Status.CONNECTED }?.let {
                        InfoRow("Status", it)
                    }
                    SectionTitle("Other connected services")
                    InfoRow("AI providers", "AI provider accounts are separate from GitHub")
                    ActionRow("Manage providers", "Open provider accounts", onOpenProviders)
                    InfoRow("Settings sync", "Not available yet")
                } else {
                    SectionTitle("Connected services")
                    ActionRow("Switch to User settings", "Accounts are user settings", { onSettingsScopeChange(SettingsScope.USER) })
                }
            }
            SettingsCategory.ABOUT -> {
                SectionTitle("Droide")
                InfoRow("Version", "Droide v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("Developer", SettingsOfficialLinks.DEVELOPER_DESCRIPTION)
                InfoRow("Engineering", "BayZov")
                ActionRow("Release status", "View packaged release and distribution information", { onLegalRoute(LegalRoute.UPDATES) })
                ActionRow("Privacy", "Published website policy and app-specific notice status", { onLegalRoute(LegalRoute.PRIVACY) })
                ActionRow("Terms of Service", "Read the published website terms", { onLegalRoute(LegalRoute.TERMS) })
                ActionRow("Droide License", "LGPLv3 license for Droide-owned source", { onLegalRoute(LegalRoute.DROIDE_LICENSE) })
                SectionTitle("Legal")
                ActionRow("Open Source Licenses", "Dependency licenses", { onLegalRoute(LegalRoute.OPEN_SOURCE) })
                ActionRow("Third-Party Notices", "Brand marks, attributions and license scope", { onLegalRoute(LegalRoute.THIRD_PARTY) })
                ActionRow("Brand Asset Terms", "Provider and project logo usage", { onLegalRoute(LegalRoute.BRAND_ASSETS) })
                SectionTitle("Official links")
                Text(SettingsOfficialLinks.EXTERNAL_LINKS_NOTE, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsOfficialLinks.about.forEach { link -> OfficialLinkRow(link, onOpenOfficialLink) }
                SectionTitle("Support & contact")
                Text(SettingsOfficialLinks.SUPPORT_NOTE, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsOfficialLinks.support.forEach { link ->
                    if (link == SettingsOfficialLink.REPORT_BUG) NativeReportBugRow(onReportBug)
                    else OfficialLinkRow(link, onOpenOfficialLink)
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
@Composable
private fun PermissionSettings(policy: PermissionPolicyDocument, pending: Set<String>, onPermission: (String, PermEffect) -> Unit) {
    val rows = listOf(
        Triple("read", "Read workspace files", PermEffect.ALLOW),
        Triple("edit", "Edit workspace files", PermEffect.ASK),
        Triple("shell", "Run shell / mutating Git commands", PermEffect.ASK),
        Triple("webfetch", "Fetch web resources", PermEffect.ASK),
        Triple("websearch", "Search the web", PermEffect.ASK),
        Triple("mcp", "Use MCP tools", PermEffect.ASK),
        Triple("mcp_repair", "Repair MCP configuration", PermEffect.ASK),
        Triple("ide_execute", "Run IDE build, tests, lint or file execution", PermEffect.ASK),
        Triple("lsp_edit", "Apply LSP quick fixes / formatting", PermEffect.ASK),
        Triple("toolchain_install", "Install or repair managed runtimes/toolchains", PermEffect.ASK),
        Triple("browser_interact", "Click/type in the integrated browser", PermEffect.ASK),
        Triple("browser_local", "Let Agent browse localhost development servers", PermEffect.ASK),
        Triple("subagent", "Launch subagents", PermEffect.ASK),
        Triple("external_directory", "Access paths outside the workspace", PermEffect.ASK),
    )
    SectionTitle("Project policy")
    Text(
        "Category controls preserve more-restrictive matching rules. Agent-specific rules can override Allow or Ask; Deny remains enforced across policy layers.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    rows.forEach { (action, label, default) ->
        val current = policy.rules.lastOrNull { it.action == action && it.resource == "*" }?.effect ?: default
        PermissionRow(label, current, enabled = action !in pending) { onPermission(action, it) }
    }
    if (policy.rules.any { it.resource != "*" }) {
        InfoRow("Fine-grained rules", "${policy.rules.count { it.resource != "*" }} existing rule(s) keep their order; conflicting broad changes are rejected")
    }
}

@Composable
private fun PermissionRow(label: String, selected: PermEffect, enabled: Boolean, onSelected: (PermEffect) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PermEffect.entries.forEach { effect ->
                FilterChip(selected = effect == selected, enabled = enabled, onClick = { if (effect != selected) onSelected(effect) }, label = { Text(effect.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
    }
    HorizontalDivider()
}


@Composable
private fun LegalSettingsContent(
    route: LegalRoute,
    selectedLicenseId: String?,
    onLicense: (String) -> Unit,
    onBack: () -> Unit,
    onOpenOfficialLink: (SettingsOfficialLink) -> Unit,
    showBackHeader: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    when (route) {
        LegalRoute.OPEN_SOURCE -> {
            val manifestResult by produceState<Result<OpenSourceLicenseManifest>?>(null, context) {
                value = withContext(Dispatchers.IO) {
                    runCatching { OpenSourceLicenseCatalog.load(context) }
                }
            }
            val manifest = manifestResult?.getOrNull()
            if (manifestResult == null) {
                LegalTextPage("Open Source Licenses", "Loading…", onBack, showBackHeader, modifier)
            } else if (manifest == null) {
                LegalTextPage("Open Source Licenses", "License catalog unavailable.\n\n${manifestResult?.exceptionOrNull()?.message.orEmpty()}", onBack, showBackHeader, modifier)
            } else if (selectedLicenseId != null) {
                val entry = manifest.entries.firstOrNull { it.id == selectedLicenseId }
                if (entry == null) LegalTextPage("License not found", "The selected catalog entry no longer exists.", onBack, showBackHeader, modifier)
                else {
                    val body by produceState<String?>(null, entry.id) {
                        value = withContext(Dispatchers.IO) {
                            runCatching { OpenSourceLicenseCatalog.readEntry(context, entry) }
                                .getOrElse { "License text could not be loaded: ${it.message}" }
                        }
                    }
                    val text = buildString {
                        append(entry.name).append("\n")
                        append("Version: ").append(entry.version).append("\n")
                        append("License: ").append(entry.licenseName).append(" (").append(entry.spdx).append(")\n")
                        append(entry.copyright).append("\n")
                        append("Source: ").append(entry.source).append("\n\n")
                        append(entry.notice).append("\n\n").append(body ?: "Loading…")
                    }
                    LegalTextPage(entry.name, text, onBack, showBackHeader, modifier)
                }
            } else {
                OpenSourceLicenseList(manifest, onLicense, onBack, showBackHeader, modifier)
            }
        }
        LegalRoute.THIRD_PARTY -> LegalAssetPage("Third-Party Notices", OpenSourceLicenseCatalog.THIRD_PARTY_NOTICES_ASSET, onBack, showBackHeader, modifier)
        LegalRoute.BRAND_ASSETS -> LegalAssetPage("Brand Asset Terms", OpenSourceLicenseCatalog.BRAND_ASSET_TERMS_ASSET, onBack, showBackHeader, modifier)
        LegalRoute.UPDATES -> LegalAssetPage("Release & Distribution Status", OpenSourceLicenseCatalog.DISTRIBUTION_COMPLIANCE_ASSET, onBack, showBackHeader, modifier)
        LegalRoute.DROIDE_LICENSE -> LegalAssetPage(
            "Droide License",
            OpenSourceLicenseCatalog.DROIDE_LICENSE_ASSET,
            onBack,
            showBackHeader,
            modifier,
        )
        LegalRoute.PRIVACY -> WebsiteLegalPage(
            "Privacy", SettingsOfficialLinks.PRIVACY_NOTE, SettingsOfficialLink.PRIVACY,
            onOpenOfficialLink, onBack, showBackHeader, modifier,
        )
        LegalRoute.TERMS -> WebsiteLegalPage(
            "Terms of Service", SettingsOfficialLinks.TERMS_NOTE, SettingsOfficialLink.TERMS,
            onOpenOfficialLink, onBack, showBackHeader, modifier,
        )
    }
}

/** Existing nested route, with explicit external actions rather than a remotely fetched policy. */
@Composable
private fun WebsiteLegalPage(
    title: String,
    text: String,
    document: SettingsOfficialLink,
    onOpenOfficialLink: (SettingsOfficialLink) -> Unit,
    onBack: () -> Unit,
    showBackHeader: Boolean,
    modifier: Modifier,
) {
    val scroll = rememberSaveable(document, saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp)) {
        LegalPageTitle(title, onBack, showBackHeader)
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(12.dp))
        Text(SettingsOfficialLinks.EXTERNAL_LINKS_NOTE, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OfficialLinkRow(document, onOpenOfficialLink)
        OfficialLinkRow(SettingsOfficialLink.SUPPORT, onOpenOfficialLink)
        Text(SettingsOfficialLinks.SUPPORT_NOTE, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun OfficialLinkRow(link: SettingsOfficialLink, onOpen: (SettingsOfficialLink) -> Unit) {
    ListItem(
        headlineContent = { Text(link.title) },
        supportingContent = {
            Column {
                Text(link.targetLabel)
                link.availabilityNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = { Icon(if (link.isEmail) Icons.Default.Email else Icons.Default.OpenInNew, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = link.actionLabel) { onOpen(link) },
    )
    HorizontalDivider()
}

@Composable
private fun OpenSourceLicenseList(manifest: OpenSourceLicenseManifest, onLicense: (String) -> Unit, onBack: () -> Unit, showBackHeader: Boolean, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(query, manifest) {
        val q = query.trim().lowercase()
        if (q.isBlank()) manifest.entries else manifest.entries.filter {
            it.name.lowercase().contains(q) || it.licenseName.lowercase().contains(q) || it.spdx.lowercase().contains(q)
        }
    }
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        LegalPageTitle("Open Source Licenses", onBack, showBackHeader)
        Text(manifest.closureNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search libraries") },
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it.id }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.name) },
                    supportingContent = { Text("${entry.version} · ${entry.licenseName}") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { onLicense(entry.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LegalAssetPage(title: String, asset: String, onBack: () -> Unit, showBackHeader: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val text by produceState<String?>(null, asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { OpenSourceLicenseCatalog.readAsset(context, asset) }
                .getOrElse { "Unable to load packaged legal document: ${it.message}" }
        }
    }
    LegalTextPage(title, text ?: "Loading…", onBack, showBackHeader, modifier)
}

@Composable
private fun LegalTextPage(title: String, text: String, onBack: () -> Unit, showBackHeader: Boolean, modifier: Modifier) {
    val chunks = remember(text) { legalTextChunks(text) }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        item { LegalPageTitle(title, onBack, showBackHeader) }
        items(chunks.size) { index ->
            SelectionContainer { Text(chunks[index], style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun LegalPageTitle(title: String, onBack: () -> Unit, showBackHeader: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showBackHeader) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
}

@Composable private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() }.padding(top = 16.dp, bottom = 8.dp))
}

@Composable private fun ScopeNotice(actual: SettingsScope, required: SettingsScope, text: String) {
    if (actual != required) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable private fun InfoRow(title: String, value: String) {
    // Read-only facts must not share a control's divider, chevron or tap treatment.
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked),
    )
    HorizontalDivider()
}

@Composable private fun CodingKeyboardSettings(
    mode: CodingKeyboardMode,
    enabled: Boolean,
    onMode: (CodingKeyboardMode) -> Unit,
) {
    SectionTitle("Editor & terminal keyboard")
    Column(Modifier.selectableGroup()) {
        CodingKeyboardMode.values().forEach { candidate ->
            val (title, description) = when (candidate) {
                CodingKeyboardMode.COMPACT -> "Compact (default)" to
                    "Requests no extra number row, suggestions or word corrections."
                CodingKeyboardMode.NO_CORRECTIONS -> "No corrections" to
                    "Keeps language input without word suggestions or corrections."
                CodingKeyboardMode.NORMAL -> "Normal" to
                    "Allows your keyboard's word suggestions and corrections."
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(
                        selected = mode == candidate,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onMode(candidate) },
                    ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == candidate, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Text(
        "The keyboard app controls its number row. If Compact affects language input, choose No corrections.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    HorizontalDivider()
}

@Composable private fun ComfortableAccessoryKeysRow(checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text("Comfortable accessory keys") },
        supportingContent = { Text("48dp mobile accessory-key row instead of compact 44dp") },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked),
    )
    HorizontalDivider()
}

@Composable private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
    )
    HorizontalDivider()
}

@Composable private fun ChoiceRow(title: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { if (!selected) onClick() }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(title, Modifier.padding(start = 8.dp))
    }
}
