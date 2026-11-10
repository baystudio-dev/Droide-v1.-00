package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AgentSaveDiskIdentityTest {
    @Test fun cleanBufferCannotBeReportedSavedWhenDiskWasChangedOrDeleted() = runBlocking {
        val root = Files.createTempDirectory("agent-save-disk-identity-").toFile()
        try {
            val state = EditorWorkspaceState().apply { completeRecovery() }
            val files = FileRepository(root, beforeMutation = { error("No-op Save must not rewrite files") })
            val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files = files)
            val requests = listOf("changed.kt", "deleted.kt").map { path ->
                root.resolve(path).writeText("original")
                state.document(path).ensureLoaded(files)
                WorkspaceDocumentSaveRequest(path, checkNotNull(authority.snapshot(path)).version)
            }
            root.resolve("changed.kt").writeText("external change")
            assertTrue(root.resolve("deleted.kt").delete())
            val result = authority.saveDocuments(requests)
            assertFalse("Clean editor state is not proof of current disk contents: $result", result.ready)
            assertTrue(result.savedPaths.isEmpty()); assertEquals(2, result.issues.size)
            assertEquals("external change", root.resolve("changed.kt").readText())
            assertFalse(root.resolve("deleted.kt").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun failedAutomaticSaveOfACleanBufferStaysPendingUntilDiskMatches() = runBlocking {
        val root = Files.createTempDirectory("agent-save-clean-pending-").toFile()
        try {
            root.resolve("a.kt").writeText("original")
            val state = EditorWorkspaceState().apply { completeRecovery() }
            val files = FileRepository(root)
            state.document("a.kt").ensureLoaded(files)
            val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files = files)
            val before = checkNotNull(authority.snapshot("a.kt"))
            val edit = authority.replaceText("a.kt", before.version, "original", "Agent edit") as WorkspaceDocumentMutationResult.Applied
            root.resolve("a.kt").writeText("external")
            val result = authority.saveDocuments(listOf(WorkspaceDocumentSaveRequest("a.kt", edit.after.version, edit)))
            assertFalse(result.ready)
            assertNotNull("Unresolved disk conflict must block command launch", AgentEditPersistence.commandBlocker(authority))
            root.resolve("a.kt").writeText("original")
            assertNull(AgentEditPersistence.commandBlocker(authority))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

}
