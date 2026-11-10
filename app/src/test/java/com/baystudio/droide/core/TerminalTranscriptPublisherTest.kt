package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class TerminalTranscriptPublisherTest {
    private class Fixture {
        data class Job(val delay: Long, val action: () -> Unit, var canceled: Boolean = false)
        val jobs = mutableListOf<Job>()
        val publications = mutableListOf<String>()
        var text = ""
        var captures = 0
        val publisher = TerminalTranscriptPublisher(
            schedule = { delay, action ->
                val job = Job(delay, action)
                jobs += job
                val cancellation = { job.canceled = true }
                cancellation
            },
            capture = { captures++; text },
            publish = publications::add,
        )
    }

    @Test fun burstSchedulesOneCopyAndCapturesLatestCompleteText() {
        val f = Fixture()
        repeat(1_000) { index -> f.text += "$index\n"; f.publisher.request() }
        assertEquals(1, f.jobs.size)
        assertEquals(100L, f.jobs.single().delay)
        assertEquals(0, f.captures)
        f.jobs.single().action()
        assertEquals(listOf(f.text), f.publications)
        assertEquals(1, f.captures)
        f.text += "next\n"
        f.publisher.request()
        assertEquals(2, f.jobs.size)
        f.jobs.last().action()
        assertEquals(f.text, f.publications.last())
    }

    @Test fun continuousOutputDoesNotPostponeThePendingCapture() {
        val f = Fixture()
        repeat(3) {
            repeat(25) { f.text += "line\n"; f.publisher.request() }
            assertEquals(it + 1, f.jobs.size)
            assertFalse(f.jobs.last().canceled)
            f.jobs.last().action()
        }
        assertEquals(3, f.captures)
        assertEquals(f.text, f.publications.last())
    }

    @Test fun explicitFlushCancelsQueuedGenerationAndPublishesFreshText() {
        val f = Fixture()
        f.text = "old"
        f.publisher.request()
        val stale = f.jobs.single()
        f.text = "fresh demand"
        f.publisher.flush()
        assertTrue(stale.canceled)
        assertEquals(listOf("fresh demand"), f.publications)
        f.text = "later"
        f.publisher.request()
        stale.action()
        assertEquals(1, f.captures)
        f.jobs.last().action()
        assertEquals("later", f.publications.last())
    }

    @Test fun clearRejectsOldCallbacksAndAllowsOnlyNewGeneration() {
        val f = Fixture()
        f.text = "must disappear"
        f.publisher.request()
        val stale = f.jobs.single()
        f.publisher.clear()
        assertEquals(listOf(""), f.publications)
        f.text = "after reset"
        f.publisher.request()
        stale.action()
        assertEquals(listOf(""), f.publications)
        f.jobs.last().action()
        assertEquals(listOf("", "after reset"), f.publications)
    }

    @Test fun finishFlushesTrailingOutputAndRejectsEveryLateTimerOrRequest() {
        val f = Fixture()
        f.text = "partial"
        f.publisher.request()
        f.text = "complete final output"
        f.publisher.finish()
        assertTrue(f.jobs.single().canceled)
        assertEquals(listOf("complete final output"), f.publications)
        f.text = "must not replace exit suffix"
        f.jobs.single().action()
        f.publisher.request()
        f.publisher.flush()
        f.publisher.finish()
        assertEquals(1, f.jobs.size)
        assertEquals(1, f.captures)
    }

    @Test fun closeCapturesNowAndFinishRetainsBytesEmittedDuringShutdownOnce() {
        val f = Fixture()
        f.text = "before close"
        f.publisher.request()
        f.publisher.close()
        assertTrue(f.jobs.single().canceled)
        f.text = "final shutdown bytes"
        f.publisher.finish()
        f.jobs.single().action()
        f.publisher.close()
        f.publisher.finish()
        f.publisher.request()
        assertEquals(listOf("before close", "final shutdown bytes"), f.publications)
        assertEquals(2, f.captures)
        assertEquals(1, f.jobs.size)
    }

    @Test fun finishBeforePostedCloseDoesNotLoseFinalOutput() {
        val f = Fixture()
        f.text = "final"
        f.publisher.request()
        f.publisher.cancelPending()
        f.publisher.finish()
        f.text = "stale after finish"
        f.publisher.close()
        f.jobs.single().action()
        assertEquals(listOf("final"), f.publications)
    }

    @Test fun explicitClearAfterFinishKeepsTheClosedTerminalEmpty() {
        val f = Fixture()
        f.text = "finished output"
        f.publisher.request()
        f.publisher.finish()
        f.publisher.clear()
        f.jobs.single().action()
        f.publisher.request()
        f.publisher.close()
        assertEquals(listOf("finished output", ""), f.publications)
    }
}
