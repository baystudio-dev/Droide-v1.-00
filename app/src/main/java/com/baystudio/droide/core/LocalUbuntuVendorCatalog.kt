package com.baystudio.droide.core

/** Local Ubuntu overlays preserve the original remote catalog and select original publisher bytes. */
enum class LocalUbuntuVendorFormat { RAW, GZIP_RAW, TAR_GZ, TAR_XZ, ZIP }
enum class LocalUbuntuVendorAdmission { GLIBC_ARM64, STATIC_ARM64, INTERPRETED }

data class LocalUbuntuVendorDefinition(
    val familyId: String,
    val command: String,
    val repository: String? = null,
    val tagPrefix: String = "v",
    val assetTemplate: String,
    val format: LocalUbuntuVendorFormat,
    val entryTemplate: String,
    val admission: LocalUbuntuVendorAdmission,
    val interpreter: List<String> = emptyList(),
    val packages: List<String> = listOf("ca-certificates"),
    val healthArgs: List<String> = listOf("--version"),
    val auxiliaryCommands: Map<String, String> = emptyMap(),
    val maxFiles: Int = 4096,
    val maxUnpackedBytes: Long = 512L * 1024L * 1024L,
    val maxAssetBytes: Long = 256L * 1024L * 1024L,
    val pinnedVersion: String? = null,
    val pinnedSha256: String? = null,
    val lsp: Boolean = false,
) {
    // Zig changed target ordering after 0.14.0. Keep the same deterministic
    // layout for resolution, extraction and receipt revalidation.
    private fun templateForVersion(template: String, version: String): String {
        if (familyId != "toolchain.zig") return template
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        val parts = version.split('.').map(String::toInt)
        val legacy = parts[0] == 0 && (parts[1] < 14 || (parts[1] == 14 && parts[2] == 0))
        return if (legacy) template.replace("zig-aarch64-linux-", "zig-linux-aarch64-") else template
    }
    fun entry(version: String) = templateForVersion(entryTemplate, version).replace("{version}", version)
    fun asset(version: String) = templateForVersion(assetTemplate, version).replace("{version}", version)
    // Existing historical pins attest known releases without capping publisher versions.
    // Publisher selection is separate from integration compatibility and historical artifact pins.
    // Hex uses its package manager for other versions because its index signs SHA-512.
    val supportsPublisherVersionSelection get() = familyId != LocalUbuntuElpPolicy.FAMILY && !LocalUbuntuGraphqlPolicy.isFamily(familyId) && !LocalUbuntuTomlPolicy.isFamily(familyId) && !LocalUbuntuVuePolicy.isFamily(familyId) && !LocalUbuntuNimPolicy.isFamily(familyId) && familyId !in setOf("package.hex", LocalUbuntuPowerShellRuntimePolicy.FAMILY, LocalUbuntuPowerShellEditorServicesPolicy.FAMILY, LocalUbuntuSqruffPolicy.FAMILY, LocalUbuntuClojurePolicy.FAMILY, LocalUbuntuMakePolicy.FAMILY, LocalUbuntuLemMinxPolicy.FAMILY)
    val offeredVersion get() = if (familyId == LocalUbuntuElpPolicy.FAMILY) LocalUbuntuElpPolicy.VERSION
        else if (familyId == LocalUbuntuGraphqlNodePolicy.FAMILY) LocalUbuntuGraphqlNodePolicy.VERSION
        else if (familyId == LocalUbuntuTomlNodePolicy.FAMILY) LocalUbuntuTomlNodePolicy.VERSION
        else if (familyId == LocalUbuntuVueHelperPolicy.FAMILY) LocalUbuntuVueHelperPolicy.VERSION
        else if (LocalUbuntuNimPolicy.isFamily(familyId)) LocalUbuntuNimPolicy.version(familyId)
        else if (familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY) LocalUbuntuPowerShellRuntimePolicy.VERSION
        else if (familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY) LocalUbuntuPowerShellEditorServicesPolicy.VERSION
        else if (familyId == LocalUbuntuSqruffPolicy.FAMILY) LocalUbuntuSqruffPolicy.VERSION
        else if (familyId == LocalUbuntuClojurePolicy.FAMILY) LocalUbuntuClojurePolicy.VERSION
        else if (familyId == LocalUbuntuMakePolicy.FAMILY) LocalUbuntuMakePolicy.VERSION
        else if (familyId == LocalUbuntuLemMinxPolicy.FAMILY) LocalUbuntuLemMinxPolicy.VERSION
        else LocalUbuntuVendorPolicy.VERSION_CHOICE
}

