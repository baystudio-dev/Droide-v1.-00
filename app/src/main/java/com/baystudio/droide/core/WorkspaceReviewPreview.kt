package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive

internal object WorkspaceReviewPreview {
    suspend fun text(root: File, path: String, expectedHash: String): String? {
        PathSecurity.resolveWithin(root, path)
        val bytes = AnchoredWorkspaceFiles(root).use { files -> files.openRead(path).use { input ->
            if (input.observe().attributes.size() > 32_768) return null
            val buffer = ByteArray(32_769)
            var count = 0
            while (count < buffer.size) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val n = input.read(buffer, count, buffer.size - count)
                if (n < 0) break
                count += n
            }
            buffer.copyOf(count)
        } }
        if (bytes.size > 32_768) return null
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == expectedHash) {
            "File changed while preparing review: $path. Review again; both versions were retained."
        }
        if (bytes.any { it == 0.toByte() }) return null
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { null }
    }
}
