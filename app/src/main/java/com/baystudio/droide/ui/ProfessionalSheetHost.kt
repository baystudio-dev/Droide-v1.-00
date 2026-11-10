package com.baystudio.droide.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.baystudio.droide.core.*
import java.io.File

enum class ProfessionalSheet { TASKS, TEST_EXPLORER, APK_ANALYZER, CHANGE_REVIEW, ISOLATED_WORKSPACES, CAPABILITY_HEALTH }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfessionalSheetHost(
    sheet: ProfessionalSheet?,
    taskManager: TaskManager,
    git: GitManager,
    worktrees: WorktreeManager,
    android: AndroidDevelopmentManager,
    device: DeviceBridgeManager,
    debugger: DebugManager,
    extensions: DevelopmentExtensionsManager,
    activeFile: String,
    apk: File?,
    onOpenSourceControl: () -> Unit,
    beforeTaskRun: suspend () -> Unit,
    taskController: WorkspaceTaskController,
    onDismiss: () -> Unit,
) {
    if (sheet == null) return
    DroideModalBottomSheet(onDismissRequest = onDismiss) { dismiss ->
        when (sheet) {
            ProfessionalSheet.TASKS -> TasksSheet(taskManager, dismiss, beforeTaskRun, taskController)
            ProfessionalSheet.TEST_EXPLORER -> TestExplorerSheet(taskManager, dismiss, beforeTaskRun, taskController)
            ProfessionalSheet.APK_ANALYZER -> if (apk?.isFile == true) ApkAnalyzerSheet(apk, dismiss) else DroidePanelHeader(title = "APK Analyzer", onClose = dismiss, subtitle = "Build an APK first to analyze it.")
            ProfessionalSheet.CHANGE_REVIEW -> ChangeReviewSheet(git, onOpenSourceControl, dismiss)
            ProfessionalSheet.ISOLATED_WORKSPACES -> IsolatedWorkspacesSheet(worktrees, dismiss)
            ProfessionalSheet.CAPABILITY_HEALTH -> ProfessionalDiagnosticsSheet(android, device, git, taskManager, debugger, extensions, activeFile, apk, dismiss)
        }
    }
}
