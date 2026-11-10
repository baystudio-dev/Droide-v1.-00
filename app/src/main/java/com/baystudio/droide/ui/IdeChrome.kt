package com.baystudio.droide.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Width and height are classified independently so short landscape phones do not inherit tablet
// chrome.
enum class DroideWindowClass { Compact, Medium, Expanded }

enum class DroideWidthTier { Compact, Medium, Expanded, Large, ExtraLarge }
enum class DroideHeightClass { Compact, Medium, Expanded }

data class DroideWindowProfile(
    val widthClass: DroideWindowClass,
    val widthTier: DroideWidthTier,
    val heightClass: DroideHeightClass,
    val layoutClass: DroideWindowClass,
) {
    val shortHeight: Boolean get() = heightClass == DroideHeightClass.Compact
}

enum class DroideRailTool(val label: String, val icon: ImageVector) {
    Files("Files", Icons.Default.Folder),
    Search("Search", Icons.Default.Search),
    Git("Source Control", Icons.Default.Source),
    Run("Run & Debug", Icons.Default.BugReport),
    Web("Web", Icons.Default.Language),
    Extensions("Extensions", Icons.Default.Extension),
}

enum class DroideBottomTool(val label: String) { Terminal("Terminal"), Output("Output"), Problems("Problems"), Build("Build") }

data class DroideHeaderAction(
    val label: String,
    val icon: ImageVector,
    val section: String,
    val enabled: Boolean = true,
    val supportingText: String? = null,
    val onClick: () -> Unit,
)

fun droideWindowClass(width: Dp): DroideWindowClass = when {
    width < 600.dp -> DroideWindowClass.Compact
    width < 840.dp -> DroideWindowClass.Medium
    else -> DroideWindowClass.Expanded
}

fun droideWidthTier(width: Dp): DroideWidthTier = when {
    width < 600.dp -> DroideWidthTier.Compact
    width < 840.dp -> DroideWidthTier.Medium
    width < 1_200.dp -> DroideWidthTier.Expanded
    width < 1_600.dp -> DroideWidthTier.Large
    else -> DroideWidthTier.ExtraLarge
}

fun droideHeightClass(height: Dp): DroideHeightClass = when {
    height < 480.dp -> DroideHeightClass.Compact
    height < 900.dp -> DroideHeightClass.Medium
    else -> DroideHeightClass.Expanded
}

fun droideWindowProfile(width: Dp, height: Dp): DroideWindowProfile {
    val widthClass = droideWindowClass(width)
    val widthTier = droideWidthTier(width)
    val heightClass = droideHeightClass(height)

    val layoutClass = if (heightClass == DroideHeightClass.Compact && widthClass == DroideWindowClass.Expanded) {
        DroideWindowClass.Medium
    } else widthClass
    return DroideWindowProfile(widthClass, widthTier, heightClass, layoutClass)
}

data class DroideWorkbenchLayout(
    val windowClass: DroideWindowClass,
    val widthTier: DroideWidthTier,
    val compact: Boolean,
    val medium: Boolean,
    val expanded: Boolean,
    val landscape: Boolean,
    val shortHeight: Boolean,
    val wideHeader: Boolean,
    val sidebarWidth: Dp,
    val wideSidebarWidth: Dp,
    val agentPanelMinWidth: Dp,
    val agentPanelMaxWidth: Dp,
    val mediumAgentWidth: Dp,
    val minimumEditorWidth: Dp,
    val bottomPanelMaxHeight: Dp,
    val effectiveBottomPanelHeight: Dp,
)

