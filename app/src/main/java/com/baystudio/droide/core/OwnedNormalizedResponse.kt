package com.baystudio.droide.core

import java.io.FilterInputStream
import java.io.InputStream

/** Migration seam, not yet the production LlmStreamResult or tool turn. */

internal class OwnedNormalizedResponse(
    val target: ProviderReplayTarget,
    val publicText: PublicText?,
    val privateReplay: PrivateReplay?,
) {
    init { require(privateReplay == null || privateReplay.target == target) { "Replay target changed" } }

    class PublicText private constructor(internal val reference: Reference) {
        val byteLength: Long get() = reference.byteLength
        val sha256: String get() = reference.sha256
        companion object {
            /** Call only after actual provider completion. This checks storage, not provider semantics. */
            fun admit(session: OwnedResponseContentStore.Session, handle: OwnedResponseContentStore.Handle): PublicText =
                PublicText(Reference.complete(session, handle, OwnedResponseContentStore.Kind.ANSWER))
        }
        override fun toString() = "PublicText(bytes=$byteLength, owned=true)"
    }

    class PrivateReplay private constructor(
        val target: ProviderReplayTarget,
        internal val reference: Reference,
    ) {
        val byteLength: Long get() = reference.byteLength
        companion object {
            /** Native OBJECT envelope only. */

            fun admit(
                session: OwnedResponseContentStore.Session,
                handle: OwnedResponseContentStore.Handle,
                target: ProviderReplayTarget,
                semanticValidator: (InputStream) -> Unit,
            ): PrivateReplay {
                val reference = Reference.complete(session, handle, OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION)
                session.openBytes(handle).use { StreamedJsonValidator.requireObject(it) }
                session.openBytes(handle).use { source ->
                    val witness = EofWitness(source)
                    semanticValidator(witness)
                    check(witness.sawEof) { "Native replay admission must consume the whole document" }
                }
                return PrivateReplay(target, reference)
            }
        }
        override fun toString() = "PrivateReplay(bytes=$byteLength, content=redacted)"
    }

    internal class Reference private constructor(
        val handle: OwnedResponseContentStore.Handle,
        val byteLength: Long,
        val sha256: String,
        val kind: OwnedResponseContentStore.Kind,
    ) {
        fun verify(session: OwnedResponseContentStore.Session) {
            val current = session.openCompleted(handle).content
            check(current.kind == kind && current.byteLength == byteLength && current.sha256 == sha256) {
                "Owned provider content changed since admission"
            }
        }
        companion object {
            fun complete(session: OwnedResponseContentStore.Session, handle: OwnedResponseContentStore.Handle,
                         expected: OwnedResponseContentStore.Kind): Reference {
                val content = session.openCompleted(handle).content
                check(content.kind == expected) { "Unexpected provider content kind" }
                return Reference(handle, content.byteLength, content.sha256, content.kind)
            }
        }
        override fun toString() = "OwnedProviderReference(redacted)"
    }

    private class EofWitness(input: InputStream) : FilterInputStream(input) {
        var sawEof = false
        override fun read(): Int = `in`.read().also { if (it < 0) sawEof = true }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { if (it < 0) sawEof = true }
        override fun skip(count: Long): Long = 0
        override fun markSupported() = false
    }
}

/** Trusted configuration identity, not supplied by response JSON. endpointIdentity must identify
 * the canonical configured endpoint (or its stable fingerprint). */
internal data class ProviderReplayTarget(
    val providerId: String,
    val modelId: String,
    val protocol: Protocol,
    val endpointIdentity: String,
) {
    enum class Protocol { OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC_MESSAGES, GOOGLE_GENERATE_CONTENT }
    init {
        require(providerId.isNotBlank() && providerId.length <= 512)
        require(modelId.isNotBlank() && modelId.length <= 512)
        require(endpointIdentity.isNotBlank() && endpointIdentity.length <= 2048)
    }
    override fun toString() = "ProviderReplayTarget(protocol=$protocol, identity=redacted)"
}
