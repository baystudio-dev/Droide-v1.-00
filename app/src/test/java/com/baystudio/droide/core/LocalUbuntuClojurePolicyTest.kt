package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Authored publisher JSON/ZIP/ELF fixtures only. No original native payload, project tools or execution. */
class LocalUbuntuClojurePolicyTest {
    private val p get() = LocalUbuntuClojurePolicy
    private val definition get() = requireNotNull(LocalUbuntuVendorCatalog.find(p.FAMILY))
    private val nativeAsset get() = """{"id":${p.ASSET_ID},"name":"${p.ASSET}","state":"uploaded","size":${p.ARTIFACT_BYTES},"digest":"sha256:${p.SHA256}","browser_download_url":"${p.ASSET_URL}"}"""
    private val sidecar get() = """{"id":${p.SIDECAR_ID},"name":"${p.ASSET}.sha256","state":"uploaded","size":65,"digest":"sha256:${p.SIDECAR_SHA256}","browser_download_url":"${p.CHECKSUM_URL}"}"""
    private val metadata get() = """{"id":${p.RELEASE_ID},"url":"${p.RELEASE_API_URL}","html_url":"${p.PROVENANCE_URL}","tag_name":"${p.VERSION}","draft":false,"prerelease":false,"assets":[$nativeAsset,$sidecar]}"""
    private fun recipe() = p.publisherRecipe(definition, metadata, p.SHA256 + "\n")
    private fun reject(block: () -> Unit) = assertTrue("Expected admission to reject", runCatching(block).isFailure)
    private fun root(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("clojure-policy-").toFile()
        try { block(directory) } finally { assertTrue(PathSecurity.deleteTreeNoFollow(directory)) }
    }

    @Test fun catalogAndRecipeAreLiteralOriginalZipOnly() {
        val r = recipe(); r.validate()
        assertEquals(p.VERSION, definition.offeredVersion); assertFalse(definition.supportsPublisherVersionSelection)
        assertEquals(LocalUbuntuVendorFormat.ZIP, definition.format)
        assertEquals(LocalUbuntuVendorAdmission.GLIBC_ARM64, definition.admission)
        assertEquals(listOf("ca-certificates", "libc6", "nodejs"), r.requiredPackages)
        assertTrue(definition.interpreter.isEmpty()); assertTrue(definition.healthArgs.isEmpty())
        assertTrue(definition.auxiliaryCommands.isEmpty()); assertTrue(definition.lsp)
        assertEquals(3, definition.maxFiles); assertEquals(p.MAX_TREE_BYTES, definition.maxUnpackedBytes)
        assertEquals(p.ARTIFACT_BYTES, definition.maxAssetBytes)
        assertEquals(p.COMMAND, r.entryPoint); assertEquals(p.VERSION, r.releaseTag)
        for (bad in listOf(r.copy(version = "latest"), r.copy(version = "v${p.VERSION}"), r.copy(releaseTag = "v${p.VERSION}"),
            r.copy(assetName = "source.zip"), r.copy(assetUrl = p.CHECKSUM_URL), r.copy(assetSize = null), r.copy(assetSize = 1L),
            r.copy(assetSha256 = "a".repeat(64)), r.copy(provenanceUrl = p.METADATA_URL),
            r.copy(definition = definition.copy(maxFiles = 4)))) {
            reject { p.requireReviewed(bad) }; reject { bad.validate() }
        }
    }

