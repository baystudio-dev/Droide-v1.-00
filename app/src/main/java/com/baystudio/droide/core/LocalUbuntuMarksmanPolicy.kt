package com.baystudio.droide.core

internal object LocalUbuntuMarksmanPolicy {
    const val FAMILY = "lsp.marksman"
    const val VERSION = "2026-02-08"
    const val SHA256 = "db8e124527f7f8048e3e6c91821b9c52ef173d92c01e47d221bf1337afd962fb"
    const val ARTIFACT_BYTES = 21_851_058L
    const val ASSET = "marksman-linux-arm64"
    const val REPOSITORY = "artempyanykh/marksman"

    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.version == VERSION && recipe.releaseTag == VERSION && recipe.assetName == ASSET &&
            recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES) {
            "Only the exact reviewed Marksman $VERSION ARM64 artifact is supported by this managed recipe; no version is substituted."
        }
    }
}
