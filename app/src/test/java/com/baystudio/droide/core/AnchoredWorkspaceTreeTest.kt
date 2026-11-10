package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.FileSystemException
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.*
import org.junit.Test

class AnchoredWorkspaceTreeTest {
    private fun fixture(block: (File, File) -> Unit) {
        val parent = Files.createTempDirectory("anchored-tree-").toFile()
        try { block(File(parent, "workspace").apply { mkdir() }, File(parent, "outside").apply { mkdir() }) }
        finally { PathSecurity.deleteTreeNoFollow(parent) }
    }

    @Test fun directoryReplacementDuringEnumerationCannotReadOutsideBytes() = fixture { root, outside ->
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "file").writeText("project")
        File(outside, "file").writeText("private")
        var payloadBytes = 0
        var replaced = false
        val cache = WorkspaceFileFingerprintCache(onHashRead = { _, bytes -> payloadBytes += bytes })
        try {
            AnchoredWorkspaceTree.snapshot(root, cache, EmptyCoroutineContext) { relative ->
                if (relative == "nested/file" && !replaced) {
                    replaced = true
                    Files.move(nested.toPath(), File(root, "retained-directory").toPath())
                    Files.createSymbolicLink(nested.toPath(), outside.toPath())
                }
                false
            }
            fail("Concurrent directory symlink replacement was accepted")
        } catch (_: FileSystemException) { }
        assertTrue(replaced)
        assertEquals(0, payloadBytes)
        assertEquals("private", File(outside, "file").readText())
        assertEquals("project", File(root, "retained-directory/file").readText())
    }

    @Test fun opaqueEntriesArePreservedAndDoNotExposeOutsideContent() = fixture { root, outside ->
        File(root, "source.kt").writeText("source")
        File(root, "node_modules").mkdir()
        File(root, "node_modules/dependency").writeText("dependency")
        File(root, ".droide-sync-manifest.tsv").writeText("foreign manifest")
        File(outside, "secret").writeText("private")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        val result = AnchoredWorkspaceTree.snapshot(root, WorkspaceFileFingerprintCache(), EmptyCoroutineContext)
        assertEquals(setOf("source.kt"), result.files.keys)
        assertEquals(setOf("node_modules", ".droide-sync-manifest.tsv", "link"), result.opaque)
        assertEquals("foreign manifest", File(root, ".droide-sync-manifest.tsv").readText())
        assertEquals("dependency", File(root, "node_modules/dependency").readText())
        assertTrue(Files.isSymbolicLink(File(root, "link").toPath()))
    }
}
