package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TerminalUiUpdateDispatcherTest {
    private class Callback(val action: () -> Unit) { var canceled = false }

    @Test fun sustainedOutputQueuesOneFrameAndDetachedTabsQueueNone() {
        val queued = mutableListOf<Callback>()
        var frames = 0
        val dispatcher = TerminalUiUpdateDispatcher({ action ->
            val callback = Callback(action).also(queued::add)
            val cancel = { callback.canceled = true }
            cancel
        }, { frames++ })
        repeat(10_000) { dispatcher.request() }
        assertTrue(queued.isEmpty())
        dispatcher.attach()
        repeat(10_000) { dispatcher.request() }
        assertEquals(1, queued.size)
        queued[0].action()
        assertEquals(1, frames)
        repeat(10_000) { dispatcher.request() }
        assertEquals(2, queued.size)
        dispatcher.detach()
        assertTrue(queued[1].canceled)
        repeat(10_000) { dispatcher.request() }
        queued[1].action()
        assertEquals(1, frames)
        assertEquals(2, queued.size)
    }

    @Test fun oldFrameCannotMutateAReattachedView() {
        val queued = mutableListOf<Callback>()
        var frames = 0
        val dispatcher = TerminalUiUpdateDispatcher({ action ->
            val callback = Callback(action).also(queued::add)
            val cancel = { callback.canceled = true }
            cancel
        }, { frames++ })
        repeat(100) {
            dispatcher.attach()
            val old = queued.last()
            dispatcher.detach()
            dispatcher.attach()
            old.action()
            assertEquals(it, frames)
            queued.last().action()
            assertEquals(it + 1, frames)
            dispatcher.detach()
        }
    }

    @Test fun renderDoesNotHoldTheDispatcherLockAgainstAnEmulatorOutputThread() {
        val requested = CountDownLatch(1)
        var frame: (() -> Unit)? = null
        lateinit var dispatcher: TerminalUiUpdateDispatcher
        dispatcher = TerminalUiUpdateDispatcher({ action -> frame = action; {} }, {
            val reader = thread { dispatcher.request(); requested.countDown() }
            assertTrue("Emulator/output thread deadlocked behind render", requested.await(2, TimeUnit.SECONDS))
            reader.join(2_000)
        })
        dispatcher.attach()
        frame!!()
        dispatcher.detach()
    }
}
