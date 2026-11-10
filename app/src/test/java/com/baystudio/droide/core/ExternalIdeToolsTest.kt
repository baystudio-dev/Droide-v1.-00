package com.baystudio.droide.core

import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun unusedIdePackages(): UnifiedPackageAuthority = Proxy.newProxyInstance(
    UnifiedPackageAuthority::class.java.classLoader, arrayOf(UnifiedPackageAuthority::class.java)
) { _, method, _ -> error("Unexpected package operation: ${method.name}") } as UnifiedPackageAuthority

internal class ExternalIdeFixture(val permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), includeSkills: Boolean = false, includeMedia: Boolean = false) : AutoCloseable {
    val root = Files.createTempDirectory("external-ide-").toFile()
    val documents = AgentWorkflowDocuments(agentWorkflowSnapshot("sample.kt", "unsaved needle\n"))
    val approvals = ApprovalManager()
    var lspText = "definition sample.kt:1"
    var lspCalls = 0
    var ownerOpen = true
    val lsp = object : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override suspend fun diagnose(relPath: String): String { lspCalls++; return lspText }
        override suspend fun lsp(op: String, path: String, line: Int, col: Int): String { lspCalls++; return lspText }
    }
    val provider = ExternalIdeTools(FileRepository(root), documents, lsp, permissions, approvals,
        requireOwner = { check(ownerOpen) { "Owner retired" } },
        handbook = { "## 0. Index\nfixture help\n\n## 1. Identity\nfixture identity\n" }, status = { "IDLE" },
        skills = if (includeSkills) SkillManagement(FileRepository(root), personalRoot = root.resolve("personal")) else null,
        media = if (includeMedia) MediaAuthoringTools(FileRepository(root), MediaTestTerminal(), ::mediaWorker, requireCurrent = { check(ownerOpen) }) else null)
    val binding: ExternalIdeToolRegistry.Binding
    init {
        root.resolve("sample.kt").writeText("old disk text\n")
        binding = ExternalIdeToolRegistry.bind("ide-fixture-${root.name}", root, provider)
    }
    suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap())) = ExternalIdeToolRegistry.use(binding.workspaceId) { it.call(name, args) }
    suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap()), id: Int = 1): JsonObject = Json.parseToJsonElement(
        ExternalIdeProtocol.rpc(binding.workspaceId, binding.token, buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }.toString())).jsonObject
    override fun close() { ExternalIdeToolRegistry.unbind(binding); check(PathSecurity.deleteTreeNoFollow(root)) }
}

