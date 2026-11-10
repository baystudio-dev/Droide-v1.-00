package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal enum class ExportJobPhase {
    QUEUED, CAPTURING, WRITING, VERIFYING, COMPLETED_VERIFIED, CANCELLING,
    CANCELLED, FAILED, PARTIAL, UNVERIFIED, INTERRUPTED;
    val terminal: Boolean get() = this in setOf(COMPLETED_VERIFIED, CANCELLED, FAILED, PARTIAL, UNVERIFIED, INTERRUPTED)
}
internal data class ExportJobState(
    val id: String,
    val epoch: String,
    val phase: ExportJobPhase,
    val entries: Long = 0,
    val bytes: Long = 0,
    val detail: String = "",
    val createdCount: Long = 0,
    val sourceLabel: String = "",
    val providerAuthority: String = "",
    val retainedCapture: Boolean = false,
) { val terminal: Boolean get() = phase.terminal }

/** One schema validator for history, active writes and startup pointer classification. */
internal class ExportStateProperties : Properties() {
    val declaredVersions = mutableSetOf<String>()
    override fun put(key: Any, value: Any): Any? {
        if (key == "version") declaredVersions += value.toString()
        return super.put(key, value)
    }
}
internal object ExportStateCodec {
    fun decode(p: ExportStateProperties): ExportJobState {
        check(p.getProperty("version") == "1" && p.declaredVersions == setOf("1")) {
            "Unknown or ambiguous export journal version; retained for review"
        }
        val id = checkNotNull(p.getProperty("id"))
        val epoch = checkNotNull(p.getProperty("epoch"))
        check(id.length in 1..255 && id.matches(Regex("[a-zA-Z0-9-]+")) && epoch.isNotBlank()) { "Invalid export job identity; retained for review" }
        val entries = checkNotNull(p.getProperty("entries")).toLong()
        val bytes = checkNotNull(p.getProperty("bytes")).toLong()
        val created = p.getProperty("createdCount", "0").toLong()
        check(entries >= 0 && bytes >= 0 && created >= 0) { "Invalid export counters; retained for review" }
        val retained = p.getProperty("retainedCapture", "false")
        check(retained == "true" || retained == "false") { "Invalid export capture state; retained for review" }
        return ExportJobState(id, epoch, ExportJobPhase.valueOf(checkNotNull(p.getProperty("phase"))),
            entries, bytes, p.getProperty("detail", ""), created, p.getProperty("sourceLabel", ""),
            p.getProperty("providerAuthority", ""), retained == "true")
    }
}

internal data class ExportHistorySnapshot(
    val jobs: List<ExportJobState>,
    val unreadableRecords: Int,
    val currentUnreadable: Boolean,
    val preservedPointers: Int = 0,
)

/** Single source of truth, private and outside every project. Never automatically resumes output. */
internal class ExportJournal(private val base: File, private val requireAuthority: () -> Unit = {}) {
    private val stateFile = File(base, "current.properties")
    init { check(base.mkdirs() || base.isDirectory) { "Export journal storage is unavailable" } }
    fun jobDirectory(id: String): File {
        requireAuthority()
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        check(!Files.isSymbolicLink(base.toPath())) { "Export storage identity changed" }
        return File(base, id).apply {
            check(!Files.isSymbolicLink(toPath())) { "Export job identity changed" }
            val created = mkdirs()
            check(created || isDirectory)
            val marker = File(this, "owned-job")
            if (created) FileOutputStream(marker).use { it.write(id.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(marker.isFile && !Files.isSymbolicLink(marker.toPath()) && marker.readText() == id) { "Export job ownership is not established" }
        }
    }
    @Synchronized fun read(): ExportJobState? {
        if (!stateFile.exists()) return null
        return readState(stateFile)
    }
    private fun readState(file: File, checkCancelled: () -> Unit = {}): ExportJobState {
        val p = ExportStateProperties().also { properties ->
            Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
                val bounded = object : java.io.FilterInputStream(input) {
                    private var remaining = 1024 * 1024
                    override fun read(): Int {
                        checkCancelled()
                        return super.read().also {
                        if (it >= 0) check(--remaining >= 0) { "Export metadata exceeds the read limit" }
                        }
                    }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        checkCancelled()
                        return `in`.read(bytes, offset, minOf(length, remaining + 1)).also {
                            if (it > 0) { remaining -= it; check(remaining >= 0) { "Export metadata exceeds the read limit" } }
                        }
                    }
                }
                properties.load(bounded)
            }
        }
        return ExportStateCodec.decode(p)
    }

