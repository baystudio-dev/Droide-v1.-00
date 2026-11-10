package com.baystudio.droide.core

import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class UserProviderDefinitionTest {
    private val example = UserProviderDefinition("user.example", "Example", "https://api.example.com/v1")
    @After fun clear() { ProviderRegistry.installUserConfigured(emptyList()); ProviderRegistry.installDiscovered(emptyList()); ProviderRegistry.installConfigured(emptyList()) }

    @Test fun metaIsAvailableWithoutCatalogOrNetworkAndUsesDocumentedTransport() {
        val meta = ProviderRegistry.findById("meta")!!
        assertEquals("Meta", meta.name)
        assertEquals("https://api.meta.ai/v1", meta.baseUrl)
        assertEquals(ProviderWireProtocol.OPENAI_RESPONSES, meta.wireProtocol)
        assertEquals("/responses", ProviderTransportV2.spec(meta).chatPath)
        assertEquals("Bearer fixture", ProviderTransportV2.applyAuth(Request.Builder().url(meta.baseUrl), meta, "fixture").build().header("Authorization"))
    }

    @Test fun metaCatalogModelsUseBootstrapAuthority() {
        val result = ModelsDevCatalog.parse("""{"meta":{"id":"meta","npm":"@ai-sdk/openai","api":"https://api.meta.ai/v1","models":{"muse-fixture":{"id":"muse-fixture","tool_call":true,"modalities":{"output":["text"]}}}}}""")
        assertTrue(result.providers.isEmpty())
        assertEquals("meta", result.capabilities.single().providerId)
        assertEquals(true, result.capabilities.single().transportCompatible)
    }

    @Test fun metaTerminalOnlyResponsesRetainFunctionCallsAndEncryptedReasoningForContinuation() {
        // Meta can deliver complete output items only in response.completed (see its upstream adapter).
        val response = Json.parseToJsonElement("""{"status":"completed","output":[
            {"type":"reasoning","id":"reason-1","summary":[],"encrypted_content":"opaque"},
            {"type":"function_call","id":"fn-1","call_id":"call-1","name":"list_files","arguments":"{}","status":"completed"}
        ]}""").jsonObject
        val stream = OpenAiResponsesAdapter.StreamAccumulator(Json)
        stream.accept(buildJsonObject { put("type", "response.completed"); put("response", response) }.toString())
        val message = Json.parseToJsonElement(stream.completedResponse()).jsonObject["choices"]!!.jsonArray.single().jsonObject["message"]!!.jsonObject
        assertEquals("list_files", message["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val body = buildJsonObject {
            put("model", "muse-fixture")
            putJsonArray("messages") {
                add(message)
                add(buildJsonObject { put("role", "tool"); put("tool_call_id", "call-1"); put("content", "[]") })
            }
        }
        val wire = Json.parseToJsonElement(OpenAiResponsesAdapter.encodeRequest(body.toString(), false, Json)).jsonObject
        assertEquals(JsonPrimitive(false), wire["store"])
        assertTrue(wire["input"]!!.jsonArray.any { it.jsonObject["encrypted_content"] == JsonPrimitive("opaque") })
        assertTrue(wire["input"]!!.jsonArray.any { it.jsonObject["type"] == JsonPrimitive("function_call_output") })
    }

    @Test fun eachProtocolHasMatchingAuthAndLocalEndpointsRemainLocal() {
        val auth = listOf(ProviderAuthScheme.BEARER, ProviderAuthScheme.BEARER, ProviderAuthScheme.ANTHROPIC_X_API_KEY, ProviderAuthScheme.GOOGLE_API_KEY)
        ProviderWireProtocol.entries.forEachIndexed { index, protocol ->
            assertEquals(auth[index], example.copy(protocol = protocol).provider().authScheme)
            val local = example.copy(baseUrl = "http://127.0.0.1:8000/v1/", protocol = protocol, requiresKey = false).provider()
            assertEquals(ProviderNetworkScope.LOOPBACK_ONLY, local.networkScope)
            assertEquals(ProviderAuthScheme.NONE, local.authScheme)
        }
    }

    @Test fun invalidDestinationsAndIdsAreRejectedBeforePersistence() {
        for (url in listOf("http://api.example.com/v1", "https://key@api.example.com/v1", "https://api.example.com/v1?key=secret", "file:///tmp/v1", "https://api.example.com/v1#key", "https://api.example.com:0/v1", "https://api.example.com/v1/chat/completions")) {
            assertTrue(url, runCatching { example.copy(baseUrl = url).normalized() }.isFailure)
        }
        for (id in listOf("openai", "meta", "custom.project", "user.", "user.bad/name", "user.bad\nname")) {
            assertTrue(id, runCatching { example.copy(id = id).normalized() }.isFailure)
        }
    }

    @Test fun codecRoundTripsAllProtocolsAndRejectsSecretsUnknownFieldsAndDuplicates() {
        val values = ProviderWireProtocol.entries.mapIndexed { index, protocol -> example.copy(id = "user.p$index", protocol = protocol, models = listOf("model/one")) }
        val raw = UserProviderCodec.encode(values)
        assertEquals(values, UserProviderCodec.decode(raw))
        assertTrue(runCatching { UserProviderCodec.decode(raw.replace("\"name\":", "\"apiKey\":\"secret\",\"name\":")) }.isFailure)
        assertTrue(runCatching { UserProviderCodec.encode(listOf(example, example)) }.isFailure)
        assertTrue(runCatching { UserProviderCodec.decode(raw.replace("\"version\":1", "\"version\":2")) }.isFailure)
    }

    @Test fun sizesAndModelTypesAreBounded() {
        assertTrue(runCatching { example.copy(models = List(17) { "m$it" }).normalized() }.isFailure)
        assertTrue(runCatching { UserProviderCodec.encode(List(33) { example.copy(id = "user.p$it") }) }.isFailure)
        assertTrue(runCatching { UserProviderCodec.decode(" ".repeat(UserProviderCodec.MAX_BYTES + 1)) }.isFailure)
        val raw = UserProviderCodec.encode(listOf(example.copy(models = listOf("good"))))
        assertTrue(runCatching { UserProviderCodec.decode(raw.replace("[\"good\"]", "[42]")) }.isFailure)
    }

    @Test fun projectAndCatalogCannotShadowUserProvidersOrTheirModelMetadata() {
        ProviderRegistry.installUserConfigured(listOf(example))
        val forged = example.provider().copy(baseUrl = "https://other.example/v1", catalogDiscovered = true)
        ProviderRegistry.installConfigured(listOf(forged))
        ProviderRegistry.installDiscovered(listOf(forged))
        assertEquals(example.baseUrl, ProviderRegistry.findById(example.id)!!.baseUrl)
        assertEquals(example.baseUrl, ProviderRegistry.findById(example.id, ProviderRegistry.prepareConfigured(emptyList()))!!.baseUrl)
        assertTrue(ModelsDevCatalog.parse("""{"user.example":{"npm":"@ai-sdk/openai-compatible","api":"https://other.example/v1","models":{"fake":{"id":"fake"}}}}""").capabilities.isEmpty())
    }

    @Test fun manualProbesUseSelectedWireProtocolAndRejectFalseSuccess() {
        ProviderWireProtocol.entries.forEach { protocol ->
            val provider = example.copy(protocol = protocol).provider()
            val (path, body) = UserProviderModelProbe.request(provider, "model/one")
            assertTrue(path.startsWith('/'))
            assertFalse(UserProviderModelProbe.validResponse(protocol, "{}"))
            assertFalse(UserProviderModelProbe.validResponse(protocol, """{"error":{"message":"bad key"}}"""))
            assertFalse(body.containsKey("apiKey"))
        }
        assertTrue(UserProviderModelProbe.validResponse(ProviderWireProtocol.OPENAI_RESPONSES, """{"status":"incomplete","output":[]}"""))
        assertFalse(UserProviderModelProbe.validResponse(ProviderWireProtocol.OPENAI_RESPONSES, """{"status":"failed","output":[]}"""))
        val anthropic = example.copy(protocol = ProviderWireProtocol.ANTHROPIC_MESSAGES, requiresKey = false).provider()
        assertEquals("2023-06-01", ProviderTransportV2.applyAuth(Request.Builder().url(anthropic.baseUrl), anthropic, "").build().header("anthropic-version"))
    }
}
