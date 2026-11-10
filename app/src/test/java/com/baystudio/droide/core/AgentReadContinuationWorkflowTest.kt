package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentReadContinuationWorkflowTest {
    @Test fun actualFileReadCanContainErrorOrDeniedTextWithoutBecomingAToolFailure() = runBlocking {
        val root = Files.createTempDirectory("droide-read-evidence-").toFile()
        try {
            val ctx = agentWorkflowContext(root)
            for (text in listOf("ERROR: sample source text", "DENIED is an example constant", "HTTP 404 sample response", "Unknown tool: documentation")) {
                root.resolve("sample.txt").writeText(text)
                for (tool in listOf("read_file", "read")) {
                    val output = AgentTools.execute(tool, buildJsonObject { put("path", "sample.txt") }, ctx)
                    assertTrue(output.contains(text))
                    assertEquals(AgentToolEvidenceState.SUCCEEDED, AgentToolResultSemantics.classify(tool, output).state)
                }
            }
            val missing = AgentTools.execute("read_file", buildJsonObject { put("path", "missing.txt") }, ctx)
            assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("read_file", missing).state)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun fullReadAdvertisesAnExactOffsetForTheRestOfALongLine() = runBlocking {
        val root = Files.createTempDirectory("droide-read-long-line-").toFile()
        try {
            val text = "start:" + "x".repeat(30_000) + ":end"
            root.resolve("source.js").writeText(text)
            val output = AgentWorkspaceReader(FileRepository(root)).read("source.js")
            assertTrue(output.contains("next_offset=10000"))
            val rest = AgentTools.execute("read_file", buildJsonObject {
                put("path", "source.js"); put("offset", 10_000)
            }, agentWorkflowContext(root))
            assertTrue(rest.contains("offset=10000"))
            assertFalse(rest.contains("start:"))
            assertTrue(rest.contains("next_offset=20000"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun offsetPagesReadEveryCharacterOfALargeSingleLineWithBoundedResponses() = runBlocking {
        val root = Files.createTempDirectory("droide-read-large-page-").toFile()
        try {
            val text = "HEAD" + "x".repeat(510_000) + "TAIL"
            root.resolve("bundle.js").writeText(text)
            val collected = StringBuilder()
            var offset = 0
            val ctx = agentWorkflowContext(root)
            do {
                val page = readBody(AgentTools.execute("read_file", buildJsonObject {
                    put("path", "bundle.js"); put("offset", offset)
                }, ctx))
                assertTrue(page.length < 10_500)
                assertTrue(page.startsWith("[DROIDE_READ_PAGE offset=$offset "))
                collected.append(page.substringAfter('\n'))
                val next = Regex("next_offset=(\\d+)").find(page)!!.groupValues[1].toInt()
                assertTrue(next > offset)
                offset = next
            } while (page.substringBefore('\n').contains("has_more=true"))
            assertEquals(text, collected.toString())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun offsetPagesUseTheLiveBufferAndDoNotSplitSurrogatePairs() = runBlocking {
        val root = Files.createTempDirectory("droide-read-live-page-").toFile()
        try {
            root.resolve("source.js").writeText("stale disk")
            val text = "x".repeat(9_999) + "\uD83D\uDE80" + "live tail"
            val documents = AgentWorkflowDocuments(agentWorkflowSnapshot("source.js", text))
            val ctx = agentWorkflowContext(root, documents = documents)
            val first = AgentTools.execute("read", buildJsonObject { put("path", "source.js"); put("offset", 0) }, ctx)
            assertTrue(first.contains("source=editor_buffer"))
            assertTrue(first.contains("next_offset=9999"))
            assertFalse(first.contains("stale disk"))
            val second = AgentTools.execute("read_file", buildJsonObject { put("path", "source.js"); put("offset", 9_999) }, ctx)
            assertTrue(second.contains("\uD83D\uDE80live tail"))
            assertEquals("stale disk", root.resolve("source.js").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun invalidOffsetOrMixedPagingModesAreRejected() = runBlocking {
        val root = Files.createTempDirectory("droide-read-page-invalid-").toFile()
        try {
            root.resolve("a.txt").writeText("hello")
            val ctx = agentWorkflowContext(root)
            for (args in listOf(
                buildJsonObject { put("path", "a.txt"); put("offset", -1) },
                buildJsonObject { put("path", "a.txt"); put("offset", 0); put("start", 1) },
                buildJsonObject { put("path", "a.txt"); put("offset", "invalid") },
            )) assertTrue(AgentTools.execute("read_file", args, ctx).startsWith("ERROR"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun diskPagesPreserveUtf8AndRejectBinaryOrInvalidEncoding() = runBlocking {
        val root = Files.createTempDirectory("droide-read-page-encoding-").toFile()
        try {
            val text = "x".repeat(9_999) + "\uD83D\uDE80é尾"
            root.resolve("unicode.txt").writeText(text)
            root.resolve("binary.bin").writeBytes(byteArrayOf(0, 1, 2))
            root.resolve("invalid.txt").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            val ctx = agentWorkflowContext(root)
            val first = AgentTools.execute("read_file", buildJsonObject { put("path", "unicode.txt"); put("offset", 0) }, ctx)
            assertTrue(first.contains("next_offset=9999"))
            val last = AgentTools.execute("read_file", buildJsonObject { put("path", "unicode.txt"); put("offset", 9_999) }, ctx)
            assertEquals("\uD83D\uDE80é尾", readBody(last).substringAfter('\n'))
            for (path in listOf("binary.bin", "invalid.txt", "../outside.txt")) {
                assertTrue(AgentTools.execute("read_file", buildJsonObject { put("path", path); put("offset", 0) }, ctx).startsWith("ERROR"))
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun arbitraryOffsetsCannotStartInsideASupplementaryCharacter() = runBlocking {
        val root = Files.createTempDirectory("droide-read-page-surrogate-").toFile()
        try {
            root.resolve("a.txt").writeText("\uD83D\uDE80hello")
            val args = buildJsonObject { put("path", "a.txt"); put("offset", 1) }
            assertTrue(AgentTools.execute("read_file", args, agentWorkflowContext(root)).startsWith("ERROR"))
            assertTrue(AgentTools.execute("read_file", args, agentWorkflowContext(root,
                documents = AgentWorkflowDocuments(agentWorkflowSnapshot("a.txt", "\uD83D\uDE80live")))).startsWith("ERROR"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun readBody(output: String) = output.removePrefix("[DROIDE_READ_RESULT returned=true]\n")
}
