package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Byte identity only. Not a publication receipt or filesystem CAS token. */
data class StreamContentVersion(val existed: Boolean, val bytes: Long, val sha256: String) {
    init {
        require(bytes >= 0)
        require(if (existed) sha256.matches(Regex("[a-f0-9]{64}")) else bytes == 0L && sha256.isEmpty())
    }
    suspend fun requireMatches(file: File) {
        val job = currentCoroutineContext()
        requireMatchesBlocking(file) { job.ensureActive() }
    }
    internal fun requireMatchesBlocking(file: File, checkActive: () -> Unit = {}) {
        checkActive()
        if (!existed) { require(!Files.exists(file.toPath(), NOFOLLOW_LINKS)); return }
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && Files.size(file.toPath()) == bytes)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                checkActive()
                val n = input.read(buffer); if (n < 0) break
                check(n > 0 && n.toLong() <= bytes - count) { "Streamed content changed" }
                count += n; digest.update(buffer, 0, n)
            }
        }
        require(count == bytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == sha256) {
            "Streamed content changed; recovery data preserved"
        }
    }
    companion object {
        fun of(version: StreamFileVersion) = StreamContentVersion(version.existed, version.bytes, version.sha256)
        fun of(preparation: StreamPublicationPreparation) = StreamContentVersion(true, preparation.bytes, preparation.sha256)
    }
}
