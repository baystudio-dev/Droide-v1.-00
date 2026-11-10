package com.baystudio.droide.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Observe user intent without consuming input, requesting focus, or changing the keyboard. */
@Composable
internal fun Modifier.workspaceApprovalAttention(
    surface: WorkspaceApprovalSurface,
    onActive: (WorkspaceApprovalSurface) -> Unit,
): Modifier {
    val currentOnActive by rememberUpdatedState(onActive)
    return onFocusChanged { if (it.hasFocus) currentOnActive(surface) }
        .onPreviewKeyEvent {
            // Pointer-only attention can change while this descendant keeps keyboard focus.
            if (it.type == KeyEventType.KeyDown) currentOnActive(surface)
            false
        }
        .focusGroup()
        .pointerInput(surface) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed && !it.previousPressed }) currentOnActive(surface)
                }
            }
        }
}

internal fun workspaceApprovalSurface(
    lastActive: WorkspaceApprovalSurface,
    chatVisible: Boolean,
    chatFullscreen: Boolean,
    browserVisible: Boolean,
    browserExclusive: Boolean,
    otherUiActive: Boolean,
): WorkspaceApprovalSurface = when {
    otherUiActive -> WorkspaceApprovalSurface.OTHER
    chatVisible && chatFullscreen -> WorkspaceApprovalSurface.AGENT
    chatVisible && lastActive == WorkspaceApprovalSurface.AGENT -> WorkspaceApprovalSurface.AGENT
    browserVisible && (lastActive == WorkspaceApprovalSurface.BROWSER || browserExclusive) -> WorkspaceApprovalSurface.BROWSER
    else -> WorkspaceApprovalSurface.OTHER
}
