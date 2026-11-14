package com.baystudio.droide.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Requires the project's serialization compiler plugin; authored, not host-fixture executed. */
class R58PublicTextSerializationTest {
    private val json=Json { ignoreUnknownKeys=true }
    @Test fun legacyMessageWithoutNewFieldsLoadsExactly() {
        val value=json.decodeFromString<AgentMessage>("""{"role":"assistant","content":"English.Sip bro!","forkHistoryEnd":4}""")
        assertEquals(AgentTextPresentation.LEGACY,value.textPresentation);assertEquals("English.Sip bro!",value.content);assertEquals(4,value.forkHistoryEnd)
    }
    @Test fun legacyPartWithoutNewFieldsLoadsExactly() {
        val value=json.decodeFromString<AgentStreamPart>("""{"id":"old","step":1,"kind":"TEXT","state":"COMPLETED","text":"old"}""")
        assertEquals(AgentTextPresentation.LEGACY,value.textPresentation);assertTrue(value.publicTextSpans.isEmpty())
    }
    @Test fun typedMessageRoundTripsWithExactUnicodeOffsets() {
        val value=AgentMessage("assistant","😀done",forkHistoryEnd=7,textPresentation=AgentTextPresentation.ANSWER,presentationId="uuid-a",publicTextSpans=listOf(AgentPublicTextSpan(0,2,ProviderPublicTextPhase.COMMENTARY),AgentPublicTextSpan(2,6,ProviderPublicTextPhase.FINAL_ANSWER)))
        assertEquals(value,json.decodeFromString<AgentMessage>(json.encodeToString(value)))
    }
    @Test fun typedInterruptedPartRoundTripsWithoutBecomingAnswer() {
        val value=AgentStreamPart("p",1,AgentStreamPartKind.TEXT,AgentStreamPartState.ERROR,text="partial",textPresentation=AgentTextPresentation.RESPONSE,presentationId="uuid-b")
        assertEquals(value,json.decodeFromString<AgentStreamPart>(json.encodeToString(value)))
    }
    @Test fun restoredMalformedOffsetsRenderNeutralWithoutLosingContent() {
        val value=json.decodeFromString<AgentMessage>("""{"role":"assistant","content":"abc","textPresentation":"ANSWER","publicTextSpans":[{"start":0,"endExclusive":999,"phase":"FINAL_ANSWER"}]}""")
        assertEquals(listOf(AgentPublicTextSlice(0,3,AgentTextPresentation.RESPONSE)),AgentPublicTextPresentation.slices(value.content,value.textPresentation,value.publicTextSpans,true))
    }
}
