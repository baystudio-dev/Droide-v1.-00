package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspacePathLifecycleTest {
    @Test(timeout = 15_000) fun liveChildCwdBlocksMoveAndDeleteUntilExactProcessExits() = runBlocking {
        val root = Files.createTempDirectory("workspace-cwd-").toFile()
        val occupied = File(root, "server").apply { mkdir() }
        File(occupied, "source.js").writeText("original")
        val process = ProcessBuilder("/bin/sh", "-c", "exec cat").directory(occupied).start()
        try {
            val repository = FileRepository(root)
            assertTrue(process.isAlive)
            try { repository.move("server", "moved"); fail("Live cwd was moved") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("Stop the command")) }
            try { repository.delete("server"); fail("Live cwd was deleted") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("Stop the command")) }
            assertTrue(process.isAlive)
            val pid = (Process::class.java.getMethod("pid").invoke(process) as Number).toLong()
            assertEquals(occupied.canonicalPath, Files.readSymbolicLink(File("/proc/$pid/cwd").toPath()).toString())
            assertEquals("original", File(occupied, "source.js").readText())
            process.destroy()
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
            assertTrue(repository.move("server", "moved"))
            assertFalse(occupied.exists())
            assertEquals("original", File(root, "moved/source.js").readText())
        } finally {
            process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun rootIdentityRejectsRecreatedDirectoryButAcceptsOrdinaryFileEdits() {
        val base = Files.createTempDirectory("workspace-inode-").toFile()
        val root = File(base, "project").apply { mkdir() }
        try {
            val identity = WorkspaceDirectoryIdentity(root)
            File(root, "file").writeText("normal edit")
            identity.requireCurrent()
            Files.move(root.toPath(), File(base, "retained").toPath())
            assertTrue(root.mkdir())
            try { identity.requireCurrent(); fail("Replaced root was accepted") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("moved or replaced")) }
            assertEquals("normal edit", File(base, "retained/file").readText())
        } finally { PathSecurity.deleteTreeNoFollow(base) }
    }

    @Test fun missingObservedCwdCannotSilentlyRunInProjectRoot() {
        val root = Files.createTempDirectory("workspace-missing-cwd-").toFile()
        try {
            val missing = File(root, "removed")
            try { TerminalCommandCwdPolicy.resolveHostCwd(root, missing.absolutePath, true); fail("Missing cwd fell back") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("Change directory")) }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun readableDeletedCwdIsReportedWithoutSignallingAnyPid() {
        val base = Files.createTempDirectory("workspace-proc-fixture-").toFile()
        val root = File(base, "project").apply { mkdir() }
        val proc = File(base, "proc").apply { mkdir() }
        try {
            File(proc, "123").mkdir()
            Files.createSymbolicLink(File(proc, "123/cwd").toPath(), File(root, "old (deleted)").toPath())
            File(proc, "124").mkdir()
            Files.createSymbolicLink(File(proc, "124/cwd").toPath(), File(base, "project-other").toPath())
            assertEquals(listOf(WorkspaceProcessPathGuard.Occupant(123, true)), WorkspaceProcessPathGuard.occupants(root, proc))
        } finally { PathSecurity.deleteTreeNoFollow(base) }
    }
}
