package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun workspaceSymbolArgs(query: JsonElement? = JsonPrimitive("NeedleSymbol"), page: JsonObject? = null) = buildJsonObject {
    put("operation", "workspaceSymbol"); put("path", "main.py")
    query?.let { put("query", it) }
    page?.let { put("result_id", it.getValue("result_id")); put("offset", it.getValue("next_offset")) }
}

internal class WorkspaceSymbolToolFixture : AutoCloseable {
    val root = Files.createTempDirectory("droide-agent-symbols-").toFile()
    var genericCalls = 0
    val queries = mutableListOf<Pair<String, String>>()
    var response = listOf(LspSymbol("NeedleSymbol", 12, "other.py", 7, 3))
    var queryAction: (suspend () -> Unit)? = null
    var genericResponse = "legacy response"
    val manager = object : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override suspend fun diagnose(relPath: String) = "diagnostic response"
        override suspend fun lsp(op: String, path: String, line: Int, col: Int): String { genericCalls++; return genericResponse }
        override suspend fun workspaceSymbols(pathHint: String, query: String): List<LspSymbol> {
            queries += pathHint to query; queryAction?.invoke(); return response
        }
    }
    init { root.resolve("main.py").writeText("value = 1\n"); root.resolve("other.py").writeText("def NeedleSymbol(): pass\n") }
    fun context(lsp: LspManager = manager, denied: Boolean = false): AgentTools.Ctx {
        val files = FileRepository(root)
        return AgentTools.Ctx(files, AgentWorkflowTerminal(), GitManager(root), ApprovalManager(), QuestionManager(),
            EditHistory(files), PermissionEngine(listOf(PermRule("*", "*", if (denied) PermEffect.DENY else PermEffect.ALLOW))),
            AgentMode.BUILD, LlmClient(), AgentConfig(), root, TodoManager(), "symbols-owner", lsp)
    }
    override fun close() { PathSecurity.deleteTreeNoFollow(root) }
}

