package com.baystudio.droide.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentSessionResultsTest {
    private fun part(path: String, state: AgentStreamPartState = AgentStreamPartState.COMPLETED, step: Int = 1) =
        AgentStreamPart("tool:$step", step, AgentStreamPartKind.TOOL, state, toolName = "write_file",
            fileChanges = listOf(AgentFileChange(path, "preview")))
    private fun media(id: String) = ChatMediaRef(id, ".droide/chat-media/$id.png", "$id.png", "image/png", 8, 0, 100)

    @Test fun onlyRecordedSuccessfulOutputsAndAssistantMediaBecomeResults() {
        val messages = listOf(
            AgentMessage("user", "out.apk", media = listOf(media("input"))),
            AgentMessage("assistant", "I created imaginary.pdf"),
            AgentMessage("assistant", "", part("done.kt")),
            AgentMessage("assistant", "", part("denied.kt", AgentStreamPartState.ERROR)),
            AgentMessage("assistant", "", part("pending.kt", AgentStreamPartState.PENDING)),
            AgentMessage("assistant", "", media = listOf(media("output"))),
        )
        val results = AgentSessionResults.page(messages).entries
        assertEquals(listOf("media:output", "file:done.kt"), results.map { it.key })
    }

    @Test fun latestRecordedActivityOwnsDuplicatePathAndLiveMediaIsNotDuplicated() {
        val messages = listOf(AgentMessage("assistant", "", part("same.kt")), AgentMessage("assistant", "", media = listOf(media("m"))))
        val results = AgentSessionResults.page(messages, listOf(part("same.kt", step = 9)), listOf(media("m"))).entries
        assertEquals(2, results.size)
        assertEquals(9, (results.last() as AgentSessionResult.File).step)
    }

    @Test fun pagesAreBoundedNewestFirstAndDoNotRepeatFiles() {
        val messages = (0..94).map { AgentMessage("assistant", "", part("$it.kt", step = it)) }
        val first = AgentSessionResults.page(messages)
        val second = AgentSessionResults.page(messages, offset = 40)
        val last = AgentSessionResults.page(messages, offset = 80)
        assertEquals(40, first.entries.size); assertEquals(40, second.entries.size); assertEquals(15, last.entries.size)
        assertTrue(first.hasNext); assertTrue(second.hasNext); assertFalse(last.hasNext)
        assertEquals(95, (first.entries + second.entries + last.entries).map { it.key }.toSet().size)
        assertEquals("file:94.kt", first.entries.first().key)
    }

    @Test fun unsafePathsAndRawToolOutputCannotBecomeActionableFiles() {
        val invalid = listOf("/tmp/private", "../secret", "a/../../secret", "a\\b", "http://host/file", "./file", "a//b", "a\nfile")
        assertTrue(AgentSessionResults.page(invalid.map { AgentMessage("assistant", "", part(it)) }).entries.isEmpty())
        assertTrue(AgentSessionResults.safePath("build/outputs/app.apk"))
        assertTrue(AgentSessionResults.safePath("folder with spaces/hasil.pdf"))
    }

    @Test fun producedFilesSurviveSerializationAndOldHistoryDefaultsToEmpty() {
        val original = part("code.kt").copy(producedFiles = listOf("report.pdf", "build/output.apk"))
        val restored = Json.decodeFromString<AgentStreamPart>(Json.encodeToString(original))
        assertEquals(3, AgentSessionResults.page(listOf(AgentMessage("assistant", "", restored))).entries.size)
        val old = """{"id":"x","step":1,"kind":"TOOL","state":"COMPLETED"}"""
        assertTrue(Json.decodeFromString<AgentStreamPart>(old).producedFiles.isEmpty())
    }

    @Test fun failedPublicationNeverAppearsAsACompletedResult() {
        val incomplete = part("x", AgentStreamPartState.CANCELLED).copy(producedFiles = listOf("report.pdf"))
        assertTrue(AgentSessionResults.page(emptyList(), listOf(incomplete)).entries.isEmpty())
    }
}
