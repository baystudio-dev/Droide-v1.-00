package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.*
import org.junit.Test

class WorkspaceScriptPermissionsTest {
    @Test fun missingExecuteBitGivesQuotedExplicitAdviceWithoutMutation() {
        val root = Files.createTempDirectory("droide-script-permission").toFile()
        try {
            val script = File(root, "install space '\$(secret).sh").apply { writeText("#!/bin/sh\nprintf 'ok'\n") }
            Files.setPosixFilePermissions(script.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            val before = Files.getPosixFilePermissions(script.toPath())
            val diagnostic = WorkspaceScriptPermissions.inspect(root, script.name)
            assertFalse(diagnostic.executable)
            assertTrue(diagnostic.portableHeader)
            assertTrue(diagnostic.render().contains("chmod u+x -- './install space '"))
            assertTrue(diagnostic.render().contains("No permission was changed"))
            assertEquals(before, Files.getPosixFilePermissions(script.toPath()))
            assertEquals("#!/bin/sh\nprintf 'ok'\n", script.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun executableHeaderDoesNotGrantExecuteBitAndFilenameDoesNotInventHeader() {
        val root = Files.createTempDirectory("droide-script-header").toFile()
        try {
            val plain = File(root, "install.sh").apply { writeText("a plain text document") }
            Files.setPosixFilePermissions(plain.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            val diagnostic = WorkspaceScriptPermissions.inspect(root, "install.sh")
            assertFalse(diagnostic.portableHeader)
            assertFalse(diagnostic.executable)
            val binary = File(root, "binary").apply { writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) }
            Files.setPosixFilePermissions(binary.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
            val executable = WorkspaceScriptPermissions.inspect(root, "binary")
            assertTrue(executable.executable)
            assertTrue(executable.portableHeader)
            assertTrue(executable.render().contains("interpreter, mount restrictions or Android policy"))
        } finally { root.deleteRecursively() }
    }

    @Test fun traversalAbsoluteControlPathsAndFileOrDirectorySymlinksAreRejected() {
        val root = Files.createTempDirectory("droide-script-safety").toFile()
        val outside = Files.createTempDirectory("droide-script-outside").toFile()
        try {
            val script = File(outside, "install.sh").apply { writeText("#!/bin/sh\n") }
            Files.createSymbolicLink(File(root, "linked").toPath(), script.toPath())
            Files.createSymbolicLink(File(root, "linked-dir").toPath(), outside.toPath())
            for (path in listOf("../install.sh", script.absolutePath, "a\nb.sh", "linked", "linked-dir/install.sh", ".")) {
                assertThrows(IllegalArgumentException::class.java) { WorkspaceScriptPermissions.inspect(root, path) }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root); PathSecurity.deleteTreeNoFollow(outside) }
    }

    @Test fun bootTimeAndPortErrorsGetActionableHintsWithoutPretendingToKnowOwner() {
        assertTrue(TerminalFailureDiagnostics.hint(1, "ps: Unable to get system boot time", "ps")!!.contains("system process table is unavailable"))
        assertTrue(TerminalFailureDiagnostics.hint(1, "EADDRINUSE: address already in use :::3000", "node server.js")!!.contains("port number alone does not identify"))
        assertNull(TerminalFailureDiagnostics.hint(0, "EADDRINUSE", "echo fixture"))
    }

    @Test fun permissionHintDoesNotInspectWrongRootWithoutActualPinnedCwd() {
        val message = TerminalFailureDiagnostics.hint(126, "./install.sh: Permission denied", "./install.sh")!!
        assertTrue(message.contains("workspace-relative-path"))
        assertFalse(message.contains("chmod u+x"))
        assertTrue(message.contains("No permission was changed"))
    }
}
