package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object LocalUbuntuAgentDistributions {
    const val ANTIGRAVITY = "plugin.antigravity"
    const val ANTIGRAVITY_VERSION = "1.5.3"
    const val MUSE_RUNTIME = "runtime.muse-code"
    const val MUSE_VERSION = "1.4.13-R6996.1"
    const val MUSE_KEY = "$MUSE_RUNTIME@$MUSE_VERSION"

    data class Release(
        val definition: LocalUbuntuVendorDefinition,
        val url: String,
        val bytes: Long,
        val provenance: String,
    ) {
        fun recipe() = LocalUbuntuVendorRecipe(definition, requireNotNull(definition.pinnedVersion),
            definition.pinnedVersion, definition.asset(definition.pinnedVersion), url,
            requireNotNull(definition.pinnedSha256), bytes, provenance)
    }

    val releases = listOf(
        Release(LocalUbuntuVendorDefinition(
            familyId = ANTIGRAVITY, command = "agy", assetTemplate = "cli_linux_arm64.tar.gz",
            format = LocalUbuntuVendorFormat.TAR_GZ, entryTemplate = "antigravity",
            admission = LocalUbuntuVendorAdmission.GLIBC_ARM64,
            packages = listOf("ca-certificates", "libstdc++6", "libgcc-s1"), maxFiles = 1,
            pinnedVersion = ANTIGRAVITY_VERSION,
            pinnedSha256 = "2d50060c7b1327995e5c81ec58829c91c2564ebb9fec165d3f9f1325ec188675"),
            "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.5.3-6604829099950080/linux-arm/cli_linux_arm64.tar.gz",
            69_843_447L, "https://antigravity.google/docs/cli/acp/"),
        Release(LocalUbuntuVendorDefinition(
            familyId = MUSE_RUNTIME, command = "muse-runtime", assetTemplate = "muse-aarch64-linux",
            format = LocalUbuntuVendorFormat.RAW, entryTemplate = "muse",
            admission = LocalUbuntuVendorAdmission.STATIC_ARM64, maxFiles = 1,
            maxAssetBytes = 384L * 1024 * 1024, maxUnpackedBytes = 384L * 1024 * 1024,
            pinnedVersion = MUSE_VERSION,
            pinnedSha256 = "c7f278b5f12862f5721c73433976256aa2a8abd433a12da0e484d30b08c024ff"),
            "https://lookaside.facebook.com/lookaside/muse/download/?channel=muse&version=$MUSE_VERSION&file=muse-aarch64-linux",
            360_257_768L, "https://dev.meta.ai/docs/muse-code"),
    )

    fun find(family: String): Release? = releases.singleOrNull { it.definition.familyId == family }

    const val ANTIGRAVITY_MANIFEST = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"
    const val MUSE_CHANNEL = "https://api.meta.ai/muse-code/channels/muse-stable"
    private const val ANTIGRAVITY_SHA512 = "c2fac818b3c1bb224079df03a714ccba99728b6a3a41d86d247ab2feb8582a43840c5032f1c2b75e72667623d5503e7ffda2712c4f37b6ee19f99c3fcfb25bae"

    fun museUrl(version: String, file: String): String {
        require(version.matches(Regex("[0-9]+[.][0-9]+[.][0-9]+-R[0-9]+(?:[.][0-9]+)?"))) { "Invalid Muse release version" }
        require(file in setOf("manifest.json", "muse-aarch64-linux"))
        return "https://lookaside.facebook.com/lookaside/muse/download/?channel=muse&version=$version&file=$file"
    }

    suspend fun resolve(family: String, requestedVersion: String?, fetch: suspend (String) -> String): LocalUbuntuVendorRecipe {
        val release = requireNotNull(find(family))

        if (requestedVersion == release.definition.pinnedVersion) return release.recipe()
        suspend fun objectAt(url: String): JsonObject {
            val text = fetch(url)
            require(text.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "Publisher manifest exceeds its bound" }
            return Json.parseToJsonElement(text) as? JsonObject ?: error("Invalid publisher manifest")
        }
        val recipe = when (family) {
            ANTIGRAVITY -> {
                val manifest = objectAt(ANTIGRAVITY_MANIFEST)
                val version = manifest.text("version")
                require(requestedVersion == null || requestedVersion == version) {
                    "Antigravity does not publish this version in its current manifest. Select latest or a previously reviewed release; no version was substituted."
                }
                val digest = manifest.text("sha512")
                if (version == ANTIGRAVITY_VERSION) require(digest == ANTIGRAVITY_SHA512) { "Known Antigravity publisher checksum changed" }
                LocalUbuntuVendorRecipe(release.definition, version, version, release.definition.asset(version),
                    manifest.text("url"), if (version == ANTIGRAVITY_VERSION) requireNotNull(release.definition.pinnedSha256) else "",
                    null, release.provenance, assetSha512 = digest)
            }
            MUSE_RUNTIME -> {
                val version = requestedVersion ?: objectAt(MUSE_CHANNEL).let { channel ->
                    require(channel.text("channel") == "muse-stable" && channel.text("state") == "public") { "Muse channel is not public" }
                    channel.text("version").also { require(channel.text("manifest_url") == museUrl(it, "manifest.json")) }
                }
                val manifest = objectAt(museUrl(version, "manifest.json"))
                require(manifest.text("version") == version && manifest.text("checksum_algorithm") == "sha256") { "Muse manifest identity changed" }
                val artifact = (manifest["artifacts"] as? JsonObject)?.get("aarch64_linux") as? JsonObject
                    ?: error("Muse Linux ARM64 artifact is unavailable")
                LocalUbuntuVendorRecipe(release.definition, version, version, release.definition.asset(version),
                    artifact.text("url"), artifact.text("checksum"),
                    artifact["size"]?.jsonPrimitive?.longOrNull ?: error("Muse artifact size is missing"), release.provenance)
            }
            else -> error("Unknown agent distribution")
        }
        recipe.validate()
        return recipe
    }

    fun requirePublisher(recipe: LocalUbuntuVendorRecipe) {
        val release = requireNotNull(find(recipe.familyId))
        require(recipe.provenanceUrl == release.provenance && recipe.releaseTag == recipe.version)
        if (recipe.version == release.definition.pinnedVersion && recipe.assetSize != null) {
            require(recipe.assetSize == release.bytes) { "Known publisher release size changed" }
        }
        when (recipe.familyId) {
            ANTIGRAVITY -> {
                require(recipe.version.matches(Regex("[0-9]+[.][0-9]+[.][0-9]+(?:[-+][A-Za-z0-9.-]+)?")))
                val prefix = "https://storage.googleapis.com/antigravity-public/antigravity-cli/"
                require(recipe.assetUrl.matches(Regex(Regex.escape(prefix + recipe.version) + "-[0-9]+/linux-arm/cli_linux_arm64[.]tar[.]gz"))) { "Antigravity artifact escaped its publisher release" }
                if (recipe.version == ANTIGRAVITY_VERSION) {
                    require(recipe.assetUrl == release.url)
                    require(recipe.assetSha512 == null || recipe.assetSha512 == ANTIGRAVITY_SHA512)
                } else require(recipe.assetSha512 != null) { "Antigravity publisher SHA-512 is missing" }
            }
            MUSE_RUNTIME -> require(recipe.assetUrl == museUrl(recipe.version, "muse-aarch64-linux") && recipe.assetSha512 == null) {
                "Muse artifact escaped its publisher release"
            }
        }
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull ?: error("Publisher field '$key' is missing")
}
