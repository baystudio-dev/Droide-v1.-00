package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class ApplicationExitDeadlineTest {
    private class Clock {
        data class Work(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        val work = mutableListOf<Work>()
        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            val item = Work(now + delay, action); work += item
            return { item.cancelled = true }
        }
        fun advance(to: Long) {
            while (true) {
                val next = work.filter { !it.cancelled && it.at <= to }.minByOrNull { it.at } ?: break
                next.cancelled = true; now = next.at
                runCatching(next.action)
            }
            now = to
        }
    }

    @Test fun retryCannotExtendTheFifteenSecondDeadline() {
        val clock = Clock(); var prepared = 0; var killed = 0
        val deadline = ApplicationExitDeadline({ prepared++ }, { killed++ }, clock::schedule)
        deadline.start(); clock.advance(10_000); deadline.start()
        clock.advance(14_499); assertEquals(0, prepared); assertEquals(0, killed)
        clock.advance(14_999); assertEquals(1, prepared); assertEquals(0, killed)
        clock.advance(15_000); assertEquals(1, killed)
        deadline.complete(); clock.advance(30_000); assertEquals(1, killed)
    }

    @Test fun successfulCleanupExitsImmediatelyWithoutWaitingForTheDeadline() {
        val clock = Clock(); var prepared = 0; var killed = 0
        val deadline = ApplicationExitDeadline({ prepared++ }, { killed++ }, clock::schedule)
        deadline.start(); clock.advance(20); deadline.complete()
        assertEquals(1, killed); clock.advance(15_000)
        assertEquals(0, prepared); assertEquals(1, killed)
    }

    @Test fun failedAndroidCleanupCannotPreventProcessTermination() {
        val clock = Clock(); var killed = 0
        val deadline = ApplicationExitDeadline({ error("Android IPC unavailable") }, { killed++ }, clock::schedule)
        deadline.start(); clock.advance(15_000); assertEquals(1, killed)
    }

    @Test fun cancelledDeadlineCannotKillAReopenedSession() {
        val clock = Clock(); var killed = 0; var prepared = 0
        val deadline = ApplicationExitDeadline({ prepared++ }, { killed++ }, clock::schedule)
        deadline.start(); deadline.cancel(); clock.advance(15_000); deadline.complete()
        assertEquals(0, prepared); assertEquals(0, killed)
    }
}