fun droideWorkbenchLayout(
    width: Dp,
    height: Dp,
    requestedBottomPanelHeight: Dp,
    physicalLandscape: Boolean = width > height,
): DroideWorkbenchLayout {
    val profile = droideWindowProfile(width, height)
    // A desktop minimum cannot exceed a keyboard-reduced or split-screen window.
    val availableHeight = height.coerceAtLeast(0.dp)
    val panelMax = minOf(DroideDimensions.BottomPanelMax, availableHeight * .6f,
        (availableHeight * .42f).coerceAtLeast(DroideDimensions.BottomPanelMin))
    val panelMin = minOf(DroideDimensions.BottomPanelMin, panelMax)
    val panelRequest = requestedBottomPanelHeight.takeIf { it.value.isFinite() } ?: DroideDimensions.BottomPanel

    val landscape = physicalLandscape

    val sidebarWidth = when (profile.widthTier) {
        DroideWidthTier.Large -> if (landscape) 284.dp else 272.dp
        DroideWidthTier.ExtraLarge -> if (landscape) 300.dp else 288.dp
        else -> if (landscape) 260.dp else DroideDimensions.Sidebar
    }
    val wideSidebarWidth = when (profile.widthTier) {
        DroideWidthTier.Large -> if (landscape) 360.dp else 344.dp
        DroideWidthTier.ExtraLarge -> if (landscape) 384.dp else 368.dp
        else -> if (landscape) 336.dp else DroideDimensions.WideSidebar
    }
    val agentPanelMinWidth = if (landscape) {
        if (width >= 840.dp) 264.dp else 176.dp
    } else DroideDimensions.AgentPanelMin
    val agentPanelMaxWidth = if (landscape) {
        when (profile.widthTier) {
            DroideWidthTier.ExtraLarge -> 560.dp
            DroideWidthTier.Large -> 480.dp
            DroideWidthTier.Expanded -> 420.dp
            else -> 296.dp
        }
    } else {
        when (profile.widthTier) {
            DroideWidthTier.ExtraLarge -> 520.dp
            DroideWidthTier.Large -> 440.dp
            DroideWidthTier.Expanded -> 392.dp
            else -> DroideDimensions.AgentPanel
        }
    }
    val mediumAgentWidth = if (landscape) {
        minOf(296.dp, (width * .28f).coerceAtLeast(agentPanelMinWidth))
    } else {
        minOf(320.dp, (width * .38f).coerceAtLeast(DroideDimensions.AgentPanelMin))
    }
    val minimumEditorWidth = when (profile.widthTier) {
        DroideWidthTier.ExtraLarge -> 640.dp
        DroideWidthTier.Large -> 560.dp
        DroideWidthTier.Expanded -> 480.dp
        else -> 360.dp
    }
    return DroideWorkbenchLayout(
        windowClass = profile.layoutClass,
        widthTier = profile.widthTier,
        compact = profile.layoutClass == DroideWindowClass.Compact,
        medium = profile.layoutClass == DroideWindowClass.Medium,
        expanded = profile.layoutClass == DroideWindowClass.Expanded,
        landscape = landscape,
        shortHeight = profile.shortHeight,

        wideHeader = width >= 1_040.dp && !profile.shortHeight,
        sidebarWidth = sidebarWidth,
        wideSidebarWidth = wideSidebarWidth,
        agentPanelMinWidth = agentPanelMinWidth,
        agentPanelMaxWidth = agentPanelMaxWidth,
        mediumAgentWidth = mediumAgentWidth,
        minimumEditorWidth = minimumEditorWidth,
        bottomPanelMaxHeight = panelMax,
        effectiveBottomPanelHeight = panelRequest.coerceIn(panelMin, panelMax),
    )
}

data class DroidePaneFit(
    val sidebarWidth: Dp,
    val agentWidth: Dp,
    val agentMinWidth: Dp,
    val agentMaxWidth: Dp,
    val editorViewportWidth: Dp,
    val editorCanvasWidth: Dp,
    val workbenchContentWidth: Dp,
    val workbenchOverflowWidth: Dp,
    val editorUnderPressure: Boolean,
)

