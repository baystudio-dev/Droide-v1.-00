package com.baystudio.droide.core

/** Disk admission for streamed files. No product-specific maximum file size. */
internal object WorkspaceTransferSpace {
    fun canStage(available: Long, bytes: Long, reserve: Long, copies: Int = 1): Boolean {
        require(bytes >= 0L && reserve >= 0L && copies > 0)
        // Subtract/divide rather than adding reserve or multiplying a large file length.
        return available > reserve && bytes <= (available - reserve) / copies
    }

    fun requireAvailable(directory: java.io.File, bytes: Long, reserve: Long, copies: Int = 1) {
        require(canStage(directory.usableSpace, bytes, reserve, copies)) {
            "Not enough free storage for file transfer and safety headroom; existing files were retained"
        }
    }
}
