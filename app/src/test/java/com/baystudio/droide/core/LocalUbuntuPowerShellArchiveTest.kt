package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Synthetic archive envelopes only; no original payload downloaded/executed. */
class LocalUbuntuPowerShellArchiveTest {
    private val policy get() = LocalUbuntuPowerShellArchive
    private fun reject(block: () -> Unit) = assertTrue("Expected strict archive rejection", runCatching(block).isFailure)
    private fun temporary(block: (File) -> Unit) { val d = Files.createTempDirectory("powershell-archive-").toFile(); try { block(d) } finally { d.deleteRecursively() } }
    private fun zipCheck(root: File, bytes: ByteArray) = File(root, "input.zip").also { it.writeBytes(bytes) }.let { policy.requireZipStructure(it) }
    private fun tarCheck(root: File, bytes: ByteArray) = File(root, "input.tar").also { it.writeBytes(bytes) }.let { policy.requireTarStructure(it) }
    private fun gzipCheck(root: File, bytes: ByteArray) = File(root, "input.tar.gz").also { it.writeBytes(bytes) }.let { policy.requireTarGzipStructure(it) }

    @Test fun zipStoredDeflatedDirectoryAndDataMembersAreMeasured() = temporary { root ->
        for (deflate in listOf(false, true)) {
            val result = zipCheck(root, zip(listOf("PowerShellEditorServices/" to "", "PowerShellEditorServices/Start-EditorServices.ps1" to "# original", "LICENSE" to "license"), deflate))
            assertEquals(setOf("PowerShellEditorServices/Start-EditorServices.ps1", "LICENSE"), result.keys)
            assertEquals(10, result.getValue("PowerShellEditorServices/Start-EditorServices.ps1").bytes)
        }
    }
    @Test fun zipRejectsUnsafeAndDuplicatePaths() = temporary { root ->
        for (name in listOf("../x", "/x", "C:/x", "a\\b", "a/../b", "./a", "a//b", "a\u0000b")) reject { zipCheck(root, zip(listOf(name to "x"))) }
        for (names in listOf(listOf("a", "a"), listOf("a", "A"), listOf("a", "a/b"), listOf("a/b", "a"), listOf("a/", "a"), listOf("Foo/a", "foo/b")))
            reject { zipCheck(root, zip(names.map { it to if (it.endsWith('/')) "" else "x" })) }
    }
    @Test fun zipRejectsTypesDescriptorsEncryptionAndCentralLocalDrift() = temporary { root ->
        val original = zip(listOf("file" to "hello"))
        val central = original.indexOfSequence(byteArrayOf(0x50, 0x4b, 1, 2))
        for (mode in listOf(0xa1ff, 0x21a4, 0x11a4, 0x61a4, 0x41ed, 0x89ed)) {
            val b = original.copyOf(); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 38, mode shl 16)
            reject { zipCheck(root, b) }
        }
        for (flag in listOf(1, 8, 16, 64, 0x2000)) {
            val b = original.copyOf(); val v = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); v.putShort(6, flag.toShort()); v.putShort(central + 8, flag.toShort())
            reject { zipCheck(root, b) }
        }
        for (change in listOf<(ByteBuffer) -> Unit>({ it.putInt(14, 0) }, { it.putInt(18, 1) }, { it.putShort(8, 99) }, { it.put(30, 'z'.code.toByte()) }, { it.putInt(central + 42, 1) }, { it.putShort(central + 34, 1) })) {
            val b = original.copyOf(); change(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)); reject { zipCheck(root, b) }
        }
    }
    @Test fun zipRejectsEnvelopePrefixesTrailersTruncationAndCrc() = temporary { root ->
        val original = zip(listOf("file" to "hello"), true)
        for (b in listOf(byteArrayOf(1) + original, original + byteArrayOf(0), original.copyOf(original.size - 1), original.copyOf(40))) reject { zipCheck(root, b) }
        val crc = original.copyOf(); crc[34] = (crc[34].toInt() xor 1).toByte(); reject { zipCheck(root, crc) }
        val truncated = zip(listOf("file" to "hello")); truncated[22] = 127; reject { zipCheck(root, truncated) }
    }
    @Test fun tarOrdinaryFilesAndDirectoriesAreMeasured() = temporary { root ->
        val result = tarCheck(root, tar(listOf(Triple("Modules/", "", '5'), Triple("pwsh", "ELF fixture", '0'), Triple("Modules/Test.psd1", "@{}", '0'))))
        assertEquals(setOf("pwsh", "Modules/Test.psd1"), result.keys); assertEquals(11, result.getValue("pwsh").bytes)
    }
    @Test fun tarRejectsLinksDevicesPaxSparseAndTraversal() = temporary { root ->
        for (type in listOf('1', '2', '3', '4', '6', '7', 'x', 'g', 'L', 'K', 'S')) reject { tarCheck(root, tar(listOf(Triple("file", "", type)))) }
        for (name in listOf("../x", "/x", "C:/x", "a\\b", "a/../b", "./a", "a//b")) reject { tarCheck(root, tar(listOf(Triple(name, "x", '0')))) }
        for (names in listOf(listOf("a", "a"), listOf("a", "A"), listOf("a", "a/b"), listOf("a/b", "a")))
            reject { tarCheck(root, tar(names.map { Triple(it, "x", '0') })) }
    }
    @Test fun tarRequiresTwoEndsZeroPaddingAndNoHiddenTail() = temporary { root ->
        val original = tar(listOf(Triple("file", "hello", '0')))
        reject { tarCheck(root, original.copyOf(original.size - 512)) }
        reject { tarCheck(root, original + tar(listOf(Triple("hidden", "x", '0')))) }
        val padding = original.copyOf(); padding[520] = 1; reject { tarCheck(root, padding) }
        val checksum = original.copyOf(); checksum[0] = 'z'.code.toByte(); reject { tarCheck(root, checksum) }
        val truncated = original.copyOf(700); reject { tarCheck(root, truncated) }
    }
    @Test fun gzipSingleMemberChecksCrcSizeAndPhysicalEof() = temporary { root ->
        val tar = tar(listOf(Triple("pwsh", "synthetic", '0'))); val original = gzip(tar)
        assertEquals(9, gzipCheck(root, original).getValue("pwsh").bytes)
        for (bad in listOf(original + original, original + byteArrayOf(0), original.copyOf(original.size - 1))) reject { gzipCheck(root, bad) }
        for (at in listOf(0, 2, 3, original.size - 8, original.size - 4)) {
            val bad = original.copyOf(); bad[at] = (bad[at].toInt() xor 1).toByte(); reject { gzipCheck(root, bad) }
        }
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".powershell-envelope-") })
    }
    @Test fun archiveCancellationCleansBoundedTemporary() = temporary { root ->
        val file = File(root, "input.tar.gz").apply { writeBytes(gzip(tar(listOf(Triple("file", "hello", '0'))))) }
        reject { policy.requireTarGzipStructure(file) { error("cancel") } }
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".powershell-envelope-") })
    }
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    private fun tar(entries: List<Triple<String, String, Char>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, body, type) in entries) {
            val h = ByteArray(512); val data = body.toByteArray()
            name.toByteArray().copyInto(h); "0000755\u0000".toByteArray().copyInto(h, 100)
            "%011o\u0000".format(data.size).toByteArray().copyInto(h, 124); "ustar\u0000".toByteArray().copyInto(h, 257)
            h[156] = type.code.toByte(); for (i in 148..155) h[i] = 32
            "%06o\u0000 ".format(h.sumOf { it.toInt() and 255 }).toByteArray().copyInto(h, 148)
            out.write(h); out.write(data); out.write(ByteArray((512 - data.size % 512) % 512))
        }
        out.write(ByteArray(1024)); return out.toByteArray()
    }
    private fun zip(entries: List<Pair<String, String>>, deflate: Boolean = false): ByteArray {
        val local = ByteArrayOutputStream(); val central = ByteArrayOutputStream()
        for ((name, content) in entries) {
            val raw = content.toByteArray(); val bytes = if (!deflate) raw else {
                val compressor = Deflater(6, true); try { compressor.setInput(raw); compressor.finish(); val out = ByteArrayOutputStream(); val buf = ByteArray(128)
                    while (!compressor.finished()) { val n = compressor.deflate(buf); out.write(buf, 0, n) }; out.toByteArray() } finally { compressor.end() }
            }
            val crc = CRC32().apply { update(raw) }.value; val n = name.toByteArray(); val offset = local.size()
            val l = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            l.putInt(0x04034b50); l.putShort(if (deflate) 20 else 10); l.putShort(0); l.putShort(if (deflate) 8 else 0); l.putInt(0); l.putInt(crc.toInt()); l.putInt(bytes.size); l.putInt(raw.size); l.putShort(n.size.toShort()); l.putShort(0)
            local.write(l.array()); local.write(n); local.write(bytes)
            val c = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
            c.putInt(0x02014b50); c.putShort(0x0314); c.putShort(if (deflate) 20 else 10); c.putShort(0); c.putShort(if (deflate) 8 else 0); c.putInt(0); c.putInt(crc.toInt()); c.putInt(bytes.size); c.putInt(raw.size); c.putShort(n.size.toShort()); c.putShort(0); c.putShort(0); c.putShort(0); c.putShort(0); c.putInt((if (name.endsWith('/')) 0x41ed else 0x81a4) shl 16); c.putInt(offset)
            central.write(c.array()); central.write(n)
        }
        val e = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        e.putInt(0x06054b50); e.putShort(0); e.putShort(0); e.putShort(entries.size.toShort()); e.putShort(entries.size.toShort()); e.putInt(central.size()); e.putInt(local.size()); e.putShort(0)
        return local.toByteArray() + central.toByteArray() + e.array()
    }
    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int = (0..size - sequence.size).first { start -> sequence.indices.all { this[start + it] == sequence[it] } }
}