    @Synchronized fun write(state: ExportJobState) {
        requireAuthority()
        // Never overwrite a pointer corrupted while an existing controller still owns a job.
        if (Files.exists(stateFile.toPath(), NOFOLLOW_LINKS)) readState(stateFile)
        val p = Properties().apply {
            setProperty("version", "1"); setProperty("id", state.id); setProperty("epoch", state.epoch)
            setProperty("phase", state.phase.name); setProperty("entries", state.entries.toString())
            setProperty("bytes", state.bytes.toString()); setProperty("detail", state.detail)
            setProperty("createdCount", state.createdCount.toString())
            setProperty("sourceLabel", state.sourceLabel); setProperty("providerAuthority", state.providerAuthority)
            setProperty("retainedCapture", state.retainedCapture.toString())
        }
        val history = File(jobDirectory(state.id), "state.properties")
        val historyTemporary = File(history.parentFile, "state.tmp")
        FileOutputStream(historyTemporary).use { p.store(it, "Droide export job"); it.fd.sync() }
        check(historyTemporary.renameTo(history)) { "Could not persist export recovery state" }
        val temporary = File(base, "current.tmp")
        FileOutputStream(temporary).use { p.store(it, "Droide export job"); it.fd.sync() }
        check(temporary.renameTo(stateFile)) { "Could not persist export state" }
    }
    fun hasCapture(id: String): Boolean = Files.exists(File(jobDirectory(id), "capture").toPath(), NOFOLLOW_LINKS)

    /** Delete only the owned capture subtree. A symlink is unlinked, never traversed. */
    @Synchronized fun discardCapture(id: String) {
        val job = jobDirectory(id)
        check(job.canonicalFile.parentFile == base.canonicalFile) { "Export job escaped private storage" }
        val capture = File(job, "capture").toPath()
        if (!Files.exists(capture, NOFOLLOW_LINKS)) return
        Files.walkFileTree(capture, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
        check(!Files.exists(capture, NOFOLLOW_LINKS)) { "Local export staging could not be fully removed" }
    }
    /** Read-only isolation: invalid records remain byte-for-byte in place, never cleanup authority. */
    @Synchronized fun historySnapshot(checkCancelled: () -> Unit = {}): ExportHistorySnapshot {
        checkCancelled()
        check(!Files.isSymbolicLink(base.toPath())) { "Export storage identity changed" }
        val children = checkNotNull(base.listFiles()) { "Export history storage is unavailable" }
        val jobs = mutableListOf<ExportJobState>()
        var unreadable = 0
        for (directory in children) {
            checkCancelled()
            // Only job candidates. Pointer/temp/other files are not job directories.
            if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory.toPath())) continue
            if (Files.isSymbolicLink(directory.toPath())) { unreadable++; continue }
            val metadata = File(directory, "state.properties")
            if (!Files.exists(metadata.toPath(), NOFOLLOW_LINKS)) continue
            try {
                val marker = File(directory, "owned-job")
                check(directory.name.matches(Regex("[a-zA-Z0-9-]+")))
                check(Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() <= 256)
                val owner = Files.newInputStream(marker.toPath(), NOFOLLOW_LINKS).use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    repeat(257) {
                        checkCancelled()
                        val next = input.read()
                        if (next < 0) return@use bytes.toString("UTF-8")
                        bytes.write(next)
                    }
                    error("Export ownership marker exceeds the read limit")
                }
                check(owner == directory.name)
                val state = readHistoryState(metadata, checkCancelled)
                check(state.id == directory.name)
                jobs += state
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { unreadable++ }
        }
        val currentUnreadable = if (!Files.exists(stateFile.toPath(), NOFOLLOW_LINKS)) false else try {
            readHistoryState(stateFile, checkCancelled); false
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: Exception) { true }
        return ExportHistorySnapshot(jobs, unreadable, currentUnreadable, children.count {
            it.name.startsWith("recovery-") && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS)
        })
    }

    private fun readHistoryState(file: File, checkCancelled: () -> Unit): ExportJobState {
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        // Metadata is small; never read unbounded damaged metadata into the history screen.
        check(file.length() <= 1024 * 1024)
        return readState(file, checkCancelled)
    }

    /** Compatibility API; the history UI uses the snapshot so isolation is never silent. */
    @Synchronized fun history(): List<ExportJobState> = historySnapshot().jobs

    @Synchronized fun recordCreated(id: String, uri: String) {
        // Length-prefixed records allow arbitrary provider URIs without newline parsing.
        FileOutputStream(File(jobDirectory(id), "created-uris"), true).use { stream ->
            val output = java.io.DataOutputStream(stream)
            output.writeUTF(uri); output.flush(); stream.fd.sync()
        }
    }
}
