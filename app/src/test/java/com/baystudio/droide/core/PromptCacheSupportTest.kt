package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PromptCacheSupportTest {
    private val tools = buildJsonArray { add(buildJsonObject {
        put("type", "function"); put("function", buildJsonObject {
            put("name", "droide_help"); put("description", "Read requested handbook section")
            put("parameters", buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) })
        })
    }) }
    private fun config(provider: String = "anthropic", model: String = "claude-sonnet-4") =
        AgentConfig(providerId = provider, model = model)
    private fun text(msg: JsonObject): String = when (val c = msg.getValue("content")) {
        is JsonPrimitive -> c.content
        is JsonArray -> c.joinToString("") { it.jsonObject.getValue("text").jsonPrimitive.content }
        else -> error("Invalid system content")
    }
    private fun composed(project: String = "\nproject A", overlay: String = "profile A", mode: AgentMode = AgentMode.BUILD) =
        AgentRuntimePrompt.compose(mode, AgentRequestScope.modeInstruction(mode), project, overlay, false, tools)
    @Test fun stablePrefixPersistsAcrossDynamicProjectProfileAndModeChanges() {
        val a = PromptCacheSupport.systemMessage(config(), composed(), tools, availableBreakpoints = 4)
        val original = composed("\nproject B", "profile B", AgentMode.REVIEW)
        val b = PromptCacheSupport.systemMessage(config(), original, tools, availableBreakpoints = 4)
        val blocks = b.getValue("content").jsonArray
        assertEquals(2, blocks.size)
        assertEquals(a.getValue("content").jsonArray.first(), blocks.first())
        assertEquals(AgentSystemPrompt.STABLE_PREFIX, blocks.first().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(original, text(b))
        assertTrue(blocks.first().jsonObject.containsKey("cache_control"))
        assertTrue(blocks.last().jsonObject.containsKey("cache_control"))
        assertTrue(text(b).endsWith(AgentRequestScope.AUTHORITY_BOUNDARY.trimIndent()))
        assertTrue(text(b).contains(DroideHandbookPolicy.BOOTSTRAP))
    }
    @Test fun loadingCapabilitiesPreservesTheOwnedCachePrefixWithoutDroppingGuidance() {
        val browser = AgentTools.definitions.first { it.jsonObject["function"]?.jsonObject?.get("name") == JsonPrimitive("browser") }
        val nextTools = JsonArray(tools + browser)
        val nextText = AgentRuntimePrompt.compose(AgentMode.BUILD, AgentRequestScope.modeInstruction(AgentMode.BUILD), "", "", false, nextTools)
        val first = PromptCacheSupport.systemMessage(config(), composed(), tools, 4)
        val second = PromptCacheSupport.systemMessage(config(), nextText, nextTools, 4)
        assertEquals(first.getValue("content").jsonArray.first(), second.getValue("content").jsonArray.first())
        assertTrue(text(second).contains("- Browser:"))
        assertEquals(nextText, text(second))
    }

    @Test fun noSeparatorTrimOrDuplicateEvenWithNonAsciiAndEmptySuffix() {
        for (suffix in listOf("", "\n\n", " Ω😀\tmode=PLAN", AgentSystemPrompt.STABLE_PREFIX)) {
            val original = AgentSystemPrompt.STABLE_PREFIX + suffix
            val result = PromptCacheSupport.systemMessage(config(), original, tools, availableBreakpoints = 4)
            assertEquals(original, text(result))
            val blocks = result.getValue("content").jsonArray
            assertEquals(if (suffix.isEmpty()) 1 else 2, blocks.size)
            assertEquals(if (suffix.isEmpty()) 1 else 2, blocks.count { "cache_control" in it.jsonObject })
        }
    }
    @Test fun unknownPrefixRetainsSingleBlockInsteadOfGuessingOrDroppingText() {
        for (original in listOf("", "prefix " + AgentSystemPrompt.STABLE_PREFIX, AgentSystemPrompt.STABLE_PREFIX.dropLast(1))) {
            val result = PromptCacheSupport.systemMessage(config(), original, tools, availableBreakpoints = 4)
            assertEquals(original, text(result))
            assertEquals(1, result.getValue("content").jsonArray.size)
        }
    }
    @Test fun knownOpenRouterProxySupportedButModelNameAloneDoesNotAuthorizeMetadata() {
        for ((provider, model, allowed) in listOf(
            Triple("anthropic", "claude-sonnet-4", true),
            Triple("openrouter", "anthropic/claude-sonnet-4", true),
            Triple("openrouter", "openai/gpt-5", false),
            Triple("custom.proxy", "anthropic/claude-sonnet-4", false),
            Triple("openai", "anthropic/claude-sonnet-4", false),
            Triple("gemini", "gemini-2.5-pro", false),
        )) {
            val result=PromptCacheSupport.systemMessage(config(provider,model),composed(),tools, availableBreakpoints = 4)
            assertEquals(composed(),text(result))
            assertEquals(provider,allowed,result["content"] is JsonArray)
        }
    }
    @Test fun nullToolFinalAndGreetingRemainScalarAndExact() {
        for (original in listOf(AgentSystemPrompt.GREETING_TEXT, AgentSystemPrompt.STABLE_PREFIX + "\nfinal")) {
            val result=PromptCacheSupport.systemMessage(config(),original,null)
            assertEquals(JsonPrimitive(original),result["content"])
        }
        val result=PromptCacheSupport.systemMessage(config(),AgentSystemPrompt.GREETING_TEXT+"\nintent",tools, availableBreakpoints = 4)
        assertEquals(AgentSystemPrompt.GREETING_TEXT,result.getValue("content").jsonArray.first().jsonObject["text"]?.jsonPrimitive?.content)
    }
    @Test fun realAnthropicEncodingPreservesBothBlocksAndBothBreakpoints() {
        val original=composed()
        val system=PromptCacheSupport.systemMessage(config(),original,tools, availableBreakpoints = 4)
        val request=buildJsonObject {
            put("model","claude-sonnet-4"); put("max_tokens",1024)
            put("messages",buildJsonArray { add(system); add(buildJsonObject { put("role","user");put("content","Explain this file") }) })
            put("tools",tools); put("tool_choice","auto")
        }
        for (streaming in listOf(false,true)) {
            val native=Json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(request.toString(),streaming,Json)).jsonObject
            val blocks=native.getValue("system").jsonArray
            assertEquals(system["content"],blocks)
            assertEquals(original,blocks.joinToString("") { it.jsonObject.getValue("text").jsonPrimitive.content })
            assertEquals(2,blocks.count { "cache_control" in it.jsonObject })
            assertEquals(1,native.getValue("messages").jsonArray.size)
            assertEquals(1,native.getValue("tools").jsonArray.size)
        }
    }
    @Test fun requestWideBudgetLeavesExistingMarkersRoomAndFallsBackWithoutDroppingText() {
        val marked=buildJsonObject { put("cache_control",buildJsonObject { put("type","ephemeral") }) }
        val original=composed()
        for (existing in 0..6) {
            val history=List(existing) { buildJsonObject { put("role","user");put("content",buildJsonArray { add(marked) }) } }
            val budget=PromptCacheSupport.availableBreakpoints(history,tools)
            assertEquals((4-existing).coerceAtLeast(0),budget)
            val result=PromptCacheSupport.systemMessage(config(),original,tools,budget)
            assertEquals(original,text(result))
            if (budget==0) assertTrue(result["content"] is JsonPrimitive)
            else {
                val blocks=result.getValue("content").jsonArray
                assertEquals(if (budget==1) 1 else 2,blocks.size)
                assertTrue("End boundary must remain cached",blocks.last().jsonObject.containsKey("cache_control"))
            }
        }
        assertEquals(3,PromptCacheSupport.availableBreakpoints(emptyList(),JsonArray(listOf(marked))))
        assertEquals(1,PromptCacheSupport.systemMessage(config(),original,tools).getValue("content").jsonArray.size)
    }

}
