package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExternalToolchainWorkflowTest {
    @get:org.junit.Rule val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private class Fixture(val permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))) : AutoCloseable {
        val reads = ExternalIdeFixture(permissions)
        val events = mutableListOf<String>()
        val arguments = mutableListOf<JsonObject>()
        val license = licenseFixture()
        val provider = ExternalIdeTools(FileRepository(reads.root), reads.documents, reads.lsp, permissions, reads.approvals,
            requireOwner = { check(reads.ownerOpen) }, toolchain = { args, provenance, current ->
                arguments += args
                ToolchainInstallConsent.install(permissions, reads.approvals, "install:sdk@36:${license.sha256}", "install SDK 36",
                    "SDK 36; exact artifact fixture", license, false, "external-cli-ide", provenance, current,
                    acceptLicense = { events += "accept" }, install = { events += "install"; "TOOLCHAIN_INSTALL_RESULT\nsuccess=true" })
            })
        val binding = ExternalIdeToolRegistry.bind(reads.binding.workspaceId, reads.root, provider)
        val cli = DroideCliTool(unusedIdePackages(), binding.workspaceId)
        fun request(args: JsonObject = buildJsonObject { put("operation", "install"); put("family", "sdk.android"); put("version", "36") }) =
            buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                put("params", buildJsonObject { put("name", "droide_toolchain"); put("arguments", args) }) }.toString()
        suspend fun rpc(args: JsonObject? = null) = Json.parseToJsonElement(ExternalIdeProtocol.rpc(binding.workspaceId, binding.token,
            args?.let(::request) ?: request())).jsonObject
        override fun close() { ExternalIdeToolRegistry.unbind(binding); reads.close() }
    }

    @Test fun discoveryMarksSetupAsMutationAndDoesNotAdvertiseItWithoutAuthority() {
        Fixture().use { f ->
            val definition = f.provider.definitions.map { it.jsonObject }.single { it["name"] == JsonPrimitive("droide_toolchain") }
            assertEquals(JsonPrimitive(false), definition["annotations"]!!.jsonObject["readOnlyHint"])
            assertFalse(definition["inputSchema"]!!.jsonObject["properties"]!!.jsonObject.containsKey("accept_license"))
            ExternalIdeFixture().use { assertFalse(it.provider.definitions.toString().contains("droide_toolchain")) }
        }
    }

    @Test(timeout = 7000) fun terminalPackageInstallUsesApprovalAndResumesTheSameCall() = runBlocking {
        Fixture().use { f ->
            val work = async(Dispatchers.Default) { f.cli.executeResult(listOf("pkg", "install", "sdk.android@36")) }
            val request = requireNotNull(f.reads.approvals.pending.first { it != null })
            assertEquals(ToolchainInstallConsent.KIND, request.kind); assertTrue(f.events.isEmpty())
            assertEquals("sdk.android", f.arguments.single()["family"]!!.jsonPrimitive.content)
            assertEquals("36", f.arguments.single()["version"]!!.jsonPrimitive.content)
            f.reads.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE)
            val result = work.await()
            assertEquals(result.stderr, 0, result.exitCode); assertTrue(result.stdout.contains("success=true"))
            assertEquals(listOf("accept", "install"), f.events)
        }
    }

    @Test(timeout = 7000) fun mcpInstallDenialReturnsAnErrorWithoutAcceptingOrInstalling() = runBlocking {
        Fixture().use { f ->
            val work = async(Dispatchers.Default) { f.rpc() }
            val request = requireNotNull(f.reads.approvals.pending.first { it != null })
            f.reads.approvals.resolve(request.id, ApprovalResolution.DENY)
            assertEquals(JsonPrimitive(true), work.await()["result"]!!.jsonObject["isError"])
            assertTrue(f.events.isEmpty())
        }
    }

    @Test fun toolRestrictionCannotFallBackToUnreviewedPackageAuthority() = runBlocking {
        Fixture(PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)), toolRestrictions = listOf(setOf("read_file")))).use { f ->
            assertFalse(f.provider.definitions.toString().contains("droide_toolchain"))
            assertNotEquals(0, f.cli.executeResult(listOf("pkg", "install", "sdk.android@36")).exitCode)
            assertTrue(f.arguments.isEmpty()); assertTrue(f.events.isEmpty())
        }
    }

    @Test fun modelSuppliedConsentFlagIsRejectedBeforeDispatch() = runBlocking {
        Fixture().use { f ->
            val result = f.rpc(buildJsonObject { put("operation", "install"); put("accept_license", true) })
            assertEquals(JsonPrimitive(true), result["result"]!!.jsonObject["isError"])
            assertTrue(f.arguments.isEmpty()); assertTrue(f.events.isEmpty())
        }
    }

    @Test(timeout = 7000) fun workspaceRetirementCancelsPendingLicenseAndPreventsLateCommit() = runBlocking {
        Fixture().use { f ->
            val work = async(Dispatchers.Default) { runCatching { f.rpc() } }
            val request = requireNotNull(f.reads.approvals.pending.first { it != null })
            ExternalIdeToolRegistry.unbind(f.binding)
            assertTrue(work.await().exceptionOrNull() is CancellationException)
            assertNull(f.reads.approvals.pending.value)
            assertFalse(f.reads.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
            assertTrue(f.events.isEmpty())
        }
    }

    @Test fun longInstallationIsNotKilledByTheObservationTimeout() = runTest {
        val root = Files.createTempDirectory("long-toolchain-").toFile()
        val binding = ExternalIdeToolRegistry.bind("long-toolchain", root, object : ExternalIdeToolRegistry.Provider {
            override val definitions = JsonArray(emptyList())
            override suspend fun call(name: String, arguments: JsonObject): String { delay(125_000); return "completed" }
        })
        try {
            assertEquals("completed", ExternalIdeToolRegistry.call(binding.workspaceId, binding.token, "droide_toolchain", JsonObject(emptyMap())))
            assertTrue(runCatching { ExternalIdeToolRegistry.call(binding.workspaceId, binding.token, "droide_read_file", JsonObject(emptyMap())) }
                .exceptionOrNull() is TimeoutCancellationException)
        } finally { ExternalIdeToolRegistry.unbind(binding); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun boundTerminalCannotInstallAfterWorkspaceRetirement() = runBlocking {
        val cli = DroideCliTool(unusedIdePackages(), "no-workspace")
        val result = cli.executeResult(listOf("pkg", "install", "sdk.android@36"))
        assertNotEquals(0, result.exitCode); assertTrue(result.stderr.contains("reopen"))
    }
}
