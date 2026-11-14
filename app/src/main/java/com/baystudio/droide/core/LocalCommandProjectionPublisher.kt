package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** Stable directory; each changed name transitions atomically from its old shim to its new shim. */
internal object LocalCommandProjectionPublisher {
    private data class OwnedFile(val file: File, val key: Any?, val size: Long, val modified: java.nio.file.attribute.FileTime)
    private data class StagedFile(val file: File, val key: Any?)

    fun publish(
        destination: File,
        stage: File,
        previous: File,
        scripts: Map<String, String>,
        isOwnedScript: (File, String) -> Boolean,
        onPublished: ((File, String) -> Unit)? = null,
    ) {
        requireSafeDirectories(listOf(destination, stage, previous))
        if (!exists(destination) && exists(previous)) {
            requireDirectory(previous)
            // Only recovery of a legacy interrupted directory swap moves a directory.
            Files.move(previous.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        createDirectory(destination)
        createDirectory(stage)
        val staged = linkedMapOf<String, StagedFile>()
        try {
            scripts.forEach { (name, text) ->
                require(name.matches(Regex("[A-Za-z0-9._+-]{1,80}")))
                val target = File(destination, name)
                if (!LocalCommandProjectionSnapshot.matchesFile(target, text)) {
                    check(!exists(target) || !Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        "Command projection name is occupied by a directory: $name"
                    }
                    val temporary = Files.createTempFile(stage.toPath(), ".droide-projection-", ".tmp").toFile()
                    staged[name] = StagedFile(temporary,
                        Files.readAttributes(temporary.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey())
                    FileOutputStream(temporary).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
                    check(temporary.setExecutable(true, false)) { "Cannot make command shim executable: $name" }
                }
            }
            staged.forEach { (name, temporary) ->
                // No delete-before-move and no non-atomic fallback. Unsupported atomic rename
                // fails while preserving the old command. Other command names stay available.
                Files.move(temporary.file.toPath(), File(destination, name).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                onPublished?.invoke(destination, name)
            }
            // Publish all desired names before retiring recognized obsolete generated shims.
            destination.listFiles().orEmpty().filter { it.name !in scripts }.forEach { candidate ->
                owned(candidate, isOwnedScript)?.let { snapshot ->
                    if (deleteUnchanged(snapshot)) onPublished?.invoke(destination, candidate.name)
                }
            }
            cleanOwned(previous, isOwnedScript)
            cleanOwned(stage, isOwnedScript)
        } finally {
            // Only operation-created leaves are eligible here. Unknown old stage/journal
            // contents are retained; never recursively remove an entire projection tree.
            staged.values.forEach { temporary ->
                // Also reclaim an operation-owned partial write, while retaining a foreign
                // replacement of its unique name. This is not a pathname CAS.
                try {
                    val attrs = Files.readAttributes(temporary.file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (temporary.key != null && attrs.fileKey() == temporary.key && attrs.isRegularFile && !attrs.isSymbolicLink)
                        temporary.file.delete()
                } catch (_: java.io.IOException) { }
            }
            removeEmpty(stage)
        }
    }

    /** Validate the entire small projection namespace before any mkdir, fast return or fallback. */
    internal fun requireSafeDirectories(directories: List<File>) {
        val checked = mutableSetOf<java.nio.file.Path>()
        directories.forEach { directory ->
            var path: java.nio.file.Path? = directory.toPath().toAbsolutePath().normalize()
            while (path != null && checked.add(path)) {
                val current = path
                val attributes = try {
                    Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: java.nio.file.NoSuchFileException) { null }
                check(attributes == null || attributes.isDirectory && !attributes.isSymbolicLink) {
                    "Command projection parent or directory is unsafe: ${directory.name}"
                }
                path = current.parent
            }
        }
    }

    private fun cleanOwned(directory: File, isOwnedScript: (File, String) -> Boolean) {
        if (!exists(directory)) return
        requireDirectory(directory)
        directory.listFiles().orEmpty().forEach { owned(it, isOwnedScript)?.let(::deleteUnchanged) }
        removeEmpty(directory)
    }

    private fun owned(file: File, predicate: (File, String) -> Boolean): OwnedFile? = try {
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.isSymbolicLink || before.size() !in 1..65_536) null
        else {
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(65_537); var count = 0
                while (count < buffer.size) {
                    val n = input.read(buffer, count, buffer.size - count)
                    if (n < 0) break
                    count += n
                }
                buffer.copyOf(count)
            }
            val snapshot = OwnedFile(file, before.fileKey(), before.size(), before.lastModifiedTime())
            val text = bytes.toString(Charsets.UTF_8)
            snapshot.takeIf { bytes.size.toLong() == before.size() && bytes.contentEquals(text.toByteArray(Charsets.UTF_8)) &&
                predicate(file, text) && unchanged(it) }
        }
    } catch (_: java.io.IOException) { null }
      catch (_: SecurityException) { null }

    private fun unchanged(snapshot: OwnedFile): Boolean = try {
        val current = Files.readAttributes(snapshot.file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        current.isRegularFile && !current.isSymbolicLink && snapshot.key != null && current.fileKey() == snapshot.key &&
            current.size() == snapshot.size && current.lastModifiedTime() == snapshot.modified
    } catch (_: java.io.IOException) { false }
      catch (_: SecurityException) { false }

    // This identity check is conservative, but is not a filesystem compare-and-unlink against
    // a different writer replacing the pathname after the check. Shared namespace writes
    // outside Droide's mutation gate remain outside the projection transaction contract.
    private fun deleteUnchanged(snapshot: OwnedFile): Boolean = unchanged(snapshot) && snapshot.file.delete()
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun requireDirectory(file: File) {
        check(!Files.isSymbolicLink(file.toPath()) && Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Command projection directory is unsafe: ${file.name}"
        }
    }
    private fun createDirectory(file: File) {
        if (!exists(file)) check(file.mkdir()) { "Cannot create command projection directory: ${file.name}" }
        requireDirectory(file)
    }
    private fun removeEmpty(file: File) {
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()) &&
            file.listFiles()?.isEmpty() == true) file.delete()
    }
}
