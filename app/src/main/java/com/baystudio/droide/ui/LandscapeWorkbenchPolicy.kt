package com.baystudio.droide.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun droideLandscapePaneFit(
    layout: DroideWorkbenchLayout,
    availableWidth: Dp,
    sidebarTool: DroideRailTool,
    sidebarVisible: Boolean,
    agentVisible: Boolean,
    requestedAgentWidth: Dp,
): DroidePaneFit {
    val usable = (availableWidth - DroideDimensions.ActivityRail).coerceAtLeast(0.dp)
    val paneBudget = (usable - 11.dp).coerceAtLeast(0.dp)
    val desiredSidebar = if (sidebarTool in setOf(DroideRailTool.Files, DroideRailTool.Search))
        layout.sidebarWidth else layout.wideSidebarWidth
    val sidebar = if (sidebarVisible) minOf(desiredSidebar, paneBudget * .28f) else 0.dp
    val maximumAgent = if (agentVisible) minOf(layout.agentPanelMaxWidth, paneBudget * .38f) else 0.dp
    val minimumAgent = minOf(layout.agentPanelMinWidth, maximumAgent)
    val request = requestedAgentWidth.takeIf { it.value.isFinite() } ?: layout.agentPanelMinWidth
    val agent = if (agentVisible) request.coerceIn(minimumAgent, maximumAgent) else 0.dp
    val separators = (if (sidebar > 0.dp) 1.dp else 0.dp) + (if (agent > 0.dp) 10.dp else 0.dp)
    val editor = (usable - sidebar - agent - separators).coerceAtLeast(0.dp)
    val content = sidebar + agent + separators + editor
    return DroidePaneFit(sidebar, agent, minimumAgent, maximumAgent, editor, editor,
        content, (content - usable).coerceAtLeast(0.dp), editor < layout.minimumEditorWidth)
}

/** Chat input never opts into editor/terminal focus layout. Portrait retains its existing chrome. */
internal fun droideHideLandscapeHeader(
    landscape: Boolean, editorTyping: Boolean, terminalExpanded: Boolean,
): Boolean = landscape && (editorTyping || terminalExpanded)

/** Otherwise a short terminal below the editor can subtract the entire keyboard height again and
 * lose its input surface while the chat composer owns focus. */
@Composable
internal fun Modifier.landscapeWorkbenchImePadding(
    landscape: Boolean,
    ime: WindowInsets = WindowInsets.ime,
): Modifier = if (landscape) windowInsetsPadding(ime) else this
