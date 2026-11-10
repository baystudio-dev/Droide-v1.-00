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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CMakeHostOs::class])
class CMakePrivateStateTest {
    @Test fun replacementOrUnexpectedCacheRevokesPrivateObservation() = runBlocking<Unit> {
        val app=CMakeHostOs.temporaryRoot("cmake-private")
        try {
            val source=File(app,"source").apply{mkdir()};val request=CMakeOwnerRequest(cmakeSelectionFixture(source),1)
            val leases=CMakeBuildOwnerLeases.forApp(app);val state=CMakePrivateState.allocate(app,request.selection.environmentIdentity)
            val owner=LocalCMakePreparedOwner(cmakeIdentityFixture(),request,state,leases,leases.reserve(request),WorkspacePathMapper(source,source.path)){true}
            assertTrue(owner.observeSelection())
            File(state.localRoot,"build/CMakeCache.txt").writeText("unexpected")
            assertThrows(IllegalStateException::class.java){owner.observeSelection()}
            owner.retire()
        }finally{app.deleteRecursively()}
    }
    @Test fun incompleteGroupLeavesResidueAndFreshOwnerGetsDifferentPath() = runBlocking<Unit> {
        val app=CMakeHostOs.temporaryRoot("cmake-residue")
        try {
            val state=CMakePrivateState.allocate(app,"environment")
            assertEquals(CMakePrivateCleanupStatus.QUARANTINED,state.cleanup(false))
            assertTrue(state.localRoot.isDirectory)
            val fresh=CMakePrivateState.allocate(app,"environment")
            assertNotEquals(state.localRoot,fresh.localRoot)
            assertEquals(CMakePrivateCleanupStatus.COMPLETE,fresh.cleanup(true))
        }finally{app.deleteRecursively()}
    }
    @Test fun noFollowSymlinkAndDirectoryReplacementAreRejected() {
        val app=CMakeHostOs.temporaryRoot("cmake-private-identity")
        try {
            val state=CMakePrivateState.allocate(app,"environment")
            val build=File(state.localRoot,"build");assertTrue(build.renameTo(File(state.localRoot,"old")));assertTrue(build.mkdir())
            android.system.Os.chmod(build.path,448)
            assertThrows(IllegalStateException::class.java){state.verify()}
            val alias=File(app,"alias");Files.createSymbolicLink(alias.toPath(),state.localRoot.toPath())
            assertThrows(IllegalArgumentException::class.java){CMakeSelectionInspector.directoryIdentity(alias,"environment")}
        }finally{app.deleteRecursively()}
    }
    @Test fun concurrentFailedAllocationsNeverExceedQuarantineCapacity() = runBlocking<Unit> {
        val app=CMakeHostOs.temporaryRoot("cmake-quarantine-cap")
        try {
            val active=(0 until 2).map{CMakePrivateState.allocate(app,"environment")}
            val registry=File(app,"run/cmake-private-records")
            repeat(32){File(registry,"fixture-$it.quarantine").writeText("retained authored fixture")}
            active.forEach { assertEquals(CMakePrivateCleanupStatus.QUARANTINED,it.cleanup(false)) }
            assertEquals(32,registry.listFiles()!!.count{it.name.endsWith(".quarantine")})
            assertEquals(2,registry.listFiles()!!.count{it.name.endsWith(".allocation")})
            assertThrows(IllegalStateException::class.java){CMakePrivateState.allocate(app,"environment")}
        }finally{app.deleteRecursively()}
    }
    @Test fun symlinkedRegistryParentIsRejectedBeforeWritingOutsideApp() {
        val app=CMakeHostOs.temporaryRoot("cmake-parent-link")
        val outside=CMakeHostOs.temporaryRoot("cmake-outside")
        try {
            Files.createSymbolicLink(File(app,"run").toPath(),outside.toPath())
            assertThrows(IllegalArgumentException::class.java){CMakePrivateState.allocate(app,"environment")}
            assertThrows(IllegalArgumentException::class.java){CMakeBuildOwnerLeases.forApp(app)}
            assertEquals(0,outside.listFiles()!!.size)
        }finally{app.deleteRecursively();outside.deleteRecursively()}
    }

    @Test fun openedCleanupRootMustMatchOriginalAllocationFileKey() = runBlocking<Unit> {
        val app=CMakeHostOs.temporaryRoot("cmake-original-root-key")
        try {
            val state=CMakePrivateState.allocate(app,"environment")
            val sentinel=File(state.localRoot,"sentinel").apply{writeText("preserve")}
            // Deterministically challenge the retained original-key comparison. This is an authored JVM
            // identity mismatch, not a claim to schedule an Android rename race.
            CMakePrivateState::class.java.getDeclaredField("originalRootFileKey").apply{isAccessible=true}.set(state,Any())
            assertEquals(CMakePrivateCleanupStatus.QUARANTINED,state.cleanup(true))
            assertEquals("preserve",sentinel.readText())
        }finally{app.deleteRecursively()}
    }

}
