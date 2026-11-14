package com.baystudio.droide.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class EnvironmentSetupControllerTest {
    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(Dispatchers.Unconfined, block)
    private suspend fun runCurrent() = yield()
    @Test fun regularDistributionDoesNotInspectOrInstallAnything() = runTest {
        val controller = EnvironmentSetupController(this, false,
            inspect = { error("Unexpected disk inspection") }, install = { error("Unexpected install") }, defer = {})
        controller.show(); controller.installFromUser(); runCurrent()
        assertFalse(controller.state.value.presented)
        assertTrue(controller.state.value.enteredWorkspace)
    }

    @Test fun completedEnvironmentSkipsIntroWithoutRunningInstallerOnEveryLaunch() = runTest {
        repeat(5) {
            val controller = EnvironmentSetupController(this, true, inspect = { EnvironmentSetupInspection(true) },
                install = { error("An installed environment must be reused") }, defer = {})
            runCurrent()
            assertFalse(controller.state.value.presented)
            assertTrue(controller.state.value.enteredWorkspace)
            controller.show(); runCurrent(); controller.installFromUser(); runCurrent()
            assertFalse(controller.state.value.presented)
        }
    }

    @Test fun inspectionFailureDoesNotTreatUnreadableUserDataAsAnEmptyInstallation() = runTest {
        var installs = 0
        val controller = EnvironmentSetupController(this, true, inspect = { error("Storage unavailable") },
            install = { installs++ }, defer = {})
        runCurrent(); controller.installFromUser(); runCurrent()
        assertEquals(0, installs)
        assertEquals(EnvironmentSetupPhase.FAILED, controller.state.value.phase)
        assertEquals("Storage unavailable", controller.state.value.error)
    }

    @Test fun doubleTapAndAdditionalObserversStillProduceExactlyOneInstallation() = runTest {
        var installed = false; var installs = 0
        val release = CompletableDeferred<Unit>()
        val controller = EnvironmentSetupController(this, true, inspect = { EnvironmentSetupInspection(installed) },
            install = { report -> installs++; report(EnvironmentSetupProgress(EnvironmentSetupPhase.DOWNLOADING, 25, 100));
                release.await(); installed = true }, defer = {})
        runCurrent()
        assertTrue(controller.state.value.presented); assertEquals(0, installs)
        repeat(10) { controller.installFromUser() }; runCurrent()
        assertEquals(1, installs)
        repeat(5) { controller.show(); assertEquals(.25f, controller.state.value.progress.fraction) }
        assertFalse(controller.state.value.enteredWorkspace)
        release.complete(Unit); runCurrent()
        assertEquals(EnvironmentSetupPhase.READY, controller.state.value.phase)
        assertFalse(controller.state.value.presented)
        controller.show(); runCurrent(); controller.installFromUser(); runCurrent()
        assertEquals(1, installs)
    }

    @Test fun cancellationWaitsForCleanupBeforePermittingAnotherInstallOrExitCompletion() = runTest {
        val cleanup = CompletableDeferred<Unit>(); var installs = 0
        val controller = EnvironmentSetupController(this, true, inspect = { EnvironmentSetupInspection(false) },
            install = { installs++; try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } } },
            defer = {})
        runCurrent(); controller.installFromUser(); runCurrent(); controller.cancel(); runCurrent()
        controller.show(); controller.installFromUser(); controller.later(); runCurrent()
        assertEquals(1, installs)
        assertEquals(EnvironmentSetupPhase.CANCELLING, controller.state.value.phase)
        val closed = launch { controller.closeAndJoin() }; runCurrent()
        assertFalse(closed.isCompleted)
        cleanup.complete(Unit); closed.join(); assertTrue(closed.isCompleted)
        controller.show(); controller.installFromUser(); runCurrent(); assertEquals(1, installs)
    }

    @Test fun pausedInstallationNeedsANewUserActionAndDoesNotRetryByItself() = runTest {
        var installs = 0
        val controller = EnvironmentSetupController(this, true, inspect = { EnvironmentSetupInspection(false) },
            install = { installs++; awaitCancellation() }, defer = {})
        runCurrent(); controller.installFromUser(); runCurrent(); controller.cancel(); runCurrent()
        assertEquals(EnvironmentSetupPhase.AVAILABLE, controller.state.value.phase)
        assertFalse(controller.state.value.busy)
        controller.show(); runCurrent(); assertEquals(1, installs)
        controller.installFromUser(); runCurrent(); assertEquals(2, installs)
        controller.closeAndJoin()
    }

    @Test fun refusingSetupKeepsEditorAccessibleAndDoesNotGrantInstallPermission() = runTest {
        var deferred = false; var installs = 0
        fun controller() = EnvironmentSetupController(this, true,
            inspect = { EnvironmentSetupInspection(false, deferred) }, install = { installs++ }, defer = { deferred = true })
        val first = controller(); runCurrent(); first.later(); runCurrent()
        assertTrue(deferred); assertTrue(first.state.value.enteredWorkspace); assertFalse(first.state.value.presented)
        first.installFromUser(); runCurrent(); assertEquals(0, installs)
        val next = controller(); runCurrent(); assertFalse(next.state.value.presented)
        next.show(); runCurrent(); assertTrue(next.state.value.presented)
        assertTrue(next.state.value.enteredWorkspace); assertEquals(0, installs)
    }

    @Test fun aConcurrentCompletedInstallIsReusedBeforeCallingInstaller() = runTest {
        var installed = false
        val controller = EnvironmentSetupController(this, true, inspect = { EnvironmentSetupInspection(installed) },
            install = { error("Another owner already finished") }, defer = {})
        runCurrent(); installed = true; controller.installFromUser(); runCurrent()
        assertEquals(EnvironmentSetupPhase.READY, controller.state.value.phase)
    }

    @Test fun errorsAndIncompletePublicationNeverBecomeSuccessfulSetup() = runTest {
        var fail = true
        val controller = EnvironmentSetupController(this, true, inspect = { EnvironmentSetupInspection(false) },
            install = { if (fail) error("Connection interrupted") }, defer = {})
        runCurrent(); controller.installFromUser(); runCurrent()
        assertEquals("Connection interrupted", controller.state.value.error)
        assertEquals(EnvironmentSetupPhase.FAILED, controller.state.value.phase)
        fail = false; controller.installFromUser(); runCurrent()
        assertEquals(EnvironmentSetupPhase.FAILED, controller.state.value.phase)
        assertFalse(controller.state.value.enteredWorkspace)
    }

    @Test fun retainedDataAfterFailedActivationStillRequiresSuccessfulValidationOnRetry() = runTest {
        var exists = false; var attempts = 0
        val controller = EnvironmentSetupController(this, true, inspect = { EnvironmentSetupInspection(exists) },
            install = { attempts++; exists = true; error("Existing environment needs repair") }, defer = {})
        runCurrent(); controller.installFromUser(); runCurrent()
        controller.installFromUser(); runCurrent()
        assertEquals(2, attempts)
        assertEquals(EnvironmentSetupPhase.FAILED, controller.state.value.phase)
        assertFalse(controller.state.value.enteredWorkspace)
        assertTrue(exists)
    }

    @Test fun retiredApplicationCannotAdmitOrPublishNewWork() = runTest {
        var open = true; var installs = 0
        val controller = EnvironmentSetupController(this, true, inspect = { EnvironmentSetupInspection(false) },
            install = { installs++ }, defer = {}, admit = { open })
        runCurrent(); open = false; controller.show(); controller.installFromUser(); runCurrent()
        assertEquals(0, installs)
    }

    @Test fun cancelledActivationMustBeValidatedBeforeRetryCanReportSuccess() = runTest {
        var exists = false; var attempts = 0
        val controller = EnvironmentSetupController(this, true,
            inspect = { EnvironmentSetupInspection(exists) },
            install = {
                attempts++
                exists = true
                if (attempts == 1) awaitCancellation()
                error("Activated environment failed health verification")
            }, defer = {})
        runCurrent(); controller.installFromUser(); runCurrent(); controller.cancel(); runCurrent()
        assertEquals(EnvironmentSetupPhase.AVAILABLE, controller.state.value.phase)
        controller.installFromUser(); runCurrent()
        assertEquals(2, attempts)
        assertEquals(EnvironmentSetupPhase.FAILED, controller.state.value.phase)
        assertFalse(controller.state.value.enteredWorkspace)
        assertTrue(exists)
    }

    @Test fun setupRequestCannotTurnFailedActivationIntoSuccessByFindingItsDirectory() = runTest {
        var exists = false; var attempts = 0
        val controller = EnvironmentSetupController(this, true,
            inspect = { EnvironmentSetupInspection(exists) },
            install = { attempts++; exists = true; error("Health check failed") }, defer = {})
        runCurrent(); controller.installFromUser(); runCurrent(); controller.show(); runCurrent()
        assertTrue(controller.state.value.presented)
        assertEquals(EnvironmentSetupPhase.FAILED, controller.state.value.phase)
        assertEquals("Health check failed", controller.state.value.error)
        assertFalse(controller.state.value.enteredWorkspace)
        controller.installFromUser(); runCurrent()
        assertEquals(2, attempts)
    }

    @Test fun setupRequestedAfterDeferralShowsInspectionFailureInsteadOfHidingIt() = runTest {
        var unreadable = false
        val controller = EnvironmentSetupController(this, true,
            inspect = {
                check(!unreadable) { "Storage unavailable" }
                EnvironmentSetupInspection(false, deferred = true)
            }, install = { error("Unexpected install") }, defer = {})
        runCurrent(); assertFalse(controller.state.value.presented)
        unreadable = true; controller.show(); runCurrent()
        assertTrue(controller.state.value.presented)
        assertTrue(controller.state.value.enteredWorkspace)
        assertEquals(EnvironmentSetupPhase.FAILED, controller.state.value.phase)
        assertEquals("Storage unavailable", controller.state.value.error)
    }

    @Test fun successfulResumeClearsIncompleteActivationAndReusesInstalledEnvironment() = runTest {
        var exists = false; var attempts = 0
        val controller = EnvironmentSetupController(this, true,
            inspect = { EnvironmentSetupInspection(exists) },
            install = { attempts++; exists = true; if (attempts == 1) awaitCancellation() }, defer = {})
        runCurrent(); controller.installFromUser(); runCurrent(); controller.cancel(); runCurrent()
        controller.later(); runCurrent(); assertFalse(controller.state.value.presented)
        controller.show(); runCurrent()
        assertTrue(controller.state.value.presented)
        assertEquals(EnvironmentSetupPhase.AVAILABLE, controller.state.value.phase)
        controller.installFromUser(); runCurrent()
        assertEquals(2, attempts)
        assertEquals(EnvironmentSetupPhase.READY, controller.state.value.phase)
        assertTrue(controller.state.value.enteredWorkspace)
        repeat(5) { controller.show(); runCurrent(); controller.installFromUser(); runCurrent() }
        assertEquals(2, attempts)
        assertFalse(controller.state.value.presented)
        assertNull(controller.state.value.error)
    }
}
