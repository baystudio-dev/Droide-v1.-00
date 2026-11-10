package com.baystudio.droide.core

/** Health-free generation identity for the exact three-artifact C# route. */
data class CSharpManagedLaunchIdentity(
    val familyId: String, val version: String,
    val sdkFamilyId: String, val sdkVersion: String,
    val helperFamilyId: String, val helperVersion: String,
    val sourcePolicyRevision: String,
    val receiptSha256: String, val sdkReceiptSha256: String, val helperReceiptSha256: String,
    val sealSha256: String, val sdkSealSha256: String, val helperSealSha256: String,
    val selectedPackageIdentity: String,
    val discoveredExecutable: String, val launchExecutable: String, val serverExecutable: String,
    val sdkExecutable: String, val sdkRoot: String, val helperExecutable: String,
    val activationIdentity: String, val sdkActivationIdentity: String, val helperActivationIdentity: String,
    val closureSha256: String,
) {
    /** Stable across owner allocations; never includes private directories or random tokens. */
    val consentIdentity: String get() = LspCSharpLaunchPolicy.sha(toString().toByteArray(Charsets.UTF_8))
}
