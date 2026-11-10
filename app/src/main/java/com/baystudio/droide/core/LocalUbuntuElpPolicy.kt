package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.*

internal object LocalUbuntuElpPolicy {
    const val FAMILY = "lsp.elp"
    const val COMMAND = "elp"
    const val VERSION = "2026.10.14-otp27.3"
    const val RELEASE_TAG = "2026-10-14"
    const val REPOSITORY = "WhatsApp/erlang-language-platform"
    const val SOURCE_COMMIT = "b36ba37346a8e6122fd309420cb67886ccc5871c"
    const val POLICY = "elp-original-otp27.3-v1"
    const val KIND = "ubuntu-elp-original-v1"
    const val ASSET = "elp-linux-aarch64-unknown-linux-gnu-otp-27.3.tar.gz"
    const val SHA256 = "282f5f26105c6bc64e17745a38ba5718300c51072d34afbb2a03163dbc4abc71"
    const val ARTIFACT_BYTES = 39_214_062L
    const val MAX_MEMBER_BYTES = 268_435_456L
    const val ROOT = "/opt/droide/packages/$FAMILY/$VERSION/arm64-v8a"
    const val EXECUTABLE = "$ROOT/payload/tree/elp"
    const val ASSET_URL = "https://github.com/$REPOSITORY/releases/download/$RELEASE_TAG/$ASSET"
    const val PROVENANCE_URL = "https://github.com/$REPOSITORY/releases/tag/$RELEASE_TAG"
    const val METADATA_URL = "https://api.github.com/repos/$REPOSITORY/releases/tags/$RELEASE_TAG"
    const val ORIGIN_SHA256 = "56ee2191992568b1a20c20ca87e8a64b569a33a2e4d42bfa702bea33ebeac981"
    const val NOTICE_POLICY_SHA256 = "e5352514eb7ecbc2546fa20d97b03c0dadf476fb8a738ab71c2a61364d5ac463"
    const val COVERAGE = "Original native-image/transitive notice closure and embedded-service/target acceptance remain unverified; zero-project health is not project acceptance."
    val helperHashes = mapOf(
        "payload/runtime-report.escript" to "5ff615d3d4a0ec71d6f91ab7860c1ba59fa1b82ed614bf4560b1e62db68d2065",
        "payload/rebar-shim.sh" to "0b6ed28cbd33b06576c439a2f165be45844ca959af0fe3697528569b7c6ee123",
        "payload/metadata-observer.py" to "350043bc7d577defca414d18b9819b9c75a964234f606b30c2cc614e4cd91739")
    val licenseHashes = mapOf("ELP-LICENSE-MIT.txt" to "da6d3703ed11cbe42bd212c725957c98da23cbff1998c05fa4b3d976d1a58e93", "ELP-LICENSE-APACHE.txt" to "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4")
    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.definition == LocalUbuntuVendorCatalog.find(FAMILY) && recipe.version == VERSION &&
            recipe.releaseTag == RELEASE_TAG && recipe.assetName == ASSET && recipe.assetUrl == ASSET_URL &&
            recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES && recipe.provenanceUrl == PROVENANCE_URL) {
            "Only exact original ELP $RELEASE_TAG OTP-27.3 ARM64 bytes are reviewed"
        }
    }
    fun recipe() = LocalUbuntuVendorRecipe(requireNotNull(LocalUbuntuVendorCatalog.find(FAMILY)), VERSION, RELEASE_TAG,
        ASSET, ASSET_URL, SHA256, ARTIFACT_BYTES, PROVENANCE_URL)
    fun publisherRecipe(definition: LocalUbuntuVendorDefinition, raw: String): LocalUbuntuVendorRecipe {
        require(definition == LocalUbuntuVendorCatalog.find(FAMILY))
        val value = LocalUbuntuPowerShellRuntimePolicy.strictObject(raw, 1_000_000)
        require(value["id"] == JsonPrimitive(412702043L) && value["tag_name"] == JsonPrimitive(RELEASE_TAG) &&
            value["draft"] == JsonPrimitive(false) && value["prerelease"] == JsonPrimitive(false) && value["html_url"] == JsonPrimitive(PROVENANCE_URL))
        val asset = value["assets"]!!.jsonArray.map { it.jsonObject }.single { it["name"] == JsonPrimitive(ASSET) }
        require(asset["id"] == JsonPrimitive(640381990L) && asset["size"] == JsonPrimitive(ARTIFACT_BYTES) &&
            asset["digest"] == JsonPrimitive("sha256:$SHA256") && asset["browser_download_url"] == JsonPrimitive(ASSET_URL) && asset["state"] == JsonPrimitive("uploaded"))
        return recipe().also { it.validate() }
    }
    fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun measure(file: File, max: Long = MAX_MEMBER_BYTES, active: () -> Unit = {}) = NimRuntimeClosure.measure(file,max,active)
    fun requireNative(file: File, active: () -> Unit = {}) {
        require(file.name == "elp")
        val image = NimRuntimeClosure.inspectElf(file,"elp",NimRuntimeClosure.NativeRole.EXECUTABLE,active)
        require(image.soname==null && !image.originSearch) { "ELP dynamic search or executable shape is unreviewed" }
    }
    fun wrapper() = "#!/bin/sh\nset -eu\nprintf '%s\\n' 'ELP requires an exact reviewed owner and explicit selected-tool permission.' >&2\nexit 64\n"
}
