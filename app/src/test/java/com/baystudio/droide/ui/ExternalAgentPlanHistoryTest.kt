package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExternalAgentPlanHistoryTest {
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun updatedPlansAndNormalizedResultsSurviveStopAndDatabaseReopen() = runBlocking {
        val root = Files.createTempDirectory("external-plan-history-").toFile()
        val store = SqlitePluginConversationHistory(root)
        try {
            val window = store.beginTurn(null, "codex", "Repair project", WorkspaceAgentMode.EDIT, "native-session")
            val id = window.summary.id
            val transcript = ExternalAgentTranscript()
            fun plan(first: String) = WorkspaceAgentEvent.Plan(listOf(
                ExternalAgentPlanEntry("Inspect source", first), ExternalAgentPlanEntry("Verify repair", "pending")))
            store.appendTranscript(id, listOf(requireNotNull(transcript.accept(plan("in_progress")))))
            store.appendTranscript(id, listOf(requireNotNull(transcript.accept(plan("completed")))))
            val patch = ExternalAgentToolUpdate("write", kind = "edit", status = "completed",
                changes = listOf(AgentFileChanges.preview("/guest/project/result.kt", null, "new")))
                .withWorkspacePaths(WorkspacePathMapper(root, "/guest/project"))
            store.appendTranscript(id, listOf(requireNotNull(transcript.accept(WorkspaceAgentEvent.Tool("Write", "", patch)))))
            store.finish(id, null, emptyList(), PluginHistoryStatus.STOPPED)
            store.close()
            val reopened = SqlitePluginConversationHistory(root)
            try {
                val loaded = requireNotNull(reopened.load(id))
                val state = ExternalAgentConversationState(messages = loaded.messages)
                assertEquals(listOf("completed", "pending"), externalAgentTasks(state).map { it.status })
                assertEquals(1, loaded.messages.count { it.streamPart?.tasks != null })
                assertEquals("result.kt", (AgentSessionResults.page(loaded.messages).entries.single() as AgentSessionResult.File).path)
                val updates = listOf(requireNotNull(transcript.accept(WorkspaceAgentEvent.Plan(emptyList()))))
                assertTrue(externalAgentTasks(state.copy(streamParts = updates.map { it.part })).isEmpty())
            } finally { reopened.close() }
        } finally { store.close(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun planMetadataIsBoundedAndOldHistoryDoesNotInventTasksFromText() {
        val transcript = ExternalAgentTranscript()
        val update = requireNotNull(transcript.accept(WorkspaceAgentEvent.Plan(
            List(90) { ExternalAgentPlanEntry("x".repeat(2_000), "unknown") })))
        assertEquals(64, update.part.tasks!!.size)
        assertTrue(update.part.tasks!!.all { it.content.length == 1_000 && it.status == "pending" })
        assertTrue(update.retainedChars >= 64_000)
        assertTrue(update.patch!!.retainedChars() >= 64_000)
        val old = Json.decodeFromString<AgentStreamPart>("""{"id":"old-plan","step":0,"kind":"TOOL","state":"COMPLETED","toolName":"plan","output":"○ Untrusted text"}""")
        assertNull(old.tasks)
        assertTrue(externalAgentTasks(ExternalAgentConversationState(messages = listOf(AgentMessage("assistant", "", old)))).isEmpty())
    }
}
