package com.baystudio.droide.core

import android.app.Application
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CMakeHostOs::class])
class LocalUbuntuCMakeHostContractTest {
    @After fun resetOwnedHostBoundary() = CMakeHostOs.reset()

    @Test fun boundsAreFiniteAndNoRawPidIsReleaseProof() {
        assertEquals(8_000L,LocalUbuntuCMakeLaunchPolicy.GROUP_TEARDOWN_MS)
        assertEquals(8_000L,LocalUbuntuCMakeLaunchPolicy.PRIVATE_CLEANUP_MS)
        assertEquals(1_800_000L,LocalUbuntuCMakeLaunchPolicy.OWNER_WALL_MS)
        val result=CMakeTeardownReceipt(CMakeTeardownStatus.COMPLETE,"lease",1,"nonce","environment",true,true,true,true,false,
            CMakePrivateCleanupStatus.COMPLETE,null,2,50,"ESRCH")
        assertFalse(result.releasesBuild)
    }
    @Test fun duplicateAndMalformedControlRecordsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CMakeControlRecords.strictObject("{\"nonce\":\"a\",\"nonce\":\"b\"}".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { CMakeControlRecords.strictObject("{\"nonce\":\"a\",\"n\\u006fnce\":\"b\"}".toByteArray()) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { CMakeControlRecords.strictObject(byteArrayOf(123,34,120,34,58,34,-1,34,125)) }
    }
    @Test fun readyAndReceiptPrimitiveKindsCannotCoerce() {
        val root=CMakeHostOs.temporaryRoot("cmake-record-kinds")
        try {
            val leases=CMakeBuildOwnerLeases.forApp(root);val lease=leases.reserve(CMakeOwnerRequest(cmakeSelectionFixture(root),1))
            val ready=CMakeControlRecords.readyJson(cmakeReadyFixture(lease))
            assertThrows(IllegalArgumentException::class.java){CMakeControlRecords.parseReady(kotlinx.serialization.json.JsonObject(ready + ("unknown" to kotlinx.serialization.json.JsonPrimitive(true))))}
            val wrong=kotlinx.serialization.json.JsonObject(ready + ("environmentIdentity" to kotlinx.serialization.json.JsonPrimitive(1)))
            assertThrows(IllegalArgumentException::class.java){CMakeControlRecords.parseReady(wrong)}
            val receipt=kotlinx.serialization.json.JsonObject(ready + mapOf(
                "status" to kotlinx.serialization.json.JsonPrimitive("COMPLETE"),
                "postReapGroupObservation" to kotlinx.serialization.json.JsonPrimitive("ESRCH"),
                "directChildReaped" to kotlinx.serialization.json.JsonPrimitive("true"),
                "groupEmpty" to kotlinx.serialization.json.JsonPrimitive(true),
                "forcedTermination" to kotlinx.serialization.json.JsonPrimitive(false),
                "supervisorTransportClosed" to kotlinx.serialization.json.JsonPrimitive(true),
                "preReapScans" to kotlinx.serialization.json.JsonPrimitive(2),
                "minimumSeparationMs" to kotlinx.serialization.json.JsonPrimitive(50)))
            assertThrows(IllegalArgumentException::class.java){CMakeControlRecords.requireReceiptShape(receipt)}
        }finally{root.deleteRecursively()}
    }

    @Test fun typedCleanBuilderUsesOnlyExactOwnerPathsAndEnvironment() = kotlinx.coroutines.runBlocking<Unit> {
        val app=CMakeHostOs.temporaryRoot("cmake-typed-builder")
        try {
            val request=CMakeOwnerRequest(cmakeSelectionFixture(app),1)
            val leases=CMakeBuildOwnerLeases.forApp(app);val state=CMakePrivateState.allocate(app,request.selection.environmentIdentity)
            val owner=LocalCMakePreparedOwner(cmakeIdentityFixture(),request,state,leases,leases.reserve(request),WorkspacePathMapper(app,app.path)){true}
            val loader=mapOf("AUTHORED_HOST_LOADER" to "value")
            val spec=LocalExecutionSubstrate.buildReviewedCMakeLaunchSpec(owner,listOf("/authored/launcher"),java.io.File(app,"rootfs"),app.path,
                java.io.File(app,"runtime-tmp"),java.io.File(app,"packages"),java.io.File(app,"l2s"),loader){ }
            val boundary=spec.prefix.indexOf("/usr/bin/env")
            assertTrue(boundary>0);assertEquals("-i",spec.prefix[boundary+1])
            val guest=spec.prefix.drop(boundary+2).associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals(LocalUbuntuCMakeLaunchPolicy.environment(owner.identity,state),guest)
            assertFalse(spec.prefix.any { it.contains("/opt/droide/bin") || it.contains("/opt/droide/core-bin") })
            assertTrue(spec.prefix.contains("${state.localRoot.path}:${state.guestRoot}"))
            assertEquals(request.selection.sourceGuestAbsolute,spec.prefix[spec.prefix.indexOf("-w")+1])
            assertEquals(loader.getValue("AUTHORED_HOST_LOADER"),spec.environment["AUTHORED_HOST_LOADER"])
            assertTrue(spec.interactiveArgs.isEmpty());assertTrue(spec.interactiveEnvironment.isEmpty())
            owner.retire()
        }finally{app.deleteRecursively()}
    }

}
