package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun cmakeIdentityFixture() = CMakeManagedLaunchIdentity("lsp.cmake-language-server", "0.1.11", "fixture", "receipt", "recipe", "seal",
    "selection", "/wrapper", "/wrapper", "/bootstrap", "/supervisor", "/purelib", "/usr/bin/python3.12", "/usr/bin/cmake", "runtime", "bootstrap-hash", "supervisor-hash")
internal fun cmakeSelectionFixture(root: File, build: File? = null, environment: String = "fixture-environment"): CMakeAnalysisSelections.Selection =
    CMakeAnalysisSelections.Selection("workspace", environment, "mapping", root.canonicalPath, root.canonicalPath,
        CMakeSelectionInspector.directoryIdentity(root, environment), 1,
        if (build == null) CMakeAnalysisSelections.BuildMode.BUILTIN_ONLY_PRIVATE else CMakeAnalysisSelections.BuildMode.EXISTING_CONFIGURED,
        build?.canonicalPath, build?.canonicalPath, build?.let { CMakeSelectionInspector.directoryIdentity(it, environment) },
        build?.let { CMakeAnalysisSelections.CacheSourceAssociation("workspace", root.canonicalPath, root.canonicalPath, root.canonicalPath) })
internal fun cmakeReadyFixture(lease: CMakeBuildOwnerLeases.Lease): CMakeReadyProof {
    val birth = CMakeProcessBirth(1_999_999_991, 42, File("/proc/sys/kernel/random/boot_id").readText().trim(),
        Files.readSymbolicLink(File("/proc/self/ns/pid").toPath()).toString())
    return CMakeReadyProof(lease.nonce, lease.id, lease.request.selection.environmentIdentity, lease.request.ownerGeneration,
        birth, birth.copy(pid=1_999_999_992), birth.copy(pid=1_999_999_993))
}
internal fun cmakeReceiptFixture(lease: CMakeBuildOwnerLeases.Lease, ready: CMakeReadyProof) = CMakeTeardownReceipt(
    CMakeTeardownStatus.COMPLETE, lease.id, lease.request.ownerGeneration, lease.nonce, lease.request.selection.environmentIdentity,
    true, true, true, true, false, CMakePrivateCleanupStatus.NOT_STARTED, ready, 2, 50, "ESRCH")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CMakeHostOs::class])
class CMakeOwnedProcessTest {
    @Test fun noProcessRetirementIsIdempotentAndPureObservationNeverAdmitsUnstartedOwner() = runBlocking<Unit> {
        val app = CMakeHostOs.temporaryRoot("cmake-owner")
        val source = File(app, "source").apply { mkdir() }
        val selection = cmakeSelectionFixture(source)
        val request = CMakeOwnerRequest(selection, 1)
        val leases = CMakeBuildOwnerLeases.forApp(app)
        val lease = leases.reserve(request)
        val privateState = CMakePrivateState.allocate(app, selection.environmentIdentity)
        val owner = LocalCMakePreparedOwner(cmakeIdentityFixture(), request, privateState, leases, lease,
            WorkspacePathMapper(source, source.canonicalPath)) { true }
        assertNull(owner.checkedAdmission())
        assertFalse(owner.isAdmissionCurrent(CMakeHostAdmissionObservation(Any(), 1)))
        assertNull(owner.retire()); assertNull(owner.retire())
        assertEquals(CMakePrivateCleanupStatus.COMPLETE, privateState.cleanupStatus)
        assertFalse(privateState.localRoot.exists())
        assertEquals("RELEASED", lease.phase)
        app.deleteRecursively()
    }
    @Test fun immediateExitPublishesOwnerBeforeObserverAndUnknownCleanupFailsAwait() = runBlocking<Unit> {
        val app = CMakeHostOs.temporaryRoot("cmake-immediate-exit")
        try {
            val request = CMakeOwnerRequest(cmakeSelectionFixture(app), 1)
            val leases = CMakeBuildOwnerLeases.forApp(app); val lease = leases.reserve(request)
            val state = CMakePrivateState.allocate(app, request.selection.environmentIdentity)
            val owner = LocalCMakePreparedOwner(cmakeIdentityFixture(), request, state, leases, lease, WorkspacePathMapper(app, app.path)) { true }
            val process = owner.createProcess { object : Process() {
                override fun getOutputStream() = java.io.ByteArrayOutputStream()
                override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())
                override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
                override fun waitFor() = 0
                override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
                override fun exitValue() = 0
                override fun destroy() {}
                override fun isAlive() = false
            } }
            assertSame(owner, process.owner)
            val failure = runCatching { kotlinx.coroutines.withTimeout(3_000) { process.awaitExit() } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("QUARANTINED", lease.phase)
            assertEquals(CMakeTeardownStatus.UNKNOWN, owner.retire()!!.status)
            assertEquals(CMakePrivateCleanupStatus.QUARANTINED, state.cleanupStatus)
            var invoked = false
            assertThrows(IllegalStateException::class.java) { owner.createProcess { invoked = true; error("must not run") } }
            assertFalse(invoked)
        } finally { app.deleteRecursively() }
    }
    @Test fun incompleteCompositeReceiptsNeverRelease() {
        val root = CMakeHostOs.temporaryRoot("cmake-receipt")
        try {
            val lease = CMakeBuildOwnerLeases.forApp(root).reserve(CMakeOwnerRequest(cmakeSelectionFixture(root), 1))
            val good = cmakeReceiptFixture(lease, cmakeReadyFixture(lease))
            assertTrue(good.releasesBuild)
            listOf(good.copy(status=CMakeTeardownStatus.UNKNOWN), good.copy(directChildReaped=false), good.copy(groupEmpty=false),
                good.copy(outerChildReaped=false), good.copy(transportClosed=false), good.copy(ready=null), good.copy(preReapScans=1),
                good.copy(minimumSeparationMs=49), good.copy(postReapGroupObservation="DENIED"), good.copy(supervisorNonce="replayed"))
                .forEach { assertFalse(it.releasesBuild) }
            assertTrue(good.copy(forcedTermination=true,privateCleanupStatus=CMakePrivateCleanupStatus.QUARANTINED).releasesBuild)
        } finally { root.deleteRecursively() }
    }

    @Test fun failedOriginalManagedWitnessRetiresBeforeAnyFactory() = runBlocking<Unit> {
        val app=CMakeHostOs.temporaryRoot("cmake-original-witness")
        try {
            val request=CMakeOwnerRequest(cmakeSelectionFixture(app),1)
            val leases=CMakeBuildOwnerLeases.forApp(app);val lease=leases.reserve(request)
            val state=CMakePrivateState.allocate(app,request.selection.environmentIdentity)
            var fullChecks=0;var retirements=0
            val owner=LocalCMakePreparedOwner(cmakeIdentityFixture(),request,state,leases,lease,WorkspacePathMapper(app,app.path),
                checkManagedBeforeStart={checkCancelled->checkCancelled();fullChecks++;false},
                retireManagedIdentity={retirements++}){true}
            assertThrows(IllegalStateException::class.java){owner.requireManagedIdentityBeforeStart{}}
            assertEquals(1,fullChecks);assertEquals(1,retirements)
            var invoked=false
            assertThrows(IllegalStateException::class.java){owner.createProcess{invoked=true;error("not admitted")}}
            assertFalse(invoked)
            owner.retire();assertEquals(1,retirements);assertEquals("RELEASED",lease.phase)
        }finally{app.deleteRecursively()}
    }
}
