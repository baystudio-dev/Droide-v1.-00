package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeWholeTreeArchiveSecurityTest {
    @Test fun reviewedEclipseNumericPaxNeverChangesPathsSizesOrModes() {
        val archive = tarGz(TarEntry("PaxHeaders/tool", type = 'x', data = pax("uid", "1001380000") + pax("gid", "1001380000") + pax("mtime", "1788441733.0190000")),
            TarEntry("bin/tool", data = "original".toByteArray()))
        try {
            assertThrows(IllegalArgumentException::class.java) {
                SafeWholeTreeArchive.projectVendorTar(archive, setOf("bin/tool"), 16, 1024 * 1024, gzip = true)
            }
            SafeWholeTreeArchive.projectVendorTar(archive, setOf("bin/tool"), 16, 1024 * 1024, gzip = true, allowReviewedNumericPax = true).use {
                assertEquals("bin/tool", it.files.single().relativePath)
                assertEquals("original", it.files.single().file.readText())
                assertEquals(493, it.files.single().mode)
            }
        } finally { archive.parentFile!!.deleteRecursively() }
    }

    @Test fun paxOverridesMalformedLengthsAndDanglingMetadataAreRejected() {
        for (data in listOf(pax("path", "../user-file"), pax("size", "999"), pax("GNU.sparse.size", "999"),
            pax("uid", "1") + pax("uid", "2"), "999 uid=1\n".toByteArray())) {
            val archive = tarGz(TarEntry("PaxHeaders/tool", type = 'x', data = data), TarEntry("bin/tool", data = "original".toByteArray()))
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    SafeWholeTreeArchive.projectVendorTar(archive, setOf("bin/tool"), 16, 1024 * 1024, gzip = true, allowReviewedNumericPax = true)
                }
                assertEquals(listOf(archive.name), archive.parentFile!!.listFiles()!!.map { it.name })
            } finally { archive.parentFile!!.deleteRecursively() }
        }
        val archive = tarGz(TarEntry("PaxHeaders/tool", type = 'x', data = pax("uid", "1")))
        try {
            assertThrows(IllegalArgumentException::class.java) {
                SafeWholeTreeArchive.projectVendorTar(archive, setOf("bin/tool"), 16, 1024 * 1024, gzip = true, allowReviewedNumericPax = true)
            }
        } finally { archive.parentFile!!.deleteRecursively() }
    }

    private fun pax(key: String, value: String): ByteArray {
        val body = "$key=$value\n"
        var length = body.length + 2
        while (length != body.length + length.toString().length + 1) length = body.length + length.toString().length + 1
        return "$length $body".toByteArray(Charsets.US_ASCII)
    }
    @Test fun cancellationDuringTarMemberCopyRemovesProjectionAndPreservesArchive() {
        val archive = tarGz(TarEntry("bin/tool", data = ByteArray(256 * 1024) { (it % 251).toByte() }, mode = 493))
        assertCanceledProjection(archive, DeclarativeGitHubArtifactFormat.TAR_GZ)
    }

    @Test fun cancellationDuringZipMemberCopyRemovesProjectionAndPreservesArchive() {
        val root = java.nio.file.Files.createTempDirectory("droide-zip-cancel-").toFile()
        val archive = File(root, "fixture.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("bin/tool"))
            val payload = ByteArray(256 * 1024).also { java.util.Random(42).nextBytes(it) }
            zip.write(payload)
            zip.closeEntry()
        }
        assertCanceledProjection(archive, DeclarativeGitHubArtifactFormat.ZIP)
    }

    private fun assertCanceledProjection(archive: File, format: DeclarativeGitHubArtifactFormat) {
        try {
            var checks = 0
            assertThrows(CancellationException::class.java) {
                SafeWholeTreeArchive.project(archive, format, setOf("bin/tool"), 16, 1024 * 1024,
                    checkActive = { if (++checks == 5) throw CancellationException("Canceled during extraction") })
            }
            check(checks == 5)
            check(archive.isFile)
            check(archive.parentFile!!.listFiles()!!.none { it.name.startsWith(".tree-") })
        } finally { archive.parentFile?.deleteRecursively() }
    }

    @Test fun safeRelativeSymlinkIsProjectedWithoutMaterializingTheLink() {
        val archive = tarGz(
            TarEntry("bin", type = '5', mode = 493),
            TarEntry("bin/tool", data = "payload".toByteArray(), mode = 493),
            TarEntry("links", type = '5', mode = 493),
            TarEntry("links/tool", type = '2', link = "../bin/tool", mode = 511),
        )
        SafeWholeTreeArchive.project(
            archive = archive,
            format = DeclarativeGitHubArtifactFormat.TAR_GZ,
            executablePaths = setOf("bin/tool"),
            maxFiles = 16,
            maxUnpackedBytes = 1024 * 1024,
            linkPolicy = DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE,
        ).use { projection ->
            assertEquals(1, projection.symlinks.size)
            assertEquals("bin/tool", projection.symlinks.single().resolvedPath)
            assertEquals(false, File(projection.root, "links/tool").exists())
        }
        archive.parentFile?.deleteRecursively()
    }


    @Test fun releasedProjectedFileIsRemovedWithoutDeletingSiblingPayloads() {
        val archive = tarGz(
            TarEntry("bin", type = '5', mode = 493),
            TarEntry("bin/tool", data = "tool".toByteArray(), mode = 493),
            TarEntry("bin/helper", data = "helper".toByteArray(), mode = 493),
        )
        SafeWholeTreeArchive.project(
            archive = archive,
            format = DeclarativeGitHubArtifactFormat.TAR_GZ,
            executablePaths = setOf("bin/tool", "bin/helper"),
            maxFiles = 16,
            maxUnpackedBytes = 1024 * 1024,
        ).use { projection ->
            val tool = projection.files.single { it.relativePath == "bin/tool" }
            val helper = projection.files.single { it.relativePath == "bin/helper" }
            check(tool.file.isFile && helper.file.isFile)
            projection.releaseLocalPayload(tool)
            assertEquals(false, tool.file.exists())
            assertEquals(true, helper.file.isFile)
        }
        archive.parentFile?.deleteRecursively()
    }

    @Test fun symlinkMayNotBeAnAncestorOfAnyProjectedEntry() {
        val archive = tarGz(
            TarEntry("bin", type = '5', mode = 493),
            TarEntry("bin/tool", data = "payload".toByteArray(), mode = 493),
            TarEntry("alias", type = '2', link = "bin", mode = 511),
            TarEntry("alias/tool", data = "shadow".toByteArray(), mode = 493),
        )
        try {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                SafeWholeTreeArchive.project(
                    archive = archive,
                    format = DeclarativeGitHubArtifactFormat.TAR_GZ,
                    executablePaths = setOf("bin/tool"),
                    maxFiles = 16,
                    maxUnpackedBytes = 1024 * 1024,
                    linkPolicy = DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE,
                )
            }
            check(failure.message.orEmpty().contains("entry below a symlink"))
        } finally {
            archive.parentFile?.deleteRecursively()
        }
    }

    private data class TarEntry(
        val name: String,
        val data: ByteArray = byteArrayOf(),
        val type: Char = '0',
        val link: String = "",
        val mode: Int = 420,
    )

    private fun tarGz(vararg entries: TarEntry): File {
        val dir = java.nio.file.Files.createTempDirectory("droide-whole-tree-").toFile()
        val file = File(dir, "fixture.tar.gz")
        GZIPOutputStream(FileOutputStream(file)).use { gzip ->
            entries.forEach { entry ->
                val header = ByteArray(512)
                ascii(header, 0, 100, entry.name)
                octal(header, 100, 8, entry.mode.toLong())
                octal(header, 108, 8, 0)
                octal(header, 116, 8, 0)
                octal(header, 124, 12, if (entry.type in setOf('0', 'x')) entry.data.size.toLong() else 0)
                octal(header, 136, 12, 0)
                for (i in 148..155) header[i] = ' '.code.toByte()
                header[156] = entry.type.code.toByte()
                ascii(header, 157, 100, entry.link)
                ascii(header, 257, 6, "ustar")
                ascii(header, 263, 2, "00")
                val checksum = header.sumOf { it.toInt() and 0xff }.toLong()
                val check = checksum.toString(8).padStart(6, '0')
                ascii(header, 148, 6, check)
                header[154] = 0
                header[155] = ' '.code.toByte()
                gzip.write(header)
                if (entry.type in setOf('0', 'x')) {
                    gzip.write(entry.data)
                    val padding = (512 - entry.data.size % 512) % 512
                    if (padding > 0) gzip.write(ByteArray(padding))
                }
            }
            gzip.write(ByteArray(1024))
        }
        return file
    }

    private fun ascii(target: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= length)
        bytes.copyInto(target, offset)
    }

    private fun octal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0')
        ascii(target, offset, length - 1, text)
        target[offset + length - 1] = 0
    }
}
