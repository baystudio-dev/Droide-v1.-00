package com.baystudio.droide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.TerminalManager

@Composable
internal fun LandscapeTerminalSessions(
    snapshot: TerminalManager.Snapshot,
    createEnabled: Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onCreate: () -> Unit,
    onInterrupt: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DroideIconButton(Icons.Default.Terminal, "Terminal sessions", { expanded = true }, size = 34.dp)
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            snapshot.tabs.forEach { tab ->
                DropdownMenuItem(text = { Text(tab.title) },
                    leadingIcon = { if (tab.id == snapshot.activeId) Icon(Icons.Default.Check, "Selected terminal", Modifier.size(18.dp)) },
                    onClick = { expanded = false; onSelect(tab.id) })
            }
            if (snapshot.tabs.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(text = { Text("New Ubuntu terminal") }, enabled = createEnabled,
                onClick = { expanded = false; onCreate() })
            snapshot.activeTab?.let { tab ->
                DropdownMenuItem(text = { Text("Send Ctrl+C") }, onClick = { expanded = false; onInterrupt() })
                DropdownMenuItem(text = { Text("Close terminal ${tab.title}") }, onClick = { expanded = false; onClose(tab.id) })
            }
        }
    }
}
