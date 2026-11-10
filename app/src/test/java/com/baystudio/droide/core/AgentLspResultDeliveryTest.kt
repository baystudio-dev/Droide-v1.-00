package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class LspDeliveryFixture : AutoCloseable {
    val root = Files.createTempDirectory("droide-lsp-delivery-").toFile()
    var text = "answer"
    var calls = 0
    var respond: (suspend () -> String)? = null
    val manager = object : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override suspend fun diagnose(relPath: String): String { calls++; return respond?.invoke() ?: text }
        override suspend fun lsp(op: String, path: String, line: Int, col: Int): String { calls++; return respond?.invoke() ?: text }
    }
    init { root.resolve("main.kt").writeText("val before = 1\n") }
    fun context(owner: String = "owner", documents: WorkspaceDocumentAuthority? = null,
                permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))): AgentTools.Ctx {
        val files = FileRepository(root)
        return AgentTools.Ctx(files, AgentWorkflowTerminal(), GitManager(root), ApprovalManager(), QuestionManager(),
            EditHistory(files, documents), permissions, AgentMode.BUILD, LlmClient(), AgentConfig(), root,
            TodoManager(), owner, manager, documentAuthority = documents)
    }
    override fun close() { PathSecurity.deleteTreeNoFollow(root) }
}

internal fun lspDeliveryArgs(page: JsonObject? = null, operation: String = "hover", path: String = "main.kt",
                             offset: Int? = null) = buildJsonObject {
    put("path", path); put("operation", operation)
    if (page != null) { put("result_id", page.getValue("result_id")); put("offset", offset ?: page.getValue("next_offset").jsonPrimitive.int) }
}
internal fun lspDeliveryMetadata(output: String): JsonObject {
    assertTrue("Expected explicit LSP page metadata: ${output.take(100)}", output.contains("LSP_RESULT_PAGE\n"))
    return Json.parseToJsonElement(output.substringAfter("LSP_RESULT_PAGE\n").lineSequence().first()).jsonObject
}
internal fun lspDeliveryText(output: String) = output.substringAfter("LSP_PAGE_TEXT\n", "")

class AgentLspResultDeliveryTest {
    @Test fun lspLongResultContinuesWithoutLostUnicodeOrRepeatedQuery(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            f.text = "a".repeat(4999) + "😀" + "b".repeat(7010) + "TAIL"
            val ctx = f.context()
            var output = AgentTools.execute("lsp", lspDeliveryArgs(), ctx)
            val reconstructed = StringBuilder()
            var pages = 0
            while (true) {
                assertTrue(output.length <= 6_000)
                val meta = lspDeliveryMetadata(output)
                reconstructed.append(lspDeliveryText(output)); pages++
                assertFalse(meta.getValue("retention_truncated").jsonPrimitive.boolean)
                assertEquals("not_established", meta.getValue("upstream_completeness").jsonPrimitive.content)
                if (!meta.getValue("more_output").jsonPrimitive.boolean) break
                assertTrue(pages < 10)
                output = AgentTools.execute("lsp", lspDeliveryArgs(meta), ctx)
            }
            assertEquals(f.text, reconstructed.toString())
            assertEquals(1, f.calls)
        }
    }

    @Test fun diagnoseLongResultContinuesWithoutSilentClipping(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            f.text = "diagnostic line\n".repeat(800) + "last diagnostic"
            val ctx = f.context()
            var output = AgentTools.execute("diagnose", lspDeliveryArgs(), ctx)
            val reconstructed = StringBuilder()
            do {
                val meta = lspDeliveryMetadata(output)
                reconstructed.append(lspDeliveryText(output))
                if (!meta.getValue("more_output").jsonPrimitive.boolean) break
                output = AgentTools.execute("diagnose", lspDeliveryArgs(meta), ctx)
            } while (true)
            assertEquals(f.text, reconstructed.toString()); assertEquals(1, f.calls)
        }
    }

    @Test fun schemaAdvertisesTypedContinuationForBothTools() {
        val schema = AgentToolCatalog.definitions.map { it.jsonObject.getValue("function").jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }
        for (name in listOf("lsp", "diagnose")) {
            val props = schema.getValue(name).getValue("parameters").jsonObject.getValue("properties").jsonObject
            assertEquals(JsonPrimitive("string"), props["result_id"]?.jsonObject?.get("type"))
            assertEquals(JsonPrimitive("integer"), props["offset"]?.jsonObject?.get("type"))
        }
    }

    @Test fun locationCapReportsOmittedItems() {
        val location = Json.parseToJsonElement("""{"uri":"file:///workspace/main.kt","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}""")
        val output = LspResultRenderer.render("findReferences", JsonArray(List(101) { location }))
        assertTrue(output.contains("LSP_RESULT_LIMIT")); assertTrue(output.contains("101"))
    }

    @Test fun incompleteCompletionListIsNotReportedAsComplete() {
        val result = Json.parseToJsonElement("""{"isIncomplete":true,"items":[{"label":"answer"}]}""")
        val output = LspResultRenderer.render("completion", result)
        assertTrue(output.contains("answer")); assertTrue(output.contains("incomplete"))
    }

    @Test fun symbolCapReportsOmittedItems() {
        val output = LspResultRenderer.render("documentSymbol", JsonArray(List(201) { buildJsonObject { put("name", "symbol$it") } }))
        assertTrue(output.contains("LSP_RESULT_LIMIT")); assertTrue(output.contains("201"))
    }

    @Test fun diagnosticCapReportsOmittedItems() {
        val items = List(101) { LspDiagnostic(line = 1, column = 1, endLine = 1, endColumn = 2, message = "diagnostic $it", severity = 1, source = "fixture") }
        val output = LspDiagnosticsProtocol.format("main.kt", items)
        assertTrue(output.contains("LSP_RESULT_LIMIT")); assertTrue(output.contains("101"))
    }

    @Test fun shortResultRemainsUnchanged(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            assertEquals("answer", AgentTools.execute("lsp", lspDeliveryArgs(), f.context()))
            assertEquals("answer", AgentTools.execute("diagnose", lspDeliveryArgs(), f.context()))
        }
    }
}
