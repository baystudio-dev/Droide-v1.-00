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
class LocalUbuntuMakePolicyTest {
    private val policy get() = LocalUbuntuMakePolicy
    private val definition get() = requireNotNull(LocalUbuntuVendorCatalog.find(policy.FAMILY))
    private val asset get() = """{"id":568317126,"state":"uploaded","name":"make-ls_linux_arm64.tar.gz","browser_download_url":"${policy.ASSET_URL}","size":2833278,"digest":"sha256:${policy.SHA256}"}"""
    private val metadata get() = """{"id":390059573,"url":"${policy.RELEASE_API_URL}","html_url":"${policy.PROVENANCE_URL}","tag_name":"v0.1.25","draft":false,"prerelease":false,"assets":[$asset]}"""
    private fun recipe() = policy.publisherRecipe(definition, metadata)
    private fun reject(block: () -> Unit) = assertTrue(runCatching(block).isFailure)
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("make-policy-").toFile()
        try { block(root) } finally { assertTrue(PathSecurity.deleteTreeNoFollow(root)) }
    }

    @Test fun exactRecipeCatalogAndBoundedDependencies() {
        val r = recipe(); r.validate()
        assertEquals("0.1.25", r.version); assertEquals("v0.1.25", r.releaseTag)
        assertEquals(policy.ASSET_URL, r.assetUrl); assertEquals(policy.SHA256, r.assetSha256)
        assertEquals(2_833_278L, r.assetSize); assertEquals("make-ls", r.command); assertEquals("make-ls", r.entryPoint)
        assertEquals(LocalUbuntuVendorFormat.TAR_GZ, definition.format)
        assertEquals(LocalUbuntuVendorAdmission.STATIC_ARM64, definition.admission)
        assertEquals(listOf("ca-certificates", "nodejs"), r.requiredPackages)
        assertTrue(definition.interpreter.isEmpty()); assertTrue(definition.healthArgs.isEmpty())
        assertTrue(definition.auxiliaryCommands.isEmpty()); assertTrue(definition.lsp)
        assertEquals(16, definition.maxFiles); assertEquals(67_108_864L, definition.maxUnpackedBytes)
        assertEquals(2_833_278L, definition.maxAssetBytes); assertEquals("0.1.25", definition.offeredVersion)
        assertFalse(definition.supportsPublisherVersionSelection)
    }

    @Test fun everyExactPublisherFieldUsesStrictScalarTypesAndIdentity() {
        val release = Json.parseToJsonElement(metadata).jsonObject
        val fields = listOf("id", "url", "html_url", "tag_name", "draft", "prerelease")
        for (key in fields) for (value in listOf<JsonElement>(JsonNull, JsonPrimitive(true), JsonPrimitive("false"),
            JsonPrimitive("390059573"), JsonPrimitive(390059574), JsonArray(emptyList()), JsonObject(emptyMap()))) {
            if (value == release[key]) continue
            reject { policy.publisherRecipe(definition, JsonObject(release.toMutableMap().apply { put(key, value) }).toString()) }
        }
        for (key in fields) reject { policy.publisherRecipe(definition, JsonObject(release - key).toString()) }
        val original = Json.parseToJsonElement(asset).jsonObject
        for (key in listOf("id", "state", "name", "browser_download_url", "size", "digest")) {
            val changes = listOf<JsonElement>(JsonNull, JsonPrimitive(false), JsonPrimitive("2833278"), JsonPrimitive(2833279),
                JsonPrimitive("sha256:${"a".repeat(64)}"), JsonArray(emptyList()), JsonObject(emptyMap()))
            for (value in changes) {
                val changed = JsonObject(original.toMutableMap().apply { put(key, value) })
                reject { policy.publisherRecipe(definition, JsonObject(release + ("assets" to JsonArray(listOf(changed)))).toString()) }
            }
            reject { policy.publisherRecipe(definition, JsonObject(release + ("assets" to JsonArray(listOf(JsonObject(original - key))))).toString()) }
        }
        for (body in listOf("{}", "[]", "null", "{", " ".repeat(1_000_001), metadata.replace("[$asset]", "[]"),
            metadata.replace("[$asset]", "[$asset,$asset]"), metadata.replace("[$asset]", "[$asset,1]"),
            metadata.replace("2833278", "2833278.0"), metadata.replace("390059573", "3.90059573e8"))) {
            reject { policy.publisherRecipe(definition, body) }
        }
    }

    @Test fun unrelatedMetadataMayChangeButLiteralRequestNeverSubstitutes() = runBlocking<Unit> {
        val extra = Json.parseToJsonElement(metadata).jsonObject + ("body" to JsonPrimitive("new release notes"))
        assertEquals(recipe(), policy.publisherRecipe(definition, JsonObject(extra).toString()))
        var calls = 0
        val resolver = LocalUbuntuVendorResolver { url -> calls++; assertEquals(policy.METADATA_URL, url); metadata }
        for (version in listOf("", "latest", "Latest official", "Reviewed Ubuntu release", "v0.1.25", "0.1.24", "0.1.26", "0.1.25-rc1")) {
            assertTrue(runCatching { resolver.resolve(definition, version) }.isFailure)
        }
        assertEquals(0, calls); assertEquals(recipe(), resolver.resolve(definition, "0.1.25")); assertEquals(1, calls)
    }

    @Test fun forgedRecipeOrDefinitionCannotAlterAnyPinnedIdentity() {
        val r = recipe()
        for (bad in listOf(r.copy(version = "0.1.26"), r.copy(releaseTag = "0.1.25"), r.copy(assetName = "other.tar.gz"),
            r.copy(assetUrl = "https://example.com/${policy.ASSET}"), r.copy(assetSha256 = "a".repeat(64)),
            r.copy(assetSize = null), r.copy(assetSize = 2_833_277L), r.copy(provenanceUrl = policy.METADATA_URL),
            r.copy(definition = definition.copy(packages = emptyList())), r.copy(definition = definition.copy(familyId = "lsp.make")))) {
            reject { bad.validate() }; reject { policy.requireReviewed(bad) }
        }
        reject { policy.publisherRecipe(definition.copy(maxFiles = 17), metadata) }
    }

    @Test fun exactNoticeTreeAdmitsOnlyThreeFlatRegularFiles() = withRoot { root ->
        val tree = File(root, "tree").apply { mkdir() }; val entry = populate(tree)
        policy.requireTree(entry); LocalUbuntuVendorPolicy.admit(recipe(), entry)
        for (name in listOf("extra", "nested/entry")) {
            val extra = File(tree, name).apply { parentFile.mkdirs(); writeText("extra") }
            reject { policy.requireTree(entry) }; assertTrue(PathSecurity.deleteTreeNoFollow(if ('/' in name) extra.parentFile else extra))
        }
        for (name in listOf("LICENSE", "README.md")) {
            val file = File(tree, name); val saved = file.readBytes()
            file.writeBytes(saved.copyOf(saved.size - 1)); reject { policy.requireTree(entry) }
            file.writeBytes(saved.mapIndexed { index, byte -> if (index == 0) (byte.toInt() xor 1).toByte() else byte }.toByteArray())
            reject { policy.requireTree(entry) }; file.delete(); reject { policy.requireTree(entry) }
            Files.createSymbolicLink(file.toPath(), File(root, "outside").apply { writeBytes(saved) }.toPath())
            reject { policy.requireTree(entry) }; file.delete(); file.writeBytes(saved)
        }
        val linkedRoot = File(root, "linked"); Files.createSymbolicLink(linkedRoot.toPath(), tree.toPath())
        reject { policy.requireTree(File(linkedRoot, "make-ls")) }
        reject { policy.requireTree(File(tree, "other")) }
        entry.delete(); entry.mkdir(); reject { policy.requireTree(entry) }
    }

    @Test fun staticGateRejectsDynamicTypeSegmentsAndMalformedElf() = withRoot { root ->
        val entry = File(root, "make-ls")
        entry.writeBytes(elf()); policy.requireStaticShape(entry)
        for (mutation in listOf<(ByteArray) -> Unit>(
            { it[4] = 1 }, { it[5] = 2 }, { it[6] = 0 }, { it[18] = 62 }, { it[16] = 3 },
            { putLong(it, 24, 0) }, { putLong(it, 24, 0x5000) }, { putLong(it, 32, Long.MAX_VALUE) },
            { it[54] = 55 }, { it[56] = 0 }, { putLong(it, 96, 4096) }, { putLong(it, 104, 1) },
            { putInt(it, 64, 2) }, { putInt(it, 64, 3) }, { putInt(it, 68, 0) })) {
            val bytes = elf(); mutation(bytes); entry.writeBytes(bytes); reject { policy.requireStaticShape(entry) }
        }
        for (kind in listOf(2, 3)) {
            val bytes = elf().copyOf(512); bytes[56] = 2; putInt(bytes, 120, kind)
            entry.writeBytes(bytes); reject { policy.requireStaticShape(entry) }
        }
        entry.writeBytes(elf().copyOf(80)); reject { policy.requireStaticShape(entry) }
    }

    @Test fun actualTarProjectionPreservesNoticesAndOnlyBinaryExecutable() = runBlocking<Unit> {
        withRoot { root ->
            val archive = File(root, "fixture.tar.gz"); archive.writeBytes(tar(listOf(
                Member("make-ls", elf()), Member("LICENSE", license), Member("README.md", readme))))
            runBlocking { LocalUbuntuVendorArchive.project(recipe(), archive, "/unused").use { projection ->
                assertTrue(projection.symlinks.isEmpty()); assertEquals(3, projection.files.size)
                assertEquals(493, projection.files.single { it.relativePath == "make-ls" }.mode)
                assertEquals(setOf(420), projection.files.filter { it.relativePath != "make-ls" }.map { it.mode }.toSet())
                LocalUbuntuVendorPolicy.admit(recipe(), File(projection.root, "make-ls"))
            } }
        }
    }

    @Test fun archiveRejectsLinksTraversalDuplicatesSpecialAndExcessiveHeaders() = runBlocking<Unit> {
        withRoot { root ->
            val base = listOf(Member("make-ls", elf()), Member("LICENSE", license), Member("README.md", readme))
            val cases = listOf(
                base + Member("link", byteArrayOf(), '2'), base + Member("hard", byteArrayOf(), '1'),
                base + Member("fifo", byteArrayOf(), '6'), base + Member("device", byteArrayOf(), '3'),
                base + Member("pax", byteArrayOf(), 'x'), base + Member("long", byteArrayOf(), 'L'),
                base + Member("../escape", byteArrayOf(1)), base + Member("/absolute", byteArrayOf(1)),
                base + Member("make-ls", byteArrayOf(1)), base + (1..33).map { Member("d$it/", byteArrayOf(), '5') })
            val archive = File(root, "bad.tar.gz")
            for (members in cases) {
                archive.writeBytes(tar(members)); assertTrue(runCatching { runBlocking {
                    LocalUbuntuVendorArchive.project(recipe(), archive, "/unused").close()
                } }.isFailure)
            }
            archive.writeBytes(tar(base).copyOf(25)); assertTrue(runCatching { runBlocking {
                LocalUbuntuVendorArchive.project(recipe(), archive, "/unused").close()
            } }.isFailure)
        }
    }

    @Test fun wrapperUsesSoleHealthSelectorZeroEditorArgumentsAndPreNodeScrub() {
        val r = recipe(); val root = "/opt/droide/packages/lsp.make-ls/0.1.25/arm64-v8a"
        val target = "$root/payload/tree/make-ls"; val probe = LocalUbuntuVendorPolicy.lspProbe(r, root, target)
        assertEquals(listOf(target), LocalUbuntuVendorPolicy.argv(r, target))
        assertEquals(listOf("--droide-health"), LocalUbuntuVendorPolicy.healthArgs(r))
        assertEquals(listOf("/bin/sh", "-c", LocalUbuntuMakeLspHealth.preNodeHandoff, "droide-make-env", "/usr/bin/node", "$root/make-lsp-health.cjs", target), probe)
        val wrapper = LocalUbuntuVendorPolicy.guestWrapper(r, target, root)
        assertTrue(wrapper.contains("[ \"${'$'}#\" -eq 0 ]")); assertTrue(wrapper.contains("[ \"${'$'}#\" -eq 1 ]"))
        assertFalse(wrapper.trimEnd().endsWith("\"${'$'}@\"")); assertTrue(wrapper.trimEnd().endsWith("exec '$target'"))
        assertEquals("make-lsp-health.cjs", LocalUbuntuVendorPolicy.lspHealthFileName(r))
        assertEquals(LocalUbuntuMakeLspHealth.script, LocalUbuntuVendorPolicy.lspHealthScript(r))
        assertTrue(LocalUbuntuMakeLspHealth.script.toByteArray().size in 1..65_536)
    }

    @Test fun targetAndHelperMustHaveSameExactFinalOrJournalStageRoot() {
        val stage = LocalPackageInstallJournal.paths("/opt/droide", policy.FAMILY, policy.VERSION,
            LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).stage.absolutePath
        for (root in listOf("/opt/droide/packages/lsp.make-ls/0.1.25/arm64-v8a", stage, "/space 'quote/packages/lsp.make-ls/0.1.25/arm64-v8a")) {
            LocalUbuntuMakeLspHealth.healthCommand("$root/make-lsp-health.cjs", "$root/payload/tree/make-ls")
        }
        val root = "/opt/droide/packages/lsp.make-ls/0.1.25/arm64-v8a"
        for (target in listOf("relative", "$root/payload/tree/not-make-ls", "$root/./payload/tree/make-ls", "$root//payload/tree/make-ls",
            "$root/payload/../tree/make-ls", "$root/payload/tree/make-ls\n", root.replace("0.1.25", "0.1.26") + "/payload/tree/make-ls",
            stage.replace("cd45e1bae123d94f96cd37a3", "0".repeat(24)) + "/payload/tree/make-ls")) {
            reject { LocalUbuntuMakeLspHealth.healthCommand("$root/make-lsp-health.cjs", target) }
        }
        reject { LocalUbuntuMakeLspHealth.healthCommand("/elsewhere/make-lsp-health.cjs", "$root/payload/tree/make-ls") }
        reject { LocalUbuntuMakeLspHealth.healthCommand("$root/other.cjs", "$root/payload/tree/make-ls") }
    }

    @Test fun receiptIntegrationRevalidatesNoticesShapeWrappersAndHealthBytes() = withRoot { app ->
        val r = recipe(); val final = File(app, "packages/${policy.FAMILY}/${policy.VERSION}/arm64-v8a").apply { mkdirs() }
        val tree = File(final, "payload/tree").apply { mkdirs() }; val entry = populate(tree)
        val guest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(final.absolutePath, "${app.absolutePath}/packages/"))
        val launcher = "${app.absolutePath}/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch"
        val wrapper = File(final, "payload/bin/make-ls").apply { parentFile.mkdirs(); writeText(LocalUbuntuVendorPolicy.guestWrapper(r, "$guest/payload/tree/make-ls", guest)) }
        File(final, "payload/host-bin/make-ls").apply { parentFile.mkdirs(); writeText(LocalUbuntuVendorPolicy.hostWrapper(launcher, "$guest/payload/bin/make-ls")) }
        val helper = File(final, "make-lsp-health.cjs").apply { writeText(LocalUbuntuMakeLspHealth.script) }
        val record = ManagedPackageRecord(policy.FAMILY, policy.VERSION, ExecutionScope.LOCAL_LINUX_ARM64.name, final.absolutePath, 0,
            pathEntries = listOf("${final.absolutePath}/payload/host-bin"), commands = mapOf("make-ls" to "${final.absolutePath}/payload/host-bin/make-ls"),
            healthChecks = listOf(ManagedPackageHealthCheck("host-bin/make-ls", listOf("--droide-health"))), artifactSha256 = policy.SHA256, abi = "arm64-v8a")
        assertTrue(LocalUbuntuVendorPolicy.verifyIntegration(record, r, app.absolutePath))
        for (file in listOf(File(tree, "LICENSE"), File(tree, "README.md"), wrapper, helper)) {
            val bytes = file.readBytes(); file.appendText("\n"); assertFalse(LocalUbuntuVendorPolicy.verifyIntegration(record, r, app.absolutePath)); file.writeBytes(bytes)
        }
        entry.writeBytes(elf().apply { putInt(this, 64, 2) }); assertFalse(LocalUbuntuVendorPolicy.verifyIntegration(record, r, app.absolutePath))
        entry.writeBytes(elf()); File(tree, "extra").writeText("extra"); assertFalse(LocalUbuntuVendorPolicy.verifyIntegration(record, r, app.absolutePath))
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
    private fun populate(root: File): File {
        File(root, "LICENSE").writeBytes(license); File(root, "README.md").writeBytes(readme)
        return File(root, "make-ls").apply { writeBytes(elf()) }
    }
    // Source notices from commit 793ab58a37a59768a042dff82c6bc6a2a15ae5bd. No archive payload bytes.
    private val license get() = Base64.getDecoder().decode("TUlUIExpY2Vuc2UKCkNvcHlyaWdodCAoYykgMjAyNiBPd2VuIFJ1bW5leQoKUGVybWlzc2lvbiBpcyBoZXJlYnkgZ3JhbnRlZCwgZnJlZSBvZiBjaGFyZ2UsIHRvIGFueSBwZXJzb24gb2J0YWluaW5nIGEgY29weQpvZiB0aGlzIHNvZnR3YXJlIGFuZCBhc3NvY2lhdGVkIGRvY3VtZW50YXRpb24gZmlsZXMgKHRoZSAiU29mdHdhcmUiKSwgdG8gZGVhbAppbiB0aGUgU29mdHdhcmUgd2l0aG91dCByZXN0cmljdGlvbiwgaW5jbHVkaW5nIHdpdGhvdXQgbGltaXRhdGlvbiB0aGUgcmlnaHRzCnRvIHVzZSwgY29weSwgbW9kaWZ5LCBtZXJnZSwgcHVibGlzaCwgZGlzdHJpYnV0ZSwgc3VibGljZW5zZSwgYW5kL29yIHNlbGwKY29waWVzIG9mIHRoZSBTb2Z0d2FyZSwgYW5kIHRvIHBlcm1pdCBwZXJzb25zIHRvIHdob20gdGhlIFNvZnR3YXJlIGlzCmZ1cm5pc2hlZCB0byBkbyBzbywgc3ViamVjdCB0byB0aGUgZm9sbG93aW5nIGNvbmRpdGlvbnM6CgpUaGUgYWJvdmUgY29weXJpZ2h0IG5vdGljZSBhbmQgdGhpcyBwZXJtaXNzaW9uIG5vdGljZSBzaGFsbCBiZSBpbmNsdWRlZCBpbiBhbGwKY29waWVzIG9yIHN1YnN0YW50aWFsIHBvcnRpb25zIG9mIHRoZSBTb2Z0d2FyZS4KClRIRSBTT0ZUV0FSRSBJUyBQUk9WSURFRCAiQVMgSVMiLCBXSVRIT1VUIFdBUlJBTlRZIE9GIEFOWSBLSU5ELCBFWFBSRVNTIE9SCklNUExJRUQsIElOQ0xVRElORyBCVVQgTk9UIExJTUlURUQgVE8gVEhFIFdBUlJBTlRJRVMgT0YgTUVSQ0hBTlRBQklMSVRZLApGSVRORVNTIEZPUiBBIFBBUlRJQ1VMQVIgUFVSUE9TRSBBTkQgTk9OSU5GUklOR0VNRU5ULiBJTiBOTyBFVkVOVCBTSEFMTCBUSEUKQVVUSE9SUyBPUiBDT1BZUklHSFQgSE9MREVSUyBCRSBMSUFCTEUgRk9SIEFOWSBDTEFJTSwgREFNQUdFUyBPUiBPVEhFUgpMSUFCSUxJVFksIFdIRVRIRVIgSU4gQU4gQUNUSU9OIE9GIENPTlRSQUNULCBUT1JUIE9SIE9USEVSV0lTRSwgQVJJU0lORyBGUk9NLApPVVQgT0YgT1IgSU4gQ09OTkVDVElPTiBXSVRIIFRIRSBTT0ZUV0FSRSBPUiBUSEUgVVNFIE9SIE9USEVSIERFQUxJTkdTIElOIFRIRQpTT0ZUV0FSRS4K")
    private val readme get() = Base64.getDecoder().decode("IyBtYWtlLWxzCgpBIGxhbmd1YWdlIHNlcnZlciBmb3IgTWFrZWZpbGVzLiBQcm92aWRlcyByZWFsIElERSBmZWF0dXJlcyDigJQgbm90IGp1c3Qgc3ludGF4IGhpZ2hsaWdodGluZy4KCiFbRGVtb10oLi8uZ2l0aHViL2ltYWdlcy9kZW1vLmdpZikKCiMjIEZlYXR1cmVzCgp8IEZlYXR1cmUgfCBXaGF0IGl0IGRvZXMgfAp8LS0tfC0tLXwKfCAqKkhvdmVyKiogfCBUYXJnZXQgZG9jcywgcmVjaXBlIHByZXZpZXcsIHZhcmlhYmxlIHZhbHVlcywgYXV0by12YXJpYWJsZSBkb2NzIChgJEBgLCBgJDxgLCAuLi4pLCBmdW5jdGlvbiBkb2NzIChgJCh3aWxkY2FyZCAuLi4pYCwgLi4uKSB8CnwgKipDb21wbGV0aW9uKiogfCBDb250ZXh0LWF3YXJlOiB2YXJpYWJsZXMgKyBmdW5jdGlvbnMgYWZ0ZXIgYCQoYCwgdGFyZ2V0cyBpbiBkZXAgbGlzdHMsIGF1dG8tdmFyaWFibGVzIGluIHJlY2lwZXMsIGRpcmVjdGl2ZXMgYXQgbGluZSBzdGFydCB8CnwgKipHbyB0byBEZWZpbml0aW9uKiogfCBKdW1wIGZyb20gZGVwIOKGkiB0YXJnZXQsIGAkKFZBUilgIOKGkiBhc3NpZ25tZW50LCBpbmNsdWRlIHBhdGgg4oaSIGZpbGUgfAp8ICoqRmluZCBSZWZlcmVuY2VzKiogfCBBbGwgZGVwcyByZWZlcmVuY2luZyBhIHRhcmdldCwgYWxsIGAkKFZBUilgIHVzYWdlcyBmb3IgYSB2YXJpYWJsZSB8CnwgKipEb2N1bWVudCBTeW1ib2xzKiogfCBUYXJnZXRzLCB2YXJpYWJsZXMsIGNvbmRpdGlvbmFscyDigJQgb3V0bGluZSB2aWV3IGFuZCBDbWQrU2hpZnQrTyB8CnwgKipEaWFnbm9zdGljcyoqIHwgVW5kZWZpbmVkIHRhcmdldHMsIHVuZGVmaW5lZCB2YXJpYWJsZXMgKHJlc3BlY3RzIGJ1aWx0aW5zLCByZWN1cnNpdmUgdmFycywgY29uZGl0aW9uYWxzKSwgbWlzc2luZyBgLlBIT05ZYCBoaW50cyB8CnwgKipDb2RlIEFjdGlvbnMqKiB8IFF1aWNrLWZpeCB0byBhZGQgYC5QSE9OWWAgZGVjbGFyYXRpb25zIHwKfCAqKkZvcm1hdHRpbmcqKiB8IFRhYnMgaW4gcmVjaXBlcywgdHJpbSB0cmFpbGluZyB3aGl0ZXNwYWNlLCBlbnN1cmUgZmluYWwgbmV3bGluZSB8CnwgKipJbmNsdWRlIFJlc29sdXRpb24qKiB8IEZvbGxvd3MgYGluY2x1ZGVgIC8gYC1pbmNsdWRlYCAvIGBzaW5jbHVkZWAgZGlyZWN0aXZlcywgbWVyZ2VzIHN5bWJvbHMgYWNyb3NzIGZpbGVzLiBJbmNsdWRlZCBmaWxlcyBtdXN0IHNpdCBpbnNpZGUgYW4gb3BlbiB3b3Jrc3BhY2UgZm9sZGVyOyBwYXRocyBvdXRzaWRlIGl0IGFyZSBpZ25vcmVkIHwKCiMjIFBhcnNlciBjb3ZlcmFnZQoKVGhlIGN1c3RvbSBwYXJzZXIgaGFuZGxlcyB0aGUgZnVsbCBicmVhZHRoIG9mIEdOVSBNYWtlIHN5bnRheDoKCi0gVmFyaWFibGUgYXNzaWdubWVudHMgKGA9YCBgOj1gIGA/PWAgYCs9YCBgIT1gKSB3aXRoIGZsYXZvdXIgdHJhY2tpbmcKLSBQYXR0ZXJuIHJ1bGVzIChgJS5vOiAlLmNgKSwgc3RhdGljIHBhdHRlcm4gcnVsZXMsIGRvdWJsZS1jb2xvbiBydWxlcwotIFRhcmdldC1zcGVjaWZpYyB2YXJpYWJsZXMgKGB0YXJnZXQ6IENDID0gY2xhbmdgKQotIE9yZGVyLW9ubHkgcHJlcmVxdWlzaXRlcyAoYHRhcmdldDogZGVwcyB8IG9yZGVyLW9ubHlgKQotIENvbmRpdGlvbmFsIGJsb2NrcyAoYGlmZXFgL2BpZm5lcWAvYGlmZGVmYC9gaWZuZGVmYCB3aXRoIG5lc3RpbmcpCi0gTXVsdGktbGluZSBgZGVmaW5lYC9gZW5kZWZgIGJsb2NrcwotIGBleHBvcnRgL2B1bmV4cG9ydGAvYG92ZXJyaWRlYC9gdnBhdGhgIGRpcmVjdGl2ZXMKLSBMaW5lIGNvbnRpbnVhdGlvbnMsIGRvYyBjb21tZW50cywgYC5QSE9OWWAKLSBQcmVjaXNlIHNvdXJjZSBwb3NpdGlvbnMgZm9yIGFsbCB0b2tlbnMKCiMjIFdoeT8KClRoZXJlJ3Mgbm8gcmVhbCBNYWtlZmlsZSBsYW5ndWFnZSBzZXJ2ZXIuIFRoZSBleGlzdGluZyBvcHRpb25zOgoKLSAqKk1pY3Jvc29mdCBNYWtlZmlsZSBUb29scyoqIChWUyBDb2RlKSDigJQgZm9jdXNlZCBvbiBidWlsZC9kZWJ1ZyBjb25maWd1cmF0aW9uLCBub3QgbGFuZ3VhZ2UgaW50ZWxsaWdlbmNlCi0gKipjaGVja21ha2UqKiDigJQgYSBzdGFuZGFsb25lIGxpbnRlciB3aXRoIHN0eWxlIHdhcm5pbmdzLCBubyBMU1AKLSAqKmVmbS1sYW5nc2VydmVyKiog4oCUIGdlbmVyaWMgd3JhcHBlciB0aGF0IGNhbiBzaGVsbCBvdXQgdG8gY2hlY2ttYWtlLCBidXQgbm8gaG92ZXIvY29tcGxldGlvbi9zeW1ib2xzCi0gKip0cmVlLXNpdHRlci1tYWtlKiog4oCUIHN5bnRheCBoaWdobGlnaHRpbmcgb25seQoKbWFrZS1scyBmaWxscyB0aGUgZ2FwIHdpdGggYWN0dWFsIExTUCBmZWF0dXJlczogaG92ZXIsIGNvbXBsZXRpb24sIGdvLXRvLWRlZmluaXRpb24sIHJlZmVyZW5jZXMsIGRpYWdub3N0aWNzLCBzeW1ib2xzLCBjb2RlIGFjdGlvbnMsIGFuZCBmb3JtYXR0aW5nLiBXb3JrcyBpbiBhbnkgZWRpdG9yIHRoYXQgc3BlYWtzIExTUC4KCiMjIEluc3RhbGwKCiMjIyBJbnN0YWxsIHdpdGggR28KCmBgYHNoCmdvIGluc3RhbGwgZ2l0aHViLmNvbS9vd2VucnVtbmV5L21ha2UtbHMvY21kL21ha2UtbHNAbGF0ZXN0CmBgYAoKTm93IHlvdSBjYW4gc2V0IHVwIE5lb3ZpbSB3aXRoIHRoZSBwYXRoIHNldCB0byBgJEdPUEFUSC9iaW4vbWFrZS1sc2AKCiMjIyBWUyBDb2RlCgpJbnN0YWxsIGZyb20gYSBgLnZzaXhgIGZpbGUgKHNlZSBbUmVsZWFzZXNdKGh0dHBzOi8vZ2l0aHViLmNvbS9vd2VucnVtbmV5L21ha2UtbHMvcmVsZWFzZXMpKToKCmBgYHNoCmNvZGUgLS1pbnN0YWxsLWV4dGVuc2lvbiBtYWtlLWxzLWRhcndpbi1hcm02NC0wLjEuMC52c2l4CmBgYAoKT3IgYnVpbGQgYW5kIGluc3RhbGwgbG9jYWxseToKCmBgYHNoCm1ha2UgZXh0ZW5zaW9uLWluc3RhbGwKYGBgCgpUbyBvdmVycmlkZSB0aGUgYnVuZGxlZCBzZXJ2ZXIgYmluYXJ5IGluIFZTIENvZGUsIHNldCBgbWFrZS1scy5zZXJ2ZXJQYXRoYCB0byB0aGUgYWJzb2x1dGUgcGF0aCBvZiB5b3VyIGBtYWtlLWxzYCBleGVjdXRhYmxlIGluIFNldHRpbmdzLgoKIyMjIE5lb3ZpbQoKTm8gZXhpc3RpbmcgTWFrZWZpbGUgTFNQIGlzIGF2YWlsYWJsZSBpbiBudmltLWxzcGNvbmZpZy4gVG8gdXNlIG1ha2UtbHMsIGFkZCBhIGN1c3RvbSBjb25maWc6CgpgYGBsdWEKLS0gSW4geW91ciBOZW92aW0gY29uZmlnIChpbml0Lmx1YSBvciBhZnRlci9mdHBsdWdpbi9tYWtlLmx1YSkKdmltLmFwaS5udmltX2NyZWF0ZV9hdXRvY21kKCJGaWxlVHlwZSIsIHsKICBwYXR0ZXJuID0gIm1ha2UiLAogIGNhbGxiYWNrID0gZnVuY3Rpb24oKQogICAgdmltLmxzcC5zdGFydCh7CiAgICAgIG5hbWUgPSAibWFrZS1scyIsCiAgICAgIGNtZCA9IHsgIi9wYXRoL3RvL21ha2UtbHMiIH0sCiAgICAgIHJvb3RfZGlyID0gdmltLmZzLnJvb3QoMCwgeyAiTWFrZWZpbGUiLCAibWFrZWZpbGUiLCAiR05VbWFrZWZpbGUiIH0pLAogICAgfSkKICBlbmQsCn0pCmBgYAoKVGhpcyBnaXZlcyB5b3UgaG92ZXIsIGNvbXBsZXRpb24sIGdvLXRvLWRlZmluaXRpb24sIHJlZmVyZW5jZXMsIGRpYWdub3N0aWNzLCBhbmQgc3ltYm9scyDigJQgbm9uZSBvZiB3aGljaCB3ZXJlIHByZXZpb3VzbHkgYXZhaWxhYmxlIGZvciBNYWtlZmlsZXMgaW4gTmVvdmltLgoKIyMjIEhlbGl4CgpBZGQgdG8gYGxhbmd1YWdlcy50b21sYDoKCmBgYHRvbWwKW1tsYW5ndWFnZV1dCm5hbWUgPSAibWFrZSIKbGFuZ3VhZ2Utc2VydmVycyA9IFsibWFrZS1scyJdCgpbbGFuZ3VhZ2Utc2VydmVyLm1ha2UtbHNdCmNvbW1hbmQgPSAiL3BhdGgvdG8vbWFrZS1scyIKYGBgCgojIyMgT3RoZXIgZWRpdG9ycwoKYG1ha2UtbHNgIGlzIGEgc3RhbmRhcmQgTFNQIHNlcnZlciBjb21tdW5pY2F0aW5nIG92ZXIgc3RkaW8uIEJ1aWxkIHRoZSBiaW5hcnkgYW5kIHBvaW50IHlvdXIgZWRpdG9yIGF0IGl0OgoKYGBgc2gKbWFrZSBidWlsZAojIGJpbmFyeSBpcyBhdCBiaW4vbWFrZS1scwpgYGAKCiMjIEJ1aWxkCgpSZXF1aXJlcyBHbyAxLjIzKyBhbmQgTm9kZSAxOCsuCgpgYGBzaAojIFJ1biB0ZXN0cwptYWtlIHRlc3QKCiMgQnVpbGQgYmluYXJ5Cm1ha2UgYnVpbGQKCiMgQnVpbGQgVlMgQ29kZSBleHRlbnNpb24gZm9yIGFsbCBwbGF0Zm9ybXMKbWFrZSBleHRlbnNpb24KCiMgQnVpbGQgZm9yIGEgc2luZ2xlIHBsYXRmb3JtCm1ha2UgZXh0ZW5zaW9uLXRhcmdldCBWU0NFX1RBUkdFVD1kYXJ3aW4tYXJtNjQKYGBgCgojIyMgU3VwcG9ydGVkIHBsYXRmb3JtcwoKfCBUYXJnZXQgfCBHT09TL0dPQVJDSCB8CnwtLS18LS0tfAp8IGBkYXJ3aW4teDY0YCB8IGRhcndpbi9hbWQ2NCB8CnwgYGRhcndpbi1hcm02NGAgfCBkYXJ3aW4vYXJtNjQgfAp8IGBsaW51eC14NjRgIHwgbGludXgvYW1kNjQgfAp8IGBsaW51eC1hcm02NGAgfCBsaW51eC9hcm02NCB8CgojIyBQcm9qZWN0IHN0cnVjdHVyZQoKYGBgCmNtZC9tYWtlLWxzLyAgICAgICAgICBFbnRyeSBwb2ludCDigJQgc3RkaW8gTFNQIHNlcnZlcgppbnRlcm5hbC8KICBtb2RlbC8gICAgICAgICAgICAgIEFTVCB0eXBlcyAoVGFyZ2V0LCBWYXJpYWJsZSwgQ29uZGl0aW9uYWwsIC4uLikKICBwYXJzZXIvICAgICAgICAgICAgIExpbmUtb3JpZW50ZWQgTWFrZWZpbGUgcGFyc2VyCiAgY29tcGxldGlvbi8gICAgICAgICBDb250ZXh0LWF3YXJlIGNvbXBsZXRpb24gKyBidWlsdGluIGRvY3MKICBhbmFseXNpcy8gICAgICAgICAgIERpYWdub3N0aWMgY2hlY2tzCiAgcmVzb2x2ZXIvICAgICAgICAgICBJbmNsdWRlIGZpbGUgcmVzb2x1dGlvbiArIG1vZGVsIG1lcmdpbmcKICBoYW5kbGVyLyAgICAgICAgICAgIExTUCBoYW5kbGVyICh3aXJlcyBldmVyeXRoaW5nIHRvZ2V0aGVyKQp2c2NvZGUtbWFrZS1scy8gICAgICAgVlMgQ29kZSBleHRlbnNpb24gKFR5cGVTY3JpcHQgdGhpbiBjbGllbnQpCmBgYAoKIyMgTGljZW5zZQoKTUlUCg==")
}
