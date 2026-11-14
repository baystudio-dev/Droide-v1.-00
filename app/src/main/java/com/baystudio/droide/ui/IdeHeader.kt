package com.baystudio.droide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class PrimaryRunTarget { FILE, PREVIEW, ANDROID_APP }

internal fun primaryRunTarget(activeFile: String, androidProject: Boolean): PrimaryRunTarget {
    if (com.baystudio.droide.core.WorkspaceHtmlPreview.isHtml(activeFile) || isMarkdownPreviewPath(activeFile))
        return PrimaryRunTarget.PREVIEW
    val name = activeFile.substringAfterLast('/').lowercase()
    val androidSource = name.substringAfterLast('.', "") in setOf("kt", "java", "xml") ||
        name in setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties")
    return if (androidProject && (activeFile.isBlank() || androidSource)) PrimaryRunTarget.ANDROID_APP
        else PrimaryRunTarget.FILE
}

data class DroideIdeHeaderState(
    val projectName: String,
    val activeFile: String,
    val compact: Boolean,
    val wideHeader: Boolean,
    val agentOpen: Boolean,
    val landscape: Boolean,
    val focusMode: Boolean,
    val busy: Boolean,
    val androidProject: Boolean,
    val deviceConnected: Boolean,
    val outputAvailable: Boolean,
    val apkArtifactAvailable: Boolean,
    val cancellable: Boolean,
    val mcpHealth: List<com.baystudio.droide.core.McpHealthSnapshot>,
    val terminalCreationAvailable: Boolean = true,
    val linuxSetupBusy: Boolean = false,
    val showProjectsButton: Boolean = true,
)

class DroideIdeHeaderActions(
    val onProjects: () -> Unit,
    val onRun: () -> Unit,
    val onBuildDebug: () -> Unit,
    val onTest: () -> Unit,
    val onTestExplorer: () -> Unit,
    val onLint: () -> Unit,
    val onReleaseApk: () -> Unit,
    val onReleaseBundle: () -> Unit,
    val onBuildOutput: () -> Unit,
    val onApkAnalyzer: () -> Unit,
    val onCancel: () -> Unit,
    val onDebugAndroid: () -> Unit,
    val onInstallRun: () -> Unit,
    val onLogcat: () -> Unit,
    val onLocalLinux: () -> Unit,
    val onAndroidDevelopment: () -> Unit,
    val onQuickOpen: () -> Unit,
    val onSearchFiles: () -> Unit,
    val onWorkspaceSymbols: () -> Unit,
    val onProblems: () -> Unit,
    val onTerminal: () -> Unit,
    val onGit: () -> Unit,
    val onReviewChanges: () -> Unit,
    val onIsolatedWorkspaces: () -> Unit,
    val onCapabilityHealth: () -> Unit,
    val onTasks: () -> Unit,
    val onAgent: () -> Unit,
    val onFocusMode: () -> Unit,
    val onCommandPalette: () -> Unit,
    val onRunDebug: () -> Unit,
    val onSessions: () -> Unit,
    val onExtensions: () -> Unit,
    val onConnectProvider: () -> Unit,
    val onModels: () -> Unit,
    val onBilling: () -> Unit,
    val onSettings: () -> Unit,
    val onMcpRetry: (String) -> Unit,
    val onManageMcp: () -> Unit,
    val onNotificationSettings: () -> Unit = {},
)






