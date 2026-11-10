package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentLiteralWhitespaceTest {
    @Test fun diskLiteralQueryDoesNotMatchTrimmedSubstrings() = runBlocking {
        val root = Files.createTempDirectory("literal-disk-").toFile()
        try {
            val text = "foobar\nx foo y\nfoo\n"
            root.resolve("a.txt").writeText(text)
            val ctx = agentWorkflowContext(root)
            val args = buildJsonObject { put("query", " foo "); put("case_sensitive", true) }
            for (tool in listOf("search_files", "grep")) {
                val output = AgentTools.execute(tool, args, ctx)
                assertTrue(output, output.contains("returned=1"))
                assertTrue(output.contains("a.txt:2: x foo y [column=2]"))
                assertFalse(output.contains("a.txt:1:")); assertFalse(output.contains("a.txt:3:"))
            }
            val direct = FileRepository(root).searchPage(" foo ")
            assertEquals(listOf(2), direct.hits.map { it.line }); assertEquals(2, direct.hits.single().column)
            assertEquals(text, root.resolve("a.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun dirtyLiteralTabsAndSpacesArePreserved() = runBlocking {
        val root = Files.createTempDirectory("literal-dirty-").toFile()
        try {
            root.resolve("a.txt").writeText("stale\tfoo stale")
            val text = "foobar\nx\tfoo y\nfoo\n"
            val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("a.txt", text))
            val output = AgentTools.execute("search_files", buildJsonObject { put("query", "\tfoo ") },
                agentWorkflowContext(root, documents=docs))
            assertTrue(output, output.contains("returned=1")); assertTrue(output.contains("column=2"))
            assertTrue(output.contains("a.txt:2:")); assertTrue(output.contains("dirty_overlays=1"))
            assertFalse(output.contains("stale")); assertEquals(text, docs.current.content)
            assertEquals("stale\tfoo stale", root.resolve("a.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun twoSpacesCanSearchLiteralIndentation() = runBlocking {
        val root = Files.createTempDirectory("literal-indent-").toFile()
        try {
            root.resolve("a.txt").writeText("one space\n  indent\nnone\n")
            val output = AgentTools.execute("grep", buildJsonObject { put("query", "  ") }, agentWorkflowContext(root))
            assertTrue(output, output.contains("returned=1")); assertTrue(output.contains("a.txt:2:   indent [column=1]"))
            assertFalse(output.contains("query too short"))
        } finally { root.deleteRecursively() }
    }

    @Test fun paginationKeepsTheExactLiteralQueryAndMatchColumns() = runBlocking {
        val root = Files.createTempDirectory("literal-pages-").toFile()
        try {
            root.resolve("a.txt").writeText((1..120).joinToString("\n") { "foobar x foo " + "z".repeat(130) })
            val ctx = agentWorkflowContext(root)
            var token: JsonObject? = null
            val lines = mutableListOf<Int>()
            var pages = 0
            do {
                val output = AgentTools.execute(if (pages == 0) "search_files" else "grep", buildJsonObject {
                    put("query", " foo "); put("max_results", 500)
                    token?.let { put("result_id", it.getValue("result_id")); put("offset", it.getValue("next_offset")) }
                }, ctx)
                val raw = AgentToolResultSemantics.stripAnnotation(output)
                assertTrue(raw, raw.startsWith("SEARCH_RESULT_PAGE\n"))
                token = Json.parseToJsonElement(raw.lineSequence().drop(1).first()).jsonObject
                assertEquals(120, token!!.getValue("captured_matches").jsonPrimitive.int)
                val hits = Regex("(?m)^a\\.txt:(\\d+):.*\\[column=(\\d+)\\]$").findAll(raw).toList()
                assertTrue(hits.isNotEmpty()); assertTrue(hits.all { it.groupValues[2] == "9" })
                lines += hits.map { it.groupValues[1].toInt() }
                pages++; assertTrue(pages <= 10)
            } while (token!!.getValue("more_output").jsonPrimitive.boolean)
            assertTrue(pages > 1); assertEquals((1..120).toList(), lines)
            ctx.searchResultPages.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun literalWhitespaceRespectsCaseWholeWordAndGlobOptions() = runBlocking {
        val root = Files.createTempDirectory("literal-options-").toFile()
        try {
            root.resolve("a.kt").writeText(" Foo \n foo \nFoo\n")
            root.resolve("b.txt").writeText(" Foo \n")
            val output = AgentTools.execute("search_files", buildJsonObject {
                put("query", " Foo "); put("case_sensitive", true); put("whole_word", true); put("include_glob", "*.kt")
            }, agentWorkflowContext(root))
            assertTrue(output, output.contains("returned=1")); assertTrue(output.contains("a.kt:1:  Foo  [column=1]"))
            assertFalse(output.contains("b.txt:")); assertFalse(output.contains("a.kt:2:")); assertFalse(output.contains("a.kt:3:"))
        } finally { root.deleteRecursively() }
    }

    @Test fun unicodeWhitespaceIsPartOfTheLiteral() = runBlocking {
        val root = Files.createTempDirectory("literal-unicode-space-").toFile()
        try {
            root.resolve("a.txt").writeText("foo\nx\u00a0foo\u00a0y\nx foo y\n")
            val page = FileRepository(root).searchPage("\u00a0foo\u00a0")
            assertEquals(listOf(2), page.hits.map { it.line }); assertEquals(2, page.hits.single().column)
        } finally { root.deleteRecursively() }
    }

    @Test fun literalLengthLimitsRemainBoundedWithoutTrimming() = runBlocking {
        val root = Files.createTempDirectory("literal-limits-").toFile()
        try {
            root.resolve("a.txt").writeText("  indent\n")
            val reader = AgentWorkspaceSearch(FileRepository(root))
            for (query in listOf("", " ", "\t", "x")) assertEquals("(query too short)", reader.search(query))
            assertFalse(reader.search(" a").contains("query too short"))
            assertTrue(FileRepository(root).searchPage(" ".repeat(253) + "foo").hits.isEmpty())
            assertTrue(runCatching { FileRepository(root).searchPage(" ".repeat(254) + "foo") }.isFailure)
            val oversized = FileRepository(root).searchPage(" ".repeat(4097))
            assertTrue(oversized.scanLimitReached); assertTrue(oversized.hits.isEmpty())
            assertTrue(oversized.searchStopReasons.contains(WorkspaceSearchStopReason.INPUT_LIMIT))
        } finally { root.deleteRecursively() }
    }

}
