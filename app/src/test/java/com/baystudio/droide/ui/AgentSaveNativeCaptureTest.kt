package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AgentSaveNativeCaptureTest {
    @Test fun targetNativeInputIsCapturedEvenWithoutTheActiveEditorBinding() = runBlocking {
        val root = Files.createTempDirectory("agent-save-native-").toFile()
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try { withContext(owner) {
            val state = EditorWorkspaceState().apply { completeRecovery() }
            val document = state.document("a.kt")
            var nativeText = "agent"
            val files = FileRepository(root, beforeMutation = { nativeText = "new user typing" })
            root.resolve("a.kt").writeText("original")
            document.ensureLoaded(files)
            val authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
            val before = checkNotNull(authority.snapshot("a.kt"))
            val edit = authority.replaceText("a.kt", before.version, "agent", "Agent edit") as WorkspaceDocumentMutationResult.Applied
            document.nativeMutationBinding = EditorNativeMutationBinding(Any(), { true },
                { document.capture(nativeText) }, { _, _, _ -> error("Save must not mutate the native buffer") })
            val report = authority.saveDocuments(listOf(WorkspaceDocumentSaveRequest("a.kt", edit.after.version, edit)))
            assertFalse("Pending native typing must stop Save: $report", report.ready)
            assertEquals("original", root.resolve("a.kt").readText())
            assertEquals("new user typing", document.content); assertTrue(document.dirty)
        } } finally { owner.close(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
