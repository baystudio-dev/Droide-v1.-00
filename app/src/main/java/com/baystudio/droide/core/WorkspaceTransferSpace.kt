package com.baystudio.droide.core

internal object WorkspaceTransferSpace {
    fun canStage(available: Long, bytes: Long, reserve: Long, copies: Int = 1): Boolean {
        require(bytes >= 0L && reserve >= 0L && copies > 0)

        return available > reserve && bytes <= (available - reserve) / copies
    }

    fun requireAvailable(directory: java.io.File, bytes: Long, reserve: Long, copies: Int = 1) {
        require(canStage(directory.usableSpace, bytes, reserve, copies)) {
            "Not enough free storage for file transfer and safety headroom; existing files were retained"
        }
    }
}