    @Test fun everyPublisherIdentityRejectsMissingWrongAndCoercedScalars() {
        val release = Json.parseToJsonElement(metadata).jsonObject
        val substitutions = listOf<JsonElement>(JsonNull, JsonPrimitive(true), JsonPrimitive("false"), JsonPrimitive(1),
            JsonPrimitive("1"), JsonArray(emptyList()), JsonObject(emptyMap()))
        val keys = listOf("id", "url", "html_url", "tag_name", "draft", "prerelease")
        for (key in keys) {
            for (value in substitutions) if (value != release[key]) reject {
                p.publisherRecipe(definition, JsonObject(release + (key to value)).toString(), p.SHA256 + "\n")
            }
            reject { p.publisherRecipe(definition, JsonObject(release - key).toString(), p.SHA256 + "\n") }
        }
        val original = listOf(Json.parseToJsonElement(nativeAsset).jsonObject, Json.parseToJsonElement(sidecar).jsonObject)
        for (index in original.indices) for (key in listOf("id", "name", "state", "size", "digest", "browser_download_url")) {
            for (value in substitutions + JsonPrimitive("sha256:${"0".repeat(64)}")) reject {
                val assets = original.toMutableList().apply { set(index, JsonObject(original[index] + (key to value))) }
                p.publisherRecipe(definition, JsonObject(release + ("assets" to JsonArray(assets))).toString(), p.SHA256 + "\n")
            }
            reject {
                val assets = original.toMutableList().apply { set(index, JsonObject(original[index] - key)) }
                p.publisherRecipe(definition, JsonObject(release + ("assets" to JsonArray(assets))).toString(), p.SHA256 + "\n")
            }
        }
        for (assets in listOf(emptyList(), listOf(original[0]), listOf(original[1]), original + original[0], original + original[1], original + JsonNull))
            reject { p.publisherRecipe(definition, JsonObject(release + ("assets" to JsonArray(assets))).toString(), p.SHA256 + "\n") }
        for (bad in listOf("[]", "null", "{", " ".repeat(1_000_001), metadata.replace("43862001", "43862001.0"),
            metadata.replace("413784285", "4.13784285e8"), metadata.replace("\"id\":643803222", "\"id\":\"643803222\"")))
            reject { p.publisherRecipe(definition, bad, p.SHA256 + "\n") }
        for (bad in listOf(p.SHA256, p.SHA256 + "\r\n", p.SHA256.uppercase() + "\n", p.SHA256 + "  " + p.ASSET + "\n", " ".repeat(1_000_001)))
            reject { p.publisherRecipe(definition, metadata, bad) }
        reject { p.publisherRecipe(definition.copy(packages = emptyList()), metadata, p.SHA256 + "\n") }
        val notes = JsonObject(release + ("body" to JsonPrimitive("Unrelated release notes may change"))).toString()
        assertEquals(recipe(), p.publisherRecipe(definition, notes, p.SHA256 + "\n"))
    }

    @Test fun resolverRejectsVersionAliasesBeforeAnyFetchAndUsesTwoExactEndpoints() = runBlocking<Unit> {
        val fetched = mutableListOf<String>()
        val resolver = LocalUbuntuVendorResolver { url ->
            fetched += url
            when (url) { p.METADATA_URL -> metadata; p.CHECKSUM_URL -> p.SHA256 + "\n"; else -> error("Unexpected request") }
        }
        for (version in listOf("", "latest", "Latest official", "Reviewed Ubuntu release", "v${p.VERSION}", "2026.10.15-11.42.39"))
            assertTrue(runCatching { resolver.resolve(definition, version) }.isFailure)
        assertTrue(fetched.isEmpty()); assertEquals(recipe(), resolver.resolve(definition, p.VERSION))
        assertEquals(listOf(p.METADATA_URL, p.CHECKSUM_URL), fetched)
    }

    @Test fun storedDeflatedAndBothBoundedDescriptorsHaveExactDecodedIdentity() = root { root ->
        val original = elf(); val archive = File(root, "fixture.zip")
        for (method in listOf(0, 8)) for (descriptor in if (method == 0) listOf(0) else listOf(0, 12, 16)) {
            archive.writeBytes(zip(original, method = method, descriptor = descriptor))
            val member = LocalUbuntuClojureArchive.requireZipStructure(archive)
            assertEquals(original.size.toLong(), member.size); assertEquals(p.sha256(original), member.sha256)
        }
        archive.writeBytes(zip(original, unixMode = 0))
        LocalUbuntuClojureArchive.requireZipStructure(archive)
        archive.writeBytes(zip(original, extra = byteArrayOf(0x55, 0x54, 1, 0, 0), comment = "inert comment"))
        LocalUbuntuClojureArchive.requireZipStructure(archive)
        val linked = File(root, "link.zip"); Files.createSymbolicLink(linked.toPath(), archive.toPath())
        reject { LocalUbuntuClojureArchive.requireZipStructure(linked) }
    }

    @Test fun zipRejectsEveryNonregularModeAndUnsafeName() = root { root ->
        val archive = File(root, "bad.zip")
        for (mode in listOf(0x1000, 0x2000, 0x4000, 0x6000, 0xa000, 0xc000, 0x9000, 0x8fff)) {
            archive.writeBytes(zip(elf(), unixMode = mode)); reject { LocalUbuntuClojureArchive.requireZipStructure(archive) }
        }
        for (name in listOf("./clojure-lsp", "dir/clojure-lsp", "../clojure-lsp", "/clojure-lsp", "clojure-lsp/", "clojure-lsp\\x", "CLOJURE-LSP", "clojure-lsp\u0000")) {
            archive.writeBytes(zip(elf(), name = name)); reject { LocalUbuntuClojureArchive.requireZipStructure(archive) }
        }
        for (extra in listOf(byteArrayOf(1, 0, 0, 0), byteArrayOf(0x75, 0x70, 0, 0), byteArrayOf(0x0d, 0, 0, 0),
            byteArrayOf(2, 0, 2, 0, 1), byteArrayOf(1), byteArrayOf(2, 0, 0, 0, 2, 0, 0, 0))) {
            archive.writeBytes(zip(elf(), extra = extra)); reject { LocalUbuntuClojureArchive.requireZipStructure(archive) }
        }
    }

