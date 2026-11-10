package com.baystudio.droide.core

import java.io.IOException
import java.io.OutputStream

/** A returned byte count means writing and close both returned successfully, not provider fsync. */
internal object TerminalHistoryExportWriter {
    fun write(history: TerminalRetainedHistory, open: () -> OutputStream?, checkOwner: () -> Unit): Int {
        checkOwner()
        val output = open() ?: throw IOException("Document provider returned no output stream")
        val count = output.use { history.writeUtf8(it, checkOwner) }
        checkOwner()
        return count
    }
}