@Composable
fun DroideIdeHeader(
    state: DroideIdeHeaderState,
    actions: DroideIdeHeaderActions,
) {
    var moreMenu by remember { mutableStateOf(false) }
    val androidDisabledHint = if (state.androidProject) null else "Android Gradle project required"
    val deviceDisabledHint = if (state.deviceConnected) null else "Connect Wireless Debugging first"
    val androidDeviceEnabled = state.androidProject && state.deviceConnected && !state.busy
    val androidDeviceHint = when {
        !state.androidProject -> androidDisabledHint
        !state.deviceConnected -> deviceDisabledHint
        else -> null
    }
    val runTarget = primaryRunTarget(state.activeFile, state.androidProject)
    val runLabel = when (runTarget) {
        PrimaryRunTarget.PREVIEW -> "Preview"
        PrimaryRunTarget.ANDROID_APP -> "Run App"
        PrimaryRunTarget.FILE -> "Run"
    }
    val runDescription = when (runTarget) {
        PrimaryRunTarget.PREVIEW -> "Preview current file"
        PrimaryRunTarget.ANDROID_APP -> "Build, install and run Android app"
        PrimaryRunTarget.FILE -> "Run current file"
    }

    val buildActions = buildList {
        add(DroideHeaderAction("Project Tasks", Icons.Default.TaskAlt, "Tasks", !state.busy, if (state.busy) "Another Build/Run operation is active" else null, actions.onTasks))
        add(DroideHeaderAction("Build Debug APK", Icons.Default.Android, "Build", state.androidProject && !state.busy, androidDisabledHint, actions.onBuildDebug))
        add(DroideHeaderAction("Test Tasks", Icons.Default.Science, "Test", !state.busy, if (state.busy) "Another Build/Run operation is active" else null, actions.onTestExplorer))
        add(DroideHeaderAction("Android Test", Icons.Default.Science, "Test", state.androidProject && !state.busy, androidDisabledHint, actions.onTest))
        add(DroideHeaderAction("Lint", Icons.Default.Rule, "Verify", state.androidProject && !state.busy, androidDisabledHint, actions.onLint))
        add(DroideHeaderAction("Release APK", Icons.Default.Inventory2, "Package", state.androidProject && !state.busy, androidDisabledHint, actions.onReleaseApk))
        add(DroideHeaderAction("Release Bundle", Icons.Default.AllInbox, "Package", state.androidProject && !state.busy, androidDisabledHint, actions.onReleaseBundle))
        add(DroideHeaderAction("Build / Run Output", Icons.Default.Article, "Inspect", state.outputAvailable, if (state.outputAvailable) null else "No output yet", actions.onBuildOutput))
        add(DroideHeaderAction("APK Analyzer", Icons.Default.Analytics, "Inspect", state.apkArtifactAvailable, if (state.apkArtifactAvailable) null else "Build an APK first", actions.onApkAnalyzer))
        if (state.cancellable) add(DroideHeaderAction("Cancel active operation", Icons.Default.StopCircle, "Current", onClick = actions.onCancel))
    }

    val debugActions = listOf(
        DroideHeaderAction("Run & Debug", Icons.Default.BugReport, "Workspace", onClick = actions.onRunDebug),
        DroideHeaderAction("Debug Android App", Icons.Default.BugReport, "Android", androidDeviceEnabled, androidDeviceHint, actions.onDebugAndroid),
        DroideHeaderAction("Install & Run", Icons.Default.InstallMobile, "Android", androidDeviceEnabled, androidDeviceHint, actions.onInstallRun),
        DroideHeaderAction("Logcat", Icons.Default.Article, "Inspect", state.deviceConnected, deviceDisabledHint, actions.onLogcat),
        DroideHeaderAction("Problems", Icons.Default.ErrorOutline, "Inspect", onClick = actions.onProblems),
    )

    val toolActions = listOf(
        DroideHeaderAction("Quick Open", Icons.Default.InsertDriveFile, "Navigate", onClick = actions.onQuickOpen),
        DroideHeaderAction("Search in Files", Icons.Default.ManageSearch, "Navigate", onClick = actions.onSearchFiles),
        DroideHeaderAction("Workspace Symbols", Icons.Default.AccountTree, "Navigate", onClick = actions.onWorkspaceSymbols),
        DroideHeaderAction("Terminal", Icons.Default.Terminal, "Workspace", onClick = actions.onTerminal),
        DroideHeaderAction("Source Control", Icons.Default.Source, "Workspace", onClick = actions.onGit),
        DroideHeaderAction("Review Changes", Icons.Default.Difference, "Workspace", onClick = actions.onReviewChanges),
        DroideHeaderAction("Isolated Workspaces", Icons.Default.AccountTree, "Workspace", onClick = actions.onIsolatedWorkspaces),
        DroideHeaderAction("Capability Health", Icons.Default.MonitorHeart, "Diagnostics", onClick = actions.onCapabilityHealth),
        DroideHeaderAction("Extensions", Icons.Default.Extension, "Workspace", onClick = actions.onExtensions),
        DroideHeaderAction("Agent Sessions", Icons.Default.History, "Agent", onClick = actions.onSessions),
        DroideHeaderAction("Ubuntu Terminal", Icons.Default.Terminal, "Runtime", state.terminalCreationAvailable && !state.linuxSetupBusy, if (state.linuxSetupBusy) "Ubuntu is opening" else if (!state.terminalCreationAvailable) "Close a terminal tab first" else null, actions.onLocalLinux),
        DroideHeaderAction("Android SDK / Toolchain", Icons.Default.Settings, "Android", onClick = actions.onAndroidDevelopment),
    )

    Box {
        DroideTopBar(
            projectName = state.projectName,
            activeFile = state.activeFile,
            compact = state.compact,
            agentOpen = state.agentOpen,
            landscape = state.landscape,
            focusMode = state.focusMode,
            running = state.busy,
            runLabel = runLabel,
            runDescription = runDescription,
            onProjects = actions.onProjects,
            onRun = actions.onRun,
            showProfessionalGroups = state.wideHeader,
            buildActions = buildActions,
            debugActions = debugActions,
            toolActions = toolActions,
            onDevice = actions.onAndroidDevelopment,
            onSettings = actions.onSettings,
            onAgent = actions.onAgent,
            onFocusMode = actions.onFocusMode,
            mcpHealth = state.mcpHealth,
            onMcpRetry = actions.onMcpRetry,
            onManageMcp = actions.onManageMcp,
            onMore = { moreMenu = true },
            showProjectsButton = state.showProjectsButton,
        )
        Box(Modifier.align(Alignment.TopEnd).padding(top = 42.dp, end = 4.dp)) {
            HeaderMoreMenu(state, moreMenu, { moreMenu = false }, buildActions, debugActions, toolActions, actions)
        }
    }
    if (state.busy) LinearProgressIndicator()
}

