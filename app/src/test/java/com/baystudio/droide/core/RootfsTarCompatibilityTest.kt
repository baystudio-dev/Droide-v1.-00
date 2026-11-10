package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RootfsTarCompatibilityTest {
    @Test fun ordinaryFilesAndSymlinksAreUnchanged() {
        val archive = archive(member("usr/bin/tool", payload = "hello".toByteArray()), member("bin", type = '2', link = "usr/bin"))
        assertArrayEquals(archive, convert(archive))
    }

    @Test fun hardLinkIsAnIndependentRegularFileWithTargetBytesAndPermissions() {
        val payload = ByteArray(1_537) { (it % 251).toByte() }
        val archive = archive(member("usr/bin/perl", payload = payload), member("usr/bin/perl5", type = '1', link = "usr/bin/perl", mode = "0000644"))
        val result = convert(archive, mapOf("usr/bin/perl5" to "usr/bin/perl"))
        val aliasOffset = 512 + 2_048
        assertEquals('0'.code.toByte(), result[aliasOffset + 156])
        assertEquals("00000003001", String(result, aliasOffset + 124, 11))
        assertEquals("0000755", String(result, aliasOffset + 100, 7))
        assertArrayEquals(payload, result.copyOfRange(aliasOffset + 512, aliasOffset + 512 + payload.size))
        assertArrayEquals(result, convert(result))
    }

    @Test fun severalAliasesCanShareATargetWithoutSharingAnInode() {
        val result = convert(archive(member("bin/base", payload = byteArrayOf(42)),
            member("bin/a", type = '1', link = "bin/base"), member("bin/b", type = '1', link = "bin/base")),
            mapOf("bin/a" to "bin/base", "bin/b" to "bin/base"))
        assertEquals('0'.code.toByte(), result[1_024 + 156])
        assertEquals('0'.code.toByte(), result[2_048 + 156])
        assertEquals(42.toByte(), result[1_536])
        assertEquals(42.toByte(), result[2_560])
    }

    @Test fun missingReviewedLinkAndUnreviewedLinkAreRejected() {
        rejected(archive(member("bin/base")), mapOf("bin/a" to "bin/base"))
        rejected(archive(member("bin/base"), member("bin/a", type = '1', link = "bin/base")))
    }

    @Test fun wrongTargetForwardReferenceAndDuplicateAliasAreRejected() {
        val aliases = mapOf("bin/a" to "bin/base")
        rejected(archive(member("bin/base"), member("bin/a", type = '1', link = "bin/other")), aliases)
        rejected(archive(member("bin/a", type = '1', link = "bin/base"), member("bin/base")), aliases)
        rejected(archive(member("bin/base"), member("bin/a", type = '1', link = "bin/base"), member("bin/a", type = '1', link = "bin/base")), aliases)
    }

    @Test fun escapingLinkPathsAreRejected() {
        listOf("/etc/passwd", "../outside", "bin/../outside", "bin\\outside", "bin//base").forEach {
            rejected(archive(member("bin/base")), mapOf("bin/a" to it))
        }
    }

    @Test fun truncatedHeaderPayloadAndDamagedChecksumAreRejected() {
        val original = archive(member("bin/base", payload = ByteArray(700)))
        rejected(original.copyOf(400))
        rejected(original.copyOf(800))
        rejected(original.copyOf().apply { this[0] = (this[0] + 1).toByte() })
        rejected(archive(member("bin/base")).copyOf().apply { this[lastIndex] = 1 })
    }

    @Test fun targetCacheHasABoundBeforeAllocatingPayload() {
        val header = member("bin/base").copyOf(512)
        "000100000001".toByteArray().copyInto(header, 124) // 16 MiB + 1, without allocating that payload.
        checksum(header)
        rejected(archive(header), mapOf("bin/a" to "bin/base"))
    }

    @Test fun cancellationInterruptsStreamingBeforeCompletion() {
        var checks = 0
        val failure = runCatching {
            RootfsTarCompatibility.copy(ByteArrayInputStream(archive(member("bin/base", payload = ByteArray(200_000)))),
                ByteArrayOutputStream(), emptyMap()) { if (++checks == 5) throw CancellationException("cancelled") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun paxMetadataAndUstarPrefixesArePreserved() {
        val entry = member("base", payload = byteArrayOf(13))
        "usr/bin".toByteArray().copyInto(entry, 345)
        checksum(entry)
        val pax = member("PaxHeaders/base", type = 'x', payload = "20 atime=1788826928\n".toByteArray())
        val result = convert(archive(pax, entry, member("usr/bin/alias", type = '1', link = "usr/bin/base")),
            mapOf("usr/bin/alias" to "usr/bin/base"))
        assertArrayEquals(pax, result.copyOfRange(0, pax.size))
        assertEquals(13.toByte(), result[2_560])
    }

    private fun convert(bytes: ByteArray, links: Map<String, String> = emptyMap()): ByteArray = ByteArrayOutputStream().also {
        RootfsTarCompatibility.copy(ByteArrayInputStream(bytes), it, links)
    }.toByteArray()

    private fun rejected(bytes: ByteArray, links: Map<String, String> = emptyMap()) {
        assertTrue(runCatching { convert(bytes, links) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun archive(vararg entries: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        entries.forEach { write(it) }; write(ByteArray(1_024))
    }.toByteArray()

    private fun member(path: String, type: Char = '0', link: String = "", payload: ByteArray = byteArrayOf(), mode: String = "0000755"): ByteArray {
        val header = ByteArray(512)
        path.toByteArray().copyInto(header)
        mode.toByteArray().copyInto(header, 100)
        payload.size.toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
        header[156] = type.code.toByte(); link.toByteArray().copyInto(header, 157)
        "ustar\u0000".toByteArray().copyInto(header, 257)
        checksum(header)
        return header + payload + ByteArray((512 - payload.size % 512) % 512)
    }

    private fun checksum(header: ByteArray) {
        header.fill(' '.code.toByte(), 148, 156)
        (header.take(512).sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ").toByteArray().copyInto(header, 148)
    }
}
