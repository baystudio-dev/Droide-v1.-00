package com.baystudio.droide.core

import java.io.File

internal object LocalUbuntuNodeDistribution {
    const val VERSION = "Latest Node.js"

    const val AUTHORITY = "https://nodejs.org/download/release/latest-v22.x/SHASUMS256.txt"
    const val LIVE_AUTHORITY = "https://nodejs.org/download/release/latest/SHASUMS256.txt"
    fun resolve(text: String): VendorArtifactInstallRecipe {
        require(text.length <= 128_000)
        val entries = text.lineSequence().mapNotNull {
            Regex("^([0-9a-f]{64})\\s+(node-v([0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?)-linux-arm64\\.tar\\.gz)$").matchEntire(it.trim())
        }.toList()
        require(entries.size == 1) { "Official Node.js ARM64 checksum is missing or ambiguous" }
        val entry = entries.single()
        return recipe(entry.groupValues[3], entry.groupValues[1])
    }
    fun recipe(version: String, sha256: String): VendorArtifactInstallRecipe {
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        val folder = "node-v$version-linux-arm64"
        return VendorArtifactInstallRecipe(
            familyId = "runtime.node", version = version, command = "node", versionAuthority = if (version.startsWith("22.")) AUTHORITY else LIVE_AUTHORITY,
            releaseTag = "v$version", artifactFormat = DeclarativeGitHubArtifactFormat.TAR_GZ,
            artifactLayout = DeclarativeGitHubArtifactLayout.WHOLE_TREE, executableBasename = "node",
            treeEntryPoint = "$folder/bin/node", treeExecutablePaths = emptyList(),
            treeAuxCommands = mapOf("npm" to "$folder/bin/npm", "npx" to "$folder/bin/npx"),
            treeMaxFiles = 8_192, treeMaxUnpackedBytes = 512L * 1024 * 1024,
            treeLinkPolicy = DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE,
            treeModePolicy = DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE,
            assetName = "$folder.tar.gz", assetUrl = "https://nodejs.org/download/release/v$version/$folder.tar.gz",
            assetSha256 = sha256, assetMaxBytes = 256L * 1024 * 1024,
            provenanceUrl = "https://nodejs.org/en/download",
            runtimeCompatibility = DeclarativeRuntimeCompatibility(
                guestProfile = DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64,
                libc = DeclarativeLibcCompatibility.GLIBC,
                requiredGuestPackages = listOf("ca-certificates", "libstdc++6", "libgcc-s1"),
                evidenceUrl = "https://github.com/nodejs/node/blob/v$version/BUILDING.md",
            ),
        ).also { it.validate() }
    }
    fun isAuxScript(familyId: String, version: String, command: String, target: String, file: File): Boolean {
        if (familyId != "runtime.node" || !version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) || command !in setOf("npm", "npx") ||
            target != "node-v$version-linux-arm64/bin/$command" || !file.isFile || file.length() !in 1..100_000) return false
        return file.inputStream().bufferedReader().use { it.readLine() } == "#!/usr/bin/env node"
    }

    fun guestWrapper(version: String, command: String, guestTree: String): String {
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) && command in setOf("node", "npm", "npx"))
        val bin = "$guestTree/node-v$version-linux-arm64/bin"
        return buildString {
            append("#!/bin/sh\nset -eu\n")
            // npm defaults to the directory containing its interpreter. Keep user-installed global packages
            // outside the byte-pinned publisher SDK; explicit prefixes still win.
            if (command != "node") append("export npm_config_prefix=\"${'$'}{npm_config_prefix:-${'$'}{NPM_CONFIG_PREFIX:-/root/.local}}\"\n")
            append("exec ").append(q("$bin/node"))
            if (command != "node") append(' ').append(q("$bin/$command"))
            append(" \"${'$'}@\"\n")
        }
    }
    fun hostWrapper(launcher: String, guestWrapper: String) =
        "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(launcher)} /bin/sh ${q(guestWrapper)} \"${'$'}@\"\n"

    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
