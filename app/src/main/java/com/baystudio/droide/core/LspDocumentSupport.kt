package com.baystudio.droide.core

/** Document generation eligibility is independent of the workspace's execution grant. */
enum class LspDocumentSupportPhase { INSPECTION_PENDING, LEGACY_ELIGIBLE, UNSUPPORTED_NEXT_OR_8, CLOSED }

data class LspDocumentSupportStatus(
    val phase: LspDocumentSupportPhase,
    val message: String,
) {
    val permitsAnalysis: Boolean get() = phase == LspDocumentSupportPhase.LEGACY_ELIGIBLE
}

class LspDocumentSupportUnavailableException(val status: LspDocumentSupportStatus) :
    IllegalStateException(status.message)
