package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AgentRangeLineEndingsTest {
    @Test fun diskReadFileUsesSearchLineNumbersForCrOnlyFiles() = runBlocking {
        val root = Files.createTempDirectory("range-cr-disk-").toFile()
        try {
            val bytes = "alpha\rbeta\rgamma".toByteArray()
            root.resolve("source.txt").writeBytes(bytes)
            val files = FileRepository(root)
            val hit = AgentWorkspaceSearch(files).capture("beta", 10, TextSearchOptions(), false).hits.single()
            assertEquals(2, hit.line)
            val output = AgentTools.execute("read_file", buildJsonObject {
                put("path", hit.path); put("start", hit.line); put("end", hit.line)
            }, agentWorkflowContext(root))
            assertTrue(output, output.contains("   2│ beta\n"))
            assertTrue(output.contains("next_start=3")); assertFalse(output.contains('\r'))
            assertArrayEquals(bytes, root.resolve("source.txt").readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun liveReadFileUsesSearchLineNumbersWithoutSaving() = runBlocking {
        val root = Files.createTempDirectory("range-cr-live-").toFile()
        try {
            root.resolve("source.txt").writeText("saved")
            val content = "alpha\rbeta\rgamma"
            val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("source.txt", content))
            val hit = AgentWorkspaceSearch(FileRepository(root), docs).capture("beta", 10, TextSearchOptions(), false).hits.single()
            assertEquals(2, hit.line)
            val output = AgentTools.execute("read_file", buildJsonObject {
                put("path", hit.path); put("start", hit.line); put("end", hit.line)
            }, agentWorkflowContext(root, documents=docs))
            assertTrue(output, output.contains("   2│ beta\n"))
            assertTrue(output.contains("next_start=3")); assertFalse(output.contains('\r'))
            assertEquals(content, docs.current.content); assertEquals("saved", root.resolve("source.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun liveCrLfDoesNotExposeRawCarriageReturns() = runBlocking {
        val root = Files.createTempDirectory("range-crlf-live-").toFile()
        try {
            root.resolve("source.txt").writeText("saved")
            val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("source.txt", "alpha\r\nbeta\r\n"))
            val output = AgentWorkspaceReader(FileRepository(root), docs).read("source.txt", 1, 2)
            assertFalse(output, output.contains('\r'))
            assertTrue(output.contains("   2│ beta\n")); assertFalse(output.contains("next_start="))
        } finally { root.deleteRecursively() }
    }

    @Test fun mixedAndTrailingSeparatorsKeepDiskAndLiveRangesAligned() = runBlocking {
        val root = Files.createTempDirectory("range-eol-matrix-").toFile()
        try {
            for (content in listOf("", "alpha", "\r", "\n", "\r\n", "alpha\r", "alpha\n", "alpha\r\n",
                "alpha\r\rbeta", "alpha\r\n\rbeta\n\rgamma\r\n", "\r\r\n", "\n\r", "\r\n\r")) {
                val file = root.resolve("source.txt"); file.writeText(content)
                val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("source.txt", content))
                val files = FileRepository(root)
                val expected = if (content.isEmpty()) emptyList() else content.split(Regex("\r\n|\r|\n")).let {
                    if (content.endsWith('\r') || content.endsWith('\n')) it.dropLast(1) else it
                }
                for (start in 1..expected.size + 1) {
                    for (reader in listOf(AgentWorkspaceReader(files), AgentWorkspaceReader(files, docs))) {
                        val output = reader.read("source.txt", start, start)
                        if (start > expected.size) assertTrue(output, output.contains("beyond end of file"))
                        else {
                            assertTrue(output, output.contains(String.format("%4d│ %s\n", start, expected[start - 1])))
                            assertEquals(output, start < expected.size, output.contains("next_start=${start + 1}"))
                        }
                        assertFalse(output.contains('\r'))
                    }
                }
                assertEquals(content, file.readText()); assertEquals(content, docs.current.content)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun crLfAcrossInputChunkBoundaryCountsOnceAndReportsContinuation() = runBlocking {
        val root = Files.createTempDirectory("range-eol-chunk-").toFile()
        try {
            for (tail in listOf("", "next", "\rnext", "\nnext")) {
                val content = "a".repeat(65_535) + "\r\n" + tail
                root.resolve("source.txt").writeText(content)
                val reader = AgentWorkspaceReader(FileRepository(root))
                val first = reader.read("source.txt", 1, 1)
                assertEquals(tail.isNotEmpty(), first.contains("next_start=2"))
                val second = reader.read("source.txt", 2, 2)
                when (tail) {
                    "" -> assertTrue(second.contains("beyond end of file"))
                    "next" -> assertTrue(second, second.contains("   2│ next\n"))
                    else -> {
                        assertTrue(second, second.contains("   2│ \n")); assertTrue(second.contains("next_start=3"))
                        assertTrue(reader.read("source.txt", 3, 3).contains("   3│ next\n"))
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun terminatorsDoNotConsumeTheDiskLineByteCap() = runBlocking {
        val root = Files.createTempDirectory("range-eol-cap-").toFile()
        try {
            for (ending in listOf("\r", "\n", "\r\n")) {
                root.resolve("source.txt").writeText("b".repeat(2000) + ending)
                val output = AgentWorkspaceReader(FileRepository(root)).read("source.txt", 1, 1)
                assertTrue(output.contains("   1│ " + "b".repeat(2000) + "\n"))
                assertFalse(output.contains("long_line_truncated")); assertFalse(output.contains("next_start="))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun fullReadContinuationUsesLogicalCrAndCrLfLines() = runBlocking {
        val root = Files.createTempDirectory("read-eol-page-").toFile()
        try {
            for (content in listOf("a\r".repeat(6000), "a\r\n".repeat(4000), "a".repeat(9999) + "\r\nnext")) {
                root.resolve("source.txt").writeText(content)
                val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("source.txt", content))
                val expectedLine = content.take(10_000).split(Regex("\r\n|\r|\n")).size
                for (reader in listOf(AgentWorkspaceReader(FileRepository(root)), AgentWorkspaceReader(FileRepository(root), docs))) {
                    val output = reader.read("source.txt")
                    assertTrue(output, output.contains("next_start=$expectedLine next_offset=10000"))
                    assertTrue(reader.read("source.txt", offset=10_000).endsWith(content.substring(10_000)))
                }
            }
        } finally { root.deleteRecursively() }
    }

}
