package com.baystudio.droide.core

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object ChatMediaProjectAssets {
    fun destination(root: File, path: String): String {
        require(path.isNotBlank() && path.length <= 500 && !File(path).isAbsolute && '\\' !in path) {
            "Choose a project-relative asset path, for example assets/logo.png"
        }
        val parts = path.split('/')
        require(parts.size <= 32) { "Asset path is too deep" }
        parts.forEach(PathSecurity::safeLeafName)
        require(parts.none { it == ".git" || it == ".droide" }) { "Choose a project asset folder outside internal metadata" }
        require(ExplorerFilePolicy.requireUserTarget(root, path) == path) { "Asset destination changed; choose it again" }
        val target = File(root.canonicalFile, path)
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "A file already exists: $path; existing data was preserved. Choose another asset name."
        }
        return path
    }

    suspend fun save(
        store: ChatMediaStore,
        ownerId: String,
        ref: ChatMediaRef,
        files: FileRepository,
        path: String,
        publicationObserver: StreamPublicationObserver? = null,
        beforePublication: (suspend () -> Unit)? = null,
        onIntegrityReady: ((String) -> Unit)? = null,
        onProgress: (suspend (StreamWriteProgress) -> Unit)? = null,
    ): Long = withContext(Dispatchers.IO) {
        destination(files.root, path)
        // An opened original survives concurrent session cleanup; publication never retains a link to
        // this original, and cleanup is never given the project destination.
        val original = store.openOwnedOriginal(ownerId, ref)
        CloseOnceInput(ExactOriginalInput(original, ref.bytes)).use { ownedInput ->
            onIntegrityReady?.invoke(original.integrityOrigin)
            var parent = ""
            path.split('/').dropLast(1).forEach { segment ->
                currentCoroutineContext().ensureActive()
                parent = if (parent.isEmpty()) segment else "$parent/$segment"
                require(ExplorerFilePolicy.requireUserTarget(files.root, parent) == parent)
                val directory = File(files.root, parent)
                if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    check(files.createDirectory(parent, requireExistingParent = true)) { "Cannot create asset folder: $parent" }
                }
            }
            // A committed new file is never rolled back on later sync/cleanup failure.

            files.writeStream(path, ownedInput, expectedBytes = ref.bytes,
                requireAbsent = true, requireExistingParent = true, onProgress = onProgress,
                publicationObserver = publicationObserver, beforePublication = beforePublication)
        }
    }

    internal class CloseOnceInput(input: InputStream) : FilterInputStream(input) {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) super.close() }
    }

    /** Fail before publication on a truncated/growing original, without buffering its contents. */
    private class ExactOriginalInput(input: InputStream, private val expected: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { value -> account(if (value < 0) -1 else 1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val requested = minOf(length.toLong(), expected - count + 1).toInt()
            return `in`.read(bytes, offset, requested).also(::account)
        }
        private fun account(read: Int) {
            if (read < 0) require(count == expected) { "Attachment was truncated while copying; project asset was not saved" }
            else {
                count += read
                require(count <= expected) { "Attachment grew while copying; project asset was not saved" }
            }
        }
    }
}
