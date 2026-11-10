package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalCommandCwdPolicyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun nativeHostUsesObservedWorkspaceChildDirectory() {
        val workspace = temp.newFolder("workspace")
        val nested = File(workspace, "src/components").apply { mkdirs() }
        assertEquals(nested.canonicalFile,
            TerminalCommandCwdPolicy.resolveHostCwd(workspace, nested.absolutePath, true))
        assertEquals(workspace.canonicalFile,
            TerminalCommandCwdPolicy.resolveHostCwd(workspace, workspace.absolutePath, true))
    }

    @Test fun invalidOutsideAndGuestDirectoriesFallBackToWorkspace() {
        val workspace = temp.newFolder("workspace")
        val outside = temp.newFolder("outside")
        val nested = File(workspace, "src").apply { mkdirs() }
        val symlink = File(workspace, "linked")
        Files.createSymbolicLink(symlink.toPath(), outside.toPath())
        for (path in listOf(outside.absolutePath, symlink.absolutePath,
                "../outside", "/nonexistent/location", "")) {
            assertEquals(workspace.canonicalFile,
                TerminalCommandCwdPolicy.resolveHostCwd(workspace, path, true).canonicalFile)
        }
        assertEquals(workspace.canonicalFile,
            TerminalCommandCwdPolicy.resolveHostCwd(workspace, nested.absolutePath, false).canonicalFile)
    }
}
