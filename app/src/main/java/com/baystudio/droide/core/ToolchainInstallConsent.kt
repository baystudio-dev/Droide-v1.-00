package com.baystudio.droide.core

import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A build/install permission is not a receipt for a particular revision of legal terms. */
internal object ToolchainInstallConsent {
    const val KIND = "android_sdk_license"
    const val MAX_DETAIL = 120_000

    suspend fun install(
        permissions: PermissionEngine,
        approvals: ApprovalManager,
        resource: String,
        summary: String,
        preview: String,
        license: AndroidSdkLicense?,
        licenseAccepted: Boolean,
        scope: String,
        provenance: AgentRequestProvenance,
        requireCurrent: () -> Unit = {},
        acceptLicense: suspend () -> Unit,
        install: suspend () -> String,
    ): String {
        suspend fun current() { currentCoroutineContext().ensureActive(); requireCurrent() }
        fun permitted() = permissions.toolAllowed("toolchain") &&
            permissions.decide("toolchain_install", resource, scope) != PermEffect.DENY
        current()
        if (!permitted()) return "DENIED (policy): toolchain installation"
        val needsConsent = license != null && !licenseAccepted
        val approved = if (needsConsent) {
            requireNotNull(license)
            require(license.text.length in 1..100_000 && sha256(license.text) == license.sha256) { "Invalid SDK license revision" }
            val detail = "$preview\n\nAccept the following Android SDK license revision and install the package above. " +
                "Acceptance is saved for this exact revision and reused by later Android SDK installs. " +
                "Choose Reject to stop this installation.\n\nSHA-256: ${license.sha256}\n\n${license.text}"
            require(detail.length <= MAX_DETAIL) { "License review exceeds display capacity" }
            approvals.requestApproval(KIND, "Accept Android SDK license and $summary", detail,
                action = "toolchain_install", resource = resource, permissionScope = scope, provenance = provenance,
                approvalSessionId = "", sessionGrantAction = "", sessionGrantResource = "") == ApprovalDecision.Approved
        } else PermissionApprovalGate.approved(permissions, approvals, "toolchain_install", resource, scope,
            "toolchain_install", summary, preview, provenance)
        current()
        if (!approved) return "DENIED by user: toolchain installation; do not retry without new user direction."
        if (!permitted()) return "DENIED (policy changed): toolchain installation"
        if (needsConsent) acceptLicense()
        current()
        if (!permitted()) return "DENIED (policy changed): toolchain installation"
        return install()
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
