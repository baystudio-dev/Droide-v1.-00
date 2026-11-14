package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baystudio.droide.core.AgentChangeTotals
import com.baystudio.droide.core.AgentFileChanges
import com.baystudio.droide.core.AgentFileChange
import com.baystudio.droide.core.UnifiedDiffParser
import com.baystudio.droide.core.DiffLineKind

internal data class AgentCodeLine(val number: String?, val code: String)

/** Presentation only: the model retains the versioned read result unchanged. */
internal fun agentReadCodeLines(output: String): List<AgentCodeLine> {
    val numbered = Regex("^\\s*(\\d+)([|│])(.*)$")
    return output.lineSequence().take(400).mapNotNull { line ->
        if (line.startsWith("[DROIDE_DOCUMENT_STATE ") || line.startsWith("[DROIDE_READ_PAGE ") || line.startsWith("… showing lines ") ||
            line.startsWith("... showing lines ")) null
        else numbered.matchEntire(line)?.let {
            AgentCodeLine(it.groupValues[1], if (it.groupValues[2] == "│") it.groupValues[3].removePrefix(" ") else it.groupValues[3])
        }
            ?: AgentCodeLine(null, line)
    }.take(200).toList()
}

@Composable
internal fun AgentReadCodePreview(output: String, path: String) {
    val lines = remember(output) { agentReadCodeLines(output.take(12_000)) }
    val code = remember(lines) { lines.joinToString("\n") { it.code } }
    val colored = rememberHighlightedCode(code, codeLanguage(path))
    Surface(Modifier.fillMaxWidth(), color = DroideColors.Background, border = BorderStroke(1.dp, DroideColors.Border),
        shape = MaterialTheme.shapes.extraSmall) {
        Column(Modifier.fillMaxWidth()) {
            var offset = 0
            lines.forEach { line ->
                val text = colored.subSequence(offset, offset + line.code.length)
                offset += line.code.length + 1
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(line.number.orEmpty(), Modifier.width(38.dp).padding(horizontal = 3.dp),
                        color = DroideColors.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(text, Modifier.weight(1f).padding(end = 6.dp), color = DroideColors.Text,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = true,
                        maxLines = 32, overflow = TextOverflow.Ellipsis)
                }
            }
            if (output.length > 12_000 || output.lineSequence().take(401).count() > 200) {
                Text("Code preview shortened. Open the file to read more.", Modifier.padding(7.dp),
                    style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
        }
    }
}

internal fun legacyFileChange(output: String, path: String): AgentFileChange? {
    val files = UnifiedDiffParser.parse(output.take(16_000))
    val lines = files.flatMap { file -> file.hunks.flatMap { it.lines } }
    if (lines.isEmpty()) return null
    val truncated = output.length > 16_000 || output.contains("more removed lines") || output.contains("more added lines")
    val file = files.singleOrNull()
    val displayPath = path.ifBlank { file?.newPath ?: file?.oldPath.orEmpty() }
    val previousPath = file?.oldPath?.takeIf { file.newPath == displayPath && it != displayPath }
    return AgentFileChange(displayPath, output.take(16_000),
        if (truncated) null else lines.count { it.kind == DiffLineKind.ADD },
        if (truncated) null else lines.count { it.kind == DiffLineKind.REMOVE }, truncated, previousPath)
}

@Composable
internal fun AgentChangeCounts(changes: List<AgentFileChange>) {
    AgentChangeCounts(remember(changes) { AgentFileChanges.totals(changes) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentChangeCounts(totals: AgentChangeTotals?, proposed: Boolean = false, modifier: Modifier = Modifier,
                               proposedLabel: String = "Proposed") {
    if (totals == null) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (proposed) Text(proposedLabel, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        if (totals.known) {
            Text("+${totals.addedLines}", color = DroideColors.Success, style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace)
            Text("-${totals.removedLines}", color = DroideColors.Error, style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace)
        } else {
            Text(if (proposed) "Count unavailable" else "Preview limited", color = DroideColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun AgentFileChangesPreview(changes: List<AgentFileChange>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        changes.forEach { change ->
            Surface(Modifier.fillMaxWidth(), color = DroideColors.Background,
                border = BorderStroke(1.dp, DroideColors.Border), shape = MaterialTheme.shapes.extraSmall) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (change.path.isNotBlank()) {
                            FileIdentityLabel(change.path, modifier = Modifier.weight(1f), iconSize = 14.dp,
                                maxLines = Int.MAX_VALUE, monospaced = true, color = DroideColors.Text,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        AgentChangeCounts(listOf(change))
                    }
                    change.previousPath?.let { FileIdentityLabel(it, "Moved from $it",
                        Modifier.padding(horizontal = 7.dp), iconSize = 14.dp, maxLines = Int.MAX_VALUE,
                        style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) }
                    AgentFileDiffText(change)
                    if (change.truncated) Text("Preview shortened. Open the file to review all changes.", Modifier.padding(7.dp),
                        style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                }
            }
        }
    }
}
