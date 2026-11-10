package com.baystudio.droide.ui

import com.baystudio.droide.core.RetainedWorkCandidate
import com.baystudio.droide.core.RetainedWorkInventory
import com.baystudio.droide.core.RetainedWorkKind
import com.baystudio.droide.core.RetainedWorkNotice
import com.baystudio.droide.core.RetainedWorkSnapshot
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RetainedWorkControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var available = true
    private val candidate = RetainedWorkCandidate("src/.droide-tmp-11111111-1111-4111-8111-111111111111",
        RetainedWorkKind.PRESENT, 12L, "Current observation only")
    private val original = RetainedWorkSnapshot("notice-a", listOf(RetainedWorkNotice("src/file", candidate, true)), 2L)
    private var current = original
    private val acknowledged = mutableListOf<String>()
    private val inspected = mutableListOf<String>()
    private var inspect: suspend (String) -> RetainedWorkInventory = { directory ->
        RetainedWorkInventory(directory, listOf(candidate), false, "Inspection budget reached")
    }
    private val controller = RetainedWorkController(scope, { available }, { current }, { token ->
        acknowledged += token
        if (current.token == token) current = RetainedWorkSnapshot("empty", emptyList(), 0L)
    }, { directory -> inspected += directory; inspect(directory) })

    @After fun cleanup() { controller.dispose(); scope.cancel() }

    @Test fun openingNeverScansAndExplicitInspectionKeepsPartialResults() {
        assertTrue(inspected.isEmpty())
        controller.open()
        assertTrue(controller.state.open)
        assertEquals("", controller.state.directory)
        assertEquals(original.notices, controller.state.notices)
        assertTrue(inspected.isEmpty())
        controller.directory("src")
        controller.inspect()
        assertEquals(listOf("src"), inspected)
        assertFalse(controller.state.busy)
        val inventory = requireNotNull(controller.state.inventory)
        assertFalse(inventory.complete)
        assertEquals("Inspection budget reached", inventory.problem)
        assertEquals(listOf(candidate), inventory.candidates)
        assertTrue(acknowledged.isEmpty())
    }

    @Test fun closingDropsLateInspectionEvenWhenReadIgnoresCancellation() = runBlocking {
        val release = CompletableDeferred<Unit>()
        inspect = { directory -> withContext(NonCancellable) { release.await() }; RetainedWorkInventory(directory, listOf(candidate), true, null) }
        controller.open(); controller.inspect()
        assertTrue(controller.state.busy)
        controller.close()
        release.complete(Unit)
        assertFalse(controller.state.open)
        assertNull(controller.state.inventory)
        assertNull(controller.state.error)
        assertTrue(acknowledged.isEmpty())
    }

    @Test fun changingFolderDiscardsTheOldResultAndFailure() = runBlocking {
        val release = CompletableDeferred<Unit>()
        inspect = { withContext(NonCancellable) { release.await() }; throw IOException("Old folder vanished") }
        controller.open(); controller.directory("old"); controller.inspect()
        controller.directory("new")
        release.complete(Unit)
        assertEquals("new", controller.state.directory)
        assertFalse(controller.state.busy)
        assertNull(controller.state.inventory)
        assertNull(controller.state.error)
    }

    @Test fun disposedOrUnavailableOwnerCannotInspectOrOpenFromAnOldNotice() = runBlocking {
        val result = controller.presentNotice(original) {
            available = false
            controller.dispose()
            true
        }
        assertFalse(result)
        controller.open(); controller.inspect()
        assertFalse(controller.state.open)
        assertTrue(inspected.isEmpty())
        assertTrue(acknowledged.isEmpty())
        assertSame(original, current)
    }

    @Test fun staleOrCancelledNotificationCannotDismissANewerSnapshot() = runBlocking {
        val newer = original.copy(token = "notice-b")
        assertFalse(controller.presentNotice(original) { current = newer; true })
        assertSame(newer, current)
        assertTrue(acknowledged.isEmpty())
        current = original
        val cancelled = CancellationException("UI detached")
        val failure = runCatching { controller.presentNotice(original) { throw cancelled } }.exceptionOrNull()
        assertSame(cancelled, failure)
        assertSame(original, current)
        assertTrue(acknowledged.isEmpty())
    }

    @Test fun dismissingOnlyAcknowledgesTheExactMemoryNotificationOnce() = runBlocking {
        assertFalse(controller.presentNotice(original) { false })
        assertEquals(listOf("notice-a"), acknowledged)
        assertTrue(current.notices.isEmpty())
        assertFalse(controller.state.open)
        assertTrue(inspected.isEmpty())
        assertFalse(controller.presentNotice(original) { fail("Dismissed notice was shown again"); true })
        assertEquals(listOf("notice-a"), acknowledged)
        assertEquals(candidate.path, original.notices.single().candidate.path)
    }

    @Test fun reviewingRetainsTheExactRowsAfterAcknowledgingNotificationMemory() = runBlocking {
        assertTrue(controller.presentNotice(original) { true })
        assertTrue(controller.state.open)
        assertEquals(original.notices, controller.state.notices)
        assertEquals(2L, controller.state.omitted)
        assertTrue(current.notices.isEmpty())
        controller.open()
        assertEquals(original.notices, controller.state.notices)
        assertTrue(inspected.isEmpty())
        assertEquals(listOf("notice-a"), acknowledged)
    }

    @Test fun reviewIsPresentedBeforeItsAcknowledgementCancelsTheCollectLatestHandler() = runBlocking {
        val notifications = MutableStateFlow(original)
        val displayed = CompletableDeferred<Unit>()
        val clicked = CompletableDeferred<Unit>()
        val emptied = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Boolean>()
        var reviews = 0
        val events = mutableListOf<String>()
        val collecting = RetainedWorkController(scope, { available }, { notifications.value }, { token ->
            assertEquals(1, reviews)
            assertEquals("notice-a", token)
            events += "acknowledge"
            notifications.value = RetainedWorkSnapshot("empty", emptyList(), 0L)
        }, { error("Review must not start an inventory") })
        val job = scope.launch {
            notifications.collectLatest { notification ->
                if (notification.notices.isEmpty()) emptied.complete(Unit)
                else try {
                    collecting.presentNotice(notification, onReview = { reviews++; events += "review" }) {
                        displayed.complete(Unit)
                        clicked.await()
                        true
                    }
                    awaitCancellation()
                } finally { stopped.complete(!currentCoroutineContext().isActive) }
            }
        }
        try {
            withTimeout(5_000) { displayed.await() }
            clicked.complete(Unit)
            withTimeout(5_000) { emptied.await(); assertTrue(stopped.await()) }
            assertEquals(listOf("review", "acknowledge"), events)
            assertEquals(1, reviews)
            assertTrue(collecting.state.open)
            assertEquals(original.notices, collecting.state.notices)
            assertTrue(notifications.value.notices.isEmpty())
        } finally { job.cancelAndJoin(); collecting.dispose() }
    }
}
