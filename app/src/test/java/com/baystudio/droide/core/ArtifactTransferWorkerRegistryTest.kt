package com.baystudio.droide.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactTransferWorkerRegistryTest {
    @Test fun aCanceledWorkerCanRestartBeforeItsOldFinallyCompletes() = runBlocking {
        val registry = ArtifactTransferWorkerRegistry<String>()
        val started = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val oldRemovedNew = CompletableDeferred<Boolean>()
        val old = checkNotNull(registry.start("artifact", "request") { identity ->
            launch(start = CoroutineStart.LAZY) {
                try { started.complete(Unit); awaitCancellation() }
                finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        finishCleanup.await()
                        oldRemovedNew.complete(registry.finish("artifact", identity))
                    }
                }
            }
        })
        old.job.start()
        withTimeout(2_000) { started.await() }
        registry.cancel("artifact", "request")
        withTimeout(2_000) { cleanupEntered.await() }
        val fresh = checkNotNull(registry.start("artifact", "request") { Job() })
        assertNotEquals(old.identity, fresh.identity)
        finishCleanup.complete(Unit)
        withTimeout(2_000) { old.job.join() }
        assertFalse(oldRemovedNew.await())
        assertEquals(1, registry.size())
        assertSame(fresh, registry.cancel("artifact", "request"))
        assertTrue(fresh.job.isCancelled)
        assertEquals(0, registry.size())
    }

    @Test fun aDuplicateStartKeepsTheExistingLazyWorker() {
        val registry = ArtifactTransferWorkerRegistry<String>()
        var created = 0
        val first = checkNotNull(registry.start("artifact", "request") { created++; Job() })
        assertNull(registry.start("artifact", "request") { created++; Job() })
        assertEquals(1, created)
        assertEquals(1, registry.size())
        assertTrue(registry.finish("artifact", first.identity))
        first.job.cancel()
    }

    @Test fun aNewRequestReplacesTheOldWorkerAndRejectsItsStaleStop() {
        val registry = ArtifactTransferWorkerRegistry<String>()
        val old = checkNotNull(registry.start("artifact", "old request") { Job() })
        val fresh = checkNotNull(registry.start("artifact", "new request") { Job() })
        assertTrue(old.job.isCancelled)
        assertNull(registry.cancel("artifact", "old request"))
        assertFalse(registry.finish("artifact", old.identity))
        assertFalse(fresh.job.isCancelled)
        assertSame(fresh, registry.cancel("artifact", "new request"))
    }

    @Test fun aCanceledEntryRestartsEvenBeforeItWasRemoved() {
        val registry = ArtifactTransferWorkerRegistry<String>()
        val old = checkNotNull(registry.start("artifact", "request") { Job() })
        old.job.cancel()
        val fresh = checkNotNull(registry.start("artifact", "request") { Job() })
        assertFalse(registry.finish("artifact", old.identity))
        assertEquals(1, registry.size())
        assertTrue(registry.finish("artifact", fresh.identity))
        fresh.job.cancel()
    }

    @Test fun aCompletedEntryCanRestartAndCannotRemoveTheReplacement() {
        val registry = ArtifactTransferWorkerRegistry<String>()
        val completed = Job().apply { complete() }
        val old = checkNotNull(registry.start("artifact", "request") { completed })
        val fresh = checkNotNull(registry.start("artifact", "request") { Job() })
        assertFalse(registry.finish("artifact", old.identity))
        assertEquals(listOf("artifact"), registry.keys())
        registry.clear()
        assertTrue(fresh.job.isCancelled)
    }

    @Test fun creatingAReplacementFailurePreservesTheLiveWorker() {
        val registry = ArtifactTransferWorkerRegistry<String>()
        val old = checkNotNull(registry.start("artifact", "old request") { Job() })
        try {
            registry.start("artifact", "new request") { error("Could not create worker") }
            error("Expected creation failure")
        } catch (failure: IllegalStateException) {
            assertEquals("Could not create worker", failure.message)
        }
        assertFalse(old.job.isCancelled)
        assertSame(old, registry.cancel("artifact", "old request"))
    }

    @Test fun destructionCancelsEveryWorkerAndLateFinallyCannotAffectTheNextGeneration() {
        val registry = ArtifactTransferWorkerRegistry<String>()
        val first = checkNotNull(registry.start("first", "request") { Job() })
        val second = checkNotNull(registry.start("second", "request") { Job() })
        registry.clear()
        assertTrue(first.job.isCancelled)
        assertTrue(second.job.isCancelled)
        assertEquals(0, registry.size())
        val next = checkNotNull(registry.start("first", "request") { Job() })
        assertFalse(registry.finish("first", first.identity))
        assertSame(next, registry.cancel("first", "request"))
    }

    @Test fun anOldWorkerCannotReclaimDurableOwnershipAfterAReplacement() {
        val registry = ArtifactTransferWorkerRegistry<String>()
        var durableOwner: String? = null
        val old = checkNotNull(registry.start("artifact", "request") { Job() })
        registry.withCurrent("artifact", old.identity) { durableOwner = old.identity }
        val fresh = checkNotNull(registry.start("artifact", "new request") { Job() })
        registry.withCurrent("artifact", fresh.identity) { durableOwner = fresh.identity }
        assertNull(registry.withCurrent("artifact", old.identity) { durableOwner = old.identity })
        assertEquals(fresh.identity, durableOwner)
        registry.clear()
    }

    @Test fun synchronousCancellationCallbacksAlreadySeeTheReplacementGeneration() {
        val registry = ArtifactTransferWorkerRegistry<String>()
        val old = checkNotNull(registry.start("artifact", "old request") { Job() })
        var oldWasCurrentDuringCancellation = true
        var oldRemovedReplacement = true
        old.job.invokeOnCompletion {
            oldWasCurrentDuringCancellation = registry.withCurrent("artifact", old.identity) { true } == true
            oldRemovedReplacement = registry.finish("artifact", old.identity)
        }
        val fresh = checkNotNull(registry.start("artifact", "new request") { Job() })
        assertFalse(oldWasCurrentDuringCancellation)
        assertFalse(oldRemovedReplacement)
        assertEquals(1, registry.size())
        assertSame(fresh, registry.cancel("artifact", "new request"))
    }
}
