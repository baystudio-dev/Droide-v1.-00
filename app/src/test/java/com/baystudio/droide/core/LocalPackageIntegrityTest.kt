package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalPackageIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun member(root: File, path: String, value: String): File = File(root, path).apply {
        parentFile!!.mkdirs(); writeText(value)
    }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Test fun nodeSizedTreeIsVerifiedWithoutLaunchingThousandsOfShellProcesses() = runBlocking {
        val root = temporary.newFolder("node")
        repeat(8_192) { member(root, "include/node/part-$it.h", "header $it") }
        val progress = mutableListOf<String>()
        val seal = LocalPackageIntegrity.seal(root, 8_192, 2_000_000, progress::add)
        assertEquals(64, seal.length)
        assertTrue(progress.last().contains("8192/8192"))
        assertTrue(LocalPackageIntegrity.verify(root, File(root, "SHA256SUMS"), exactInventory = true))
        member(root, "include/node/part-4000.h", "tampered!")
        assertFalse(LocalPackageIntegrity.verify(root, File(root, "SHA256SUMS")))
    }

    @Test fun transferManifestOutsideTreeRequiresExplicitOwnedStagingRoot() = runBlocking {
        val stage = temporary.newFolder("stage")
        val tree = File(stage, "payload/tree").apply { mkdirs() }
        member(tree, "bin/node", "binary")
        val manifest = member(stage, "TREE_SHA256SUMS", "${sha("binary")}  bin/node\n")
        assertFalse(LocalPackageIntegrity.verify(tree, manifest, exactInventory = true))
        assertTrue(LocalPackageIntegrity.verify(tree, manifest, exactInventory = true, manifestRoot = stage))
        member(tree, "unexpected", "extra")
        assertFalse(LocalPackageIntegrity.verify(tree, manifest, exactInventory = true, manifestRoot = stage))
        assertFalse(LocalPackageIntegrity.verify(tree, manifest, manifestRoot = temporary.newFolder("foreign")))
    }

    @Test fun nestedChecksumFilesSpacesAndUnicodeAreIncludedInTheSeal() = runBlocking {
        val root = temporary.newFolder("package")
        member(root, "nested/SHA256SUMS", "vendor checksum")
        member(root, "nested/kode sumber 日本.js", "console.log('ok')")
        LocalPackageIntegrity.seal(root)
        assertTrue(File(root, "SHA256SUMS").readText().contains("nested/SHA256SUMS"))
        assertTrue(LocalPackageIntegrity.verify(root, File(root, "SHA256SUMS"), exactInventory = true))
        member(root, "nested/SHA256SUMS", "forged checksum")
        assertFalse(LocalPackageIntegrity.verify(root, File(root, "SHA256SUMS")))
    }

    @Test fun existingGnuTextAndBinaryManifestsRemainCompatible() = runBlocking {
        val root = temporary.newFolder("gnu")
        member(root, "a", "first"); member(root, "b", "second")
        val manifest = member(root, "SHA256SUMS", "${sha("first").uppercase()}  ./a\n${sha("second")} *b\n")
        assertTrue(LocalPackageIntegrity.verify(root, manifest, exactInventory = true))
        File(root, "b").delete()
        assertFalse(LocalPackageIntegrity.verify(root, manifest))
    }

    @Test fun tamperingWithRestoredSizeAndTimestampIsStillDetected() = runBlocking {
        val root = temporary.newFolder("tamper")
        val file = member(root, "binary", "original")
        LocalPackageIntegrity.seal(root)
        val timestamp = file.lastModified()
        file.writeText("modified"); file.setLastModified(timestamp)
        assertFalse(LocalPackageIntegrity.verify(root, File(root, "SHA256SUMS")))
    }

    @Test fun linksTraversalDuplicatesAndSelfChecksumsCannotBecomeTrustedFiles() = runBlocking {
        val root = temporary.newFolder("unsafe")
        val outside = temporary.newFile("outside").apply { writeText("value") }
        member(root, "safe", "value")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        val manifest = File(root, "SHA256SUMS")
        for (path in listOf("../outside", "link", "/outside", "nested/../safe", "safe\\file", "SHA256SUMS")) {
            manifest.writeText("${sha("value")}  $path\n")
            assertFalse(path, LocalPackageIntegrity.verify(root, manifest))
        }
        manifest.writeText("${sha("value")}  safe\n${sha("value")}  ./safe\n")
        assertFalse(LocalPackageIntegrity.verify(root, manifest))
        manifest.delete()
        Files.createSymbolicLink(manifest.toPath(), outside.toPath())
        assertFalse(LocalPackageIntegrity.verify(root, manifest))
    }

    @Test fun trustedAndroidStyleAncestorAliasDoesNotInvalidateARealPackage() = runBlocking {
        val real = temporary.newFolder("real")
        val root = File(real, "packages/runtime").apply { mkdirs() }
        member(root, "node", "valid")
        val alias = File(temporary.root, "data-alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val aliasedRoot = File(alias, "packages/runtime")
        LocalPackageIntegrity.seal(aliasedRoot)
        assertTrue(LocalPackageIntegrity.verify(aliasedRoot, File(aliasedRoot, "SHA256SUMS"), exactInventory = true))
        Files.createSymbolicLink(File(root, "nested").toPath(), real.toPath())
        val manifest = member(root, "SHA256SUMS", "${sha("valid")}  nested/packages/runtime/node\n")
        assertFalse(LocalPackageIntegrity.verify(root, manifest))
    }

    @Test fun cancellationIsNotReportedAsCorruptionAndReviewedBoundsAreEnforced() = runBlocking {
        val root = temporary.newFolder("cancel")
        member(root, "a", "data"); member(root, "b", "data")
        LocalPackageIntegrity.seal(root)
        try {
            LocalPackageIntegrity.verify(root, File(root, "SHA256SUMS"), progress = { throw CancellationException("stop verification") })
            fail("Cancellation was swallowed")
        } catch (expected: CancellationException) { assertEquals("stop verification", expected.message) }
        for ((files, bytes) in listOf(1 to 100L, 2 to 1L)) {
            try { LocalPackageIntegrity.seal(root, files, bytes); fail("Bounds ignored") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