fun droidePaneFit(
    layout: DroideWorkbenchLayout,
    availableWidth: Dp,
    sidebarTool: DroideRailTool,
    sidebarVisible: Boolean,
    agentVisible: Boolean,
    requestedAgentWidth: Dp,
): DroidePaneFit {
    if (layout.landscape) return droideLandscapePaneFit(layout, availableWidth, sidebarTool,
        sidebarVisible, agentVisible, requestedAgentWidth)
    val usable = (availableWidth - DroideDimensions.ActivityRail).coerceAtLeast(0.dp)
    val editorMinimum = minOf(layout.minimumEditorWidth, maxOf(180.dp, usable * .35f), usable)
    val desiredSidebar = if (!sidebarVisible) 0.dp else if (sidebarTool == DroideRailTool.Files || sidebarTool == DroideRailTool.Search) layout.sidebarWidth else layout.wideSidebarWidth
    val handle = if (agentVisible) minOf(10.dp, (usable - editorMinimum).coerceAtLeast(0.dp)) else 0.dp
    val minimumAgent = if (agentVisible) minOf(layout.agentPanelMinWidth, (usable - editorMinimum - handle).coerceAtLeast(0.dp)) else 0.dp
    val sidebarBudget = (usable - editorMinimum - handle - minimumAgent - 1.dp).coerceAtLeast(0.dp)
    // If both cannot remain usable, the presentation coordinator selects one side panel.
    val sidebarFits = !agentVisible || sidebarBudget >= minOf(180.dp, desiredSidebar)
    val sidebarWidth = if (sidebarFits) minOf(desiredSidebar, sidebarBudget) else 0.dp
    val sidebarDividerWidth = if (sidebarWidth > 0.dp) 1.dp else 0.dp
    val agentMaxWidth = if (agentVisible) minOf(layout.agentPanelMaxWidth,
        (usable - editorMinimum - sidebarWidth - sidebarDividerWidth - handle).coerceAtLeast(0.dp)) else 0.dp
    val agentMinWidth = minOf(layout.agentPanelMinWidth, agentMaxWidth)
    val safeAgentRequest = requestedAgentWidth.takeIf { it.value.isFinite() } ?: layout.agentPanelMinWidth
    val agentWidth = if (agentVisible) safeAgentRequest.coerceIn(agentMinWidth, agentMaxWidth) else 0.dp
    val editorViewportWidth = (usable - sidebarWidth - sidebarDividerWidth - handle - agentWidth).coerceAtLeast(0.dp)
    val editorCanvasWidth = editorViewportWidth
    val workbenchContentWidth = sidebarWidth + sidebarDividerWidth + editorViewportWidth + handle + agentWidth
    val workbenchOverflowWidth = (workbenchContentWidth - usable).coerceAtLeast(0.dp)
    val editorUnderPressure = editorViewportWidth < layout.minimumEditorWidth

    return DroidePaneFit(
        sidebarWidth = sidebarWidth,
        agentWidth = agentWidth,
        agentMinWidth = agentMinWidth,
        agentMaxWidth = agentMaxWidth,
        editorViewportWidth = editorViewportWidth,
        editorCanvasWidth = editorCanvasWidth,
        workbenchContentWidth = workbenchContentWidth,
        workbenchOverflowWidth = workbenchOverflowWidth,
        editorUnderPressure = editorUnderPressure,
    )
}

@Composable
fun DroideAdaptiveWorkbenchEffects(
    layout: DroideWorkbenchLayout,
    requestedBottomPanelHeight: Dp,
    onClampBottomPanelHeight: (Dp) -> Unit,
) {
    LaunchedEffect(layout.effectiveBottomPanelHeight, requestedBottomPanelHeight) {
        if (requestedBottomPanelHeight != layout.effectiveBottomPanelHeight) onClampBottomPanelHeight(layout.effectiveBottomPanelHeight)
    }
}

