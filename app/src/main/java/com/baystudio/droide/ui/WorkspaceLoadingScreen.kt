package com.baystudio.droide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baystudio.droide.core.WorkspaceStartupPhase

/**
 * Presentation only: the workspace owner decides whether startup is still pending.
 * No minimum display time, simulated percentage, or animation-completion callback.
 * The caller already owns system insets, errors, retries and runtime publication.
 */
@Composable
internal fun WorkspaceLoadingScreen(phase: WorkspaceStartupPhase, modifier: Modifier = Modifier) {
    DroideSessionLayout(modifier) {
        DroideSessionLogo()
        Spacer(Modifier.height(28.dp))
        Text(
            "Welcome to Droide",
            modifier = Modifier.fillMaxWidth().semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text("Wait a moment…", color = DroideSessionColors.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))
        Column(Modifier.widthIn(max = 280.dp).fillMaxWidth()) {
            // Startup has bounded stages, not a known total amount of work. The
            // standard indeterminate indicator honors Android's animation scale.
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = DroideSessionColors.Accent,
                trackColor = Color(0xFF293241),
                strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                startupMessage(phase),
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                color = DroideSessionColors.Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

private fun startupMessage(phase: WorkspaceStartupPhase): String = when (phase) {
    WorkspaceStartupPhase.IDLE, WorkspaceStartupPhase.RUNTIME_REGISTRIES -> "Preparing your workspace"
    WorkspaceStartupPhase.PROJECT_CATALOG -> "Loading your projects"
    WorkspaceStartupPhase.ACTIVE_RUNTIME -> "Opening your workspace"
    WorkspaceStartupPhase.FALLBACK_RUNTIME -> "Opening the recovery workspace"
    WorkspaceStartupPhase.PUBLISH_RUNTIME, WorkspaceStartupPhase.READY -> "Getting things ready"
    // Error delivery follows cleanup; MainActivity replaces this screen with recovery actions.
    WorkspaceStartupPhase.FAILED -> "Finishing startup"
}
