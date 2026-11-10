package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.StoredPreferenceRead
import com.baystudio.droide.core.observeStoredPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal class PreferenceReadRecovery<T>(initial: T) {
    var state by mutableStateOf(StoredPreferenceRead(initial, false, false))
        private set
    var attempt by mutableIntStateOf(0)
        private set
    fun retry() { state = state.copy(ready = false, failed = false); attempt++ }
    fun accept(next: StoredPreferenceRead<T>) { state = next }
}

@Composable
internal fun <T> rememberPreferenceReadRecovery(key: Any, initial: T, source: () -> Flow<T>): PreferenceReadRecovery<T> {
    val recovery = remember(key) { PreferenceReadRecovery(initial) }
    val latestSource by rememberUpdatedState(source)
    LaunchedEffect(recovery, recovery.attempt) {
        observeStoredPreferences(recovery.state.value) { latestSource() }.collect(recovery::accept)
    }
    return recovery
}

@Composable
internal fun PreferenceReadFailureRow(label: String, failed: Boolean, onRetry: () -> Unit) {
    if (!failed) return
    DismissibleNotice("$label could not be read. Stored data was kept.", actions = {
        TextButton(onClick = onRetry) { Text("Retry") }
    })
}
