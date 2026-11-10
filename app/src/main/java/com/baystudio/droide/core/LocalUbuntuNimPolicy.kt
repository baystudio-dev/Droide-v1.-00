package com.baystudio.droide.core

import java.io.File

/** Exact publisher input, never a latest resolver or a terminal toolchain selection. */
internal object LocalUbuntuNimPolicy {
    const val FAMILY = "lsp.nimlangserver"
    const val VERSION = "1.16.0"
    const val COMMAND = "nimlangserver"
    const val REPOSITORY = "nim-lang/langserver"
    const val TAG = "v1.16.0"
    const val ASSET = "nimlangserver-linux-arm64.tar.gz"
    const val ENTRY = "nimlangserver"
    const val SOURCE_COMMIT = "58168102d92d5742c39ab692aeb7bdbd7cf8df9a"
    const val SHA256 = "024a8b3e6d8d1f7c35694048704754d3d660b520f9a0331822f5a579a964925e"
    const val ARTIFACT_BYTES = 2_173_550L
    const val RELEASE_ID = 408_169_543L
    const val ASSET_ID = 625_674_464L
    const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/$RELEASE_ID"
    const val RELEASE_URL = "https://github.com/$REPOSITORY/releases/tag/$TAG"
    const val ASSET_URL = "https://github.com/$REPOSITORY/releases/download/$TAG/$ASSET"
    const val ROOT = "/opt/droide/packages/$FAMILY/$VERSION/arm64-v8a"
    const val EXECUTABLE = "$ROOT/payload/tree/$ENTRY"
    const val KEY = "$FAMILY@$VERSION"
    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.definition == LocalUbuntuVendorCatalog.find(FAMILY) && recipe.familyId == FAMILY &&
            recipe.version == VERSION && recipe.releaseTag == TAG && recipe.assetName == ASSET &&
            recipe.assetUrl == ASSET_URL && recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES &&
            recipe.provenanceUrl == RELEASE_URL) { "Only the exact reviewed Nim artifact $FAMILY@$VERSION is supported" }
    }
    fun releaseRecipe(definition: LocalUbuntuVendorDefinition, requested: String?, text: String): LocalUbuntuVendorRecipe {
        require(requested == VERSION && definition == LocalUbuntuVendorCatalog.find(FAMILY)) { "Choose exact $FAMILY@$VERSION; no version is substituted" }
        LocalUbuntuPowerShellRuntimePolicy.requireRelease(text, RELEASE_ID, API_URL, RELEASE_URL, TAG, ASSET_ID, ASSET, ARTIFACT_BYTES, ASSET_URL, SHA256)
        return LocalUbuntuVendorRecipe(definition, VERSION, TAG, ASSET, ASSET_URL, SHA256, ARTIFACT_BYTES, RELEASE_URL).also(::requireReviewed)
    }
    const val MAX_FILES = 1
    const val MAX_TREE_BYTES = 64L * 1024 * 1024
    val families = setOf(FAMILY, LocalUbuntuNimToolchainPolicy.FAMILY, LocalUbuntuNimHelperPolicy.FAMILY)
    fun isFamily(family: String) = family in families
    fun version(family: String): String = when (family) {
        FAMILY -> VERSION
        LocalUbuntuNimToolchainPolicy.FAMILY -> LocalUbuntuNimToolchainPolicy.VERSION
        LocalUbuntuNimHelperPolicy.FAMILY -> LocalUbuntuNimHelperPolicy.VERSION
        else -> error("Not a reviewed Nim family")
    }
    fun requireTree(entry: File, active: () -> Unit = {}): NimRuntimeClosure.Inventory =
        NimRuntimeClosure.inspectTree(entry.parentFile!!, setOf(ENTRY), setOf(ENTRY), MAX_FILES, MAX_TREE_BYTES, active = active).also {
            require(it.files == setOf(ENTRY)) { "Unexpected original Nim server archive layout" }
        }
}
