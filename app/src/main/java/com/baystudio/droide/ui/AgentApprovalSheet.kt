package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.ApprovalRequest
import com.baystudio.droide.core.ApprovalResolution
import com.baystudio.droide.core.ToolchainInstallConsent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentApprovalSheet(request: ApprovalRequest, onDismiss: () -> Unit,
                                clearFocusAndHideKeyboard: Boolean = true,
                                onResolve: (ApprovalResolution) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(request.id, clearFocusAndHideKeyboard) {
        if (clearFocusAndHideKeyboard) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DroideColors.Surface,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding()) {
            // Expanded is the measured content height. Only large previews use
            // the viewport cap; a short permission must not fill the screen.
            AgentApprovalContent(request, maxHeight * .92f, onResolve)
        }
    }
}

/** Pin actions when there is room; short/accessibility windows scroll the complete sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentApprovalContent(request: ApprovalRequest, maxHeight: Dp,
                                  onResolve: (ApprovalResolution) -> Unit) {
    AdaptiveSheetColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("agent-approval-content")
            .padding(horizontal = 12.dp, vertical = 6.dp),
        minimumBodyHeight = textControlHeight(MaterialTheme.typography.bodySmall, 0.dp),
        header = {
            Text(if (request.kind == ToolchainInstallConsent.KIND) "Android SDK license" else "Permission required",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        },
        body = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(request.summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                request.browserContext?.let { browser ->
                    Text("Action: ${browser.operation}", style = MaterialTheme.typography.bodySmall)
                    Text("Page: ${browser.pagePreview}", style = MaterialTheme.typography.bodySmall)
                    browser.targetPreview?.takeIf { it.isNotBlank() }?.let { Text("Target: $it", style = MaterialTheme.typography.bodySmall) }
                    Text("Permission reason: ${browser.reason}", style = MaterialTheme.typography.bodySmall)
                    browser.outputScope?.let { Text("Output scope: $it", style = MaterialTheme.typography.bodySmall) }
                    browser.outputDisclosure?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (request.action.isNotBlank() || request.resource.isNotBlank()) {
                    val label = "${request.action.ifBlank { request.kind }} · ${request.resource}".take(1_000)
                    val singleFile = request.kind in setOf("write_file", "edit_file", "agent_plugin_read", "agent_plugin_write") ||
                        (request.kind == "agent_plugin_tool" && request.action == "write_file")
                    if (singleFile && request.resource.isNotBlank()) {
                        FileIdentityLabel(request.resource, label, iconSize = 14.dp, maxLines = Int.MAX_VALUE,
                            monospaced = true, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    } else {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                request.proposedContent?.let { AgentProposedContentReview(it) }
                request.inputReview?.let { AgentInputReview(it) }
                Surface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, DroideColors.Border),
                    color = DroideColors.Background, shape = MaterialTheme.shapes.small) {
                    val proposalSummary: (@Composable () -> Unit)? = request.proposedChangeTotals?.let { totals ->
                        {
                            AgentChangeCounts(totals, proposed = true, proposedLabel = "Proposal total",
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
                                    .testTag("agent-proposed-change-counts").semantics(mergeDescendants = true) {
                                    contentDescription = if (totals.known)
                                        "Proposed changes: ${totals.addedLines} lines added, ${totals.removedLines} lines removed. Not applied."
                                    else "Proposed changes: line counts unavailable. Not applied."
                                })
                        }
                    }
                    if (request.kind == ToolchainInstallConsent.KIND) {
                        // Legal terms must be complete and readable, never a truncated code preview.
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(request.detail, Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (request.kind in setOf("write_file", "edit_file", "apply_patch", "lsp_edit") || request.summary.startsWith("Terapkan patch")) {
                        DiffText(request.detail.take(16_000), path = request.resource,
                            wrapFileNames = true, leadingSummary = proposalSummary)
                    } else {
                        Column {
                            proposalSummary?.invoke()
                            val language = when (request.kind) {
                                "shell", "run_command", "bash", "exec_command" -> "shell"
                                else -> "plaintext"
                            }
                            Text(rememberHighlightedCode(request.detail.take(8_000), language), Modifier.fillMaxWidth().padding(8.dp),
                                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        footer = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(color = DroideColors.Border)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton({ onResolve(ApprovalResolution.DENY) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Reject") }
                    Button({ onResolve(ApprovalResolution.ALLOW_ONCE) }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(when (request.kind) { ToolchainInstallConsent.KIND -> "Accept & install"; "lsp_project_analysis" -> "Enable analysis"; else -> "Allow once" })
                    }
                }
                if (request.canAllowForSession) {
                    TextButton({ onResolve(ApprovalResolution.ALLOW_SESSION) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (request.browserContext?.sessionAccess == true) "Allow this site for this session" else "Always allow for this session")
                    }
                }
            }
        },
    )
}