data class LocalUbuntuVendorRecipe @JvmOverloads constructor(
    val definition: LocalUbuntuVendorDefinition,
    val version: String,
    val releaseTag: String,
    val assetName: String,
    val assetUrl: String,
    val assetSha256: String,
    val assetSize: Long?,
    val provenanceUrl: String,
    // Some publishers attest SHA-512 only. SHA-256 is materialized after that download verifies.
    val assetSha512: String? = null,
) {
    val familyId get() = definition.familyId
    val command get() = definition.command
    val healthArgs get() = definition.healthArgs
    val entryPoint get() = definition.entry(version)
    val requiredPackages get() = if (familyId == LocalUbuntuJsDebugPolicy.FAMILY &&
        !LocalUbuntuJsDebugPolicy.requiresLegacyNode(this)) definition.packages + "nodejs" else definition.packages
    fun artifactDescriptor(id: String): TrustedArtifactDescriptor {
        validate()
        return if (assetSha512 != null) TrustedSha512ArtifactSpec(id, assetUrl, assetSha512, assetName, definition.maxAssetBytes, assetSize)
            else TrustedArtifactSpec(id, assetUrl, assetSha256, assetName, definition.maxAssetBytes, assetSize)
    }

    internal fun withVerifiedSha256(digest: String): LocalUbuntuVendorRecipe {
        require(digest.matches(Regex("[0-9a-f]{64}")) && (assetSha256.isEmpty() || assetSha256 == digest)) {
            "Verified artifact differs from its recorded SHA-256"
        }
        return copy(assetSha256 = digest).also { it.validate() }
    }

    fun validate() {
        require(LocalUbuntuVendorCatalog.find(familyId) == definition) { "Unreviewed local Ubuntu vendor definition" }
        LocalUbuntuAgentDistributions.find(familyId)?.let { LocalUbuntuAgentDistributions.requirePublisher(this) }
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}")) && version !in setOf(".", ".."))
        if (familyId == LocalUbuntuElpPolicy.FAMILY) LocalUbuntuElpPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuNimPolicy.FAMILY) LocalUbuntuNimPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuNimToolchainPolicy.FAMILY) LocalUbuntuNimToolchainPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuNimHelperPolicy.FAMILY) LocalUbuntuNimHelperPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuGraphqlNodePolicy.FAMILY) LocalUbuntuGraphqlNodePolicy.requireReviewed(this)
        if (familyId == LocalUbuntuTomlNodePolicy.FAMILY) LocalUbuntuTomlNodePolicy.requireReviewed(this)
        if (familyId == LocalUbuntuVueHelperPolicy.FAMILY) LocalUbuntuVueHelperPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY) LocalUbuntuPowerShellRuntimePolicy.requireReviewed(this)
        if (familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY) LocalUbuntuPowerShellEditorServicesPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuSqruffPolicy.FAMILY) LocalUbuntuSqruffPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuMarksmanPolicy.FAMILY && version == LocalUbuntuMarksmanPolicy.VERSION) LocalUbuntuMarksmanPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuTexlabPolicy.FAMILY && version == LocalUbuntuTexlabPolicy.VERSION) LocalUbuntuTexlabPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuBufPolicy.FAMILY && version == LocalUbuntuBufPolicy.VERSION) LocalUbuntuBufPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuMakePolicy.FAMILY) LocalUbuntuMakePolicy.requireReviewed(this)
        if (familyId == LocalUbuntuClojurePolicy.FAMILY) LocalUbuntuClojurePolicy.requireReviewed(this)
        if (familyId == LocalUbuntuTerraformPolicy.FAMILY && version == LocalUbuntuTerraformPolicy.VERSION) LocalUbuntuTerraformPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuZlsPolicy.FAMILY) require(LocalUbuntuZlsPolicy.supportsVersion(version)) { "ZLS requires the Zig 0.16 compatible release line" }
        if (familyId == LocalUbuntuZlsPolicy.FAMILY && version == LocalUbuntuZlsPolicy.VERSION) LocalUbuntuZlsPolicy.requireReviewed(this)
        if (familyId == LocalUbuntuLemMinxPolicy.FAMILY) LocalUbuntuLemMinxPolicy.requireReviewed(this)
        if (version == definition.pinnedVersion) definition.pinnedSha256?.let {
            require(assetSha256 == it) { "Known publisher release digest changed" }
        }
        val assetMatches = when (familyId) {
            "toolchain.solidity" -> assetName.matches(Regex("solc-linux-arm64-v${Regex.escape(version)}\\+commit\\.[0-9a-f]{8,40}"))
            "lsp.jdtls" -> assetName.matches(Regex("jdt-language-server-${Regex.escape(version)}-[0-9]{12,14}\\.tar\\.gz"))
            else -> assetName == definition.asset(version)
        }
        require(assetMatches) { "Publisher asset differs from reviewed Ubuntu asset layout" }
        NetworkSecurity.validatePublicHttpsUrl(assetUrl)
        require(assetSha512 == null || (familyId == LocalUbuntuAgentDistributions.ANTIGRAVITY &&
            assetSha512.matches(Regex("[0-9a-f]{128}"))))
        require(assetSha256.matches(Regex("[0-9a-f]{64}")) || (assetSha256.isEmpty() && assetSha512 != null))
        require(assetSize == null || assetSize in 1L..definition.maxAssetBytes)
        if (familyId == LocalUbuntuJsDebugPolicy.FAMILY && version == LocalUbuntuJsDebugPolicy.VERSION) require(assetSize == LocalUbuntuJsDebugPolicy.ARTIFACT_BYTES) {
            "Reviewed js-debug release size changed"
        }
        val expectedUrl = when (familyId) {
            LocalUbuntuAgentDistributions.ANTIGRAVITY, LocalUbuntuAgentDistributions.MUSE_RUNTIME -> assetUrl // Independently admitted above.
            LocalUbuntuElpPolicy.FAMILY -> LocalUbuntuElpPolicy.ASSET_URL
            LocalUbuntuGraphqlNodePolicy.FAMILY -> LocalUbuntuGraphqlNodePolicy.ASSET_URL
            LocalUbuntuTomlNodePolicy.FAMILY -> LocalUbuntuTomlNodePolicy.ASSET_URL
            LocalUbuntuNimPolicy.FAMILY -> LocalUbuntuNimPolicy.ASSET_URL
            LocalUbuntuNimToolchainPolicy.FAMILY -> LocalUbuntuNimToolchainPolicy.ASSET_URL
            LocalUbuntuNimHelperPolicy.FAMILY -> LocalUbuntuNimHelperPolicy.ASSET_URL
            LocalUbuntuVueHelperPolicy.FAMILY -> LocalUbuntuVueHelperPolicy.ASSET_URL
            LocalUbuntuTerraformPolicy.FAMILY -> "https://releases.hashicorp.com/terraform-ls/$version/$assetName"
            LocalUbuntuZlsPolicy.FAMILY -> "https://builds.zigtools.org/$assetName"
            LocalUbuntuLemMinxPolicy.FAMILY -> LocalUbuntuLemMinxPolicy.ASSET_URL
            "toolchain.zig" -> "https://ziglang.org/download/$version/$assetName"
            "toolchain.solidity" -> "https://binaries.soliditylang.org/linux-arm64/$assetName"
            "cli.kubectl" -> "https://dl.k8s.io/release/v$version/bin/linux/arm64/kubectl"
            "cli.helm" -> "https://get.helm.sh/$assetName"
            "build.gradle" -> "https://services.gradle.org/distributions/$assetName"
            "lsp.jdtls" -> "https://download.eclipse.org/jdtls/milestones/$version/$assetName"
            "package.hex" -> "https://repo.hex.pm/installs/1.14.0/$assetName"
            else -> "https://github.com/${definition.repository}/releases/download/${definition.tagPrefix}$version/$assetName"
        }
        if (definition.repository != null && familyId !in setOf(LocalUbuntuNimToolchainPolicy.FAMILY,LocalUbuntuElpPolicy.FAMILY)) require(releaseTag == definition.tagPrefix + version) { "Publisher tag differs from the reviewed version" }
        require(assetUrl == expectedUrl) { "Publisher URL escaped the reviewed release" }
        when (familyId) {
            LocalUbuntuTerraformPolicy.FAMILY -> require(releaseTag == "v$version" &&
                provenanceUrl == "https://releases.hashicorp.com/terraform-ls/$version/terraform-ls_${version}_SHA256SUMS")
            LocalUbuntuZlsPolicy.FAMILY -> require(releaseTag == version && provenanceUrl == LocalUbuntuZlsPolicy.INDEX_URL)
        }

        if (familyId == "toolchain.solidity" && version == definition.pinnedVersion) require(assetSha256 == "717142f4275804e75c3d0bafd264db669da9cf411550c7c29cc68f634d17a9da")
        require(entryPoint.split('/').all { it.matches(Regex("[A-Za-z0-9._+@-]{1,180}")) && it !in setOf(".", "..") })
        require(provenanceUrl.startsWith("https://"))
        require((definition.admission == LocalUbuntuVendorAdmission.INTERPRETED) == definition.interpreter.isNotEmpty())
        require(definition.interpreter.firstOrNull() in setOf(null, "/usr/bin/java", "/usr/bin/php", "/bin/bash", "/usr/bin/python3", "/usr/bin/elixir") ||
            (familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY && definition.interpreter == listOf(LocalUbuntuPowerShellEditorServicesPolicy.RUNTIME_GUEST)) ||
            (familyId == LocalUbuntuJsDebugPolicy.FAMILY && definition.interpreter == listOf(LocalUbuntuJsDebugPolicy.NODE_GUEST)) ||
            (familyId == LocalUbuntuLemMinxPolicy.FAMILY && definition.interpreter == listOf(LocalUbuntuLemMinxPolicy.JAVA, "-jar"))) {
            "Unreviewed publisher interpreter"
        }
    }
}

