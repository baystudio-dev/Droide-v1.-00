package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SourceStdinBindingTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private val owner=OwnedResponseContentStore.Owner("session","run","call")
    private val hash="a".repeat(64)
    private suspend fun fixture(sink:(ByteArray)->Boolean={true},eof:()->Boolean={true},
        body:suspend(BackgroundCommandManager,String,ProcessInputBuffer)->Unit) {
        val ready=CompletableDeferred<ProcessInputBuffer>()
        val terminal=object:ITerminalSession by AgentWorkflowTerminal(),InteractivePtyCommandSession {
            override val supportsInteractivePty=true
            override suspend fun execInteractivePty(command:String,timeoutMs:Long,input:ProcessInputBuffer?,onOutput:(String)->Unit):ExecResult {
                val buffer=requireNotNull(input);buffer.bindPty(sink,eof);ready.complete(buffer)
                awaitCancellation()
            }
        }
        val manager=BackgroundCommandManager(terminal)
        try {
            val id=manager.start("fixture",keepStdinOpen=true,requiresTty=true).id
            val buffer=withTimeout(5000){ready.await()}
            body(manager,id,buffer)
        } finally {manager.closeAndJoin()}
    }
    @Test fun oneOfferAcceptedDoesNotClaimDeliveryAndDuplicateRejects()=runBlocking {
        val offers=AtomicInteger()
        fixture(sink={offers.incrementAndGet();true}){m,id,_->
            val b=m.bindSourceStdin(id,owner,hash,"data",false,false)
            m.beginSourceStdin(b,id,"data",false,false);m.sendSourceInput(b,id,"data",false)
            assertTrue(m.inspectSourceStdin(b).offerAttempted);assertTrue(m.inspectSourceStdin(b).offerAccepted);assertFalse(m.inspectSourceStdin(b).eofAttempted)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(b,id,"data",false)}
            assertThrows(IllegalStateException::class.java){m.beginSourceStdin(b,id,"data",false,false)}
            assertEquals(1,offers.get())
        }
    }
    @Test fun explicitEofWithoutCharsDoesNotClaimOfferAcceptance()=runBlocking {
        val eofs=AtomicInteger()
        fixture(eof={eofs.incrementAndGet();true}){m,id,_->
            val b=m.bindSourceStdin(id,owner,hash,"",true,false)
            m.beginSourceStdin(b,id,"",true,false);m.sendSourceInput(b,id,"",true)
            assertFalse(m.inspectSourceStdin(b).offerAttempted);assertFalse(m.inspectSourceStdin(b).offerAccepted)
            assertTrue(m.inspectSourceStdin(b).eofAttempted);assertTrue(m.inspectSourceStdin(b).eofAccepted);assertEquals(1,eofs.get())
        }
    }
    @Test fun laterSourceCallCanContinueTheSamePtyAfterEof()=runBlocking {
        val writes=mutableListOf<String>();val eofs=AtomicInteger()
        fixture(sink={writes+=String(it);true},eof={eofs.incrementAndGet();true}){m,id,buffer->
            val first=m.bindSourceStdin(id,owner,hash,"first",true,false)
            m.beginSourceStdin(first,id,"first",true,false);m.sendSourceInput(first,id,"first",true)
            assertTrue(m.inspectSourceStdin(first).eofAccepted)
            assertFalse(buffer.isClosed);assertTrue(requireNotNull(m.snapshot(id)).stdinAvailable)
            val next=m.bindSourceStdin(id,owner.copy(callId="next"),"b".repeat(64),"next",false,false)
            m.beginSourceStdin(next,id,"next",false,false);m.sendSourceInput(next,id,"next",false)
            assertEquals(listOf("first","next"),writes);assertEquals(1,eofs.get())
            assertTrue(m.inspectSourceStdin(next).offerAccepted)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(first,id,"first",true)}
            assertEquals(1,eofs.get())
        }
    }
    @Test fun closeFailureRetainsPriorOfferAcceptanceAndEofUncertainty()=runBlocking {
        fixture(eof={false}){m,id,_->
            val b=m.bindSourceStdin(id,owner,hash,"data",true,false)
            m.beginSourceStdin(b,id,"data",true,false)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(b,id,"data",true)}
            assertTrue(m.inspectSourceStdin(b).offerAccepted);assertTrue(m.inspectSourceStdin(b).eofAttempted);assertFalse(m.inspectSourceStdin(b).eofAccepted)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(b,id,"data",true)}
        }
    }
    @Test fun rejectedQueueAdmissionIsNotClaimedAcceptedAndCannotBeReplayed()=runBlocking {
        val attempts=AtomicInteger()
        fixture(sink={attempts.incrementAndGet();false}){m,id,_->
            val b=m.bindSourceStdin(id,owner,hash,"data",false,false)
            m.beginSourceStdin(b,id,"data",false,false)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(b,id,"data",false)}
            assertTrue(m.inspectSourceStdin(b).offerAttempted);assertFalse(m.inspectSourceStdin(b).offerAccepted)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(b,id,"data",false)}
            assertEquals(1,attempts.get())
        }
    }
    @Test fun sinkPartialEffectThenThrowRetainsAttemptNeverRetries()=runBlocking {
        val attempts=AtomicInteger()
        fixture(sink={attempts.incrementAndGet();throw IllegalStateException("partial native failure")}){m,id,_->
            val b=m.bindSourceStdin(id,owner,hash,"data",false,false)
            m.beginSourceStdin(b,id,"data",false,false)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(b,id,"data",false)}
            assertTrue(m.inspectSourceStdin(b).offerAttempted);assertFalse(m.inspectSourceStdin(b).offerAccepted)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(b,id,"data",false)}
            assertEquals(1,attempts.get())
        }
    }
    @Test fun fabricatedForeignRestartAndChangedPayloadReject()=runBlocking {
        fixture{m,id,_->
            val b=m.bindSourceStdin(id,owner,hash,"data",false,false)
            val forged=BackgroundCommandManager.SourceStdinBinding(id,owner,hash)
            assertThrows(IllegalStateException::class.java){m.beginSourceStdin(forged,id,"data",false,false)}
            assertThrows(IllegalStateException::class.java){m.beginSourceStdin(b,id,"changed",false,false)}
            val foreign=BackgroundCommandManager(AgentWorkflowTerminal())
            try { assertThrows(IllegalStateException::class.java){foreign.beginSourceStdin(b,id,"data",false,false)} }
            finally {foreign.closeAndJoin()}
            m.beginSourceStdin(b,id,"data",false,false)
            m.close()
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(b,id,"data",false)}
            assertFalse(m.inspectSourceStdin(b).offerAttempted)
        }
    }
    @Test fun historicalEvidenceIsImmutableAndSurvivesRevocationAndClose()=runBlocking {
        fixture(eof={false}){m,id,_->
            var revoked=false
            val b=m.bindSourceStdin(id,owner,hash,"data",true,false){check(!revoked)}
            val before=m.inspectSourceStdin(b)
            m.beginSourceStdin(b,id,"data",true,false)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(b,id,"data",true)}
            revoked=true;m.close()
            val after=m.inspectSourceStdin(b)
            assertFalse(before.observationStarted);assertFalse(before.offerAccepted)
            assertEquals(id,after.sessionId);assertEquals(owner,after.sourceOwner);assertEquals(hash,after.argumentsSha256)
            assertTrue(after.observationStarted);assertTrue(after.offerAccepted)
            assertTrue(after.eofAttempted);assertFalse(after.eofAccepted)
            val forged=BackgroundCommandManager.SourceStdinBinding(id,owner,hash)
            assertThrows(IllegalStateException::class.java){m.inspectSourceStdin(forged)}
            val restarted=BackgroundCommandManager(AgentWorkflowTerminal())
            try{assertThrows(IllegalStateException::class.java){restarted.inspectSourceStdin(b)}}finally{restarted.closeAndJoin()}
        }
    }
    @Test fun completedEntryStillAllowsReadOnlyObservation()=runBlocking {
        fixture{m,id,_->
            m.kill(id)
            val b=m.bindSourceStdin(id,owner,hash,"",false,false)
            val snapshot=m.beginSourceStdin(b,id,"",false,false)
            assertEquals(BackgroundCommandManager.State.KILLED,snapshot.state)
            assertFalse(m.inspectSourceStdin(b).offerAttempted);assertFalse(m.inspectSourceStdin(b).eofAttempted);assertFalse(m.inspectSourceStdin(b).interruptAttempted)
        }
    }
    @Test fun cancellationAfterApprovalBeforeOfferMakesNoAttempt()=runBlocking {
        fixture{m,id,_->
            var cancelled=false
            val b=m.bindSourceStdin(id,owner,hash,"data",false,false){if(cancelled)throw CancellationException("stopped")}
            m.beginSourceStdin(b,id,"data",false,false);cancelled=true
            assertThrows(CancellationException::class.java){m.sendSourceInput(b,id,"data",false)}
            assertFalse(m.inspectSourceStdin(b).offerAttempted)
        }
    }
    @Test fun staleFinishedEntryRejectsAndExplicitInterruptIsOneShot()=runBlocking {
        fixture{m,id,_->
            val write=m.bindSourceStdin(id,owner,hash,"data",false,false)
            m.beginSourceStdin(write,id,"data",false,false)
            val stop=m.bindSourceStdin(id,owner,hash,"",false,true)
            m.beginSourceStdin(stop,id,"",false,true)
            m.interruptSource(stop,id)
            assertTrue(m.inspectSourceStdin(stop).interruptAttempted);assertTrue(m.inspectSourceStdin(stop).interruptAccepted);assertFalse(m.inspectSourceStdin(stop).offerAttempted)
            assertTrue(runCatching{m.interruptSource(stop,id)}.isFailure)
            assertThrows(IllegalStateException::class.java){m.sendSourceInput(write,id,"data",false)}
            assertFalse(m.inspectSourceStdin(write).offerAttempted)
        }
    }
}
