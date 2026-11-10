package com.baystudio.droide.core

import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.sync.Mutex

/** Content token derived from the payload that was actually written, never a later disk reread. */
data class DiskFileVersion(
    val existed: Boolean,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(bytes in 0..EditorLargeFilePolicy.MAX_EDITABLE_BYTES) { "Invalid bounded file-version size" }
        require(if (existed) sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }
            else bytes == 0L && sha256.isEmpty()) { "Invalid file-version digest" }
    }

    fun matches(file: File): Boolean {
        if (!existed) return !file.exists() && !PathSecurity.isSymbolicLink(file)
        if (!file.isFile || PathSecurity.isSymbolicLink(file) || file.length() != bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                read += count
                // A concurrent replacement/growth never triggers an unbounded hash or text allocation.
                if (read > bytes) return false
                digest.update(buffer, 0, count)
            }
        }
        return read == bytes && digest.digest().hex() == sha256 && file.length() == bytes
    }

    companion object {
        fun fromText(content: String?): DiskFileVersion {
            if (content == null) return DiskFileVersion(false, 0L, "")
            val encoded = content.toByteArray(Charsets.UTF_8)
            return DiskFileVersion(true, encoded.size.toLong(), MessageDigest.getInstance("SHA-256").digest(encoded).hex())
        }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Repositories for the same project serialize persisted mutations without keeping closed projects alive. */
internal object WorkspaceFileMutationLocks {
    private val locks = mutableMapOf<String, WeakReference<Mutex>>()

    fun forRoot(root: File): Mutex = synchronized(locks) {
        locks.entries.removeAll { it.value.get() == null }
        val path = root.canonicalPath
        locks[path]?.get() ?: Mutex().also { locks[path] = WeakReference(it) }
    }

    /** Explicit ownership shared by repositories and standalone workspace synchronizers. */
    suspend fun <T> withRoot(root: File, action: suspend (WorkspaceMutationGuard) -> T): T =
        ExportWriterAdmission.withSuspendingWriter(root, "workspace mutation") { withLockedRoot(root, action) }

    /** Read-only export capture uses the same mutex but registers its capture fence separately. */
    internal suspend fun <T> withCaptureRoot(root: File, action: suspend (WorkspaceMutationGuard) -> T): T =
        withLockedRoot(root, action)

    private suspend fun <T> withLockedRoot(root: File, action: suspend (WorkspaceMutationGuard) -> T): T {
        val lock = forRoot(root)
        val owner = Any()
        lock.lock(owner)
        var guard: WorkspaceMutationGuard? = null
        try {
            guard = WorkspaceMutationGuard(root, lock, owner)
            return action(guard)
        } finally {
            guard?.close()
            lock.unlock(owner)
        }
    }
}

/** Not a coroutine-context capability: callers must pass this exact, live guard explicitly. */
internal class WorkspaceMutationGuard internal constructor(root: File, private val lock: Mutex, private val owner: Any) {
    val canonicalRoot: File = root.canonicalFile
    val rootIdentity: String = identity(canonicalRoot)
    private var active = true

    init {
        check(lock === WorkspaceFileMutationLocks.forRoot(canonicalRoot) && lock.holdsLock(owner)) {
            "Workspace mutation guard requires the shared root lock"
        }
    }

    fun requireRoot(root: File) {
        check(active && lock.holdsLock(owner)) { "Workspace mutation guard is no longer owned" }
        check(root.canonicalFile == canonicalRoot && identity(canonicalRoot) == rootIdentity) {
            "Workspace mutation guard belongs to a different or replaced root"
        }
    }

    internal fun close() { active = false }

    private fun identity(root: File): String {
        val attrs = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attrs.isDirectory) { "Workspace mutation root is not a directory" }
        return checkNotNull(attrs.fileKey()) { "Workspace mutation root identity is unavailable" }.toString()
    }
}
