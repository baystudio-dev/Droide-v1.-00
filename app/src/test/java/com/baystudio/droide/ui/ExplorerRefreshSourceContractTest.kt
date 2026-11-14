package com.baystudio.droide.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring guards, not Android/device execution evidence. */
class ExplorerRefreshSourceContractTest {
    private fun source(name: String): String {
        val relative = "src/main/java/com/baystudio/droide/$name"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }

    private data class RefreshBranch(val name: String, val start: String, val end: String)
    private val mutationBranches = listOf(
        RefreshBranch("committed move", "if (observation.committed) {", "} else if (observation.uncertain) {"),
        RefreshBranch("uncertain move", "} else if (observation.uncertain) {", "failure?.let {"),
        RefreshBranch("create file", "if (showNewFile) AlertDialog(", "if (showNewFolder) AlertDialog("),
        RefreshBranch("create folder", "if (showNewFolder) AlertDialog(", "if (showRename != null) AlertDialog("),
        RefreshBranch("rename", "if (showRename != null) AlertDialog(", "showDelete?.let { target ->"),
        RefreshBranch("delete", "showDelete?.let { target ->", "private fun savedFileSyncMessage("),
    )
    private val refreshAndSearch = "refreshLocalTreeAfterMutation(); searchRevision++"

    private fun branchRange(text: String, branch: RefreshBranch): IntRange {
        fun unique(anchor: String): Int {
            val index = text.indexOf(anchor)
            assertTrue("Missing or ambiguous ${branch.name} anchor: $anchor",
                index >= 0 && index == text.lastIndexOf(anchor))
            return index
        }
        val start = unique(branch.start)
        val end = unique(branch.end)
        assertTrue("Reversed ${branch.name} anchors", start < end)
        return start until end
    }

    private fun requireMutationRefreshes(text: String) {
        mutationBranches.forEach { branch ->
            assertTrue("${branch.name} must refresh the tree and invalidate search",
                text.substring(branchRange(text, branch)).contains(refreshAndSearch))
        }
    }

    @Test fun eachMutationOutcomeRequiresItsOwnRefreshAndSearchInvalidation() {
        val text = source("ui/FileExplorer.kt")
        mutationBranches.forEach { branch ->
            val range = branchRange(text, branch)
            val body = text.substring(range)
            assertTrue(body.contains(refreshAndSearch))
            for (missing in listOf("refreshLocalTreeAfterMutation()", "searchRevision++")) {
                val changed = text.replaceRange(range, body.replace(missing, "/* omitted in negative fixture */"))
                assertTrue("Missing $missing in ${branch.name} must be detected",
                    runCatching { requireMutationRefreshes(changed) }.exceptionOrNull() is AssertionError)
            }
        }
    }

    @Test fun missingDuplicatedOrReversedAnchorsCannotPassBySubstringFallback() {
        val text = source("ui/FileExplorer.kt")
        mutationBranches.forEach { branch ->
            for (anchor in listOf(branch.start, branch.end)) {
                val missing = text.replace(anchor, "/* missing anchor */")
                val duplicated = text + "\n" + anchor
                for (changed in listOf(missing, duplicated)) assertTrue(
                    runCatching { requireMutationRefreshes(changed) }.exceptionOrNull() is AssertionError)
            }
            val reversed = text.replace(branch.start, "TEMP_ANCHOR").replace(branch.end, branch.start)
                .replace("TEMP_ANCHOR", branch.end)
            assertTrue(runCatching { requireMutationRefreshes(reversed) }.exceptionOrNull() is AssertionError)
        }
    }

    @Test fun productionControllerBelongsToHostWithOneLaneAcrossRuntimeReplacement() {
        val host = source("WorkspaceRuntimeHost.kt")
        assertTrue(host.contains("private val explorerReadLane = Mutex()"))
        assertTrue(host.contains("check(coordinator.runtime.value === runtime)"))
        assertTrue(host.contains("explorerRuntime !== runtime || explorerStateBinding !== state"))
        assertTrue(host.contains("state, runtimeScope, explorerReadLane,"))
        assertTrue(source("WorkspaceViewModel.kt").contains("host.explorerRefreshFor(runtime)"))
        assertTrue(source("MainActivity.kt").contains("explorerRefresh = workspaceModel.explorerRefreshFor(rt)"))
        assertFalse(source("ui/IdeScreen.kt").contains("ExplorerRefreshController("))
        assertFalse(source("ui/FileExplorer.kt").contains("ExplorerRefreshController("))
        val explorer = source("ui/FileExplorer.kt")
        assertFalse(explorer.contains("reloadTree("))
        requireMutationRefreshes(explorer)
        val mutationRefresh = explorer.substringAfter("    fun refreshLocalTreeAfterMutation() {").substringBefore("    fun loadMore")
        assertTrue(mutationRefresh.contains("state.requestRefresh()"))
        assertTrue(mutationRefresh.contains("refreshController.requestRefresh(refreshLease, state.refreshToken)"))
        assertFalse(mutationRefresh.contains("onReconcileSaf"))
    }

    @Test fun renderAndCommittedVisibilitySharePredicateAndSnapshotObservesExpansion() {
        val screen = source("ui/IdeScreen.kt")
        val explorer = source("ui/FileExplorer.kt")
        assertTrue(screen.contains("explorerRefresh.updateVisibility(explorerLease, uiResumed && recoveryReady,"))
        assertTrue(screen.contains("visibleExplorer, explorerTreeMode(explorerState.query)"))
        assertTrue(explorer.contains("val treeMode = explorerTreeMode(state.query)"))
        assertTrue(explorer.contains("if (!treeMode)"))
        assertTrue(explorer.contains("snapshotFlow { explorerViewportPages(state,"))
        assertTrue(explorer.contains("onDispose { refreshController.updateViewport(refreshLease, emptySet()) }"))
    }

    @Test fun platformAdapterIsNonrecursiveAndCannotTriggerSafEditorSearchOrSelfReadEvents() {
        val adapter = source("ui/AndroidExplorerDirectoryWatch.kt")
        listOf("CREATE", "DELETE", "MOVED_FROM", "MOVED_TO", "DELETE_SELF", "MOVE_SELF", "CLOSE_WRITE", "ATTRIB").forEach {
            assertTrue(adapter.contains("FileObserver.$it"))
        }
        listOf("FileObserver.ACCESS", "FileObserver.OPEN", "FileObserver.CLOSE_NOWRITE", "walkTopDown", "listFiles").forEach {
            assertFalse(adapter.contains(it))
        }
        assertTrue(adapter.contains("event and EVENTS"))
        assertTrue(adapter.contains("requireUserTarget(root, path)"))
        val owner = source("ui/ExplorerRefreshController.kt")
        listOf("onReconcileSaf", "persistedMutation", "globPage", "editorBridge", "terminalManager", "state.requestRefresh()").forEach {
            assertFalse(owner.contains(it))
        }
        val toolbar = source("ui/FileExplorer.kt").substringAfter("    fun refresh()").substringBefore("    fun toggleDirectory")
        assertTrue(toolbar.indexOf("onReconcileSaf()") < toolbar.indexOf("state.requestRefresh()"))
    }
}
