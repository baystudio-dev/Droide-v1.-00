package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Authored metadata, inert ARM64 ELF/TAR bytes and exact retained source notices; never publisher execution. */
class LocalUbuntuSqruffPolicyTest {
    private val policy get() = LocalUbuntuSqruffPolicy
    private val definition get() = requireNotNull(LocalUbuntuVendorCatalog.find(policy.FAMILY))
    private val asset get() = """{"id":615913480,"state":"uploaded","name":"sqruff-linux-aarch64-musl.tar.gz","browser_download_url":"${policy.ASSET_URL}","size":8582177,"digest":"sha256:${policy.SHA256}"}"""
    private val metadata get() = """{"id":404863604,"url":"${policy.RELEASE_API_URL}","html_url":"${policy.PROVENANCE_URL}","tag_name":"v0.41.0","draft":false,"prerelease":false,"assets":[$asset]}"""
    private fun recipe() = policy.publisherRecipe(definition, metadata)
    private fun reject(block: () -> Unit) = assertTrue(runCatching(block).isFailure)
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("make-policy-").toFile()
        try { block(root) } finally { assertTrue(PathSecurity.deleteTreeNoFollow(root)) }
    }

    @Test fun exactRecipeCatalogAndBoundedDependencies() {
        val r = recipe(); r.validate()
        assertEquals("0.41.0", r.version); assertEquals("v0.41.0", r.releaseTag)
        assertEquals(policy.ASSET_URL, r.assetUrl); assertEquals(policy.SHA256, r.assetSha256)
        assertEquals(8_582_177L, r.assetSize); assertEquals("sqruff", r.command); assertEquals("sqruff", r.entryPoint)
        assertEquals(LocalUbuntuVendorFormat.TAR_GZ, definition.format)
        assertEquals(LocalUbuntuVendorAdmission.STATIC_ARM64, definition.admission)
        assertEquals(listOf("ca-certificates", "nodejs"), r.requiredPackages)
        assertTrue(definition.interpreter.isEmpty()); assertTrue(definition.healthArgs.isEmpty())
        assertTrue(definition.auxiliaryCommands.isEmpty()); assertTrue(definition.lsp)
        assertEquals(1, definition.maxFiles); assertEquals(67_108_864L, definition.maxUnpackedBytes)
        assertEquals(8_582_177L, definition.maxAssetBytes); assertEquals("0.41.0", definition.offeredVersion)
        assertFalse(definition.supportsPublisherVersionSelection)
    }

    @Test fun everyExactPublisherFieldUsesStrictScalarTypesAndIdentity() {
        val release = Json.parseToJsonElement(metadata).jsonObject
        val fields = listOf("id", "url", "html_url", "tag_name", "draft", "prerelease")
        for (key in fields) for (value in listOf<JsonElement>(JsonNull, JsonPrimitive(true), JsonPrimitive("false"),
            JsonPrimitive("404863604"), JsonPrimitive(404863605), JsonArray(emptyList()), JsonObject(emptyMap()))) {
            if (value == release[key]) continue
            reject { policy.publisherRecipe(definition, JsonObject(release.toMutableMap().apply { put(key, value) }).toString()) }
        }
        for (key in fields) reject { policy.publisherRecipe(definition, JsonObject(release - key).toString()) }
        val original = Json.parseToJsonElement(asset).jsonObject
        for (key in listOf("id", "state", "name", "browser_download_url", "size", "digest")) {
            val changes = listOf<JsonElement>(JsonNull, JsonPrimitive(false), JsonPrimitive("8582177"), JsonPrimitive(8582178),
                JsonPrimitive("sha256:${"a".repeat(64)}"), JsonArray(emptyList()), JsonObject(emptyMap()))
            for (value in changes) {
                val changed = JsonObject(original.toMutableMap().apply { put(key, value) })
                reject { policy.publisherRecipe(definition, JsonObject(release + ("assets" to JsonArray(listOf(changed)))).toString()) }
            }
            reject { policy.publisherRecipe(definition, JsonObject(release + ("assets" to JsonArray(listOf(JsonObject(original - key))))).toString()) }
        }
        for (body in listOf("{}", "[]", "null", "{", " ".repeat(1_000_001), metadata.replace("[$asset]", "[]"),
            metadata.replace("[$asset]", "[$asset,$asset]"), metadata.replace("[$asset]", "[$asset,1]"),
            metadata.replace("8582177", "8582177.0"), metadata.replace("404863604", "3.90059573e8"))) {
            reject { policy.publisherRecipe(definition, body) }
        }
    }

    @Test fun unrelatedMetadataMayChangeButLiteralRequestNeverSubstitutes() = runBlocking<Unit> {
        val extra = Json.parseToJsonElement(metadata).jsonObject + ("body" to JsonPrimitive("new release notes"))
        assertEquals(recipe(), policy.publisherRecipe(definition, JsonObject(extra).toString()))
        var calls = 0
        val resolver = LocalUbuntuVendorResolver { url -> calls++; assertEquals(policy.METADATA_URL, url); metadata }
        for (version in listOf("", "latest", "Latest official", "Reviewed Ubuntu release", "v0.41.0", "0.40.0", "0.42.0", "0.41.0-rc1")) {
            assertTrue(runCatching { resolver.resolve(definition, version) }.isFailure)
        }
        assertEquals(0, calls); assertEquals(recipe(), resolver.resolve(definition, "0.41.0")); assertEquals(1, calls)
    }

    @Test fun forgedRecipeOrDefinitionCannotAlterAnyPinnedIdentity() {
        val r = recipe()
        for (bad in listOf(r.copy(version = "0.42.0"), r.copy(releaseTag = "0.41.0"), r.copy(assetName = "other.tar.gz"),
            r.copy(assetUrl = "https://example.com/${policy.ASSET}"), r.copy(assetSha256 = "a".repeat(64)),
            r.copy(assetSize = null), r.copy(assetSize = 8_582_176L), r.copy(provenanceUrl = policy.METADATA_URL),
            r.copy(definition = definition.copy(packages = emptyList())), r.copy(definition = definition.copy(familyId = "lsp.make")))) {
            reject { bad.validate() }; reject { policy.requireReviewed(bad) }
        }
        reject { policy.publisherRecipe(definition.copy(maxFiles = 17), metadata) }
    }

    @Test fun exactSingleTreeRejectsAnyExtraOrLink() = withRoot { root ->
        val tree=File(root,"tree").apply{mkdir()};val entry=File(tree,"sqruff").apply{writeBytes(elf())}
        policy.requireTree(entry);LocalUbuntuVendorPolicy.admit(recipe(),entry)
        for(name in listOf("LICENSE","extra","nested")) { val extra=File(tree,name).apply{mkdir()};reject{policy.requireTree(entry)};extra.delete() }
        entry.delete();Files.createSymbolicLink(entry.toPath(),File(root,"outside").apply{writeBytes(elf())}.toPath());reject{policy.requireTree(entry)}
    }
    @Test fun exactArchiveAndStaticPieAreAdmittedWithoutExecuting() = withRoot { root ->
        val archive=File(root,"fixture.tar.gz");archive.writeBytes(tar(listOf(Member("sqruff",elf()))))
        runBlocking { LocalUbuntuVendorArchive.project(recipe(),archive,"/unused").use { projection ->
            assertEquals(1,projection.files.size);assertTrue(projection.symlinks.isEmpty());assertEquals(493,projection.files.single().mode)
            LocalUbuntuVendorPolicy.admit(recipe(),File(projection.root,"sqruff"))
        } }
        val entry=File(root,"sqruff");for(type in listOf(2,3)){val bytes=elf();bytes[16]=type.toByte();entry.writeBytes(bytes);LinuxArm64ElfAdmission.requireLinuxStatic(entry)}
        for(mutation in listOf<(ByteArray)->Unit>({it[4]=1},{it[5]=2},{it[18]=62},{putLong(it,24,0)},{putLong(it,24,99999)},{putLong(it,32,Long.MAX_VALUE)},{putInt(it,64,3)})) {
            val bytes=elf();mutation(bytes);entry.writeBytes(bytes);reject{LinuxArm64ElfAdmission.requireLinuxStatic(entry)}
        }
    }
    @Test fun archiveRejectsExtraDuplicateTraversalAndEveryLinkType() = withRoot { root ->
        val cases=listOf(listOf(Member("sqruff",elf()),Member("extra",byteArrayOf(1))),listOf(Member("sqruff",elf()),Member("sqruff",elf())),
            listOf(Member("prefix/sqruff",elf())),listOf(Member("../sqruff",elf())),listOf(Member("/sqruff",elf())),
            listOf(Member("C:\\sqruff",elf())),listOf(Member("other",elf()))) + listOf('1','2','3','4','5','6','x','g','L','K','S').map{listOf(Member("sqruff",byteArrayOf(),it))}
        val archive=File(root,"bad.tar.gz");for(members in cases){archive.writeBytes(tar(members));reject{runBlocking{LocalUbuntuVendorArchive.project(recipe(),archive,"/unused").close()}}}
        archive.writeBytes(tar(listOf(Member("sqruff",elf()))).copyOf(25));reject{runBlocking{LocalUbuntuVendorArchive.project(recipe(),archive,"/unused").close()}}
    }
    @Test fun wrapperForwardsExactlyOnceAndHealthIsPrivateOnly() {
        val r=recipe();val root="/opt/droide/packages/lsp.sqruff/0.41.0/arm64-v8a";val target="$root/payload/tree/sqruff"
        val wrapper=LocalUbuntuVendorPolicy.guestWrapper(r,target,root)
        assertEquals(listOf(target),LocalUbuntuVendorPolicy.argv(r,target));assertTrue(wrapper.trimEnd().endsWith("exec '$target' \"\$@\""))
        assertFalse(wrapper.contains("exec '$target' 'lsp'"));assertEquals(listOf("--droide-health"),LocalUbuntuVendorPolicy.healthArgs(r))
        val args=LocalUbuntuVendorPolicy.lspProbe(r,root,target);assertEquals(LocalUbuntuSqruffLspHealth.preNodeHandoff,args[2]);assertEquals(target,args.last())
        assertTrue(LocalUbuntuSqruffLspHealth.script.contains("phase = 'registration'"));assertTrue(LocalUbuntuSqruffLspHealth.script.contains("Method not supported by installer health client"))
    }
    private data class Member(val name: String, val bytes: ByteArray, val type: Char = '0')
    private fun tar(members: List<Member>): ByteArray {
        val raw = ByteArrayOutputStream()
        for (member in members) {
            val h = ByteArray(512)
            fun text(at: Int, size: Int, text: String) { text.toByteArray(Charsets.US_ASCII).copyInto(h, at, 0, minOf(size, text.length)) }
            fun octal(at: Int, size: Int, value: Int) = text(at, size, value.toString(8).padStart(size - 1, '0') + "\u0000")
            text(0, 100, member.name); octal(100, 8, 511); octal(124, 12, member.bytes.size)
            for (i in 148..155) h[i] = 32; h[156] = member.type.code.toByte(); text(157, 100, "make-ls")
            text(257, 6, "ustar\u0000"); text(263, 2, "00"); octal(148, 8, h.sumOf { it.toInt() and 255 })
            raw.write(h); raw.write(member.bytes); raw.write(ByteArray((512 - member.bytes.size % 512) % 512))
        }
        raw.write(ByteArray(1024))
        val compressed = ByteArrayOutputStream(); GZIPOutputStream(compressed).use { it.write(raw.toByteArray()) }; return compressed.toByteArray()
    }
    private fun putInt(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value) }
    private fun putLong(bytes: ByteArray, at: Int, value: Long) { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(at, value) }
    private fun elf(): ByteArray = ByteArray(512).apply {
        byteArrayOf(127, 69, 76, 70, 2, 1, 1).copyInto(this); this[16] = 2; this[18] = 183.toByte()
        putInt(this, 20, 1); putLong(this, 24, 0x1080); putLong(this, 32, 64)
        this[52] = 64; this[54] = 56; this[56] = 1
        putInt(this, 64, 1); putInt(this, 68, 5); putLong(this, 80, 0x1000); putLong(this, 96, 512); putLong(this, 104, 512)
    }

}
