package com.baystudio.droide.core

/** Managed original server proof. Selected external OTP/Python tools never inherit its provenance. */
data class ElpManagedLaunchIdentity(
    val familyId: String, val version: String, val sourcePolicyRevision: String,
    val receiptSha256: String, val sealSha256: String, val selectedPackageIdentity: String,
    val discoveredExecutable: String, val launchExecutable: String, val activationIdentity: String,
    val originalMemberSha256: String, val originalMemberBytes: Long, val helpersSha256: Map<String, String>,
) {
    val consentIdentity: String get() = LspElpLaunchPolicy.sha(toString().toByteArray(Charsets.UTF_8))
}
