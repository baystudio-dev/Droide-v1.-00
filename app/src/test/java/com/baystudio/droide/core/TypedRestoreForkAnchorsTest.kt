package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TypedRestoreForkAnchorsTest {
    private val target=TypedChatTarget("provider","model","endpoint-sha256:"+"a".repeat(64))
    private fun legacy(role:String,text:String)=TypedChatEntry.Legacy(buildJsonObject {put("role",role);put("content",text)})
    private fun checkpoint(entries:List<TypedChatEntry>)=TypedChatCheckpoint(target=target,live=entries,exact=entries)
    private val native=TypedChatEntry.OwnedAssistant("12345678-1234-1234-1234-123456789abc","run","response",listOf("call"))
    private val result=TypedChatEntry.Legacy(buildJsonObject {put("role","tool");put("tool_call_id","call");put("content","settled")})

    @Test fun duplicateTextUsesExactOrderAndPreservesNormalizedLoadedFields() {
        val history=checkpoint(listOf(legacy("user","same"),legacy("assistant","same")))
        val saved=listOf(AgentMessage("user","same",presentationId="first",forkHistoryEnd=1),AgentMessage("assistant","same",presentationId="second",forkHistoryEnd=2))
        val loaded=saved.map {it.copy(forkHistoryEnd=null)}
        val ends=TypedRestoreForkAnchors.validatedEnds(saved,history,history)
        assertEquals(listOf(1,2),TypedRestoreForkAnchors.restore(saved,loaded,ends).map {it.forkHistoryEnd})
        val changed=loaded.toMutableList();changed[0]=changed[0].copy(content="normalized")
        val restored=TypedRestoreForkAnchors.restore(saved,changed,ends)
        assertEquals("normalized",restored[0].content);assertNull(restored[0].forkHistoryEnd);assertEquals(2,restored[1].forkHistoryEnd)
        assertTrue(TypedRestoreForkAnchors.restore(saved,loaded.reversed(),ends).all {it.forkHistoryEnd==null})
    }
    @Test fun splitPendingInvalidAndOutOfRangeAnchorsAreNeverPromoted() {
        val history=checkpoint(listOf(legacy("user","start"),native,result))
        val saved=listOf(-1,1,2,3,99).map {AgentMessage("assistant","row",forkHistoryEnd=it)}
        assertEquals(setOf(1,3),TypedRestoreForkAnchors.validatedEnds(saved,history,history))
        assertTrue(TypedRestoreForkAnchors.restore(saved,saved.dropLast(1),setOf(1,3)).all {it.forkHistoryEnd==null})
    }
    @Test fun recoveredInsertionCannotShiftOldAnchorOntoDifferentCompletePrefix() {
        val original=checkpoint(listOf(legacy("user","start"),native,legacy("user","later")))
        val recovered=checkpoint(listOf(legacy("user","start"),native,result,legacy("user","later")))
        val saved=listOf(AgentMessage("user","start",forkHistoryEnd=1),AgentMessage("user","later",forkHistoryEnd=3))
        assertEquals(setOf(1),TypedRestoreForkAnchors.validatedEnds(saved,original,recovered))
    }
    private class CountingList<T>(private val values:List<T>): AbstractList<T>() {
        var reads=0
        override val size:Int get()=values.size
        override fun get(index:Int):T { reads++;return values[index] }
    }
    @Test fun longHistoryChecksEachPrefixOnceAndRetainsEarlierAnchorsWithPendingTail() {
        val count=10_000
        val rows=List(count) { legacy(if(it%2==0) "user" else "assistant","row-$it") }
        val first=CountingList(rows+native)
        val second=CountingList(rows+native)
        val saved=(0..count+1).map { AgentMessage("assistant","row",forkHistoryEnd=it) }
        assertEquals((0..count).toSet(),TypedRestoreForkAnchors.validatedEnds(saved,checkpoint(first),checkpoint(second)))
        assertTrue("original history repeatedly scanned: ${first.reads}",first.reads<=2*(count+1))
        assertTrue("reconciled history repeatedly scanned: ${second.reads}",second.reads<=2*(count+1))
    }
    @Test fun atomicOwnedTurnWidthsAndMalformedTailPreserveOnlyEarlierCompletePrefixes() {
        val turn=TypedChatEntry.OwnedTurn("12345678-1234-1234-1234-123456789abc","run","response",2)
        val original=checkpoint(listOf(legacy("user","start"),turn,native,legacy("user","invalid")))
        val saved=(0..7).map { AgentMessage("assistant","row",forkHistoryEnd=it) }
        assertEquals(setOf(0,1,4),TypedRestoreForkAnchors.validatedEnds(saved,original,original))
        val changed=checkpoint(listOf(legacy("user","start"),turn.copy(responseId="different")))
        assertEquals(setOf(0,1),TypedRestoreForkAnchors.validatedEnds(saved,original,changed))
    }
}
