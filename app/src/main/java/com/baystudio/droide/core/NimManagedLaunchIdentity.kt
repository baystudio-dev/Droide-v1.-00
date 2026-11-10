package com.baystudio.droide.core

/** Immutable health-free receipt generation; full byte/closure proof occurs only at protected creation. */
data class NimManagedLaunchIdentity(
    val familyId: String, val version: String, val toolchainFamilyId: String, val toolchainVersion: String,
    val helperFamilyId: String, val helperVersion: String, val sourcePolicyRevision: String,
    val publisherArchiveSha256: String, val toolchainArchiveSha256: String, val helperArchiveSha256: String,
    val receiptSha256: String, val toolchainReceiptSha256: String, val helperReceiptSha256: String,
    val sealSha256: String, val toolchainSealSha256: String, val helperSealSha256: String,
    val selectedPackageIdentity: String, val discoveredExecutable: String, val launchExecutable: String,
    val serverExecutable: String, val compilerExecutable: String, val backendExecutable: String,
    val nimbleExecutable: String, val helperExecutable: String,
    val activationIdentity: String, val toolchainActivationIdentity: String, val helperActivationIdentity: String,
    val helperConfigSha256: String, val helperSourceBindingSha256: String, val closureSha256: String,
)
