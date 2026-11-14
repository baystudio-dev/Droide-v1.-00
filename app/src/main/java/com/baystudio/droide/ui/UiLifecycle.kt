package com.baystudio.droide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Pause screen maintenance when hidden; application-owned work never observes this gate. */
@Composable
internal fun rememberUiStarted(): Boolean {
    return rememberUiAtLeast(Lifecycle.State.STARTED)
}

/** Returning from an external picker/app can change project bytes while the UI was paused. */
@Composable
internal fun rememberUiResumed(): Boolean = rememberUiAtLeast(Lifecycle.State.RESUMED)

@Composable
private fun rememberUiAtLeast(minimumState: Lifecycle.State): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle, minimumState) { mutableStateOf(lifecycle.currentState.isAtLeast(minimumState)) }
    DisposableEffect(lifecycle, minimumState) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle.currentState.isAtLeast(minimumState)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return started
}
