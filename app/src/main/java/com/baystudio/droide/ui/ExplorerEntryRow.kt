package com.baystudio.droide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.FileEntry

internal data class ExplorerEntryStyle(
    val minimumHeight: Dp, val iconSize: Dp, val chevronSize: Dp,
    val actionSize: Dp, val actionIconSize: Dp, val label: TextStyle,
) {

    val reservedWidth: Dp get() = chevronSize + iconSize + actionSize + 88.dp + 8.dp + 6.dp
}

@Composable
internal fun explorerEntryStyle(portrait: Boolean): ExplorerEntryStyle = if (portrait)
    ExplorerEntryStyle(40.dp, 20.dp, 17.dp, 40.dp, 18.dp, MaterialTheme.typography.labelLarge)
else ExplorerEntryStyle(32.dp, 17.dp, 15.dp, 30.dp, 16.dp, MaterialTheme.typography.labelMedium)

/** Presentation only; the Explorer owner retains selection, directory loading and mutation authority. */
@Composable
internal fun ExplorerEntryRow(
    entry: FileEntry, depth: Int, expanded: Boolean, selected: Boolean, style: ExplorerEntryStyle,
    onOpen: () -> Unit, onActions: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = style.minimumHeight)
                .semantics { this.selected = selected }
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else DroideColors.Surface)
                .clickable(onClick = onOpen)
                .padding(start = 6.dp + explorerIndent(depth, maxWidth, style.reservedWidth), end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.isDir) {
                Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    if (expanded) "Collapse" else "Expand", Modifier.size(style.chevronSize), tint = DroideColors.Muted)
                Icon(Icons.Default.Folder, null, Modifier.size(style.iconSize), tint = DroideColors.Warning)
            } else {
                Spacer(Modifier.width(style.chevronSize))
                FileTypeBrandIcon(entry.path, size = style.iconSize)
            }
            Spacer(Modifier.width(6.dp))
            Text(entry.path.substringAfterLast('/'), Modifier.weight(1f), style = style.label,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onActions, Modifier.size(style.actionSize)) {
                Icon(Icons.Default.MoreVert, "File actions", Modifier.size(style.actionIconSize), tint = DroideColors.Muted)
            }
        }
    }
}
