package com.baystudio.droide.core

/** The community ACP adapter must launch the separately owned Meta executable, never a PATH guess. */
internal object LocalUbuntuMusePolicy {
    const val FAMILY = "plugin.muse-code"
    const val VERSION = "0.11.0"
    const val PACKAGE = "@bex-co/muse-code-acp"
    const val INTEGRITY = "sha512-X+N1ULi/BaCjeu4bOJkQnak3Ra/Yn9m8Mn8gshzCeH4dtGjXLag7slpw8B3O1TsTcOoZzu1hYDpxxvkgAv2LEg=="
    const val PROVENANCE = "https://github.com/bex-co/muse-code-acp/tree/v0.11.0"

    fun isRuntimeKey(key: String): Boolean = key.startsWith("${LocalUbuntuAgentDistributions.MUSE_RUNTIME}@") &&
        key.substringAfter('@').matches(Regex("[0-9]+[.][0-9]+[.][0-9]+-R[0-9]+(?:[.][0-9]+)?"))

    fun requireRuntime(record: ManagedPackageRecord, appRoot: String): String {
        val release = requireNotNull(LocalUbuntuAgentDistributions.find(LocalUbuntuAgentDistributions.MUSE_RUNTIME))
        val expected = LocalPackageInstallJournal.paths(appRoot, release.definition.familyId,
            record.version, LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).final.absolutePath
        require(record.familyId == release.definition.familyId && isRuntimeKey("${record.familyId}@${record.version}") &&
            record.artifactSha256?.matches(Regex("[0-9a-f]{64}")) == true && record.installRoot == expected &&
            record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a") {
            "Muse Code requires its versioned locally owned Meta runtime; reinstall Muse Code from Extensions"
        }
        if (record.version == release.definition.pinnedVersion) require(record.artifactSha256 == release.definition.pinnedSha256) {
            "Known Muse runtime checksum changed"
        }
        return requireNotNull(LocalLinuxPackageEnvironment.guestPath("$expected/payload/tree/muse", "$appRoot/packages/"))
    }

    fun environment(executable: String): String {
        require(executable.startsWith("/opt/droide/packages/") && executable.endsWith("/payload/tree/muse") &&
            executable.none { it.code < 32 || it.code == 127 })
        // SDK sessions support approvals and resumption. Do not silently select exec/yolo fallbacks.
        return "export MUSE_CODE_EXECUTABLE=${shellQuote(executable)}\nexport MUSE_CODE_ACP_BACKEND=sdk\n"
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
