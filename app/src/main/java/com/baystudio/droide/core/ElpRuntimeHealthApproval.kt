package com.baystudio.droide.core

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** This request observes file metadata only. Version responses are obtained only after approval. */
class ElpRuntimeHealthApprovalRequest internal constructor(
    val selection: ElpToolSelection,
    val attemptId: String = UUID.randomUUID().toString(),
    val artifactSha256: String = LocalUbuntuElpPolicy.SHA256,
    val policy: String = LocalUbuntuElpPolicy.POLICY,
) {
    private val accepted=AtomicBoolean(false)
    private val declined=AtomicBoolean(false)
    fun decline() { declined.set(true) }
    internal fun acceptOnce() { validate();check(accepted.compareAndSet(false,true)) { "ELP health request was already accepted; request a fresh attempt" } }
    val selectionIdentity: String = selection.consentIdentity
    val title: String get() = "Run ELP and the selected Erlang tools for health?"
    val detail: String get() = "ELP ${LocalUbuntuElpPolicy.RELEASE_TAG}, ${LocalUbuntuElpPolicy.ASSET}, SHA-256 $artifactSha256. " +
        "This executes the selected programs: erl ${selection.erl.file.guestPath} (${selection.erl.file.sha256}); " +
        "escript ${selection.escript.file.guestPath} (${selection.escript.file.sha256}). Their versions and provenance are unverified. " +
        "Allow fixed bounded OTP/escript compatibility probes and a zero-document native initialize, ping, shutdown and exit. " +
        "Private empty working directory, HOME and TMPDIR; no Rebar or project analysis. Total health budget 90 seconds including 10 seconds for retirement; " +
        "CPU 60 seconds, address space 4 GiB per process, 512 file descriptors, 256 real-UID tasks; observed process/temp limits also apply. " +
        "This one-attempt approval does not authorize project builds or dependency fetching."
    internal fun validate() {
        check(!declined.get()) { "ELP health request was declined; request a fresh attempt" }
        require(attemptId.matches(Regex("[0-9a-f-]{36}")) && artifactSha256==LocalUbuntuElpPolicy.SHA256 && policy==LocalUbuntuElpPolicy.POLICY)
        require(selectionIdentity==selection.consentIdentity && selection.rebar==null && selection.python==null)
    }
}

/** Constructor is private: only the explicit Extensions acceptance route can issue this authority. */
class ElpStageHealthPermit private constructor(val request: ElpRuntimeHealthApprovalRequest) {
    private val valid=AtomicBoolean(true)
    private val claimed=AtomicBoolean(false)
    val attemptId: String get() = request.attemptId
    fun invalidate() { valid.set(false) }
    fun claim() { request.validate();check(valid.get()&&claimed.compareAndSet(false,true)){"ELP health approval was canceled or already used"} }
    fun requireCurrent(selection: ElpToolSelection, attemptId: String) {
        request.validate();check(valid.get()&&this.attemptId==attemptId&&selection.consentIdentity==request.selectionIdentity) { "ELP selected tools or health attempt changed; approve again" }
    }
    fun requireCurrent() { requireCurrent(request.selection,attemptId) }
    fun requireClaimed() { requireCurrent();check(claimed.get()) { "ELP health permit was not claimed by an install attempt" } }
    internal companion object {
        fun acceptedFromExtensions(request: ElpRuntimeHealthApprovalRequest): ElpStageHealthPermit {
            request.acceptOnce();return ElpStageHealthPermit(request)
        }
    }
}
