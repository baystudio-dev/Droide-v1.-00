package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Authored metadata/TAR fixtures only. No publisher executable, XZ subprocess or project code is run. */
class LocalUbuntuZlsPolicyTest {
    private val family = "lsp.zls"
    private val version = "0.16.0"
    private val sha = "430cd293d201eb70ae2519dbc96c854bf8791b8df7fc9392e8d2dc9680a2bed7"
    private val url = "https://builds.zigtools.org/zls-aarch64-linux-0.16.0.tar.xz"
    private val index get() = """{"0.16.0":{"date":"2026-04-16","aarch64-linux":{"tarball":"$url","shasum":"$sha","size":"3814984"}},"0.15.1":{}}"""
    private suspend fun recipe() = LocalUbuntuVendorResolver { requested ->
        assertEquals("https://builds.zigtools.org/index.json", requested)
        index
    }.resolve(family, version)
    private fun extension() = ExtensionFamily(family, "ZLS", ExtensionCategory.LANGUAGE_SERVERS,
        "Zig language server", listOf(ExtensionVersion(version, installKind = ExtensionInstallKind.REVIEWED_RECIPE,
            provides = setOf("zls"))), "zig")

    @Test fun exactOriginalPublisherIndexRetainsReviewedPinAndCompleteTreeContract() = runBlocking<Unit> {
        val r = recipe()
        r.validate()
        assertEquals(url, r.assetUrl)
        assertEquals(version, r.releaseTag)
        assertEquals(sha, r.assetSha256)
        assertEquals(3_814_984L, r.assetSize)
        assertEquals("zls", r.entryPoint)
        assertEquals(LocalUbuntuVendorFormat.TAR_XZ, r.definition.format)
        assertEquals(LocalUbuntuVendorAdmission.STATIC_ARM64, r.definition.admission)
        assertEquals(32, r.definition.maxFiles)
        assertEquals(64L * 1024 * 1024, r.definition.maxUnpackedBytes)
        assertEquals(128L * 1024 * 1024, r.definition.maxAssetBytes)
        assertEquals(listOf("ca-certificates", "xz-utils", "nodejs"), r.requiredPackages)
        assertEquals(listOf("--version"), r.healthArgs)
        assertTrue(r.definition.interpreter.isEmpty())
        assertTrue(r.definition.auxiliaryCommands.isEmpty())
        assertTrue(r.definition.lsp)
        assertTrue(r.definition.supportsPublisherVersionSelection)
        assertEquals(LocalUbuntuVendorPolicy.VERSION_CHOICE, r.definition.offeredVersion)
    }

    @Test fun missingChangedMalformedAndWrongTargetMetadataCannotResolve() = runBlocking<Unit> {
        val bodies = listOf("[]", "{}", "null", "{", index.replace("aarch64-linux", "x86_64-linux"),
            index.replace("\"0.16.0\"", "\"0.17.0\""), index.replace(sha, "a".repeat(64)),
            index.replace(url, "https://github.com/zigtools/zls/releases/download/0.16.0/zls-aarch64-linux.tar.xz"),
            index.replace("3814984", "3814985"), index.replace("3814984", "03814984"),
            index.replace("\"3814984\"", "3814984"), index.replace("\"3814984\"", "null"),
            index.replace("\"shasum\":\"$sha\",", ""), index.replace("\"$sha\"", "{}"),
            index.replace("\"tarball\":\"$url\",", ""), " ".repeat(1_000_001))
        for (body in bodies) assertTrue(runCatching { LocalUbuntuVendorResolver { body }.resolve(family, version) }.isFailure)
    }

    @Test fun incompatibleCompilerLinesNeverFetchAndLatestSelectsCompatiblePublisherRelease() = runBlocking<Unit> {
        var calls = 0
        val resolver = LocalUbuntuVendorResolver { calls++; index }
        for (v in listOf("Reviewed Ubuntu release", "0.15.1", "0.17.0", "v0.16.0")) {
            assertTrue(runCatching { resolver.resolve(family, v) }.isFailure)
        }
        assertEquals(0, calls)
        assertEquals(version, resolver.resolve(family, "latest").version)
        assertEquals(1, calls)
    }

    @Test fun forgedReceiptRecipesCannotAlterAnyReviewedIdentity() = runBlocking<Unit> {
        val r = recipe()
        for (bad in listOf(r.copy(version = "0.16.1"), r.copy(releaseTag = "v0.16.0"),
            r.copy(assetSha256 = "a".repeat(64)), r.copy(assetSize = null), r.copy(assetSize = 3_814_983),
            r.copy(assetName = "zls-aarch64-linux.tar.xz"), r.copy(assetUrl = "https://example.com/zls.tar.xz"),
            r.copy(provenanceUrl = "https://ziglang.org/download/index.json"),
            r.copy(definition = r.definition.copy(packages = emptyList())),
            r.copy(definition = r.definition.copy(admission = LocalUbuntuVendorAdmission.GLIBC_ARM64)))) {
            assertTrue(runCatching { bad.validate() }.isFailure)
        }
    }