object LocalUbuntuVendorCatalog {
    private fun native(family: String, command: String, repo: String, asset: String, format: LocalUbuntuVendorFormat,
        entry: String, static: Boolean = false, prefix: String = "v", health: List<String> = listOf("--version"),
        auxiliary: Map<String, String> = emptyMap(), lsp: Boolean = false) = LocalUbuntuVendorDefinition(
        family, command, repo, prefix, asset, format, entry,
        if (static) LocalUbuntuVendorAdmission.STATIC_ARM64 else LocalUbuntuVendorAdmission.GLIBC_ARM64,
        packages = (if (static) listOf("ca-certificates") else listOf("ca-certificates", "libstdc++6", "libgcc-s1")) + if (lsp) listOf("nodejs") else emptyList(),
        healthArgs = health, auxiliaryCommands = auxiliary, lsp = lsp)
    val all: List<LocalUbuntuVendorDefinition> = LocalUbuntuAgentDistributions.releases.map { it.definition } + listOf(
        LocalUbuntuVendorDefinition(LocalUbuntuElpPolicy.FAMILY,LocalUbuntuElpPolicy.COMMAND,LocalUbuntuElpPolicy.REPOSITORY,"",LocalUbuntuElpPolicy.ASSET,
            LocalUbuntuVendorFormat.TAR_GZ,"elp",LocalUbuntuVendorAdmission.GLIBC_ARM64,packages=emptyList(),healthArgs=emptyList(),
            maxFiles=1,maxUnpackedBytes=LocalUbuntuElpPolicy.MAX_MEMBER_BYTES,maxAssetBytes=LocalUbuntuElpPolicy.ARTIFACT_BYTES,
            pinnedVersion=LocalUbuntuElpPolicy.VERSION,pinnedSha256=LocalUbuntuElpPolicy.SHA256,lsp=true),
        LocalUbuntuVendorDefinition(LocalUbuntuNimPolicy.FAMILY, LocalUbuntuNimPolicy.COMMAND, LocalUbuntuNimPolicy.REPOSITORY, "v", LocalUbuntuNimPolicy.ASSET,
            LocalUbuntuVendorFormat.TAR_GZ, LocalUbuntuNimPolicy.ENTRY, LocalUbuntuVendorAdmission.GLIBC_ARM64,
            packages = listOf("ca-certificates", "libc6", "libstdc++6", "libgcc-s1", "xz-utils", "libssl3t64", "libpcre3"), healthArgs = listOf("--health"),
            maxFiles = LocalUbuntuNimPolicy.MAX_FILES, maxUnpackedBytes = LocalUbuntuNimPolicy.MAX_TREE_BYTES, maxAssetBytes = LocalUbuntuNimPolicy.ARTIFACT_BYTES,
            pinnedVersion = LocalUbuntuNimPolicy.VERSION, pinnedSha256 = LocalUbuntuNimPolicy.SHA256, lsp = true),
        LocalUbuntuVendorDefinition(LocalUbuntuNimToolchainPolicy.FAMILY, LocalUbuntuNimToolchainPolicy.COMMAND, LocalUbuntuNimToolchainPolicy.REPOSITORY, "v", LocalUbuntuNimToolchainPolicy.ASSET,
            LocalUbuntuVendorFormat.TAR_XZ, LocalUbuntuNimToolchainPolicy.ENTRY, LocalUbuntuVendorAdmission.GLIBC_ARM64,
            packages = listOf("ca-certificates", "libc6", "libstdc++6", "libgcc-s1", "xz-utils", "libssl3t64", "libpcre3"), healthArgs = listOf("--health"),
            maxFiles = LocalUbuntuNimToolchainPolicy.MAX_FILES, maxUnpackedBytes = LocalUbuntuNimToolchainPolicy.MAX_TREE_BYTES, maxAssetBytes = LocalUbuntuNimToolchainPolicy.ARTIFACT_BYTES,
            pinnedVersion = LocalUbuntuNimToolchainPolicy.VERSION, pinnedSha256 = LocalUbuntuNimToolchainPolicy.SHA256, lsp = false),
        LocalUbuntuVendorDefinition(LocalUbuntuNimHelperPolicy.FAMILY, LocalUbuntuNimHelperPolicy.COMMAND, null, "v", LocalUbuntuNimHelperPolicy.ASSET,
            LocalUbuntuVendorFormat.TAR_GZ, LocalUbuntuNimHelperPolicy.ENTRY, LocalUbuntuVendorAdmission.GLIBC_ARM64,
            packages = listOf("ca-certificates", "libc6", "libstdc++6", "libgcc-s1", "xz-utils", "libssl3t64", "libpcre3"), healthArgs = listOf("--health"),
            maxFiles = LocalUbuntuNimHelperPolicy.MAX_FILES, maxUnpackedBytes = LocalUbuntuNimHelperPolicy.MAX_TREE_BYTES, maxAssetBytes = LocalUbuntuNimHelperPolicy.ARTIFACT_BYTES,
            pinnedVersion = LocalUbuntuNimHelperPolicy.VERSION, pinnedSha256 = LocalUbuntuNimHelperPolicy.SHA256, lsp = false),
        LocalUbuntuVendorDefinition(LocalUbuntuGraphqlNodePolicy.FAMILY, LocalUbuntuGraphqlNodePolicy.COMMAND, null, "v", LocalUbuntuGraphqlNodePolicy.ASSET,
            LocalUbuntuVendorFormat.TAR_GZ, LocalUbuntuGraphqlNodePolicy.ENTRY, LocalUbuntuVendorAdmission.GLIBC_ARM64,
            packages=listOf("ca-certificates","libc6","libstdc++6","libgcc-s1"),healthArgs=emptyList(),
            maxFiles=LocalUbuntuGraphqlNodePolicy.MAX_FILES,maxUnpackedBytes=LocalUbuntuGraphqlNodePolicy.MAX_TREE_BYTES,maxAssetBytes=LocalUbuntuGraphqlNodePolicy.ARTIFACT_BYTES,
            pinnedVersion=LocalUbuntuGraphqlNodePolicy.VERSION,pinnedSha256=LocalUbuntuGraphqlNodePolicy.SHA256,lsp=false),
        LocalUbuntuVendorDefinition(LocalUbuntuTomlNodePolicy.FAMILY, LocalUbuntuTomlNodePolicy.COMMAND, null, "v", LocalUbuntuTomlNodePolicy.ASSET,
            LocalUbuntuVendorFormat.TAR_GZ, LocalUbuntuTomlNodePolicy.ENTRY, LocalUbuntuVendorAdmission.GLIBC_ARM64,
            packages=listOf("ca-certificates","libc6","libstdc++6","libgcc-s1"),healthArgs=emptyList(),
            maxFiles=LocalUbuntuTomlNodePolicy.MAX_FILES,maxUnpackedBytes=LocalUbuntuTomlNodePolicy.MAX_TREE_BYTES,maxAssetBytes=LocalUbuntuTomlNodePolicy.ARTIFACT_BYTES,
            pinnedVersion=LocalUbuntuTomlNodePolicy.VERSION,pinnedSha256=LocalUbuntuTomlNodePolicy.SHA256,lsp=false),
        LocalUbuntuVendorDefinition(LocalUbuntuVueHelperPolicy.FAMILY, LocalUbuntuVueHelperPolicy.COMMAND, null, "v", LocalUbuntuVueHelperPolicy.ASSET,
            LocalUbuntuVendorFormat.TAR_GZ, LocalUbuntuVueHelperPolicy.ENTRY, LocalUbuntuVendorAdmission.GLIBC_ARM64,
            packages = listOf("ca-certificates", "libc6", "libstdc++6", "libgcc-s1", "xz-utils", "libssl3t64", "libpcre3"), healthArgs = listOf("--health"),
            maxFiles = LocalUbuntuVueHelperPolicy.MAX_FILES, maxUnpackedBytes = LocalUbuntuVueHelperPolicy.MAX_TREE_BYTES, maxAssetBytes = LocalUbuntuVueHelperPolicy.ARTIFACT_BYTES,
            pinnedVersion = LocalUbuntuVueHelperPolicy.VERSION, pinnedSha256 = LocalUbuntuVueHelperPolicy.SHA256, lsp = false),
        LocalUbuntuVendorDefinition(LocalUbuntuPowerShellRuntimePolicy.FAMILY, "pwsh", LocalUbuntuPowerShellRuntimePolicy.REPOSITORY,
            "v", LocalUbuntuPowerShellRuntimePolicy.ASSET, LocalUbuntuVendorFormat.TAR_GZ, "pwsh", LocalUbuntuVendorAdmission.GLIBC_ARM64,
            packages = listOf("ca-certificates", "libc6", "libgcc-s1", "libgssapi-krb5-2", "libstdc++6", "zlib1g", "libicu74", "libssl3t64", "nodejs"),
            healthArgs = listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-Version"), maxFiles = 4096,
            maxUnpackedBytes = LocalUbuntuPowerShellRuntimePolicy.MAX_TREE_BYTES, maxAssetBytes = LocalUbuntuPowerShellRuntimePolicy.ARTIFACT_BYTES,
            pinnedVersion = LocalUbuntuPowerShellRuntimePolicy.VERSION, pinnedSha256 = LocalUbuntuPowerShellRuntimePolicy.SHA256),
        LocalUbuntuVendorDefinition(LocalUbuntuPowerShellEditorServicesPolicy.FAMILY, LocalUbuntuPowerShellEditorServicesPolicy.COMMAND,
            LocalUbuntuPowerShellEditorServicesPolicy.REPOSITORY, "v", LocalUbuntuPowerShellEditorServicesPolicy.ASSET, LocalUbuntuVendorFormat.ZIP,
            LocalUbuntuPowerShellEditorServicesPolicy.ENTRY, LocalUbuntuVendorAdmission.INTERPRETED,
            interpreter = listOf(LocalUbuntuPowerShellEditorServicesPolicy.RUNTIME_GUEST),
            packages = listOf("ca-certificates", "libc6", "libgcc-s1", "libgssapi-krb5-2", "libstdc++6", "zlib1g", "libicu74", "libssl3t64", "nodejs"),
            healthArgs = emptyList(), maxFiles = 4096, maxUnpackedBytes = LocalUbuntuPowerShellEditorServicesPolicy.MAX_TREE_BYTES,
            maxAssetBytes = LocalUbuntuPowerShellEditorServicesPolicy.ARTIFACT_BYTES, pinnedVersion = LocalUbuntuPowerShellEditorServicesPolicy.VERSION,
            pinnedSha256 = LocalUbuntuPowerShellEditorServicesPolicy.SHA256, lsp = true),
        LocalUbuntuVendorDefinition(LocalUbuntuSqruffPolicy.FAMILY, LocalUbuntuSqruffPolicy.COMMAND,
            LocalUbuntuSqruffPolicy.REPOSITORY, "v", LocalUbuntuSqruffPolicy.ASSET, LocalUbuntuVendorFormat.TAR_GZ,
            LocalUbuntuSqruffPolicy.COMMAND, LocalUbuntuVendorAdmission.STATIC_ARM64,
            packages = listOf("ca-certificates", "nodejs"), healthArgs = emptyList(), maxFiles = 1,
            maxUnpackedBytes = LocalUbuntuSqruffPolicy.MAX_TREE_BYTES, maxAssetBytes = LocalUbuntuSqruffPolicy.ARTIFACT_BYTES,
            pinnedVersion = LocalUbuntuSqruffPolicy.VERSION, pinnedSha256 = LocalUbuntuSqruffPolicy.SHA256, lsp = true),
        LocalUbuntuVendorDefinition(LocalUbuntuClojurePolicy.FAMILY, LocalUbuntuClojurePolicy.COMMAND,
            LocalUbuntuClojurePolicy.REPOSITORY, "", LocalUbuntuClojurePolicy.ASSET, LocalUbuntuVendorFormat.ZIP,
            LocalUbuntuClojurePolicy.COMMAND, LocalUbuntuVendorAdmission.GLIBC_ARM64,
            packages = listOf("ca-certificates", "libc6", "nodejs"), healthArgs = emptyList(),
            maxFiles = 3, maxUnpackedBytes = LocalUbuntuClojurePolicy.MAX_TREE_BYTES,
            maxAssetBytes = LocalUbuntuClojurePolicy.ARTIFACT_BYTES, pinnedVersion = LocalUbuntuClojurePolicy.VERSION,
            pinnedSha256 = LocalUbuntuClojurePolicy.SHA256, lsp = true),
        LocalUbuntuVendorDefinition(LocalUbuntuMakePolicy.FAMILY, "make-ls", LocalUbuntuMakePolicy.REPOSITORY,
            "v", LocalUbuntuMakePolicy.ASSET, LocalUbuntuVendorFormat.TAR_GZ, "make-ls", LocalUbuntuVendorAdmission.STATIC_ARM64,
            packages = listOf("ca-certificates", "nodejs"), healthArgs = emptyList(),
            maxFiles = 16, maxUnpackedBytes = 64L * 1024 * 1024, maxAssetBytes = LocalUbuntuMakePolicy.ARTIFACT_BYTES,
            pinnedVersion = LocalUbuntuMakePolicy.VERSION, pinnedSha256 = LocalUbuntuMakePolicy.SHA256, lsp = true),
        LocalUbuntuVendorDefinition(LocalUbuntuLemMinxPolicy.FAMILY, LocalUbuntuLemMinxPolicy.COMMAND,
            assetTemplate = LocalUbuntuLemMinxPolicy.ASSET, format = LocalUbuntuVendorFormat.RAW,
            entryTemplate = LocalUbuntuLemMinxPolicy.ASSET, admission = LocalUbuntuVendorAdmission.INTERPRETED,
            interpreter = listOf(LocalUbuntuLemMinxPolicy.JAVA, "-jar"),
            packages = listOf("ca-certificates", "openjdk-21-jdk-headless", "nodejs"), healthArgs = emptyList(),
            maxFiles = 1, maxUnpackedBytes = LocalUbuntuLemMinxPolicy.ARTIFACT_BYTES,
            maxAssetBytes = LocalUbuntuLemMinxPolicy.ARTIFACT_BYTES, pinnedVersion = LocalUbuntuLemMinxPolicy.VERSION,
            pinnedSha256 = LocalUbuntuLemMinxPolicy.SHA256, lsp = true),
        LocalUbuntuVendorDefinition(LocalUbuntuZlsPolicy.FAMILY, "zls", LocalUbuntuZlsPolicy.REPOSITORY,
            "", "zls-aarch64-linux-{version}.tar.xz", LocalUbuntuVendorFormat.TAR_XZ, "zls", LocalUbuntuVendorAdmission.STATIC_ARM64,
            packages = listOf("ca-certificates", "xz-utils", "nodejs"), healthArgs = listOf("--version"),
            maxFiles = 32, maxUnpackedBytes = 64L * 1024 * 1024, maxAssetBytes = 128L * 1024 * 1024,
            pinnedVersion = LocalUbuntuZlsPolicy.VERSION, pinnedSha256 = LocalUbuntuZlsPolicy.SHA256, lsp = true),
        LocalUbuntuVendorDefinition(LocalUbuntuBufPolicy.FAMILY, "buf", LocalUbuntuBufPolicy.REPOSITORY,
            "v", LocalUbuntuBufPolicy.ASSET, LocalUbuntuVendorFormat.TAR_GZ, "buf/bin/buf", LocalUbuntuVendorAdmission.STATIC_ARM64,
            packages = listOf("ca-certificates", "nodejs"), healthArgs = emptyList(),
            maxFiles = 512, maxUnpackedBytes = 128L * 1024 * 1024, maxAssetBytes = 128L * 1024 * 1024,
            pinnedVersion = LocalUbuntuBufPolicy.VERSION, pinnedSha256 = LocalUbuntuBufPolicy.SHA256, lsp = true),
        LocalUbuntuVendorDefinition(LocalUbuntuTerraformPolicy.FAMILY, "terraform-ls", "hashicorp/terraform-ls",
            "v", "terraform-ls_{version}_linux_arm64.zip", LocalUbuntuVendorFormat.ZIP, "terraform-ls", LocalUbuntuVendorAdmission.STATIC_ARM64,
            packages = listOf("ca-certificates", "nodejs"), healthArgs = emptyList(),
            maxFiles = 2, maxUnpackedBytes = 128L * 1024 * 1024, maxAssetBytes = 128L * 1024 * 1024,
            pinnedVersion = LocalUbuntuTerraformPolicy.VERSION, pinnedSha256 = LocalUbuntuTerraformPolicy.SHA256, lsp = true),
        LocalUbuntuVendorDefinition(LocalUbuntuTexlabPolicy.FAMILY, "texlab", LocalUbuntuTexlabPolicy.REPOSITORY,
            "v", LocalUbuntuTexlabPolicy.ASSET, LocalUbuntuVendorFormat.TAR_GZ, "texlab", LocalUbuntuVendorAdmission.GLIBC_ARM64,
            packages = listOf("ca-certificates", "libc6", "libgcc-s1", "nodejs"),
            healthArgs = emptyList(), maxFiles = 1, maxUnpackedBytes = 64L * 1024 * 1024,
            maxAssetBytes = 128L * 1024 * 1024, pinnedVersion = LocalUbuntuTexlabPolicy.VERSION,
            pinnedSha256 = LocalUbuntuTexlabPolicy.SHA256, lsp = true),
        LocalUbuntuVendorDefinition(LocalUbuntuMarksmanPolicy.FAMILY, "marksman", LocalUbuntuMarksmanPolicy.REPOSITORY,
            "", LocalUbuntuMarksmanPolicy.ASSET, LocalUbuntuVendorFormat.RAW, "marksman", LocalUbuntuVendorAdmission.GLIBC_ARM64,
            packages = listOf("ca-certificates", "libc6", "libgcc-s1", "libgssapi-krb5-2", "libicu74", "libssl3t64",
                "libstdc++6", "tzdata", "zlib1g", "nodejs"),
            healthArgs = emptyList(), maxFiles = 1, maxUnpackedBytes = 256L * 1024 * 1024,
            maxAssetBytes = 128L * 1024 * 1024, pinnedVersion = LocalUbuntuMarksmanPolicy.VERSION,
            pinnedSha256 = LocalUbuntuMarksmanPolicy.SHA256, lsp = true),
        native("cli.mise", "mise", "jdx/mise", "mise-v{version}-linux-arm64.tar.xz",
            LocalUbuntuVendorFormat.TAR_XZ, "mise/bin/mise").let { it.copy(packages = it.packages + "xz-utils") },
        native("plugin.opencode", "opencode", "anomalyco/opencode", "opencode-linux-arm64.tar.gz",
            LocalUbuntuVendorFormat.TAR_GZ, "opencode"),
        LocalUbuntuVendorDefinition(LocalUbuntuJsDebugPolicy.FAMILY, LocalUbuntuJsDebugPolicy.COMMAND,
            "microsoft/vscode-js-debug", "v", "js-debug-dap-v{version}.tar.gz", LocalUbuntuVendorFormat.TAR_GZ,
            "js-debug/src/dapDebugServer.js", LocalUbuntuVendorAdmission.INTERPRETED,
            interpreter = listOf(LocalUbuntuJsDebugPolicy.NODE_GUEST), packages = listOf("ca-certificates"),
            healthArgs = listOf("--help"), maxFiles = 128, maxUnpackedBytes = 8L * 1024 * 1024,
            maxAssetBytes = 4L * 1024 * 1024, pinnedVersion = LocalUbuntuJsDebugPolicy.VERSION,
            pinnedSha256 = LocalUbuntuJsDebugPolicy.ARTIFACT_SHA256),
        LocalUbuntuVendorDefinition("toolchain.zig", "zig", assetTemplate = "zig-aarch64-linux-{version}.tar.xz", format = LocalUbuntuVendorFormat.TAR_XZ,
            entryTemplate = "zig-aarch64-linux-{version}/zig", admission = LocalUbuntuVendorAdmission.STATIC_ARM64,
            packages = listOf("ca-certificates", "xz-utils"), healthArgs = listOf("version"), maxFiles = 50000, maxUnpackedBytes = 1024L * 1024 * 1024),
        LocalUbuntuVendorDefinition("toolchain.solidity", "solc", assetTemplate = "solc-linux-arm64-v{version}+commit.f401782d", format = LocalUbuntuVendorFormat.RAW,
            entryTemplate = "solc", admission = LocalUbuntuVendorAdmission.STATIC_ARM64, pinnedVersion = "0.8.37"),
        native("package.bun", "bun", "oven-sh/bun", "bun-linux-aarch64.zip", LocalUbuntuVendorFormat.ZIP, "bun-linux-aarch64/bun", prefix = "bun-v"),
        native("package.uv", "uv", "astral-sh/uv", "uv-aarch64-unknown-linux-gnu.tar.gz", LocalUbuntuVendorFormat.TAR_GZ, "uv-aarch64-unknown-linux-gnu/uv", prefix = "",
            auxiliary = mapOf("uvx" to "uv-aarch64-unknown-linux-gnu/uvx")),
        LocalUbuntuVendorDefinition("package.hex", "hex", assetTemplate = "hex-{version}.ez", format = LocalUbuntuVendorFormat.ZIP,
            entryTemplate = "hex-{version}/ebin/Elixir.Mix.Tasks.Hex.Info.beam", admission = LocalUbuntuVendorAdmission.INTERPRETED,
            interpreter = listOf("/usr/bin/elixir"), packages = listOf("ca-certificates", "elixir", "erlang-ssl"),
            healthArgs = listOf("hex.info"), pinnedVersion = "2.5.1",
            pinnedSha256 = "4278d8eaeeb274cdfebfc64dde42866f35b7c12dd0aee01e136056d4e7f1a2a1"),
        LocalUbuntuVendorDefinition("cli.kubectl", "kubectl", assetTemplate = "kubectl", format = LocalUbuntuVendorFormat.RAW, entryTemplate = "kubectl",
            admission = LocalUbuntuVendorAdmission.STATIC_ARM64, healthArgs = listOf("version", "--client=true")),
        LocalUbuntuVendorDefinition("cli.helm", "helm", "helm/helm", assetTemplate = "helm-v{version}-linux-arm64.tar.gz", format = LocalUbuntuVendorFormat.TAR_GZ,
            entryTemplate = "linux-arm64/helm", admission = LocalUbuntuVendorAdmission.STATIC_ARM64, healthArgs = listOf("version", "--short")),
        native("quality.golangci-lint", "golangci-lint", "golangci/golangci-lint", "golangci-lint-{version}-linux-arm64.tar.gz", LocalUbuntuVendorFormat.TAR_GZ, "golangci-lint-{version}-linux-arm64/golangci-lint", static = true),
        native("quality.stylua", "stylua", "JohnnyMorganz/StyLua", "stylua-linux-aarch64.zip", LocalUbuntuVendorFormat.ZIP, "stylua"),
        native("quality.tflint", "tflint", "terraform-linters/tflint", "tflint_linux_arm64.zip", LocalUbuntuVendorFormat.ZIP, "tflint", static = true),
        native("quality.hadolint", "hadolint", "hadolint/hadolint", "hadolint-linux-arm64", LocalUbuntuVendorFormat.RAW, "hadolint", static = true),
        native("quality.protolint", "protolint", "yoheimuta/protolint", "protolint_{version}_linux_arm64.tar.gz", LocalUbuntuVendorFormat.TAR_GZ, "protolint", static = true),
        native("quality.ruff", "ruff", "astral-sh/ruff", "ruff-aarch64-unknown-linux-gnu.tar.gz", LocalUbuntuVendorFormat.TAR_GZ, "ruff-aarch64-unknown-linux-gnu/ruff", prefix = ""),
        LocalUbuntuVendorDefinition("lsp.kotlin", "kotlin-language-server", "fwcd/kotlin-language-server", "", "server.zip",
            LocalUbuntuVendorFormat.ZIP, "server/bin/kotlin-language-server", LocalUbuntuVendorAdmission.INTERPRETED,
            interpreter = listOf("/bin/bash"), packages = listOf("ca-certificates", "openjdk-21-jdk-headless", "nodejs"),
            pinnedVersion = "1.3.13", pinnedSha256 = "4fe7d71d087b307c7869036171bd9d8c6a4284cd7c25b89098b0a24eb2d9b6d2", lsp = true),
        LocalUbuntuVendorDefinition("lsp.jdtls", "jdtls", assetTemplate = "jdt-language-server-1.61.0-202609031315.tar.gz",
            format = LocalUbuntuVendorFormat.TAR_GZ, entryTemplate = "bin/jdtls", admission = LocalUbuntuVendorAdmission.INTERPRETED,
            interpreter = listOf("/usr/bin/python3"), packages = listOf("ca-certificates", "openjdk-21-jdk-headless", "python3", "nodejs"),
            pinnedVersion = "1.61.0", pinnedSha256 = "338e7e73d61836651ba2453919a0d34fa763eb4e7c03342092309bffb8934c64", lsp = true),
        native("lsp.rust-analyzer", "rust-analyzer", "rust-lang/rust-analyzer", "rust-analyzer-aarch64-unknown-linux-gnu.gz", LocalUbuntuVendorFormat.GZIP_RAW, "rust-analyzer", prefix = "", lsp = true),
        native("lsp.lua-language-server", "lua-language-server", "LuaLS/lua-language-server", "lua-language-server-{version}-linux-arm64.tar.gz", LocalUbuntuVendorFormat.TAR_GZ, "bin/lua-language-server", prefix = "", lsp = true),
        LocalUbuntuVendorDefinition("quality.ktlint", "ktlint", "ktlint/ktlint", "", "ktlint", LocalUbuntuVendorFormat.RAW, "ktlint",
            LocalUbuntuVendorAdmission.INTERPRETED, interpreter = listOf("/usr/bin/java", "-jar"), packages = listOf("ca-certificates", "openjdk-21-jdk-headless")),
        LocalUbuntuVendorDefinition("quality.phpstan", "phpstan", "phpstan/phpstan", "", "phpstan.phar", LocalUbuntuVendorFormat.RAW, "phpstan.phar",
            LocalUbuntuVendorAdmission.INTERPRETED, interpreter = listOf("/usr/bin/php"), packages = listOf("ca-certificates", "php-cli", "php-mbstring", "php-xml")),
        LocalUbuntuVendorDefinition("quality.php-cs-fixer", "php-cs-fixer", "PHP-CS-Fixer/PHP-CS-Fixer", "v", "php-cs-fixer.phar", LocalUbuntuVendorFormat.RAW, "php-cs-fixer.phar",
            LocalUbuntuVendorAdmission.INTERPRETED, interpreter = listOf("/usr/bin/php"), packages = listOf("ca-certificates", "php-cli", "php-mbstring", "php-xml")),
        LocalUbuntuVendorDefinition("build.gradle", "gradle", assetTemplate = "gradle-{version}-bin.zip", format = LocalUbuntuVendorFormat.ZIP,
            entryTemplate = "gradle-{version}/bin/gradle", admission = LocalUbuntuVendorAdmission.INTERPRETED,
            interpreter = listOf("/bin/bash"), packages = listOf("ca-certificates", "openjdk-21-jdk-headless")),
        LocalUbuntuVendorDefinition("package.sbt", "sbt", "sbt/sbt", assetTemplate = "sbt-{version}.zip", format = LocalUbuntuVendorFormat.ZIP,
            entryTemplate = "sbt/bin/sbt", admission = LocalUbuntuVendorAdmission.INTERPRETED,
            interpreter = listOf("/bin/bash"), packages = listOf("ca-certificates", "openjdk-21-jdk-headless"), healthArgs = listOf("--script-version")),
        native("quality.swiftformat", "swiftformat", "nicklockwood/SwiftFormat", "swiftformat_linux_aarch64.zip", LocalUbuntuVendorFormat.ZIP, "swiftformat_linux_aarch64", static = true, prefix = ""),
        native("quality.swiftlint", "swiftlint", "realm/SwiftLint", "swiftlint_linux_arm64.zip", LocalUbuntuVendorFormat.ZIP, "swiftlint", prefix = ""),
    )
    fun find(familyId: String): LocalUbuntuVendorDefinition? = all.singleOrNull { it.familyId == familyId }
}
