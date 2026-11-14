package com.baystudio.droide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

internal enum class LandscapeInputTarget { NONE, EDITOR, TERMINAL }

internal data class LandscapeInputExpansion(
    val target: LandscapeInputTarget,
    val restore: () -> Unit,
    val dismissKeyboard: () -> Unit,
) {
    val editorExpanded get() = target == LandscapeInputTarget.EDITOR
    val terminalExpanded get() = target == LandscapeInputTarget.TERMINAL
}

/** Expansion belongs to the user's workspace, not to the lifetime of a keyboard inset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberLandscapeInputExpansion(
    workspaceKey: String,
    landscape: Boolean,
    imeVisible: Boolean,
    focusedInput: LandscapeInputTarget,
): LandscapeInputExpansion {
    val keyboard = LocalSoftwareKeyboardController.current
    val rawImeVisible = WindowInsets.isImeVisible
    var target by rememberSaveable(workspaceKey) { mutableStateOf(LandscapeInputTarget.NONE) }
    var suppressed by remember(workspaceKey) { mutableStateOf(false) }
    var dismissalPending by remember(workspaceKey) { mutableStateOf(false) }
    var dismissalSawHidden by remember(workspaceKey) { mutableStateOf(false) }

    LaunchedEffect(landscape, imeVisible, focusedInput, rawImeVisible) {
        if (!landscape) {
            target = LandscapeInputTarget.NONE
            suppressed = false
            dismissalPending = false
            dismissalSawHidden = false
        } else if (!imeVisible) {
            suppressed = false
            dismissalPending = false
            dismissalSawHidden = false
        } else {
            if (dismissalPending) {
                if (!rawImeVisible) dismissalSawHidden = true
                else if (dismissalSawHidden) {
                    dismissalPending = false
                    dismissalSawHidden = false
                }
            }
            if (!suppressed && focusedInput != LandscapeInputTarget.NONE) target = focusedInput
        }
    }
    val restore: () -> Unit = {
        target = LandscapeInputTarget.NONE
        suppressed = imeVisible
        dismissalPending = false
        dismissalSawHidden = false
        keyboard?.hide()
    }
    val dismissKeyboard: () -> Unit = {
        if (!imeVisible || dismissalPending || keyboard == null) restore()
        else {
            dismissalPending = true
            dismissalSawHidden = !rawImeVisible
            keyboard.hide()
        }
    }
    val presented = if (landscape) target else LandscapeInputTarget.NONE
    BackHandler(enabled = presented != LandscapeInputTarget.NONE) { dismissKeyboard() }
    return LandscapeInputExpansion(presented, restore, dismissKeyboard)
}
