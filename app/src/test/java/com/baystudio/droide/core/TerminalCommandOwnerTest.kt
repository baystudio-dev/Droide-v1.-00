package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TerminalCommandOwnerTest {
    private val token="0123456789abcdef0123456789abcdef"
    private fun title(event:String)=TerminalCommandActivityTracker.TITLE_PREFIX+token+":"+event
    @Test fun staleOrInactiveCommandNeverRunsStopAction() {
        val tracker=TerminalCommandActivityTracker(token);var stops=0
        assertFalse(tracker.withActiveGeneration(0){stops++;true})
        tracker.submitted();val generation=tracker.state.value.generation
        assertFalse(tracker.withActiveGeneration(generation-1){stops++;true})
        assertTrue(tracker.withActiveGeneration(generation){stops++;true})
        tracker.closed();assertFalse(tracker.withActiveGeneration(generation){stops++;true})
        assertEquals(1,stops)
    }
    @Test fun promptCannotChangeGenerationBetweenFinalCheckAndDetach() {
        val tracker=TerminalCommandActivityTracker(token);tracker.consumeTitle(title("ready:0"));tracker.submitted()
        val old=tracker.state.value.generation;val entered=CountDownLatch(1);val release=CountDownLatch(1);val transition=CountDownLatch(1)
        val stop=thread { tracker.withActiveGeneration(old){entered.countDown();check(release.await(5,TimeUnit.SECONDS));true} }
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val output=thread { tracker.consumeTitle(title("ready:0"));tracker.consumeTitle(title("running"));transition.countDown() }
        try { assertFalse(transition.await(50,TimeUnit.MILLISECONDS));assertEquals(old,tracker.state.value.generation) }
        finally { release.countDown();stop.join(5000);output.join(5000) }
        assertFalse(stop.isAlive);assertFalse(output.isAlive);assertEquals(old+1,tracker.state.value.generation)
        assertFalse(tracker.withActiveGeneration(old){error("must not stop replacement")})
    }
}
