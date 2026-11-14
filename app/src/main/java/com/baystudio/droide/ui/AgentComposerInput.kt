package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentMentionTarget
import kotlinx.coroutines.CancellationException

/** Owns cursor/IME state and temporary suggestions; the workspace still owns the draft. */
@Composable
internal fun AgentComposerInput(
    value: String,
    catalogKey: Any,
    commands: List<AgentComposerCommand>,
    loadMentions: suspend () -> List<AgentMentionTarget>,
    onValueChange: (String) -> Boolean,
    onCommandSelected: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    placeholder: String,
    maxHeight: Dp,
    dense: Boolean,
    trailingControl: @Composable () -> Unit,
) {
    var savedValue by rememberSaveable(catalogKey, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    val field = if (savedValue.text == value) savedValue else TextFieldValue(value, TextRange(value.length))
    val query = AgentComposerShortcuts.query(value, field.selection.end, field.selection.collapsed)
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf<AgentComposerQuery?>(null) }
    var mentions by remember(catalogKey) { mutableStateOf(emptyList<AgentMentionTarget>()) }
    var status by remember(catalogKey) { mutableStateOf<String?>(null) }
    val currentLoader by rememberUpdatedState(loadMentions)
    LaunchedEffect(catalogKey, focused && query != null) {
        if (focused && query != null) {
            mentions = emptyList()
            status = "Loading skills, plugins and MCP servers…"
            try { mentions = currentLoader(); status = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "Could not load suggestions. Check /skills, /plugins or /mcp." }
        }
    }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicTextField(
                enabled = LocalWorkspacePresented.current,
                value = field,
                onValueChange = { candidate ->
                    if (onValueChange(candidate.text)) { savedValue = candidate; dismissed = null }
                },
                singleLine = false, minLines = 1, maxLines = 10,
                modifier = Modifier.weight(1f).heightIn(min = if (dense) 24.dp else 27.dp, max = maxHeight)
                    .clipToBounds().onFocusChanged { focused = it.isFocused; onFocusChanged(it.isFocused) }
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = DroideColors.Text),
                cursorBrush = SolidColor(DroideColors.Text),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = DroideColors.Muted)
                        inner()
                    }
                },
            )
            trailingControl()
        }
        val active = query?.takeIf { focused && it != dismissed }
        val choices = active?.let { AgentComposerShortcuts.choices(it, commands, mentions) }.orEmpty()
        AgentComposerSuggestions(
            choices = choices,
            emptyMessage = if (active != null && choices.isEmpty())
                status ?: "No matching skill, plugin or MCP server. Use /skills, /plugins or /mcp." else null,
            onDismiss = { dismissed = active },
            onSelect = { choice ->
                if (active != null) {
                    val (next, caret) = AgentComposerShortcuts.insert(value, active, choice)
                    if (onValueChange(next)) {
                        savedValue = TextFieldValue(next, TextRange(caret))
                        if (choice.command) onCommandSelected()
                    }
                }
            },
        )
    }
}
