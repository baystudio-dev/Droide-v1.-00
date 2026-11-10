package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MediaAuthoringContractsTest {
    private val mediaNames = setOf("document", "audio", "transcribe_audio", "generate_image")
    @Test fun typedMediaSchemasSurviveAllFourProviderWireFormats() {
        val definitions = JsonArray(AgentToolCatalog.definitions.filter {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content in mediaNames
        })
        val request = buildJsonObject {
            put("model", "current-model"); put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "Edit these files") }) })
            put("tools", definitions)
        }.toString()
        fun root(text: String) = Json.parseToJsonElement(text).jsonObject
        val formats = listOf(
            root(ProviderToolSchema.openAiRequest(request, Json))["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject },
            root(OpenAiResponsesAdapter.encodeRequest(request, false, Json))["tools"]!!.jsonArray.map { it.jsonObject },
            root(AnthropicMessagesAdapter.encodeRequest(request, false, Json))["tools"]!!.jsonArray.map { it.jsonObject },
            root(GoogleGenerateContentAdapter.encodeRequest(request, Json))["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray.map { it.jsonObject },
        )
        for (format in formats) {
            assertEquals(mediaNames, format.map { it["name"]!!.jsonPrimitive.content }.toSet())
            val fn = format.single { it["name"] == JsonPrimitive("document") }
            val schema = (fn["parameters"] ?: fn["input_schema"] ?: fn["parametersJsonSchema"])!!.jsonObject
            val pages = schema["properties"]!!.jsonObject["pages"]!!.jsonObject
            assertEquals(JsonPrimitive("integer"), pages["items"]!!.jsonObject["type"])
            assertEquals(JsonArray(listOf(JsonPrimitive("operation"))), schema["required"])
        }
    }
    @Test fun runtimeToolsNeedContextAndMutationModesButReferenceSkillsDoNot() {
        val root = Files.createTempDirectory("media-surface-").toFile()
        try {
            fun names(available: Boolean, mode: AgentMode) = AgentTools.definitionsWithCustom(
                SkillManager(root).withBundled(), CustomToolManager(root), authoringAvailable = available, mode = mode
            ).map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertFalse("document" in names(false, AgentMode.BUILD)); assertFalse("audio" in names(false, AgentMode.BUILD))
            assertTrue(names(true, AgentMode.BUILD).containsAll(setOf("document", "audio", "transcribe_audio")))
            assertTrue(names(true, AgentMode.PLAN).containsAll(setOf("document", "audio")))
            assertFalse("transcribe_audio" in names(true, AgentMode.PLAN)); assertEquals(3, SkillManager(root).withBundled().catalog().size)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun runtimeFailureJsonIsFailureEvidenceNotSuccessfulMetadata() {
        assertTrue(AgentToolResultSemantics.classify("audio", """{"ok":false,"error":"Missing ffmpeg"}""").isFailure)
        assertTrue(AgentToolResultSemantics.classify("document", """{"ok":false,"error":"Corrupt PDF"}""").isFailure)
        assertFalse(AgentToolResultSemantics.classify("audio", """{"ok":true,"speech_transcribed":false}""").isFailure)
    }
}
