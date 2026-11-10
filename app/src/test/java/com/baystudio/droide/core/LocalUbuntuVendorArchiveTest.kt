package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuVendorArchiveTest {
    @Test fun originalRawAndGzipPayloadsKeepBytesAndCleanProjection() = runBlocking {
        val root = Files.createTempDirectory("droide-vendor-raw-").toFile()
        try {
            for (family in listOf("cli.kubectl", "lsp.rust-analyzer")) {
                val def = LocalUbuntuVendorCatalog.find(family)!!
                val version = "1.0.0"
                val source = File(root, def.asset(version)); val bytes = Random(91).nextBytes(4096)
                if (def.format == LocalUbuntuVendorFormat.GZIP_RAW) GZIPOutputStream(source.outputStream()).use { it.write(bytes) }
                else source.writeBytes(bytes)
                val url = if (family == "cli.kubectl") "https://dl.k8s.io/release/v$version/bin/linux/arm64/kubectl"
                    else "https://github.com/rust-lang/rust-analyzer/releases/download/$version/${def.asset(version)}"
                val recipe = LocalUbuntuVendorRecipe(def, version, version, source.name, url, "a".repeat(64), source.length(), "https://example.com/provenance")
                var projected: File? = null
                LocalUbuntuVendorArchive.project(recipe, source, "/unused/launcher").use {
                    projected = it.root
                    assertArrayEquals(bytes, it.files.single().file.readBytes())
                    assertEquals(4096L, it.totalBytes)
                    assertEquals(493, it.files.single().mode)
                }
                assertFalse(projected!!.exists()); assertTrue(source.isFile)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun gzipBombDoesNotLeaveExpandedPayload() = runBlocking {
        val root = Files.createTempDirectory("droide-vendor-bomb-").toFile()
        try {
            val def = LocalUbuntuVendorCatalog.find("lsp.rust-analyzer")!!
            val source = File(root, def.asset("1.0.0"))
            GZIPOutputStream(source.outputStream()).use { it.write(ByteArray(1_000_000)) }
            val recipe = LocalUbuntuVendorRecipe(def, "1.0.0", "1.0.0", source.name,
                "https://github.com/rust-lang/rust-analyzer/releases/download/1.0.0/${source.name}", "a".repeat(64), source.length(), "https://example.com/provenance")
            try { LocalUbuntuVendorArchive.project(recipe, source, "/unused/launcher"); fail() } catch (_: IllegalArgumentException) {}
            assertEquals(listOf(source.name), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }
}
