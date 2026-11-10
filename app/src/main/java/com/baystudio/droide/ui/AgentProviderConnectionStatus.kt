package com.baystudio.droide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.baystudio.droide.core.ProviderConnectionBackend
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** UI hint only. Send-time provider/credential validation remains authoritative. */
@Composable
internal fun rememberAgentProviderConnected(
    connectionBackend: ProviderConnectionBackend,
    providerId: String,
): Boolean = rememberAgentProviderConnected(connectionBackend, providerId, connectionBackend.revision) { id ->
    connectionBackend.snapshot(id).status == ProviderConnectionBackend.Status.CONNECTED
}

/** The reader is injectable so slow storage and cancelled reads can be exercised without credentials. */
@Composable
internal fun rememberAgentProviderConnected(
    backendIdentity: Any,
    providerId: String,
    revisions: StateFlow<Long>,
    readConnected: (String) -> Boolean,
): Boolean = key(backendIdentity, revisions) {
    val revision by revisions.collectAsState()
    // Reset during composition, rather than in the next effect: the old provider must never appear
    // connected for even one frame after a selection/backend/revision change.
    val connected = remember(providerId, revision) { mutableStateOf(false) }
    LaunchedEffect(providerId, revision) {
        connected.value = runSuspendCatching {
            // snapshot() may reach SharedPreferences and AndroidKeyStore. Retain only this Boolean.
            withContext(Dispatchers.IO) { readConnected(providerId) }
        }.getOrDefault(false)
    }
    connected.value
}
