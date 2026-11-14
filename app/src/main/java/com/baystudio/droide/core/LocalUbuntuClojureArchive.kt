package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Clojure-only ZIP envelope preflight; the shared projector and other vendor families stay unchanged. */
internal object LocalUbuntuClojureArchive {
    internal data class Member(val size: Long, val sha256: String)

    fun project(recipe: LocalUbuntuVendorRecipe, archive: File, active: () -> Unit = {}): SafeTreeProjection {
        recipe.validate(); LocalUbuntuClojurePolicy.requireReviewed(recipe)
        requireOriginalArtifact(archive, active)
        val member = requireZipStructure(archive, active)
        val projection = SafeWholeTreeArchive.project(archive, DeclarativeGitHubArtifactFormat.ZIP,
            setOf(LocalUbuntuClojurePolicy.COMMAND), 1, LocalUbuntuClojurePolicy.MAX_MEMBER_BYTES,
            linkPolicy = DeclarativeWholeTreeLinkPolicy.REJECT, checkActive = active)
        return try {
            requireOriginalArtifact(archive, active)
            val file = projection.files.single()
            require(projection.symlinks.isEmpty() && file.relativePath == LocalUbuntuClojurePolicy.COMMAND &&
                file.mode == 493 && file.size == member.size && file.sha256 == member.sha256) {
                "Clojure LSP ZIP preflight and projected member disagree"
            }
            LocalUbuntuClojurePolicy.requireElfStructure(file.file)
            decorate(projection)
        } catch (failure: Throwable) {
            projection.close(); throw failure
        }
    }

    /** Production always requires the original compressed bytes; synthetic ZIPs use only structural seams. */
    internal fun requireOriginalArtifact(archive: File, active: () -> Unit = {}) {
        val measured = LocalUbuntuClojurePolicy.measure(archive, LocalUbuntuClojurePolicy.ARTIFACT_BYTES, active)
        require(measured.first == LocalUbuntuClojurePolicy.ARTIFACT_BYTES && measured.second == LocalUbuntuClojurePolicy.SHA256) {
            "Clojure LSP requires the unchanged original publisher ZIP length and SHA256"
        }
    }

