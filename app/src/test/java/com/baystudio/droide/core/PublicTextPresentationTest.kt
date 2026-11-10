package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class PublicTextPresentationTest {
    private val update = ProviderPublicTextPhase.COMMENTARY
    private val final = ProviderPublicTextPhase.FINAL_ANSWER
    private val neutral = ProviderPublicTextPhase.UNSPECIFIED
    private fun slices(text: String, role: AgentTextPresentation, spans: List<AgentPublicTextSpan> = emptyList(), complete: Boolean = true) =
        AgentPublicTextPresentation.slices(text, role, spans, complete)
    private fun roles(text: String, role: AgentTextPresentation, spans: List<AgentPublicTextSpan>, complete: Boolean = true) =
        slices(text,role,spans,complete).map { it.presentation }

    @Test fun legacyDefaultsRemainUntyped() {
        val message = AgentMessage("assistant", "Let's think. Sip bro!")
        val part = AgentStreamPart("old", 1, AgentStreamPartKind.TEXT, AgentStreamPartState.COMPLETED)
        assertEquals(AgentTextPresentation.LEGACY,message.textPresentation)
        assertEquals(AgentTextPresentation.LEGACY,part.textPresentation)
        assertTrue(message.publicTextSpans.isEmpty());assertEquals("",message.presentationId)
        assertEquals(listOf(AgentPublicTextSlice(0,message.content.length,AgentTextPresentation.LEGACY)),slices(message.content,message.textPresentation))
    }
    @Test fun languageAndPhrasesNeverSplitUntypedText() {
        for (text in listOf("Thinking: answer", "Proses. Jawaban.", "English.Bahasa", "Everything updated.Sip bro!", "<analysis>not a real channel</analysis>")) {
            assertEquals(1,slices(text,AgentTextPresentation.RESPONSE).size)
            assertEquals(text.length,slices(text,AgentTextPresentation.RESPONSE).single().endExclusive)
        }
    }
    @Test fun explicitPublicPhasesRemainOrderedAndExact() {
        val text="Working.\nAnswer 🇮🇩";val cut=9
        val spans=listOf(AgentPublicTextSpan(0,cut,update),AgentPublicTextSpan(cut,text.length,final))
        val projected=slices(text,AgentTextPresentation.ANSWER,spans)
        assertEquals(listOf(AgentTextPresentation.UPDATE,AgentTextPresentation.ANSWER),projected.map{it.presentation})
        assertEquals(text,projected.joinToString(""){text.substring(it.start,it.endExclusive)})
    }
    @Test fun providerFinalCannotOverrideCoordinatorContinuation() {
        val spans=listOf(AgentPublicTextSpan(0,3,final))
        assertEquals(listOf(AgentTextPresentation.RESPONSE),roles("yes",AgentTextPresentation.RESPONSE,spans))
        assertEquals(listOf(AgentTextPresentation.UPDATE),roles("yes",AgentTextPresentation.UPDATE,spans))
    }
    @Test fun unfinishedAnswerStaysNeutral() {
        assertEquals(AgentTextPresentation.RESPONSE,slices("yes",AgentTextPresentation.ANSWER,complete=false).single().presentation)
        assertEquals(listOf(AgentTextPresentation.RESPONSE),roles("yes",AgentTextPresentation.ANSWER,listOf(AgentPublicTextSpan(0,3,final)),false))
    }
    @Test fun explicitCommentaryCanBeVisibleDuringStreaming() {
        assertEquals(listOf(AgentTextPresentation.UPDATE),roles("now",AgentTextPresentation.RESPONSE,listOf(AgentPublicTextSpan(0,3,update)),false))
    }
    @Test fun invalidOffsetsFallBackWithoutDroppingText() {
        val bad=listOf(listOf(AgentPublicTextSpan(1,3,update)),listOf(AgentPublicTextSpan(-1,3,update)),listOf(AgentPublicTextSpan(0,4,update)),listOf(AgentPublicTextSpan(0,2,update)),listOf(AgentPublicTextSpan(0,0,update)),listOf(AgentPublicTextSpan(0,2,update),AgentPublicTextSpan(1,3,final)))
        bad.forEach { assertEquals(listOf(AgentPublicTextSlice(0,3,AgentTextPresentation.RESPONSE)),slices("abc",AgentTextPresentation.ANSWER,it)) }
    }
    @Test fun splitSurrogateBoundaryIsRejected() {
        val text="A😀B";val spans=listOf(AgentPublicTextSpan(0,2,update),AgentPublicTextSpan(2,4,final))
        assertFalse(AgentPublicTextPresentation.valid(text,spans))
        assertEquals(1,slices(text,AgentTextPresentation.RESPONSE,spans).size)
    }
    @Test fun astralUnicodeAndWhitespaceRemainExact() {
        val text="😀 \n語";val spans=listOf(AgentPublicTextSpan(0,2,update),AgentPublicTextSpan(2,text.length,final))
        assertTrue(AgentPublicTextPresentation.valid(text,spans))
        assertEquals(text,slices(text,AgentTextPresentation.ANSWER,spans).joinToString(""){text.substring(it.start,it.endExclusive)})
    }
    @Test fun excessiveMetadataStaysBoundedAndNeutral() {
        val text="x".repeat(129);val spans=text.indices.map{AgentPublicTextSpan(it,it+1,if(it%2==0)update else final)}
        assertFalse(AgentPublicTextPresentation.valid(text,spans))
        assertEquals(AgentTextPresentation.RESPONSE,slices(text,AgentTextPresentation.ANSWER,spans).single().presentation)
    }
    @Test fun emptyTextProducesNoChrome() { assertTrue(slices("",AgentTextPresentation.ANSWER).isEmpty()) }
    @Test fun phaseAppendCoalescesWithoutAddingCharacters() {
        var text="";var spans=emptyList<AgentPublicTextSpan>()
        listOf("a" to update,"b" to update,"c" to final).forEach{(delta,phase)->spans=AgentPublicTextPresentation.append(text,spans,delta,phase);text+=delta}
        assertEquals(listOf(AgentPublicTextSpan(0,2,update),AgentPublicTextSpan(2,3,final)),spans)
    }
    @Test fun unknownPrefixDoesNotAcquireLaterPhase() {
        val spans=AgentPublicTextPresentation.append("old",emptyList(),"new",final)
        assertEquals(listOf(AgentPublicTextSpan(0,3,neutral),AgentPublicTextSpan(3,6,final)),spans)
    }
    @Test fun emptyDeltaDoesNotChangeMetadata() {
        val spans=listOf(AgentPublicTextSpan(0,3,update));assertEquals(spans,AgentPublicTextPresentation.append("old",spans,"",final))
    }
    @Test fun actualProjectorPreservesStreamingAndBufferedText() {
        val state=MutableStateFlow(AgentStreamState());val projector=AgentStreamProjector(state,100,100)
        projector.appendText("text:1",1,"work",false,update,"public:run-a")
        projector.appendText("text:1",1,"done",true,final,"public:run-a")
        val part=projector.part("text:1")!!;assertEquals("workdone",part.text);assertEquals("public:run-a",part.presentationId)
        assertEquals(AgentTextPresentation.RESPONSE,part.textPresentation)
        assertEquals(listOf(AgentPublicTextSpan(0,4,update),AgentPublicTextSpan(4,8,final)),part.publicTextSpans)
    }
    @Test fun projectorBoundedPreviewDoesNotGrowSpansPastText() {
        val state=MutableStateFlow(AgentStreamState());val projector=AgentStreamProjector(state,5,100)
        projector.appendText("text:1",1,"work",false,update,"a");projector.appendText("text:1",1,"done",false,final,"a")
        val part=projector.part("text:1")!!;assertEquals("workd",part.text);assertTrue(AgentPublicTextPresentation.valid(part.text,part.publicTextSpans))
    }
    @Test fun reusedStepGetsDistinctPresentationIdentity() {
        val state=MutableStateFlow(AgentStreamState());val projector=AgentStreamProjector(state,100,100)
        projector.appendText("text:1",1,"a",false,update,"session-a:uuid-a");projector.remove("text:1")
        projector.appendText("text:1",1,"b",false,final,"session-b:uuid-b")
        assertEquals("session-b:uuid-b",projector.part("text:1")!!.presentationId)
    }
    @Test fun toolAndStopContinuationRemainUpdates() {
        assertEquals(AgentTextPresentation.UPDATE,AgentPublicTextPresentation.afterBoundary(true,false,false,false))
        assertEquals(AgentTextPresentation.UPDATE,AgentPublicTextPresentation.afterBoundary(false,false,true,false))
    }
    @Test fun genuinelyEndedSegmentBecomesAnswer() { assertEquals(AgentTextPresentation.ANSWER,AgentPublicTextPresentation.afterBoundary(false,false,false,true)) }
    @Test fun queuedNewUserEndsPreviousSegment() { assertEquals(AgentTextPresentation.ANSWER,AgentPublicTextPresentation.afterBoundary(false,true,false,false)) }
    @Test fun mixedAndUncertainPromotionsStayNeutral() {
        assertEquals(AgentTextPresentation.RESPONSE,AgentPublicTextPresentation.afterBoundary(false,true,true,false))
        assertEquals(AgentTextPresentation.RESPONSE,AgentPublicTextPresentation.afterBoundary(false,false,false,false))
    }
    @Test fun stopContinuationWinsOverQueuedUser() { assertEquals(AgentTextPresentation.UPDATE,AgentPublicTextPresentation.afterBoundary(true,true,false,false)) }
    @Test fun interruptionNeverCarriesFinalPresentation() {
        val p=AgentStreamPart("p",1,AgentStreamPartKind.TEXT,AgentStreamPartState.RUNNING,text="partial",textPresentation=AgentTextPresentation.ANSWER,presentationId="stable",publicTextSpans=listOf(AgentPublicTextSpan(0,7,final)))
        for(cancel in listOf(false,true)) {
            val snapshot=InterruptedAgentStream.snapshot(p,cancel,3)!!;val part=snapshot.message.streamPart!!
            assertEquals(AgentTextPresentation.RESPONSE,part.textPresentation);assertTrue(part.publicTextSpans.isEmpty());assertEquals("partial",part.text)
            assertEquals("stable",part.presentationId);assertFalse(snapshot.apiEntry.toString().contains("FINAL_ANSWER"))
        }
    }
    @Test fun metadataChangesDoNotRewriteTextOrForkMarker() {
        val old=AgentMessage("assistant","exact\ntext",forkHistoryEnd=17,textPresentation=AgentTextPresentation.RESPONSE,presentationId="a")
        val next=old.copy(textPresentation=AgentTextPresentation.ANSWER);assertEquals(old.content,next.content);assertEquals(17,next.forkHistoryEnd)
    }
}
