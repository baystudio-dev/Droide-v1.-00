package com.baystudio.droide.core

import kotlinx.serialization.Serializable

/** Small private-session manifest entry. It is not an execution or cross-session capability.
 * The caller supplies the authorized session separately; never derive session authority here.
 */
@Serializable
data class AgentPublicContentRef(
    val version: Int = 1,
    val opaqueHandle: String,
    val runId: String,
    val itemId: String,
    val bytes: Long,
    val utf16Chars: Long,
    val sha256: String,
    val complete: Boolean,
) {
    init {
        require(version == 1) { "Unsupported public content reference version" }
        require(runId.isNotBlank() && runId.length <= 512 && itemId.isNotBlank() && itemId.length <= 512)
        require(bytes >= 0 && (utf16Chars >= 0 || (!complete && utf16Chars == -1L)))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        OwnedResponseContentStore.Handle.parse(opaqueHandle)
    }
    override fun toString() = "AgentPublicContentRef(version=$version, bytes=$bytes, complete=$complete, handle=redacted)"
}

/** UI text is a projection only. Persist/export the reference, never substitute the preview. */
@Serializable
data class ArchivedPublicAnswer(
    val preview: String,
    val reference: AgentPublicContentRef,
    val previewIsPartial: Boolean,
    /** False when interrupted UTF-8 cannot be decoded. Exact bytes remain exportable. */
    val textPreviewAvailable: Boolean = true,
)


/** Outcome describes what was checkpointed, not an assertion that all attempted input survived. */
@Serializable
enum class PublicAnswerAbortTailStatus {
    PRESERVED_AVAILABLE_BYTES,
    UNPAIRED_UTF16_NOT_REPRESENTABLE,
    FAILED_INPUT_PREFIX_ONLY,
}

/** Persist these flags together with the reference. Dropping them would hide a failed input
 * prefix or unrepresentable Unicode tail. No preview is read while cancellation is active.
 */
@Serializable
data class PublicAnswerAbortCheckpoint(
    val reference: AgentPublicContentRef,
    val tailStatus: PublicAnswerAbortTailStatus,
    val inputHadFailed: Boolean,
) {
    init {
        require(!reference.complete) { "An abort checkpoint cannot be complete" }
        require(tailStatus != PublicAnswerAbortTailStatus.FAILED_INPUT_PREFIX_ONLY || inputHadFailed)
    }
}
