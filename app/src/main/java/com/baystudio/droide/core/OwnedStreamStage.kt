package com.baystudio.droide.core

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal enum class StagePathState { ABSENT_AT_RECORDED_PATH, PRESENT, FOREIGN, UNVERIFIABLE }

internal data class StageCloseObservation(val state: StagePathState, val bytes: Long? = null, val detail: String? = null)

/** Descriptor-pinned stage; closing observes candidates without mutating their names. */
internal class OwnedStreamStage private constructor(
    val file: File,
    private val channel: FileChannel,
    val identity: String,
    private val parentIdentity: String,
) : AutoCloseable {
    // Keep admitted staging until receipts and synchronization callbacks are verified.
    private var recoveryRequired = false
    private var closeAttempted = false
    @Volatile var closeObservation: StageCloseObservation? = null
        private set

    fun retainForRecovery() { recoveryRequired = true }

    fun publicationVerified() { recoveryRequired = false }

    // Closing a buffering layer must not release the inode pin before commit/cleanup.
    val output: OutputStream = object : java.io.FilterOutputStream(Channels.newOutputStream(channel)) {
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        override fun close() = flush()
    }

    fun requireOwned() {
        check(identityOf(file) == identity) { "Import staging file changed; foreign data was preserved" }
    }

    fun force(durability: StreamPublicationDurability) {
        requireOwned()
        durability.forceFile(channel)
        requireOwned()
    }

    @Synchronized override fun close() {
        if (closeAttempted) return
        closeAttempted = true
        try {
            val observed = observeCandidate(file, parentIdentity, identity)
            closeObservation = if (observed.state == StagePathState.PRESENT && observed.detail == null)
                observed.copy(detail = if (recoveryRequired) "Preserved for recovery" else "Automatic cleanup withheld")
            else observed
        } finally {
            channel.close()
        }
    }

    companion object {
        fun create(target: File, plannedName: String? = null): OwnedStreamStage {
            val name = plannedName ?: ".droide-tmp-${UUID.randomUUID()}"
            require(RetainedWorkPolicy.isCandidateName(name)) {
                "Invalid import stage name"
            }
            val parent = requireNotNull(target.parentFile) { "Import stage parent is unavailable" }
            val file = File(parent, name)
            var channel: FileChannel? = null
            try {
                val binding = RetainedWorkPolicy.creating(parent) { files ->
                    val parentKey = files.directoryKey()
                    files.createNew(name).use { opened ->
                        val observed = opened.observe().attributes
                        val identity = requireNotNull(observed.fileKey()).toString()
                        channel = opened.openWriteChannel()
                        check(observed.size() == 0L && requireNotNull(channel).size() == 0L && identityOf(file) == identity) {
                            "Import stage differs from its opened descriptor; foreign data was preserved"
                        }
                        identity to parentKey
                    }
                }
                return OwnedStreamStage(file, requireNotNull(channel), binding.first, binding.second)
            } catch (failure: Throwable) {
                try { channel?.close() } catch (close: Exception) { failure.addSuppressed(close) }
                if (failure is LinkageError) throw IOException("Native stage creation is unavailable; existing files were preserved", failure)
                throw failure
            }
        }

        fun observeCandidate(file: File, expectedParent: String, expectedIdentity: String?): StageCloseObservation = try {
            fun requireParent() {
                val attrs = Files.readAttributes(requireNotNull(file.parentFile).toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(attrs.isDirectory && attrs.fileKey()?.toString() == expectedParent) { "Recorded parent changed or is unavailable" }
            }
            requireParent()
            val result = try {
                val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attrs.isRegularFile) StageCloseObservation(StagePathState.FOREIGN, detail = "Entry is not a regular file; preserved without following it")
                else if (expectedIdentity != null && attrs.fileKey()?.toString() != expectedIdentity)
                    StageCloseObservation(StagePathState.FOREIGN, attrs.size(), "Named entry differs from the created descriptor")
                else StageCloseObservation(StagePathState.PRESENT, attrs.size(),
                    if (expectedIdentity == null) "Candidate ownership is unverified" else null)
            } catch (_: NoSuchFileException) { StageCloseObservation(StagePathState.ABSENT_AT_RECORDED_PATH) }
            requireParent()
            result
        } catch (failure: Exception) {
            StageCloseObservation(StagePathState.UNVERIFIABLE, detail = (failure.message ?: "Location could not be verified").take(300))
        } catch (_: LinkageError) {
            StageCloseObservation(StagePathState.UNVERIFIABLE, detail = "Filesystem observation is unavailable")
        }

        private fun identityOf(file: File): String? = try {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            attributes.takeIf { it.isRegularFile }?.fileKey()?.toString()
        } catch (_: NoSuchFileException) { null }
    }
}

internal interface StreamPublicationDurability {
    fun forceFile(channel: FileChannel)
    fun forceDirectory(directory: File)

    companion object {
        val platform = object : StreamPublicationDurability {
            override fun forceFile(channel: FileChannel) = channel.force(true)
            override fun forceDirectory(directory: File) {
                if (System.getProperty("java.vm.name") == "Dalvik" ||
                    System.getProperty("java.runtime.name") == "Android Runtime") {
                    val descriptor = Os.open(directory.absolutePath,
                        OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
                    try {
                        check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Stream durability target is not a directory" }
                        Os.fsync(descriptor)
                    } finally { Os.close(descriptor) }
                } else {
                    // Host JVM tooling backend, not a fallback after Android I/O failure.
                    FileChannel.open(directory.toPath(), READ, NOFOLLOW_LINKS).use { it.force(true) }
                }
            }
        }
    }
}