@Composable
private fun HeaderMoreMenu(
    state: DroideIdeHeaderState,
    expanded: Boolean,
    onDismiss: () -> Unit,
    buildActions: List<DroideHeaderAction>,
    debugActions: List<DroideHeaderAction>,
    toolActions: List<DroideHeaderAction>,
    actions: DroideIdeHeaderActions,
) {
    fun closeThen(action: () -> Unit) {
        onDismiss()
        action()
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem({ Text("Projects") }, onClick = { closeThen(actions.onProjects) }, leadingIcon = { Icon(Icons.Default.Folder, null) })
        DropdownMenuItem({ Text(if (state.agentOpen) "Close AI" else "Open AI") }, onClick = { closeThen(actions.onAgent) }, leadingIcon = { Icon(Icons.Default.SmartToy, null) })
        DropdownMenuItem({ Text("Run current file or app") }, enabled = !state.busy,
            onClick = { closeThen(actions.onRun) }, leadingIcon = { Icon(Icons.Default.PlayArrow, null) })
        DropdownMenuItem({ Text("Manage MCP") }, onClick = { closeThen(actions.onManageMcp) }, leadingIcon = { Icon(Icons.Default.Hub, null) })
        HorizontalDivider()
        if (!state.wideHeader) {
            HeaderOverflowSection("BUILD", buildActions, onDismiss)
            HorizontalDivider()
            HeaderOverflowSection("DEBUG", debugActions, onDismiss)
            HorizontalDivider()
            HeaderOverflowSection("TOOLS", toolActions, onDismiss)
            HorizontalDivider()
        }
        DropdownMenuItem({ Text("Command Palette") }, onClick = { closeThen(actions.onCommandPalette) }, leadingIcon = { Icon(Icons.Default.Search, null) })
        DropdownMenuItem({ Text("Connect Provider") }, onClick = { closeThen(actions.onConnectProvider) }, leadingIcon = { Icon(Icons.Default.Cloud, null) })
        DropdownMenuItem({ Text("Models") }, onClick = { closeThen(actions.onModels) }, leadingIcon = { Icon(Icons.Default.Memory, null) })
        DropdownMenuItem({ Text("Billing / Usage") }, onClick = { closeThen(actions.onBilling) }, leadingIcon = { Icon(Icons.Default.ReceiptLong, null) })
        DropdownMenuItem({ Text("Notification settings") }, onClick = { closeThen(actions.onNotificationSettings) }, leadingIcon = { Icon(Icons.Default.Notifications, null) })
        DropdownMenuItem({ Text("Settings") }, onClick = { closeThen(actions.onSettings) }, leadingIcon = { Icon(Icons.Default.Settings, null) })
    }
}

@Composable
private fun HeaderOverflowSection(
    title: String,
    actions: List<DroideHeaderAction>,
    onDismiss: () -> Unit,
) {
    Text(title, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
    actions.forEach { action ->
        DropdownMenuItem(
            text = {
                Column {
                    Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    action.supportingText?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
            onClick = { onDismiss(); action.onClick() },
            enabled = action.enabled,
            leadingIcon = { Icon(action.icon, null) },
        )
    }
}