    @Test fun zipRejectsPrefixesTrailersGapsMetadataCrcAndDescriptorMismatch() = root { root ->
        val archive = File(root, "bad.zip")
        fun check(bytes: ByteArray) { archive.writeBytes(bytes); reject { LocalUbuntuClojureArchive.requireZipStructure(archive) } }
        val good = zip(elf()); val central = centralOffset(good)
        for (bad in listOf(good.copyOf(12), good + byteArrayOf(0), byteArrayOf(0) + good,
            good.copyOfRange(0, central) + byteArrayOf(0) + good.copyOfRange(central, good.size))) check(bad)
        for (mutation in listOf<(ByteArray) -> Unit>(
            { put16(it, it.size - 22 + 4, 1) }, { put16(it, it.size - 22 + 8, 2) }, { put16(it, it.size - 22 + 10, 2) },
            { put32(it, it.size - 22 + 12, 46) }, { put32(it, central + 42, 1) }, { put16(it, central + 34, 1) },
            { put32(it, central + 38, 0x10) }, { put32(it, central + 38, 0x08) },
            { put32(it, central + 38, 0x40) }, { put32(it, central + 38, 0x400) },
            { put16(it, central + 6, 45) }, { put16(it, central + 8, 1) }, { put16(it, central + 10, 99) },
            { put16(it, 4, 20) }, { put16(it, 6, 2048) }, { put16(it, 8, 8) }, { put16(it, 10, 1) },
            { it[30] = 'X'.code.toByte() }, { put32(it, 14, 1) }, { put32(it, 18, 1) },
            { put32(it, central + 16, 1); put32(it, 14, 1) },
            { put32(it, central + 24, p.MAX_MEMBER_BYTES + 1) }, { put32(it, central + 20, 0xffffffffL) },
            { it[30 + p.COMMAND.length + 200] = 1 })) {
            val bad = good.copyOf(); mutation(bad); check(bad)
        }
        val descriptor = zip(elf(), method = 8, descriptor = 16)
        check(descriptor.copyOf().apply { put32(this, centralOffset(this) - 12, 1) })
        check(descriptor.copyOf().apply { put32(this, 14, 1) })
        check(descriptor.copyOf().apply { put32(this, centralOffset(this) - 16, 0) })
        check(zip(ByteArray(64 * 1024), method = 8)) // Expanded-to-compressed ratio is intentionally bounded.
    }

    @Test fun deflateTrailingAndTruncatedStreamsCannotHideBehindMatchingHeaders() = root { root ->
        val archive = File(root, "bad.zip")
        val good = zip(elf(), method = 8)
        val at = centralOffset(good)
        val withGarbage = good.copyOfRange(0, at) + byteArrayOf(1) + good.copyOfRange(at, good.size)
        val newCentral = at + 1
        put32(withGarbage, 18, u32(good, 18) + 1); put32(withGarbage, newCentral + 20, u32(good, 18) + 1)
        put32(withGarbage, withGarbage.size - 6, newCentral.toLong())
        archive.writeBytes(withGarbage); reject { LocalUbuntuClojureArchive.requireZipStructure(archive) }
        val truncated = good.copyOfRange(0, at - 1) + good.copyOfRange(at, good.size)
        put32(truncated, 18, u32(good, 18) - 1); put32(truncated, at - 1 + 20, u32(good, 18) - 1)
        put32(truncated, truncated.size - 6, (at - 1).toLong())
        archive.writeBytes(truncated); reject { LocalUbuntuClojureArchive.requireZipStructure(archive) }
    }

    @Test fun originalArchivePinCannotBeBypassedByAnAdmissibleSyntheticZip() = root { root ->
        val archive = File(root, p.ASSET).apply { writeBytes(zip(elf())) }
        LocalUbuntuClojureArchive.requireZipStructure(archive)
        reject { LocalUbuntuClojureArchive.requireOriginalArtifact(archive) }
        reject { LocalUbuntuClojureArchive.project(recipe(), archive) }
        java.io.RandomAccessFile(archive, "rw").use { it.setLength(p.ARTIFACT_BYTES) }
        reject { LocalUbuntuClojureArchive.requireOriginalArtifact(archive) }
    }

