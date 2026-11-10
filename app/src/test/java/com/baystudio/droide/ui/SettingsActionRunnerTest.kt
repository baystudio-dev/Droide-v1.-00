package com.baystudio.droide.ui

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SettingsActionRunnerTest {
    private class Fixture {
        val failures = mutableListOf<Pair<String, Throwable>>()
        val results = mutableListOf<String>()
        val states = mutableListOf<Set<String>>()
        val fatal = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> fatal += e })
        val runner = SettingsActionRunner(scope, { states += it }, { label, e -> failures += label to e }, { results += it })
        val pending get() = states.lastOrNull().orEmpty()
    }
    @Test fun successfulSaveClearsPendingAndDoesNotInventSuccessMessage() = runBlocking {
        val f = Fixture(); var saves = 0
        f.runner.launch("wrap", "save failed") { saves++; null }!!.join()
        assertEquals(1, saves); assertEquals(listOf(setOf("wrap"), emptySet<String>()), f.states)
        assertTrue(f.results.isEmpty()); assertTrue(f.failures.isEmpty()); f.scope.cancel()
    }
    @Test fun sameFieldDuplicateIsIgnoredUntilCompletion() = runBlocking {
        val f = Fixture(); val hold = CompletableDeferred<Unit>(); var saves = 0
        val job = f.runner.launch("wrap", "failed") { saves++; hold.await(); null }!!
        assertNull(f.runner.launch("wrap", "failed") { saves++; null }); assertEquals(setOf("wrap"), f.pending)
        hold.complete(Unit); job.join(); assertEquals(1, saves); assertTrue(f.pending.isEmpty()); f.scope.cancel()
    }
    @Test fun independentFieldsAreNotGloballyBlocked() = runBlocking {
        val f = Fixture(); val one = CompletableDeferred<Unit>(); val two = CompletableDeferred<Unit>()
        val a = f.runner.launch("wrap", "failed") { one.await(); null }!!
        val b = f.runner.launch("tab", "failed") { two.await(); null }!!
        assertEquals(setOf("wrap", "tab"), f.pending); one.complete(Unit); a.join(); assertEquals(setOf("tab"), f.pending)
        two.complete(Unit); b.join(); assertTrue(f.pending.isEmpty()); f.scope.cancel()
    }
    @Test fun completedFieldCanBeChangedAgain() = runBlocking {
        val f = Fixture(); val saved = mutableListOf<Int>()
        f.runner.launch("tab", "failed") { saved += 2; null }!!.join()
        f.runner.launch("tab", "failed") { saved += 4; null }!!.join()
        assertEquals(listOf(2,4), saved); f.scope.cancel()
    }
    @Test fun saveFailureReachesVisibleOwnerWithOriginalCause() = runBlocking {
        val f = Fixture(); val error = IOException("disk unavailable")
        f.runner.launch("wrap", "Could not save word wrap") { throw error }!!.join()
        assertEquals("Could not save word wrap", f.failures.single().first); assertSame(error, f.failures.single().second)
        assertTrue(f.pending.isEmpty()); assertTrue(f.scope.isActive); f.scope.cancel()
    }
    @Test fun pendingIsReleasedBeforeSuspendingFeedback() = runBlocking {
        val f = Fixture(); val feedback = CompletableDeferred<Unit>(); var pending = emptySet<String>(); var reported = false
        val runner = SettingsActionRunner(f.scope, { pending = it }, { _, _ -> reported = true; feedback.await() }, {})
        val first = runner.launch("wrap", "failed") { error("disk") }!!
        assertTrue(reported); assertTrue(pending.isEmpty()); assertNotNull(runner.launch("wrap", "failed") { null })
        feedback.complete(Unit); first.join(); f.scope.cancel()
    }
    @Test fun mcpResultIsReportedExactlyOnceAfterOperation() = runBlocking {
        val f = Fixture(); val hold = CompletableDeferred<Unit>(); var calls = 0
        val job = f.runner.launch("mcp:one", "MCP retry failed") { calls++; hold.await(); "Approval required" }!!
        assertTrue(f.results.isEmpty()); hold.complete(Unit); job.join()
        assertEquals(1, calls); assertEquals(listOf("Approval required"), f.results); assertTrue(f.pending.isEmpty()); f.scope.cancel()
    }
    @Test fun mcpFailureNeverBecomesSuccessOrAutomaticRetry() = runBlocking {
        val f = Fixture(); var calls = 0
        f.runner.launch("mcp:one", "MCP retry failed") { calls++; error("not available") }!!.join()
        assertEquals(1, calls); assertEquals(1, f.failures.size); assertTrue(f.results.isEmpty()); f.scope.cancel()
    }
    @Test fun cancellationDuringOperationClearsPendingWithoutError() = runBlocking {
        val f = Fixture(); val job = f.runner.launch("wrap", "failed") { awaitCancellation() }!!
        job.cancelAndJoin(); assertTrue(f.pending.isEmpty()); assertTrue(f.failures.isEmpty()); assertTrue(f.results.isEmpty()); f.scope.cancel()
    }
    @Test fun disposedOwnerCannotStartSave() {
        val f = Fixture(); f.scope.cancel(); var called = false
        assertNull(f.runner.launch("wrap", "failed") { called = true; null }); assertFalse(called); assertTrue(f.states.isEmpty())
    }
    @Test fun cancelledOwnerCannotPublishReturnedResult() = runBlocking {
        val f = Fixture()
        val job = f.runner.launch("mcp:one", "failed") { f.scope.cancel(); "Late status" }!!
        job.join(); assertTrue(job.isCancelled); assertTrue(f.results.isEmpty()); assertTrue(f.failures.isEmpty()); assertTrue(f.pending.isEmpty())
    }
    @Test fun unavailableLinkageIsRecoverableAndFatalVmErrorIsNot() = runBlocking {
        val f = Fixture(); val missing = UnsatisfiedLinkError("unavailable")
        f.runner.launch("mcp:one", "failed") { throw missing }!!.join(); assertSame(missing, f.failures.single().second)
        val fatal = OutOfMemoryError("fatal")
        f.runner.launch("mcp:two", "failed") { throw fatal }!!.join(); assertSame(fatal, f.fatal.single())
        assertEquals(1, f.failures.size); assertTrue(f.pending.isEmpty()); f.scope.cancel()
    }
    @Test fun explicitOperationCancellationRetainsCancellationIdentity() = runBlocking {
        val f = Fixture(); val job = f.runner.launch("wrap", "failed") { throw CancellationException("owner replaced") }!!
        job.join(); assertTrue(job.isCancelled); assertTrue(f.failures.isEmpty()); assertTrue(f.results.isEmpty()); assertTrue(f.pending.isEmpty()); f.scope.cancel()
    }

}