    /** One exact root member, regular Unix type (or unspecified), no ZIP64/encryption/prefix/trailer/gaps. */
    internal fun requireZipStructure(archive: File, active: () -> Unit = {}): Member {
        require(Files.isRegularFile(archive.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Clojure LSP ZIP must be a regular nonlink file" }
        FileChannel.open(archive.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val length = input.size()
            require(length in 22..LocalUbuntuClojurePolicy.ARTIFACT_BYTES) { "Clojure LSP ZIP exceeds its original compressed bound" }
            fun read(at: Long, size: Int): ByteArray {
                require(at >= 0 && size >= 0 && at <= length && size <= length - at) { "Clojure LSP ZIP record escapes the archive" }
                val bytes = ByteBuffer.allocate(size); input.position(at)
                while (bytes.hasRemaining()) { active(); require(input.read(bytes) > 0) { "Truncated Clojure LSP ZIP" } }
                return bytes.array()
            }
            val tailSize = minOf(length, 65_557L).toInt()
            val tail = read(length - tailSize, tailSize)
            val endings = (0..tailSize - 22).filter { u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tailSize }
            val end = endings.singleOrNull() ?: error("Missing or ambiguous Clojure LSP ZIP end at physical EOF")
            val endAt = length - tailSize + end
            require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0 &&
                u16(tail, end + 8) == 1 && u16(tail, end + 10) == 1) { "Clojure LSP ZIP must have one member on one disk" }
            val centralAt = u32(tail, end + 16); val centralSize = u32(tail, end + 12)
            require(centralAt > 0 && centralSize >= 46 && centralAt + centralSize == endAt) { "Invalid Clojure LSP central directory bounds" }
            val central = read(centralAt, 46)
            require(u32(central, 0) == 0x02014b50L && u16(central, 34) == 0 && u32(central, 42) == 0L) {
                "Clojure LSP ZIP rejects ZIP64, alternate disks and leading prefixes"
            }
            val needed = u16(central, 6); val flags = u16(central, 8); val method = u16(central, 10)
            require(needed in setOf(10, 20) && method in setOf(0, 8) && (method != 8 || needed == 20) && flags and 0xf7f1 == 0 &&
                (method == 8 || flags and 14 == 0)) { "Unsupported Clojure LSP ZIP compression, encryption or flags" }
            val crc = u32(central, 16); val compressed = u32(central, 20); val expanded = u32(central, 24)
            require(compressed in 1..LocalUbuntuClojurePolicy.ARTIFACT_BYTES && expanded in 1..LocalUbuntuClojurePolicy.MAX_MEMBER_BYTES &&
                expanded <= compressed * 200L && (method != 0 || compressed == expanded)) { "Clojure LSP ZIP sizes exceed their bounded contract" }
            val nameSize = u16(central, 28); val extraSize = u16(central, 30); val commentSize = u16(central, 32)
            val external = u32(central, 38); val mode = (external ushr 16).toInt()
            require(mode and 0xf000 in setOf(0, 0x8000) && mode and 0x0e00 == 0 && external and 0x458 == 0L) {
                "Clojure LSP ZIP rejects nonregular file modes, links, devices and directories"
            }
            val expectedName = LocalUbuntuClojurePolicy.COMMAND.toByteArray(Charsets.US_ASCII)
            require(nameSize == expectedName.size && centralSize == 46L + nameSize + extraSize + commentSize &&
                read(centralAt + 46, nameSize).contentEquals(expectedName)) { "Clojure LSP ZIP must contain only the exact root clojure-lsp member" }
            requireExtraFields(read(centralAt + 46 + nameSize, extraSize))
            val local = read(0, 30)
            require(u32(local, 0) == 0x04034b50L && u16(local, 4) == needed && u16(local, 6) == flags &&
                u16(local, 8) == method && u16(local, 10) == u16(central, 12) && u16(local, 12) == u16(central, 14) &&
                u16(local, 26) == nameSize && read(30, nameSize).contentEquals(expectedName)) { "Clojure LSP ZIP local and central metadata disagree" }
            val localExtra = u16(local, 28)
            requireExtraFields(read(30L + nameSize, localExtra))
            val dataAt = 30L + nameSize + localExtra
            val dataEnd = Math.addExact(dataAt, compressed)
            require(dataEnd <= centralAt) { "Clojure LSP ZIP data overlaps its directory" }
            if (flags and 8 == 0) {
                require(u32(local, 14) == crc && u32(local, 18) == compressed && u32(local, 22) == expanded && dataEnd == centralAt) {
                    "Clojure LSP ZIP local size/CRC mismatch or trailing data"
                }
            } else {
                // Only DEFLATE descriptors are supported; STORE+descriptor is deliberately refused above.
                require(u32(local, 14) == 0L && u32(local, 18) == 0L && u32(local, 22) == 0L) {
                    "Clojure LSP ZIP descriptor must have zero local size/CRC fields"
                }
                val descriptorSize = centralAt - dataEnd
                require(descriptorSize == 12L || descriptorSize == 16L) { "Unsupported Clojure LSP ZIP descriptor length" }
                val descriptor = read(dataEnd, descriptorSize.toInt())
                val start = if (descriptorSize == 16L) {
                    require(u32(descriptor, 0) == 0x08074b50L) { "Invalid Clojure LSP ZIP descriptor signature" }; 4
                } else 0
                require(u32(descriptor, start) == crc && u32(descriptor, start + 4) == compressed && u32(descriptor, start + 8) == expanded) {
                    "Clojure LSP ZIP descriptor disagrees with central metadata"
                }
            }
            val digest = MessageDigest.getInstance("SHA-256"); val observedCrc = CRC32()
            var decoded = 0L
            fun consume(bytes: ByteArray, size: Int) {
                decoded = Math.addExact(decoded, size.toLong())
                require(decoded <= expanded) { "Clojure LSP ZIP decoded member exceeds declared bound" }
                digest.update(bytes, 0, size); observedCrc.update(bytes, 0, size)
            }
            var remaining = compressed; var position = dataAt
            if (method == 0) {
                while (remaining > 0) {
                    val bytes = read(position, minOf(65_536L, remaining).toInt())
                    consume(bytes, bytes.size); position += bytes.size; remaining -= bytes.size
                }
            } else {
                val inflater = Inflater(true)
                try {
                    val buffer = ByteArray(65_536)
                    while (!inflater.finished()) {
                        active()
                        if (inflater.needsInput()) {
                            require(remaining > 0) { "Truncated Clojure LSP raw DEFLATE stream" }
                            val bytes = read(position, minOf(65_536L, remaining).toInt())
                            position += bytes.size; remaining -= bytes.size; inflater.setInput(bytes)
                        }
                        val count = inflater.inflate(buffer)
                        require(!inflater.needsDictionary()) { "Clojure LSP ZIP cannot require a DEFLATE dictionary" }
                        if (count > 0) consume(buffer, count)
                        else require(inflater.finished() || inflater.needsInput()) { "Invalid or stalled Clojure LSP DEFLATE stream" }
                    }
                    require(remaining == 0L && inflater.remaining == 0 && inflater.bytesRead == compressed) {
                        "Clojure LSP ZIP compressed member contains trailing data"
                    }
                } finally { inflater.end() }
            }
            require(decoded == expanded && observedCrc.value == crc && input.size() == length &&
                Files.isRegularFile(archive.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Clojure LSP ZIP decoded size/CRC or source identity changed" }
            return Member(decoded, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }
    }

    /** Supplemental files are new projection members, never retroactively claimed as ZIP contents. */
    internal fun decorate(projection: SafeTreeProjection): SafeTreeProjection {
        val member = projection.files.single()
        require(projection.symlinks.isEmpty() && member.relativePath == LocalUbuntuClojurePolicy.COMMAND && member.mode == 493) { "Clojure LSP original projection has unexpected members" }
        LocalUbuntuClojurePolicy.requireInventory(projection.root, setOf(LocalUbuntuClojurePolicy.COMMAND))
        require(LocalUbuntuClojurePolicy.measure(member.file, LocalUbuntuClojurePolicy.MAX_MEMBER_BYTES) == (member.size to member.sha256))
        val notices = File(projection.root, "DROIDE-NOTICES")
        check(notices.mkdir()) { "Clojure LSP supplemental notices collide with an existing member" }
        val additions = listOf(LocalUbuntuClojurePolicy.NOTICE_PATH to LocalUbuntuClojurePolicy.licenseBytes,
            LocalUbuntuClojurePolicy.ORIGIN_PATH to LocalUbuntuClojurePolicy.originBytes(member.sha256, member.size)).map { (path, bytes) ->
            val file = File(projection.root, path)
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS).use { output ->
                val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) check(output.write(buffer) > 0)
            }
            SafeTreeFile(path, file, 420, bytes.size.toLong(), LocalUbuntuClojurePolicy.sha256(bytes))
        }
        val files = (projection.files + additions).sortedBy { it.relativePath }
        val total = files.sumOf { it.size }; require(total <= LocalUbuntuClojurePolicy.MAX_TREE_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            digest.update(file.relativePath.toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(file.mode.toString().toByteArray(Charsets.US_ASCII)); digest.update(0)
            digest.update(file.sha256.toByteArray(Charsets.US_ASCII)); digest.update('\n'.code.toByte())
        }
        LocalUbuntuClojurePolicy.requireTree(member.file)
        return SafeTreeProjection(projection.root, files, emptyList(), total,
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private fun requireExtraFields(extra: ByteArray) {
        var at = 0; val seen = HashSet<Int>()
        while (at < extra.size) {
            require(extra.size - at >= 4) { "Truncated Clojure LSP ZIP extra field" }
            val id = u16(extra, at); val size = u16(extra, at + 2); at += 4
            require(size <= extra.size - at && seen.add(id)) { "Duplicate or truncated Clojure LSP ZIP extra field" }
            // ZIP64, path overrides and alternative UNIX metadata can change layout or file identity.
            // Timestamp/UID/GID and other bounded opaque fields are inert to the existing projector.
            require(id !in setOf(0x0001, 0x000d, 0x5855, 0x756e, 0x7075)) { "Unsupported Clojure LSP ZIP identity-changing extra field" }
            at += size
        }
    }
    private fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, at: Int): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
}
