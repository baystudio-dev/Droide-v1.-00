package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BuildRunDebugOperationGateTest {
    private enum class Kind { DEBUG, BUILD }

    @Test(timeout = 10_000) fun stopCancelsDebugPrerequisiteAndNeverLaunchesAnOldExecutable() = runBlocking {
        val gate = BuildRunDebugOperationGate<Kind>()
        val entered = CompletableDeferred<Unit>()
        var prerequisiteCleaned = false
        var launched = false
        var stopped = false
        lateinit var operationJob: Job
        val startup = async {
            gate.run(Kind.DEBUG) {
                operationJob = currentCoroutineContext()[Job]!!
                ProjectDebugTaskGate.start("build", {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { prerequisiteCleaned = true }
                }, { launched = true })
            }
        }
        entered.await()
        gate.stopDebug(Kind.DEBUG) {
            assertTrue(prerequisiteCleaned)
            assertTrue(operationJob.isCompleted)
            stopped = true
        }
        startup.join()
        assertTrue(startup.isCancelled)
        assertTrue(stopped)
        assertFalse(launched)
        assertEquals("next build", gate.run(Kind.BUILD) { "next build" })
    }

    @Test(timeout = 10_000) fun stopJoinsAdapterStartupCleanupBeforeStoppingTheSession() = runBlocking {
        val gate = BuildRunDebugOperationGate<Kind>()
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        var adapterStopped = false
        val startup = async {
            gate.run(Kind.DEBUG) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        cleanupRelease.await()
                    }
                }
            }
        }
        entered.await()
        val stop = async { gate.stopDebug(Kind.DEBUG) { adapterStopped = true } }
        cleanupEntered.await()
        assertFalse(adapterStopped)
        assertFalse(stop.isCompleted)
        assertTrue(runCatching { gate.run(Kind.DEBUG) { error("Must not launch") } }.exceptionOrNull() is IllegalStateException)
        cleanupRelease.complete(Unit)
        stop.await()
        assertTrue(adapterStopped)
        startup.join()
        assertTrue(startup.isCancelled)
    }

    @Test(timeout = 10_000) fun stopKeepsUnrelatedBuildAndItsStateAndReservesAgainstNewDebug() = runBlocking {
        var state = "idle"
        val gate = BuildRunDebugOperationGate<Kind> { stopping -> state = if (stopping) "stopping" else "idle" }
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<String>()
        val build = async { gate.run(Kind.BUILD) { state = "building"; entered.complete(Unit); finish.await() } }
        entered.await()
        val stopping = CompletableDeferred<Unit>()
        val stopRelease = CompletableDeferred<Unit>()
        val stop = async { gate.stopDebug(Kind.DEBUG) { stopping.complete(Unit); stopRelease.await() } }
        stopping.await()
        assertTrue(build.isActive)
        assertEquals("building", state)
        assertTrue(runCatching { gate.run(Kind.DEBUG) { error("New debugger raced Stop") } }.exceptionOrNull() is IllegalStateException)
        finish.complete("fresh APK")
        assertEquals("fresh APK", build.await())
        assertEquals("stopping", state)
        stopRelease.complete(Unit)
        stop.await()
        assertEquals("idle", state)
        assertEquals("new debugger", gate.run(Kind.DEBUG) { "new debugger" })
    }

    @Test(timeout = 10_000) fun finishingStopDoesNotResetAnUnrelatedBuildState() = runBlocking {
        var state = "idle"
        val gate = BuildRunDebugOperationGate<Kind> { stopping -> state = if (stopping) "stopping" else "idle" }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val build = async { gate.run(Kind.BUILD) { state = "building"; entered.complete(Unit); release.await() } }
        entered.await()
        gate.stopDebug(Kind.DEBUG) { }
        assertTrue(build.isActive)
        assertEquals("building", state)
        release.complete(Unit)
        build.await()
        assertEquals("idle", state)
    }

    @Test(timeout = 10_000) fun admissionRemainsReservedAfterStartupEndsUntilStopCleanupFinishes() = runBlocking {
        val gate = BuildRunDebugOperationGate<Kind>()
        val entered = CompletableDeferred<Unit>()
        val startup = async { gate.run(Kind.DEBUG) { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        val stopping = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stop = async { gate.stopDebug(Kind.DEBUG) { stopping.complete(Unit); release.await() } }
        stopping.await()
        startup.join()
        assertTrue(startup.isCompleted)
        for (kind in Kind.entries) {
            assertTrue(runCatching { gate.run(kind) { error("New work entered Stop") } }.exceptionOrNull() is IllegalStateException)
        }
        release.complete(Unit)
        stop.await()
        assertEquals("new debug", gate.run(Kind.DEBUG) { "new debug" })
    }

    @Test(timeout = 10_000) fun repeatedStopJoinsTheSameCleanupAndRemainsSafeAfterCompletion() = runBlocking {
        val gate = BuildRunDebugOperationGate<Kind>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleanups = 0
        val first = async { gate.stopDebug(Kind.DEBUG) { cleanups++; entered.complete(Unit); release.await() } }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.stopDebug(Kind.DEBUG) { cleanups++ } }
        assertFalse(second.isCompleted)
        assertEquals(1, cleanups)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, cleanups)
        gate.stopDebug(Kind.DEBUG) { cleanups++ }
        assertEquals(2, cleanups)
    }

    @Test(timeout = 10_000) fun cancellingStopCannotReleaseAdmissionBeforeCleanupFinishes() = runBlocking {
        val gate = BuildRunDebugOperationGate<Kind>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleaned = false
        val first = async { gate.stopDebug(Kind.DEBUG) { entered.complete(Unit); release.await(); cleaned = true } }
        entered.await()
        first.cancel()
        assertFalse(first.isCompleted)
        assertTrue(runCatching { gate.run(Kind.DEBUG) { error("Cleanup race") } }.exceptionOrNull() is IllegalStateException)
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.stopDebug(Kind.DEBUG) { error("Must join first cleanup") } }
        release.complete(Unit)
        first.join()
        second.await()
        assertTrue(cleaned)
        assertTrue(first.isCancelled)
        assertEquals("ready", gate.run(Kind.DEBUG) { "ready" })
    }

    @Test(timeout = 10_000) fun coalescedStopCompletionAllowsImmediateAdmissionOnAnotherThread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            repeat(100) {
                val gate = BuildRunDebugOperationGate<Kind>()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val first = scope.async { gate.stopDebug(Kind.DEBUG) { entered.complete(Unit); release.await() } }
                entered.await()
                val joined = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    gate.stopDebug(Kind.DEBUG) { error("Duplicate Stop") }
                    gate.run(Kind.DEBUG) { "next session" }
                }
                release.complete(Unit)
                assertEquals("next session", joined.await())
                first.await()
            }
        } finally { scope.cancel() }
    }

    @Test(timeout = 10_000) fun cleanupFailureIsSharedAndAdmissionIsReleased() = runBlocking {
        val gate = BuildRunDebugOperationGate<Kind>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            runCatching { gate.stopDebug(Kind.DEBUG) { entered.complete(Unit); release.await(); error("Adapter cleanup failed") } }.exceptionOrNull()
        }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { gate.stopDebug(Kind.DEBUG) { error("Duplicate cleanup") } }.exceptionOrNull()
        }
        release.complete(Unit)
        assertEquals("Adapter cleanup failed", first.await()?.message)
        assertEquals("Adapter cleanup failed", second.await()?.message)
        assertEquals("ready", gate.run(Kind.BUILD) { "ready" })
    }

    @Test(timeout = 10_000) fun navigationRetainsBuildButAgentCancellationReapsOwnedWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = BuildRunDebugOperationGate<Kind>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val build = scope.async { gate.run(Kind.BUILD) { entered.complete(Unit); release.await() } }
            val uiObserver = launch(start = CoroutineStart.UNDISPATCHED) { awaitWorkspaceOperation(build) }
            entered.await()
            uiObserver.cancelAndJoin()
            assertTrue(build.isActive)
            release.complete(Unit)
            build.await()

            val debugEntered = CompletableDeferred<Unit>()
            var cleaned = false
            val debug = scope.async { gate.run(Kind.DEBUG) { debugEntered.complete(Unit); try { awaitCancellation() } finally { cleaned = true } } }
            val agentObserver = launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(AgentOwnedWorkspaceOperation()) { awaitWorkspaceOperation(debug) }
            }
            debugEntered.await()
            agentObserver.cancelAndJoin()
            assertTrue(debug.isCancelled)
            assertTrue(cleaned)
            assertEquals("next build", gate.run(Kind.BUILD) { "next build" })
        } finally { scope.cancel() }
    }

    @Test(timeout = 10_000) fun explicitCancelTargetsOnlyTheAdmittedOperation() = runBlocking {
        val gate = BuildRunDebugOperationGate<Kind>()
        assertFalse(gate.cancelActive("No work"))
        val entered = CompletableDeferred<Unit>()
        val unrelated = async { awaitCancellation() }
        val build = async { gate.run(Kind.BUILD) { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        assertTrue(gate.cancelActive("Canceled by user"))
        build.join()
        assertTrue(build.isCancelled)
        assertTrue(unrelated.isActive)
        assertFalse(gate.cancelActive("Already finished"))
        unrelated.cancelAndJoin()
    }
}
