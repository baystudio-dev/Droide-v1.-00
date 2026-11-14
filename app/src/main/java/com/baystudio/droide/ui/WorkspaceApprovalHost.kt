package com.baystudio.droide.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.ApprovalManager
import com.baystudio.droide.core.ApprovalRequest
import com.baystudio.droide.core.ApprovalResolution

/** User attention is distinct from a pane merely remaining visible in a split workspace. */
internal enum class WorkspaceApprovalSurface { AGENT, BROWSER, OTHER }

/** One workspace-owned responder survives chat/browser placement changes without owning the WebView. */
@Composable
internal fun WorkspaceApprovalHost(
    approvals: ApprovalManager,
    browserVisible: Boolean,
    onOpenBrowser: () -> Unit,
    browserDialogPending: Boolean = false,
    modifier: Modifier = Modifier,
    activeSurface: WorkspaceApprovalSurface = WorkspaceApprovalSurface.OTHER,
    reviewRequest: Pair<Long, Long>? = null,
    compact: Boolean = false,
) {
    val pending by approvals.pending.collectAsState()
    val request = pending ?: return
    key(approvals, request.id) {
        var reviewRequested by remember { mutableStateOf(false) }
        var automaticDetailsDismissed by remember { mutableStateOf(false) }
        val activeBrowser = browserVisible && activeSurface == WorkspaceApprovalSurface.BROWSER
        // The event is emitted by a user action (including Review from a website dialog).
        // Its request id prevents a late/stale action from opening a different approval.
        LaunchedEffect(request.id, reviewRequest) {
            if (reviewRequest?.first == request.id) reviewRequested = true
        }
        // A visible website prompt may own attention before a surface callback arrives.
        // Its own Review action remains available without automatically layering modals.
        val automaticallyShowDetails = !automaticDetailsDismissed &&
            activeSurface == WorkspaceApprovalSurface.AGENT && !(browserVisible && browserDialogPending)
        val resolve: (ApprovalResolution) -> Unit = { approvals.resolve(request.id, it) }
        val requestReview: () -> Unit = {
            if (approvals.pending.value?.id == request.id) reviewRequested = true
        }
        if (reviewRequested || automaticallyShowDetails) {
            // Only an explicit review stays open after attention moves elsewhere. An
            // automatically presented sheet collapses as soon as its context is left.
            AgentApprovalSheet(request, onDismiss = {
                reviewRequested = false
                automaticDetailsDismissed = true
            }, clearFocusAndHideKeyboard = false, onResolve = resolve)
        } else {
            BoxWithConstraints(modifier.widthIn(max = 600.dp).fillMaxWidth().imePadding().navigationBarsPadding()) {
                // Keep meaningful content space below keyboard/landscape constraints.
                // A cramped browser uses the same explicit-review status as other work.
                val dockMinimum = 300.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                val dockHeight = minOf(maxHeight * .4f, 220.dp)
                val dockHasRoom = maxHeight >= dockMinimum &&
                    maxWidth >= 280.dp * LocalDensity.current.fontScale.coerceAtLeast(1f) &&
                    dockHeight >= 140.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                if (!compact && !automaticDetailsDismissed && activeBrowser && request.browserContext != null && dockHasRoom) {
                    BrowserApprovalDock(request, browserVisible, onOpenBrowser, requestReview, resolve,
                        Modifier.heightIn(max = dockHeight))
                } else {
                    WorkspaceApprovalIndicator(onReview = requestReview, projectAnalysis = request.kind == "lsp_project_analysis")
                }
            }
        }
    }
}

/** Persistent, nonmodal status. Arrival does not request/clear focus or hide the keyboard. */
@Composable
private fun WorkspaceApprovalIndicator(onReview: () -> Unit, projectAnalysis: Boolean = false) {
    val currentOnReview by rememberUpdatedState(onReview)
    val thresholdPx = with(LocalDensity.current) { 24.dp.toPx() }
    Surface(Modifier.fillMaxWidth().testTag("workspace-approval-indicator").pointerInput(thresholdPx) {
        // Only gestures beginning on this footer participate; the workspace stays usable.
        // Touch slop is handled by the detector. Reopen once on release, never on cancel.
        var verticalTravel = 0f
        detectVerticalDragGestures(
            onDragStart = { verticalTravel = 0f },
            onDragCancel = { verticalTravel = 0f },
            onDragEnd = {
                val shouldReview = verticalTravel <= -thresholdPx
                verticalTravel = 0f
                if (shouldReview) currentOnReview()
            },
            onVerticalDrag = { _, amount -> verticalTravel += amount },
        )
    },
        color = DroideColors.Surface, border = BorderStroke(1.dp, DroideColors.Border),
        tonalElevation = 6.dp, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Announce only the stable status, never streaming logs or a changing preview.
            Text(if (projectAnalysis) "Project analysis waiting for permission" else "Agent waiting for permission", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Review approval")
            }
        }
    }
}

/** Compact approval content; the workspace reserves footer space without replacing the live browser. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserApprovalDock(
    request: ApprovalRequest,
    browserVisible: Boolean,
    onOpenBrowser: () -> Unit,
    onDetails: () -> Unit,
    onResolve: (ApprovalResolution) -> Unit,
    modifier: Modifier = Modifier,
) {
    val browser = requireNotNull(request.browserContext)
    Surface(modifier.fillMaxWidth().testTag("browser-approval-dock"),
        color = DroideColors.Surface, border = BorderStroke(1.dp, DroideColors.Border),
        tonalElevation = 6.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Permission required", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                if (!browserVisible) {
                    TextButton(onClick = onOpenBrowser, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("View browser", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = onDetails, modifier = Modifier.heightIn(min = 48.dp)) { Text("Details") }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(request.summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Action: ${browser.operation}", style = MaterialTheme.typography.bodySmall)
                Text("Site: ${browser.site}", style = MaterialTheme.typography.bodySmall)
                browser.target?.takeIf { it.isNotBlank() }?.let {
                    Text("Target: $it", style = MaterialTheme.typography.bodySmall)
                }
                Text("Permission reason: ${browser.reason}", style = MaterialTheme.typography.bodySmall)
                browser.outputScope?.let { Text("Output scope: $it", style = MaterialTheme.typography.bodySmall) }
                browser.outputDisclosure?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton({ onResolve(ApprovalResolution.DENY) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Reject") }
                val siteSession = request.canAllowForSession && browser.sessionAccess
                Button({ onResolve(if (siteSession) ApprovalResolution.ALLOW_SESSION else ApprovalResolution.ALLOW_ONCE) },
                    Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (siteSession) "Allow for session" else "Allow once") }
            }
        }
    }
}
