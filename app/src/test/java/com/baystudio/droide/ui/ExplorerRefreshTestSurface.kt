package com.baystudio.droide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.WorkspaceImportController
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import org.robolectric.util.ReflectionHelpers

/** A fixture-only owner for isolated Compose tests; production ownership is WorkspaceRuntimeHost. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun ComposeContentTestRule.TestFileExplorer(
    files: FileRepository,
    state: ExplorerWorkspaceState,
    onOpen: (String) -> Unit,
    canMutatePath: (String) -> Boolean = { true },
    onRenamed: (String, String) -> Unit = { _, _ -> },
    imports: WorkspaceImportController? = null,
    onExport: ((String, Boolean) -> Unit)? = null,
    onExportStatus: (() -> Unit)? = null,
    exportStatusLabel: String? = null,
    onDismissExportStatus: (() -> Unit)? = null,
    preparePathMutation: suspend (List<String>) -> Unit = {},
) {
    val scheduler = remember(this) {
        val environment = ReflectionHelpers.getField<Any>(this, "environment")
        ReflectionHelpers.getField<TestDispatcher>(environment, "testCoroutineDispatcher").scheduler
    }
    val scope = rememberCoroutineScope { StandardTestDispatcher(scheduler) }
    val refresh = remember(files, state) {
        ExplorerRefreshController(state, scope, Mutex(),
            load = { path, after, options, budget, traversal ->
                files.listFilesPageBounded(path, after, 250, options, budget, traversal)
            }, watchFactory = ExplorerDirectoryWatchFactory { _, _ -> null },
            now = { mainClock.currentTime })
    }
    val lease = remember(refresh) { refresh.newUiLease() }
    SideEffect { refresh.updateVisibility(lease, true, true, explorerTreeMode(state.query)) }
    DisposableEffect(refresh) { onDispose { refresh.detach(lease); refresh.close() } }
    FileExplorer(files, state, refresh, lease, onOpen, canMutatePath, onRenamed, imports = imports, onExport = onExport,
        onExportStatus = onExportStatus, exportStatusLabel = exportStatusLabel, onDismissExportStatus = onDismissExportStatus, preparePathMutation = preparePathMutation)
}
