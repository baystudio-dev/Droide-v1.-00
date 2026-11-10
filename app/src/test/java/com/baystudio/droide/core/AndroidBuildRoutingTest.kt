package com.baystudio.droide.core

import com.baystudio.droide.core.AndroidDevelopmentManager.BuildBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidBuildRoutingTest {
    @Test fun ubuntuProductionRouteAdmitsAutomaticAndRequiredLocalBuilds() = runBlocking {
        for (requested in listOf(null, BuildBackend.LOCAL_UBUNTU)) {
            val calls = mutableListOf<String>()
            val result = AndroidBuildRouting.executeUbuntu(requested,
                resolveLocal = { calls += "discover"; "selected-ubuntu-jdk-sdk" },
                runLocal = { calls += "build"; it },
            )
            assertEquals("selected-ubuntu-jdk-sdk", result)
            assertEquals(listOf("discover", "build"), calls)
        }
    }

    @Test fun ubuntuProductionRouteDoesNotReplaceMissingToolsWithDeviceBuild() = runBlocking {
        var buildRan = false
        val failure = runCatching {
            AndroidBuildRouting.executeUbuntu(null,
                resolveLocal = { null as String? },
                runLocal = { buildRan = true; it },
                unavailableReason = { "Ubuntu JDK 17 is missing" },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Ubuntu JDK 17 is missing", failure!!.message)
        assertFalse(buildRan)
    }

    @Test fun ubuntuProductionRouteRejectsStaleDeviceRequestBeforeDiscovery() = runBlocking {
        var discoveryRan = false
        val failure = runCatching {
            AndroidBuildRouting.executeUbuntu(BuildBackend.DEVICE_WORKSTATION,
                resolveLocal = { discoveryRan = true; "ubuntu" },
                runLocal = { error("Rejected request must not run any build") },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("builds use Ubuntu"))
        assertFalse(discoveryRan)
    }

    @Test fun deviceCertificationDoesNotProbeOrExecuteAnAvailableLocalToolchain() = runBlocking {
        val result = AndroidBuildRouting.execute(
            BuildBackend.DEVICE_WORKSTATION,
            resolveLocal = { error("Device certification must not discover local tools") },
            runLocal = { _: String -> error("Device evidence must not come from a local build") },
            runDevice = { "device-evidence" },
        )
        assertEquals("device-evidence", result)
    }

    @Test fun automaticBuildUsesHealthyLocalToolsWithoutRequiringADevice() = runBlocking {
        val result = AndroidBuildRouting.execute(null, { "selected-jdk-sdk" }, { it }, { error("No device is connected") })
        assertEquals("selected-jdk-sdk", result)
    }

    @Test fun automaticBuildFallsBackToDeviceWhenLocalToolsAreAbsent() = runBlocking {
        val result = AndroidBuildRouting.execute(null, { null as String? }, { error("No local toolchain") }, { "device-fallback" })
        assertEquals("device-fallback", result)
    }

    @Test fun requiredLocalBackendCannotSilentlyProduceDeviceEvidence() = runBlocking {
        var deviceRan = false
        val failure = runCatching {
            AndroidBuildRouting.execute(BuildBackend.LOCAL_UBUNTU, { null as String? }, { it }, { deviceRan = true; "device" })
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(deviceRan)
    }

    @Test fun canceledDiscoveryDoesNotStartTheFallbackBuild() = runBlocking {
        var deviceRan = false
        val failure = runCatching {
            AndroidBuildRouting.execute(null, { throw CancellationException("stop") }, { _: String -> "local" }, { deviceRan = true; "device" })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(deviceRan)
    }
}
