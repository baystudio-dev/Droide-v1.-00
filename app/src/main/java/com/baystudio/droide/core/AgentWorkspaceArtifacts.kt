package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Filesystem artifacts are bytes, independent of the ACP/editor text-document limit. */
internal object AgentWorkspaceArtifacts {
    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_FILES = 20_000

    fun requireRegular(root: File, path: File): BasicFileAttributes {
        val base = root.absoluteFile.toPath().normalize()
        val target = path.absoluteFile.toPath().normalize()
        require(target.startsWith(base) && target != base) { "Agent output escaped its workspace" }
        var cursor = base
        require(Files.isDirectory(base, NOFOLLOW_LINKS)) { "Agent workspace is unavailable" }
        for (part in base.relativize(target)) {
            cursor = cursor.resolve(part)
            require(!Files.isSymbolicLink(cursor)) { "Agent output contains a symbolic link" }
        }
        require(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "Agent output is not an ordinary file" }
        return AnchoredWorkspaceFiles(root).use { files ->
            files.openRead(base.relativize(target).toString().replace(File.separatorChar, '/')).use { it.observe().attributes }
        }
    }

    suspend fun pull(root: File, path: File, destination: File) = withContext(Dispatchers.IO) {
        requireRegular(root, path)
        val relative = root.absoluteFile.toPath().normalize().relativize(path.absoluteFile.toPath().normalize())
            .toString().replace(File.separatorChar, '/')
        val stageDirectory = requireNotNull(destination.parentFile) { "Agent transfer staging directory is unavailable" }
        AnchoredWorkspaceFiles(root).use { files -> files.openRead(relative).use { input ->
            val observed = input.observe()
            val before = observed.attributes
            WorkspaceTransferSpace.requireAvailable(stageDirectory, before.size(), 192L * 1024 * 1024, copies = 2)
            destination.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                var bytesSinceSpaceCheck = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count.toLong() <= before.size() - total) {
                        "Agent output grew during transfer; the CLI file was retained"
                    }
                    bytesSinceSpaceCheck += count
                    if (bytesSinceSpaceCheck >= 16L * 1024 * 1024) {
                        // The initial two-copy admission proves this sum cannot overflow.
                        WorkspaceTransferSpace.requireAvailable(stageDirectory,
                            before.size() + (before.size() - total), 192L * 1024 * 1024)
                        bytesSinceSpaceCheck = 0L
                    }
                    total += count
                    output.write(buffer, 0, count)
                }
                check(total == before.size()) { "Agent output changed during transfer; the CLI file was retained" }
            }
            val after = input.observe()
            val named = files.openRead(relative).use { it.observe() }
            check(unchanged(observed, after) && unchanged(observed, named)) {
                "Agent output changed during transfer; the CLI file was retained"
            }
            check(destination.setExecutable(observed.executable, true)) { "Cannot preserve CLI output executable state" }
        } }
    }

    /** Android-host file walk: no PRoot startup, shell pipeline or full-file allocation. */
    suspend fun snapshot(
        root: File,
        fingerprints: WorkspaceFileFingerprintCache = WorkspaceFileFingerprintCache(maxEntries = 0),
    ): Map<String, String> = snapshotEntries(root, fingerprints).mapValues { it.value.sha256 }

    suspend fun snapshotEntries(
        root: File,
        fingerprints: WorkspaceFileFingerprintCache = WorkspaceFileFingerprintCache(maxEntries = 0),
    ): Map<String, WorkspaceSyncManifest.Entry> = withContext(Dispatchers.IO) {
        AnchoredWorkspaceTree.snapshot(root, fingerprints, currentCoroutineContext()).files.mapValues { (_, observed) ->
            WorkspaceSyncManifest.Entry(observed.sha256, if (observed.executable) 493 else 420)
        }
    }

    private fun unchanged(before: AnchoredWorkspaceFiles.Observation, after: AnchoredWorkspaceFiles.Observation): Boolean =
        before.attributes.fileKey() == after.attributes.fileKey() && before.attributes.size() == after.attributes.size() &&
            before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime() &&
            before.changed == after.changed && before.executable == after.executable
}
