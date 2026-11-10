package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeEstimatorReproductionTest {
    private val json=Json
    private fun obj(s:String)=json.parseToJsonElement(s).jsonObject
    private val text="Review complete: preserve user edits, run focused regression tests, and report remaining failures.\n".repeat(110)
    private val call=obj("""{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"src/main.kt\"}"}}""")
    private fun native(protocol:String):JsonArray=when(protocol) {
        "anthropic" -> buildJsonArray {
            add(obj("""{"type":"thinking","thinking":"Private reasoning","signature":"opaque-preserve-exactly"}"""))
            add(buildJsonObject {put("type","text");put("text",text)})
            add(obj("""{"type":"tool_use","id":"call_1","name":"read_file","input":{"path":"src/main.kt"}}"""))
        }
        "google" -> buildJsonArray {
            add(buildJsonObject {put("text",text)})
            add(obj("""{"functionCall":{"id":"call_1","name":"read_file","args":{"path":"src/main.kt"}},"thoughtSignature":"opaque-preserve-exactly"}"""))
        }
        else -> buildJsonArray {
            add(obj("""{"type":"reasoning","id":"r1","encrypted_content":"opaque-preserve-exactly"}"""))
            add(buildJsonObject {put("type","message");put("role","assistant");put("content",buildJsonArray {add(buildJsonObject {put("type","output_text");put("text",text)})})})
            add(obj("""{"type":"function_call","call_id":"call_1","name":"read_file","arguments":"{\"path\":\"src/main.kt\"}","status":"completed"}"""))
        }
    }
    private fun key(p:String)=when(p) {"anthropic"->AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD;"google"->GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD;else->OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD}
    private fun assistant(p:String,value:JsonElement=native(p))=buildJsonObject {put("role","assistant");put("content",text);put("tool_calls",JsonArray(listOf(call)));put(key(p),value)}
    private fun messages(a:JsonObject,media:Boolean=false)=buildJsonArray {
        add(if(media) obj("""{"role":"user","content":[{"type":"text","text":"Inspect"},{"type":"image_url","image_url":{"url":"data:image/png;base64,aGVsbG8="}}]}""") else obj("""{"role":"user","content":"Inspect"}"""))
        add(a);add(obj("""{"role":"tool","tool_call_id":"call_1","content":"File read result"}"""))
    }
    private fun encode(p:String,m:JsonArray):JsonObject {
        val body=buildJsonObject {put("model",when(p){"anthropic"->"claude-sonnet-4-5";"google"->"gemini-2.5-pro";else->"gpt-5"});put("messages",m)}.toString()
        return obj(when(p){"anthropic"->AnthropicMessagesAdapter.encodeRequest(body,false,json);"google"->GoogleGenerateContentAdapter.encodeRequest(body,json);else->OpenAiResponsesAdapter.encodeRequest(body,false,json)})
    }
    @Test fun validNativeCopiesAreSentOnceAndSignaturesPreserved() {
        for(p in listOf("anthropic","google","responses")) {
            val a=assistant(p);val wire=encode(p,messages(a))
            val wireText=wire.toString()
            val escaped=JsonPrimitive(text).toString()
            assertEquals(p,1,wireText.windowed(escaped.length).count {it==escaped})
            assertTrue(wireText.contains("opaque-preserve-exactly"))
            assertFalse(wireText.contains(key(p)))
            assertTrue(a.toString().length > wireText.length * 1.8)
            println("$p: internalAssistantChars=${a.toString().length}, completeWireRequestChars=${wireText.length}")
        }
    }
    @Test fun currentEstimatorCountsBothCopiesDespiteAdapterSelectingOne() {
        for(p in listOf("anthropic","google","responses")) {
            val a=assistant(p)
            val current=ContextBudget.estimate("",messages(a).map {it.jsonObject},null,p,"fixture-model")
            val canonicalOnly=JsonObject(a-key(p))
            val canonical=ContextBudget.estimate("",messages(canonicalOnly).map {it.jsonObject},null,p,"fixture-model")
            assertTrue(current.approximateTokens > canonical.approximateTokens * 1.8)
            assertEquals(current.reservedOutputTokens,canonical.reservedOutputTokens)
            println("$p: currentEstimatedTokens=${current.approximateTokens}, canonicalOnlyEstimatedTokens=${canonical.approximateTokens}; canonical-only is NOT proposed fix")
        }
    }
    @Test fun incompleteNativeArrayCannotBeUsedToLowerEstimate() {
        for(p in listOf("anthropic","google","responses")) {
            assertThrows(IllegalArgumentException::class.java) {encode(p,messages(assistant(p,JsonArray(emptyList()))))}
        }
    }
    @Test fun malformedNativeFieldHasProtocolSpecificSelectionRules() {

        for(p in listOf("anthropic","google")) assertFalse(encode(p,messages(assistant(p,JsonPrimitive("invalid")))).toString().contains("opaque-preserve-exactly"))
        assertThrows(IllegalArgumentException::class.java) {encode("responses",messages(assistant("responses",JsonPrimitive("invalid"))))}
    }
    @Test fun providerSwitchUsesCanonicalNotForeignPrivateContent() {
        for(source in listOf("anthropic","google","responses")) for(target in listOf("anthropic","google","responses")) if(source!=target) {
            val wire=encode(target,messages(assistant(source))).toString()
            assertFalse(wire.contains("opaque-preserve-exactly"))
            assertTrue(wire.contains("Review complete"))
            assertTrue(wire.contains("read_file"))
        }
    }
    @Test fun nativeContinuationsDoNotDiscardUserMedia() {
        for(p in listOf("anthropic","google","responses")) {
            val wire=encode(p,messages(assistant(p),true)).toString()
            assertTrue(wire.contains("aGVsbG8="))
            assertTrue(wire.contains("opaque-preserve-exactly"))
        }
    }
    @Test fun argumentMismatchRejectsNativeInsteadOfSilentlyDroppingIt() {
        for(p in listOf("anthropic","google","responses")) {
            val wrong=json.parseToJsonElement(native(p).toString().replace("src/main.kt","other.kt"))
            assertThrows(IllegalArgumentException::class.java) {encode(p,messages(assistant(p,wrong)))}
        }
    }
    private fun provider(p:String)=AiProvider(p,p,"https://example.invalid", "",false,true,"","",wireProtocol=when(p){"anthropic"->ProviderWireProtocol.ANTHROPIC_MESSAGES;"google"->ProviderWireProtocol.GOOGLE_GENERATE_CONTENT;"responses"->ProviderWireProtocol.OPENAI_RESPONSES;else->ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS})
    @Test fun selectedNativeLowersTokensButPreservesRawGuardAndOriginalMessages() {
        for(p in listOf("anthropic","google","responses")) {
            val h=messages(assistant(p)).map {it.jsonObject};val original=h.toString()
            val raw=ContextBudget.estimate("system",h,null,p,"fixture")
            val projected=ContextBudget.estimate("system",h,null,p,"fixture",ProviderTransportV2.spec(provider(p),"fixture"))
            assertTrue(projected.approximateTokens < raw.approximateTokens)
            assertEquals(raw.serializedChars,projected.serializedChars)
            assertEquals(raw.reservedOutputTokens,projected.reservedOutputTokens)
            assertEquals(raw.triggerTokens,projected.triggerTokens)
            assertEquals(original,h.toString())
            println("Projected $p: ${raw.approximateTokens} -> ${projected.approximateTokens}; guard=${projected.serializedChars}")
        }
    }
    @Test fun invalidNativeAndWrongTransportKeepConservativeAccounting() {
        for(p in listOf("anthropic","google","responses")) {
            for(value in listOf(JsonArray(emptyList()),JsonPrimitive("invalid"))) {
                val a=assistant(p,value)
                assertEquals(a,NativeContextProjection.forAccounting(a,ProviderTransportV2.spec(provider(p),"fixture")))
            }
            val a=assistant(p)
            assertEquals(a,NativeContextProjection.forAccounting(a,ProviderTransportV2.spec(provider("custom"),"gpt-5")))
            assertEquals(a,NativeContextProjection.forAccounting(a,null))
        }
    }
    @Test fun rawMobileGuardStillTriggersAfterTokenDeduplication() {
        val h=List(60){assistant("anthropic")}
        val raw=ContextBudget.estimate("",h,null,"anthropic","unknown")
        val projected=ContextBudget.estimate("",h,null,"anthropic","unknown",ProviderTransportV2.spec(provider("anthropic"),"unknown"))
        assertTrue(projected.approximateTokens < raw.approximateTokens)
        assertTrue(projected.serializedChars >= ContextBudget.MOBILE_COMPACT_CHARS)
        assertTrue(projected.shouldCompact)
    }
    @Test fun imageAllowanceUnchangedByNativeDeduplication() {
        for(p in listOf("anthropic","google","responses")) {
            val spec=ProviderTransportV2.spec(provider(p),"fixture")
            val plain=ContextBudget.estimate("",messages(assistant(p)).map{it.jsonObject},null,p,"fixture",spec)
            val media=ContextBudget.estimate("",messages(assistant(p),true).map{it.jsonObject},null,p,"fixture",spec)
            assertTrue(media.approximateTokens-plain.approximateTokens>=4096)
            assertTrue(media.approximateTokens-plain.approximateTokens<4200)
        }
    }

    @Test fun mismatchedResponsesTextNeverEarnsAccountingDeduction() {
        val original=assistant("responses")
        val malformed=JsonObject(original+("content" to JsonPrimitive("different canonical text")))
        val spec=ProviderTransportV2.spec(provider("responses"),"gpt-5")
        assertEquals(malformed,NativeContextProjection.forAccounting(malformed,spec))
        assertThrows(IllegalArgumentException::class.java){encode("responses",messages(malformed))}
    }

    @Test fun finalAdmissionUsesTheSameValidatedNativeProjectionAsPreflight() {
        for (p in listOf("anthropic", "google", "responses")) {
            val model = "budget-native"
            ModelContextRegistry.remember(p, model, 5000)
            try {
                val history = messages(assistant(p))
                val root = buildJsonObject { put("model", model); put("max_tokens", 1024); put("messages", history) }
                val raw = ContextBudget.estimate("", history.map { it.jsonObject }, null, p, model, null, 1024)
                assertTrue(raw.approximateTokens > 5000 - 1024)
                val prepared = ProviderRequestBudget.prepare(root, p, model, provider(p))
                assertEquals(history, prepared["messages"])
                assertEquals(1024, ProviderRequestBudget.explicitOutputTokens(prepared))
                assertTrue(prepared.toString().contains("opaque-preserve-exactly"))
            } finally { ModelContextRegistry.forget(p, model) }
        }
    }

}
