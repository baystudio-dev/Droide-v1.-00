package com.baystudio.droide.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** In-memory inventory acceleration, never a persisted merge/approval authority. */

internal class WorkspaceFileFingerprintCache(
    private val maxEntries: Int = 40_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val changedTime: (Path) -> FileTime? = ::unixChangedTime,
    private val onHashRead: (Path, Int) -> Unit = { _, _ -> },
) {
    internal data class Fingerprint(
        val sha256: String,
        val attributes: BasicFileAttributes,
        val executable: Boolean,
    )

    private data class Stamp(
        val identity: String?,
        val size: Long,
        val modified: FileTime,
        val created: FileTime,
        val changed: FileTime?,
        val executable: Boolean,
    )
    private data class Observed(val attributes: BasicFileAttributes, val stamp: Stamp)
    private data class Cached(val stamp: Stamp, val sha256: String, val checkedAtMillis: Long)
    private val entries = object : LinkedHashMap<Path, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Cached>?) = size > maxEntries
    }

    init { require(maxEntries >= 0) }

    fun read(path: Path, context: CoroutineContext, allowReuse: Boolean = true): Fingerprint {
        val key = path.toAbsolutePath().normalize()
        return readObserved(key, context, allowReuse, { observe(key) }) { block ->
            Files.newInputStream(key, NOFOLLOW_LINKS).use(block)
        }
    }

    fun read(files: AnchoredWorkspaceFiles, relative: String, logicalPath: Path,
             context: CoroutineContext, allowReuse: Boolean = true): Fingerprint = files.openRead(relative).use { input ->
        readObserved(logicalPath.toAbsolutePath().normalize(), context, allowReuse, {
            val value = input.observe()
            val attributes = value.attributes
            Observed(attributes, Stamp(attributes.fileKey()?.toString(), attributes.size(), attributes.lastModifiedTime(),
                attributes.creationTime(), value.changed, value.executable))
        }) { block -> block(input) }
    }

    private fun readObserved(key: Path, context: CoroutineContext, allowReuse: Boolean,
                             observe: () -> Observed, read: ((java.io.InputStream) -> Unit) -> Unit): Fingerprint {
        context.ensureActive()
        val started = nowMillis()
        val before = observe()
        val cached = entries[key]
        if (allowReuse && cached != null && cached.stamp == before.stamp &&
            started >= cached.checkedAtMillis && mature(before.stamp, started)) {
            context.ensureActive()
            return Fingerprint(cached.sha256, before.attributes, before.stamp.executable)
        }
        // Invalidate before I/O. Cancellation, an I/O error or a racing writer cannot leave an old
        // observation eligible after this attempted verification.
        entries.remove(key)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        read { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                check(count.toLong() <= before.stamp.size - total) {
                    "Workspace file grew during inspection; pending changes were preserved"
                }
                total += count
                digest.update(buffer, 0, count)
                onHashRead(key, count)
            }
        }
        context.ensureActive()
        check(total == before.stamp.size && before.stamp == observe().stamp) {
            "Workspace file changed during inspection; pending changes were preserved"
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        // The decision uses the time BEFORE hashing, not the finish time. In particular, a long hash
        // must not turn a same-timestamp concurrent edit into a cache hit.
        if (maxEntries > 0 && mature(before.stamp, started)) {
            entries[key] = Cached(before.stamp, hash, started)
        }
        return Fingerprint(hash, before.attributes, before.stamp.executable)
    }

    private fun observe(path: Path): Observed {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attrs.isRegularFile && attrs.size() >= 0L) { "Workspace artifact is not an ordinary file" }
        return Observed(attrs, Stamp(attrs.fileKey()?.toString(), attrs.size(), attrs.lastModifiedTime(),
            attrs.creationTime(), changedTime(path),
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)))
    }

    private fun mature(stamp: Stamp, checkedAtMillis: Long): Boolean {
        val changed = stamp.changed ?: return false
        if (stamp.identity == null) return false
        // Internal Linux storage supports ctime. A conservative two-second margin also avoids trusting
        // recent whole-second timestamps, without delaying any operation.
        val cutoffSeconds = Math.floorDiv(checkedAtMillis, 1000L) - 2L
        return changed.to(TimeUnit.SECONDS) < cutoffSeconds &&
            stamp.modified.to(TimeUnit.SECONDS) < cutoffSeconds
    }

    companion object {
        private fun unixChangedTime(path: Path): FileTime? = try {
            Files.getAttribute(path, "unix:ctime", NOFOLLOW_LINKS) as? FileTime
        } catch (_: UnsupportedOperationException) { null }
        catch (_: IllegalArgumentException) { null }
    }
}
