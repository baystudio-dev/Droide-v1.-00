package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CMakeHostOs::class])
class CMakeBuildOwnerLeasesTest {
    @Test fun sameLocatorReplacementAndIdentityAliasesConflictAcrossWorkspaceLifetimes() {
        val app = CMakeHostOs.temporaryRoot("cmake-leases")
        try {
            val source = File(app,"source").apply{mkdir()}; val build=File(source,"build").apply{mkdir()}
            val original=cmakeSelectionFixture(source,build); val leases=CMakeBuildOwnerLeases.forApp(app)
            val first=leases.reserve(CMakeOwnerRequest(original,1))
            val replacement=original.copy(workspaceIdentity="reopened",configuredBuildDirectoryIdentity=original.configuredBuildDirectoryIdentity!!.copy(inode=123456789))
            assertThrows(IllegalStateException::class.java){leases.reserve(CMakeOwnerRequest(replacement,2))}
            val alias=original.copy(configuredBuildLocalCanonical="/different/path",configuredBuildGuestAbsolute="/guest/alias")
            assertThrows(IllegalStateException::class.java){leases.reserve(CMakeOwnerRequest(alias,3))}
            leases.releaseNeverStarted(first)
            leases.releaseNeverStarted(leases.reserve(CMakeOwnerRequest(replacement,4)))
        } finally {app.deleteRecursively()}
    }
    @Test fun unknownAndMismatchedReceiptsRetainConfiguredKey() {
        val app=CMakeHostOs.temporaryRoot("cmake-quarantine")
        try {
            val build=File(app,"build").apply{mkdir()};val selection=cmakeSelectionFixture(app,build)
            val leases=CMakeBuildOwnerLeases.forApp(app);val lease=leases.reserve(CMakeOwnerRequest(selection,1))
            leases.starting(lease);val proof=cmakeReadyFixture(lease);leases.ready(lease,proof)
            assertThrows(IllegalStateException::class.java){leases.release(lease,cmakeReceiptFixture(lease,proof).copy(transportClosed=false))}
            leases.quarantine(lease)
            assertThrows(IllegalStateException::class.java){leases.reserve(CMakeOwnerRequest(selection,2))}
            leases.release(lease,cmakeReceiptFixture(lease,proof).copy(privateCleanupStatus=CMakePrivateCleanupStatus.QUARANTINED))
            leases.releaseNeverStarted(leases.reserve(CMakeOwnerRequest(selection,3)))
        } finally {app.deleteRecursively()}
    }
    @Test fun restartWithoutReadyCannotRecoverAndCapacityNeverEvicts() {
        val app=CMakeHostOs.temporaryRoot("cmake-capacity")
        try {
            val leases=CMakeBuildOwnerLeases.forApp(app)
            repeat(32){n-> val build=File(app,"build$n").apply{mkdir()};leases.reserve(CMakeOwnerRequest(cmakeSelectionFixture(app,build),n.toLong()))}
            val extra=File(app,"extra").apply{mkdir()}
            assertThrows(IllegalStateException::class.java){leases.reserve(CMakeOwnerRequest(cmakeSelectionFixture(app,extra),99))}
            val constructor=CMakeBuildOwnerLeases::class.java.getDeclaredConstructor(File::class.java).apply{isAccessible=true}
            val restarted=constructor.newInstance(File(app,"run/cmake-build-leases"))
            assertEquals(0,restarted.recover())
            assertEquals(32,File(app,"run/cmake-build-leases").listFiles()!!.size)
        } finally {app.deleteRecursively()}
    }
    @Test fun malformedTraversalAndFilenameMismatchCannotDeleteOutsideRegistry() {
        val app=CMakeHostOs.temporaryRoot("cmake-malformed")
        try {
            val leases=CMakeBuildOwnerLeases.forApp(app);val lease=leases.reserve(CMakeOwnerRequest(cmakeSelectionFixture(app),1))
            val outside=File(app,"outside.json").apply{writeText("sentinel")}
            val original=lease.file.readText()
            lease.file.writeText(original.replace(lease.id,"../../outside"))
            assertThrows(IllegalArgumentException::class.java){leases.recover()}
            assertEquals("sentinel",outside.readText())
            lease.file.writeText(original)
            val wrong=File(lease.file.parentFile,"ffffffffffffffffffffffffffffffff.json")
            assertTrue(lease.file.renameTo(wrong))
            assertThrows(IllegalArgumentException::class.java){leases.recover()}
            assertTrue(wrong.exists());assertEquals("sentinel",outside.readText())
        }finally{app.deleteRecursively()}
    }
    @Test fun privateReservationDoesNotConsumeConfiguredLeaseQuota() {
        val app=CMakeHostOs.temporaryRoot("cmake-independent-quotas")
        try {
            val leases=CMakeBuildOwnerLeases.forApp(app)
            repeat(32){n->val build=File(app,"build$n").apply{mkdir()};leases.reserve(CMakeOwnerRequest(cmakeSelectionFixture(app,build),n.toLong()))}
            val builtin=leases.reserve(CMakeOwnerRequest(cmakeSelectionFixture(app),40))
            leases.releaseNeverStarted(builtin)
        }finally{app.deleteRecursively()}
    }

}
