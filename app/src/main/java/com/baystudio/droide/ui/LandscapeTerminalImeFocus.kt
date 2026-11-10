package com.baystudio.droide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

internal data class LandscapeTerminalImeFocusState(
    val active: Boolean,
    val exit: () -> Unit,
    val dismissKeyboard: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberLandscapeTerminalImeFocus(
    workspaceKey: String,
    physicalLandscape: Boolean,
    imeVisible: Boolean,
    terminalInputFocused: Boolean,
    otherInputFocused: Boolean = false,
): LandscapeTerminalImeFocusState {
    val keyboardController = LocalSoftwareKeyboardController.current
    var active by remember(workspaceKey) { mutableStateOf(false) }
    var suppressedUntilImeHide by remember(workspaceKey) { mutableStateOf(false) }
    var dismissalPending by remember(workspaceKey) { mutableStateOf(false) }
    var dismissalSawHidden by remember(workspaceKey) { mutableStateOf(false) }
    val rawImeVisible = WindowInsets.isImeVisible

    LaunchedEffect(physicalLandscape, imeVisible, terminalInputFocused, otherInputFocused, rawImeVisible) {
        if (!physicalLandscape || !imeVisible || otherInputFocused) {
            active = false
            suppressedUntilImeHide = false
            dismissalPending = false
            dismissalSawHidden = false
        } else {
            if (dismissalPending) {
                if (!rawImeVisible) dismissalSawHidden = true
                else if (dismissalSawHidden) {
                    // A fresh show during the existing visibility grace cancels pending dismissal.
                    dismissalPending = false
                    dismissalSawHidden = false
                    suppressedUntilImeHide = false
                }
            }
            if (terminalInputFocused && !suppressedUntilImeHide) active = true
        }
    }

    val exit: () -> Unit = {
        active = false
        suppressedUntilImeHide = true
        dismissalPending = false
        dismissalSawHidden = false
        keyboardController?.hide()
    }
    val dismissKeyboard: () -> Unit = {
        if (dismissalPending || keyboardController == null) {

            exit()
        } else {
            dismissalPending = true
            dismissalSawHidden = !rawImeVisible
            suppressedUntilImeHide = true

            keyboardController.hide()
        }
    }
    val presented = active && physicalLandscape && imeVisible && !otherInputFocused
    BackHandler(enabled = presented) { dismissKeyboard() }
    return LandscapeTerminalImeFocusState(active = presented, exit = exit, dismissKeyboard = dismissKeyboard)
}
