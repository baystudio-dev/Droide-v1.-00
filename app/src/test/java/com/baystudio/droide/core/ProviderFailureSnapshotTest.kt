package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderFailureSnapshotTest {
    private val protocols=ProviderWireProtocol.entries
    private fun progress(id:String="fixture")=ProviderFailureProgress(id,Json)
    private fun usage(protocol:ProviderWireProtocol, n:Int=5):String=when(protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS->"""{"usage":{"prompt_tokens":2,"completion_tokens":${n-2},"total_tokens":$n}}"""
        ProviderWireProtocol.OPENAI_RESPONSES->"""{"response":{"usage":{"input_tokens":2,"output_tokens":${n-2},"total_tokens":$n}}}"""
        ProviderWireProtocol.ANTHROPIC_MESSAGES->"""{"usage":{"input_tokens":2,"output_tokens":${n-2}}}"""
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT->"""{"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":${n-2},"totalTokenCount":$n}}"""
    }
    private fun failed(protocol:ProviderWireProtocol):String=when(protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS->"""{"error":{"message":"private error fixture"},"choices":[{"message":{"role":"assistant","content":"Public","tool_calls":[{"private":"secret tool"}],"reasoning_content":"secret thought"}}]}"""
        ProviderWireProtocol.OPENAI_RESPONSES->"""{"type":"response.failed","response":{"status":"failed","error":{"message":"private error fixture"},"output":[{"type":"reasoning","summary":"secret thought"},{"type":"function_call","arguments":"secret tool"},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Public"}]}]}}"""
        ProviderWireProtocol.ANTHROPIC_MESSAGES->"""{"error":{"message":"private error fixture"},"content":[{"type":"thinking","thinking":"secret thought"},{"type":"redacted_thinking","data":"secret encrypted"},{"type":"tool_use","input":"secret tool"},{"type":"text","text":"Public"}]}"""
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT->"""{"error":{"message":"private error fixture"},"candidates":[{"content":{"parts":[{"text":"secret thought","thought":true},{"text":"secret malformed thought","thought":"true"},{"functionCall":{"args":{"secret":"secret tool"}}},{"text":"Public","thought":false}]}}]}"""
    }
    private fun failure(snapshot:ProviderFailureSnapshot)=ProviderStreamFailure.fromUnsafeMessage("fixture",emptyList(),200,snapshot)

    @Test fun allProtocolsRetainOnlyPublicBufferedFailureText() {
        for(protocol in protocols) for(buffered in listOf(false,true)) {
            val p=progress();p.observe(protocol,failed(protocol),buffered)
            val snapshot=p.snapshot(emptyList());assertEquals("Public",snapshot.publicText)
            assertFalse(snapshot.toString().contains("secret"));assertFalse(snapshot.toString().contains("private error"))
            assertEquals(UsageAuthority.UNKNOWN,snapshot.usage.tokenAuthority);assertEquals(1L,snapshot.usage.requestCount)
        }
    }
    @Test fun allProtocolsRetainLatestReportedUsageWithoutAddingSnapshots() {
        for(protocol in protocols) {
            val p=progress();p.observe(protocol,usage(protocol,5));p.observe(protocol,usage(protocol,9));p.observe(protocol,failed(protocol))
            val u=p.snapshot(emptyList()).usage;assertEquals(9,u.total);assertEquals(1L,u.requestCount);assertEquals(UsageAuthority.AUTHORITATIVE,u.tokenAuthority)
        }
    }
    @Test fun sameFailureEnvelopeUsageIsCapturedBeforeErrorAdmission() {
        for(protocol in protocols) {
            val failure=Json.parseToJsonElement(failed(protocol)).jsonObject
            val report=Json.parseToJsonElement(usage(protocol,9)).jsonObject
            val combined=if(protocol==ProviderWireProtocol.OPENAI_RESPONSES) JsonObject(failure+("response" to JsonObject(failure["response"]!!.jsonObject+report["response"]!!.jsonObject))) else JsonObject(failure+report)
            val p=progress();p.observe(protocol,combined.toString());assertEquals(9,p.snapshot(emptyList()).usage.total)
        }
    }
    @Test fun malformedNewUsageInvalidatesOldInsteadOfBecomingZero() {
        for(protocol in protocols) for(bad in listOf("{}","[]","true","\"fixture\"")) {
            val p=progress();p.observe(protocol,usage(protocol))
            val raw=when(protocol) {
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT->"""{"usageMetadata":$bad}"""
                ProviderWireProtocol.OPENAI_RESPONSES->"""{"response":{"usage":$bad}}"""
                else->"""{"usage":$bad}"""
            }
            p.observe(protocol,raw)

            if(protocol!=ProviderWireProtocol.ANTHROPIC_MESSAGES || bad!="{}") assertEquals(UsageAuthority.UNKNOWN,p.snapshot(emptyList()).usage.tokenAuthority)
        }
    }
    @Test fun absentNullUsageRetainsPreviousAndExplicitZeroIsKnown() {
        for(protocol in protocols) {
            val p=progress();p.observe(protocol,usage(protocol));p.observe(protocol,"{}");p.observe(protocol,"""{"usage":null,"usageMetadata":null,"response":{"usage":null}}""")
            assertEquals(5,p.snapshot(emptyList()).usage.total)
        }
        val p=progress();p.observe(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,"""{"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}""")
        val u=p.snapshot(emptyList()).usage;assertEquals(0,u.total);assertEquals(UsageAuthority.AUTHORITATIVE,u.tokenAuthority)
    }
    @Test fun malformedCanonicalCountsCannotFallBackToAliasesOrStaleData() {
        for(bad in listOf("\"8\"","-1","2147483648","true","null")) {
            val p=progress();p.observe(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,usage(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS))
            p.observe(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,"""{"usage":{"prompt_tokens":$bad,"input_tokens":8,"completion_tokens":2,"total_tokens":$bad,"cost":$bad}}""")
            val u=p.snapshot(emptyList()).usage;assertEquals(UsageAuthority.UNKNOWN,u.tokenAuthority);assertFalse(u.breakdownComplete)
        }
    }
    @Test fun anthropicUsageMergesFieldsButMalformedReplacementRemainsUnknown() {
        val p=progress();p.observe(ProviderWireProtocol.ANTHROPIC_MESSAGES,"""{"type":"message_start","message":{"usage":{"input_tokens":2,"cache_read_input_tokens":4,"cache_creation_input_tokens":3}}}""")
        p.observe(ProviderWireProtocol.ANTHROPIC_MESSAGES,"""{"type":"message_delta","usage":{"output_tokens":5}}""")
        val u=p.snapshot(emptyList()).usage;assertEquals(14,u.total);assertEquals(9,u.prompt);assertEquals(4,u.cacheRead);assertEquals(3,u.cacheWrite)
        p.observe(ProviderWireProtocol.ANTHROPIC_MESSAGES,"""{"type":"message_delta","usage":{"output_tokens":"bad"}}""")
        assertEquals(UsageAuthority.UNKNOWN,p.snapshot(emptyList()).usage.tokenAuthority)
    }
    @Test fun googleThoughtAndToolPromptUsageNotDoubleCounted() {
        val p=progress();p.observe(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT,"""{"usageMetadata":{"promptTokenCount":2,"toolUsePromptTokenCount":3,"candidatesTokenCount":4,"thoughtsTokenCount":5,"totalTokenCount":14,"cachedContentTokenCount":1}}""")
        val u=p.snapshot(emptyList()).usage;assertEquals(5,u.prompt);assertEquals(9,u.completion);assertEquals(14,u.total);assertEquals(5,u.reasoning);assertEquals(1,u.cacheRead)
    }
    @Test fun streamedTextAndCumulativeFailureTextNeverDuplicateOrReorder() {
        val p=progress();p.record(LlmStreamEvent.TextDelta("Pub"));p.record(LlmStreamEvent.TextDelta("lic"));p.observe(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,failed(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS))
        assertEquals("Public",p.snapshot(emptyList()).publicText)
        p.observe(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,"""{"error":{},"choices":[{"message":{"content":"Divergent"}}]}""")
        assertEquals("Public",p.snapshot(emptyList()).publicText)
    }
    @Test fun splitCredentialsAreRedactedBeforeLengthBoundAndSnapshotHasOnlySafeFields() {
        val secret="credential-fixture-1234567890";val p=progress()
        p.record(LlmStreamEvent.TextDelta("x".repeat(99_990)+secret.take(12)))
        p.record(LlmStreamEvent.TextDelta(secret.drop(12)+"tail"))
        val snap=p.snapshot(listOf(secret));assertFalse(snap.publicText.contains(secret));assertFalse(snap.publicText.contains("credential-fixture"));assertTrue(snap.publicText.endsWith("tail"))
        assertEquals(setOf("publicText","usage"),ProviderFailureSnapshot::class.java.declaredFields.filterNot{it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers)}.map{it.name}.toSet())
    }
    @Test fun errorAndPrivateFieldsNeverBecomePublicEvenWhenContentShaped() {
        for(protocol in protocols) {
            val p=progress();p.observe(protocol,"""{"error":{"content":"secret","text":"secret","usage":{"total_tokens":999}},"reasoning":"secret","usage":{"total_tokens":3}}""",true)
            assertEquals("",p.snapshot(emptyList()).publicText)
        }
    }
    @Test fun strictPublicFieldTypesDoNotStringifyNumbersOrWrongRoles() {
        val fixtures=mapOf(
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS to """{"choices":[{"message":{"role":"user","content":"secret","refusal":123}}]}""",
            ProviderWireProtocol.OPENAI_RESPONSES to """{"output":[{"type":"message","role":"user","content":[{"type":"output_text","text":"secret"}]},{"type":"message","role":"assistant","content":[{"type":"output_text","text":123}]}]}""",
            ProviderWireProtocol.ANTHROPIC_MESSAGES to """{"content":[{"type":"text","text":123}]}""",
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT to """{"candidates":[{"content":{"parts":[{"text":123}]}}]}""")
        fixtures.forEach{(protocol,raw)->val p=progress();p.observe(protocol,raw,true);assertEquals("",p.snapshot(emptyList()).publicText)}
    }
    @Test fun accountingRecordsSuccessOrFailureOnceAndPreservesUnknownCoverage() {
        val tracker=TokenTracker();val a=ProviderRequestAccounting("fixture"){tracker.addSample("model",it)}
        val p=progress();p.observe(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,usage(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS))
        a.failure(failure(p.snapshot(emptyList())));assertNull(a.failure(failure(p.snapshot(emptyList()))))
        assertEquals(1L,tracker.usage.value.requestCount);assertEquals(5,tracker.usage.value.total)
        val b=ProviderRequestAccounting("fixture"){tracker.addSample("model",it)};b.response(usage(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS));assertNull(b.failure(failure(p.snapshot(emptyList()))))
        assertEquals(2L,tracker.usage.value.requestCount)
        val c=ProviderRequestAccounting("fixture"){tracker.addSample("model",it)};c.failure(failure(progress().snapshot(emptyList())))
        assertEquals(3L,tracker.usage.value.requestCount);assertEquals(UsageAuthority.MIXED,tracker.usage.value.tokenAuthority);assertEquals(2L,tracker.usage.value.knownTokenRequestCount)
    }
    @Test fun callbackCancellationIdentityAndAccountingReentrancyArePreserved()=runBlocking {
        val p=progress();val cancelled=CancellationException("owner")
        try {p.emit(LlmStreamEvent.TextDelta("Public")){throw cancelled};fail()}catch(t:CancellationException){assertSame(cancelled,t);assertTrue(p.isCallbackFailure(t))}
        val a=ProviderRequestAccounting("fixture"){throw cancelled}
        try {a.response(usage(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS));fail()}catch(t:CancellationException){assertSame(cancelled,t);assertTrue(a.isCallbackFailure(t))}
        assertNull(a.failure(failure(p.snapshot(emptyList()))))
        assertNull(ProviderRequestAccounting("fixture"){fail()}.failure(cancelled))
    }
    @Test fun immutableSnapshotDoesNotChangeWhenProgressContinues() {
        val p=progress();p.record(LlmStreamEvent.TextDelta("A"));p.observe(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,usage(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS))
        val first=p.snapshot(emptyList());p.record(LlmStreamEvent.TextDelta("B"));p.observe(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,usage(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,9))
        assertEquals("A",first.publicText);assertEquals(5,first.usage.total);assertEquals("AB",p.snapshot(emptyList()).publicText)
    }
    @Test fun capacityFailureDoesNotRetainCredentialPrefixOrGrowPastBound() {
        val p=progress();p.record(LlmStreamEvent.TextDelta("x".repeat(LlmClient.MAX_RESPONSE_CHARS-3)+"key"))
        try {p.record(LlmStreamEvent.TextDelta("rest-of-secret"));fail()}catch(_:IllegalStateException){}
        assertEquals("",p.snapshot(listOf("keyrest-of-secret")).publicText)
        val q=progress();q.record(LlmStreamEvent.TextDelta("old"))
        try {q.record(LlmStreamEvent.BufferedResponse("x".repeat(LlmClient.MAX_RESPONSE_CHARS+1)));fail()}catch(_:IllegalStateException){}
        assertEquals("",q.snapshot(emptyList()).publicText)
    }
    @Test fun callbackFailureIdentityCannotMasqueradeAsProviderOverflow() {
        val callbackError=LlmHttpException(400,"context length exceeded")
        val a=ProviderRequestAccounting("fixture"){throw callbackError}
        try {a.response(usage(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS));fail()}catch(t:Throwable){assertSame(callbackError,t);assertTrue(a.isCallbackFailure(t))}
        assertFalse(a.isCallbackFailure(LlmHttpException(400,"context length exceeded")))
        assertNull(a.failure(callbackError))
    }

    @Test fun malformedResponsesTerminalCanRetainTypedPublicSuffixWithoutPreEmission() {
        val p=progress();p.record(LlmStreamEvent.TextDelta("Public"))
        p.observe(ProviderWireProtocol.OPENAI_RESPONSES,"""{"type":"response.completed","response":{"status":"completed","output":[{"type":"reasoning","summary":"PRIVATE"},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Public suffix"}]},{"type":"function_call","arguments":"malformed"}],"usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5}}}""")
        val snap=p.snapshot(emptyList());assertEquals("Public suffix",snap.publicText);assertEquals(5,snap.usage.total)
        // Successful emitted text still extends only the original delta sequence.
        p.record(LlmStreamEvent.TextDelta(" suffix"));assertEquals("Public suffix",p.snapshot(emptyList()).publicText)
        p.observe(ProviderWireProtocol.OPENAI_RESPONSES,"""{"type":"response.incomplete","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Different"}]}]}}""")
        assertEquals("Public suffix",p.snapshot(emptyList()).publicText)
    }

}
