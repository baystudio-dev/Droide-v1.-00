package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun searchPageMetadata(output: String): JsonObject {
    val raw = AgentToolResultSemantics.stripAnnotation(output)
    assertTrue("Missing search continuation: ${raw.take(160)}", raw.startsWith("SEARCH_RESULT_PAGE\n"))
    return Json.parseToJsonElement(raw.lineSequence().drop(1).first()).jsonObject
}

internal fun searchPageArgs(page: JsonObject? = null, query: String = "needle", max: Int = 500) = buildJsonObject {
    put("query", query); put("max_results", max)
    page?.let { put("result_id", it.getValue("result_id")); put("offset", it.getValue("next_offset")) }
}

internal fun searchPageText(count: Int = 120) = (1..count).joinToString("\n") { "needle item-$it " + "x".repeat(130) }

class AgentSearchResultDeliveryTest {
    private suspend fun fixture(body: suspend (File, AgentTools.Ctx) -> Unit) {
        val root = Files.createTempDirectory("droide-search-pages-").toFile()
        try {
            root.resolve("a.kt").writeText(searchPageText())
            body(root, agentWorkflowContext(root))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun allCapturedMatchesArriveExactlyOnceAcrossAliasPages() = runBlocking { fixture { _, c ->
        val lines = mutableListOf<Int>()
        var output = AgentTools.execute("search_files", searchPageArgs(), c)
        var pages = 0
        while (true) {
            val page = searchPageMetadata(output)
            assertTrue(output.length < 10_000)
            assertEquals(120, page.getValue("captured_matches").jsonPrimitive.int)
            assertFalse(page.getValue("retention_truncated").jsonPrimitive.boolean)
            lines += Regex("(?m)^a\\.kt:(\\d+):").findAll(output).map { it.groupValues[1].toInt() }.toList()
            pages++
            if (!page.getValue("more_output").jsonPrimitive.boolean) break
            assertTrue(pages < 10)
            output = AgentTools.execute("grep", searchPageArgs(page), c)
        }
        assertEquals((1..120).toList(), lines)
        assertTrue(pages > 1)
    } }

    @Test fun continuationDoesNotRecaptureDirtyBuffersOrSearchNewFiles() = runBlocking { fixture { root, _ ->
        var captures = 0
        val live = agentWorkflowSnapshot("a.kt", searchPageText())
        val docs = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String) = live.takeIf { it.path == path }
            override suspend fun snapshots() = listOf(live)
            override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture {
                captures++; return request.captureFiniteSnapshots(listOf(live))
            }
        }
        val c = agentWorkflowContext(root, documents = docs)
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        root.resolve("new.kt").writeText("needle newly created")
        val next = AgentTools.execute("search_files", searchPageArgs(first), c)
        assertEquals(1, captures)
        assertFalse(next.contains("new.kt:"))
        assertTrue(next.contains("New matches need a new search"))
        assertEquals(0L, searchPageMetadata(next).getValue("validation_bytes").jsonPrimitive.long)
    } }

