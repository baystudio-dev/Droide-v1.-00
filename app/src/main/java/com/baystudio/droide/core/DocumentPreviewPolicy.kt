package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.math.min
import kotlin.math.sqrt

/** Document bytes never enter an editable/recovery buffer. No converter or network dependency. */
internal object DocumentPreviewPolicy {
    private val preparedCaches = HashSet<String>()
    enum class Kind { PDF, DOCX, LEGACY_WORD }
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_PAGES = 10_000
    const val MAX_PIXELS = 2_000_000
    const val MAX_TEXT_CHARS = 64_000
    const val TIMEOUT_MS = 20_000L
    fun kind(path: String): Kind? = when (path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
        "pdf" -> Kind.PDF
        "docx" -> Kind.DOCX
        "doc", "docm", "dot", "dotx", "dotm" -> Kind.LEGACY_WORD
        else -> null
    }
    fun size(width: Int, height: Int, zoom: Int = 1): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Invalid PDF page size" }
        val scale = min(1600.0 * zoom.coerceIn(1, 2) / maxOf(width, height),
            sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height)))
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    @Synchronized private fun prepareCache(cache: File, checkCancelled: () -> Unit) {
        val key = cache.absolutePath
        if (key in preparedCaches) return
        require(cache.isDirectory || cache.mkdirs()) { "Preview cache is unavailable" }
        Files.newDirectoryStream(cache.toPath()).use { paths ->
            var count = 0
            for (path in paths) {
                checkCancelled()
                require(++count <= 256) { "Preview cache needs clearing before opening another document" }
                val name = path.fileName.toString()
                if (name.startsWith("document-") && name.endsWith(".preview") && name.length <= 96 &&
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(path)
            }
        }
        preparedCaches.add(key)
    }

    /** Bounded immutable preview copy, deleted by its owner. Reject mutation during the copy. */
    fun snapshot(source: File, cache: File, checkCancelled: () -> Unit): File {
        fun attributes() = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val before = attributes()
        require(before.isRegularFile && !before.isSymbolicLink) { "Document is unavailable" }
        require(before.size() in 1..MAX_FILE_BYTES) { "Document preview supports files up to 32 MiB" }
        prepareCache(cache, checkCancelled)
        val temporary = File.createTempFile("document-", ".preview", cache)
        try {
            checkCancelled()
            Files.newInputStream(source.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                temporary.outputStream().use { output ->
                    val bytes = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(bytes)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_FILE_BYTES) { "Document grew beyond the 32 MiB preview limit" }
                        output.write(bytes, 0, count)
                    }
                    require(total == before.size()) { "Document changed while opening; reopen it" }
                }
            }
            checkCancelled()
            val after = attributes()
            require(after.isRegularFile && after.size() == before.size() && after.lastModifiedTime() == before.lastModifiedTime() &&
                after.fileKey() == before.fileKey()) { "Document changed while opening; reopen it" }
            return temporary
        } catch (failure: Throwable) { temporary.delete(); throw failure }
    }
}
