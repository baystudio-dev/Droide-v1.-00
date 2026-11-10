package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Family-only strict envelope/type preflight. Other families retain the original projector semantics. */
internal object LocalUbuntuPowerShellArchive {
    internal data class Member(val bytes: Long, val sha256: String)
    private const val PSES_FAMILY = "lsp.powershell-editor-services"
    private const val PSES_BYTES = 20_484_361L
    private const val PSES_SHA256 = "5084f0326cc88539e9d880b08f6c52ce5be4672bb8ecee8921e35f8895a6b9d7"
    private const val PSES_MAX = 256L * 1024 * 1024
    private const val PSES_ENTRY = "PowerShellEditorServices/Start-EditorServices.ps1"
    private const val MAX_ENTRIES = 8192
    private const val MAX_FILES = 4096
    private val p get() = LocalUbuntuPowerShellRuntimePolicy

    fun project(recipe: LocalUbuntuVendorRecipe, archive: File, active: () -> Unit = {}): SafeTreeProjection {
        recipe.validate()
        val runtime = recipe.familyId == p.FAMILY
        require(runtime || recipe.familyId == PSES_FAMILY) { "PowerShell archive policy is family-only" }
        if (runtime) p.requireReviewed(recipe)
        else require(recipe.version == "4.7.0" && recipe.releaseTag == "v4.7.0" && recipe.assetName == "PowerShellEditorServices.zip" &&
            recipe.assetSize == PSES_BYTES && recipe.assetSha256 == PSES_SHA256 && recipe.entryPoint == PSES_ENTRY &&
            recipe.assetUrl == "https://github.com/PowerShell/PowerShellEditorServices/releases/download/v4.7.0/PowerShellEditorServices.zip")
        val length = if (runtime) p.ARTIFACT_BYTES else PSES_BYTES
        val hash = if (runtime) p.SHA256 else PSES_SHA256
        fun requireOriginal() { require(p.measure(archive, length, active) == (length to hash)) { "PowerShell original archive length or SHA256 changed" } }
        requireOriginal()
        val expected = if (runtime) requireTarGzipStructure(archive, active) else requireZipStructure(archive, active)
        val projection = SafeWholeTreeArchive.project(archive,
            if (runtime) DeclarativeGitHubArtifactFormat.TAR_GZ else DeclarativeGitHubArtifactFormat.ZIP,
            if (runtime) p.executablePaths(recipe) else setOf(PSES_ENTRY), MAX_FILES,
            if (runtime) p.MAX_TREE_BYTES else PSES_MAX, linkPolicy = DeclarativeWholeTreeLinkPolicy.REJECT, checkActive = active)
        return try {
            requireOriginal()
            require(projection.symlinks.isEmpty() && projection.files.size == expected.size)
            projection.files.forEach { file ->
                active(); require(expected[file.relativePath] == Member(file.size, file.sha256)) { "PowerShell original preflight and projected inventory disagree: ${file.relativePath}" }
                require(file.mode == if (file.relativePath in (if (runtime) p.executablePaths(recipe) else setOf(PSES_ENTRY))) 493 else 420)
            }
            projection
        } catch (failure: Throwable) { projection.close(); throw failure }
    }

