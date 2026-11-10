package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read-only bounded observation, not an atomic filesystem snapshot or a native reload barrier. */
internal class LspSvelteFilePoller(
    root: File,
    val limits: Limits = Limits(),
    private val clockNanos: () -> Long = System::nanoTime,
) {
    data class Limits(
        val maxEntries: Int = 12_000,
        val maxDepth: Int = 96,
        val maxFileBytes: Long = 4L * 1024 * 1024,

        val maxReadBytes: Long = 96L * 1024 * 1024,
        val maxScanMillis: Long = 15_000,
    ) {
        init {
            require(maxEntries in 1..100_000 && maxDepth in 1..256)
            require(maxFileBytes in 1..64L * 1024 * 1024)
            require(maxReadBytes in 1..1024L * 1024 * 1024 && maxScanMillis in 1..120_000)
        }
    }

    enum class Type { FILE, DIRECTORY, BIN_LINK }
    data class Entry(val type: Type, val digest: String, val watched: Boolean, val critical: Boolean)
    data class Difference(val relativePath: String, val before: Entry?, val after: Entry?) {
        val watched: Boolean get() = before?.watched == true || after?.watched == true
        val critical: Boolean get() = before?.critical == true || after?.critical == true
        val eventType: Int get() = if (before == null) 1 else if (after == null) 3 else 2
    }
    data class Snapshot(
        val canonicalRoot: String,
        val entries: Map<String, Entry>,
        val aggregateDigest: String,
        val criticalDigest: String,
        val completedNanos: Long,
        val entryCount: Int,
        val readBytes: Long,
        val elapsedNanos: Long,
    )
    class CoverageException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val approvedRoot = root.absoluteFile.toPath().normalize()
    val canonicalRoot: String = approvedRoot.toString()

    suspend fun scan(previous: Snapshot? = null, onDifference: (Difference) -> Unit = {}): Snapshot =
        withContext(Dispatchers.IO) {
            val started = clockNanos()
            val context = currentCoroutineContext()
            var count = 0
            var bytes = 0L
            var differenceReported = false
            val entries = sortedMapOf<String, Entry>()
            fun check() {
                context.ensureActive()
                if (clockNanos() - started > limits.maxScanMillis * 1_000_000L) {
                    throw CoverageException("Svelte observation deadline exceeded")
                }
            }
            fun attrs(path: Path): BasicFileAttributes {
                check()
                return Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            }
            fun identity(a: BasicFileAttributes): String =
                "${a.isRegularFile}:${a.isDirectory}:${a.isSymbolicLink}:${a.size()}:${a.lastModifiedTime()}:${a.creationTime()}:${a.fileKey()}"
            fun unchanged(a: BasicFileAttributes, b: BasicFileAttributes) = identity(a) == identity(b)
            fun noLinkComponents(path: Path) {
                var cursor = path.root ?: throw CoverageException("Svelte observation requires an absolute root")
                for (part in path) {
                    cursor = cursor.resolve(part)
                    if (attrs(cursor).isSymbolicLink) throw CoverageException("Svelte observation refuses symlink coverage")
                }
            }
            fun put(relative: String, entry: Entry) {
                entries[relative] = entry
                val before = previous?.entries?.get(relative)
                if (previous != null && before != entry && !differenceReported) {
                    differenceReported = true
                    onDifference(Difference(relative, before, entry))
                }
            }
            fun readDigest(path: Path, expected: Long): String {
                val digest = MessageDigest.getInstance("SHA-256")
                var fileBytes = 0L
                FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.allocate(32 * 1024)
                    while (true) {
                        check()
                        buffer.clear()
                        val n = channel.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        fileBytes += n
                        bytes += n
                        if (fileBytes > limits.maxFileBytes || bytes > limits.maxReadBytes) {
                            throw CoverageException("Svelte observation byte budget exceeded")
                        }
                        digest.update(buffer.array(), 0, n)
                    }
                }
                if (fileBytes != expected) throw CoverageException("Svelte observation changed during read")
                return hex(digest.digest())
            }
            fun names(path: Path): List<String> {
                val result = ArrayList<String>()
                Files.newDirectoryStream(path).use { stream ->
                    for (child in stream) {
                        check()
                        if (++count > limits.maxEntries) throw CoverageException("Svelte observation entry budget exceeded")
                        result.add(child.fileName.toString())
                    }
                }
                return result.sorted()
            }
            fun verifyNames(path: Path, expected: List<String>) {
                var seen = 0
                val actual = ArrayList<String>()
                Files.newDirectoryStream(path).use { stream ->
                    for (child in stream) {
                        check()
                        if (++seen > limits.maxEntries) throw CoverageException("Svelte observation entry budget exceeded")
                        actual.add(child.fileName.toString())
                    }
                }
                if (actual.sorted() != expected) throw CoverageException("Svelte observation directory changed during scan")
            }
            fun visit(path: Path, relative: String, depth: Int) {
                check()
                if (depth > limits.maxDepth) throw CoverageException("Svelte observation depth budget exceeded")
                val before = attrs(path)
                val watched = matchesGlob(relative)
                val critical = isCritical(relative)
                if (before.isSymbolicLink) {
                    // npm's .bin links are common. Prove a contained, non-symlink regular target without opening it;
                    // every other link is ambiguous coverage and fails closed.
                    if (watched || !isBinLink(relative)) throw CoverageException("Svelte observation refuses symlink coverage")
                    val link = Files.readSymbolicLink(path)
                    val target = (if (link.isAbsolute) link else path.parent.resolve(link)).normalize()
                    if (!target.startsWith(approvedRoot) || target == approvedRoot) {
                        throw CoverageException("Svelte .bin link leaves the approved root")
                    }
                    noLinkComponents(target)
                    val targetBefore = attrs(target)
                    if (!targetBefore.isRegularFile || targetBefore.isSymbolicLink) {
                        throw CoverageException("Svelte .bin link is not a regular file")
                    }
                    if (!unchanged(before, attrs(path)) || Files.readSymbolicLink(path) != link ||
                        !unchanged(targetBefore, attrs(target))) throw CoverageException("Svelte .bin link changed during scan")
                    put(relative, Entry(Type.BIN_LINK, hash(link.toString() + ":" + identity(targetBefore)), false, true))
                } else if (before.isDirectory) {
                    val children = names(path)
                    put(relative, Entry(Type.DIRECTORY, hash(children.joinToString("\u0000")), watched, critical))
                    for (name in children) visit(path.resolve(name), if (relative.isEmpty()) name else "$relative/$name", depth + 1)
                    verifyNames(path, children)
                    if (!unchanged(before, attrs(path))) throw CoverageException("Svelte observation directory changed during scan")
                } else if (before.isRegularFile) {
                    if (watched || isSentinel(relative)) {
                        if (!Files.isReadable(path) || before.size() > limits.maxFileBytes) {
                            throw CoverageException("Svelte observation file unreadable or over budget")
                        }
                        val first = readDigest(path, before.size())
                        if (!unchanged(before, attrs(path))) throw CoverageException("Svelte observation file changed during scan")
                        val second = readDigest(path, before.size())
                        if (first != second || !unchanged(before, attrs(path))) {
                            throw CoverageException("Svelte observation file bytes changed during scan")
                        }
                        put(relative, Entry(Type.FILE, first, watched, critical))
                    }
                } else {
                    throw CoverageException("Svelte observation refuses a special filesystem entry")
                }
            }
            try {
                check()
                noLinkComponents(approvedRoot)
                if (approvedRoot.toFile().canonicalFile.toPath() != approvedRoot || !attrs(approvedRoot).isDirectory) {
                    throw CoverageException("Svelte observation lost its canonical approved root")
                }
                if (previous != null && previous.canonicalRoot != canonicalRoot) throw CoverageException("Svelte observation root changed")
                visit(approvedRoot, "", 0)
                if (previous != null && !differenceReported) {
                    val removed = previous.entries.keys.firstOrNull { it !in entries }
                    if (removed != null) {
                        differenceReported = true
                        onDifference(Difference(removed, previous.entries[removed], null))
                    }
                }
                check()
                Snapshot(canonicalRoot, Collections.unmodifiableMap(LinkedHashMap(entries)),
                    digestEntries(entries, false), digestEntries(entries, true), clockNanos(), count, bytes,
                    clockNanos() - started)
            } catch (failure: java.io.IOException) {
                throw CoverageException("Svelte observation lost filesystem coverage", failure)
            } catch (failure: SecurityException) {
                throw CoverageException("Svelte observation access denied", failure)
            }
        }

    companion object {
        const val GLOB = "**/*.{ts,js,mts,mjs,cjs,cts,json,svelte}"
        private val extensions = setOf("ts", "js", "mts", "mjs", "cjs", "cts", "json", "svelte")
        private val lockNames = setOf("package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml", "bun.lock", "bun.lockb")
        fun matchesGlob(relative: String): Boolean = relative.substringAfterLast('/').substringAfterLast('.', "") in extensions
        private fun isBinLink(relative: String): Boolean {
            val segments = relative.split('/')
            return segments.size >= 3 && segments[segments.lastIndex - 1] == ".bin" && segments[segments.lastIndex - 2] == "node_modules"
        }
        private fun isCritical(relative: String): Boolean {
            val name = relative.substringAfterLast('/')
            return name == "package.json" || name in lockNames || name.startsWith("tsconfig") || name.startsWith("jsconfig") ||
                name.startsWith("svelte.config.") || name.startsWith("vite.config.") || name.startsWith("prettier.config.") ||
                name == ".prettierignore" || name.startsWith(".prettierrc")
        }
        private fun isSentinel(relative: String): Boolean = isCritical(relative) ||
            relative.substringAfterLast('.', "") in setOf("tsx", "jsx")
        private fun digestEntries(entries: Map<String, Entry>, criticalOnly: Boolean): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for ((path, entry) in entries) if (!criticalOnly || entry.critical) {
                digest.update("${path.length}:$path:${entry.type}:${entry.digest}:${entry.watched}:${entry.critical}\n".toByteArray(Charsets.UTF_8))
            }
            return hex(digest.digest())
        }
        private fun hash(value: String): String = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
        private fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
