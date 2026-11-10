package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AgentMediaScratchTest {
    private val start = 10L * ChatMediaStore.RETENTION_MS

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("media-scratch-").toFile()
        try { block(root) } finally {
            PathSecurity.deleteTreeNoFollow(ChatMediaStoragePaths.directory(root))
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun scratchIsOutsideProjectAndClosedEmptyScopeLeavesNoFiles() = fixture { root ->
        val scope = AgentMediaScratch(root, "browser") { start }
        val file = scope.create("png").apply { writeText("bounded encoded bytes") }
        assertFalse(PathSecurity.contains(root, file))
        assertFalse(root.resolve(".droide").exists())
        file.delete()
        val parent = file.parentFile
        scope.close()
        assertFalse(parent.exists())
        assertThrows(IllegalStateException::class.java) { scope.create("png") }
    }

    @Test fun sevenDaySweepDeletesOnlyInactiveReceiptOwnedFiles() = fixture { root ->
        val scope = AgentMediaScratch(root, "web") { start }
        val file = scope.create("pdf").apply { writeText("%PDF bounded fixture") }
        scope.close()
        AgentMediaScratch.cleanup(root, start + ChatMediaStore.RETENTION_MS - 1)
        assertTrue(file.exists())
        AgentMediaScratch.cleanup(root, start + ChatMediaStore.RETENTION_MS)
        assertFalse(file.exists())
        assertFalse(file.parentFile.exists())
    }

    @Test fun activeLeaseScopeIsKeptEvenWhenJobRunsBeyondSevenDays() = fixture { root ->
        val scope = AgentMediaScratch(root, "web") { start }
        val file = scope.create("png").apply { writeText("active reader fixture") }
        try {
            AgentMediaScratch.cleanup(root, start + ChatMediaStore.RETENTION_MS + 1)
            assertTrue(file.exists())
        } finally { scope.close() }
        AgentMediaScratch.cleanup(root, start + ChatMediaStore.RETENTION_MS + 1)
        assertFalse(file.exists())
    }

    @Test fun unknownFilesAndUnmarkedDirectoriesAreNeverDeleted() = fixture { root ->
        val user = root.resolve("original.png").apply { writeText("project original") }
        val scope = AgentMediaScratch(root, "web") { start }
        val known = scope.create("png").apply { writeText("scratch bytes") }
        val unexpected = File(known.parentFile, "user-notes.txt").apply { writeText("preserve") }
        val unmarked = File(known.parentFile.parentFile, UUID.randomUUID().toString()).apply { mkdirs() }
        val unknown = File(unmarked, "${UUID.randomUUID()}.png").apply { writeText("unknown origin") }
        scope.close()
        AgentMediaScratch.cleanup(root, start + ChatMediaStore.RETENTION_MS + 1)
        assertTrue(known.exists())
        assertEquals("preserve", unexpected.readText())
        assertEquals("unknown origin", unknown.readText())
        assertEquals("project original", user.readText())
    }
}
