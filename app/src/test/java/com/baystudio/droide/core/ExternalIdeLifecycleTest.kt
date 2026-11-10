package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExternalIdeLifecycleTest {
    @get:org.junit.Rule val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun replacementRejectsOldMcpGenerationAndOldUnbindCannotRemoveNewProvider() = runBlocking {
        ExternalIdeFixture().use { f ->
            val newer = ExternalIdeToolRegistry.bind(f.binding.workspaceId, f.root, object : ExternalIdeToolRegistry.Provider {
                override val definitions = JsonArray(emptyList())
                override suspend fun call(name: String, arguments: JsonObject) = "new provider"
            })
            try {
                val rejected = f.rpc("tools/list")
                assertTrue(rejected.toString(), rejected.containsKey("error"))
                ExternalIdeToolRegistry.unbind(f.binding)
                assertEquals(newer, ExternalIdeToolRegistry.binding(f.binding.workspaceId))
                assertEquals("new provider", ExternalIdeToolRegistry.use(newer.workspaceId, newer.token) { it.call("test", JsonObject(emptyMap())) })
            } finally { ExternalIdeToolRegistry.unbind(newer) }
        }
    }

    @Test(timeout = 5000) fun retirementRevokesAdmissionBeforeCancellationAndPreservesCallerScope() = runBlocking {
        ExternalIdeFixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val call = async { runCatching { ExternalIdeToolRegistry.use(f.binding.workspaceId) { entered.complete(Unit); awaitCancellation() } }.exceptionOrNull() }
            entered.await()
            val release = ExternalIdeToolRegistry.retire(f.binding)
            assertNull(ExternalIdeToolRegistry.binding(f.binding.workspaceId))
            assertTrue(call.isActive)
            release()
            assertTrue(call.await() is CancellationException)
            assertFalse(call.isCancelled); assertTrue(currentCoroutineContext().isActive)
        }
    }

    @Test fun retiredServiceCannotPublishObservationEvenIfEndpointHasNotClosedYet() = runBlocking {
        ExternalIdeFixture().use { f ->
            f.ownerOpen = false
            val result = f.rpc("tools/call", buildJsonObject { put("name", "droide_read_file"); put("arguments", buildJsonObject { put("path", "sample.kt") }) })["result"]!!.jsonObject
            assertEquals(JsonPrimitive(true), result["isError"]); assertFalse(result.toString().contains("unsaved needle"))
        }
    }

    @Test fun capabilityRestrictionsAffectBothDiscoveryAndDispatch() = runBlocking {
        ExternalIdeFixture(PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)), toolRestrictions = listOf(setOf("read_file")))).use { f ->
            val list = f.rpc("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
            assertEquals(listOf("droide_read_file"), list.map { it.jsonObject["name"]!!.jsonPrimitive.content })
            val result = f.rpc("tools/call", buildJsonObject { put("name", "droide_diagnose"); put("arguments", buildJsonObject { put("path", "sample.kt") }) })["result"]!!.jsonObject
            assertEquals(JsonPrimitive(true), result["isError"]); assertEquals(0, f.lspCalls)
        }
    }

    @Test(timeout = 5000) fun stdioAdapterPreservesJsonAsDataAndNeverEvaluatesShellText() {
        val root = Files.createTempDirectory("ide-stdio-").toFile()
        try {
            val bridge = root.resolve("mock-cli")
            bridge.writeText("#!/bin/sh\n[ \"${'$'}1\" = ide ] && [ \"${'$'}2\" = rpc ] && [ \"${'$'}3\" = generation ] || exit 9\nprintf '%s\\n' \"${'$'}4\"\n")
            assertTrue(bridge.setExecutable(true))
            val payload = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call"); put("text", "${'$'}(touch escaped) `touch escaped` ' quoted \\\" 🌌") }.toString()
            val process = ProcessBuilder("/bin/sh", "-c", ExternalIdeProtocol.stdioScript, "droide-ide-mcp", bridge.absolutePath, "generation").directory(root).start()
            try {
                val writer = process.outputStream.bufferedWriter()
                val reader = process.inputStream.bufferedReader()

                repeat(2) {
                    writer.write(payload + "\n"); writer.flush()
                    assertEquals(payload, reader.readLine())
                }
                writer.close()
                assertTrue(process.waitFor(2, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                assertFalse(root.resolve("escaped").exists())
            } finally { process.destroyForcibly() }
            val tooLarge = ProcessBuilder("/bin/sh", "-c", ExternalIdeProtocol.stdioScript, "droide-ide-mcp", bridge.absolutePath, "generation").directory(root).start()
            try {
                tooLarge.outputStream.bufferedWriter().use { it.write("x".repeat(4097) + "\n") }
                assertTrue(tooLarge.waitFor(2, TimeUnit.SECONDS)); assertEquals(2, tooLarge.exitValue())
                assertEquals("", tooLarge.inputStream.bufferedReader().readText())
            } finally { tooLarge.destroyForcibly() }
        } finally { assertTrue(PathSecurity.deleteTreeNoFollow(root)) }
    }
}
