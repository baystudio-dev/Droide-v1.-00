package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A streamed byte-file observation, separate from the editor's smaller text version.
 *
 * Capture hashes once. Stable identity/size/mtime/ctime then avoid rehashing large artifacts
 * during publication. Providers without ctime receive a cancellable content recheck instead.
 * Repository mutations share a root lock; arbitrary native/terminal writes do not. Neither
 * these observations nor a rename constitute filesystem compare-and-swap against an external
 * writer in the final check/rename interval (or a filesystem with imprecise timestamps).
 */
class StreamFileVersion private constructor(
    private val canonicalRoot: File,
    private val relativePath: String,
    private val parents: List<Pair<File, String>>,
    private val observed: Observation?,
    val sha256: String,
) {
    val existed: Boolean get() = observed != null
    val bytes: Long get() = observed?.bytes ?: 0L
    val executable: Boolean get() = observed?.permissions?.contains(PosixFilePermission.OWNER_EXECUTE) == true

    /** No content allocation, and no following links at the leaf or in project parents. */
    internal fun requireMetadataMatches(root: File, relative: String) {
        require(root.canonicalFile == canonicalRoot && normalize(relative) == relativePath) {
            "File version belongs to a different workspace path"
        }
        val path = checkedPath(canonicalRoot, relativePath)
        parents.forEach { (directory, key) ->
            val attrs = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attrs.isDirectory && attrs.fileKey()?.toString() == key) { changedMessage() }
        }
        require(observe(path) == observed) { changedMessage() }
    }

    internal suspend fun requireMatches(root: File, relative: String) {
        currentCoroutineContext().ensureActive()
        requireMetadataMatches(root, relative)
        if (observed != null && observed.changed == null) {
            require(hash(checkedPath(canonicalRoot, relativePath), bytes) == sha256) { changedMessage() }
            requireMetadataMatches(root, relative)
        }
        currentCoroutineContext().ensureActive()
    }

    private fun changedMessage() =
        "File changed on disk: $relativePath; newer workspace data was preserved. Review or reload before retrying."

    private data class Observation(
        val key: String,
        val bytes: Long,
        val modified: FileTime,
        val created: FileTime,
        val changed: FileTime?,
        val permissions: Set<PosixFilePermission>,
    )

    companion object {
        private const val HASH_BUFFER_BYTES = 64 * 1024

        suspend fun capture(root: File, relativePath: String): StreamFileVersion = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val canonicalRoot = root.canonicalFile
            val relative = normalize(relativePath)
            val file = checkedPath(canonicalRoot, relative)
            val parents = existingParents(canonicalRoot, file)
            val observed = observe(file)
            val digest = if (observed == null) "" else hash(file, observed.bytes)
            StreamFileVersion(canonicalRoot, relative, parents, observed, digest).also {
                // Reject replacement, growth, chmod or ordinary in-place edits during hashing.
                it.requireMetadataMatches(root, relative)
                currentCoroutineContext().ensureActive()
            }
        }

        private fun normalize(relative: String): String {
            val path = relative.replace('\\', '/')
            require(!path.startsWith('/') && '\u0000' !in path) { "Expected a relative workspace file path" }
            val parts = path.split('/').filter { it.isNotEmpty() }
            require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) { "Invalid workspace file path" }
            return parts.joinToString("/")
        }

        private fun checkedPath(root: File, relative: String): File {
            var current = root
            val parts = relative.split('/')
            // Inspect lexical components before canonical resolution; in-root symlinks are aliases too.
            for (index in -1 until parts.size) {
                if (index >= 0) current = File(current, parts[index])
                val attrs = try {
                    Files.readAttributes(current.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) { null }
                if (attrs != null) {
                    require(!attrs.isSymbolicLink) { "Workspace file path contains a symbolic link: $relative" }
                    if (index < parts.lastIndex) require(attrs.isDirectory) { "Workspace parent is not a directory: $relative" }
                    else require(attrs.isRegularFile) { "Expected an ordinary workspace file: $relative" }
                } else require(index >= 0) { "Workspace root is unavailable" }
            }
            return current
        }

        private fun existingParents(root: File, file: File): List<Pair<File, String>> {
            val result = mutableListOf<Pair<File, String>>()
            var directory = root
            val relative = root.toPath().relativize(file.parentFile.toPath())
            for (segment in listOf("") + relative.map { it.toString() }.filter { it.isNotEmpty() }) {
                if (segment.isNotEmpty()) directory = File(directory, segment)
                val attrs = try {
                    Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) { break }
                require(attrs.isDirectory) { "Workspace parent changed during observation" }
                result += directory to requireNotNull(attrs.fileKey()) { "Workspace parent identity is unavailable" }.toString()
            }
            return result
        }

        private fun observe(file: File): Observation? {
            val attrs = try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) { return null }
            require(attrs.isRegularFile) { "Expected an ordinary workspace file: ${file.name}" }
            require(attrs.size() >= 0L) { "Workspace file size is unavailable: ${file.name}" }
            // Android's java.nio implementation does not always expose the Unix attribute view.
            val changed = try {
                Files.getAttribute(file.toPath(), "unix:ctime", NOFOLLOW_LINKS) as? FileTime
            } catch (_: UnsupportedOperationException) { null }
            catch (_: IllegalArgumentException) { null }
            return Observation(requireNotNull(attrs.fileKey()) { "Workspace file identity is unavailable" }.toString(),
                attrs.size(), attrs.lastModifiedTime(), attrs.creationTime(), changed,
                Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS))
        }

        private suspend fun hash(file: File, expectedBytes: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            var total = 0L
            Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(read.toLong() <= expectedBytes - total) { "Workspace file grew during observation" }
                    total += read
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            require(total == expectedBytes) { "Workspace file changed during observation" }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
