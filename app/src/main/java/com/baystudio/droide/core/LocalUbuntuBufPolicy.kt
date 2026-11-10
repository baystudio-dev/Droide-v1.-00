package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest

/** Exact original publisher archive; metadata/source review is not target acceptance. */
internal object LocalUbuntuBufPolicy {
    const val FAMILY = "lsp.buf"
    const val VERSION = "1.74.0"
    const val REPOSITORY = "bufbuild/buf"
    const val ASSET = "buf-Linux-aarch64.tar.gz"
    const val SHA256 = "54a5d5500992deb3239bfee61c9f0ef7646b6f6b231d2c255fdfc16eeb50564c"
    const val ARTIFACT_BYTES = 29_377_099L
    const val LICENSE_SHA256 = "995b27237c0d8ef8c970d36da9e81f1472790ae18f3d7d5a966781b53d78f242"

    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.version == VERSION && recipe.releaseTag == "v$VERSION" && recipe.assetName == ASSET &&
            recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES &&
            recipe.provenanceUrl == "https://github.com/$REPOSITORY/releases/tag/v$VERSION") {
            "Only the exact reviewed Buf $VERSION ARM64 archive is supported; no version is substituted."
        }
    }

    /** Retain the complete projected archive, including notices; expose only buf/bin/buf. */
    @JvmOverloads
    fun requireTree(entry: File, version: String = VERSION) {
        val bin = requireNotNull(entry.parentFile)
        val root = requireNotNull(bin.parentFile)
        require(entry.name == "buf" && bin.name == "bin" && root.name == "buf" &&
            bin.isDirectory && root.isDirectory && !PathSecurity.isSymbolicLink(bin) && !PathSecurity.isSymbolicLink(root)) {
            "Buf archive differs from the reviewed entry layout"
        }
        val license = File(root, "LICENSE")
        require(license.isFile && !PathSecurity.isSymbolicLink(license) && license.length() in 1..1_000_000L) {
            "Buf archive must retain its exact publisher license"
        }
        if (version != VERSION) return
        require(license.length() == 11_357L)
        val digest = MessageDigest.getInstance("SHA-256").digest(license.readBytes()).joinToString("") { "%02x".format(it) }
        require(digest == LICENSE_SHA256) { "Buf publisher license differs from reviewed source" }
    }
}
