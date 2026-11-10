package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** Private, disposable web/capture bytes; project files and unmarked directories are never swept. */
internal class AgentMediaScratch(workDir: File, purpose: String, private val clock: () -> Long = System::currentTimeMillis) {
    private val workRoot: File = ChatMediaStoragePaths.canonicalWorkspace(workDir)
    private val parent: File = File(ChatMediaStoragePaths.directory(workRoot), "scratch/$purpose")
    private val id = UUID.randomUUID().toString()
    private val markerText = "droide-media-scratch-v1:$purpose:$id"
    private val directory: File = File(parent, id)
    private val marker: File = File(directory, MARKER)
    private var initialized = false
    private var closed = false

    init { require(purpose in setOf("web", "browser")) { "Unknown media scratch purpose" } }

    @Synchronized fun create(extension: String): File {
        check(!closed) { "Media scratch scope is closed" }
        require(extension in EXTENSIONS) { "Unsupported media scratch extension" }
        ChatMediaStoragePaths.requireNoLinks(directory)
        if (!initialized) {
            cleanupStale()
            check(directory.mkdirs() || directory.isDirectory) { "Cannot create private media scratch directory" }
            marker.writeText(markerText)
            synchronized(ACTIVE) { ACTIVE += directory.absolutePath }
            initialized = true
        }
        marker.setLastModified(clock())
        return File(directory, "${UUID.randomUUID()}.$extension")
    }

    /** Files remain managed by their caller's leases; remove only an empty owned scope. */
    @Synchronized fun close() {
        closed = true
        synchronized(ACTIVE) { ACTIVE -= directory.absolutePath }
        if (!initialized || Files.isSymbolicLink(directory.toPath())) return
        if (directory.listFiles()?.all { it.name == MARKER } == true &&
            Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS) && marker.length() <= 160 && marker.readText() == markerText) {
            marker.delete()
            directory.delete()
        }
    }

    private fun cleanupStale(force: Boolean = false) {
        val now = clock()
        val shouldSweep = synchronized(SWEEPS) {
            val previous = SWEEPS[parent.absolutePath]
            if (!force && previous != null && now >= previous && now - previous < SWEEP_INTERVAL_MS) false
            else { if (SWEEPS.size >= 128) SWEEPS.clear(); SWEEPS[parent.absolutePath] = now; true }
        }
        if (!shouldSweep || now < ChatMediaStore.RETENTION_MS || !parent.isDirectory) return
        ChatMediaStoragePaths.requireNoLinks(parent)
        var inspected = 0
        Files.newDirectoryStream(parent.toPath()).use { scopes ->
            for (scope in scopes) {
                if (++inspected > MAX_SCOPES) break
                val file = scope.toFile()
                if (!UUID_NAME.matches(file.name) || !Files.isDirectory(scope, LinkOption.NOFOLLOW_LINKS)) continue
                if (synchronized(ACTIVE) { file.absolutePath in ACTIVE }) continue
                val receipt = File(file, MARKER)
                if (!Files.isRegularFile(receipt.toPath(), LinkOption.NOFOLLOW_LINKS) || receipt.length() > 160) continue
                if (receipt.lastModified() > now - ChatMediaStore.RETENTION_MS) continue
                if (receipt.readText() != "droide-media-scratch-v1:${parent.name}:${file.name}") continue
                // An unexpected file or link means this directory is not exclusively ours anymore.
                val owned = mutableListOf<File>()
                var safe = true
                Files.newDirectoryStream(scope).use { items ->
                    for (item in items) {
                        val entry = item.toFile()
                        if (owned.size >= MAX_FILES || !Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS) ||
                            entry.name != MARKER && !OWNED_FILE.matches(entry.name)) { safe = false; break }
                        owned += entry
                    }
                }
                if (safe) {
                    owned.filter { it.name != MARKER }.forEach { it.delete() }
                    if (file.listFiles()?.all { it.name == MARKER } == true) { receipt.delete(); file.delete() }
                }
            }
        }
    }

    companion object {
        /** Bounded receipt-only sweep for the existing seven-day media job, never a project-tree scan. */
        fun cleanup(workDir: File, now: Long = System.currentTimeMillis()) {
            for (purpose in listOf("web", "browser")) AgentMediaScratch(workDir, purpose) { now }.cleanupStale(force = true)
        }

        private const val MARKER = "scratch.owner"
        private const val MAX_SCOPES = 64
        private const val MAX_FILES = 64
        private const val SWEEP_INTERVAL_MS = 15L * 60 * 1000
        private val EXTENSIONS = setOf("pdf", "png", "jpg", "gif", "webp")
        private val UUID_NAME = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val OWNED_FILE = Regex("${UUID_NAME.pattern}\\.(pdf|png|jpg|gif|webp)")
        private val ACTIVE = mutableSetOf<String>()
        private val SWEEPS = mutableMapOf<String, Long>()
    }
}
