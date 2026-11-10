package com.baystudio.droide.ui

import com.baystudio.droide.core.PathSecurity
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AndroidWorkspaceDocumentWatchTest {
    @Test fun regularFileWatchesItsParentAndMissingParentsWatchOnlyNearestExistingAncestor() {
        val root = Files.createTempDirectory("document-watch-target-").toFile()
        try {
            root.resolve("module").mkdir()
            assertEquals(AndroidWorkspaceDocumentWatch.Target(root.resolve("module"), "a.kt", false),
                AndroidWorkspaceDocumentWatch.target(root, "module/a.kt"))
            assertEquals(AndroidWorkspaceDocumentWatch.Target(root.resolve("module"), "new", true),
                AndroidWorkspaceDocumentWatch.target(root, "module/new/deep/a.kt"))
            root.resolve("module/new/deep").mkdirs()
            assertEquals(AndroidWorkspaceDocumentWatch.Target(root.resolve("module/new/deep"), "a.kt", false),
                AndroidWorkspaceDocumentWatch.target(root, "module/new/deep/a.kt"))
            PathSecurity.deleteTreeNoFollow(root.resolve("module"))
            assertEquals(AndroidWorkspaceDocumentWatch.Target(root, "module", true),
                AndroidWorkspaceDocumentWatch.target(root, "module/new/deep/a.kt"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun aliasesEscapesAndSymbolicLinksCannotRetargetWorkspaceWatch() {
        val base = Files.createTempDirectory("document-watch-alias-").toFile()
        val root = base.resolve("project").apply { mkdir() }
        val other = base.resolve("other").apply { mkdir() }
        try {
            Files.createSymbolicLink(root.resolve("link").toPath(), other.toPath())
            listOf("", "../other/a.txt", "/outside.txt", "module/../a.txt", "./a.txt", "link/a.txt").forEach { path ->
                assertThrows("Accepted $path", IllegalArgumentException::class.java) {
                    AndroidWorkspaceDocumentWatch.target(root, path)
                }
            }
            Files.createSymbolicLink(base.resolve("alias").toPath(), root.toPath())
            assertThrows(IllegalArgumentException::class.java) {
                AndroidWorkspaceDocumentWatch.target(base.resolve("alias"), "a.txt")
            }
            assertNull(AndroidWorkspaceDocumentWatch.target(base.resolve("absent"), "a.txt"))
        } finally { PathSecurity.deleteTreeNoFollow(base) }
    }
}
