package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production ranged Agent read routes, without changing disk/editor content. */
class AgentRangeUnicodeTest {
    @Test fun diskRangeDoesNotInventReplacementCharactersAtUtf8ByteBoundary() = runBlocking {
        val root = Files.createTempDirectory("range-unicode-").toFile()
        try {
            for (character in listOf("é", "€", "😀")) {
                for (retainedBytes in 1 until character.toByteArray(Charsets.UTF_8).size) {
                    val prefix = "a".repeat(2000 - retainedBytes)
                    val bytes = (prefix + character + "tail\nnext line\n").toByteArray(Charsets.UTF_8)
                    val file = root.resolve("source.txt"); file.writeBytes(bytes)
                    val reader = AgentWorkspaceReader(FileRepository(root))
                    val output = reader.read("source.txt", 1, 1)
                    assertFalse("Valid UTF-8 must not gain U+FFFD at the byte cap", output.contains('\uFFFD'))
                    assertTrue(output.contains("   1│ " + prefix + "…"))
                    assertTrue(output.contains("long_line_truncated=true")); assertTrue(output.contains("next_start=2"))
                    assertTrue(reader.read("source.txt", 2, 2).contains("   2│ next line"))
                    assertArrayEquals(bytes, file.readBytes())
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun liveRangeDoesNotSplitSupplementaryCharacterAtUtf16Boundary() = runBlocking {
        val root = Files.createTempDirectory("range-live-unicode-").toFile()
        try {
            val file = root.resolve("source.txt"); file.writeText("saved content")
            val content = "a".repeat(1999) + "😀tail\nnext line"
            val snapshot = WorkspaceDocumentSnapshot(path="source.txt", content=content, loaded=true,
                kind=WorkspaceDocumentKind.TEXT, dirty=true, editable=true, revision=3, changeVersion=4,
                selectionStart=0, selectionEnd=0, savedContentLength=13)
            val authority = object : WorkspaceDocumentAuthority {
                override suspend fun snapshot(path: String) = snapshot.takeIf { it.path == path }
                override suspend fun snapshots() = listOf(snapshot)
            }
            val output = AgentWorkspaceReader(FileRepository(root), authority).read("source.txt", 1, 1)
            assertFalse("Truncation must not expose an unpaired surrogate", output.any { it.isSurrogate() })
            assertTrue(output.contains("   1│ " + "a".repeat(1999) + "… [line truncated]"))
            assertTrue(output.contains("source=editor_buffer dirty=true")); assertTrue(output.contains("long_line_truncated=true"))
            assertEquals(content, authority.snapshot("source.txt")?.content)
            assertEquals("saved content", file.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun exactUtf8BoundaryKeepsWholeCharactersAndExistingMarkers() = runBlocking {
        val root = Files.createTempDirectory("range-exact-").toFile()
        try {
            for (character in listOf("é", "€", "😀")) for (tail in listOf("", "tail")) {
                val prefix = "a".repeat(2000 - character.toByteArray().size)
                root.resolve("source.txt").writeText(prefix + character + tail + "\nnext\n")
                val output = AgentWorkspaceReader(FileRepository(root)).read("source.txt", 1, 1)
                assertTrue(output.contains("   1│ " + prefix + character + if (tail.isEmpty()) "\n" else "…\n"))
                assertEquals(tail.isNotEmpty(), output.contains("long_line_truncated=true"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun malformedInteriorAndNonTruncatedEofKeepExistingReplacementBehavior() = runBlocking {
        val root = Files.createTempDirectory("range-malformed-").toFile()
        try {
            val file = root.resolve("source.txt"); val reader = AgentWorkspaceReader(FileRepository(root))
            val eof = "prefix".toByteArray() + byteArrayOf(0xC3.toByte())
            file.writeBytes(eof)
            assertTrue(reader.read("source.txt", 1, 1).contains("prefix\uFFFD")); assertArrayEquals(eof, file.readBytes())
            val interior = "prefix".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "a".repeat(2200).toByteArray()
            file.writeBytes(interior)
            val output = reader.read("source.txt", 1, 1)
            assertTrue(output.contains("prefix\uFFFD(")); assertTrue(output.contains("long_line_truncated=true"))
            assertArrayEquals(interior, file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun crlfAndOutputBudgetRemainBounded() = runBlocking {
        val root = Files.createTempDirectory("range-budget-").toFile()
        try {
            val bytes = ("a".repeat(1999) + "\r\n" + "b".repeat(2000) + "\r\nnext\n").toByteArray()
            val file = root.resolve("source.txt"); file.writeBytes(bytes)
            val files = FileRepository(root)
            val all = files.readRange("source.txt", 1, 2, 5000)
            assertFalse(all.contains('\r')); assertTrue(all.contains("   1│ " + "a".repeat(1999) + "\n"))
            assertTrue(all.contains("   2│ " + "b".repeat(2000) + "\n"))
            val limited = files.readRange("source.txt", 1, 2, 2040)
            assertTrue(limited.contains("next_start=2")); assertFalse(limited.contains("   2│"))
            assertArrayEquals(bytes, file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun readFileToolPreservesExactBoundaryEmojiFromDirtyBuffer() = runBlocking {
        val root = Files.createTempDirectory("range-tool-").toFile()
        try {
            root.resolve("source.txt").writeText("saved")
            val content = "a".repeat(1998) + "😀tail"
            val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("source.txt", content))
            val output = AgentTools.execute("read_file", buildJsonObject {
                put("path", "source.txt"); put("start", 1); put("end", 1)
            }, agentWorkflowContext(root, documents=docs))
            assertTrue(output.contains("source=editor_buffer dirty=true"))
            assertTrue(output.contains("😀… [line truncated]"))
            assertFalse(output.replace("😀", "").any { it.isSurrogate() })
            assertEquals(content, docs.current.content); assertEquals("saved", root.resolve("source.txt").readText())
        } finally { root.deleteRecursively() }
    }

}