    @Test fun versionPickerAllowsPublisherPatchUpdatesButKeepsCompilerCompatibility() {
        val family = extension()
        val offered = ExtensionVersionState(family, family.versions.single(), ExtensionState.AVAILABLE, "reviewed")
        for (v in listOf(version, "latest", "0.16.1")) {
            val selected = requireNotNull(ToolVersionRequests.select(listOf(offered), family.id, v))
            assertEquals(ExtensionState.AVAILABLE, selected.state)
            assertEquals(ExtensionInstallKind.REVIEWED_RECIPE, selected.version.installKind)
        }
        for (v in listOf("0.15.1", "0.17.0", "99.0.0")) {
            val selected = requireNotNull(ToolVersionRequests.select(listOf(offered), family.id, v))
            assertEquals(ExtensionState.UNAVAILABLE, selected.state)
            assertEquals(v, selected.version.version)
        }
        for (v in listOf(version, "latest", "99.0.0"))
            assertTrue(runCatching { ToolUpdatePlanner.recipe(family, v, null) }.isFailure)
    }

    @Test fun existingZigToolchainStillHasIndependentPublisherVersionSelection() {
        val zig = requireNotNull(LocalUbuntuVendorCatalog.find("toolchain.zig"))
        assertTrue(zig.supportsPublisherVersionSelection)
        assertNull(zig.pinnedVersion)
        assertEquals("zig-linux-aarch64-0.14.0/zig", zig.entry("0.14.0"))
        assertEquals("zig-aarch64-linux-0.16.0/zig", zig.entry("0.16.0"))
        assertEquals(listOf("ca-certificates", "xz-utils"), zig.packages)
        assertEquals(listOf("version"), zig.healthArgs)
    }

    @Test fun missingFabricatedLinkedAndUnexpectedNoticesFailClosed() = withRoot { root ->
        val entry = File(root, "zls").apply { writeText("inert authored fixture") }
        assertTrue(runCatching { LocalUbuntuZlsPolicy.requireTree(entry) }.isFailure)
        File(root, "LICENSE").writeText(" ".repeat(1068))
        File(root, "README.md").writeText(" ".repeat(2846))
        assertTrue(runCatching { LocalUbuntuZlsPolicy.requireTree(entry) }.isFailure)
        assertTrue(File(root, "LICENSE").delete())
        Files.createSymbolicLink(File(root, "LICENSE").toPath(), File(root, "README.md").toPath())
        assertTrue(runCatching { LocalUbuntuZlsPolicy.requireTree(entry) }.isFailure)
        assertTrue(File(root, "LICENSE").delete())
        File(root, "LICENSE").mkdir()
        assertTrue(runCatching { LocalUbuntuZlsPolicy.requireTree(entry) }.isFailure)
        File(root, "unexpected").writeText("inert")
        assertTrue(runCatching { LocalUbuntuZlsPolicy.requireTree(entry) }.isFailure)
        assertTrue(runCatching { LocalUbuntuZlsPolicy.requireTree(File(root, "other")) }.isFailure)
    }

    @Test fun zlsDecodedTarRejectsLinksTraversalSpecialAndDuplicateEntriesAndCleansUp() = runBlocking<Unit> {
        val r = recipe()
        withRoot { root ->
            for ((name, type, target) in listOf(Triple("alias", '2', "zls"), Triple("alias", '1', "zls"),
                Triple("../escape", '0', ""), Triple("/absolute", '0', ""), Triple("fifo", '6', ""),
                Triple("zls", '0', ""))) {
                val tar = File(root, "authored.tar").apply { writeBytes(tar(
                    Member("zls", bytes = "inert fixture".toByteArray()), Member(name, type, target))) }
                assertTrue(runCatching { LocalUbuntuVendorArchive.projectDecodedTar(r, tar) }.isFailure)
                assertEquals(listOf("authored.tar"), root.listFiles()!!.map { it.name })
            }
        }
    }

    @Test fun legacyVendorTarDefaultStillPreservesSafeRelativeLinks() = withRoot { root ->
        val archive = File(root, "authored.tar").apply { writeBytes(tar(
            Member("real", bytes = "inert fixture".toByteArray()), Member("alias", '2', "real"))) }
        SafeWholeTreeArchive.projectVendorTar(archive, setOf("real"), 32, 1024).use {
            assertEquals(listOf("alias"), it.symlinks.map { link -> link.relativePath })
            assertEquals("inert fixture", it.files.single().file.readText())
        }
        assertEquals(listOf("authored.tar"), root.listFiles()!!.map { it.name })
    }

    private data class Member(val name: String, val type: Char = '0', val target: String = "", val bytes: ByteArray = byteArrayOf())
    private fun tar(vararg members: Member): ByteArray = ByteArrayOutputStream().use { output ->
        for (member in members) {
            val header = ByteArray(512)
            fun field(at: Int, value: String) { value.toByteArray(Charsets.US_ASCII).copyInto(header, at) }
            field(0, member.name)
            field(100, "0000644\u0000")
            field(124, member.bytes.size.toString(8).padStart(11, '0') + "\u0000")
            for (i in 148..155) header[i] = 32
            header[156] = member.type.code.toByte()
            field(157, member.target)
            field(257, "ustar\u0000")
            val checksum = header.sumOf { it.toInt() and 255 }
            field(148, checksum.toString(8).padStart(6, '0') + "\u0000 ")
            output.write(header)
            output.write(member.bytes)
            output.write(ByteArray((512 - member.bytes.size % 512) % 512))
        }
        output.write(ByteArray(1024))
        output.toByteArray()
    }
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("zls-policy-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