@Composable
fun DroideTopBar(
    projectName: String,
    activeFile: String,
    compact: Boolean,
    agentOpen: Boolean,
    landscape: Boolean,
    focusMode: Boolean,
    running: Boolean,
    runLabel: String,
    runDescription: String,
    onProjects: () -> Unit,
    onRun: () -> Unit,
    showProfessionalGroups: Boolean,
    buildActions: List<DroideHeaderAction>,
    debugActions: List<DroideHeaderAction>,
    toolActions: List<DroideHeaderAction>,
    onDevice: () -> Unit,
    onSettings: () -> Unit,
    onAgent: () -> Unit,
    onFocusMode: () -> Unit,
    mcpHealth: List<com.baystudio.droide.core.McpHealthSnapshot>,
    onMcpRetry: (String) -> Unit,
    onManageMcp: () -> Unit,
    onMore: () -> Unit,
    showProjectsButton: Boolean = true,
) {
    Surface(color = DroideColors.Surface, tonalElevation = 0.dp) {
        CutoutHeaderRow(endAlignActions = compact && !landscape && !showProfessionalGroups) {
            if (showProjectsButton) Box(Modifier.headerPart(HeaderPart.PROJECTS)) { DroideIconButton(Icons.Default.Menu, "Projects", onProjects) }
            Box(Modifier.headerPart(HeaderPart.MCP)) { McpHeaderStatusButton(mcpHealth, onRetry = onMcpRetry, onManage = onManageMcp) }
            Column(Modifier.headerPart(HeaderPart.TITLE).padding(horizontal = if (compact) 2.dp else 6.dp)) {
                Text(projectName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                if (!compact && activeFile.isNotBlank()) {
                    FileIdentityLabel(activeFile, activeFile, iconSize = 13.dp, color = DroideColors.Muted)
                }
            }
            if (!compact) {
                OutlinedButton(
                    onClick = onRun,
                    enabled = !running,
                    modifier = Modifier.headerPart(HeaderPart.RUN).heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    border = BorderStroke(1.dp, DroideColors.Border),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = DroideColors.Surface2),
                ) {
                    Icon(if (running) Icons.Default.HourglassTop else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (running) "Running" else runLabel, style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Box(Modifier.headerPart(HeaderPart.RUN)) { DroideIconButton(Icons.Default.PlayArrow, runDescription, onRun, enabled = !running) }
            }
            if (showProfessionalGroups) {
                HeaderActionGroup(Icons.Default.Build, "Build", buildActions)
                HeaderActionGroup(Icons.Default.BugReport, "Debug", debugActions)
                HeaderActionGroup(Icons.Default.Tune, "Tools", toolActions)
            }
            Box(Modifier.headerPart(HeaderPart.SETTINGS)) {
                if (compact && !landscape) {
                    DroideIconButton(Icons.Default.Settings, "Settings", onSettings)
                } else {
                    DroideIconButton(Icons.Default.PhoneAndroid, "Device & Android toolchain", onDevice)
                }
            }
            if (landscape) {
                IconButton(
                    onClick = onFocusMode,
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (focusMode) DroideColors.Primary else DroideColors.Muted),
                ) { Icon(if (focusMode) Icons.Default.Visibility else Icons.Outlined.Visibility, if (focusMode) "Landscape focus mode on" else "Landscape focus mode off", Modifier.size(if (focusMode) 19.dp else 18.dp)) }
            }
            FilledTonalIconButton(
                onClick = onAgent,
                modifier = Modifier.headerPart(HeaderPart.AGENT).size(DroideDimensions.PrimaryTouch),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (agentOpen) DroideColors.Primary.copy(alpha = .16f) else Color.Transparent,
                    contentColor = if (agentOpen) DroideColors.Primary else DroideColors.Muted,
                ),
            ) { Icon(if (agentOpen) Icons.Default.Close else Icons.Default.SmartToy, if (agentOpen) "Close AI" else "Open AI") }
            Box(Modifier.headerPart(HeaderPart.MORE)) { DroideIconButton(Icons.Default.MoreVert, "More", onMore) }
        }
    }
    HorizontalDivider(color = DroideColors.Border, thickness = 1.dp)
}

@Composable
private fun HeaderActionGroup(
    icon: ImageVector,
    description: String,
    actions: List<DroideHeaderAction>,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        DroideIconButton(icon, "$description tools", { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            var previousSection: String? = null
            actions.forEach { action ->
                if (action.section != previousSection) {
                    if (previousSection != null) HorizontalDivider()
                    Text(
                        action.section.uppercase(),
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = DroideColors.Muted,
                    )
                    previousSection = action.section
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            action.supportingText?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    enabled = action.enabled,
                    leadingIcon = { Icon(action.icon, null) },
                )
            }
        }
    }
}

@Composable
fun DroideActivityRail(
    selected: DroideRailTool,
    sidebarVisible: Boolean,
    bottomPanelVisible: Boolean,
    onTool: (DroideRailTool) -> Unit,
    onTerminal: () -> Unit,
    onProjects: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = DroideColors.Surface) {
        BoxWithConstraints(Modifier.width(DroideDimensions.ActivityRail).fillMaxHeight()) {
            val requiresScroll = maxHeight < 480.dp
            if (requiresScroll) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RailAction(Icons.Default.Menu, "Projects", false, onProjects)
                    HorizontalDivider(Modifier.width(28.dp).padding(vertical = 4.dp), color = DroideColors.Border)
                    DroideRailTool.entries.forEach { tool ->
                        RailAction(tool.icon, tool.label, selected == tool && sidebarVisible) { onTool(tool) }
                    }
                    RailAction(Icons.Default.Terminal, "Terminal", bottomPanelVisible, onTerminal)
                    RailAction(Icons.Default.Settings, "Settings", false, onSettings)
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RailAction(Icons.Default.Menu, "Projects", false, onProjects)
                    HorizontalDivider(Modifier.width(28.dp).padding(vertical = 4.dp), color = DroideColors.Border)
                    DroideRailTool.entries.forEach { tool ->
                        RailAction(tool.icon, tool.label, selected == tool && sidebarVisible) { onTool(tool) }
                    }
                    RailAction(Icons.Default.Terminal, "Terminal", bottomPanelVisible, onTerminal)
                    Spacer(Modifier.weight(1f))
                    RailAction(Icons.Default.Settings, "Settings", false, onSettings)
                }
            }
        }
    }
}