    @Test fun sameSizeDiskEditWithRestoredTimestampInvalidatesContinuation() = runBlocking { fixture { root, c ->
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        val path = root.resolve("a.kt").toPath()
        val modified = Files.getLastModifiedTime(path)
        root.resolve("a.kt").writeText(searchPageText().replace("needle", "change"))
        Files.setLastModifiedTime(path, modified)
        val next = AgentTools.execute("search_files", searchPageArgs(first), c)
        assertTrue(next.startsWith("ERROR: Search result is stale"))
        assertFalse(next.contains("a.kt:"))
        assertTrue(root.resolve("a.kt").readText().startsWith("change"))
        assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).contains("expired"))
    } }

    @Test fun dirtyBufferChangesAreNotReplacedByOldDiskMatches() = runBlocking { fixture { root, _ ->
        val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("a.kt", searchPageText()))
        val c = agentWorkflowContext(root, documents = docs)
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        docs.current = docs.current.copy(content = "unsaved user changes", changeVersion = 4)
        val next = AgentTools.execute("search_files", searchPageArgs(first), c)
        assertTrue(next.startsWith("ERROR: Search result is stale"))
        assertEquals("unsaved user changes", docs.current.content)
        assertEquals(searchPageText(), root.resolve("a.kt").readText())
    } }

    @Test fun openingADirtyBufferInvalidatesCapturedDiskResults() = runBlocking { fixture { root, _ ->
        val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("other.kt", "other"))
        val c = agentWorkflowContext(root, documents = docs)
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        docs.current = agentWorkflowSnapshot("a.kt", "new unsaved")
        assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).startsWith("ERROR: Search result is stale"))
    } }

    @Test fun deletedOrRenamedPathRejectsCapturedMatches() = runBlocking { fixture { root, c ->
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        Files.move(root.resolve("a.kt").toPath(), root.resolve("renamed.kt").toPath())
        assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).contains("stale"))
        assertEquals(searchPageText(), root.resolve("renamed.kt").readText())
    } }

    @Test fun replacingFileWithSymlinkCannotServeCapturedText() = runBlocking { fixture { root, c ->
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        Files.move(root.resolve("a.kt").toPath(), root.resolve("other.kt").toPath())
        Files.createSymbolicLink(root.resolve("a.kt").toPath(), root.resolve("other.kt").toPath())
        assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).startsWith("ERROR: Search result is stale"))
    } }

    @Test fun requestOptionsCannotBeChangedForAnExistingCursor() = runBlocking { fixture { _, c ->
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        for (change in listOf("query" to JsonPrimitive("other"), "include_glob" to JsonPrimitive("*.kt"),
            "whole_word" to JsonPrimitive(true), "case_sensitive" to JsonPrimitive(true), "max_results" to JsonPrimitive(400),
            "include_dependencies" to JsonPrimitive(true))) {
            val args = JsonObject(searchPageArgs(first) + change)
            assertTrue(AgentTools.execute("search_files", args, c).startsWith("ERROR:"))
        }
        assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).startsWith("SEARCH_RESULT_PAGE"))
    } }

    @Test fun unknownCursorDoesNotSilentlyStartANewSearch() = runBlocking { fixture { _, c ->
        val args = JsonObject(searchPageArgs() + ("result_id" to JsonPrimitive("00000000-0000-0000-0000-000000000000")))
        val result = AgentTools.execute("search_files", args, c)
        assertTrue(result.startsWith("ERROR:")); assertFalse(result.contains("a.kt:"))
    } }

    @Test fun malformedContinuationArgumentsAreRejected() = runBlocking { fixture { _, c ->
        for (arg in listOf("offset" to JsonPrimitive(1), "offset" to JsonPrimitive("0"),
            "result_id" to JsonNull, "max_results" to JsonPrimitive("100"), "whole_word" to JsonPrimitive("true"))) {
            assertTrue("arg=$arg", AgentTools.execute("search_files", JsonObject(searchPageArgs() + arg), c).startsWith("ERROR:"))
        }
    } }

    @Test fun cappedSearchStaysIncompleteEvenOnFinalOutputPage() = runBlocking { fixture { _, c ->
        var output = AgentTools.execute("search_files", searchPageArgs(max = 100), c)
        while (true) {
            val page = searchPageMetadata(output)
            assertTrue(output.contains("result_limit=true"))
            if (!page.getValue("more_output").jsonPrimitive.boolean) break
            output = AgentTools.execute("search_files", searchPageArgs(page, max = 100), c)
        }
        assertTrue(output.contains("Results are incomplete"))
    } }

    @Test fun smallSearchRetainsExistingOutputAndDoesNotModifyFiles() = runBlocking { fixture { root, c ->
        root.resolve("a.kt").writeText("needle small\n")
        assertEquals(AgentWorkspaceSearch(FileRepository(root)).search("needle", 500),
            AgentTools.execute("search_files", searchPageArgs(), c))
        assertEquals("needle small\n", root.resolve("a.kt").readText())
    } }

    @Test fun bothCatalogNamesAdvertiseContinuation() {
        for (name in listOf("search_files", "grep")) {
            val tool = AgentTools.definitions.map { it.jsonObject.getValue("function").jsonObject }
                .first { it.getValue("name").jsonPrimitive.content == name }
            val props = tool.getValue("parameters").jsonObject.getValue("properties").jsonObject
            assertTrue(props.containsKey("result_id")); assertTrue(props.containsKey("offset"))
        }
    }
}
