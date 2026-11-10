package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsTest {
    @Test fun builtInDefinitionsHaveUniqueFunctionNamesAndRequiredSchemas() {
        val names = AgentTools.definitions.map { element ->
            val fn = element.jsonObject.getValue("function").jsonObject
            assertEquals("object", fn.getValue("parameters").jsonObject.getValue("type").jsonPrimitive.content)
            fn.getValue("name").jsonPrimitive.content
        }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.containsAll(listOf("read_file", "write_file", "run_command", "environment_probe", "git_status", "lsp")))
    }

    @Test fun aliasesMapToSamePermissionClassAsCanonicalTools() {
        assertEquals(AgentTools.actionOf("run_command"), AgentTools.actionOf("bash"))
        assertEquals(AgentTools.actionOf("edit_file"), AgentTools.actionOf("edit"))
        assertEquals(AgentTools.actionOf("write_file"), AgentTools.actionOf("write"))
        assertEquals(AgentTools.actionOf("read_file"), AgentTools.actionOf("read"))
        assertEquals(AgentTools.actionOf("search_files"), AgentTools.actionOf("grep"))
    }

    @Test fun mutatingAndExecutionToolsNeverMapToReadPermission() {
        listOf("write_file", "edit_file", "apply_patch", "run_command", "git_commit", "git_push", "worktree")
            .forEach { name -> assertTrue("$name must be gated", AgentTools.actionOf(name) != "read") }
    }

    private val contributedSchema = Json.parseToJsonElement("""{
        "type":"object",
        "properties":{
            "tasks":{"type":"array","items":{
                "type":"object",
                "properties":{"content":{"type":"string"},"priority":{"type":"integer"}},
                "required":["content"],
                "additionalProperties":false
            }}
        },
        "required":["tasks"],
        "additionalProperties":false
    }""").jsonObject

    private fun withContributedDefinitions(block: (JsonArray) -> Unit) {
        val workspace = Files.createTempDirectory("droide-contributed-tools-").toFile()
        try {
            val plugin = AgentPluginToolDef("fixture", "tasks", "plugin_fixture_tasks", "Plugin tasks",
                listOf("fixture"), contributedSchema, 10_000L, "fixture-sha")
            val mcp = McpAgentTool("mcp__fixture__tasks__0123456789ab", "fixture", "tasks", "MCP tasks", contributedSchema, "fixture@cfg:sha")
            block(AgentTools.definitionsWithCustom(SkillManager(workspace), CustomToolManager(workspace),
                pluginTools = listOf(plugin), mcpTools = listOf(mcp)))
        } finally {
            PathSecurity.deleteTreeNoFollow(workspace)
        }
    }

    @Test fun pluginAndMcpInsertTheirFullSchemaWithoutWrappingItAsProperties() = withContributedDefinitions { definitions ->
        val contributed = definitions.map { it.jsonObject.getValue("function").jsonObject }
            .filter { it.getValue("name").jsonPrimitive.content in setOf("plugin_fixture_tasks", "mcp__fixture__tasks__0123456789ab") }
        assertEquals(2, contributed.size)
        contributed.forEach { assertEquals(contributedSchema, it["parameters"]) }
    }

    @Test fun contributedRequiredFieldsAndTypedArrayItemsReachEveryProviderTransport() = withContributedDefinitions { definitions ->
        val request = buildJsonObject {
            put("model", "gemini-2.5-flash")
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "List tasks") }) })
            put("tools", definitions)
        }.toString()
        val canonical = ProviderModelRequestIdentity.canonicalize(request, Json).body
        val openAi = Json.parseToJsonElement(ProviderToolSchema.openAiRequest(canonical, Json)).jsonObject.getValue("tools").jsonArray
        val anthropic = Json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(canonical, false, Json)).jsonObject.getValue("tools").jsonArray
        val google = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(canonical, Json)).jsonObject.getValue("tools").jsonArray[0]
            .jsonObject.getValue("functionDeclarations").jsonArray
        val expected = ProviderToolSchema.parameters(contributedSchema, "fixture")
        listOf("plugin_fixture_tasks", "mcp__fixture__tasks__0123456789ab").forEach { name ->
            val parameters = openAi.map { it.jsonObject.getValue("function").jsonObject }
                .single { it["name"] == JsonPrimitive(name) }.getValue("parameters").jsonObject
            assertEquals(expected, parameters)
            assertEquals(expected, anthropic.single { it.jsonObject["name"] == JsonPrimitive(name) }.jsonObject["input_schema"])
            assertEquals(expected, google.single { it.jsonObject["name"] == JsonPrimitive(name) }.jsonObject["parametersJsonSchema"])
            assertEquals(JsonArray(listOf(JsonPrimitive("tasks"))), parameters["required"])
            val items = parameters.getValue("properties").jsonObject.getValue("tasks").jsonObject.getValue("items").jsonObject
            assertEquals(JsonPrimitive("integer"), items.getValue("properties").jsonObject.getValue("priority").jsonObject["type"])
            assertEquals(JsonArray(listOf(JsonPrimitive("content"))), items["required"])
            assertEquals(JsonPrimitive(false), items["additionalProperties"])
        }
    }
}
