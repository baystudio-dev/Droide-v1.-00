package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PatchSnapshotBudgetTest {
    private fun project(body: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("patch-snapshot-budget").toFile()
        try { body(root) } finally { root.deleteRecursively() }
    }
    private fun snapshot(path: String, text: String) = WorkspaceDocumentSnapshot(
        path, text, true, WorkspaceDocumentKind.TEXT, false, true, 1, 1, 0, 0, text.length, "doc-$path")
    private class Documents(val values: Map<String, WorkspaceDocumentSnapshot>) : WorkspaceDocumentAuthority {
        val requested = mutableListOf<String>()
        override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? { requested += path; return values[path] }
        override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = values.values.toList()
        override suspend fun replaceText(path: String, expected: WorkspaceDocumentVersion, content: String, reason: String): WorkspaceDocumentMutationResult = error("No mutation during preflight")
    }
    @Test fun hundredDiskFilesRejectAtThirdBeforeInvalidFourthAndNoEffects() = project { root ->
        repeat(3) { root.resolve("f$it").writeText("a".repeat(2_000_000)) }
        (3 until 100).forEach { root.resolve("f$it").writeBytes(byteArrayOf(0)) }
        var writes = 0
        val files = FileRepository(root, beforeMutation = { writes++ })
        val ops = (0 until 100).map { PatchTool.Op.Delete("f$it") }
        val failure = runCatching { PatchTool.reviewSnapshot(ops, files) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Patch touches too much text"))
        assertEquals(0, writes); assertFalse(root.resolve(".droide").exists())
        repeat(3) { assertEquals(2_000_000L, root.resolve("f$it").length()) }
        assertEquals(100, root.list()!!.size)
    }
    @Test fun hundredLiveTargetsRejectBeforeFourthSnapshotRequest() = project { root ->
        val text = "a".repeat(2_000_000)
        val docs = Documents((0 until 100).associate { "f$it" to snapshot("f$it", text) })
        val failure = runCatching {
            PatchTool.reviewWorkspaceSnapshot((0 until 100).map { PatchTool.Op.Delete("f$it") }, FileRepository(root), docs)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Patch touches too much text"))
        assertEquals(listOf("f0", "f1", "f2"), docs.requested)
        assertTrue(root.list()!!.isEmpty())
    }
    @Test fun exactDiskBoundaryAndAbsentAddRemainAccepted() = project { root ->
        listOf(2_000_000, 2_000_000, 1_000_000).forEachIndexed { i, size -> root.resolve("f$i").writeText("a".repeat(size)) }
        val ops = (0..2).map { PatchTool.Op.Delete("f$it") } + PatchTool.Op.Add("new", "text")
        val reviewed = PatchTool.reviewSnapshot(ops, FileRepository(root))
        assertEquals(5_000_000L, reviewed.values.sumOf { it?.length?.toLong() ?: 0L })
        assertTrue(reviewed.containsKey("new")); assertNull(reviewed["new"])
        assertFalse(root.resolve("new").exists())
    }
    @Test fun mixedLiveAndDiskShareSameBoundaryWithoutReadingLiveDiskCopy() = project { root ->
        root.resolve("disk").writeText("d".repeat(2_000_000))
        root.resolve("live").writeBytes(byteArrayOf(0, 1)) // Must not read disk beneath live text.
        val live = snapshot("live", "l".repeat(3_000_000))
        val docs = Documents(mapOf("live" to live))
        val reviewed = PatchTool.reviewWorkspaceSnapshot(listOf(PatchTool.Op.Delete("live"), PatchTool.Op.Delete("disk")), FileRepository(root), docs)
        assertSame(live, reviewed.liveDocuments["live"])
        assertSame(live.content, reviewed.before["live"])
        assertEquals(5_000_000L, reviewed.before.values.sumOf { it?.length?.toLong() ?: 0L })
        assertArrayEquals(byteArrayOf(0, 1), root.resolve("live").readBytes())
    }
    @Test fun mixedOneCharacterOverBoundaryRejectsBeforeNextTarget() = project { root ->
        root.resolve("disk").writeText("d".repeat(2_000_000))
        val docs = Documents(mapOf("live" to snapshot("live", "l".repeat(3_000_001))))
        val failure = runCatching { PatchTool.reviewWorkspaceSnapshot(
            listOf(PatchTool.Op.Delete("live"), PatchTool.Op.Delete("disk"), PatchTool.Op.Delete("missing")), FileRepository(root), docs)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Patch touches too much text"))
        assertEquals(listOf("live", "disk"), docs.requested)
        assertFalse(root.resolve(".droide").exists())
    }
}
