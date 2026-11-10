package com.baystudio.droide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.baystudio.droide.core.WorkspaceAgentAvailability
import com.baystudio.droide.core.runSuspendCatching

@Composable
internal fun rememberWorkspaceAgentAvailability(
    hostIdentity: Any?,
    expanded: Boolean,
    probe: suspend () -> List<WorkspaceAgentAvailability>,
): List<WorkspaceAgentAvailability> {
    val availability = remember(hostIdentity) { mutableStateOf<List<WorkspaceAgentAvailability>>(emptyList()) }
    val currentProbe by rememberUpdatedState(probe)
    LaunchedEffect(hostIdentity, expanded) {
        if (availability.value.isEmpty() || expanded) {
            availability.value = runSuspendCatching { currentProbe() }.getOrDefault(emptyList())
        }
    }
    return availability.value
}
