package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FoundryUbuntuLayerReleasePolicyTest {
    private val layer = "0123456789abcdef01234567"

    private fun release(root: File) {
        // The host fixture maps only the two owned guest directories; the production script is unchanged.
        val script = FoundryUbuntuLayerReleasePolicy.command(layer)[2]
            .replace("/var/lib/droide-foundry/layers", File(root, "var/lib/droide-foundry/layers").path)
            .replace("/var/cache/droide-foundry", File(root, "var/cache/droide-foundry").path)
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        try {
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(output, 0, process.exitValue())
        } finally { process.destroyForcibly() }
    }

    @Test fun releasingAnIntegrationPreservesTerminalPackagesHomeConfigurationAndSharedAptCache() {
        val root = Files.createTempDirectory("droide shared Ubuntu ").toFile()
        try {
            val preserved = listOf("root/work.txt", "root/.bash_history", "etc/user.conf", "var/lib/dpkg/status",
                "var/lib/apt/lists/signed-index", "var/cache/apt/archives/user-package.deb",
                "var/lib/droide-foundry/layers/ffffffffffffffffffffffff.lock",
                "var/cache/droide-foundry/ffffffffffffffffffffffff/other.deb")
                .associateWith { relative -> File(root, relative).apply { parentFile.mkdirs(); writeText("terminal-owned:$relative") } }
            val marker = File(root, "var/lib/droide-foundry/layers/$layer.lock").apply { writeText("obsolete anchor") }
            val cache = File(root, "var/cache/droide-foundry/$layer/archives").apply { mkdirs() }
            File(cache, "owned.deb").writeText("temporary transaction")
            release(root)
            assertFalse(marker.exists())
            assertFalse(cache.parentFile.exists())
            preserved.forEach { (relative, file) -> assertEquals("terminal-owned:$relative", file.readText()) }
        } finally { root.deleteRecursively() }
    }

    @Test fun aTransactionSymlinkDoesNotDeleteTheTerminalDirectoryItPointsTo() {
        val root = Files.createTempDirectory("droide shared cache ").toFile()
        try {
            val user = File(root, "root/work").apply { mkdirs() }
            val data = File(user, "important.txt").apply { writeText("keep") }
            File(root, "var/lib/droide-foundry/layers").mkdirs()
            val cache = File(root, "var/cache/droide-foundry").apply { mkdirs() }
            Files.createSymbolicLink(File(cache, layer).toPath(), user.toPath())
            release(root)
            assertEquals("keep", data.readText())
            assertFalse(Files.exists(File(cache, layer).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally { root.deleteRecursively() }
    }

    @Test fun unsafeOrAmbiguousLayerIdentitiesFailBeforeLaunchingAnything() {
        for (value in listOf("", "..", "$layer/../root", "$layer; touch marker", "$layer\n", layer.uppercase(),
            layer.dropLast(1), layer + "0", layer + "\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuLayerReleasePolicy.command(value) }
        }
    }
}
