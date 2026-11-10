package com.baystudio.droide.core

/** Exact managed generation. An identity is provenance, never a project-execution permit. */
data class CMakeManagedLaunchIdentity(
    val familyId: String,
    val version: String,
    val sourcePolicyRevision: String,
    val receiptSha256: String,
    val recipeSha256: String,
    val sealSha256: String,
    val selectedPackageIdentity: String,
    val discoveredExecutable: String,
    val launchExecutable: String,
    val bootstrapGuestAbsolute: String,
    val supervisorGuestAbsolute: String,
    val purelibGuestAbsolute: String,
    val pythonExecutableGuestAbsolute: String,
    val cmakeExecutableGuestAbsolute: String,
    val runtimeFingerprint: String,
    val bootstrapSha256: String,
    val supervisorSha256: String,
    val approvedToolBinGuestPaths: List<String> = emptyList(),
) {
    val consentIdentity: String = run {
        val value = listOf(familyId, version, sourcePolicyRevision, receiptSha256, recipeSha256, sealSha256,
            selectedPackageIdentity, discoveredExecutable, launchExecutable, bootstrapGuestAbsolute,
            supervisorGuestAbsolute, purelibGuestAbsolute, pythonExecutableGuestAbsolute, cmakeExecutableGuestAbsolute,
            runtimeFingerprint, bootstrapSha256, supervisorSha256, approvedToolBinGuestPaths.joinToString("\u0000"),
            LocalUbuntuCMakeLaunchPolicy.EDITOR_POLICY, LocalUbuntuCMakeLaunchPolicy.ENVIRONMENT_POLICY,
            "r96-cmake-owner-metadata-admission-v2", "5e8a1427a187a20b50db97e71bc02a6095f49b3e300a06738ed99ba14c4fe69b",
            "cmake-utf32-scalar-v1", "cmake-full-sync-pygls131-v1", "cpython312-unicode150-word-v1", "DISABLED")
        java.security.MessageDigest.getInstance("SHA-256").digest(value.joinToString("\u0001").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

data class CMakeDirectoryIdentity(
    val environmentIdentity: String,
    val device: Long,
    val inode: Long,
    val ownerUid: Long,
    val mode: Int,
)

data class CMakeOwnerRequest(val selection: CMakeAnalysisSelections.Selection, val ownerGeneration: Long)

/** Identity token: equal numeric fields cannot resurrect an earlier observation. */
class CMakeHostAdmissionObservation internal constructor(internal val owner: Any, val generation: Long)

enum class CMakePrivateCleanupStatus { NOT_STARTED, COMPLETE, QUARANTINED }
enum class CMakeTeardownStatus { COMPLETE, UNKNOWN, FAILED }

data class CMakeProcessBirth(val pid: Int, val startTicks: Long, val bootId: String, val namespace: String)

data class CMakeReadyProof(
    val nonce: String,
    val leaseId: String,
    val environmentIdentity: String,
    val ownerGeneration: Long,
    val outer: CMakeProcessBirth,
    val supervisor: CMakeProcessBirth,
    val leader: CMakeProcessBirth,
)

data class CMakeTeardownReceipt(
    val status: CMakeTeardownStatus,
    val leaseId: String,
    val ownerGeneration: Long,
    val supervisorNonce: String,
    val environmentIdentity: String,
    val directChildReaped: Boolean,
    val groupEmpty: Boolean,
    val outerChildReaped: Boolean,
    val transportClosed: Boolean,
    val forcedTermination: Boolean,
    val privateCleanupStatus: CMakePrivateCleanupStatus,
    val ready: CMakeReadyProof?,
    val preReapScans: Int = 0,
    val minimumSeparationMs: Long = 0,
    val postReapGroupObservation: String = "UNKNOWN",
    val reason: String? = null,
) {
    val releasesBuild: Boolean get() = status == CMakeTeardownStatus.COMPLETE &&
        directChildReaped && groupEmpty && outerChildReaped && transportClosed && ready != null &&
        preReapScans >= 2 && minimumSeparationMs >= 50 && postReapGroupObservation == "ESRCH" &&
        ready.leaseId == leaseId && ready.nonce == supervisorNonce &&
        ready.environmentIdentity == environmentIdentity && ready.ownerGeneration == ownerGeneration
}
