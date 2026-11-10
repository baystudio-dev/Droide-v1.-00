package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ProcessInputAdmissionTest {
    private fun reason(result:ProcessInputBuffer.OfferAdmission)=
        (result as ProcessInputBuffer.OfferAdmission.Rejected).reason
    @Test fun fullPipeRejectsOnlyCurrentChunkWhileEarlierEightRemain() {
        val b=ProcessInputBuffer();repeat(8){b.offer("old$it;")}
        val result=b.offerWithEvidence("new;")
        assertEquals(ProcessInputBuffer.OfferRejection.PIPE_QUEUE_FULL,reason(result))
        val ex=assertThrows(IllegalStateException::class.java){result.requireAccepted()}
        assertEquals("Command input queue is full or closed; input was not sent. Check the terminal before retrying",ex.message)
        b.close();val output=ByteArrayOutputStream();b.drain(output){true}
        assertEquals((0..7).joinToString(""){"old$it;"},output.toString("UTF-8"))
    }
    @Test fun closedAndAbortedHaveTypedNoAdmissionAndExactLegacyErrors() {
        for(abort in listOf(false,true)) {
            val b=ProcessInputBuffer();if(abort)b.abort() else b.close()
            assertEquals(ProcessInputBuffer.OfferRejection.CLOSED,reason(b.offerWithEvidence("new")))
            assertEquals("Command stdin is closed",assertThrows(IllegalStateException::class.java){b.offer("new")}.message)
        }
    }
    @Test fun oversizeRejectsBeforeSinkAndLegacyBoundaryStays64KiB() {
        val b=ProcessInputBuffer();var calls=0;b.bindPty({calls++;true}){true}
        assertEquals(ProcessInputBuffer.OfferRejection.TOO_LARGE,reason(b.offerWithEvidence("x".repeat(65537))))
        assertEquals("Input exceeds 64 KiB",assertThrows(IllegalArgumentException::class.java){b.offer("x".repeat(65537))}.message)
        assertEquals(0,calls);assertEquals(ProcessInputBuffer.OfferAdmission.Accepted,b.offerWithEvidence("x".repeat(65536)));assertEquals(1,calls)
    }
    @Test fun emptyInputDoesNotInvokeSinkAndClosedCheckStillPrecedesSize() {
        val b=ProcessInputBuffer();var calls=0;b.bindPty({calls++;true}){true}
        assertEquals(ProcessInputBuffer.OfferAdmission.Empty,b.offerWithEvidence(""));assertEquals(0,calls)
        b.close();assertEquals(ProcessInputBuffer.OfferRejection.CLOSED,reason(b.offerWithEvidence("x".repeat(65537))))
    }
    @Test fun arbitraryPtyFalseAfterEffectIsNotTypedNoAdmission() {
        val b=ProcessInputBuffer();var effects=0;b.bindPty({effects++;false}){true}
        assertThrows(IllegalStateException::class.java){b.offerWithEvidence("new")}
        assertEquals(1,effects)
    }
    @Test fun arbitraryPtyThrowWithIdenticalClosedMessageIsNotTypedNoAdmission() {
        val b=ProcessInputBuffer();val error=IllegalStateException("Command stdin is closed")
        b.bindPty({throw error}){true}
        assertSame(error,assertThrows(IllegalStateException::class.java){b.offerWithEvidence("new")})
    }
}
