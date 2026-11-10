package com.baystudio.droide.core

/** Per-owner, byte-verified managed provenance. Not native execution acceptance or a same-UID sandbox. */
data class SqruffManagedLaunchIdentity(
    val familyId: String,
    val version: String,
    val sourcePolicyRevision: String,
    val publisherArchiveSha256: String,
    val installedExecutableSha256: String,
    val receiptSha256: String,
    val recipeSha256: String,
    val sealSha256: String,
    val selectedPackageIdentity: String,
    val discoveredExecutable: String,
    val launchExecutable: String,
    val nativeExecutable: String,
)
