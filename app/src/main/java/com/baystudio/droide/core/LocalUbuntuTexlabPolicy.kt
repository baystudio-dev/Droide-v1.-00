package com.baystudio.droide.core

internal object LocalUbuntuTexlabPolicy {
    const val FAMILY = "lsp.texlab"
    const val VERSION = "5.26.0"
    const val SHA256 = "a85cdfcd22454b8d8550f4b0f0620c45ab51760f302fac7a12bc18a890f70f8c"
    const val ARTIFACT_BYTES = 10_383_190L
    const val ASSET = "texlab-aarch64-linux.tar.gz"
    const val REPOSITORY = "latex-lsp/texlab"

    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.version == VERSION && recipe.releaseTag == "v$VERSION" && recipe.assetName == ASSET &&
            recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES) {
            "Only the exact reviewed Texlab $VERSION ARM64 artifact is supported by this managed recipe; no version is substituted."
        }
    }
}
