package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal object ProjectMetadataPublication {
    private interface Unconfirmed
    private class UnconfirmedIo(message: String, cause: Throwable) : IOException(message, cause), Unconfirmed
    private class UnconfirmedCancellation(message: String, cause: Throwable) : CancellationException(message), Unconfirmed {
        init { initCause(cause) }
    }
    private class UnconfirmedFatal(message: String, cause: Throwable) : Error(message, cause), Unconfirmed

    fun isUnconfirmed(failure: Throwable): Boolean = failure is Unconfirmed

    internal fun unconfirmed(target: File, cause: Throwable): Throwable {
        val message = "Project metadata persistence could not be confirmed: ${target.path}. Existing data was retained."
        return when (cause) {
            is Error -> UnconfirmedFatal(message, cause)
            is CancellationException -> UnconfirmedCancellation(message, cause)
            else -> UnconfirmedIo(message, cause)
        }
    }

    fun replace(source: Path, target: Path, options: Array<CopyOption>) {
        if (StandardCopyOption.REPLACE_EXISTING in options) {
            Files.move(source, target, *options)
        } else {
            // ATOMIC_MOVE without replacement must not enter the JDK overwrite path.
            AnchoredWorkspaceFiles(requireNotNull(source.parent).toFile()).use { origin ->
                AnchoredWorkspaceFiles(requireNotNull(target.parent).toFile()).use { destination ->
                    origin.moveNoReplace(source.fileName.toString(), destination, target.fileName.toString())
                }
            }
        }
    }

    fun write(target: File, text: String, replace: (Path, Path, Array<CopyOption>) -> Unit,
              durability: StreamPublicationDurability = StreamPublicationDurability.platform) {
        writeChecked(target, text, replace, durability, beforeReplace = {})
    }

    fun writeChecked(target: File, text: String, replace: (Path, Path, Array<CopyOption>) -> Unit,
                     durability: StreamPublicationDurability, replaceExisting: Boolean = true,
                     beforeReplace: () -> Unit) {
        val parent = requireNotNull(target.parentFile)
        val container = requireNotNull(parent.parentFile)
        val containerIdentity = WorkspaceDirectoryIdentity(container)
        parent.mkdirs()
        val parentIdentity = WorkspaceDirectoryIdentity(parent)
        fun confirm(action: () -> Unit) {
            try {
                containerIdentity.requireCurrent()
                parentIdentity.requireCurrent()
                action()
                containerIdentity.requireCurrent()
                parentIdentity.requireCurrent()
            } catch (failure: Throwable) { throw unconfirmed(target, failure) }
        }
        confirm { durability.forceDirectory(container) }
        val staged = target.toPath().resolveSibling(".${target.name}.tmp-${UUID.randomUUID()}")
        FileChannel.open(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text))
            while (bytes.hasRemaining()) channel.write(bytes)
            confirm { durability.forceFile(channel) }
        }
        confirm { durability.forceDirectory(parent) }
        // A rejected prepublication condition is known not to have replaced the target. Keep it outside
        // confirm/unconfirmed wrapping and retain the staged proposal.
        beforeReplace()

        var failedMove: Throwable? = null
        val options = if (replaceExisting) arrayOf<CopyOption>(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else arrayOf<CopyOption>(StandardCopyOption.ATOMIC_MOVE)
        try { replace(staged, target.toPath(), options) }
        catch (failure: Throwable) { failedMove = failure }
        try { confirm { durability.forceDirectory(parent) } }
        catch (flush: Throwable) {
            val move = failedMove
            if (move is Error || move is CancellationException && flush !is Error) {
                throw unconfirmed(target, move).also { it.addSuppressed(flush) }
            }
            if (move != null && move !== flush) flush.addSuppressed(move)
            throw flush
        }
        failedMove?.let { throw it }
    }
}