class AgentWorkspaceSymbolToolTest {
    @Test fun usesTypedWorkspaceQueryAndKeepsLocations(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            val output = AgentTools.execute("lsp", workspaceSymbolArgs(), f.context())
            assertEquals(listOf("main.py" to "NeedleSymbol"), f.queries)
            assertEquals(0, f.genericCalls)
            val row = Json.parseToJsonElement(output.lineSequence().first { it.startsWith('{') }).jsonObject
            assertEquals(JsonPrimitive("NeedleSymbol"), row["name"])
            assertEquals(JsonPrimitive("other.py"), row["path"])
            assertEquals(JsonPrimitive(7), row["line"]); assertEquals(JsonPrimitive(3), row["col"])
        }
    }

    @Test fun schemaDescribesQueryAndExistingOperations() {
        val tool = AgentToolCatalog.definitions.map { it.jsonObject.getValue("function").jsonObject }
            .single { it["name"] == JsonPrimitive("lsp") }
        val schema = tool.getValue("parameters").jsonObject
        val properties = schema.getValue("properties").jsonObject
        assertEquals(JsonPrimitive("string"), properties["query"]?.jsonObject?.get("type"))
        assertEquals(JsonPrimitive(256), properties["query"]?.jsonObject?.get("maxLength"))
        assertEquals(setOf("hover", "completion", "goToDefinition", "goToTypeDefinition", "findReferences",
            "goToImplementation", "documentSymbol", "workspaceSymbol"),
            properties["operation"]?.jsonObject?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content }?.toSet())
        assertEquals(setOf("operation", "path"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test fun invalidQueryNeverStartsLanguageServer(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            for (q in listOf(null, JsonNull, JsonPrimitive(17), JsonPrimitive(true), JsonArray(emptyList()),
                JsonPrimitive(""), JsonPrimitive(" \n\t "), JsonPrimitive("x".repeat(257)))) {
                val output = AgentTools.execute("lsp", workspaceSymbolArgs(q), f.context())
                assertTrue(output, output.startsWith("ERROR:")); assertTrue(output.contains("query"))
            }
            assertEquals(0, f.genericCalls); assertTrue(f.queries.isEmpty())
        }
    }

    @Test fun continuationRequiresOriginalQueryAndReusesProvider(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            f.genericResponse = "legacy".repeat(2000)
            f.response = List(150) { LspSymbol("NeedleSymbol$it", 12, "other.py", it + 1, 1) }
            val c = f.context()
            var output = AgentTools.execute("lsp", workspaceSymbolArgs(), c)
            var page = lspDeliveryMetadata(output)
            val changed = AgentTools.execute("lsp", workspaceSymbolArgs(JsonPrimitive("DifferentSymbol"), page), c)
            assertTrue(changed, changed.startsWith("ERROR:")); assertFalse(changed.contains("LSP_PAGE_TEXT"))
            val text = StringBuilder(lspDeliveryText(output))
            while (page.getValue("more_output").jsonPrimitive.boolean) {
                output = AgentTools.execute("lsp", workspaceSymbolArgs(page = page), c)
                page = lspDeliveryMetadata(output); text.append(lspDeliveryText(output))
            }
            assertTrue(text.contains("NeedleSymbol149")); assertEquals(1, f.queries.size); assertEquals(0, f.genericCalls)
        }
    }

    @Test fun boundedResultsReportOmittedSymbols(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            f.response = List(205) { LspSymbol("Symbol$it", 12, "other.py", it + 1, 1) }
            val c = f.context(); var output = AgentTools.execute("lsp", workspaceSymbolArgs(), c)
            val text = StringBuilder()
            while (true) {
                assertTrue(output.length <= 6000)
                val page = lspDeliveryMetadata(output); text.append(lspDeliveryText(output))
                if (!page.getValue("more_output").jsonPrimitive.boolean) break
                output = AgentTools.execute("lsp", workspaceSymbolArgs(page = page), c)
            }
            assertTrue(text.contains("LSP_RESULT_LIMIT")); assertTrue(text.contains("205"))
            assertTrue(text.contains("Symbol199")); assertFalse(text.contains("Symbol200"))
            assertEquals(1, f.queries.size)
        }
    }

    @Test fun emptyProviderResultDoesNotClaimExhaustiveSearch(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            f.response = emptyList()
            val output = AgentTools.execute("lsp", workspaceSymbolArgs(), f.context())
            assertTrue(output.contains("completeness is not established"))
            assertTrue(output.contains("unsupported analysis or a stale request"))
            assertFalse(output.contains("(no symbols)")); assertEquals(1, f.queries.size)
        }
    }

    @Test fun expiredActivationGuardCannotBePublished(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            f.response = listOf(f.response.single().copy(activationGuard = LspHeldResultGuard { false }))
            val output = AgentTools.execute("lsp", workspaceSymbolArgs(), f.context())
            assertTrue(output.startsWith("ERROR:")); assertTrue(output.contains("stale"))
            assertFalse(output.contains("other.py"))
        }
    }

    @Test fun providerExceptionDoesNotBecomeSuccess(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            f.queryAction = { error("fixture symbol transport failure") }
            val output = AgentTools.execute("lsp", workspaceSymbolArgs(), f.context())
            assertTrue(output.startsWith("ERROR:")); assertTrue(output.contains("fixture symbol transport failure"))
        }
    }

    @Test fun cancellationPropagatesAndRetainsUserFile(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            f.queryAction = { throw CancellationException("fixture cancellation") }
            val result = runCatching { AgentTools.execute("lsp", workspaceSymbolArgs(), f.context()) }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals("value = 1\n", f.root.resolve("main.py").readText())
        }
    }

    @Test fun changedDocumentDuringSearchCannotPublishResults(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            f.queryAction = { f.root.resolve("main.py").writeText("user edit during search\n") }
            val output = AgentTools.execute("lsp", workspaceSymbolArgs(), f.context())
            assertTrue(output.startsWith("ERROR:")); assertFalse(output.contains("other.py"))
            assertEquals("user edit during search\n", f.root.resolve("main.py").readText())
        }
    }

    @Test fun existingHoverAndDiagnoseRemainUsable(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            val hover = buildJsonObject { put("operation", "hover"); put("path", "main.py") }
            assertEquals("legacy response", AgentTools.execute("lsp", hover, f.context()))
            assertEquals("diagnostic response", AgentTools.execute("diagnose", hover, f.context()))
            assertEquals(1, f.genericCalls); assertTrue(f.queries.isEmpty())
        }
    }

    @Test fun deniedSearchNeverCallsProvider(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            assertTrue(AgentTools.execute("lsp", workspaceSymbolArgs(), f.context(denied = true)).startsWith("DENIED"))
            assertTrue(f.queries.isEmpty()); assertEquals(0, f.genericCalls)
        }
    }

    @Test fun provisioningPresenceCacheIsReused(): Unit = runBlocking {
        WorkspaceSymbolToolFixture().use { f ->
            var presenceChecks = 0
            val wrapped = ProvisioningLspManager(f.manager, { presenceChecks++; true })
            val c = f.context(wrapped)
            AgentTools.execute("lsp", workspaceSymbolArgs(), c)
            AgentTools.execute("lsp", workspaceSymbolArgs(JsonPrimitive("AnotherSymbol")), c)
            assertEquals(listOf("main.py" to "NeedleSymbol", "main.py" to "AnotherSymbol"), f.queries)
            assertEquals(1, presenceChecks)
        }
    }
}
