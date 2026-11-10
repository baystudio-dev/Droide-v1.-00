package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InstallerOperationStateTest {
    private fun publisher(coordinator: InstallerOperationCoordinator) =
        ManagedExtensionInstaller.OperationStatePublisher(coordinator)

    @Test fun progressUsesTheGrantedOwnerAndDetachedObserversReceiveNoLaterLogs() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val state = publisher(coordinator)
        val first = mutableListOf<PackageProgressSnapshot>()
        val second = mutableListOf<PackageProgressSnapshot>()
        val safeLog = InstallerProgressLog().append("Authorization: Bearer private-secret\nFetching archive\n")
        coordinator.operation(onStart = { state.progressObserver = PackageProgressObserver { first.add(it) } },
            onFinish = { state.progressObserver = null }) {
            state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.DOWNLOADING, "Fetching", log = safeLog)
            coordinator.operation {
                state.update { it.copy(phase = ManagedExtensionInstaller.Phase.ACTIVATING, message = "Checking health") }
            }
        }
        val before = first.size
        coordinator.operation(onStart = { state.progressObserver = PackageProgressObserver { second.add(it) } },
            onFinish = { state.progressObserver = null }) {
            state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.INSTALLING, "Other package", log = "Other output\n")
        }
        state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.IDLE, "Later snapshot")
        assertEquals(before, first.size)
        assertEquals(1, second.size)
        assertTrue(first.last().message.contains("Checking health"))
        assertFalse(first.joinToString().contains("private-secret"))
        assertFalse(second.joinToString().contains("Fetching archive"))
    }

    @Test fun verifiedOpenCodeSuccessSurvivesNormalizationAndLaterRestorationActivity() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val state = publisher(coordinator)
        val success = InstallerExecutionPolicy.successMessage("plugin.opencode")
        coordinator.operation(onFinish = {
            assertNull(it)
            state.finishSuccess(ManagedExtensionInstaller.Phase.INSTALLING)
        }) {
            state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.READY, success,
                familyId = "plugin.opencode")
            assertEquals(ManagedExtensionInstaller.Phase.ACTIVATING, state.value.phase)
            assertTrue(state.value.running)
            state.update { it.copy(message = "Restoring the previously selected prerequisite…") }
            assertEquals(success, state.value.verifiedCompletionMessage)
        }
        assertEquals(ManagedExtensionInstaller.Phase.READY, state.value.phase)
        assertEquals(success, state.value.message)
        assertTrue(state.value.message.contains("Sign-in is optional"))
        assertFalse(state.value.running)
    }

    @Test fun successLookingProgressIsNotAcceptedAsVerifiedCompletion() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val state = publisher(coordinator)
        coordinator.operation(onFinish = { state.finishSuccess(ManagedExtensionInstaller.Phase.INSTALLING) }) {
            state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.ACTIVATING,
                InstallerExecutionPolicy.successMessage("plugin.opencode"), familyId = "plugin.opencode")
            assertNull(state.value.verifiedCompletionMessage)
        }
        assertEquals("Installation completed.", state.value.message)
    }

    @Test fun failedRestorationAndNewOperationsDoNotReuseEarlierVerifiedSuccess() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val state = publisher(coordinator)
        coordinator.operation {
            state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.READY,
                InstallerExecutionPolicy.successMessage("plugin.opencode"))
            state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.FAILED,
                "Previous prerequisite could not be restored")
            assertNull(state.value.verifiedCompletionMessage)
        }
        assertEquals(ManagedExtensionInstaller.Phase.FAILED, state.value.phase)
        coordinator.operation {
            state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.INSTALLING, "Next package")
            assertNull(state.value.verifiedCompletionMessage)
        }
    }

    @Test fun licenseDownloadRemainsBusyAndConfirmationAfterPreparationDoesNot() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val state = publisher(coordinator)
        coordinator.operation {
            state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.LICENSE, "Loading license")
            assertTrue(state.value.running)
            assertTrue(state.value.operationActive)
        }
        state.update { it.copy(operationActive = false) }
        assertEquals(ManagedExtensionInstaller.Phase.LICENSE, state.value.phase)
        assertFalse(state.value.running)
    }

    @Test fun helperReadyIsHeldUntilTheWholeTransactionAndDefaultRestorationFinish() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val state = publisher(coordinator)
        var restored = false
        coordinator.operation(onFinish = {
            assertNull(it)
            assertTrue(restored)
            state.update { previous -> previous.copy(phase = ManagedExtensionInstaller.Phase.READY) }
        }) {
            state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.READY, "Package verified")
            assertEquals(ManagedExtensionInstaller.Phase.ACTIVATING, state.value.phase)
            assertTrue(state.value.running)
            restored = true
        }
        assertEquals(ManagedExtensionInstaller.Phase.READY, state.value.phase)
        assertFalse(state.value.running)
    }

    @Test fun failedOrCanceledNestedStepStaysBusyUntilCleanupReturns() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val state = publisher(coordinator)
        coordinator.operation(onFinish = { state.update { previous -> previous.copy(operationActive = false) } }) {
            state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.FAILED, "Verification failed")
            assertTrue(state.value.running)
            state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.CANCELED, "Rolling back")
            assertTrue(state.value.running)
        }
        assertFalse(state.value.running)
    }

    @Test fun closedManagerIgnoresLateSuccessAndProgressPublication() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val state = publisher(coordinator)
        state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.IDLE, "Last snapshot")
        coordinator.close()
        state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.READY, "Late success")
        state.update { it.copy(message = "Late progress") }
        assertEquals("Last snapshot", state.value.message)
        state.value = ManagedExtensionInstaller.State(ManagedExtensionInstaller.Phase.CANCELED, "Workspace closed")
        assertEquals(ManagedExtensionInstaller.Phase.CANCELED, state.value.phase)
        assertFalse(state.value.running)
    }
}
