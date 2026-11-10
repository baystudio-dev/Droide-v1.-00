package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class WholeTreeNodeArchiveTest {
    private fun header(name: String, type: Char, size: Int): ByteArray {
        val data = ByteArray(512)
        fun field(offset: Int, length: Int, value: String) {
            val bytes = value.toByteArray(); require(bytes.size <= length); bytes.copyInto(data, offset)
        }
        field(0, 100, name); field(100, 8, "0000755\u0000"); field(124, 12, size.toString(8).padStart(11, '0') + "\u0000")
        for (i in 148..155) data[i] = 32
        data[156] = type.code.toByte()
        val checksum = data.sumOf { it.toInt() and 255 }
        field(148, 8, checksum.toString(8).padStart(6, '0') + "\u0000 ")
        return data
    }
    private fun fixture(names: List<String>, file: Boolean = true, policy: DeclarativeWholeTreeModePolicy = DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE,
        check: (SafeTreeProjection) -> Unit = {}) {
        val dir = Files.createTempDirectory("droide-long-tar").toFile()
        try {
            val archive = File(dir, "node.tar.gz")
            GZIPOutputStream(archive.outputStream()).use { out ->
                for (name in names) {
                    val payload = (name + "\u0000").toByteArray()
                    out.write(header("././@LongLink", 'L', payload.size)); out.write(payload)
                    out.write(ByteArray((512 - payload.size % 512) % 512))
                }
                if (file) { out.write(header("short", '0', 4)); out.write("data".toByteArray()); out.write(ByteArray(508)) }
                out.write(ByteArray(1024))
            }
            SafeWholeTreeArchive.project(archive, DeclarativeGitHubArtifactFormat.TAR_GZ,
                if (policy == DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST) setOf("short") else emptySet(),
                8192.takeIf { policy == DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE } ?: 4096, 1024 * 1024,
                DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE, policy).use(check)
        } finally { PathSecurity.deleteTreeNoFollow(dir) }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unsafe archive accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun boundedGnuLongFilenameProjectsWithPinnedPolicy() {
        val name = "node/lib/" + "a".repeat(140) + ".js"
        fixture(listOf(name)) { assertEquals(name, it.files.single().relativePath); assertEquals("data", it.files.single().file.readText()) }
    }
    @Test fun longFilenameCannotTraverseOutsideProjection() { rejects { fixture(listOf("../outside")) } }
    @Test fun consecutiveLongFilenameRecordsAreRejected() { rejects { fixture(listOf("node/first", "node/second")) } }
    @Test fun danglingLongFilenameRecordIsRejected() { rejects { fixture(listOf("node/file"), file = false) } }
    @Test fun genericExplicitArchivePolicyDoesNotAdmitLongFilenameExtension() {
        rejects { fixture(listOf("node/file"), policy = DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST) }
    }
}
