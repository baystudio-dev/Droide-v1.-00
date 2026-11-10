package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object AgentWorkspaceMediaInput {
    suspend fun openAdmitted(
        root: File,
        path: File,
        admission: suspend (() -> InputStream) -> InputStream,
    ): InputStream {
        val caller = currentCoroutineContext()
        var owned: InputStream? = null
        try {
            val accepted = admission {
                caller.ensureActive()
                check(owned == null) { "Media input creation was requested more than once" }
                open(root, path).also { owned = it }
            }
            check(accepted === owned) { "Media admission returned an unowned input" }
            caller.ensureActive()
            return accepted
        } catch (failure: Throwable) {
            try { owned?.close() }
            catch (close: Throwable) { if (close !== failure) failure.addSuppressed(close) }
            throw failure
        }
    }

    fun open(root: File, path: File, maxBytes: Long = ChatMediaStore.MAX_FILE_BYTES): InputStream {
        require(maxBytes in 1..ChatMediaStore.MAX_FILE_BYTES)
        val base = root.absoluteFile.toPath().normalize()
        val target = path.absoluteFile.toPath().normalize()
        require(target.startsWith(base) && target != base) { "Media output escaped its workspace" }
        val relative = base.relativize(target).toString().replace(File.separatorChar, '/')
        val files = AnchoredWorkspaceFiles(root)
        var input: AnchoredWorkspaceFiles.OpenedFile? = null
        try {
            val opened = files.openRead(relative)
            input = opened
            val initial = opened.observe()
            require(initial.attributes.size() in 1..maxBytes) { "Media output is empty or exceeds the supported limit" }
            return VerifiedInput(files, opened, relative, initial)
        } catch (failure: Throwable) {
            try { input?.close() } catch (close: Throwable) { failure.addSuppressed(close) }
            try { files.close() } catch (close: Throwable) { failure.addSuppressed(close) }
            throw failure
        }
    }

    private class VerifiedInput(
        private val files: AnchoredWorkspaceFiles,
        private val input: AnchoredWorkspaceFiles.OpenedFile,
        private val path: String,
        private val initial: AnchoredWorkspaceFiles.Observation,
    ) : InputStream() {
        private var readBytes = 0L
        private var verified = false
        private var closed = false
        private var invalid: IOException? = null

        @Synchronized override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!closed) { "Media input is closed" }
            invalid?.let { throw it }
            require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset)
            if (length == 0) return 0
            if (verified) return -1
            val count = input.read(bytes, offset, length)
            if (count >= 0) {
                if (count.toLong() > initial.attributes.size() - readBytes) changed()
                readBytes += count
                return count
            }
            try {
                if (readBytes != initial.attributes.size() || !same(initial, input.observe()) ||
                    !files.openRead(path).use { same(initial, it.observe()) }) changed()
            } catch (failure: Exception) {
                val rejection = IOException("CLI media changed during transfer; its working files were preserved", failure)
                invalid = rejection
                throw rejection
            }
            verified = true
            return -1
        }

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
        }

        @Synchronized override fun close() {
            if (closed) return
            closed = true
            try { input.close() } catch (failure: Throwable) {
                try { files.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                throw failure
            }
            files.close()
        }

        private fun changed(): Nothing {
            val rejection = IOException("CLI media changed during transfer; its working files were preserved")
            invalid = rejection
            throw rejection
        }

        private fun same(before: AnchoredWorkspaceFiles.Observation, after: AnchoredWorkspaceFiles.Observation): Boolean =
            before.attributes.fileKey() == after.attributes.fileKey() &&
                before.attributes.size() == after.attributes.size() &&
                before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime() &&
                before.changed == after.changed && before.executable == after.executable
    }
}