    @Test fun decorationPreservesExactBinaryAddsOnlySeparateNoticesAndRecomputesManifest() = root { root ->
        val archive = File(root, "inert.zip").apply { writeBytes(zip(elf())) }
        val original = SafeWholeTreeArchive.project(archive, DeclarativeGitHubArtifactFormat.ZIP, setOf(p.COMMAND), 1, p.MAX_MEMBER_BYTES)
        val binary = original.files.single()
        LocalUbuntuClojureArchive.decorate(original).use { decorated ->
            assertEquals(3, decorated.files.size); assertEquals(binary, decorated.files.single { it.relativePath == p.COMMAND })
            assertEquals(setOf(420), decorated.files.filter { it.relativePath != p.COMMAND }.map { it.mode }.toSet())
            assertArrayEquals(elf(), binary.file.readBytes()); assertEquals(p.LICENSE_BYTES, p.licenseBytes.size.toLong())
            assertEquals(p.LICENSE_SHA256, p.sha256(p.licenseBytes))
            assertEquals(p.originText(binary.sha256, binary.size), File(decorated.root, p.ORIGIN_PATH).readText())
            assertTrue(p.originText(binary.sha256, binary.size).contains("\"noticeSuppliedSeparately\":true"))
            assertTrue(p.originText(binary.sha256, binary.size).contains("\"completeArtifactLicenseInventory\":false"))
            val manifest = decorated.files.sortedBy { it.relativePath }.joinToString("") { "${it.relativePath}\u0000${it.mode}\u0000${it.sha256}\n" }
            assertEquals(p.sha256(manifest.toByteArray()), decorated.treeSha256)
            assertEquals(decorated.files.sumOf { it.size }, decorated.totalBytes); assertTrue(decorated.symlinks.isEmpty())
            p.requireTree(binary.file); p.requireElfStructure(binary.file)
            reject { LocalUbuntuClojureArchive.decorate(decorated) }
            for (relative in listOf(p.NOTICE_PATH, p.ORIGIN_PATH, p.COMMAND)) {
                val file = File(decorated.root, relative); val bytes = file.readBytes()
                file.writeBytes(bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }); reject { p.requireTree(binary.file) }
                file.delete(); Files.createSymbolicLink(file.toPath(), File(root, "outside").apply { writeBytes(bytes) }.toPath())
                reject { p.requireTree(binary.file) }; file.delete(); file.writeBytes(bytes)
            }
            File(decorated.root, "extra").writeText("extra"); reject { p.requireTree(binary.file) }
        }
    }

    @Test fun additiveElfGateRejectsInvalidEntryLoadBoundsAlignmentAndDynamicTable() = root { root ->
        val entry = File(root, p.COMMAND)
        entry.writeBytes(elf()); p.requireElfStructure(entry)
        entry.writeBytes(elf().apply { put16(this, 16, 3) }); p.requireElfStructure(entry) // PIE is allowed.
        for (mutation in listOf<(ByteArray) -> Unit>(
            { it[4] = 1 }, { it[5] = 2 }, { put16(it, 18, 62) }, { put32(it, 20, 0) },
            { put64(it, 24, 0) }, { put64(it, 24, 0x9000) }, { put64(it, 32, Long.MAX_VALUE) },
            { put16(it, 52, 63) }, { put16(it, 54, 57) }, { put16(it, 56, 0) },
            { put32(it, 68, 4) }, { put64(it, 72, -1) }, { put64(it, 96, 1025) }, { put64(it, 104, 1) },
            { put64(it, 80, Long.MAX_VALUE) }, { put64(it, 112, 3) }, { put64(it, 112, 4096); put64(it, 72, 1) },
            { put32(it, 120, 0) }, { it[256] = 'X'.code.toByte() }, { put32(it, 176, 0) },
            { put64(it, 208, 15) }, { put64(it, 320, 1); put64(it, 336, 1) })) {
            entry.writeBytes(elf().also(mutation)); reject { p.requireElfStructure(entry) }
        }
        entry.writeBytes(elf().copyOf(120)); reject { p.requireElfStructure(entry) }
    }

    @Test fun heldFileMeasurementsRejectReplacementDuringReadAndBoundDirectoryInventory() = root { root ->
        val file = File(root, "data").apply { writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() }) }
        val original = file.readBytes(); var replaced = false
        reject {
            p.measure(file, original.size.toLong()) {
                if (!replaced) {
                    replaced = true
                    val replacement = File(root, "replacement").apply { writeBytes(original) }
                    Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        assertTrue(replaced)
        p.requireInventory(root, setOf("data"))
        for (i in 1..100) File(root, "extra-$i").writeText("extra")
        reject { p.requireInventory(root, setOf("data")) }
        val link = File(root, "link"); Files.createSymbolicLink(link.toPath(), root.toPath())
        reject { p.requireInventory(link, setOf("data")) }
    }

    private fun elf(): ByteArray = ByteArray(1024).apply {
        byteArrayOf(127, 69, 76, 70, 2, 1, 1).copyInto(this)
        put16(this, 16, 2); put16(this, 18, 183); put32(this, 20, 1); put64(this, 24, 0x1200)
        put64(this, 32, 64); put16(this, 52, 64); put16(this, 54, 56); put16(this, 56, 3)
        put32(this, 64, 1); put32(this, 68, 5); put64(this, 80, 0x1000)
        put64(this, 96, 1024); put64(this, 104, 1024); put64(this, 112, 0x1000)
        val interpreter = "/lib/ld-linux-aarch64.so.1\u0000".toByteArray()
        put32(this, 120, 3); put64(this, 128, 256); put64(this, 152, interpreter.size.toLong()); put64(this, 160, interpreter.size.toLong())
        interpreter.copyInto(this, 256)
        put32(this, 176, 2); put64(this, 184, 320); put64(this, 208, 32); put64(this, 216, 32)
        // Deterministic incompressible-ish body keeps fixture safely within the expansion-ratio bound.
        for (i in 512 until size) this[i] = (i * 31 + i / 7).toByte()
    }

    private fun zip(bytes: ByteArray, method: Int = 0, descriptor: Int = 0, unixMode: Int = 0x81ed,
        name: String = p.COMMAND, extra: ByteArray = byteArrayOf(), comment: String = ""): ByteArray {
        val encoded = if (method == 0) bytes else {
            val deflater = Deflater(6, true); val out = ByteArrayOutputStream()
            try { deflater.setInput(bytes); deflater.finish(); val buffer = ByteArray(4096)
                while (!deflater.finished()) { val n = deflater.deflate(buffer); check(n > 0); out.write(buffer, 0, n) }
            } finally { deflater.end() }; out.toByteArray()
        }
        val crc = CRC32().apply { update(bytes) }.value; val nameBytes = name.toByteArray(); val flags = if (descriptor == 0) 0 else 8
        val version = if (method == 0) 10 else 20
        val local = ByteArray(30).apply {
            put32(this, 0, 0x04034b50); put16(this, 4, version); put16(this, 6, flags); put16(this, 8, method)
            if (descriptor == 0) { put32(this, 14, crc); put32(this, 18, encoded.size.toLong()); put32(this, 22, bytes.size.toLong()) }
            put16(this, 26, nameBytes.size); put16(this, 28, extra.size)
        }
        val dd = ByteArray(descriptor).apply { if (descriptor > 0) {
            val start = if (descriptor == 16) { put32(this, 0, 0x08074b50); 4 } else 0
            put32(this, start, crc); put32(this, start + 4, encoded.size.toLong()); put32(this, start + 8, bytes.size.toLong())
        } }
        val central = ByteArray(46).apply {
            put32(this, 0, 0x02014b50); put16(this, 4, 0x0314); put16(this, 6, version); put16(this, 8, flags); put16(this, 10, method)
            put32(this, 16, crc); put32(this, 20, encoded.size.toLong()); put32(this, 24, bytes.size.toLong())
            put16(this, 28, nameBytes.size); put16(this, 30, extra.size); put32(this, 38, unixMode.toLong() shl 16)
        }
        val start = local.size + nameBytes.size + extra.size + encoded.size + dd.size
        val ending = ByteArray(22).apply {
            put32(this, 0, 0x06054b50); put16(this, 8, 1); put16(this, 10, 1)
            put32(this, 12, (central.size + nameBytes.size + extra.size).toLong()); put32(this, 16, start.toLong()); put16(this, 20, comment.length)
        }
        return local + nameBytes + extra + encoded + dd + central + nameBytes + extra + ending + comment.toByteArray()
    }
    private fun centralOffset(bytes: ByteArray) = u32(bytes, bytes.size - 6).toInt()
    private fun u32(bytes: ByteArray, at: Int): Long = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(at).toLong() and 0xffffffffL
    private fun put16(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(at, value.toShort()) }
    private fun put32(bytes: ByteArray, at: Int, value: Long) { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value.toInt()) }
    private fun put64(bytes: ByteArray, at: Int, value: Long) { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(at, value) }
}
