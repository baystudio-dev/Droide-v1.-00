package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Original publisher identity and proposed fail-closed layout; no original runtime acceptance. */
internal object LocalUbuntuSqruffPolicy {
    const val FAMILY = "lsp.sqruff"
    const val COMMAND = "sqruff"
    const val SOURCE_POLICY_REVISION = "sqruff-0.41.0-immutable-lf-scalar-format-v1/sqruff-managed-native-project-lsp-v1/vendor-v1"
    const val VERSION = "0.41.0"
    const val REPOSITORY = "quarylabs/sqruff"
    const val SOURCE_COMMIT = "aa4c5730d697794ffaf72a4c187d64522e5820c7"
    const val ASSET = "sqruff-linux-aarch64-musl.tar.gz"
    const val SHA256 = "e548e491be0966ee6511c14393803498df58f3d03b9c8426df25130e52a51947"
    const val ARTIFACT_BYTES = 8_582_177L
    const val RELEASE_ID = 404_863_604L
    const val ASSET_ID = 615_913_480L
    const val METADATA_URL = "https://api.github.com/repos/$REPOSITORY/releases/tags/v$VERSION"
    const val RELEASE_API_URL = "https://api.github.com/repos/$REPOSITORY/releases/$RELEASE_ID"
    const val ASSET_URL = "https://github.com/$REPOSITORY/releases/download/v$VERSION/$ASSET"
    const val PROVENANCE_URL = "https://github.com/$REPOSITORY/releases/tag/v$VERSION"
    const val MAX_TREE_BYTES = 64L * 1024 * 1024

    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.definition == LocalUbuntuVendorCatalog.find(FAMILY) && recipe.version == VERSION &&
            recipe.releaseTag == "v$VERSION" && recipe.assetName == ASSET && recipe.assetUrl == ASSET_URL &&
            recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES && recipe.provenanceUrl == PROVENANCE_URL) {
            "Only the exact reviewed Sqruff $VERSION original ARM64 archive is supported; no version is substituted."
        }
    }

    fun publisherRecipe(definition: LocalUbuntuVendorDefinition, text: String): LocalUbuntuVendorRecipe {
        require(text.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "Sqruff publisher metadata exceeds its bound" }
        require(definition == LocalUbuntuVendorCatalog.find(FAMILY)) { "Unreviewed Sqruff definition" }
        val release = Json.parseToJsonElement(text) as? JsonObject ?: error("Sqruff release is not an object")
        fun JsonObject.string(key: String): String {
            val value = this[key] as? JsonPrimitive
            require(value != null && value.isString) { "Sqruff publisher field '$key' is not a string" }
            return value.content
        }
        fun JsonObject.scalar(key: String, expected: String) {
            val value = this[key] as? JsonPrimitive
            require(value != null && !value.isString && value.content == expected) { "Sqruff publisher field '$key' changed" }
        }
        release.scalar("id", RELEASE_ID.toString())
        release.scalar("draft", "false"); release.scalar("prerelease", "false")
        require(release.string("tag_name") == "v$VERSION" && release.string("html_url") == PROVENANCE_URL &&
            release.string("url") == RELEASE_API_URL) { "Sqruff exact release identity changed" }
        val assets = release["assets"] as? JsonArray ?: error("Sqruff release assets are missing")
        val candidates = assets.map {
            val asset = it as? JsonObject ?: error("Sqruff release asset is not an object")
            asset.string("name") to asset
        }.filter { it.first == ASSET }
        val asset = candidates.singleOrNull()?.second ?: error("Sqruff ARM64 asset is missing or ambiguous")
        asset.scalar("id", ASSET_ID.toString()); asset.scalar("size", ARTIFACT_BYTES.toString())
        require(asset.string("state") == "uploaded" && asset.string("browser_download_url") == ASSET_URL &&
            asset.string("digest") == "sha256:$SHA256") { "Sqruff publisher asset identity changed or lacks its exact digest" }
        return LocalUbuntuVendorRecipe(definition, VERSION, "v$VERSION", ASSET, ASSET_URL, SHA256,
            ARTIFACT_BYTES, PROVENANCE_URL).also { it.validate() }
    }

    /** Exact projected inventory only; shared TAR/GZIP trailing-envelope behavior is unchanged. */
    fun requireTree(entry: File) {
        val root = requireNotNull(entry.parentFile)
        require(root.isDirectory && !PathSecurity.isSymbolicLink(root) && entry.name == COMMAND)
        val files = requireNotNull(root.listFiles())
        require(files.size == 1 && files.single().name == COMMAND) { "Sqruff requires exactly the single original executable" }
        require(entry.isFile && !PathSecurity.isSymbolicLink(entry) && entry.length() in 1..MAX_TREE_BYTES) {
            "Sqruff payload must be a bounded regular file"
        }
    }
}
