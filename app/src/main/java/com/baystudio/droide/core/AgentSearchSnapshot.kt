package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class AgentSearchSnapshot(private val root: File) {
    private data class Stamp(val key: String, val size: Long, val modified: FileTime, val created: FileTime)
    private data class Source(val stamp: Stamp, val parents: List<String>, val bytes: Long, val hash: List<Byte>)
    private data class Document(val version: WorkspaceDocumentVersion, val loaded: Boolean,
        val kind: WorkspaceDocumentKind, val editable: Boolean, val dirty: Boolean, val hash: List<Byte>)
    private val identity = WorkspaceDirectoryIdentity(root)
    private val disk = mutableMapOf<String, Source>()
    private val live = mutableMapOf<String, Document>()

    fun observe(authority: WorkspaceDocumentAuthority?): WorkspaceDocumentAuthority? = authority?.let {
        object : WorkspaceDocumentAuthority by it {
            override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture {
                val capture = it.captureSearchSnapshots(request)
                if (!capture.supported) return capture
                val limits = WorkspaceSearchLimits()
                check(capture.snapshots.size <= limits.snapshots &&
                    capture.snapshots.sumOf { snapshot -> snapshot.content.length.toLong() } <= limits.snapshotChars) {
                    "Search snapshot admission limit exceeded"
                }
                capture.snapshots.filter { snapshot -> snapshot.dirty }.forEach { snapshot ->
                    live[normalized(snapshot.path)] = document(snapshot)
                }
                return capture
            }
        }
    }

    // Hash only bytes already admitted by the search reader, including decoder read-ahead.
    fun open(file: File): InputStream {
        val relative = root.absoluteFile.toPath().normalize().relativize(file.absoluteFile.toPath().normalize()).toString()
            .replace(File.separatorChar, '/')
        val path = normalized(relative)
        disk.remove(path)
        val (checked, parents) = checked(path)
        val before = stamp(checked)
        val input = Files.newInputStream(checked.toPath(), NOFOLLOW_LINKS)
        val digest = MessageDigest.getInstance("SHA-256")
        return object : InputStream() {
            private var bytes = 0L
            private var closed = false
            override fun read(): Int = input.read().also { if (it >= 0) { bytes++; digest.update(it.toByte()) } }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length).also {
                if (it > 0) { bytes += it; digest.update(buffer, offset, it) }
            }
            override fun close() {
                if (closed) return
                closed = true
                try {
                    check(before == stamp(checked(path).first) && parents == checked(path).second) { "Search source changed" }
                    disk[path] = Source(before, parents, bytes, digest.digest().toList())
                } finally { input.close() }
            }
        }
    }

    fun retain(paths: Set<String>) {
        identity.requireCurrent()
        disk.keys.retainAll(paths)
        live.keys.retainAll(paths)
    }

    suspend fun validate(paths: Set<String>, authority: WorkspaceDocumentAuthority?): Long {
        val context = currentCoroutineContext()
        val deadline = WorkspaceRequestDeadline(5_000_000_000L)
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        for (path in paths) {
            context.ensureActive()
            check(!deadline.expired()) { "Search source validation timed out" }
            val expected = live[path]
            val current = authority?.snapshot(path)
            if (expected != null) {
                check(current != null && document(current) == expected) { "Search editor buffer changed" }
                continue
            }
            check(current?.dirty != true) { "Search now has an unsaved editor buffer" }
            val source = checkNotNull(disk[path]) { "Search source could not be verified" }
            val (file, parents) = checked(path)
            check(stamp(file) == source.stamp && parents == source.parents) { "Search file changed" }
            val digest = MessageDigest.getInstance("SHA-256")
            var remaining = source.bytes
            Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
                while (remaining > 0) {
                    context.ensureActive()
                    check(!deadline.expired() && total < MAX_VALIDATION_BYTES) { "Search source validation limit reached" }
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining, MAX_VALIDATION_BYTES - total).toInt())
                    check(count > 0) { "Search source shortened" }
                    digest.update(buffer, 0, count)
                    remaining -= count; total += count
                }
            }
            check(digest.digest().toList() == source.hash && stamp(checked(path).first) == source.stamp &&
                checked(path).second == source.parents) { "Search source content changed" }
            check(authority?.snapshot(path)?.dirty != true) { "Search editor buffer changed during validation" }
        }
        identity.requireCurrent()
        context.ensureActive()
        return total
    }

    private fun checked(path: String): Pair<File, List<String>> {
        identity.requireCurrent()
        var file = root
        val parents = mutableListOf<String>()
        val parts = normalized(path).split('/')
        for (part in parts) {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(attrs.isDirectory) { "Search parent is not an ordinary directory" }
            parents += checkNotNull(attrs.fileKey()).toString()
            file = File(file, part)
        }
        stamp(file)
        return file to parents
    }

    private fun stamp(file: File): Stamp {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attrs.isRegularFile && attrs.size() >= 0L) { "Search source is not an ordinary file" }
        return Stamp(checkNotNull(attrs.fileKey()).toString(), attrs.size(), attrs.lastModifiedTime(), attrs.creationTime())
    }

    private suspend fun document(value: WorkspaceDocumentSnapshot): Document {
        check(value.content.length.toLong() <= WorkspaceSearchLimits().snapshotChars) { "Search editor buffer exceeds validation limit" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8_192)
        val context = currentCoroutineContext()
        val deadline = WorkspaceRequestDeadline(5_000_000_000L)
        var at = 0
        while (at < value.content.length) {
            context.ensureActive()
            check(!deadline.expired()) { "Search buffer validation timed out" }
            val count = minOf(buffer.size / 2, value.content.length - at)
            repeat(count) { index ->
                val code = value.content[at + index].code
                buffer[index * 2] = code.toByte(); buffer[index * 2 + 1] = (code ushr 8).toByte()
            }
            digest.update(buffer, 0, count * 2); at += count
        }
        return Document(value.version, value.loaded, value.kind, value.editable, value.dirty, digest.digest().toList())
    }

    private fun normalized(raw: String): String {
        val path = raw.replace('\\', '/')
        require(!path.startsWith('/') && '\u0000' !in path) { "Invalid search path" }
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.isNotEmpty() && parts.none { it == ".." }) { "Invalid search path" }
        return parts.joinToString("/")
    }

    companion object { const val MAX_VALIDATION_BYTES = 67_108_864L }
}
