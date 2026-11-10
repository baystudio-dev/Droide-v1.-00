package com.baystudio.droide.core

/** Immutable discovery generation. Only protected creation proves the two payload trees. */
data class PowerShellManagedLaunchIdentity(
    val familyId: String, val version: String, val runtimeFamilyId: String, val runtimeVersion: String,
    val sourcePolicyRevision: String, val publisherArchiveSha256: String, val runtimeArchiveSha256: String,
    val receiptSha256: String, val runtimeReceiptSha256: String, val sealSha256: String, val runtimeSealSha256: String,
    val selectedPackageIdentity: String, val discoveredExecutable: String, val launchExecutable: String,
    val runtimeExecutable: String, val activationIdentity: String, val runtimeActivationIdentity: String,
)