    /** No ZIP64, split disks, descriptors, encryption, prefix/trailer, duplicate identity, hidden payload or alternate type. */
    internal fun requireZipStructure(archive: File, active: () -> Unit = {}): Map<String, Member> {
        p.requireDirectory(requireNotNull(archive.absoluteFile.parentFile))
        require(Files.isRegularFile(archive.toPath(), NOFOLLOW_LINKS))
        FileChannel.open(archive.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
            val size = input.size(); require(size in 22..PSES_BYTES)
            fun read(at: Long, count: Int): ByteArray = read(input, size, at, count, active)
            val tailSize = minOf(size, 65_557L).toInt(); val tail = read(size - tailSize, tailSize)
            val ends = (0..tailSize - 22).filter { u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tailSize }
            val end = ends.singleOrNull() ?: error("PowerShell ZIP lacks a unique end at physical EOF")
            val endAt = size - tailSize + end
            val count = u16(tail, end + 10)
            require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0 && u16(tail, end + 8) == count && count in 1..MAX_ENTRIES)
            val centralSize = u32(tail, end + 12); val centralAt = u32(tail, end + 16)
            require(centralAt > 0 && centralSize >= 46 && centralAt + centralSize == endAt)
            data class Record(val path: String, val directory: Boolean, val needed: Int, val flags: Int, val method: Int,
                val time: Int, val date: Int, val crc: Long, val compressed: Long, val expanded: Long, val localAt: Long, val name: ByteArray)
            val records = mutableListOf<Record>(); val names = Names(); var at = centralAt; var total = 0L
            repeat(count) {
                val c = read(at, 46); require(u32(c, 0) == 0x02014b50L && u16(c, 34) == 0)
                val needed = u16(c, 6); val flags = u16(c, 8); val method = u16(c, 10)
                require(needed in setOf(10, 20) && method in setOf(0, 8) && (method != 8 || needed == 20) && flags and 0xf7ff == 0) { "PowerShell ZIP encryption/descriptors/flags or compression are unreviewed" }
                val compressed = u32(c, 20); val expanded = u32(c, 24); val localAt = u32(c, 42)
                val ns = u16(c, 28); val es = u16(c, 30); val cs = u16(c, 32)
                require(ns in 1..4096 && at + 46 + ns + es + cs <= endAt && localAt < centralAt)
                val rawName = read(at + 46, ns); val name = decodeName(rawName)
                val directory = name.endsWith('/'); val path = names.add(name, directory)
                val external = u32(c, 38); val mode = (external ushr 16).toInt(); val type = mode and 0xf000
                require(type in (if (directory) setOf(0, 0x4000) else setOf(0, 0x8000)) && mode and 0x0e00 == 0 && external and 0x440 == 0L &&
                    (external and 0x10 == 0L || directory)) { "PowerShell ZIP links, special files and inconsistent directory types are forbidden" }
                requireExtra(read(at + 46 + ns, es))
                require(compressed <= PSES_BYTES && expanded <= PSES_MAX && (expanded == 0L || compressed > 0 && expanded <= compressed * 200) && (method != 0 || compressed == expanded))
                if (directory) require(expanded == 0L)
                else { total = Math.addExact(total, expanded); require(total <= PSES_MAX && records.count { !it.directory } < MAX_FILES) }
                records += Record(path, directory, needed, flags, method, u16(c, 12), u16(c, 14), u32(c, 16), compressed, expanded, localAt, rawName)
                at += 46L + ns + es + cs
            }
            require(at == endAt) { "PowerShell ZIP unexplained central-directory bytes" }
            val members = linkedMapOf<String, Member>(); var localEnd = 0L
            for (record in records.sortedBy { it.localAt }) {
                active(); require(record.localAt == localEnd) { "PowerShell ZIP prefix, overlap or local-record gap" }
                val l = read(record.localAt, 30); val ns = u16(l, 26); val es = u16(l, 28)
                require(u32(l, 0) == 0x04034b50L && u16(l, 4) == record.needed && u16(l, 6) == record.flags && u16(l, 8) == record.method &&
                    u16(l, 10) == record.time && u16(l, 12) == record.date && u32(l, 14) == record.crc && u32(l, 18) == record.compressed &&
                    u32(l, 22) == record.expanded && ns == record.name.size && read(record.localAt + 30, ns).contentEquals(record.name)) { "PowerShell ZIP local/central identity mismatch" }
                requireExtra(read(record.localAt + 30 + ns, es))
                val dataAt = record.localAt + 30 + ns + es; localEnd = dataAt + record.compressed
                require(localEnd <= centralAt)
                val digest = MessageDigest.getInstance("SHA-256"); val crc = CRC32(); var decoded = 0L
                fun consume(bytes: ByteArray, n: Int) { decoded = Math.addExact(decoded, n.toLong()); require(decoded <= record.expanded); digest.update(bytes, 0, n); crc.update(bytes, 0, n) }
                if (record.method == 0) {
                    var pos = dataAt
                    while (pos < localEnd) { val b = read(pos, minOf(65536L, localEnd - pos).toInt()); consume(b, b.size); pos += b.size }
                } else inflate(input, size, dataAt, record.compressed, active, ::consume).also { require(it == localEnd) }
                require(decoded == record.expanded && crc.value == record.crc) { "PowerShell ZIP CRC or decoded length changed" }
                if (!record.directory) members[record.path] = Member(decoded, hex(digest.digest()))
            }
            require(localEnd == centralAt && input.size() == size && members.isNotEmpty())
            return members
        }
    }

    /** One gzip member with checked CRC/ISIZE and exact EOF, then full bounded TAR including both end blocks. */
    internal fun requireTarGzipStructure(archive: File, active: () -> Unit = {}): Map<String, Member> {
        p.requireDirectory(requireNotNull(archive.absoluteFile.parentFile)); require(Files.isRegularFile(archive.toPath(), NOFOLLOW_LINKS))
        val decoded = File.createTempFile(".powershell-envelope-", ".tar", archive.absoluteFile.parentFile)
        try {
            FileChannel.open(archive.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
                val length = input.size(); require(length in 18..p.ARTIFACT_BYTES)
                val h = read(input, length, 0, 10, active)
                require(u16(h, 0) == 0x8b1f && h[2].toInt() == 8 && (h[3].toInt() and 255) in setOf(0, 8)) { "PowerShell gzip unsupported header/flags" }
                var start = 10L
                if (h[3].toInt() and 8 != 0) {
                    var n = 0
                    while (true) { require(++n <= 1024); val byte = read(input, length, start++, 1, active)[0].toInt() and 255; if (byte == 0) break; require(byte in 32..126) }
                }
                val crc = CRC32(); var bytes = 0L
                val bound = p.MAX_TREE_BYTES + MAX_ENTRIES * 1024L + 10_240L
                FileChannel.open(decoded.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { output ->
                    val end = inflate(input, length, start, length - start, active) { b, n ->
                        bytes = Math.addExact(bytes, n.toLong()); require(bytes <= bound && bytes <= length * 200)
                        crc.update(b, 0, n); val buffer = ByteBuffer.wrap(b, 0, n)
                        while (buffer.hasRemaining()) { active(); require(output.write(buffer) > 0) }
                    }
                    require(end + 8 == length) { "PowerShell gzip concatenated member or trailing bytes" }
                    val trailer = read(input, length, end, 8, active)
                    require(u32(trailer, 0) == crc.value && u32(trailer, 4) == bytes && input.size() == length) { "PowerShell gzip CRC/ISIZE mismatch" }
                }
            }
            return requireTarStructure(decoded, active)
        } finally { require(Files.deleteIfExists(decoded.toPath())) { "Cannot clean PowerShell envelope temporary file" } }
    }

    internal fun requireTarStructure(tar: File, active: () -> Unit = {}): Map<String, Member> {
        require(Files.isRegularFile(tar.toPath(), NOFOLLOW_LINKS))
        FileChannel.open(tar.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
            val length = input.size(); require(length >= 1024 && length % 512 == 0L && length <= p.MAX_TREE_BYTES + MAX_ENTRIES * 1024L + 10_240)
            var at = 0L; var count = 0; var bytes = 0L; val names = Names(); val members = linkedMapOf<String, Member>()
            while (true) {
                val h = read(input, length, at, 512, active); at += 512
                if (h.all { it.toInt() == 0 }) {
                    require(length - at in 512..10_240 && read(input, length, at, 512, active).all { it.toInt() == 0 }) { "PowerShell TAR requires two end blocks" }
                    while (at < length) { val b = read(input, length, at, minOf(65536L, length - at).toInt(), active); require(b.all { it.toInt() == 0 }) { "PowerShell TAR hidden content after end" }; at += b.size }
                    require(members.isNotEmpty()); return members
                }
                require(++count <= MAX_ENTRIES)
                val expectedChecksum = octal(h, 148, 8)
                val checksum = h.indices.sumOf { if (it in 148..155) 32L else (h[it].toInt() and 255).toLong() }
                require(checksum == expectedChecksum) { "PowerShell TAR checksum mismatch" }
                val magic = h.copyOfRange(257, 263)
                require(magic.contentEquals("ustar\u0000".toByteArray()) || magic.contentEquals("ustar ".toByteArray())) { "PowerShell TAR header format is unreviewed" }
                val type = h[156].toInt() and 255; require(type in setOf(0, 48, 53)) { "PowerShell TAR links, PAX, sparse, long-name or special types require separate review" }
                require(field(h, 157, 100).isEmpty() && octal(h, 329, 8) == 0L && octal(h, 337, 8) == 0L)
                val mode = octal(h, 100, 8); require(mode and 0xe00 == 0L && mode <= 0xfff)
                val size = octal(h, 124, 12); require(size <= p.MAX_TREE_BYTES)
                val prefix = field(h, 345, 155); val name = field(h, 0, 100)
                val directory = type == 53; val path = names.add(if (prefix.isEmpty()) name else "$prefix/$name", directory)
                if (directory) require(size == 0L)
                else { bytes = Math.addExact(bytes, size); require(bytes <= p.MAX_TREE_BYTES && members.size < MAX_FILES) }
                val end = Math.addExact(at, size); require(end <= length)
                val digest = MessageDigest.getInstance("SHA-256")
                while (at < end) { val b = read(input, length, at, minOf(65536L, end - at).toInt(), active); digest.update(b); at += b.size }
                val padding = (512 - size % 512) % 512
                if (padding > 0) { require(read(input, length, at, padding.toInt(), active).all { it.toInt() == 0 }) { "PowerShell TAR nonzero file padding" }; at += padding }
                if (!directory) members[path] = Member(size, hex(digest.digest()))
            }
        }
    }

    private class Names {
        private val names = linkedMapOf<String, Boolean>(); private val folded = hashSetOf<String>(); private val components = hashMapOf<String, String>()
        fun add(raw: String, directory: Boolean): String {
            require(raw.isNotEmpty() && (directory || !raw.endsWith('/')))
            val path = if (directory) raw.removeSuffix("/") else raw
            LocalUbuntuPowerShellRuntimePolicy.requireRelative(path)
            require(!names.containsKey(path) && folded.add(path.lowercase(Locale.ROOT))) { "PowerShell archive duplicate/case-colliding path: $path" }
            val segments = path.split('/'); var spelling = ""
            for (part in segments) {
                spelling += if (spelling.isEmpty()) part else "/$part"
                val foldedSpelling = spelling.lowercase(Locale.ROOT)
                require(components[foldedSpelling] == null || components[foldedSpelling] == spelling) { "PowerShell archive case-colliding ancestor" }
                components[foldedSpelling] = spelling
            }
            var prefix = ""
            for (part in segments.dropLast(1)) { prefix += if (prefix.isEmpty()) part else "/$part"; require(names[prefix] != false) { "PowerShell archive file ancestor conflict" } }
            if (!directory) require(names.keys.none { it.startsWith("$path/") }) { "PowerShell archive file-directory conflict" }
            names[path] = directory; return path
        }
    }
    private fun requireExtra(bytes: ByteArray) {
        var at = 0; val ids = hashSetOf<Int>()
        while (at < bytes.size) {
            require(bytes.size - at >= 4); val id = u16(bytes, at); val size = u16(bytes, at + 2); at += 4
            require(size <= bytes.size - at && ids.add(id) && id !in setOf(0x0001, 0x000d, 0x5855, 0x756e, 0x7075)) { "PowerShell ZIP identity-changing or malformed extra field" }; at += size
        }
    }
    private fun field(bytes: ByteArray, at: Int, length: Int): String {
        val source = bytes.copyOfRange(at, at + length); val end = source.indexOf(0).let { if (it < 0) source.size else it }
        require(source.drop(end).all { it.toInt() == 0 }); return decodeName(source.copyOf(end))
    }
    private fun octal(bytes: ByteArray, at: Int, length: Int): Long {
        val text = bytes.copyOfRange(at, at + length).map { it.toInt() and 255 }
        require(text.all { it == 0 || it == 32 || it in 48..55 }) { "PowerShell TAR unreviewed numeric encoding" }
        val value = text.map { it.toChar() }.joinToString("").trim(' ', '\u0000')
        require(value.none { it == ' ' || it == '\u0000' }); return if (value.isEmpty()) 0 else value.toLong(8)
    }
    private fun decodeName(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    private fun read(input: FileChannel, length: Long, at: Long, count: Int, active: () -> Unit): ByteArray {
        require(at >= 0 && count >= 0 && at <= length && count <= length - at) { "PowerShell archive truncated or overflowing record" }
        val b = ByteBuffer.allocate(count); input.position(at)
        while (b.hasRemaining()) { active(); require(input.read(b) > 0) { "PowerShell archive truncated" } }; return b.array()
    }
    private fun inflate(input: FileChannel, length: Long, start: Long, available: Long, active: () -> Unit, consume: (ByteArray, Int) -> Unit): Long {
        val inflater = Inflater(true); var at = start; var remaining = available; val output = ByteArray(65536)
        try {
            while (!inflater.finished()) {
                active()
                if (inflater.needsInput()) { require(remaining > 0); val b = read(input, length, at, minOf(65536L, remaining).toInt(), active); inflater.setInput(b); at += b.size; remaining -= b.size }
                val n = inflater.inflate(output); require(!inflater.needsDictionary())
                if (n > 0) consume(output, n) else require(inflater.finished() || inflater.needsInput()) { "PowerShell compressed stream stalled" }
            }
            return start + inflater.bytesRead
        } finally { inflater.end() }
    }
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
}
