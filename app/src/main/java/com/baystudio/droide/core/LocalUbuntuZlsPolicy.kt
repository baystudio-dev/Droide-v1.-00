package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Publisher metadata and pinned build-source layout, not archive or target-execution attestation. */
internal object LocalUbuntuZlsPolicy {
    const val FAMILY = "lsp.zls"
    const val VERSION = "0.16.0"
    const val REPOSITORY = "zigtools/zls"
    const val ASSET = "zls-aarch64-linux-0.16.0.tar.xz"
    const val ASSET_URL = "https://builds.zigtools.org/" + ASSET
    const val INDEX_URL = "https://builds.zigtools.org/index.json"
    const val SHA256 = "430cd293d201eb70ae2519dbc96c854bf8791b8df7fc9392e8d2dc9680a2bed7"
    const val ARTIFACT_BYTES = 3_814_984L
    const val LICENSE_SHA256 = "e0ef11f84a052c6a36c838a920dd0d852222b78e34c95bfc45ac7867de1a13ee"
    const val LICENSE_BYTES = 1_068L
    const val README_SHA256 = "0e6189a24fc48500540bb879f445d1eb5af1000b837b6f33cf78ff6c6a5f52ed"
    const val README_BYTES = 2_846L

    fun supportsVersion(version: String): Boolean = version.matches(Regex("0[.]16[.][0-9]{1,6}"))

    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.version == VERSION && recipe.releaseTag == VERSION && recipe.assetName == ASSET &&
            recipe.assetUrl == ASSET_URL && recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES &&
            recipe.provenanceUrl == INDEX_URL) {
            "Only the exact reviewed ZLS $VERSION ARM64 archive is supported; no version is substituted."
        }
    }

    /** The original index uses version/target objects and string-valued tarball, shasum and size. */
    fun publisherRecipe(definition: LocalUbuntuVendorDefinition, text: String): LocalUbuntuVendorRecipe {
        require(text.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "ZLS publisher index exceeds its bound" }
        val index = Json.parseToJsonElement(text) as? JsonObject ?: error("ZLS publisher index is not an object")
        val version = index[VERSION] as? JsonObject ?: error("Reviewed ZLS release is missing")
        val target = version["aarch64-linux"] as? JsonObject ?: error("Reviewed ZLS ARM64 Linux target is missing")
        fun string(key: String): String {
            val value = target[key] as? JsonPrimitive
            require(value != null && value.isString) { "ZLS publisher field '$key' is not a string" }
            return value.content
        }
        val url = string("tarball")
        val sha = string("shasum")
        val size = string("size")
        require(url == ASSET_URL && sha == SHA256 && size == ARTIFACT_BYTES.toString()) {
            "ZLS publisher metadata differs from the exact reviewed ARM64 archive"
        }
        return LocalUbuntuVendorRecipe(definition, VERSION, VERSION, ASSET, url, sha, ARTIFACT_BYTES, INDEX_URL)
            .also { it.validate() }
    }

    /** build.zig at 494486203c3a48927f2383aa3d5ce5fca112186d packages these three flat files. */
    @JvmOverloads
    fun requireTree(entry: File, version: String = VERSION) {
        val root = requireNotNull(entry.parentFile)
        require(entry.name == "zls" && entry.isFile && !PathSecurity.isSymbolicLink(entry) &&
            root.isDirectory && !PathSecurity.isSymbolicLink(root)) { "ZLS archive entry is not a regular root-level zls file" }
        require(root.listFiles()?.map { it.name }?.toSet() == setOf("zls", "LICENSE", "README.md")) {
            "ZLS archive differs from the reviewed flat binary/license/readme layout"
        }
        if (version != VERSION) {
            for (name in listOf("LICENSE", "README.md")) {
                val file = File(root, name)
                require(file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() in 1..1_000_000L)
            }
            return
        }
        requireNotice(File(root, "LICENSE"), LICENSE_BYTES, LICENSE_SHA256)
        requireNotice(File(root, "README.md"), README_BYTES, README_SHA256)
    }

    private fun requireNotice(file: File, size: Long, sha256: String) {
        require(file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() == size) {
            "ZLS archive must retain its exact publisher ${file.name}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        require(digest == sha256) { "ZLS publisher ${file.name} differs from reviewed source" }
    }
}
