package com.baystudio.droide.core

/** Publication seam for already admitted PUBLIC completion text, never private reasoning.
 * Large content is durably referenced before its bounded display projection is published.
 * Does not change the provider input budget or authorize/extract tool arguments.
 */
internal object AgentPublicAnswerPublication {
    data class Result(val display: String, val reference: AgentPublicContentRef?)
    fun complete(
        publicText: String,
        inlineDisplayChars: Int,
        archive: () -> AgentPublicContentArchive,
        runId: String,
        itemId: String,
        checkOwner: () -> Unit,
    ): Result {
        require(inlineDisplayChars > 0)
        checkOwner()
        if (publicText.isBlank()) return Result(publicText.take(inlineDisplayChars), null)
        if (publicText.length <= inlineDisplayChars) return Result(publicText, null)
        archive().begin(runId, itemId).use { capture ->
            var offset = 0
            while (offset < publicText.length) {
                checkOwner()
                val end = minOf(publicText.length, offset + 8192)
                capture.append(publicText.subSequence(offset, end))
                offset = end
            }
            val completed = capture.finishAfterProviderCompletion()
            checkOwner()
            return Result(completed.preview, completed.reference)
        }
    }
}
