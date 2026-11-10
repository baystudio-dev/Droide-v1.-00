package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SourceStdinRejectionTest {
    @get:org.junit.Rule val applicationAdmission:org.junit.rules.TestRule=ApplicationWorkAdmissionFixture()
    private val owner=OwnedResponseContentStore.Owner("session","run","call")
    private suspend fun fixture(block:suspend(BackgroundCommandManager,String,ProcessInputBuffer)->Unit) {
        val ready=CompletableDeferred<ProcessInputBuffer>()
        val terminal=object:ITerminalSession by AgentWorkflowTerminal() {
            override suspend fun execStreamingWithInput(cmd:String,timeoutMs:Long,input:ProcessInputBuffer,onOutput:(String)->Unit):ExecResult {
                ready.complete(input);awaitCancellation()
            }
        }
        val m=BackgroundCommandManager(terminal)
        try {val id=m.start("fixture",keepStdinOpen=true).id;block(m,id,withTimeout(5000){ready.await()})}
        finally{m.closeAndJoin()}
    }
    @Test fun queueFullKeepsAttemptButProvesNoNewAdmissionAndDoesNotClose()=runBlocking {
        fixture{m,id,b->
            repeat(8){b.offer("old$it;")}
            val binding=m.bindSourceStdin(id,owner,"a".repeat(64),"new;",true,false)
            m.beginSourceStdin(binding,id,"new;",true,false)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(binding,id,"new;",true)}
            val e=m.inspectSourceStdin(binding)
            assertTrue(e.offerAttempted);assertTrue(e.offerKnownRejected);assertFalse(e.offerAccepted)
            assertEquals(ProcessInputBuffer.OfferRejection.PIPE_QUEUE_FULL,e.offerRejection)
            assertFalse(e.eofAttempted);assertFalse(e.interruptAttempted);assertFalse(b.isClosed)
            b.close();val output=java.io.ByteArrayOutputStream();b.drain(output){true}
            assertEquals((0..7).joinToString(""){"old$it;"},output.toString("UTF-8"))
        }
    }
    @Test fun closedInputProofSurvivesManagerCloseAndNeverExecutesEof()=runBlocking {
        fixture{m,id,b->
            b.close()
            val binding=m.bindSourceStdin(id,owner,"a".repeat(64),"new",true,false)
            m.beginSourceStdin(binding,id,"new",true,false)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(binding,id,"new",true)}
            m.close();val e=m.inspectSourceStdin(binding)
            assertTrue(e.offerAttempted);assertTrue(e.offerKnownRejected);assertEquals(ProcessInputBuffer.OfferRejection.CLOSED,e.offerRejection)
            assertFalse(e.offerAccepted);assertFalse(e.eofAttempted)
        }
    }
    @Test fun closedEmptyOfferBeforeRequestedEofHasNoBytesOrEofAttempt()=runBlocking {
        fixture{m,id,b->
            b.close()
            val binding=m.bindSourceStdin(id,owner,"a".repeat(64),"",true,false)
            m.beginSourceStdin(binding,id,"",true,false)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(binding,id,"",true)}
            val e=m.inspectSourceStdin(binding)
            assertFalse(e.offerAttempted);assertFalse(e.offerKnownRejected);assertNull(e.offerRejection)
            assertFalse(e.offerAccepted);assertFalse(e.eofAttempted)
        }
    }
    @Test fun ptyPartialEffectFalseAndSameMessageThrowStayUnknown()=runBlocking {
        for(throws in listOf(false,true))fixture{m,id,b->
            var effects=0;b.bindPty({effects++;if(throws)throw IllegalStateException("Command stdin is closed") else false}){true}
            val binding=m.bindSourceStdin(id,owner,"a".repeat(64),"new",true,false)
            m.beginSourceStdin(binding,id,"new",true,false)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(binding,id,"new",true)}
            val e=m.inspectSourceStdin(binding)
            assertTrue(e.offerAttempted);assertFalse(e.offerKnownRejected);assertNull(e.offerRejection)
            assertFalse(e.offerAccepted);assertFalse(e.eofAttempted);assertEquals(1,effects)
        }
    }
}
