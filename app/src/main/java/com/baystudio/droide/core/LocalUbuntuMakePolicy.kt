package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Original publisher identity and proposed fail-closed layout; no original runtime acceptance. */
internal object LocalUbuntuMakePolicy {
    const val FAMILY = "lsp.make-ls"
    const val VERSION = "0.1.25"
    const val REPOSITORY = "owenrumney/make-ls"
    const val SOURCE_COMMIT = "793ab58a37a59768a042dff82c6bc6a2a15ae5bd"
    const val ASSET = "make-ls_linux_arm64.tar.gz"
    const val SHA256 = "6008e679a9f76554877e4d6e1160030e0f8eb6ac790a6624668f1dbeae67934e"
    const val ARTIFACT_BYTES = 2_833_278L
    const val RELEASE_ID = 390_059_573L
    const val ASSET_ID = 568_317_126L
    const val METADATA_URL = "https://api.github.com/repos/$REPOSITORY/releases/tags/v$VERSION"
    const val RELEASE_API_URL = "https://api.github.com/repos/$REPOSITORY/releases/$RELEASE_ID"
    const val ASSET_URL = "https://github.com/$REPOSITORY/releases/download/v$VERSION/$ASSET"
    const val PROVENANCE_URL = "https://github.com/$REPOSITORY/releases/tag/v$VERSION"
    const val LICENSE_SHA256 = "21d27fe886bf885f89037c9c8fba58adc4ee62f1cdf34686f4c890ec5142de8b"
    const val README_SHA256 = "119b98155cd353416b6541d163b5386367a547752c7c14c208656bad0fc401a6"
    private const val MAX_TREE_BYTES = 64L * 1024 * 1024

    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.definition == LocalUbuntuVendorCatalog.find(FAMILY) && recipe.version == VERSION &&
            recipe.releaseTag == "v$VERSION" && recipe.assetName == ASSET && recipe.assetUrl == ASSET_URL &&
            recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES && recipe.provenanceUrl == PROVENANCE_URL) {
            "Only the exact reviewed Make LS $VERSION original ARM64 archive is supported; no version is substituted."
        }
    }

    fun publisherRecipe(definition: LocalUbuntuVendorDefinition, text: String): LocalUbuntuVendorRecipe {
        require(text.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "Make LS publisher metadata exceeds its bound" }
        require(definition == LocalUbuntuVendorCatalog.find(FAMILY)) { "Unreviewed Make LS definition" }
        val release = Json.parseToJsonElement(text) as? JsonObject ?: error("Make LS release is not an object")
        fun JsonObject.string(key: String): String {
            val value = this[key] as? JsonPrimitive
            require(value != null && value.isString) { "Make LS publisher field '$key' is not a string" }
            return value.content
        }
        fun JsonObject.scalar(key: String, expected: String) {
            val value = this[key] as? JsonPrimitive
            require(value != null && !value.isString && value.content == expected) { "Make LS publisher field '$key' changed" }
        }
        release.scalar("id", RELEASE_ID.toString())
        release.scalar("draft", "false"); release.scalar("prerelease", "false")
        require(release.string("tag_name") == "v$VERSION" && release.string("html_url") == PROVENANCE_URL &&
            release.string("url") == RELEASE_API_URL) { "Make LS exact release identity changed" }
        val assets = release["assets"] as? JsonArray ?: error("Make LS release assets are missing")
        val candidates = assets.map {
            val asset = it as? JsonObject ?: error("Make LS release asset is not an object")
            asset.string("name") to asset
        }.filter { it.first == ASSET }
        val asset = candidates.singleOrNull()?.second ?: error("Make LS ARM64 asset is missing or ambiguous")
        asset.scalar("id", ASSET_ID.toString()); asset.scalar("size", ARTIFACT_BYTES.toString())
        require(asset.string("state") == "uploaded" && asset.string("browser_download_url") == ASSET_URL &&
            asset.string("digest") == "sha256:$SHA256") { "Make LS publisher asset identity changed or lacks its exact digest" }
        return LocalUbuntuVendorRecipe(definition, VERSION, "v$VERSION", ASSET, ASSET_URL, SHA256,
            ARTIFACT_BYTES, PROVENANCE_URL).also { it.validate() }
    }

    /** Exact admitted projected tree, not a claim about unopened publisher TAR/GZIP envelope bytes. */
    fun requireTree(entry: File) {
        val root = requireNotNull(entry.parentFile)
        require(root.isDirectory && !PathSecurity.isSymbolicLink(root) && entry.name == "make-ls") { "Invalid Make LS tree root" }
        val files = requireNotNull(root.listFiles()) { "Cannot inspect Make LS tree" }
        require(files.size == 3 && files.map { it.name }.toSet() == setOf("make-ls", "LICENSE", "README.md")) {
            "Make LS projected tree differs from proposed flat binary and notice layout"
        }
        var bytes = 0L
        files.forEach {
            require(it.isFile && !PathSecurity.isSymbolicLink(it) && it.length() > 0) { "Make LS tree must contain only regular nonempty files" }
            bytes = Math.addExact(bytes, it.length())
            require(bytes <= MAX_TREE_BYTES) { "Make LS tree exceeds its bound" }
        }
        fun notice(name: String, size: Long, expected: String) {
            val file = File(root, name)
            require(file.length() == size) { "Make LS publisher $name length changed" }
            val bytes = ByteBuffer.allocate(Math.toIntExact(size + 1))
            FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                require(channel.size() == size) { "Make LS publisher $name length changed before reading" }
                while (bytes.hasRemaining()) {
                    if (channel.read(bytes) <= 0) break // No zero-progress retry; at most exact size plus one bytes.
                }
                require(bytes.position().toLong() == size && channel.size() == size &&
                    file.isFile && !PathSecurity.isSymbolicLink(file)) { "Make LS publisher $name changed while reading" }
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array().copyOf(size.toInt()))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash == expected) { "Make LS publisher $name differs from reviewed source" }
        }
        notice("LICENSE", 1_068L, LICENSE_SHA256)
        notice("README.md", 4_867L, README_SHA256)
    }

    /** Narrow Make-only extension: the shared static gate also admits ET_DYN and PT_DYNAMIC. */
    fun requireStaticShape(entry: File) {
        require(LinuxArm64ElfAdmission.requireLinuxStatic(entry).type == 2) { "Make LS requires static ET_EXEC" }
        RandomAccessFile(entry, "r").use { file ->
            val header = ByteBuffer.wrap(ByteArray(64).also(file::readFully)).order(ByteOrder.LITTLE_ENDIAN)
            require(header.getShort(16).toInt() == 2)
            val offset = header.getLong(32)
            val size = header.getShort(54).toInt() and 0xffff
            val count = header.getShort(56).toInt() and 0xffff
            require(offset >= 64 && size == 56 && count in 1..512 && offset <= file.length() &&
                Math.multiplyExact(size.toLong(), count.toLong()) <= file.length() - offset)
            repeat(count) { index ->
                file.seek(offset + index.toLong() * size)
                val kind = Integer.reverseBytes(file.readInt())
                require(kind != 2 && kind != 3) { "Make LS static executable must not declare PT_DYNAMIC or PT_INTERP" }
            }
        }
    }
}
