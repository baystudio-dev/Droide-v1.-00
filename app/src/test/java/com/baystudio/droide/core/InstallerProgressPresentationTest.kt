package com.baystudio.droide.core

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class InstallerProgressPresentationTest {
    private val details = InstallerProgressDetails("npm", "npm", "latest", listOf("npm: latest"))
    private val success = UnifiedPackageTransactionResult(true, "npm installed and verified")
    private class Owner {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val panel = InstallerProgressPresentation()
        fun latest() = panel.state.value.entries.last()
    }
    private fun withOwner(block: suspend Owner.() -> Unit) = runBlocking {
        val owner = Owner()
        try { withTimeout(5_000) { owner.block() } }
        finally { owner.scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun repeatedMinimizeReopenOnlyChangesPresentation() = withOwner {
        val release = CompletableDeferred<Unit>()
        var started = 0
        val job = panel.launch(scope, details) { report ->
            started++
            report(PackageProgressSnapshot("downloading", "Downloaded 60099 KiB / 61116 KiB", "redacted log"), .98f)
            release.await(); success
        }
        val entry = latest()
        repeat(20) {
            panel.minimize(entry.id)
            assertNull(panel.state.value.expandedId)
            assertEquals(entry, latest())
            assertTrue(job.isActive)
            panel.open(entry.id)
            assertEquals(entry, panel.state.value.expanded)
        }
        assertEquals(1, started)
        release.complete(Unit); job.await()
        assertEquals(InstallerProgressOutcome.SUCCEEDED, latest().outcome)
    }

    @Test fun completionWhileMinimizedRetainsResultAndNeverReopens() = withOwner {
        val release = CompletableDeferred<Unit>()
        val job = panel.launch(scope, details) { release.await(); success }
        panel.minimize(latest().id)
        release.complete(Unit); job.await()
        assertNull(panel.state.value.expandedId)
        assertEquals(success.message, latest().message)
        assertFalse(latest().running)
        panel.open(latest().id)
        assertEquals(InstallerProgressOutcome.SUCCEEDED, panel.state.value.expanded!!.outcome)
    }

    @Test fun failureWhileMinimizedRetainsErrorAndNeverReopens() = withOwner {
        val release = CompletableDeferred<Unit>()
        val job = panel.launch(scope, details) { release.await(); UnifiedPackageTransactionResult(false, "Checksum mismatch") }
        panel.minimize(latest().id)
        release.complete(Unit); job.await()
        assertNull(panel.state.value.expandedId)
        assertEquals(InstallerProgressOutcome.FAILED, latest().outcome)
        assertEquals("Checksum mismatch", latest().message)
    }

    @Test fun thrownFailureIsTerminalWithoutUnhandledLaunchException() = withOwner {
        val job = panel.launch(scope, details) { throw IllegalStateException("private failure details") }
        try { job.await(); fail("must throw") } catch (_: IllegalStateException) { }
        assertEquals(InstallerProgressOutcome.FAILED, latest().outcome)
        assertEquals("Installation failed: IllegalStateException", latest().message)
    }

    @Test fun helperReadyDoesNotClaimCompletionBeforeOwnedCleanup() = withOwner {
        val release = CompletableDeferred<Unit>()
        val job = panel.launch(scope, details) { report ->
            report(PackageProgressSnapshot("ready", "Helper ready", "retained output"), 1f)
            release.await(); success
        }
        assertTrue(latest().running)
        panel.minimize(latest().id)
        release.complete(Unit); job.await()
        assertFalse(latest().running)
        assertEquals("retained output", latest().log)
        assertNull(latest().fraction)
    }

    @Test fun cancelWaitsForOwnedCleanupAndDoesNotDismiss() = withOwner {
        val entered = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val job = panel.launch(scope, details) {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { entered.complete(Unit); finishCleanup.await() }
            }
        }
        val id = latest().id
        panel.cancel(id); entered.await()
        assertTrue(latest().running)
        assertTrue(latest().cancelRequested)
        assertEquals(id, panel.state.value.expandedId)
        panel.minimize(id)
        finishCleanup.complete(Unit); job.join()
        assertEquals(InstallerProgressOutcome.CANCELED, latest().outcome)
        assertNull(panel.state.value.expandedId)
    }

    @Test fun cancelQueuedJobDoesNotCancelDifferentActiveInstaller() = withOwner {
        val coordinator = InstallerOperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = panel.launch(scope, details) {
            coordinator.operation { entered.complete(Unit); release.await(); success }
        }
        entered.await()
        val firstId = latest().id
        var secondEntered = false
        val second = panel.launch(scope, details.copy(familyId = "git", familyName = "git")) {
            coordinator.operation { secondEntered = true; success }
        }
        val secondId = latest().id
        panel.cancel(secondId); second.join()
        assertFalse(secondEntered)
        assertTrue(first.isActive)
        assertTrue(coordinator.active)
        assertEquals(InstallerProgressOutcome.CANCELED, latest().outcome)
        assertTrue(panel.state.value.entries.first { it.id == firstId }.running)
        release.complete(Unit); first.await()
    }

    @Test fun staleControlsNeverAffectNewSameFamilyJob() = withOwner {
        panel.launch(scope, details) { success }.await()
        val oldId = latest().id
        panel.dismiss(oldId)
        val release = CompletableDeferred<Unit>()
        val next = panel.launch(scope, details) { release.await(); success }
        val newId = latest().id
        assertNotEquals(oldId, newId)
        panel.cancel(oldId); panel.minimize(oldId); panel.dismiss(oldId); panel.open(oldId)
        assertEquals(newId, panel.state.value.expandedId)
        assertTrue(next.isActive)
        release.complete(Unit); next.await()
    }

    @Test fun finishingOlderJobDoesNotChangeExpandedNewJob() = withOwner {
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val first = panel.launch(scope, details) { firstRelease.await(); success }
        val second = panel.launch(scope, details) { secondRelease.await(); success }
        val id = latest().id
        firstRelease.complete(Unit); first.await()
        assertEquals(id, panel.state.value.expandedId)
        secondRelease.complete(Unit); second.await()
    }

    @Test fun dismissRunningEntryDoesNothing() = withOwner {
        val release = CompletableDeferred<Unit>()
        val job = panel.launch(scope, details) { release.await(); success }
        val before = panel.state.value
        panel.dismiss(latest().id)
        assertEquals(before, panel.state.value)
        assertTrue(job.isActive)
        release.complete(Unit); job.await()
        panel.dismiss(latest().id)
        assertTrue(panel.state.value.entries.isEmpty())
        assertNull(panel.state.value.expandedId)
    }

    @Test fun staleProgressAfterCompletionCannotOverwriteResultOrNewOperation() = withOwner {
        var oldReport: ((PackageProgressSnapshot, Float?) -> Unit)? = null
        panel.launch(scope, details) { oldReport = it; success }.await()
        val before = panel.state.value
        oldReport!!(PackageProgressSnapshot("downloading", "stale", "stale"), .2f)
        assertEquals(before, panel.state.value)
    }

    @Test fun managerScopeCancellationRetiresJobsButNewOwnerStartsEmpty() = withOwner {
        val first = panel.launch(scope, details) { awaitCancellation() }
        val second = panel.launch(scope, details) { awaitCancellation() }
        panel.minimize(latest().id)
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(first.isCancelled); assertTrue(second.isCancelled)
        assertTrue(panel.state.value.entries.all { it.outcome == InstallerProgressOutcome.CANCELED })
        assertNull(panel.state.value.expandedId)
        assertTrue(InstallerProgressPresentation().state.value.entries.isEmpty())
    }

    @Test fun alreadyClosedScopeNeverStartsInstallation() = withOwner {
        scope.cancel()
        var started = false
        val job = panel.launch(scope, details) { started = true; success }
        job.join()
        assertFalse(started)
        assertEquals(InstallerProgressOutcome.CANCELED, latest().outcome)
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
    @Test fun cancelBeforeFirstProgressNeverExecutesBody() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val panel = InstallerProgressPresentation()
        var started = false
        val job = panel.launch(scope, details) { started = true; success }
        val id = panel.state.value.entries.single().id
        panel.minimize(id); panel.cancel(id); dispatcher.drain()
        assertFalse(started)
        assertTrue(job.isCancelled)
        assertEquals(InstallerProgressOutcome.CANCELED, panel.state.value.entries.single().outcome)
        assertNull(panel.state.value.expandedId)
        scope.cancel(); dispatcher.drain()
    }

    @Test fun resultsAreBoundedWithoutEvictingRunningJob() = withOwner {
        val release = CompletableDeferred<Unit>()
        val running = panel.launch(scope, details) { release.await(); success }
        val runningId = latest().id
        repeat(30) { panel.launch(scope, details) { success }.await() }
        assertTrue(panel.state.value.entries.any { it.id == runningId && it.running })

        assertTrue(panel.state.value.entries.count { !it.running } <= 9)
        panel.open(runningId)
        release.complete(Unit); running.await()
        assertEquals(runningId, panel.state.value.expandedId)
    }

    @Test fun logAndMessageBoundsAndInvalidFractionAreEnforced() = withOwner {
        val release = CompletableDeferred<Unit>()
        var publish: ((PackageProgressSnapshot, Float?) -> Unit)? = null
        val job = panel.launch(scope, details) { report ->
            publish = report
            report(PackageProgressSnapshot("downloading", "a".repeat(5000), "b".repeat(20_000)), Float.NaN)
            release.await(); success
        }
        assertEquals(1024, latest().message.length)
        assertEquals(8192, latest().log.length)
        assertNull(latest().fraction)
        publish!!(PackageProgressSnapshot("downloading", "progress"), 2f)
        assertEquals(1f, latest().fraction!!, 0f)
        release.complete(Unit); job.await()
    }

    @Test fun confirmedMetadataIsCopiedAndNotMutableThroughCallerList() = withOwner {
        val components = mutableListOf("original")
        val release = CompletableDeferred<Unit>()
        val job = panel.launch(scope, details.copy(components = components)) { release.await(); success }
        components.clear()
        assertEquals(listOf("original"), latest().details.components)
        release.complete(Unit); job.await()
    }

    @Test fun failureThenExplicitNewRequestUsesIndependentIdentity() = withOwner {
        panel.launch(scope, details) { UnifiedPackageTransactionResult(false, "checksum") }.await()
        val failure = latest()
        panel.minimize(failure.id); panel.open(failure.id)
        assertEquals(failure, latest())
        panel.launch(scope, details) { success }.await()
        assertNotEquals(failure.id, latest().id)
        assertEquals(InstallerProgressOutcome.FAILED, panel.state.value.entries.first().outcome)
        assertEquals(InstallerProgressOutcome.SUCCEEDED, latest().outcome)
    }
    @Test fun utf16BoundsNeverSplitValidSurrogatePairs() = withOwner {
        val release = CompletableDeferred<Unit>()
        val job = panel.launch(scope, details) { report ->
            report(PackageProgressSnapshot("downloading", "a".repeat(1023) + "😀z", "x😀" + "b".repeat(8191)), null)
            release.await(); UnifiedPackageTransactionResult(false, "c".repeat(1023) + "😀z")
        }
        assertEquals("a".repeat(1023), latest().message)
        assertEquals("b".repeat(8191), latest().log)
        release.complete(Unit); job.await()
        assertEquals("c".repeat(1023), latest().message)
    }

    @Test fun providerRelayRejectsInitialQueuedAndLateForeignFractions() {
        val reports = mutableListOf<Pair<PackageProgressSnapshot, Float?>>()
        val relay = InstallerProviderProgressRelay { progress, fraction -> reports += progress to fraction }
        relay.provider(1f)
        assertTrue(reports.isEmpty())
        relay.operation(PackageProgressSnapshot("queued", "Waiting"))
        relay.provider(.3f)
        assertEquals(1, reports.size)
        assertNull(reports.last().second)
        relay.operation(PackageProgressSnapshot("downloading", "Downloading", "redacted"))
        relay.provider(.4f)
        assertEquals(.4f, reports.last().second!!, 0f)
        assertEquals("redacted", reports.last().first.log)
        relay.operation(PackageProgressSnapshot("ready", "Done"))
        val count = reports.size
        relay.provider(.8f)
        assertEquals(count, reports.size)
        assertNull(reports.last().second)
    }

    @Test fun providerRelayUpdatesLiveFractionAndKeepsLatestScopedLog() {
        val reports = mutableListOf<Pair<PackageProgressSnapshot, Float?>>()
        val relay = InstallerProviderProgressRelay { progress, fraction -> reports += progress to fraction }
        relay.operation(PackageProgressSnapshot("downloading", "Download", "first"))
        relay.provider(.1f); relay.provider(.2f); relay.provider(.9f)
        relay.operation(PackageProgressSnapshot("downloading", "Download", "new log"))
        assertEquals(.9f, reports.last().second!!, 0f)
        assertEquals("new log", reports.last().first.log)
        relay.operation(PackageProgressSnapshot("activating", "Verify"))
        assertNull(reports.last().second)
        relay.operation(PackageProgressSnapshot("downloading", "Next artifact"))
        assertNull(reports.last().second)
    }

    @Test fun scopedReportingCollectsLiveFractionsAndStopsBeforeJobCompletes() = withOwner {
        val fractions = kotlinx.coroutines.flow.MutableStateFlow<Float?>(1f)
        var collecting = false
        val flow = kotlinx.coroutines.flow.flow {
            collecting = true
            try { fractions.collect { emit(it) } } finally { collecting = false }
        }
        val release = CompletableDeferred<Unit>()
        val job = panel.launch(scope, details) { report ->
            withInstallerProgressReporting(flow, report) {
                assertTrue(collecting)
                assertNull(latest().fraction)
                currentCoroutineContext()[PackageProgressObserver]!!.publish(PackageProgressSnapshot("downloading", "Download"))
                release.await(); success
            }
        }
        fractions.value = .3f
        assertEquals(.3f, latest().fraction!!, 0f)
        panel.minimize(latest().id)
        fractions.value = .8f
        assertEquals(.8f, latest().fraction!!, 0f)
        release.complete(Unit); job.await()
        assertFalse(collecting)
        assertEquals(InstallerProgressOutcome.SUCCEEDED, latest().outcome)
        assertNull(latest().fraction)
    }

    @Test fun queuedSameProviderRequestCannotBorrowActiveRequestsFraction() = withOwner {
        val coordinator = InstallerOperationCoordinator()
        val fractions = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = panel.launch(scope, details) { report ->
            withInstallerProgressReporting(fractions, report) {
                coordinator.operation {
                    currentCoroutineContext()[PackageProgressObserver]!!.publish(PackageProgressSnapshot("downloading", "First"))
                    admitted.complete(Unit); release.await(); success
                }
            }
        }
        admitted.await()
        val firstId = latest().id
        val second = panel.launch(scope, details) { report ->
            withInstallerProgressReporting(fractions, report) {
                currentCoroutineContext()[PackageProgressObserver]!!.publish(PackageProgressSnapshot("admitted", "Waiting for owner"))
                coordinator.operation { success }
            }
        }
        fractions.value = .7f
        assertNull(latest().fraction)
        assertEquals(.7f, panel.state.value.entries.first { it.id == firstId }.fraction!!, 0f)
        panel.cancel(latest().id); second.join()
        assertTrue(first.isActive)
        release.complete(Unit); first.await()
    }

    @Test fun cancelRetiresProviderCollectorBeforeTerminalPresentation() = withOwner {
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val flow = kotlinx.coroutines.flow.flow<Float?> {
            try { emit(.2f); awaitCancellation() }
            finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseCleanup.await() } }
        }
        val job = panel.launch(scope, details) { report ->
            withInstallerProgressReporting(flow, report) { awaitCancellation() }
        }
        panel.cancel(latest().id); cleanupEntered.await()
        assertTrue(latest().running)
        releaseCleanup.complete(Unit); job.join()
        assertEquals(InstallerProgressOutcome.CANCELED, latest().outcome)
    }

}
