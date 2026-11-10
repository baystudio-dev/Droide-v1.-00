package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class UbuntuPackageAttachAdmissionTest {
    @Test fun admittedPackageDownloadDoesNotBlockAttachOnItsMutationMutex() = runBlocking {
        val gate = Mutex()
        val admission = UbuntuPackageAttachAdmission()
        val entered = CompletableDeferred<Unit>()
        val download = CompletableDeferred<Unit>()
        var healthProbes = 0
        val installer = launch {
            gate.withLock {
                healthProbes++
                admission.duringPackageOperation({ "same-runtime" }) {
                    entered.complete(Unit)
                    download.await()
                }
            }
        }
        try {
            entered.await()
            assertTrue(gate.isLocked)
            withTimeout(1_000) {
                repeat(100) {
                    if (!admission.mayAttach()) gate.withLock { healthProbes++ }
                }
            }
            assertEquals(1, healthProbes)
            assertFalse(download.isCompleted)
        } finally { download.complete(Unit); installer.join() }
        assertFalse(admission.mayAttach())
    }

    @Test fun changedOrUnknownRuntimeAndRepairDoNotBorrowPackageAdmission() = runBlocking {
        val admission = UbuntuPackageAttachAdmission()
        var identity: String? = "original"
        assertFalse(admission.mayAttach())
        admission.duringPackageOperation({ identity }) {
            assertTrue(admission.mayAttach())
            identity = "replaced-root"
            assertFalse(admission.mayAttach())
            identity = null
            assertFalse(admission.mayAttach())
        }
        identity = "original"
        assertFalse(admission.mayAttach())
        admission.duringPackageOperation({ null }) { assertFalse(admission.mayAttach()) }
    }

    @Test fun cancellationInvalidatesAdmissionBeforeNoncancellableCleanupFinishes() = runBlocking {
        val admission = UbuntuPackageAttachAdmission()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val installer = launch {
            admission.duringPackageOperation({ "original" }) {
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); releaseCleanup.await() } }
            }
        }
        entered.await()
        assertTrue(admission.mayAttach())
        installer.cancel()
        cleaning.await()
        try { assertFalse(admission.mayAttach()) }
        finally { releaseCleanup.complete(Unit); installer.join() }
        assertFalse(admission.mayAttach())
    }

    @Test fun cancellationDuringIdentityProbeCannotAdmitAStaleOwner() = runBlocking {
        val admission = UbuntuPackageAttachAdmission()
        val entered = CompletableDeferred<Unit>()
        var cancelDuringProbe = false
        lateinit var installer: Job
        installer = launch(start = CoroutineStart.LAZY) {
            admission.duringPackageOperation({
                if (cancelDuringProbe) installer.cancel()
                "same-runtime"
            }) { entered.complete(Unit); awaitCancellation() }
        }
        installer.start(); entered.await()
        cancelDuringProbe = true
        assertFalse(admission.mayAttach())
        installer.join()
    }

    @Test fun failedPackageOperationClearsAdmissionAndAllowsTheNextOwner() = runBlocking {
        val admission = UbuntuPackageAttachAdmission()
        try {
            admission.duringPackageOperation({ "a" }) { error("download failed") }
            fail("Expected package failure")
        } catch (expected: IllegalStateException) { assertEquals("download failed", expected.message) }
        assertFalse(admission.mayAttach())
        admission.duringPackageOperation({ "b" }) { assertTrue(admission.mayAttach()) }
        assertFalse(admission.mayAttach())
    }
}
