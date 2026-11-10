package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Dynamic creation times are data: compact serializers must not recreate them on restore. */
class AgentDurableTimestampTest {
    @Test fun dynamicModelTimestampsAreAlwaysPresentWhileOrdinaryDefaultsRemainCompact() {
        val session = DroideSession("s-clock", "clock")
        val sessionJson = Json.parseToJsonElement(Json.encodeToString(session)).jsonObject
        assertEquals(session.createdAt, sessionJson.getValue("createdAt").jsonPrimitive.long)
        assertFalse(sessionJson.containsKey("messages"))
        assertFalse(sessionJson.containsKey("pendingInputs"))
        val context = AgentIdeContextSnapshot()
        val contextJson = Json.parseToJsonElement(Json.encodeToString(context)).jsonObject
        assertEquals(context.capturedAtMs, contextJson.getValue("capturedAtMs").jsonPrimitive.long)
        assertFalse(contextJson.containsKey("openFiles"))
        val input = AgentPendingInput("in-clock", "next", AgentPromptDelivery.QUEUE, "fixture", "model", ideContext = context)
        val inputJson = Json.parseToJsonElement(Json.encodeToString(input)).jsonObject
        assertEquals(input.createdAt, inputJson.getValue("createdAt").jsonPrimitive.long)
        assertFalse(inputJson.containsKey("images"))
        assertEquals(input, Json.decodeFromString<AgentPendingInput>(inputJson.toString()))
    }

    @Test fun streamPartsKeepActualStartTimeAndDiffMetadataOnCompactJsonRoundtrip() {
        val part = AgentStreamPart("tool-clock", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED,
            fileChanges = listOf(AgentFileChanges.preview("logo.txt", "old", "new")))
        val encoded = Json.encodeToString(part)
        val data = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(part.startedAtMs, data.getValue("startedAtMs").jsonPrimitive.long)
        assertFalse(data.containsKey("promptTokens"))
        assertEquals(part, Json.decodeFromString<AgentStreamPart>(encoded))
    }

    @Test fun subagentRecordPreservesBothDynamicTimesWithoutEncodingAllDefaultFields() {
        val task = SubagentTaskRecord("task-clock", "s-parent", "s-child", "explore", description = "Inspect a symbol",
            background = true, state = SubagentTaskState.QUEUED, delivery = SubagentDeliveryState.NONE)
        val encoded = Json.encodeToString(task)
        val data = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(task.createdAtMs, data.getValue("createdAtMs").jsonPrimitive.long)
        assertEquals(task.updatedAtMs, data.getValue("updatedAtMs").jsonPrimitive.long)
        assertFalse(data.containsKey("workspaceBranch"))
        assertEquals(task, Json.decodeFromString<SubagentTaskRecord>(encoded))
    }

    @Test fun actualSessionWritesAndPendingFieldUpdatesPreserveNestedCreationTimes() = runBlocking {
        val root = Files.createTempDirectory("droide-durable-clock-").toFile()
        try {
            val manager = SessionManager(root)
            val context = AgentIdeContextSnapshot()
            val pending = AgentPendingInput("in-durable-clock", "next", AgentPromptDelivery.QUEUE, "fixture", "model", ideContext = context)
            val part = AgentStreamPart("stream-clock", 1, AgentStreamPartKind.STATUS, AgentStreamPartState.COMPLETED)
            val session = manager.create().copy(messages = listOf(AgentMessage("assistant", "saved", streamPart = part)), pendingInputs = listOf(pending))
            manager.save(session)
            val file = File(root, ".droide/sessions/${session.id}.json")
            val disk = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(session.createdAt, disk.getValue("createdAt").jsonPrimitive.long)
            val storedInput = disk.getValue("pendingInputs").jsonArray.single().jsonObject
            assertEquals(pending.createdAt, storedInput.getValue("createdAt").jsonPrimitive.long)
            assertEquals(context.capturedAtMs, storedInput.getValue("ideContext").jsonObject.getValue("capturedAtMs").jsonPrimitive.long)
            assertEquals(part.startedAtMs, disk.getValue("messages").jsonArray.single().jsonObject.getValue("streamPart").jsonObject.getValue("startedAtMs").jsonPrimitive.long)
            manager.savePendingInputs(session.id, listOf(pending.copy(text = "updated pending text")))
            val restored = SessionManager(root).getForUpdate(session.id)!!
            assertEquals(session.createdAt, restored.createdAt)
            assertEquals(pending.createdAt, restored.pendingInputs.single().createdAt)
            assertEquals(context.capturedAtMs, restored.pendingInputs.single().ideContext!!.capturedAtMs)
            assertEquals(part, restored.messages.single().streamPart)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
