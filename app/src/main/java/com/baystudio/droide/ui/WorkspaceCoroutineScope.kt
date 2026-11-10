package com.baystudio.droide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import com.baystudio.droide.core.ExitUiWorkOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal val LocalExitUiWorkOwner = staticCompositionLocalOf<ExitUiWorkOwner?> { null }

/** Same Compose scope lifetime; Exit additionally observes completion after cancellation. */
@Composable
internal fun rememberWorkspaceCoroutineScope(): CoroutineScope {
    val scope = rememberCoroutineScope()
    val owner = LocalExitUiWorkOwner.current
    DisposableEffect(scope, owner) {
        scope.coroutineContext[Job]?.let { owner?.register(it) }
        onDispose { /* Compose cancels its scope. Owner retains it until actual completion. */ }
    }
    return scope
}
