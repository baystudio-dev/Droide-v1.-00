package com.baystudio.droide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baystudio.droide.R
import com.baystudio.droide.core.EnvironmentSetupController
import com.baystudio.droide.core.EnvironmentSetupPhase
import com.baystudio.droide.core.EnvironmentSetupState
import com.baystudio.droide.core.WorkspaceStartupPhase

/** Once entered, the workspace stays mounted while an explicit setup request covers it. */
@Composable
internal fun EnvironmentSetupHost(controller: EnvironmentSetupController, allowed: Boolean, content: @Composable () -> Unit) {
    val state by controller.state.collectAsState()
    val checking = state.phase == EnvironmentSetupPhase.CHECKING
    val presented = allowed && state.presented && !checking
    val animationMillis = if (rememberDroideMotionEnabled()) 180 else 0
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(presented) { if (presented) { focus.clearFocus(force = true); keyboard?.hide() } }
    Box(Modifier.fillMaxSize()) {
        if (state.enteredWorkspace || !allowed) RetainedWorkspaceContent(!presented, content = content)
        if (allowed && checking && !state.enteredWorkspace) WorkspaceLoadingScreen(WorkspaceStartupPhase.PUBLISH_RUNTIME)
        AnimatedVisibility(presented, enter = fadeIn(tween(animationMillis)), exit = fadeOut(tween(animationMillis))) {
            EnvironmentSetupScreen(state, controller::installFromUser, controller::later, controller::cancel)
        }
    }
    BackHandler(allowed && state.presented) {
        if (state.busy) controller.cancel() else controller.later()
    }
}

@Composable
internal fun EnvironmentSetupScreen(
    state: EnvironmentSetupState, onInstall: () -> Unit, onLater: () -> Unit, onCancel: () -> Unit,
) {
    DroideSessionLayout(Modifier.background(MaterialTheme.colorScheme.surface).pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }, footer = { EnvironmentSetupActions(state, onInstall, onLater, onCancel) }) {
        DroideSessionLogo(80.dp)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.environment_setup_title),
            Modifier.fillMaxWidth().semantics { heading() }, fontSize = 26.sp, lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.environment_setup_once), style = MaterialTheme.typography.bodyLarge,
            color = DroideSessionColors.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ubuntu 24.04", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.environment_setup_capabilities), style = MaterialTheme.typography.bodyMedium,
                    color = DroideSessionColors.Muted)
                if (state.busy || state.phase == EnvironmentSetupPhase.CHECKING) {
                    val fraction = state.progress.fraction.takeIf { state.phase == EnvironmentSetupPhase.DOWNLOADING }
                    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = DroideSessionColors.Accent)
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = DroideSessionColors.Accent)
                    Text(stringResource(setupPhaseText(state.phase)),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    if (fraction != null) Text(stringResource(R.string.environment_setup_download_progress,
                        state.progress.downloadedBytes / 1024 / 1024, (state.progress.totalBytes ?: 0) / 1024 / 1024),
                        style = MaterialTheme.typography.bodyMedium, color = DroideSessionColors.Muted)
                } else Text(stringResource(R.string.environment_setup_size), style = MaterialTheme.typography.bodyMedium,
                    color = DroideSessionColors.Accent)
            }
        }
        state.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun EnvironmentSetupActions(state: EnvironmentSetupState, onInstall: () -> Unit, onLater: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            if (state.busy) {
                OutlinedButton(onCancel, enabled = state.phase != EnvironmentSetupPhase.CANCELLING,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(stringResource(R.string.environment_setup_pause), style = MaterialTheme.typography.bodyLarge)
                }
            } else if (state.phase != EnvironmentSetupPhase.CHECKING) {
                Text(stringResource(R.string.environment_setup_consent), style = MaterialTheme.typography.bodyMedium,
                    color = DroideSessionColors.Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onLater, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                        Text(stringResource(R.string.environment_setup_later), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    }
                    Button(onInstall, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                        Text(stringResource(if (state.phase == EnvironmentSetupPhase.FAILED || state.error != null)
                            R.string.environment_setup_retry else R.string.environment_setup_install), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

private fun setupPhaseText(phase: EnvironmentSetupPhase): Int = when (phase) {
    EnvironmentSetupPhase.CHECKING -> R.string.environment_setup_checking
    EnvironmentSetupPhase.DOWNLOADING -> R.string.environment_setup_downloading
    EnvironmentSetupPhase.VERIFYING -> R.string.environment_setup_verifying
    EnvironmentSetupPhase.EXTRACTING -> R.string.environment_setup_extracting
    EnvironmentSetupPhase.CONFIGURING -> R.string.environment_setup_configuring
    EnvironmentSetupPhase.CANCELLING -> R.string.environment_setup_pausing
    else -> R.string.environment_setup_preparing
}
