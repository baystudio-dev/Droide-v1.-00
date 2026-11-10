package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test

class SourceCommandDispositionTest {
    @Test fun everyStateExitAndOutputCompletenessCombinationIsExplicit() {
        for(state in BackgroundCommandManager.State.values())for(exit in listOf(null,-1,0,1,124))for(incomplete in listOf(false,true)){
            val snapshot=BackgroundCommandManager.Snapshot("job",state,"command","",exit,null,0,1,outputIncomplete=incomplete)
            val expected=state in setOf(BackgroundCommandManager.State.EXITED,BackgroundCommandManager.State.FAILED,
                BackgroundCommandManager.State.TIMED_OUT) && exit!=null && exit>=0
            assertEquals("$state / $exit / outputIncomplete=$incomplete",expected,SourceCommandDisposition.knownTerminal(snapshot))
        }
    }
}
