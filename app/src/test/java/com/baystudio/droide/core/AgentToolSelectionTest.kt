package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentToolSelectionTest {
    private fun names(tools: JsonArray) = AgentRuntimePrompt.toolNames(tools).toSet()
    private fun query(text: String, limit: Int = 1, offset: Int = 0) = buildJsonObject { put("query", text); put("limit", limit); put("offset", offset) }
    private fun extra(name: String, description: String = "Plugin operation") = buildJsonObject {
        put("type", "function"); put("function", buildJsonObject {
            put("name", name); put("description", description)
            put("parameters", buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) })
        })
    }

    @Test fun onlyCoreSchemasAreSentUntilDiscoveryAndNoCompatibilityAliasesAreDuplicated() {
        val selection = AgentToolSelection()
        val initial = selection.project(AgentTools.definitions)
        assertTrue(names(initial).containsAll(listOf("tool_search", "read_file", "write_file", "save_files", "skill")))
        assertFalse(names(initial).any { it in setOf("document", "audio", "browser", "generate_image", "bash", "read", "grep") })
        assertTrue(initial.toString().length < AgentTools.definitions.toString().length / 2)
        val found = Json.parseToJsonElement(selection.search(query("document"))).jsonObject
        assertEquals(JsonPrimitive("document"), found.getValue("matches").jsonArray.single().jsonObject["name"])
        val next = selection.project(AgentTools.definitions)
        assertTrue("document" in names(next)); assertFalse("audio" in names(next))
        assertEquals(AgentTools.definitions.first { it.jsonObject["function"]?.jsonObject?.get("name") == JsonPrimitive("document") },
            next.first { it.jsonObject["function"]?.jsonObject?.get("name") == JsonPrimitive("document") })
    }

    @Test fun revocationAndRemovalDiscardLoadedCapabilitiesAndSearchCannotRestoreThem() {
        val selection = AgentToolSelection()
        selection.project(AgentTools.definitions); selection.search(query("browser"))
        val readOnly = JsonArray(AgentTools.definitions.filter {
            AgentModePolicy.isToolVisible(AgentMode.PLAN, it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        }.filterNot { it.jsonObject["function"]?.jsonObject?.get("name") == JsonPrimitive("browser") })
        val next = selection.project(readOnly)
        assertFalse("browser" in names(next)); assertFalse("write_file" in names(next))
        assertEquals(0, Json.parseToJsonElement(selection.search(query("write_file"))).jsonObject.getValue("total_matches").jsonPrimitive.int)
        assertFalse(names(selection.project(readOnly)).contains("browser"))
    }

    @Test fun everyPermittedExtraRemainsDiscoverableWithBoundedPages() {
        val selection = AgentToolSelection()
        val catalog = JsonArray((0 until 80).map { extra("plugin_$it", "unique-capability-$it") })
        assertEquals(setOf("tool_search"), names(selection.project(catalog)))
        val seen = mutableSetOf<String>()
        var offset = 0
        do {
            val page = Json.parseToJsonElement(selection.search(query("", 5, offset))).jsonObject
            page.getValue("matches").jsonArray.forEach { seen += it.jsonObject.getValue("name").jsonPrimitive.content }
            val next = page["next_offset"]?.jsonPrimitive?.intOrNull
            offset = next ?: -1
        } while (offset >= 0)
        assertEquals(80, seen.size)
        assertTrue(names(selection.project(catalog)).size <= 13)
        assertTrue(selection.search(query("plugin_79")).contains("\"available_next_request\":true"))
    }

    @Test fun aggregateSchemaBudgetDoesNotGrowWithInstalledPlugins() {
        val selection = AgentToolSelection()
        val catalog = JsonArray((0 until 50).map { extra("plugin_$it", "漢".repeat(3_000)) })
        selection.project(catalog)
        repeat(50) { selection.search(query("plugin_$it")) }
        assertTrue(selection.project(catalog).toString().toByteArray().size < 68_000)
        assertTrue("plugin_49" in names(selection.project(catalog)))
        assertFalse("plugin_0" in names(selection.project(catalog)))
        val oversized = JsonArray(listOf(extra("plugin_large", "x".repeat(70_000))))
        selection.project(oversized)
        assertTrue(selection.search(query("plugin_large")).contains("\"available_next_request\":false"))
        assertFalse("plugin_large" in names(selection.project(oversized)))
    }

    @Test fun discoveryDisabledProfileKeepsItsOriginalSurface() {
        assertEquals(AgentTools.definitions, AgentToolSelection().project(AgentTools.definitions, false))
    }

    @Test fun freshRunDoesNotInheritPreviouslyLoadedPlugins() {
        val first = AgentToolSelection()
        first.project(AgentTools.definitions); first.search(query("browser"))
        assertFalse("browser" in names(AgentToolSelection().project(AgentTools.definitions)))
    }

    @Test fun changingToASmallerInputWindowEvictsSchemasWithoutRevivingThem() {
        val catalog = JsonArray(listOf(extra("plugin_large", "x".repeat(10_000))))
        val selection = AgentToolSelection()
        selection.project(catalog); selection.search(query("plugin_large"))
        assertTrue("plugin_large" in names(selection.project(catalog)))
        val budget = AgentToolSelection.extraBudget(8_000)
        assertEquals(6_400, budget)
        assertFalse("plugin_large" in names(selection.project(catalog, maxAdditionalSchemaBytes = budget)))
        assertTrue(selection.search(query("plugin_large")).contains("\"available_next_request\":false"))
    }

    @Test fun promptIncludesLoadedToolPoliciesWhileAlwaysPreservingCoreAuthority() {
        val selection = AgentToolSelection()
        val first = selection.project(AgentTools.definitions)
        val prompt = AgentSystemPrompt.forTools(names(first))
        assertFalse(prompt.contains("- Browser:"))
        assertFalse(prompt.contains("- Deleted local folders:"))
        assertTrue(prompt.contains(AgentRequestScope.INSTRUCTIONS))
        assertTrue(prompt.contains("Compaction means continuation, not completion"))
        assertTrue(prompt.contains("expected_document_id/expected_revision/expected_change_version"))
        selection.search(query("browser"))
        val next = AgentSystemPrompt.forTools(names(selection.project(AgentTools.definitions)))
        assertTrue(next.contains("- Browser:")); assertTrue(next.contains("uncertain effects"))
    }

    @Test fun realModeAndPermissionFilteringOccursBeforeDiscovery() {
        val root = Files.createTempDirectory("selection-permission").toFile()
        try {
            val perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("webfetch", "*", PermEffect.DENY)))
            val catalog = AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root),
                mode = AgentMode.PLAN, permissions = perms, browserAvailable = true)
            val selection = AgentToolSelection(); selection.project(catalog)
            assertFalse(selection.search(query("browser")).contains("\"name\":\"browser\""))
            assertFalse(selection.search(query("write_file")).contains("\"name\":\"write_file\""))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun projectedToolSchemasRemainValidAcrossAllFourProviderProtocols() {
        val selection = AgentToolSelection()
        selection.project(AgentTools.definitions)
        selection.search(query("document"))
        val tools = selection.project(AgentTools.definitions)
        val request = buildJsonObject {
            put("model", "test-model"); put("max_tokens", 1024)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "Read the document") }) })
            put("tools", tools); put("tool_choice", "auto")
        }.toString()
        fun root(text: String) = Json.parseToJsonElement(text).jsonObject
        val encoded = listOf(
            root(ProviderToolSchema.openAiRequest(request, Json))["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject },
            root(OpenAiResponsesAdapter.encodeRequest(request, false, Json))["tools"]!!.jsonArray.map { it.jsonObject },
            root(AnthropicMessagesAdapter.encodeRequest(request, false, Json))["tools"]!!.jsonArray.map { it.jsonObject },
            root(GoogleGenerateContentAdapter.encodeRequest(request, Json))["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray.map { it.jsonObject },
        )
        encoded.forEach { functions ->
            assertEquals(names(tools), functions.map { it.getValue("name").jsonPrimitive.content }.toSet())
            assertTrue(functions.first { it["name"] == JsonPrimitive("tool_search") }.toString().contains("query"))
            assertTrue(functions.first { it["name"] == JsonPrimitive("document") }.toString().contains("operation"))
        }
    }
}
