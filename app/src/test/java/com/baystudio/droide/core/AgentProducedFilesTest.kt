package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AgentProducedFilesTest {
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun mediaOutputIsRecordedOnlyAfterPublicationAndNeverForRejectedOverwrite() = runBlocking {
        val root = Files.createTempDirectory("produced-document-").toFile()
        val outputs = mutableListOf<String>()
        try {
            val tools = MediaAuthoringTools(FileRepository(root), MediaTestTerminal(), ::mediaWorker,
                onProducedFile = { path -> assertTrue(root.resolve(path).isFile); outputs += path })
            val args = buildJsonObject { put("operation", "create_docx"); put("destination", "report.docx"); put("content", "Report") }
            val result = tools.execute("document", args, AgentMode.BUILD) { _, _ -> null }
            assertTrue(Json.parseToJsonElement(result).jsonObject["persisted_to_project"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("report.docx"), outputs)
            assertTrue(runCatching { tools.execute("document", args, AgentMode.BUILD) { _, _ -> null } }.isFailure)
            assertEquals(listOf("report.docx"), outputs)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun detachedResultDisplayDoesNotTurnSuccessfulPublicationIntoARetryableFailure() = runBlocking {
        val root = Files.createTempDirectory("detached-document-").toFile()
        try {
            val tools = MediaAuthoringTools(FileRepository(root), MediaTestTerminal(), ::mediaWorker,
                onProducedFile = { error("Display detached") })
            val result = tools.execute("document", buildJsonObject {
                put("operation", "create_docx"); put("destination", "report.docx"); put("content", "Report")
            }, AgentMode.BUILD) { _, _ -> null }
            assertTrue(root.resolve("report.docx").isFile)
            assertTrue(Json.parseToJsonElement(result).jsonObject["persisted_to_project"]!!.jsonPrimitive.boolean)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
