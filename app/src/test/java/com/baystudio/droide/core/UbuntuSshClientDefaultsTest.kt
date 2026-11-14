package com.baystudio.droide.core

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UbuntuSshClientDefaultsTest {
    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("droide ssh ").toFile().canonicalFile
        try { test(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun config(root: File): File = root.resolve(UbuntuSshClientDefaults.CONFIG)

    private fun ssh(vararg args: String): Pair<Int, String> {
        assumeTrue("Host integration requires OpenSSH", File("/usr/bin/ssh").canExecute())
        val process = ProcessBuilder(listOf("/usr/bin/ssh") + args).redirectErrorStream(true).start()
        try {
            process.outputStream.close()
            assertTrue("SSH must finish within the test deadline", process.waitFor(8, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally { process.destroyForcibly() }
    }

    @Test fun defaultsAreAtomicReusableAndDoNotReplaceUserFiles() = fixture { root ->
        root.resolve("root/.ssh").mkdirs()
        val known = root.resolve("root/.ssh/known_hosts").apply { writeText("retained host keys\n") }
        assertTrue(UbuntuSshClientDefaults.install(root))
        val identity = Files.readAttributes(config(root).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        assertEquals(UbuntuSshClientDefaults.CONTENT, config(root).readText())
        config(root).writeText("Host *\n ConnectTimeout 90\n IPQoS throughput\n")
        assertTrue(UbuntuSshClientDefaults.install(root))
        assertEquals(identity, Files.readAttributes(config(root).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertTrue(config(root).readText().contains("ConnectTimeout 90"))
        assertEquals("retained host keys\n", known.readText())
        assertFalse(root.resolve("etc/ssh/ssh_config").exists())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".droide-ssh-") })
    }

    @Test fun actualOpenSshKeepsSecurityAndAllowsPerHostAndCommandLineOverrides() = fixture { root ->
        assertTrue(UbuntuSshClientDefaults.install(root))
        val defaults = ssh("-G", "-F", config(root).path, "example.invalid")
        assertEquals(defaults.second, 0, defaults.first)
        assertTrue(defaults.second.contains("connecttimeout 15\n"))
        assertTrue(defaults.second.contains("ipqos none"))
        assertTrue(defaults.second.contains("stricthostkeychecking ask\n"))
        assertTrue(defaults.second.contains("batchmode no\n"))
        assertTrue(defaults.second.contains("requesttty auto\n"))
        assertTrue(defaults.second.contains("serveraliveinterval 0\n"))
        val user = root.resolve("user.conf").apply {
            writeText("Host example.invalid\n ConnectTimeout 47\n IPQoS af21\nHost *\n Include \"${config(root).path}\"\n")
        }
        val overridden = ssh("-G", "-F", user.path, "example.invalid")
        assertEquals(overridden.second, 0, overridden.first)
        assertTrue(overridden.second.contains("connecttimeout 47\n"))
        assertTrue(overridden.second, overridden.second.contains("ipqos af21"))
        val system = root.resolve("system.conf").apply {
            writeText("Include \"${config(root).path}\"\nHost *\n ConnectTimeout 73\n IPQoS cs1\n")
        }
        val later = ssh("-G", "-F", system.path, "example.invalid")
        assertEquals(later.second, 0, later.first)
        assertTrue(later.second, later.second.contains("connecttimeout 73\n"))
        assertTrue(later.second, later.second.contains("ipqos cs1"))
        val commandLine = ssh("-G", "-o", "ConnectTimeout=60", "-F", user.path, "example.invalid")
        assertEquals(commandLine.second, 0, commandLine.first)
        assertTrue(commandLine.second.contains("connecttimeout 60\n"))
    }

    @Test fun symlinkedDirectoriesAndConfigCannotWriteOutsideGuest() = fixture { root ->
        fixture { outside ->
            outside.resolve("sentinel").writeText("keep")
            Files.createSymbolicLink(root.resolve("etc").toPath(), outside.toPath())
            assertFalse(UbuntuSshClientDefaults.install(root))
            assertEquals(listOf("sentinel"), outside.listFiles()!!.map { it.name })
            Files.delete(root.resolve("etc").toPath())
            root.resolve("etc/ssh/ssh_config.d").mkdirs()
            Files.createSymbolicLink(config(root).toPath(), outside.resolve("sentinel").toPath())
            assertFalse(UbuntuSshClientDefaults.install(root))
            assertEquals("keep", outside.resolve("sentinel").readText())
        }
    }

    @Test fun collidingDirectoriesArePreservedWithoutStrandingTerminalPreparation() = fixture { root ->
        config(root).mkdirs()
        config(root).resolve("keep").writeText("user data")
        assertFalse(UbuntuSshClientDefaults.install(root))
        assertEquals("user data", config(root).resolve("keep").readText())
    }

    @Test fun concurrentInstallPublishesOneCompleteFile() = fixture { root ->
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll(List(8) { Callable { UbuntuSshClientDefaults.install(root) } }, 5, TimeUnit.SECONDS)
            assertTrue(results.all { it.get() })
            assertEquals(UbuntuSshClientDefaults.CONTENT, config(root).readText())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".droide-ssh-") })
        } finally { pool.shutdownNow() }
    }

    @Test fun silentServerBannerTimesOutInsteadOfWaitingIndefinitely() = fixture { root ->
        assertTrue(UbuntuSshClientDefaults.install(root))
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val result = ssh("-F", config(root).path, "-o", "ConnectTimeout=1", "-o", "BatchMode=yes",
                "-p", server.localPort.toString(), server.inetAddress.hostAddress, "true")
            assertEquals(result.second, 255, result.first)
            assertTrue(result.second, result.second.contains("timed out", ignoreCase = true))
        }
    }
}
