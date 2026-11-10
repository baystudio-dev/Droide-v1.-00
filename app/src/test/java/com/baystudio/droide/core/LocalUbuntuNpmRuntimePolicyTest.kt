package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuNpmRuntimePolicyTest {
    @Test fun engineConstraintIsPassedAsArgvDataRatherThanShellSource() {
        val range = ">=22.22.2 || >=24; touch /tmp/marker"
        val command = LocalUbuntuNpmRuntimePolicy.command(22, range)
        assertEquals(listOf("node", "-e"), command.take(2))
        assertEquals(listOf("22", range), command.takeLast(2))
        assertFalse(command[2].contains(range))
    }

    @Test fun publisherEngineMetadataAndMissingEngineAreHandledExplicitly() {
        assertNull(LocalUbuntuNpmRuntimePolicy.engine(buildJsonObject {}))
        val metadata = buildJsonObject { put("engines", buildJsonObject { put("node", "^22.13.0 || >=24") }) }
        assertEquals("^22.13.0 || >=24", LocalUbuntuNpmRuntimePolicy.engine(metadata))
    }

    @Test fun malformedOrOversizedEngineArgumentsAreRejectedBeforeLaunch() {
        for (engine in listOf("", "a".repeat(2_001), ">=22\n", ">=22\u0000"))
            assertThrows(IllegalArgumentException::class.java) { LocalUbuntuNpmRuntimePolicy.command(22, engine) }
        for (major in listOf(0, -1, 101))
            assertThrows(IllegalArgumentException::class.java) { LocalUbuntuNpmRuntimePolicy.command(major, null) }
    }

    @Test fun compatibilityRequiresACompatibleLinuxArm64Runtime() {
        fun result(compatible: Boolean, platform: String = "linux", arch: String = "arm64") =
            """{"compatible":$compatible,"platform":"$platform","arch":"$arch"}"""
        assertTrue(LocalUbuntuNpmRuntimePolicy.compatible(result(true)))
        assertFalse(LocalUbuntuNpmRuntimePolicy.compatible(result(false)))
        assertFalse(LocalUbuntuNpmRuntimePolicy.compatible(result(true, "darwin")))
        assertFalse(LocalUbuntuNpmRuntimePolicy.compatible(result(true, arch = "x64")))
        assertTrue(LocalUbuntuNpmRuntimePolicy.compatible(result(true, arch = "x64"), requireArm64 = false))
    }

    @Test fun managedDependencyMustMatchTheExecutableActuallySelectedByPath() {
        val appRoot = "/data/user/0/com.baystudio.droide/files"
        val record = ManagedPackageRecord("runtime.node", "22.23.3", ExecutionScope.LOCAL_LINUX_ARM64.name,
            "$appRoot/packages/runtime.node/22.23.3/arm64-v8a", 1)
        val executable = "/opt/droide/packages/runtime.node/22.23.3/arm64-v8a/payload/tree/node-v22.23.3-linux-arm64/bin/node"
        assertTrue(LocalUbuntuNpmRuntimePolicy.matchesManagedNode(record, executable, appRoot))
        assertFalse(LocalUbuntuNpmRuntimePolicy.matchesManagedNode(record, "/usr/local/bin/node", appRoot))
        assertFalse(LocalUbuntuNpmRuntimePolicy.matchesManagedNode(record, executable.replace("22.23.3", "22.0.0"), appRoot))
        assertFalse(LocalUbuntuNpmRuntimePolicy.matchesManagedNode(record.copy(familyId = "other"), executable, appRoot))
        assertFalse(LocalUbuntuNpmRuntimePolicy.matchesManagedNode(record.copy(scope = "DEVICE_WORKSTATION"), executable, appRoot))
    }

    @Test fun newerOwnedNodeVersionsCanRemainDependenciesWhileOldOrWrongScopeCannot() {
        val recipe = WorkstationInstallRecipe("plugin.codex", "99.0.0", "@agentclientprotocol/codex-acp", "99.0.0", "codex-acp", 22,
            "https://github.com/zed-industries/codex-acp")
        val future = ManagedPackageRecord("runtime.node", "26.9.1", ExecutionScope.LOCAL_LINUX_ARM64.name, "/owned/node26", 1)
        assertTrue(LocalUbuntuNpmRuntimePolicy.admitsDependency(recipe, future))
        assertFalse(LocalUbuntuNpmRuntimePolicy.admitsDependency(recipe, future.copy(version = "20.0.0")))
        assertFalse(LocalUbuntuNpmRuntimePolicy.admitsDependency(recipe, future.copy(scope = "DEVICE_WORKSTATION")))
        assertFalse(LocalUbuntuNpmRuntimePolicy.admitsDependency(recipe, future.copy(version = "26.invalid")))
    }

    @Test fun missingMalformedOrUntrustedProbeOutputCannotApproveARuntime() {
        for (output in listOf("", "[]", "{}", "null", "{\"compatible\":true}",
            "{\"compatible\":\"true\",\"platform\":\"linux\",\"arch\":\"arm64\"}",
            "log\n{\"compatible\":true,\"platform\":\"linux\",\"arch\":\"arm64\"}"))
            assertFalse(output, LocalUbuntuNpmRuntimePolicy.compatible(output))
    }
}
