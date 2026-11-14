package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Exact original publisher identity. Static admission is not native/platform or dependency acceptance. */
internal object LocalUbuntuClojurePolicy {
    const val FAMILY = "lsp.clojure-lsp"
    const val COMMAND = "clojure-lsp"
    const val VERSION = "2026.10.15-11.42.38"
    const val REPOSITORY = "clojure-lsp/clojure-lsp"
    const val SOURCE_COMMIT = "3f7f177268aee3d943b983843a2596a015e67a48"
    const val SOURCE_TREE = "9eb6b4bb2b79cea473376544f496681f814f9706"
    const val ASSET = "clojure-lsp-native-linux-aarch64.zip"
    const val SHA256 = "232ac0cd191070a3b7b5532135ec9f97217b82324de7e97da0b1bf81353a3c94"
    const val ARTIFACT_BYTES = 43_862_001L
    const val RELEASE_ID = 413_784_285L
    const val ASSET_ID = 643_803_086L
    const val SIDECAR_ID = 643_803_222L
    const val SIDECAR_SHA256 = "9c9fdcff1daab4a184258982d3c95a0f43ff0ec97dad466d75d9bffa5fb78702"
    const val METADATA_URL = "https://api.github.com/repos/$REPOSITORY/releases/tags/$VERSION"
    const val RELEASE_API_URL = "https://api.github.com/repos/$REPOSITORY/releases/$RELEASE_ID"
    const val ASSET_URL = "https://github.com/$REPOSITORY/releases/download/$VERSION/$ASSET"
    const val CHECKSUM_URL = "$ASSET_URL.sha256"
    const val PROVENANCE_URL = "https://github.com/$REPOSITORY/releases/tag/$VERSION"
    const val LICENSE_URL = "https://raw.githubusercontent.com/$REPOSITORY/$SOURCE_COMMIT/LICENSE"
    const val LICENSE_SHA256 = "f2ea07ed625651b1e4a88ae49b620f0fecd32b64fe1f6609e4d0f12754d77e72"
    const val NOTICE_SHA256 = LICENSE_SHA256
    const val LICENSE_BYTES = 1_080L
    const val NOTICE_PATH = "DROIDE-NOTICES/clojure-lsp-LICENSE.txt"
    const val ORIGIN_PATH = "DROIDE-NOTICES/origin.json"
    const val MAX_MEMBER_BYTES = 256L * 1024 * 1024
    const val MAX_TREE_BYTES = MAX_MEMBER_BYTES + 16_384L

    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.definition == LocalUbuntuVendorCatalog.find(FAMILY) && recipe.version == VERSION &&
            recipe.releaseTag == VERSION && recipe.assetName == ASSET && recipe.assetUrl == ASSET_URL &&
            recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES && recipe.provenanceUrl == PROVENANCE_URL) {
            "Only the exact reviewed Clojure LSP $VERSION original ARM64 ZIP is supported; no version is substituted."
        }
    }

    /** Both publisher assets, strict scalar identities, and exact untrimmed sidecar bytes are required. */
    fun publisherRecipe(definition: LocalUbuntuVendorDefinition, text: String, checksumText: String): LocalUbuntuVendorRecipe {
        require(text.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "Clojure LSP publisher metadata exceeds its bound" }
        require(definition == LocalUbuntuVendorCatalog.find(FAMILY)) { "Unreviewed Clojure LSP definition" }
        val release = Json.parseToJsonElement(text) as? JsonObject ?: error("Clojure LSP release is not an object")
        fun JsonObject.string(key: String): String {
            val value = this[key] as? JsonPrimitive
            require(value != null && value.isString) { "Clojure LSP publisher field '$key' is not a string" }
            return value.content
        }
        fun JsonObject.scalar(key: String, expected: String) {
            val value = this[key] as? JsonPrimitive
            require(value != null && !value.isString && value.content == expected) { "Clojure LSP publisher field '$key' changed" }
        }
        release.scalar("id", RELEASE_ID.toString())
        release.scalar("draft", "false"); release.scalar("prerelease", "false")
        require(release.string("tag_name") == VERSION && release.string("html_url") == PROVENANCE_URL &&
            release.string("url") == RELEASE_API_URL) { "Clojure LSP exact release identity changed" }
        val assets = release["assets"] as? JsonArray ?: error("Clojure LSP release assets are missing")
        val named = assets.map { element ->
            val asset = element as? JsonObject ?: error("Clojure LSP release asset is not an object")
            asset.string("name") to asset
        }
        fun asset(name: String, id: Long, size: Long, url: String, hash: String) {
            val item = named.filter { it.first == name }.singleOrNull()?.second
                ?: error("Clojure LSP exact publisher asset is missing or ambiguous: $name")
            item.scalar("id", id.toString()); item.scalar("size", size.toString())
            require(item.string("state") == "uploaded" && item.string("browser_download_url") == url &&
                item.string("digest") == "sha256:$hash") { "Clojure LSP asset identity changed or lacks its exact digest" }
        }
        asset(ASSET, ASSET_ID, ARTIFACT_BYTES, ASSET_URL, SHA256)
        asset("$ASSET.sha256", SIDECAR_ID, 65, CHECKSUM_URL, SIDECAR_SHA256)
        require(checksumText == "$SHA256\n" && sha256(checksumText.toByteArray(Charsets.UTF_8)) == SIDECAR_SHA256) {
            "Clojure LSP original publisher checksum bytes changed"
        }
        return LocalUbuntuVendorRecipe(definition, VERSION, VERSION, ASSET, ASSET_URL, SHA256,
            ARTIFACT_BYTES, PROVENANCE_URL).also { it.validate() }
    }

    /** Deterministic supplemental provenance, explicitly separate from original publisher ZIP inventory. */
    fun originText(memberSha256: String, memberBytes: Long): String = originBytes(memberSha256, memberBytes).toString(Charsets.UTF_8)

    fun originBytes(memberSha256: String, memberBytes: Long): ByteArray {
        require(memberSha256.matches(Regex("[0-9a-f]{64}")) && memberBytes in 1..MAX_MEMBER_BYTES)
        return ("""{"schema":1,"familyId":"$FAMILY","release":"$VERSION","releaseId":$RELEASE_ID,"sourceCommit":"$SOURCE_COMMIT","sourceTree":"$SOURCE_TREE","artifactName":"$ASSET","artifactUrl":"$ASSET_URL","artifactSha256":"$SHA256","artifactBytes":$ARTIFACT_BYTES,"memberPath":"$COMMAND","memberSha256":"$memberSha256","memberBytes":$memberBytes,"noticePath":"$NOTICE_PATH","noticeSourceUrl":"$LICENSE_URL","noticeSha256":"$LICENSE_SHA256","noticeBytes":$LICENSE_BYTES,"noticeSuppliedSeparately":true,"completeArtifactLicenseInventory":false}""" + "\n").toByteArray(Charsets.UTF_8)
    }

    /** Tree and notice revalidation relies on the installer's independent sealed-tree authority. */
    fun requireTree(entry: File) {
        val root = requireNotNull(entry.parentFile)
        require(entry.name == COMMAND) { "Invalid Clojure LSP tree entry" }
        requireInventory(root, setOf(COMMAND, "DROIDE-NOTICES"))
        requireInventory(File(root, "DROIDE-NOTICES"), setOf("clojure-lsp-LICENSE.txt", "origin.json"))
        val measured = measure(entry, MAX_MEMBER_BYTES)
        require(measured.first > 0) { "Clojure LSP native member is empty" }
        require(readExact(File(root, NOTICE_PATH), LICENSE_BYTES).contentEquals(licenseBytes)) { "Clojure LSP exact source MIT notice changed" }
        val origin = originBytes(measured.second, measured.first)
        require(readExact(File(root, ORIGIN_PATH), origin.size.toLong()).contentEquals(origin)) { "Clojure LSP supplemental provenance changed" }
    }

    /** Clojure-only non-executing ELF extension; DT_NEEDED/GLIBC versions/runtime behavior remain unverified. */
    fun requireElfStructure(entry: File) {
        LinuxArm64ElfAdmission.requireUbuntuGlibc(entry)
        require(Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS) && entry.length() in 64..MAX_MEMBER_BYTES)
        FileChannel.open(entry.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val length = input.size()
            fun read(at: Long, count: Int): ByteBuffer {
                require(at >= 0 && at <= length && count <= length - at) { "Clojure LSP ELF structure escapes the file" }
                val buffer = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN)
                input.position(at)
                while (buffer.hasRemaining()) require(input.read(buffer) > 0) { "Truncated Clojure LSP ELF" }
                return buffer
            }
            val header = read(0, 64)
            require(header.getInt(20) == 1 && header.getShort(52).toInt() == 64)
            val address = header.getLong(24); val offset = header.getLong(32)
            val size = header.getShort(54).toInt() and 65535; val count = header.getShort(56).toInt() and 65535
            require(address > 0 && offset >= 64 && size == 56 && count in 1..512 &&
                offset <= length && size.toLong() * count <= length - offset) { "Invalid Clojure LSP ELF entry/program table" }
            var executableEntry = false
            var dynamic = 0
            repeat(count) { index ->
                val ph = read(offset + index.toLong() * size, 56)
                val kind = ph.getInt(0)
                val fileOffset = ph.getLong(8); val virtual = ph.getLong(16)
                val fileBytes = ph.getLong(32); val memoryBytes = ph.getLong(40)
                require(fileOffset >= 0 && fileBytes >= 0 && fileOffset <= length && fileBytes <= length - fileOffset) {
                    "Clojure LSP ELF segment escapes the file"
                }
                if (kind == 1) {
                    val alignment = ph.getLong(48)
                    require(virtual >= 0 && memoryBytes >= fileBytes && virtual <= Long.MAX_VALUE - memoryBytes && alignment >= 0 &&
                        (alignment <= 1 || alignment and (alignment - 1) == 0L)) { "Invalid Clojure LSP ELF load segment" }
                    if (alignment > 1) require(fileOffset % alignment == virtual % alignment) { "Misaligned Clojure LSP ELF load segment" }
                    if (ph.getInt(4) and 1 != 0 && address >= virtual && address - virtual < fileBytes) executableEntry = true
                }
                if (kind == 2) {
                    dynamic++
                    require(dynamic == 1 && fileBytes in 16..65_536 && fileBytes % 16 == 0L) { "Invalid Clojure LSP ELF dynamic table" }
                    var terminated = false
                    for (at in 0 until fileBytes step 16) if (read(fileOffset + at, 16).getLong(0) == 0L) { terminated = true; break }
                    require(terminated) { "Unterminated Clojure LSP ELF dynamic table" }
                }
            }
            require(executableEntry && dynamic == 1) { "Clojure LSP requires a file-backed executable entry and dynamic table" }
            require(input.size() == length && Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Clojure LSP ELF changed during admission" }
        }
    }

    // Held-channel bounds plus path snapshots detect ordinary replacement/drift. Java exposes no portable
    // FileChannel inode; this remains within app-owned package trust, not an atomic same-UID defense.
    internal fun measure(file: File, limit: Long, active: () -> Unit = {}): Pair<Long, String> {
        val before = snapshot(file)
        require(before.size() in 1..limit) { "Clojure LSP file exceeds its byte bound" }
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val original = input.size()
            require(original == before.size() && sameState(before, snapshot(file))) { "Clojure LSP file changed before reading" }
            val buffer = ByteBuffer.allocate(65_536)
            while (true) {
                active(); buffer.clear(); val n = input.read(buffer)
                if (n < 0) break
                require(n > 0) { "Clojure LSP file made no read progress" }
                count = Math.addExact(count, n.toLong()); require(count <= original)
                hash.update(buffer.array(), 0, n)
            }
            require(count == original && input.size() == original && sameState(before, snapshot(file))) { "Clojure LSP file changed while reading" }
        }
        return count to hash.digest().hex()
    }
    private fun readExact(file: File, length: Long): ByteArray {
        val before = snapshot(file)
        require(length in 1..16_384 && before.size() == length)
        return FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            require(input.size() == length && sameState(before, snapshot(file)))
            val buffer = ByteBuffer.allocate(length.toInt())
            while (buffer.hasRemaining()) require(input.read(buffer) > 0)
            require(input.size() == length && sameState(before, snapshot(file)))
            buffer.array()
        }
    }
    /** Bounded family inventory: fail as soon as an unreviewed member appears, without an unbounded list(). */
    internal fun requireInventory(directory: File, expected: Set<String>) {
        require(expected.size in 1..3)
        val before = snapshot(directory, directory = true)
        val seen = HashSet<String>()
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) require(seen.size < expected.size && entry.fileName.toString() in expected && seen.add(entry.fileName.toString())) {
                "Unexpected Clojure LSP original or supplemental tree member"
            }
        }
        require(seen == expected && sameState(before, snapshot(directory, directory = true))) { "Clojure LSP directory inventory or identity changed" }
    }
    private fun snapshot(file: File, directory: Boolean = false): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
            require(!it.isSymbolicLink && (if (directory) it.isDirectory else it.isRegularFile) && it.fileKey() != null) {
                "Clojure LSP path lacks a stable regular nonlink identity"
            }
        }
    private fun sameState(left: BasicFileAttributes, right: BasicFileAttributes): Boolean =
        left.fileKey() == right.fileKey() && left.size() == right.size() && left.lastModifiedTime() == right.lastModifiedTime()

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    // Exact unmodified source/LICENSE, not a claim that the original ZIP contains this notice.
    val licenseBytes: ByteArray get() = SOURCE_LICENSE.toByteArray(Charsets.UTF_8).also {
        check(it.size.toLong() == LICENSE_BYTES && sha256(it) == LICENSE_SHA256)
    }
    private const val SOURCE_LICENSE = """MIT License

Copyright (c) 2022 clojure-lsp maintainers

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""
}
