package com.baystudio.droide.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ApplicationWorkAdmissionStateR64Test {
    private class Store {
        var saved: ApplicationWorkAdmissionState.Snapshot? = null
        var failRead = false
        var failWrite = false
        var writes = 0
        var generation = 0
        fun owner() = ApplicationWorkAdmissionState(
            read = { if (failRead) throw IOException("read"); saved },
            write = { if (failWrite) throw IOException("write"); writes++; saved = it },
            newEpoch = { (++generation).toString(16).padStart(32, '0') },
        ).also { it.initialize() }
    }
    @Test fun initialOwnerRequiresExplicitUserLaunch() {
        val store = Store(); val owner = store.owner()
        assertFalse(owner.isOpen)
        assertThrows(IllegalStateException::class.java) { owner.requireOpen() }
        val epoch = owner.reopenFromUser()
        assertTrue(owner.isCurrentOpen(epoch)); assertEquals(epoch, owner.requireOpen())
    }
    @Test fun closeIsDurableAndIdempotentWithoutChangingEpoch() {
        val store = Store(); val owner = store.owner(); val epoch = owner.reopenFromUser()
        assertEquals(epoch, owner.closeForExit()); val writes = store.writes
        assertEquals(epoch, owner.closeForExit()); assertEquals(writes, store.writes)
        val replacement = store.owner()
        assertFalse(replacement.isOpen); assertEquals(epoch, replacement.currentEpoch)
    }
    @Test fun processRecreationPreservesOpenEpochWithoutOpeningNewWork() {
        val store = Store(); val epoch = store.owner().reopenFromUser(); val writes = store.writes
        val replacement = store.owner()
        assertTrue(replacement.isCurrentOpen(epoch)); assertEquals(writes, store.writes)
    }
    @Test fun explicitReopenRejectsOldNotificationEpoch() {
        val store = Store(); val owner = store.owner(); val old = owner.reopenFromUser()
        owner.closeForExit(); val next = owner.reopenFromUser()
        assertNotEquals(old, next); assertFalse(owner.isCurrentOpen(old)); assertTrue(owner.isCurrentOpen(next))
    }
    @Test fun failedCloseStaysClosedAndCanRetryPersistence() {
        val store = Store(); val owner = store.owner(); val epoch = owner.reopenFromUser(); store.failWrite = true
        assertThrows(IOException::class.java) { owner.closeForExit() }
        assertFalse(owner.isOpen); assertFalse(owner.isCurrentOpen(epoch))
        store.failWrite = false; assertEquals(epoch, owner.closeForExit())
        assertFalse(store.owner().isOpen)
    }
    @Test fun failedReopenNeverRestoresOldOpenValue() {
        val store = Store(); val owner = store.owner(); owner.reopenFromUser(); owner.closeForExit(); store.failWrite = true
        assertThrows(IOException::class.java) { owner.reopenFromUser() }
        assertFalse(owner.isOpen)
    }
    @Test fun corruptOrUnreadablePersistenceFailsClosed() {
        val owner = ApplicationWorkAdmissionState(read = { ApplicationWorkAdmissionState.Snapshot("bad", true) }, write = {})
        assertThrows(IllegalStateException::class.java) { owner.initialize() }; assertFalse(owner.isOpen)
        val unreadable = ApplicationWorkAdmissionState(read = { throw IOException("read") }, write = {})
        assertThrows(IllegalStateException::class.java) { unreadable.initialize() }; assertFalse(unreadable.isOpen)
    }
    @Test fun closeDoesNotReturnBeforeDurableWriteFinishes() {
        val entered = CountDownLatch(1); val allowWrite = CountDownLatch(1); val returned = CountDownLatch(1)
        var epoch = 0; var writingClose = false
        val owner = ApplicationWorkAdmissionState(read = { null }, write = {
            if (writingClose) { entered.countDown(); check(allowWrite.await(5, TimeUnit.SECONDS)) }
        }, newEpoch = { (++epoch).toString(16).padStart(32, '0') })
        owner.initialize(); owner.reopenFromUser(); writingClose = true
        val thread = Thread { try { owner.closeForExit() } finally { returned.countDown() } }.apply { start() }
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(1, returned.count) }
        finally { allowWrite.countDown(); thread.join(5000) }
        assertEquals(0, returned.count); assertFalse(owner.isOpen)
    }
}
