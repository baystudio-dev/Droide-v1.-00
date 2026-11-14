package com.baystudio.droide.core

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Public-answer bridge over the owned store; no provider/private/tool content is exposed.
 *
 * Not yet wired into AgentMessage/SessionManager: that integration must retain the blob before
 * publishing a session and explicitly call copyForFork before publishing a child session.
 * No methods accept a session ID from a content reference or expose a whole-answer String.
 */
internal class AgentPublicContentArchive(
    private val store: OwnedResponseContentStore,
    private val authorizedSessionId: String,
    private val previewChars: Int = 16_384,
) {
    init { require(authorizedSessionId.isNotBlank()); require(previewChars in 2..65_536) }

    fun begin(runId: String, itemId: String): Capture {
        val session = session(runId, itemId)
        return Capture(runId, itemId, session.begin(OwnedResponseContentStore.Kind.ANSWER))
    }

    inner class Capture internal constructor(
        private val runId: String,
        private val itemId: String,
        private val writer: OwnedResponseContentStore.Session.Writer,
    ) : Closeable {
        /** Recovery only; not a complete public-content descriptor and not executable. */
        val recoveryHandle: OwnedResponseContentStore.Handle get() = writer.recoveryHandle
        fun append(text: CharSequence) = writer.append(text)
        fun appendUtf8(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) = writer.appendUtf8(bytes, offset, length)

        /** Upstream caller must have validated protocol completion, not just EOF.
         * Public plaintext has no tool semantics; strict Unicode and integrity are store-owned.
         */
        fun finishAfterProviderCompletion(): ArchivedPublicAnswer = archived(runId, itemId,
            writer.sealCompleted { input -> drain(input) }.content)

        /** For deliberate interruption while the writer remains usable. A failed/cancelled writer
         * keeps a recoverable stage; do not convert an I/O failure into successful completion.
         */
        fun finishIncomplete(): ArchivedPublicAnswer = archived(runId, itemId, writer.sealIncomplete())

        /** Finite cleanup of already received data, including inline data before spill.
         * Runs blocking I/O: caller must run it off the UI thread. No normal cancelled-store
         * read/open is attempted, and no global cancellation flag is changed. Store/disk
         * failures still throw; the result reports only the actual checkpointed prefix.
         */
        fun abortToIncomplete(): PublicAnswerAbortCheckpoint {
            val checkpoint = writer.abortToIncomplete()
            check(checkpoint.content.state == OwnedResponseContentStore.State.INCOMPLETE)
            val status = when (checkpoint.tailStatus) {
                OwnedResponseContentStore.AbortTailStatus.PRESERVED_AVAILABLE_BYTES -> PublicAnswerAbortTailStatus.PRESERVED_AVAILABLE_BYTES
                OwnedResponseContentStore.AbortTailStatus.UNPAIRED_UTF16_NOT_REPRESENTABLE -> PublicAnswerAbortTailStatus.UNPAIRED_UTF16_NOT_REPRESENTABLE
                OwnedResponseContentStore.AbortTailStatus.FAILED_INPUT_PREFIX_ONLY -> PublicAnswerAbortTailStatus.FAILED_INPUT_PREFIX_ONLY
            }
            return PublicAnswerAbortCheckpoint(referenceForContent(runId, itemId, checkpoint.content), status, checkpoint.inputHadFailed)
        }
        override fun close() = writer.close()
    }

    /** Metadata/ownership/kind must match before preview or bytes are returned. */
    fun reopen(reference: AgentPublicContentRef): ArchivedPublicAnswer {
        val content = verified(reference)
        return archived(reference.runId, reference.itemId, content)
    }

    /** Explicit recovery never upgrades an unfinished stage to complete. */
    fun recoverIncomplete(runId: String, itemId: String, recoveryHandle: OwnedResponseContentStore.Handle): ArchivedPublicAnswer =
        archived(runId, itemId, session(runId, itemId).recoverIncomplete(recoveryHandle, OwnedResponseContentStore.Kind.ANSWER))

    fun openPages(reference: AgentPublicContentRef): OwnedResponseContentStore.PageReader {
        verified(reference)
        return session(reference.runId, reference.itemId).openPages(handle(reference))
    }

    /** Exact UTF-8 export, including an intentionally incomplete public answer. Caller owns
     * destination permission and atomic staging; this method never closes the destination.
     */
    fun export(reference: AgentPublicContentRef, destination: OutputStream): Long {
        verified(reference)
        return session(reference.runId, reference.itemId).openBytes(handle(reference)).use { source ->
            transfer(source) { bytes, count -> destination.write(bytes, 0, count) }
        }.also { check(it == reference.bytes) { "Public content length changed during export" } }
    }

    /** Explicit copy before committing child session metadata. Source authority is this archive;
     * target authority is separately supplied by the caller, not taken from untrusted content.
     * Parent blob remains intact even if fork publication later fails.
     */
    fun copyForFork(reference: AgentPublicContentRef, target: AgentPublicContentArchive, runId: String, itemId: String): ArchivedPublicAnswer {
        verified(reference)
        target.begin(runId, itemId).use { capture ->
            session(reference.runId, reference.itemId).openBytes(handle(reference)).use { source ->
                transfer(source) { bytes, count -> capture.appendUtf8(bytes, 0, count) }
            }
            val copy = if (reference.complete) capture.finishAfterProviderCompletion() else capture.finishIncomplete()
            check(copy.reference.bytes == reference.bytes && (reference.utf16Chars < 0 || copy.reference.utf16Chars == reference.utf16Chars) &&
                copy.reference.sha256 == reference.sha256 && copy.reference.complete == reference.complete) {
                "Public content copy did not preserve the original"
            }
            return copy
        }
    }

    private fun verified(ref: AgentPublicContentRef): OwnedResponseContentStore.Content {
        val content = session(ref.runId, ref.itemId).open(handle(ref))
        check(content.kind == OwnedResponseContentStore.Kind.ANSWER) { "Only public answers may be displayed or exported here" }
        check(content.byteLength == ref.bytes && content.utf16Length == ref.utf16Chars && content.sha256 == ref.sha256 &&
            (content.state == OwnedResponseContentStore.State.COMPLETE) == ref.complete) { "Public content descriptor does not match owned data" }
        return content
    }

    /** Pure descriptor construction: in particular, safe after the writer's scoped abort
     * cleanup, when ordinary store reads correctly remain cancelled.
     */
    private fun referenceForContent(runId: String, itemId: String, content: OwnedResponseContentStore.Content): AgentPublicContentRef {
        check(content.kind == OwnedResponseContentStore.Kind.ANSWER &&
            (content.utf16Length >= 0 || content.state == OwnedResponseContentStore.State.INCOMPLETE))
        return AgentPublicContentRef(opaqueHandle = content.handle.serialized, runId = runId, itemId = itemId,
            bytes = content.byteLength, utf16Chars = content.utf16Length, sha256 = content.sha256,
            complete = content.state == OwnedResponseContentStore.State.COMPLETE)
    }

    private fun archived(runId: String, itemId: String, content: OwnedResponseContentStore.Content): ArchivedPublicAnswer {
        val ref = referenceForContent(runId, itemId, content)
        val preview = StringBuilder(minOf(previewChars.toLong(), content.utf16Length.coerceAtLeast(0)).toInt())
        var textAvailable = true
        try { session(runId, itemId).openPages(content.handle).use { pages ->
            while (preview.length < previewChars) {
                val part = pages.readPage(minOf(8192, maxOf(2, previewChars - preview.length))) ?: break
                val remaining = previewChars - preview.length
                var count = minOf(remaining, part.length)
                if (count < part.length && count > 0 && Character.isHighSurrogate(part[count - 1])) count--
                preview.append(part, 0, count)
                if (count < part.length) break
            }
        }
        } catch (invalidText: java.nio.charset.CharacterCodingException) {
            // Incomplete byte artifacts may have an interrupted UTF-8 suffix. Do not
            // repair/replace bytes, hide disk errors, or mislabel partial output complete.
            check(content.state == OwnedResponseContentStore.State.INCOMPLETE) { "Completed public content has invalid UTF-8" }
            textAvailable = false
        }
        return ArchivedPublicAnswer(preview.toString(), ref,
            content.utf16Length < 0 || content.utf16Length > preview.length, textAvailable)
    }

    private fun session(runId: String, itemId: String) = store.authorize(OwnedResponseContentStore.Owner(authorizedSessionId, runId, itemId))
    private fun handle(ref: AgentPublicContentRef) = OwnedResponseContentStore.Handle.parse(ref.opaqueHandle)
    private fun drain(source: InputStream) { transfer(source) { _, _ -> } }
    private fun transfer(source: InputStream, write: (ByteArray, Int) -> Unit): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = source.read(buffer)
            if (count < 0) return total
            if (count == 0) continue
            write(buffer, count)
            total = Math.addExact(total, count.toLong())
        }
    }
}
