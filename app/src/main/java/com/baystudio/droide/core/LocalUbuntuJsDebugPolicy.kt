package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest

/** Original publisher bundles are sealed; historical entry hashes only attest that release. */
internal object LocalUbuntuJsDebugPolicy {
    const val FAMILY = "debug.js-debug"
    const val COMMAND = "js-debug-adapter"
    const val VERSION = "1.140.0"
    const val NODE_VERSION = "22.23.3"
    const val ARTIFACT_SHA256 = "27dab92937ec1ab35821ae955aac867544fe06a1b6307229049f2d789af10968"
    const val ENTRY_SHA256 = "6b1e6058901a1d733a0687ef4a4840665f694f45e8863d34fe64a9e62c68c844"
    const val ARTIFACT_BYTES = 1_249_709L
    const val NODE_GUEST = "/opt/droide/packages/runtime.node/$NODE_VERSION/arm64-v8a/payload/bin/node"
    const val NODE_KEY = "runtime.node@$NODE_VERSION"

    /** Keep old receipts/wrapper bytes valid, while newer releases use the user's Node PATH. */
    fun requiresLegacyNode(recipe: LocalUbuntuVendorRecipe) = recipe.familyId == FAMILY && recipe.version == VERSION &&
        recipe.assetSha256 == ARTIFACT_SHA256

    fun requireNode(record: ManagedPackageRecord, appRoot: String) {
        require(record.familyId == "runtime.node" && record.version == NODE_VERSION &&
            record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a" &&
            record.metadata[PackageBackendContract.METADATA_KEY] == PackageBackendId.LOCAL_APP.name &&
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE &&
            record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] == DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name &&
            record.metadata[LocalManagedPackageMetadata.LIBC_KEY] == DeclarativeLibcCompatibility.GLIBC.name &&
            record.artifactSha256?.matches(Regex("[0-9a-f]{64}")) == true) {
            "js-debug requires the exact owned official Ubuntu ARM64 Node.js dependency"
        }
        val expected = File(appRoot, "packages/runtime.node/$NODE_VERSION/arm64-v8a").absolutePath
        require(record.installRoot == expected && record.commands["node"] == "$expected/payload/host-bin/node") {
            "js-debug Node dependency has an unexpected package destination"
        }
    }

    fun requireEntry(recipe: LocalUbuntuVendorRecipe, file: File) {
        recipe.validate()
        require(recipe.familyId == FAMILY &&
            recipe.entryPoint == "js-debug/src/dapDebugServer.js" && file.isFile && !PathSecurity.isSymbolicLink(file) &&
            file.length() in 1L..8L * 1024 * 1024) { "Invalid official js-debug entry point" }
        // New releases have their own publisher archive digest and full-tree seal. Do not
        // compare new JavaScript bytes with the old release's hash or pin its Node runtime.
        if (!requiresLegacyNode(recipe)) return
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        require(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == ENTRY_SHA256) {
            "Official js-debug JavaScript entry changed"
        }
    }
}