@Composable
private fun RailAction(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (selected) DroideColors.Primary else DroideColors.Muted,
        tween(DroideMotion.SelectionMillis), label = "railSelection",
    )
    val background = animateColorAsState(
        if (selected) DroideColors.Primary.copy(alpha = .12f) else Color.Transparent,
        tween(DroideMotion.SelectionMillis), label = "railSelectionBackground",
    )
    Box(Modifier.width(DroideDimensions.ActivityRail).height(48.dp), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(24.dp).background(DroideColors.Primary))
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp).semantics { this.selected = selected }
                .clip(MaterialTheme.shapes.large).drawBehind { drawRect(background.value) },
            colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
        ) { Icon(icon, label, Modifier.size(20.dp)) }
    }
}

@Composable
fun DroideSidebarFrame(title: String, width: Dp, onClose: () -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.width(width).fillMaxHeight(), color = DroideColors.Surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 41.dp).padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                DroideIconButton(Icons.Default.Close, "Close $title", onClose, size = 36.dp)
            }
            HorizontalDivider(color = DroideColors.Border)
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

@Composable
fun DroideEditorToolbar(
    path: String,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSave: () -> Unit,
    onSymbols: () -> Unit,
    onGoToLine: () -> Unit,
    markdownPreviewMode: MarkdownPreviewMode? = null,
    onMarkdownPreviewModeChange: ((MarkdownPreviewMode) -> Unit)? = null,
    onResetZoom: (() -> Unit)? = null,
) {
    val breadcrumbStyle = if (path.isBlank()) MaterialTheme.typography.labelSmall else LocalTextStyle.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(textControlHeight(breadcrumbStyle, 38.dp))) {
        val availableWidth = maxWidth
        val showNavigation = availableWidth >= 180.dp
        val showContextActions = availableWidth >= 286.dp
        val zoomMinWidth = if (markdownPreviewMode != null && onMarkdownPreviewModeChange != null) 220.dp else 126.dp
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showNavigation) {
                DroideIconButton(Icons.Default.ArrowBack, "Navigate back", onBack, enabled = canBack, size = 34.dp)
                DroideIconButton(Icons.Default.ArrowForward, "Navigate forward", onForward, enabled = canForward, size = 34.dp)
            }
            if (availableWidth >= 92.dp) {
                if (path.isNotBlank()) FileIdentityLabel(path, path, Modifier.weight(1f).padding(horizontal = 4.dp), iconSize = 14.dp, color = DroideColors.Muted)
                else Text("No file selected", Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = DroideColors.Muted, style = MaterialTheme.typography.labelSmall)
            } else Spacer(Modifier.weight(1f))
            if (showContextActions) {
                DroideIconButton(Icons.Default.AccountTree, "Workspace symbols", onSymbols, size = 34.dp)
            }
            if (onResetZoom != null && availableWidth >= zoomMinWidth) {
                DroideIconButton(Icons.Default.RestartAlt, "Reset editor zoom", onResetZoom, size = 34.dp)
            }
            if (showContextActions) {
                DroideIconButton(Icons.Default.FormatListNumbered, "Go to line", onGoToLine, size = 34.dp)
            }
            if (markdownPreviewMode != null && onMarkdownPreviewModeChange != null) {
                var markdownMenu by remember { mutableStateOf(false) }
                Box {
                    val previewIcon = when (markdownPreviewMode) {
                        MarkdownPreviewMode.EDITOR -> Icons.Default.Edit
                        MarkdownPreviewMode.PREVIEW -> Icons.Outlined.Visibility
                        MarkdownPreviewMode.SPLIT -> Icons.Default.VerticalSplit
                    }
                    DroideIconButton(previewIcon, "Markdown view", { markdownMenu = true }, size = 34.dp)
                    DropdownMenu(expanded = markdownMenu, onDismissRequest = { markdownMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Editor") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { markdownMenu = false; onMarkdownPreviewModeChange(MarkdownPreviewMode.EDITOR) },
                        )
                        DropdownMenuItem(
                            text = { Text("Preview") },
                            leadingIcon = { Icon(Icons.Outlined.Visibility, null) },
                            onClick = { markdownMenu = false; onMarkdownPreviewModeChange(MarkdownPreviewMode.PREVIEW) },
                        )
                        DropdownMenuItem(
                            text = { Text("Split") },
                            leadingIcon = { Icon(Icons.Default.VerticalSplit, null) },
                            onClick = { markdownMenu = false; onMarkdownPreviewModeChange(MarkdownPreviewMode.SPLIT) },
                        )
                    }
                }
            }
            DroideIconButton(Icons.Default.Save, "Save", onSave, size = 34.dp)
        }
    }
    HorizontalDivider(color = DroideColors.Border.copy(alpha = .65f))
}

