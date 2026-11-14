package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RemoteProcessLeaseTest {
    @Test fun allCommandFamiliesHaveValidShellSyntax() {
        val lease = RemoteProcessLease.create("syntax-check")
        listOf(lease.wrap("printf x"), lease.aliveCommand(), lease.terminateCommand(),
            lease.detachedCommand("sleep 1", DeviceBridgeManager.remoteRoot() + "/run/command.log")).forEach { command ->
            val process = ProcessBuilder(shell(), "-n", "-c", command).redirectErrorStream(true).start()
            assertEquals(process.inputStream.bufferedReader().readText(), 0, process.waitFor())
        }
    }

    @Test fun emptyLeaseDirectoryAndMissingDirectoryDoNotFailStartup() = fixture { root ->
        assertEquals(0, execute(root).first)
        assertEquals(0, execute(File(root, "absent")).first)
    }

    @Test fun markerWithoutLeasesIsPreserved() = fixture { root ->
        val marker = File(root, ".app-process").apply { writeText("previous-process") }
        assertEquals(0, execute(root).first)
        assertEquals("previous-process", marker.readText())
    }

    @Test fun malformedLeasesAreRemovedWithoutScanningOrSignalling() = fixture { root ->
        listOf("", "garbage", "abc:" + "a".repeat(20), "123:wrong", "1:" + "z".repeat(20)).forEachIndexed { i, text ->
            File(root, "$i.pid").writeText(text)
        }
        assertEquals(0, execute(root).first)
        assertTrue(root.list().isNullOrEmpty())
    }

    @Test fun pidFileSymlinksAreUnlinkedWithoutFollowingTheirTargets() = fixture { root ->
        val outside = File(root, "preserved.txt").apply { writeText("private data") }
        Files.createSymbolicLink(File(root, "unsafe.pid").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "missing.pid").toPath(), File(root, "missing").toPath())
        assertEquals(0, execute(root).first)
        assertEquals("private data", outside.readText())
        assertFalse(Files.isSymbolicLink(File(root, "unsafe.pid").toPath()))
        assertFalse(Files.isSymbolicLink(File(root, "missing.pid").toPath()))
    }

    @Test fun directoryNamesCannotInjectShellCommands() = fixture { root ->
        val tricky = File(root, "space's; \$odd").apply { mkdir() }
        File(tricky, "invalid.pid").writeText("not a lease")
        assertEquals(0, execute(tricky).first)
        assertTrue(tricky.list().isNullOrEmpty())
    }

    @Test fun pidReuseDoesNotSignalAnUnownedLiveProcess() = fixture { root ->
        val process = ProcessBuilder(shell(), "-c", "exec sleep 30").start()
        try {
            val pid = hostProcessId(process)
            val proc = File(root, "proc").apply { mkdir() }
            File(proc, "$pid/environ").apply { parentFile.mkdirs(); writeBytes("DROIDE_PROCESS_LEASE=${token()}\u0000".toByteArray()) }
            File(root, "reused.pid").writeText("$pid:${token()}")
            assertEquals(0, execute(root, proc).first)
            assertTrue(process.isAlive)
        } finally { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS) }
    }

    @Test fun aVerifiedOwnedProcessIsTerminatedAndItsLeaseIsRemoved() = fixture { root ->
        val token = token()
        val process = ProcessBuilder("setsid", shell(), "-c", "exec sleep 30")
            .apply { environment()["DROIDE_PROCESS_LEASE"] = token }.start()
        try {
            val pid = hostProcessId(process)
            // Deterministic procfs fixture; some CI hosts deny even child-environ reads.
            val proc = File(root, "proc").apply { mkdir() }
            File(proc, "$pid/environ").apply { parentFile.mkdirs(); writeBytes("DROIDE_PROCESS_LEASE=$token\u0000".toByteArray()) }
            // Synthetic procfs reports that the owned PID is a session/group leader.
            File(proc, "$pid/stat").writeText("$pid (sleep process) S 1 $pid $pid 0 0 0 0\n")
            File(root, "owned.pid").writeText("$pid:$token")
            assertEquals(0, execute(root, proc).first)
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
            assertFalse(File(root, "owned.pid").exists())
        } finally { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS) }
    }

    @Test fun sigkillIsSkippedWhenLeaseOwnerChangesAfterSigterm() = fixture { root ->
        // The shell fixture records signals instead of delivering them to a PID.
        val pid = "987654"
        val owner = token()
        val proc = File(root, "proc").apply { mkdir() }
        val envFile = File(proc, "$pid/environ").apply {
            parentFile.mkdirs()
            writeBytes("DROIDE_PROCESS_LEASE=$owner\u0000".toByteArray())
        }
        File(root, "owned.pid").writeText("$pid:$owner")
        val signals = File(root, "signals.log")
        val command = RemoteProcessLease.reapAllInDirectory(root.absolutePath)
            .replace("/system/bin/toybox ", "")
            .replace("/proc/", proc.absolutePath + "/")
        fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val killMock = "kill() { printf '%s\\n' \"$*\" >> " + q(signals.absolutePath) + "; }; "
        val sleepMock = "sleep() { printf 'NOT_OWNED' > " + q(envFile.absolutePath) + "; }; "
        val process = ProcessBuilder(shell(), "-c", killMock + sleepMock + command)
            .redirectErrorStream(true).start()
        assertTrue("Reconciliation timed out", process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        assertEquals(listOf("-TERM $pid"), signals.readLines())
        assertFalse(File(root, "owned.pid").exists())
    }

    private fun execute(directory: File, procFixture: File? = null): Pair<Int, String> {
        val command = RemoteProcessLease.reapAllInDirectory(directory.absolutePath)
        val syntax = ProcessBuilder(shell(), "-n", "-c", command).redirectErrorStream(true).start()
        val syntaxOutput = syntax.inputStream.bufferedReader().readText()
        assertEquals(syntaxOutput, 0, syntax.waitFor())
        val nativeCommand = if (File("/system/bin/toybox").isFile) command else command.replace("/system/bin/toybox ", "")
        val hostCommand = procFixture?.let { nativeCommand.replace("/proc/", it.absolutePath + "/") } ?: nativeCommand
        val process = ProcessBuilder(shell(), "-c", hostCommand).redirectErrorStream(true).start()
        try {
            assertTrue("Lease reconciliation exceeded its deadline", process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
            return process.exitValue() to output
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun shell() = if (File("/system/bin/sh").isFile) "/system/bin/sh" else "/bin/sh"
    // These are host JVM tests, but Gradle type-checks them against Android's Process API.
    private fun hostProcessId(process: Process): Long =
        (Process::class.java.getMethod("pid").invoke(process) as Number).toLong()
    private fun token() = UUID.randomUUID().toString().replace("-", "").take(20)
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-lease-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
