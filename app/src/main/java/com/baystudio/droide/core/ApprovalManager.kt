package com.baystudio.droide.core

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

 
data class ApprovalRequest(
    val id: Long,
    val kind: String, 
    val summary: String,
    val detail: String, 
    val action: String = "",
    val resource: String = "",
    val permissionScope: String = "",
    val provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
     
    val approvalSessionId: String = "",
     
    val sessionGrantAction: String = "",
    val sessionGrantResource: String = "",
    val browserContext: BrowserApprovalContext? = null,
    val proposedChangeTotals: AgentChangeTotals? = null,
    val proposedContent: ProposedFileContent? = null,
    val inputReview: ApprovalInputReview? = null,
) {
    val canAllowForSession: Boolean get() =
        approvalSessionId.isNotBlank() && permissionScope.isNotBlank() &&
            sessionGrantAction.isNotBlank() && sessionGrantResource.isNotBlank()
}

sealed interface ApprovalDecision { data object Approved : ApprovalDecision; data object Denied : ApprovalDecision }
enum class ApprovalResolution { DENY, ALLOW_ONCE, ALLOW_SESSION }


class ApprovalManager {
    private class BrowserValidity(val check: () -> Boolean, val admit: (suspend () -> Boolean)?) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<BrowserValidity>
    }

    /** Restricts approval admission/resolution for this browser call, including doom/hooks. */
    internal suspend fun <T> withBrowserValidity(check: () -> Boolean, admission: (suspend () -> Boolean)? = null,
        block: suspend () -> T): T = kotlinx.coroutines.withContext(BrowserValidity(check, admission)) { block() }

    private class OwnerAdmission(val manager: ApprovalManager, val owner: ApprovalOwnerAdmission) :
        kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<OwnerAdmission>
    }

    /** Bind this manager's nested tool/doom approvals without changing unrelated callers. */
    internal suspend fun <T> withApprovalOwner(owner: ApprovalOwnerAdmission, block: suspend () -> T): T =
        kotlinx.coroutines.withContext(OwnerAdmission(this, owner)) { block() }

    private data class SessionGrant(
        val sessionId: String,
        val permissionScope: String,
        val action: String,
        val resource: String,
    )

    private var nextId = 1L
    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()

    private val conts = mutableMapOf<Long, CancellableContinuation<ApprovalDecision>>()
    private val validityChecks = mutableMapOf<Long, () -> Boolean>()
    private val ownerAdmissions = mutableMapOf<Long, ApprovalOwnerAdmission>()
    private val sessionGrants = LinkedHashSet<SessionGrant>()
    private val requestMutex = Mutex()
    private val stateLock = Any()
    private var retired = false

    /** Withdraw finite in-memory authority now; deliver Denied outside owner/publication locks. */
    @OptIn(InternalCoroutinesApi::class)
    internal fun retire(): () -> Unit {
        val deliveries = synchronized(stateLock) {
            retired = true
            sessionGrants.clear()
            val reserved = conts.values.mapNotNull { continuation ->
                continuation.tryResume(ApprovalDecision.Denied)?.let { continuation to it }
            }
            conts.clear()
            validityChecks.clear()
            ownerAdmissions.clear()
            _pending.value = null
            reserved
        }
        return { deliveries.forEach { (continuation, token) -> continuation.completeResume(token) } }
    }

    suspend fun requestApproval(
        kind: String,
        summary: String,
        detail: String,
        action: String = "",
        resource: String = "",
        permissionScope: String = "",
        provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
        approvalSessionId: String = provenance.sessionId,
        sessionGrantAction: String = action,
        sessionGrantResource: String = resource,
        browserContext: BrowserApprovalContext? = null,
        isStillCurrent: (() -> Boolean)? = null,
        ownerAdmission: ApprovalOwnerAdmission? = null,
        proposedChangeTotals: AgentChangeTotals? = null,
        proposedContent: ProposedFileContent? = null,
    ): ApprovalDecision = requestApproval(
        kind, summary, detail, action, resource, permissionScope, provenance,
        approvalSessionId, sessionGrantAction, sessionGrantResource, browserContext,
        isStillCurrent, ownerAdmission, proposedChangeTotals, proposedContent, inputReview = null,
    )

    @OptIn(InternalCoroutinesApi::class)
    suspend fun requestApproval(
        kind: String,
        summary: String,
        detail: String,
        action: String = "",
        resource: String = "",
        permissionScope: String = "",
        provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
        approvalSessionId: String = provenance.sessionId,
        sessionGrantAction: String = action,
        sessionGrantResource: String = resource,
        browserContext: BrowserApprovalContext? = null,
        isStillCurrent: (() -> Boolean)? = null,
        ownerAdmission: ApprovalOwnerAdmission? = null,
        proposedChangeTotals: AgentChangeTotals? = null,
        proposedContent: ProposedFileContent? = null,
        inputReview: ApprovalInputReview?,
    ): ApprovalDecision = requestMutex.withLock {
        if (synchronized(stateLock) { retired }) return@withLock ApprovalDecision.Denied
        val capturedOwnerAdmission = ownerAdmission ?: kotlinx.coroutines.currentCoroutineContext()[OwnerAdmission]
            ?.takeIf { it.manager === this }?.owner
        val browserContextValidity = kotlinx.coroutines.currentCoroutineContext()[BrowserValidity]
        // Admission may have waited behind an unrelated prompt. Sample again now,
        // outside stateLock, before making a browser-owned prompt visible.
        if (browserContextValidity?.admit?.invoke() == false) return@withLock ApprovalDecision.Denied
        val browserValidity = browserContextValidity?.check
        val validity: (() -> Boolean)? = if (browserValidity == null && inputReview == null) isStillCurrent else {
            { (browserValidity?.invoke() != false) && (isStillCurrent?.invoke() != false) &&
                (inputReview?.let { runCatching { it.requireCurrent() }.isSuccess } != false) }
        }
        if (validity != null && !runCatching(validity).getOrDefault(false)) return@withLock ApprovalDecision.Denied
        val scope = permissionScope.takeIf { it.isNotBlank() }
            ?.let(PermissionPolicyDocument::normalizeAgentId).orEmpty()
        val sessionId = if (kind == ToolchainInstallConsent.KIND) "" else normalizeApprovalSessionId(approvalSessionId)
        val grantAction = normalizeGrantAction(sessionGrantAction)
        val grantResource = normalizeGrantResource(sessionGrantResource, grantAction)
        val cached = withOwnerAdmission(capturedOwnerAdmission) { current ->
            synchronized(stateLock) {
                if (!current || retired) ApprovalDecision.Denied
                else if (sessionId.isNotBlank() && scope.isNotBlank() && grantAction.isNotBlank() && grantResource.isNotBlank() &&
                    SessionGrant(sessionId, scope, grantAction, grantResource) in sessionGrants) ApprovalDecision.Approved
                else null
            }
        }
        if (cached != null) return@withLock cached

        val requestId = nextId++
        val req = ApprovalRequest(
            id = requestId,
            kind = kind,
            summary = provenance.decorateSummary(summary),
            detail = detail.take(if (kind == ToolchainInstallConsent.KIND) ToolchainInstallConsent.MAX_DETAIL else 8_000),
            action = action,
            resource = resource,
            permissionScope = scope,
            provenance = provenance,
            approvalSessionId = sessionId,
            sessionGrantAction = grantAction,
            sessionGrantResource = grantResource,
            browserContext = browserContext,
            proposedChangeTotals = proposedChangeTotals,
            proposedContent = proposedContent,
            inputReview = inputReview?.bindCurrentRequest {
                check(validity?.let { runCatching(it).getOrDefault(false) } != false) { "Input review expired" }
                withOwnerAdmission(capturedOwnerAdmission) { current ->
                    synchronized(stateLock) {
                        check(current && !retired && _pending.value?.id == requestId &&
                            conts[requestId]?.isActive == true) { "Input review expired" }
                    }
                }
            },
        )
        kotlinx.coroutines.suspendCancellableCoroutine<ApprovalDecision> { cont ->
            cont.invokeOnCancellation {
                synchronized(stateLock) {
                    conts.remove(req.id)
                    validityChecks.remove(req.id)
                    ownerAdmissions.remove(req.id)
                    if (_pending.value?.id == req.id) _pending.value = null
                }
            }
            val deniedToken = withOwnerAdmission(capturedOwnerAdmission) { current ->
                synchronized(stateLock) {
                    // Fresh owner admission and pending publication share one finite transaction.
                    if (!cont.isActive) null
                    else if (!current || retired) cont.tryResume(ApprovalDecision.Denied)
                    else {
                        conts[req.id] = cont
                        if (validity != null) validityChecks[req.id] = validity
                        if (capturedOwnerAdmission != null) ownerAdmissions[req.id] = capturedOwnerAdmission
                        _pending.value = req
                        null
                    }
                }
            }
            if (deniedToken != null) cont.completeResume(deniedToken)
        }
    }

    fun resolve(approved: Boolean) = resolve(if (approved) ApprovalResolution.ALLOW_ONCE else ApprovalResolution.DENY)

    fun resolve(resolution: ApprovalResolution) {
        val (requestId, compatibleResolution) = synchronized(stateLock) {
            val req = _pending.value ?: return
            // Preserve historical compatibility for old callers, but project-analysis
            // consent has exactly one explicit action and must never widen a session choice.
            req.id to if (resolution == ApprovalResolution.ALLOW_SESSION && !req.canAllowForSession &&
                req.kind !in setOf("lsp_project_analysis", ToolchainInstallConsent.KIND)) {
                ApprovalResolution.ALLOW_ONCE
            } else resolution
        }
        resolve(requestId, compatibleResolution)
    }

    /** Returns false for stale, cancelled, already answered, or unavailable session choices. */
    @OptIn(InternalCoroutinesApi::class)
    fun resolve(requestId: Long, resolution: ApprovalResolution): Boolean {
        // Read callbacks under stateLock, then release it before invoking any owner or
        // browser check. Publication lock order is owner monitor -> stateLock only.
        val (validity, ownerAdmission) = synchronized(stateLock) {
            if (_pending.value?.id != requestId) return false
            validityChecks[requestId] to ownerAdmissions[requestId]
        }
        val sampledCurrent = validity?.let { runCatching(it).getOrDefault(false) } ?: true
        val resumed = withOwnerAdmission(ownerAdmission) { ownerCurrent ->
            synchronized(stateLock) {
                val req = _pending.value?.takeIf { it.id == requestId } ?: return@synchronized null
                val cont = conts[requestId]?.takeIf { it.isActive } ?: return@synchronized null
                if (resolution == ApprovalResolution.ALLOW_SESSION && !req.canAllowForSession) return@synchronized null
                val current = sampledCurrent && ownerCurrent && !retired
                val decision: ApprovalDecision =
                    if (resolution == ApprovalResolution.DENY || !current) ApprovalDecision.Denied else ApprovalDecision.Approved
                val grant = if (resolution == ApprovalResolution.ALLOW_SESSION && current) {
                    SessionGrant(req.approvalSessionId, req.permissionScope, req.sessionGrantAction, req.sessionGrantResource)
                } else null

                // Reserve continuation delivery before creating authority. Cancellation
                // revokes this exact grant object, never a newer equivalent session grant.
                val resumeToken = cont.tryResume(decision, null) { _, _, _ ->
                    if (grant != null) synchronized(stateLock) {
                        sessionGrants.removeAll { it === grant }
                    }
                } ?: return@synchronized null
                conts.remove(requestId)
                validityChecks.remove(requestId)
                ownerAdmissions.remove(requestId)
                if (grant != null) addSessionGrantLocked(grant)
                _pending.value = null
                cont to resumeToken
            }
        } ?: return false
        val (continuation, token) = resumed
        continuation.completeResume(token)
        return true
    }

    private fun <T> withOwnerAdmission(owner: ApprovalOwnerAdmission?, block: (Boolean) -> T): T =
        if (owner == null) block(true) else owner.withCurrent(block)

    fun hasSessionApproval(sessionId: String, permissionScope: String, action: String, resource: String): Boolean {
        val sid = normalizeApprovalSessionId(sessionId)
        val scope = permissionScope.takeIf { it.isNotBlank() }
            ?.let(PermissionPolicyDocument::normalizeAgentId).orEmpty()
        val grantAction = normalizeGrantAction(action)
        val grantResource = normalizeGrantResource(resource, grantAction)
        if (sid.isBlank() || scope.isBlank() || grantAction.isBlank() || grantResource.isBlank()) return false
        return synchronized(stateLock) { !retired && SessionGrant(sid, scope, grantAction, grantResource) in sessionGrants }
    }

     
    fun clearSessionApprovals(sessionId: String, permissionScope: String? = null) {
        val sid = normalizeApprovalSessionId(sessionId)
        if (sid.isBlank()) return
        val scope = permissionScope?.takeIf { it.isNotBlank() }?.let(PermissionPolicyDocument::normalizeAgentId)
        synchronized(stateLock) {
            sessionGrants.removeAll { it.sessionId == sid && (scope == null || it.permissionScope == scope) }
        }
    }

     
    fun clearAllSessionApprovals() = synchronized(stateLock) { sessionGrants.clear() }

    /** Capture this grant instance, so revocation followed by reapproval cannot revive old work. */
    internal fun sessionApprovalValidity(sessionId: String, permissionScope: String, action: String, resource: String): (() -> Boolean)? {
        val key = SessionGrant(normalizeApprovalSessionId(sessionId), PermissionPolicyDocument.normalizeAgentId(permissionScope),
            normalizeGrantAction(action), normalizeGrantResource(resource, action))
        val grant = synchronized(stateLock) { sessionGrants.firstOrNull { !retired && it == key } } ?: return null
        return { synchronized(stateLock) { !retired && sessionGrants.any { it === grant } } }
    }

    private fun addSessionGrantLocked(grant: SessionGrant) {
        sessionGrants.remove(grant)
        while (sessionGrants.size >= MAX_SESSION_GRANTS) {
            val oldest = sessionGrants.firstOrNull() ?: break
            sessionGrants.remove(oldest)
        }
        sessionGrants += grant
    }

    private fun normalizeApprovalSessionId(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) return ""
        if (normalized.length > 512 || '\u0000' in normalized || '\n' in normalized || '\r' in normalized) return ""
        return normalized
    }

    private fun normalizeGrantAction(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) return ""
        if (normalized.length > 128 || !normalized.matches(Regex("[A-Za-z0-9_.*?-]{1,128}"))) return ""
        return normalized
    }

    private fun normalizeGrantResource(value: String, action: String): String {
        if (action == "browser_local") return BrowserSessionApprovalScope.canonicalOrigin(value).orEmpty()
        val normalized = value.trim()
        if (normalized.isBlank()) return ""
        if (normalized.length > 4_096 || '\u0000' in normalized || '\n' in normalized || '\r' in normalized) return ""
        return normalized
    }

    private companion object { const val MAX_SESSION_GRANTS = 512 }
}

/** Finite owner transaction; the internal check must be pure, non-suspending and perform no IO.
 * The final method invokes publication exactly once, after checking the owner under its monitor.
 */
class ApprovalOwnerAdmission internal constructor(
    private val monitor: Any,
    private val isCurrent: () -> Boolean,
) {
    internal fun <T> withCurrent(publish: (Boolean) -> T): T = synchronized(monitor) { publish(isCurrent()) }
}
