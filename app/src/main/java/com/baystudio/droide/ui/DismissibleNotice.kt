package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun DismissibleNotice(
    message: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    ownerKey: Any? = null,
    actions: (@Composable () -> Unit)? = null,
) = DismissibleNotice(message = message, dismissalKey = message, modifier = modifier,
    color = color, ownerKey = ownerKey, actions = actions)

@Composable
internal fun DismissibleNotice(
    message: String,
    dismissalKey: Any,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    ownerKey: Any? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    var dismissed by rememberSaveable(ownerKey, dismissalKey) { mutableStateOf(false) }
    if (message.isBlank()) return
    if (!dismissed) {
        Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f).padding(vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = color)
            actions?.invoke()
            IconButton(onClick = { dismissed = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, "Dismiss message", Modifier.size(18.dp))
            }
        }
    } else if (actions != null) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
    }
}

@Composable
internal fun DismissibleSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            action = data.visuals.actionLabel?.let { label ->
                { TextButton(onClick = data::performAction) { Text(label) } }
            },
            dismissAction = {
                IconButton(onClick = data::dismiss) { Icon(Icons.Default.Close, "Dismiss message") }
            },
        ) { Text(data.visuals.message) }
    }
}