@Composable
fun DroideCompactNavigation(selected: Int, onSelect: (Int) -> Unit) =
    DroideCompactNavigation(selected, enabled = true, onSelect = onSelect)

@Composable
fun DroideCompactNavigation(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple(0, "Files", Icons.Default.Folder),
        Triple(1, "Editor", Icons.Default.Edit),
        Triple(2, "Terminal", Icons.Default.Terminal),
        Triple(3, "Git", Icons.Default.Source),
        Triple(4, "Web", Icons.Default.Language),
        Triple(6, "Extensions", Icons.Default.Extension),
    )
    Surface(color = DroideColors.Surface) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
            items.forEach { (id, label, icon) ->
                val active = selected == id
                val tint by animateColorAsState(
                    if (active) DroideColors.Primary else DroideColors.Muted,
                    tween(DroideMotion.SelectionMillis), label = "navigationSelection",
                )
                val background = animateColorAsState(
                    if (active) DroideColors.Primary.copy(alpha = .12f) else Color.Transparent,
                    tween(DroideMotion.SelectionMillis), label = "navigationSelectionBackground",
                )
                Column(
                    Modifier.weight(1f).heightIn(min = 58.dp)
                        .selectable(selected = active, enabled = enabled, role = Role.Tab, onClick = { onSelect(id) })
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.clip(MaterialTheme.shapes.large)
                            .drawBehind { drawRect(background.value) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) { Icon(icon, null, Modifier.size(19.dp), tint = tint) }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun DroideBottomPanelFrame(
    active: DroideBottomTool,
    height: Dp,
    maxHeight: Dp = DroideDimensions.BottomPanelMax,
    onHeightChange: (Dp) -> Unit,
    onSelect: (DroideBottomTool) -> Unit,
    onCollapse: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(height).background(DroideColors.Background)) {
        Box(
            Modifier.fillMaxWidth().height(10.dp).droideResizeHandle(
                DroideResizeAxis.Vertical, height, minOf(DroideDimensions.BottomPanelMin, maxHeight),
                maxHeight, "Resize bottom panel", onHeightChange,
            ),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(42.dp).height(2.dp).background(DroideColors.BorderStrong)) }
        Row(Modifier.fillMaxWidth().heightIn(min = 35.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                DroideBottomTool.entries.forEach { tool ->
                    TextButton(onClick = { onSelect(tool) }) {
                        Text(tool.label, color = if (active == tool) DroideColors.Text else DroideColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            DroideIconButton(Icons.Default.Close, "Close bottom panel", onCollapse, size = 34.dp)
        }
        HorizontalDivider(color = DroideColors.Border)
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
fun DroideResizableAgentPanel(width: Dp, minWidth: Dp = DroideDimensions.AgentPanelMin, maxWidth: Dp, onWidthChange: (Dp) -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.width(width + 10.dp).fillMaxHeight()) {
        Box(
            Modifier.width(10.dp).fillMaxHeight().droideResizeHandle(
                DroideResizeAxis.Horizontal, width, minWidth, maxWidth,
                "Resize agent panel", onWidthChange,
            ),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(1.dp).fillMaxHeight().background(DroideColors.Border)) }
        Surface(Modifier.width(width).fillMaxHeight(), color = DroideColors.Surface) { content() }
    }
}

@Composable
fun DroidePanelHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val leadingWidth = if (onBack != null) 36.dp else 6.dp
        val closeWidth = if (onClose != null) 36.dp else 0.dp
        val titleBudget = minOf(96.dp, maxWidth * .35f)
        val actionsBudget = (maxWidth - leadingWidth - closeWidth - titleBudget - 8.dp).coerceAtLeast(0.dp)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) DroideIconButton(Icons.Default.ArrowBack, "Back", onBack, size = 36.dp)
            else Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.widthIn(max = actionsBudget).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, content = actions)
            if (onClose != null) DroideIconButton(Icons.Default.Close, "Close $title", onClose, size = 36.dp)
        }
    }
    HorizontalDivider(color = DroideColors.Border)
}

@Composable
fun DroideIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true, size: Dp = DroideDimensions.PrimaryTouch) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size)) {
        Icon(icon, description, Modifier.size(20.dp), tint = if (enabled) LocalContentColor.current else DroideColors.Muted.copy(alpha = .5f))
    }
}
