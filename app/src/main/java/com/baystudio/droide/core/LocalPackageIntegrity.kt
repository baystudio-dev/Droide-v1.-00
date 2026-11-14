package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Portable, cancellable package hashing; never starts one Android shell process per file. */
internal object LocalPackageIntegrity {
    private const val MAX_MANIFEST_BYTES = 32L * 1024 * 1024
    private const val MAX_FILES = 120_000
    private const val MAX_BYTES = 12L * 1024 * 1024 * 1024
    private val checksumPattern = Regex("[0-9a-fA-F]{64}")

    suspend fun seal(root: File, maxFiles: Int = MAX_FILES, maxBytes: Long = MAX_BYTES,
        progress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val base = checkedRoot(root)
        val inventory = regularFiles(base, "SHA256SUMS", maxFiles)
        require(inventory.isNotEmpty()) { "Package tree is empty" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var bytes = 0L
        val rows = ArrayList<String>(inventory.size)
        for ((index, entry) in inventory.withIndex()) {
            currentCoroutineContext().ensureActive()
            bytes = Math.addExact(bytes, Files.size(entry.second))
            require(bytes <= maxBytes) { "Package exceeds its reviewed byte bound" }
            rows += "${hash(base, entry.second, digest, buffer) { read ->
                progress("Verifying package file ${index + 1}/${inventory.size} · ${read / 1024} KiB checked…\n")
            }}  ${entry.first}"
            if (index == 0 || (index + 1) % 128 == 0 || index == inventory.lastIndex)
                progress("Verifying package files ${index + 1}/${inventory.size}…\n")
        }
        val text = rows.joinToString("\n", postfix = "\n")
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "Package checksum manifest is too large" }
        val manifest = base.resolve("SHA256SUMS")
        require(!Files.isSymbolicLink(manifest)) { "Checksum manifest is a symbolic link" }
        Files.write(manifest, text.toByteArray(Charsets.UTF_8))
        // The bytes were hashed above in this owned staging transaction. Admission reads the
        // published tree again; re-reading every staged byte here adds no independent authority.
        hash(base, manifest, digest, buffer)
    }

    suspend fun verify(root: File, manifest: File, exactInventory: Boolean = false,
        progress: (String) -> Unit = {}, manifestRoot: File = root): Boolean =
        verifiedChecksums(root, manifest, exactInventory, progress, manifestRoot) != null

    /** Results exist only after every listed byte was verified in this call, never from timestamps. */
    suspend fun verifiedChecksums(root: File, manifest: File, exactInventory: Boolean = false,
        progress: (String) -> Unit = {}, manifestRoot: File = root): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            val base = checkedRoot(root)
            val manifestBase = checkedRoot(manifestRoot)
            require(base.startsWith(manifestBase)) { "Checksum manifest root does not own the package tree" }
            val suppliedRoot = manifestRoot.toPath().toAbsolutePath().normalize()
            val suppliedManifest = manifest.toPath().toAbsolutePath().normalize()
            require(suppliedManifest.startsWith(suppliedRoot)) { "Checksum manifest is outside its owned root" }
            val manifestPath = manifestBase.resolve(suppliedRoot.relativize(suppliedManifest)).normalize()
            require(Files.isRegularFile(manifestPath, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(manifestPath) && Files.size(manifestPath) in 1..MAX_MANIFEST_BYTES) { "Invalid checksum manifest" }
            safeFile(manifestBase, manifestPath)
            val rows = Files.readAllLines(manifestPath, Charsets.UTF_8).filter { it.isNotEmpty() }
            require(rows.size in 1..MAX_FILES) { "Invalid checksum entry count" }
            val seen = HashSet<String>()
            val verified = LinkedHashMap<String, String>(rows.size)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            var bytes = 0L
            for ((index, row) in rows.withIndex()) {
                currentCoroutineContext().ensureActive()
                require(row.length > 66 && row.take(64).matches(checksumPattern) &&
                    row[64] == ' ' && row[65] in charArrayOf(' ', '*')) { "Malformed checksum entry" }
                val relative = row.substring(66).removePrefix("./")
                checkedRelative(relative)
                require(seen.add(relative)) { "Duplicate checksum entry" }
                val path = base.resolve(relative).normalize()
                require(path != manifestPath) { "Checksum manifest cannot checksum itself" }
                safeFile(base, path)
                bytes = Math.addExact(bytes, Files.size(path))
                require(bytes <= MAX_BYTES) { "Checksum verification exceeds package bound" }
                val expected = row.take(64).lowercase()
                if (expected != hash(base, path, digest, buffer) { read ->
                    progress("Checking package file ${index + 1}/${rows.size} · ${read / 1024} KiB checked…\n")
                }) return@withContext null
                verified[relative] = expected
                if (index == 0 || (index + 1) % 128 == 0 || index == rows.lastIndex)
                    progress("Checking package integrity ${index + 1}/${rows.size}…\n")
            }
            if (exactInventory) {
                val manifestRelative = if (manifestPath.startsWith(base))
                    base.relativize(manifestPath).toString().replace(File.separatorChar, '/') else null
                if (regularFiles(base, manifestRelative, MAX_FILES).map { it.first }.toSet() != seen) return@withContext null
            }
            verified.toMap()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private fun checkedRoot(root: File): Path {
        val base = root.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(base, NOFOLLOW_LINKS) && !Files.isSymbolicLink(base)) { "Package root is unavailable" }
        // Android's /data/data and /data/user/0 may alias the same app directory. Resolve
        // the trusted root once while still refusing links inside the package itself.
        return root.canonicalFile.toPath()
    }

    private suspend fun regularFiles(base: Path, excluded: String?, maxFiles: Int): List<Pair<String, Path>> {
        require(maxFiles in 1..MAX_FILES)
        val entries = ArrayList<Pair<String, Path>>()
        Files.walk(base).use { paths ->
            val it = paths.iterator()
            while (it.hasNext()) {
                currentCoroutineContext().ensureActive()
                val path = it.next()
                if (path == base) continue
                val relative = base.relativize(path).toString().replace(File.separatorChar, '/')
                checkedRelative(relative)
                if (relative == excluded) continue
                when {
                    Files.isSymbolicLink(path) -> {
                        require(path.toFile().canonicalFile.toPath().startsWith(base)) { "Package symbolic link escapes storage" }
                    }
                    Files.isDirectory(path, NOFOLLOW_LINKS) -> Unit
                    Files.isRegularFile(path, NOFOLLOW_LINKS) -> {
                        require(entries.size < maxFiles) { "Package exceeds its reviewed file bound" }
                        entries += relative to path
                    }
                    else -> error("Unsupported package file type")
                }
            }
        }
        return entries.sortedBy { it.first }
    }

    private fun checkedRelative(value: String) {
        require(value.length in 1..4096 && '\\' !in value && !value.startsWith('/') &&
            value.none { it.code < 32 || it.code == 127 } && value.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Unsafe checksum member"
        }
    }

    private fun safeFile(base: Path, path: Path) {
        require(path.startsWith(base) && Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "Checksum member is not a regular package file" }
        var parent = path.parent
        while (parent != base) {
            require(parent != null && parent.startsWith(base) && !Files.isSymbolicLink(parent)) { "Checksum member crosses a symbolic link" }
            parent = parent.parent
        }
    }

    private suspend fun hash(base: Path, path: Path, digest: MessageDigest, buffer: ByteArray,
        onBytes: (Long) -> Unit = {}): String {
        safeFile(base, path)
        digest.reset()
        val expectedLength = Files.size(path)
        var bytes = 0L
        var lastReportNs = System.nanoTime()
        Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                bytes = Math.addExact(bytes, read.toLong())
                require(bytes <= expectedLength) { "Package file changed while being verified" }
                digest.update(buffer, 0, read)
                val now = System.nanoTime()
                if (now - lastReportNs >= 1_000_000_000L) { lastReportNs = now; onBytes(bytes) }
            }
        }
        safeFile(base, path)
        require(bytes == expectedLength && Files.size(path) == expectedLength) { "Package file changed while being verified" }
        val digits = "0123456789abcdef"
        return buildString(64) { for (byte in digest.digest()) {
            val value = byte.toInt() and 255
            append(digits[value ushr 4]); append(digits[value and 15])
        } }
    }
}
