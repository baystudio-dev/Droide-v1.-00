package com.baystudio.droide.core

import java.util.UUID

/** One Agent output only. This is explicit uncertainty consent, never frame attestation. */
class BrowserOutputConsent private constructor(
    private val permissions: PermissionEngine,
    private val policy: PermissionEngine.BrowserOutputAuthority,
    internal val request: Request,
    private val requestCurrent: () -> Boolean,
    private val documentCurrent: () -> Boolean,
) {
    internal data class Request(
        val sessionId: String,
        val runId: String,
        val callId: String,
        val permissionScope: String,
        val document: AgentBrowserController.DocumentIdentity,
        val operation: String,
        val fullPage: Boolean = false,
        val consoleIdentity: Any? = null,
        val nonce: String = UUID.randomUUID().toString(),
        val captureContract: String = CAPTURE_CONTRACT,
    )

    private enum class State { PENDING, APPROVED, CONSUMED, INVALID }
    private val lock = Any()
    private var state = State.PENDING
    private var resultBound = false

    /** This exact tagged Observation is the only unknown-source exception. */
    class Receipt internal constructor(
        private val owner: BrowserOutputConsent,
        private val observation: () -> AgentBrowserController.Observation,
    ) {
        internal fun permits(candidate: AgentBrowserController.Observation): Boolean =
            candidate === observation() && owner.validConsumed()
    }

    // Never call controller/run/policy predicates while holding the token lock. Native capture owns
    // the controller lock when it consumes this token.
    private fun valid(): Boolean {
        if (synchronized(lock) { state == State.INVALID }) return false
        val current = permissions.browserOutputAuthorityCurrent(policy, request.permissionScope) &&
            requestCurrent() && documentCurrent()
        return synchronized(lock) {
            if (!current) state = State.INVALID
            state != State.INVALID
        }
    }

    internal fun validForApproval(): Boolean = valid() && synchronized(lock) { state == State.PENDING }
    internal fun approve(): Boolean {
        if (!valid()) return false
        return synchronized(lock) {
            if (state != State.PENDING) false else { state = State.APPROVED; true }
        }
    }
    internal fun validForDispatch(): Boolean = valid() && synchronized(lock) {
        state == State.APPROVED || state == State.CONSUMED
    }
    private fun validConsumed(): Boolean = valid() && synchronized(lock) { state == State.CONSUMED && resultBound }

    /** Admission consumes once. Repeatable dispatch/publication checks never consume again. */
    internal fun consume(operation: String, document: AgentBrowserController.DocumentIdentity,
        fullPage: Boolean = false, consoleIdentity: Any? = null) {
        val current = valid()
        val sameDocument = document.leaseToken === request.document.leaseToken &&
            document.pageGeneration == request.document.pageGeneration && document.url == request.document.url &&
            document.authorityKey == request.document.authorityKey
        val scopeMatches = operation == request.operation && fullPage == request.fullPage &&
            consoleIdentity === request.consoleIdentity && sameDocument
        synchronized(lock) {
            if (state != State.APPROVED || !scopeMatches || !current) {
                state = State.INVALID
                throw BrowserPublicationRevokedException(WITHHELD)
            }
            state = State.CONSUMED
        }
    }

    /** Called only by the screenshot/frozen-console branches, never a dialog fallback. */
    internal fun complete(observation: AgentBrowserController.Observation): AgentBrowserController.Observation {
        val current = valid()
        return synchronized(lock) {
            if (state != State.CONSUMED || resultBound || !current) {
                state = State.INVALID
                throw BrowserPublicationRevokedException(WITHHELD)
            }
            lateinit var tagged: AgentBrowserController.Observation
            val receipt = Receipt(this) { tagged }
            tagged = observation.copy(outputConsent = receipt)
            resultBound = true
            tagged
        }
    }

    internal fun invalidate() = synchronized(lock) { state = State.INVALID }

    companion object {

        internal val CAPTURE_CONTRACT = "vertical-v1:${AgentBrowserCapture.MAX_BITMAP_BYTES}:${AgentBrowserCapture.MAX_OUTPUT_WIDTH}:${AgentBrowserCapture.MAX_OUTPUT_HEIGHT}:${AgentBrowserCapture.MAX_SOURCE_HEIGHT}:${AgentBrowserCapture.MAX_TILES}:${AgentBrowserCapture.MAX_CAPTURE_MS}"
        const val DISCLOSURE = "This one output may contain content from other or unknown frames or sources. Their origins cannot all be verified. This is not site isolation and does not grant future access."
        internal const val WITHHELD = "DENIED: this browser output consent is missing, stale or revoked. Output was withheld; do not replay the operation automatically."

        internal fun create(permissions: PermissionEngine, request: Request,
            requestCurrent: () -> Boolean, documentCurrent: () -> Boolean): BrowserOutputConsent {
            require(request.sessionId.isNotBlank() && request.runId.isNotBlank() && request.callId.isNotBlank())
            require(request.operation in setOf("screenshot", "console"))
            require((request.operation == "console") == (request.consoleIdentity != null))
            require(request.operation == "screenshot" || !request.fullPage)
            require(request.captureContract == CAPTURE_CONTRACT)
            val policy = permissions.browserOutputAuthority(request.permissionScope)
            if (policy.unresolvedDeny) throw BrowserPublicationRevokedException(
                "DENIED: unverified browser contributors could match an applicable read/local DENY. Output was withheld.")
            return BrowserOutputConsent(permissions, policy, request, requestCurrent, documentCurrent)
        }
    }
}
