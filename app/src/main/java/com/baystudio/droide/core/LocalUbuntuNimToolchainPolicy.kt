package com.baystudio.droide.core

import java.io.File

/** Exact publisher input, never a latest resolver or a terminal toolchain selection. */
internal object LocalUbuntuNimToolchainPolicy {
    const val FAMILY = "runtime.nim-lsp"
    const val VERSION = "2.2.12"
    const val COMMAND = "droide-nim-lsp-toolchain"
    const val REPOSITORY = "nim-lang/nightlies"
    const val TAG = "2026-09-08-version-2-2-8e8fbf60693418dc95bb0d762fd660231d08a583"
    const val ASSET = "nim-2.2.12-linux_arm64.tar.xz"
    const val ENTRY = "nim-2.2.12/bin/nim"
    const val SOURCE_COMMIT = "8e8fbf60693418dc95bb0d762fd660231d08a583"
    const val SHA256 = "3dc04191af72a8310cf1c8ffb6f772cc3716fc5f264bc7c453ab4aecb4085221"
    const val ARTIFACT_BYTES = 17_826_880L
    const val RELEASE_ID = 384_441_901L
    const val ASSET_ID = 549_860_064L
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
    const val MAX_FILES = 50_000
    const val MAX_TREE_BYTES = 1024L * 1024 * 1024
    const val TOP = "nim-2.2.12"
    const val BACKEND = "$ROOT/payload/tree/$TOP/bin/nimsuggest"
    const val NIMBLE = "$ROOT/payload/tree/$TOP/bin/nimble"
    const val NIMBLE_VERSION = "0.24.1"
    const val NIMBLE_SOURCE = "a399f502dec7ffcd905c1cf54b13274ad990bada"
    val executablePaths = setOf("$TOP/bin/nim", "$TOP/bin/nimsuggest", "$TOP/bin/nimble")
    fun requireTree(entry: File, active: () -> Unit = {}): NimRuntimeClosure.Inventory {
        require(entry.name == "nim" && entry.parentFile?.name == "bin" && entry.parentFile?.parentFile?.name == TOP)
        val root = entry.parentFile!!.parentFile!!.parentFile!!
        return NimRuntimeClosure.inspectTree(root, executablePaths,
            executablePaths + setOf("$TOP/nim.nimble", "$TOP/copying.txt", "$TOP/config/nim.cfg", "$TOP/lib/system.nim"),
            MAX_FILES, MAX_TREE_BYTES, top = TOP, active = active)
    }
}
