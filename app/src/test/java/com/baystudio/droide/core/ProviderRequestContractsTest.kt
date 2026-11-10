package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderRequestContractsTest {
    private fun body(tools: JsonArray = AgentToolCatalog.definitions) = buildJsonObject {
        put("model", "gemini-3.5-flash")
        put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hai") }) })
        put("tools", tools)
    }.toString()

    @Test fun completeCatalogSerializesThroughAllFourProtocols() {
        val canonical = ProviderModelRequestIdentity.canonicalize(body(), Json).body
        val openai = Json.parseToJsonElement(ProviderToolSchema.openAiRequest(canonical, Json)).jsonObject["tools"]!!.jsonArray
        val anthropic = Json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(canonical, false, Json)).jsonObject["tools"]!!.jsonArray
        val google = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(canonical, Json)).jsonObject["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        val responses = Json.parseToJsonElement(OpenAiResponsesAdapter.encodeRequest(canonical, false, Json)).jsonObject.getValue("tools").jsonArray
        assertTrue(openai.size >= 30)
        assertEquals(openai.size, anthropic.size)
        assertEquals(openai.size, google.size)
        assertEquals(openai.size, responses.size)
        openai.indices.forEach { index ->
            val fn = openai[index].jsonObject["function"]!!.jsonObject
            assertEquals(fn["name"], anthropic[index].jsonObject["name"])
            assertEquals(fn["name"], google[index].jsonObject["name"])
            assertEquals(fn["parameters"], anthropic[index].jsonObject["input_schema"])
            assertEquals(fn["parameters"], google[index].jsonObject["parametersJsonSchema"])
            assertEquals(fn["name"], responses[index].jsonObject["name"])
            assertEquals(fn["parameters"], responses[index].jsonObject["parameters"])
            assertFalse("parameters" in google[index].jsonObject)
        }
    }

    @Test fun pluginAndMcpDefinitionsPassThroughTheSameBoundaryAsBuiltinTools() {
        val custom = Json.parseToJsonElement("""[
          {"type":"function","function":{"name":"mcp_fixture","parameters":{
            "type":"object","properties":{"rows":{"type":"array","items":{
              "type":"object","properties":{"name":{"type":"string"}}
            }}}
          }}}
        ]""").jsonArray
        val request = ProviderModelRequestIdentity.canonicalize(body(custom), Json)
        val parameters = Json.parseToJsonElement(request.body).jsonObject["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(JsonArray(emptyList()), parameters["properties"]!!.jsonObject["rows"]!!.jsonObject["items"]!!.jsonObject["required"])
    }

    @Test fun providerSwapDoesNotSendPrivateContinuationMetadataToOpenAiGateway() {
        val source = """{"model":"vendor/gemini-test","messages":[{"role":"assistant","content":"hello","_droide_google_private_parts":[{"thoughtSignature":"private"}],"_droide_anthropic_private_content":[]}]}"""
        val encoded = ProviderToolSchema.openAiRequest(source, Json)
        assertFalse(encoded.contains("_droide_"))
        assertFalse(encoded.contains("private"))
        assertTrue(encoded.contains("hello"))
    }

    @Test fun googleRouterModelsKeepJsonSchemaInsteadOfBeingRewrittenByModelName() {
        val schema = """{"type":"object","properties":{"n":{"type":["number","null"],"enum":[1,2,null]}}}"""
        val source = """{"model":"google/gemini-3.5-flash","messages":[],"tools":[{"type":"function","function":{"name":"test","parameters":$schema}}]}"""
        val wire = Json.parseToJsonElement(ProviderToolSchema.openAiRequest(source, Json)).jsonObject
        assertEquals(JsonPrimitive(1), wire["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject["n"]!!.jsonObject["enum"]!!.jsonArray[0])
    }

    @Test fun bufferedAndStreamingAnthropicHaveIdenticalToolContracts() {
        val buffered = Json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(body(), false, Json)).jsonObject
        val stream = Json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(body(), true, Json)).jsonObject
        assertEquals(buffered["tools"], stream["tools"])
        assertEquals(JsonPrimitive(true), stream["stream"])
    }

    @Test fun nativeGoogleRoutesBothMethodsWithoutEmbeddingCredentialInUrl() {
        val provider = ProviderRegistry.byId("gemini")
        assertEquals(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT, provider.wireProtocol)
        assertEquals(ProviderAuthScheme.GOOGLE_API_KEY, provider.authScheme)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent", GoogleGenerateContentAdapter.requestUrl(provider.baseUrl, "gemini-3.5-flash", false))
        assertTrue(GoogleGenerateContentAdapter.requestUrl(provider.baseUrl, "gemini-3.5-flash", true).endsWith(":streamGenerateContent?alt=sse"))
        assertTrue(ReasoningSupport.wire("gemini", "gemini-3.5-flash", ReasoningEffort.HIGH) is ReasoningWire.GoogleNative)
        assertThrows(IllegalArgumentException::class.java) { ProviderRuntimeGuard.requireBoundProvider("gemini", "https://generativelanguage.googleapis.com/v1beta/openai") }
    }

    @Test fun nativeThinkingUsesProtoEnumAndOlderGeminiUsesTokenBudget() {
        val high = """{"model":"gemini-3.5-flash","messages":[{"role":"user","content":"hello"}],"reasoning_effort":"high"}"""
        val encoded = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(high, Json)).jsonObject
        assertEquals(JsonPrimitive("HIGH"), encoded["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"])
        assertFalse("reasoning_effort" in encoded)
        val older = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(high.replace("gemini-3.5-flash", "gemini-2.5-flash"), Json)).jsonObject
        assertEquals(JsonPrimitive(24576), older["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"])
        assertFalse("thinkingLevel" in older["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject)
    }
}