class ExternalIdeToolsTest {
    @get:org.junit.Rule val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun catalogExposesIdeObservationsWithoutNativeMutationOrCredentialTools() = runBlocking {
        ExternalIdeFixture().use { f ->
            val definitions = f.rpc("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
            assertEquals(8, definitions.size)
            val names = definitions.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertTrue(names.containsAll(setOf("droide_read_file", "droide_search_files", "droide_diagnose", "droide_lsp", "droide_help", "droide_ide_status")))
            assertTrue(names.none { it.contains("write") || it.contains("command") || it.contains("generate_image") || it.contains("task_control") })
            assertTrue(definitions.all { it.jsonObject["annotations"]!!.jsonObject["readOnlyHint"] == JsonPrimitive(true) })
            val help = definitions.single { it.jsonObject["name"] == JsonPrimitive("droide_help") }.jsonObject
            assertFalse(help["inputSchema"]!!.jsonObject["properties"]!!.jsonObject.containsKey("screenshot"))
        }
    }

    @Test fun terminalCliAndMcpReadTheSameUnsavedBufferWithoutSavingIt() = runBlocking {
        ExternalIdeFixture().use { f ->
            val args = buildJsonObject { put("path", "sample.kt") }
            val cli = DroideCliTool(unusedIdePackages(), f.binding.workspaceId)
            val direct = cli.executeResult(listOf("ide", "call", "droide_read_file", args.toString()))
            assertEquals(0, direct.exitCode)
            val rpc = f.rpc("tools/call", buildJsonObject { put("name", "droide_read_file"); put("arguments", args) })["result"]!!.jsonObject
            assertEquals(false, rpc["isError"]!!.jsonPrimitive.boolean)
            assertEquals(direct.stdout, rpc["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertTrue(direct.stdout.contains("unsaved needle")); assertTrue(direct.stdout.contains("dirty=true"))
            assertTrue(direct.stdout.contains("CLI working copy may differ"))
            assertFalse(direct.stdout.contains("old disk text")); assertEquals("old disk text\n", f.root.resolve("sample.kt").readText())
        }
    }

    @Test fun searchUsesDirtyOverlayAndNativeContinuationTokens() = runBlocking {
        ExternalIdeFixture().use { f ->
            f.documents.current = agentWorkflowSnapshot("sample.kt", (1..120).joinToString("\n") { "needle $it " + "x".repeat(160) })
            val args = buildJsonObject { put("query", "needle"); put("max_results", 120) }
            val first = f.call("droide_search_files", args)
            assertTrue(first.contains("dirty_overlays=1")); assertTrue(first.contains("SEARCH_RESULT_PAGE"))
            val metadata = Json.parseToJsonElement(first.substringAfter("SEARCH_RESULT_PAGE\n").lineSequence().first()).jsonObject
            val second = f.call("droide_search_files", JsonObject(args + mapOf("result_id" to metadata.getValue("result_id"), "offset" to metadata.getValue("next_offset"))))
            assertTrue(second.contains("SEARCH_RESULT_PAGE")); assertFalse(second.contains("sample.kt:1:"))
            f.documents.current = agentWorkflowSnapshot("sample.kt", "changed needle").copy(changeVersion = 9)
            assertTrue(f.call("droide_search_files", JsonObject(args + mapOf("result_id" to metadata.getValue("result_id"), "offset" to metadata.getValue("next_offset")))).contains("stale"))
        }
    }

    @Test fun lspPagesRemainBoundToTheOriginalSourceVersion() = runBlocking {
        ExternalIdeFixture().use { f ->
            f.lspText = "definition ".repeat(1200)
            val args = buildJsonObject { put("path", "sample.kt"); put("operation", "goToDefinition") }
            val first = f.call("droide_lsp", args)
            assertTrue(first, first.contains("LSP_RESULT_PAGE"))
            val metadata = Json.parseToJsonElement(first.substringAfter("LSP_RESULT_PAGE\n").lineSequence().first()).jsonObject
            val more = JsonObject(args + mapOf("result_id" to metadata.getValue("result_id"), "offset" to metadata.getValue("next_offset")))
            assertTrue(f.call("droide_lsp", more).contains("LSP_RESULT_PAGE")); assertEquals(1, f.lspCalls)
            f.documents.current = f.documents.current.copy(changeVersion = 10)
            assertTrue(f.call("droide_lsp", more).contains("stale")); assertEquals(1, f.lspCalls)
        }
    }

    @Test fun unavailableLanguageProviderIsAnMcpErrorNotSuccessfulDiagnosis() = runBlocking {
        ExternalIdeFixture().use { f ->
            f.lspText = "ERROR: Analysis unavailable"
            val result = f.rpc("tools/call", buildJsonObject { put("name", "droide_diagnose"); put("arguments", buildJsonObject { put("path", "sample.kt") }) })["result"]!!.jsonObject
            assertEquals(JsonPrimitive(true), result["isError"])
        }
    }

    @Test fun fileTextThatSaysErrorIsStillAReadResult() = runBlocking {
        ExternalIdeFixture().use { f ->
            f.documents.current = agentWorkflowSnapshot("sample.kt", "ERROR: content, not execution failure")
            assertFalse(AgentToolResultSemantics.parseAnnotation(f.call("droide_read_file", buildJsonObject { put("path", "sample.kt") }))!!.isFailure)
        }
    }

    @Test fun privateTraversalAndAliasPathsCannotReadHostState() = runBlocking {
        ExternalIdeFixture().use { f ->
            f.root.resolve(".droide").mkdir(); f.root.resolve(".droide/state").writeText("private content")
            Files.createSymbolicLink(f.root.resolve("alias").toPath(), f.root.resolve(".droide/state").toPath())
            for (path in listOf("../outside", "/etc/passwd", ".droide/state", "alias", ".git/config", "local.properties")) {
                val result = f.rpc("tools/call", buildJsonObject { put("name", "droide_read_file"); put("arguments", buildJsonObject { put("path", path) }) })["result"]!!.jsonObject
                assertEquals(path, JsonPrimitive(true), result["isError"])
                assertFalse(result.toString().contains("private content"))
            }
        }
    }

    @Test fun scopedInstructionDenialStopsBeforeReturningRestrictedContent() = runBlocking {
        ExternalIdeFixture(PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("read", "AGENTS.md", PermEffect.DENY)))).use { f ->
            f.root.resolve("AGENTS.md").writeText("protected instruction")
            val result = f.rpc("tools/call", buildJsonObject { put("name", "droide_project_instructions"); put("arguments", buildJsonObject { put("path", "sample.kt") }) })["result"]!!.jsonObject
            assertEquals(JsonPrimitive(true), result["isError"]); assertFalse(result.toString().contains("protected instruction"))
        }
    }

    @Test fun malformedAndMutationRequestsNeverReachAnalysis() = runBlocking {
        ExternalIdeFixture().use { f ->
            val bad = listOf(
                "droide_lsp" to buildJsonObject { put("path", "sample.kt"); put("operation", "rename") },
                "droide_read_file" to buildJsonObject { put("path", "sample.kt"); put("offset", "0") },
                "droide_read_file" to buildJsonObject { put("path", "sample.kt"); put("workspace", "other") },
                "write_file" to buildJsonObject { put("path", "sample.kt"); put("content", "bad") },
            )
            for ((name, args) in bad) assertEquals(JsonPrimitive(true), f.rpc("tools/call", buildJsonObject { put("name", name); put("arguments", args) })["result"]!!.jsonObject["isError"])
            assertEquals(0, f.lspCalls); assertEquals("old disk text\n", f.root.resolve("sample.kt").readText())
        }
    }

    @Test fun helpAndStatusAreOnDemandWithoutPackageOrModelCalls() = runBlocking {
        ExternalIdeFixture().use { f ->
            assertTrue(f.call("droide_help", buildJsonObject { put("section", "identity") }).contains("fixture identity"))
            assertTrue(f.call("droide_ide_status").contains("IDLE"))
            val cli = DroideCliTool(unusedIdePackages(), f.binding.workspaceId)
            assertTrue(cli.executeResult(listOf("--help")).stdout.contains("droide ide"))
            assertEquals(0, cli.executeResult(listOf("ide", "--help")).exitCode)
            assertTrue(DroideCli.handle(listOf("ide", "tools"), f.root) is DroideCli.Result.Pkg)
            assertEquals(69, DroideCliTool(unusedIdePackages(), "other-workspace").executeResult(listOf("ide", "tools")).exitCode)
        }
    }

    @Test fun protocolNegotiationNotificationsInvalidIdsAndUnknownMethodsAreBounded() = runBlocking {
        ExternalIdeFixture().use { f ->
            assertEquals(JsonPrimitive("2024-11-05"), f.rpc("initialize", buildJsonObject { put("protocolVersion", "2024-11-05") })["result"]!!.jsonObject["protocolVersion"])
            assertEquals(JsonPrimitive("2025-06-18"), f.rpc("initialize", buildJsonObject { put("protocolVersion", "future") })["result"]!!.jsonObject["protocolVersion"])
            assertEquals(JsonPrimitive(-32601), f.rpc("resources/list")["error"]!!.jsonObject["code"])
            val notification = """{"jsonrpc":"2.0","method":"tools/call","params":{"name":"droide_diagnose","arguments":{"path":"sample.kt"}}}"""
            assertEquals("", ExternalIdeProtocol.rpc(f.binding.workspaceId, f.binding.token, notification)); assertEquals(0, f.lspCalls)
            for (invalid in listOf("[]", "{", "x".repeat(4097), """{"jsonrpc":"2.0","id":true,"method":"ping"}"""))
                assertTrue(Json.parseToJsonElement(ExternalIdeProtocol.rpc(f.binding.workspaceId, f.binding.token, invalid)).jsonObject.containsKey("error"))
        }
    }

    @Test fun terminalMcpConfigPinsGenerationAndNeverWritesUserConfiguration() = runBlocking {
        ExternalIdeFixture().use { f ->
            val result = DroideCliTool(unusedIdePackages(), f.binding.workspaceId).executeResult(listOf("ide", "mcp-config"))
            val config = Json.parseToJsonElement(result.stdout).jsonObject["mcpServers"]!!.jsonObject["droide-ide"]!!.jsonObject
            assertEquals("sh", config["command"]!!.jsonPrimitive.content)
            assertTrue(config["args"]!!.jsonArray.any { it.jsonPrimitive.content == f.binding.token })
            assertEquals(listOf("sample.kt"), f.root.list()!!.toList())
        }
    }
}
