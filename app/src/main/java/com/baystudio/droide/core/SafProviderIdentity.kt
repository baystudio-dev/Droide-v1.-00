package com.baystudio.droide.core

import java.io.InputStream

/** Query-admitted identity only. Matching it never authorizes replay or journal cleanup. */
internal data class SafProviderIdentity(
    val documentId: String,
    val uri: String,
    val name: String,
    val mimeType: String,
)

/** Counts actual streamed bytes, not the provider's optional declared size. */
internal class SafReadBudget(private val maximum: Long) {
    init { require(maximum >= 0L) }
    var bytes: Long = 0L
        private set

    fun consume(count: Int) {
        require(count >= 0)
        check(count.toLong() <= maximum - bytes) { "SAF content exceeds its bounded byte limit" }
        bytes += count
    }
}

/** Tolerate a transient empty read without treating it as EOF or spinning forever. */
internal fun readSafChunk(input: InputStream, buffer: ByteArray, checkCancelled: () -> Unit): Int {
    repeat(32) {
        checkCancelled()
        val count = input.read(buffer)
        checkCancelled()
        if (count != 0) return count
    }
    error("SAF input made no progress within its read limit")
}
