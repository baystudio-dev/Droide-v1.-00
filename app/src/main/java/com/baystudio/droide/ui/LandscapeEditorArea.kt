package com.baystudio.droide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A persistent center column: maximizing a terminal changes geometry, never its parent
 * or native view identity. The user's normal panel size/visibility remain separate state.
 */
@Composable
internal fun LandscapeEditorArea(
    workspaceKey: String,
    activeTool: DroideBottomTool,
    panelVisible: Boolean,
    panelHeight: Dp,
    panelMaxHeight: Dp,
    editorTyping: Boolean,
    terminalExpanded: Boolean,
    onPanelHeightChange: (Dp) -> Unit,
    onSelectTool: (DroideBottomTool) -> Unit,
    onOpenPanel: () -> Unit,
    onClosePanel: () -> Unit,
    onRestoreTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    motion: LandscapeWorkbenchMotion = rememberLandscapeWorkbenchMotion(editorTyping, terminalExpanded, panelVisible),
    panelActions: @Composable () -> Unit = {},
    editor: @Composable () -> Unit,
    panel: @Composable (typingFocus: Boolean) -> Unit,
) {
    val workspacePresented = LocalWorkspacePresented.current
    val panelPresented = terminalExpanded || (panelVisible && !editorTyping)
    var panelMounted by remember(workspaceKey) { mutableStateOf(panelPresented) }
    SideEffect { if (panelPresented) panelMounted = true }
    val editorDrawn by remember(motion) { derivedStateOf { motion.terminal.value < 1f } }
    val panelDrawn by remember(motion) {
        derivedStateOf { motion.normal.value * motion.panel.value + motion.terminal.value > 0f }
    }
    Layout(modifier = modifier, content = {
            Box(Modifier.fillMaxWidth().clipToBounds().testTag("landscape-editor-region")) {
                RetainedWorkspaceContent(workspacePresented && !terminalExpanded,
                    drawWhileHidden = workspacePresented && editorDrawn, content = editor)
            }
            Column(Modifier.fillMaxWidth().clipToBounds()
                .background(DroideColors.Background).testTag("landscape-panel-region")) {
                RetainedWorkspaceContent(workspacePresented && panelPresented,
                    drawWhileHidden = workspacePresented && panelDrawn) {
                    Column(Modifier.fillMaxSize()) {
                        LandscapeChromeReveal({ 1f - motion.terminal.value }, !terminalExpanded) {
                            Box(Modifier.fillMaxWidth().height(10.dp).droideResizeHandle(
                                DroideResizeAxis.Vertical, panelHeight,
                                minOf(DroideDimensions.BottomPanelMin, panelMaxHeight), panelMaxHeight,
                                "Resize bottom panel", onPanelHeightChange), contentAlignment = Alignment.Center) {
                                Box(Modifier.width(42.dp).height(2.dp).background(DroideColors.BorderStrong))
                            }
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 35.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                                DroideBottomTool.entries.forEach { tool ->
                                    TextButton(onClick = { onSelectTool(tool) }) {
                                        Text(tool.label, style = MaterialTheme.typography.labelSmall,
                                            color = if (activeTool == tool) DroideColors.Text else DroideColors.Muted)
                                    }
                                }
                            }
                            panelActions()
                            DroideIconButton(Icons.Default.Close,
                                if (terminalExpanded) "Restore terminal size" else "Close bottom panel",
                                if (terminalExpanded) onRestoreTerminal else onClosePanel, size = 34.dp)
                        }
                        HorizontalDivider(color = DroideColors.Border)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // Do not initialize a hidden terminal that the user has never opened.
                            if (panelMounted || panelPresented) panel(terminalExpanded)
                        }
                    }
                }
            }
            LandscapeChromeReveal({ motion.normal.value * (1f - motion.panel.value) },
                workspacePresented && !panelPresented && !editorTyping) {
                Row(Modifier.fillMaxWidth().height(25.dp).background(DroideColors.Surface),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        DroideBottomTool.entries.forEach { tool ->
                            TextButton(onClick = { onSelectTool(tool); onOpenPanel() },
                                contentPadding = PaddingValues(horizontal = 12.dp)) {
                                Text(tool.label, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                            }
                        }
                    }
                    DroideIconButton(Icons.Default.KeyboardArrowUp, "Open bottom panel", onOpenPanel, size = 34.dp)
                }
            }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = constraints.copy(minHeight = 0)
        val footer = measurables[2].measure(loose)
        val normal = panelHeight.roundToPx().coerceIn(0, height)
        val panelPixels = (normal * motion.normal.value * motion.panel.value + height * motion.terminal.value)
            .roundToInt().coerceIn(0, height - footer.height)
        val editorPixels = height - footer.height - panelPixels
        val editorPlaceable = measurables[0].measure(loose.copy(minHeight = editorPixels, maxHeight = editorPixels))
        val panelPlaceable = measurables[1].measure(loose.copy(minHeight = panelPixels, maxHeight = panelPixels))
        layout(width, height) {
            editorPlaceable.placeRelative(0, 0)
            panelPlaceable.placeRelative(0, editorPixels)
            footer.placeRelative(0, editorPixels + panelPixels)
        }
    }
}
