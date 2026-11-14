package com.baystudio.droide.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.delay

@Composable
internal fun TerminalSessionHeader(
    session: ITerminalSession,
    profile: TerminalManager.Profile,
    typingFocusMode: Boolean,
    onOpenLinux: (() -> Unit)?,
    status: String? = null,
    accessoryHandle: (@Composable () -> Unit)? = null,
) {
    val guide = remember(profile, session) {
        TerminalCommandGuide.forProfile(profile).let { base ->
            if (session is TerminalSession) base.copy(label = base.label + " · Command mode (no PTY)",
                summary = listOfNotNull(session.commandModeReason,
                    "Complete shell commands are supported. Interactive stdin, TUI programs and job control require a PTY.",
                    base.summary).joinToString(" ")) else base
        }
    }
    val directoryFlow = (session as? TerminalWorkingDirectorySession)?.workingDirectory
    val directory = directoryFlow?.collectAsState()?.value
    val activity = (session as? TerminalCommandActivitySession)?.commandActivity?.collectAsState()?.value
    val agentActivity = (session as? TerminalAgentActivitySession)?.agentActivity?.collectAsState()?.value
    val protection = (session as? TerminalCpuProtectionSession)?.cpuProtection?.collectAsState()?.value
    val manualWake by DevelopmentForegroundService.manualWakeLock.collectAsState()
    val geometry = (session as? NativeTerminalGeometrySession)?.geometry?.collectAsState()?.value
    var nowMs by remember(session) { mutableLongStateOf(System.nanoTime() / 1_000_000L) }
    LaunchedEffect(session, activity?.phase, activity?.generation, activity?.startedAtMs) {
        if (activity?.startedAtMs != null && activity.phase in setOf(TerminalCommandPhase.RUNNING, TerminalCommandPhase.AWAITING_RESPONSE)) {
            while (true) { nowMs = System.nanoTime() / 1_000_000L; delay(1_000L) }
        }
    }
    var showDetails by remember(session) { mutableStateOf(false) }
    (session as? NativePtySessionHandle)?.displayedPtySession?.collectAsState()?.value
    val controlAction = TerminalControlAction.forSession(session)
    var showHistory by remember(session) { mutableStateOf(false) }
    var showGuide by remember(session) { mutableStateOf(false) }
    var actionError by remember(session) { mutableStateOf<String?>(null) }
    fun runAction(block: () -> Unit) {
        runUiCatching(onError = { actionError = it.message ?: "Terminal action failed" }) { block(); actionError = null }
    }
    val context = LocalContext.current
    val commandFeedback = activity?.let { TerminalCommandFeedback.describe(it, nowMs) }
    val agentFeedback = agentActivity?.displayText()?.takeIf { it.isNotEmpty() }
    val commandFailed = activity?.let { value -> value.phase == TerminalCommandPhase.ERROR ||
            value.phase == TerminalCommandPhase.CWD_UNAVAILABLE ||
        value.phase == TerminalCommandPhase.READY && value.lastExitCode?.let { it != 0 } == true } == true
    val agentFailed = agentActivity?.let { it.completed && it.lastExitCode?.let { code -> code != 0 } == true } == true
    val feedback = listOfNotNull(
        commandFeedback?.let { it to commandFailed },
        status?.takeIf { it.isNotBlank() && it != commandFeedback }?.let { it to false },
        agentFeedback?.let { it to agentFailed },
        geometry?.warning?.let { it to true },
        protection?.let { value -> when (value.phase) {
            HumanTerminalCpuProtection.Phase.IDLE -> null
            HumanTerminalCpuProtection.Phase.PENDING -> "CPU protection pending" to false
            HumanTerminalCpuProtection.Phase.ACQUIRED -> "CPU protection acquired" to false
            HumanTerminalCpuProtection.Phase.UNAVAILABLE -> "CPU protection unavailable" to true
        } },
    )
    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().testTag("terminal-session-header").padding(horizontal = 8.dp, vertical = 2.dp)) {
            // Actions and session context share one row. Details remain available in Commands,
            // rather than permanently taking several lines from the terminal's screen.
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SelectionContainer {
                        Text(if (session is TerminalSession) "Command mode (no PTY)" else
                            directory?.let(TerminalPromptPresentation::displayDirectory) ?: guide.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = if (directory != null && session !is TerminalSession) FontFamily.Monospace else null,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (feedback.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            feedback.forEach { (label, failed) ->
                                Text(label, Modifier.weight(if (label == commandFeedback) 2f else 1f),
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                TextButton(onClick = { showGuide = true }, modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Commands", maxLines = 1) }
                IconButton(onClick = { runAction { session.interrupt() } }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Stop, controlAction.label)
                }
                accessoryHandle?.invoke()
            }
            actionError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error, ownerKey = session) }
        }
    }
    if (typingFocusMode) {
        // The landscape panel can be very short while another pane owns the IME.
        // Leave a touchable terminal viewport; controls return as soon as it expands.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxHeight >= 96.dp) {
                // Companion panes can leave a narrow center column on phones. Preserve
                // full action targets and let the toolbar scroll instead of overflowing.
                val toolbarWidth = maxOf(maxWidth, 320.dp)
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Box(Modifier.width(toolbarWidth)) { header() }
                }
            }
        }
    } else header()
    if (showHistory) TerminalHistoryDialog(session) { showHistory = false }
    if (showDetails && session is TerminalDiagnosticsSession) TerminalExecutionDetails(session) { showDetails = false }
    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text(guide.label) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(guide.summary)
                    if (session is TerminalDiagnosticsSession) TextButton(onClick = { showGuide = false; showDetails = true }) { Text("Execution details / Record output") }
                    Text("Ctrl+C interrupts the foreground terminal program. Stop agent command cancels the displayed agent job. Command mode restarts its shell. Closing a tab stops that session; hiding the panel keeps it running. Detached jobs may outlive a shell. Exit saves drafts and waits for owned work to stop; it cannot confirm untracked detached processes.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showGuide = false; showHistory = true }) { Text("Find / Export retained history") }
                    Text("Search or save retained terminal output. Agent logs and discarded output are not included.", style = MaterialTheme.typography.bodySmall)
                    if (session is TerminalCpuProtectionSession) {
                        Text("CPU protection is automatic for foreground commands. For background jobs (& or detached), enable the manual CPU lock before leaving Droide and release it when done. This uses battery; Android may still restrict network or stop the app.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { runAction {
                            check(DevelopmentForegroundService.setManualWakeLock(context, !manualWake)) {
                                "CPU wake-lock request could not start"
                            }
                        } }) { Text(if (manualWake) "Release manual WakeLock" else "Acquire manual WakeLock") }
                    }
                    actionError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error, ownerKey = session) }
                    directory?.let { path ->
                        SelectionContainer { Text(TerminalPromptPresentation.displayDirectory(path), fontFamily = FontFamily.Monospace) }
                        TextButton(onClick = {
                            runAction { (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                ?.setPrimaryClip(ClipData.newPlainText("Terminal directory", path))
                                ?: error("Clipboard is unavailable") }
                        }) { Text("Copy directory") }
                    }
                    feedback.forEach { (label, failed) ->
                        Text(label, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    geometry?.let { measured ->
                        SelectionContainer {
                            Text(measured.description(), style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                        }
                        TextButton(onClick = {
                            runAction {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                    ?.setPrimaryClip(ClipData.newPlainText("Terminal geometry", measured.description()))
                                    ?: error("Clipboard is unavailable")
                            }
                        }) { Text("Copy geometry") }
                    }
                    IconButton(onClick = { runAction { session.clear() }; showGuide = false }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.ClearAll, "Clear terminal output")
                    }
                    guide.sections.forEach { section ->
                        Text(section.title, style = MaterialTheme.typography.titleSmall)
                        Text(section.description, style = MaterialTheme.typography.bodySmall)
                        section.examples.forEach { example ->
                            Row(Modifier.fillMaxWidth()) {
                                SelectionContainer(Modifier.weight(1f)) { Text(example, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                                TextButton(onClick = {
                                    runAction { (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                        ?.setPrimaryClip(ClipData.newPlainText("Terminal command", example))
                                        ?: error("Clipboard is unavailable") }
                                }) { Text("Copy") }
                            }
                        }
                    }
                    Text("Copy an example, adjust it, then run it in the terminal. Commands do not run automatically.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showGuide = false }) { Text("Close") } },
            dismissButton = if (profile == TerminalManager.Profile.LOCAL && onOpenLinux != null) {
                { TextButton(onClick = { showGuide = false; onOpenLinux() }) { Text("Open Linux") } }
            } else null,
        )
    }
}
