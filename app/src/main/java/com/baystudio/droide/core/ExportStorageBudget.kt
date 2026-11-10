package com.baystudio.droide.core

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream

/** Physical free-space headroom, not a source-size limit or a promise of reserved blocks. */
internal object ExportStorageBudget {
    const val RESERVE = 64L * 1024 * 1024
    fun requireMetadataAvailable(directory: File, available: Long = directory.usableSpace) {
        // Recovery moves existing bytes, not a capture copy. Do not make low-space recovery depend on
        // the capture reserve; actual ENOSPC still leaves its receipt/evidence intact.
        if (!WorkspaceTransferSpace.canStage(available, 4096, 0)) throw ExportFailure("InsufficientStorage")
    }
    fun requireAvailable(directory: File, remaining: Long = 0, available: Long = directory.usableSpace) {
        if (!WorkspaceTransferSpace.canStage(available, remaining, RESERVE))
            throw ExportFailure("InsufficientStorage")
    }
    fun output(directory: File, target: OutputStream, expectedBytes: Long? = null,
        available: () -> Long = { directory.usableSpace }): OutputStream {
        require(expectedBytes == null || expectedBytes >= 0)
        return object : FilterOutputStream(target) {
            private var written = 0L
            private var credit = 0L
            private var checkedAt = 0L
            override fun write(value: Int) { checkSpace(1); out.write(value); written = Math.addExact(written, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                if (length == 0) return
                checkSpace(length); out.write(bytes, offset, length); written = Math.addExact(written, length.toLong())
            }
            private fun checkSpace(next: Int) {
                val now = System.nanoTime()
                if (next.toLong() > credit || now - checkedAt >= 250_000_000L) {
                    val remaining = maxOf(next.toLong(), expectedBytes?.let { maxOf(0, it - written) } ?: 0)
                    val free = available()
                    requireAvailable(directory, remaining, free)
                    // This is not a disk reservation; racing external allocations can still cause ENOSPC and
                    // ordinary partial-capture retention.
                    credit = minOf(free - RESERVE, maxOf(1024L * 1024, next.toLong()))
                    checkedAt = now
                }
                credit -= next.toLong()
            }
        }
    }
}
