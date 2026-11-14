package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest

/** Exact publisher checksum/HEAD metadata; binary layout and target acceptance remain separate. */
internal object LocalUbuntuTerraformPolicy {
    const val FAMILY = "lsp.terraform-ls"
    const val VERSION = "0.39.0"
    const val SHA256 = "62f32ea22cb78e5e5667ed638ad6e0fbde30ab59228d073c3c9bb249f89c7f5a"
    const val ARTIFACT_BYTES = 30_305_656L
    const val ASSET = "terraform-ls_0.39.0_linux_arm64.zip"
    const val BASE_URL = "https://releases.hashicorp.com/terraform-ls/0.39.0/"
    const val CHECKSUM_URL = BASE_URL + "terraform-ls_0.39.0_SHA256SUMS"
    const val LICENSE_SHA256 = "aff4e69bd67075d771127826ddf1ffa929c064f48bdd7ed6f8c946598399f523"

    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.version == VERSION && recipe.releaseTag == "v$VERSION" && recipe.assetName == ASSET &&
            recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES && recipe.provenanceUrl == CHECKSUM_URL) {
            "Only the exact reviewed Terraform LS $VERSION ARM64 artifact is supported; no version is substituted."
        }
    }

    @JvmOverloads
    fun publisherChecksum(text: String, asset: String = ASSET): String {
        require(text.toByteArray(Charsets.UTF_8).size <= 65_536) { "Terraform LS checksum metadata exceeds its bound" }
        val rows = text.lineSequence().filter { it.isNotBlank() }.map { line ->
            require(line.length <= 300) { "Publisher checksum line exceeds its bound" }
            val match = Regex("([0-9a-f]{64})  ([A-Za-z0-9._-]+)").matchEntire(line)
                ?: error("Malformed Terraform LS publisher checksum line")
            match.groupValues[2] to match.groupValues[1]
        }.toList()
        require(rows.map { it.first }.distinct().size == rows.size) { "Duplicate publisher checksum entry" }
        val value = rows.singleOrNull { it.first == asset }?.second
        require(value != null && (asset != ASSET || value == SHA256)) { "Reviewed Terraform LS publisher checksum is missing or changed" }
        return value
    }

    /** Expected layout is workflow-derived. Reject unreviewed changes; never discard notices. */
    @JvmOverloads
    fun requireTree(entry: File, version: String = VERSION) {
        val parent = requireNotNull(entry.parentFile)
        require(parent.isDirectory && !PathSecurity.isSymbolicLink(parent))
        require(parent.listFiles()?.map { it.name }?.toSet() == setOf("terraform-ls", "LICENSE.txt")) {
            "Terraform LS archive differs from the reviewed flat binary/license layout"
        }
        val license = File(parent, "LICENSE.txt")
        require(license.isFile && !PathSecurity.isSymbolicLink(license) && license.length() in 1..1_000_000L)
        if (version != VERSION) return
        require(license.length() == 16_009L)
        val digest = MessageDigest.getInstance("SHA-256").digest(license.readBytes()).joinToString("") { "%02x".format(it) }
        require(digest == LICENSE_SHA256) { "Terraform LS publisher license differs from reviewed source" }
    }
}
