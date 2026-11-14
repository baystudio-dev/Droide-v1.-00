package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalAgentWorkspaceMirrorTest {
    @Test fun localMirrorNeverCreatesCompatibilityManifest() = runBlocking {
        val root = Files.createTempDirectory("droide-no-guest-manifest-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "file").writeText("source")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            assertFalse(File(copy, ".droide-sync-manifest.tsv").exists())
            assertEquals(hash(File(source, "file")), mirror.baseline().getValue("file").sha256)
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertFalse(File(copy, ".droide-sync-manifest.tsv").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun emptyProjectHasAVerifiableNonemptyBaselineAndNoWarmMetadataRewrite() = runBlocking {
        val root = Files.createTempDirectory("droide-empty-baseline-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val copy = File(root, "mirror")
            LocalAgentWorkspaceMirror(source, copy).refresh()
            val baseline = File(root, ".mirror-sync-baseline.tsv")
            assertTrue(baseline.length() > 0)
            val attrs = Files.readAttributes(baseline.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            val restarted = LocalAgentWorkspaceMirror(source, copy)
            assertTrue(restarted.baseline().isEmpty())
            restarted.refresh()
            assertEquals(attrs.fileKey(), Files.readAttributes(baseline.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
            assertEquals(attrs.lastModifiedTime(), Files.getLastModifiedTime(baseline.toPath()))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun validLookingTruncatedRowsCannotPassEstablishedBaselineChecksum() = runBlocking {
        val root = Files.createTempDirectory("droide-truncated-valid-prefix-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "a").writeText("a")
            File(source, "b").writeText("b")
            val copy = File(root, "mirror")
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertTrue(File(copy, "b").delete())
            val baseline = File(root, ".mirror-sync-baseline.tsv")
            val original = baseline.readText().lines()
            assertTrue(original.size >= 3)
            baseline.writeText(original.take(2).joinToString("\n", postfix = "\n"))
            try { LocalAgentWorkspaceMirror(source, copy).refresh(); fail("Truncated valid-looking baseline was accepted") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("checksum")) }
            assertFalse(File(copy, "b").exists())
            assertEquals("b", File(source, "b").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun validLegacyHostBaselineCanBeUpgradedWithoutTrustingGuestManifest() = runBlocking {
        val root = Files.createTempDirectory("droide-legacy-host-baseline-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val file = File(source, "file").apply { writeText("initial") }
            val copy = File(root, "mirror")
            LocalAgentWorkspaceMirror(source, copy).refresh()
            val baseline = File(root, ".mirror-sync-baseline.tsv")
            baseline.writeText("${hash(file)}\t420\tfile\n")
            File(copy, ".droide-sync-manifest.tsv").writeText("foreign notes")
            file.writeText("source edit")
            val restarted = LocalAgentWorkspaceMirror(source, copy)
            restarted.refresh()
            assertEquals("source edit", File(copy, "file").readText())
            assertEquals("foreign notes", File(copy, ".droide-sync-manifest.tsv").readText())
            assertTrue(baseline.readText().startsWith("DROIDE-LOCAL-BASELINE-1\t"))
            assertEquals(hash(file), restarted.baseline().getValue("file").sha256)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun missingEstablishedBaselineCannotRecreatePendingDeletionAfterRestart() = runBlocking {
        val root = Files.createTempDirectory("droide-baseline-loss-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "file").writeText("source")
            val copy = File(root, "mirror")
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertTrue(File(copy, "file").delete())
            assertTrue(File(root, ".mirror-sync-baseline.tsv").delete())
            var rejected = false
            try { LocalAgentWorkspaceMirror(source, copy).refresh() }
            catch (_: IllegalStateException) { rejected = true }
            catch (_: IllegalArgumentException) { rejected = true }
            assertFalse("Lost baseline must not undo an unpublished CLI deletion", File(copy, "file").exists())
            assertTrue("Established metadata loss must fail closed", rejected)
            assertEquals("source", File(source, "file").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun zeroLengthEstablishedBaselineCannotBeAcceptedAsAnEmptyFirstSync() = runBlocking {
        val root = Files.createTempDirectory("droide-baseline-truncation-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "file").writeText("source")
            val copy = File(root, "mirror")
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertTrue(File(copy, "file").delete())
            File(root, ".mirror-sync-baseline.tsv").writeBytes(byteArrayOf())
            var rejected = false
            try { LocalAgentWorkspaceMirror(source, copy).refresh() }
            catch (_: IllegalStateException) { rejected = true }
            catch (_: IllegalArgumentException) { rejected = true }
            assertFalse("Truncated baseline must not undo an unpublished CLI deletion", File(copy, "file").exists())
            assertTrue("Truncated established metadata must fail closed", rejected)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun existingCompatibilitySnapshotIsNeverRefreshedOrReplaced() = runBlocking {
        val root = Files.createTempDirectory("droide-snapshot-preserved-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val file = File(source, "file").apply { writeText("before") }
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val snapshot = File(copy, ".droide-sync-manifest.tsv").apply { writeText(WorkspaceSyncManifest.serialize(mirror.baseline())) }
            val initial = snapshot.readBytes()
            val identity = Files.readAttributes(snapshot.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            file.writeText("after")
            mirror.refresh()
            assertArrayEquals(initial, snapshot.readBytes())
            assertEquals(identity.fileKey(), Files.readAttributes(snapshot.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
            assertEquals(identity.lastModifiedTime(), Files.getLastModifiedTime(snapshot.toPath()))
            assertEquals(hash(file), mirror.baseline().getValue("file").sha256)
            assertEquals("after", File(copy, "file").readText())
            assertTrue(mirror.metadataNotice.orEmpty().contains("preserved"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun deletedCompatibilitySnapshotIsNotRecreatedOnRefreshOrHostRecreation() = runBlocking {
        val root = Files.createTempDirectory("droide-snapshot-deleted-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "file").writeText("source")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val snapshot = File(copy, ".droide-sync-manifest.tsv").apply { writeText(WorkspaceSyncManifest.serialize(mirror.baseline())) }
            assertTrue(snapshot.delete())
            mirror.refresh()
            assertFalse(snapshot.exists())
            assertNull(mirror.metadataNotice)
            val recreated = LocalAgentWorkspaceMirror(source, copy)
            recreated.refresh()
            assertFalse(snapshot.exists())
            assertEquals(hash(File(source, "file")), recreated.baseline().getValue("file").sha256)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun compatibilityFileCreatedDuringInitialInspectionIsPreserved() = runBlocking {
        val root = Files.createTempDirectory("droide-snapshot-create-race-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val file = File(source, "file").apply { writeText("source") }
            val copy = File(root, "mirror")
            val snapshot = File(copy, ".droide-sync-manifest.tsv")
            var injected = false
            val mirror = LocalAgentWorkspaceMirror(source, copy, WorkspaceFileFingerprintCache(
                onHashRead = { path, _ ->
                    if (!injected && path == file.toPath()) {
                        snapshot.writeText("concurrent CLI metadata\n")
                        injected = true
                    }
                }))
            mirror.refresh()
            assertTrue(injected)
            assertEquals("concurrent CLI metadata\n", snapshot.readText())
            assertTrue(mirror.metadataNotice.orEmpty().contains("preserved"))
            assertEquals(hash(file), mirror.baseline().getValue("file").sha256)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun foreignCompatibilityManifestIsPreservedAndReportedAcrossRefreshAndRecreation() = runBlocking {
        val root = Files.createTempDirectory("droide-manifest-owner-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "file").writeText("source")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val metadata = File(copy, ".droide-sync-manifest.tsv")
            metadata.writeText("user diagnostic notes\n")
            File(source, "file").writeText("new source")
            mirror.refresh()
            assertEquals("user diagnostic notes\n", metadata.readText())
            assertTrue(mirror.metadataNotice.orEmpty().contains("preserved"))
            val recreated = LocalAgentWorkspaceMirror(source, copy)
            recreated.refresh()
            assertEquals("user diagnostic notes\n", metadata.readText())
            assertNotNull(recreated.metadataNotice)
            assertEquals("new source", File(copy, "file").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun vanishedPersistentMirrorIsNotRecreatedFromStaleBaseline() = runBlocking {
        val root = Files.createTempDirectory("droide-vanished-mirror-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "file").writeText("source")
            val copy = File(root, "mirror")
            LocalAgentWorkspaceMirror(source, copy).refresh()
            val retained = File(root, "retained")
            Files.move(copy.toPath(), retained.toPath())
            val recreated = LocalAgentWorkspaceMirror(source, copy)
            try { recreated.refresh(); fail("Missing execution workspace was recreated") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("disappeared")) }
            assertFalse(copy.exists())
            assertEquals("source", File(retained, "file").readText())
            assertTrue(File(root, ".mirror-sync-baseline.tsv").isFile)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun removedCanonicalRootIsNotCopiedFromAReplacement() = runBlocking {
        val root = Files.createTempDirectory("droide-replaced-source-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "file").writeText("original")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            Files.move(source.toPath(), File(root, "retained").toPath())
            assertTrue(source.mkdir())
            File(source, "file").writeText("foreign replacement")
            try { mirror.refresh(); fail("New canonical inode was silently accepted") }
            catch (_: IllegalStateException) { }
            assertEquals("original", File(copy, "file").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun sourceCanReplaceAFileWithDirectoryAndBack() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val item = File(source, "item").apply { writeText("first") }
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            assertTrue(item.delete()); assertTrue(item.mkdir())
            File(item, "child").writeText("second")
            mirror.refresh()
            assertEquals("second", File(copy, "item/child").readText())
            assertTrue(PathSecurity.deleteTreeNoFollow(item)); item.writeText("third")
            mirror.refresh()
            assertEquals("third", File(copy, "item").readText())
            assertEquals("third", item.readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun bothSidesReplacingFileWithTheSameDirectoryRetiresStaleFileBaseline() = runBlocking {
        val root = Files.createTempDirectory("droide-directory-agreement-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "item").writeText("old file")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            for (tree in listOf(source, copy)) {
                val item = File(tree, "item")
                assertTrue(item.delete()); assertTrue(item.mkdir())
                File(item, "child").writeText("same new work")
            }
            mirror.refresh()
            assertEquals(setOf("item/child"), mirror.baseline().keys)
            val baseline = File(root, ".mirror-sync-baseline.tsv")
            val bytes = baseline.readBytes()
            val attrs = Files.readAttributes(baseline.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            repeat(2) {
                val restarted = LocalAgentWorkspaceMirror(source, copy)
                restarted.refresh()
                assertEquals(setOf("item/child"), restarted.baseline().keys)
                assertArrayEquals(bytes, baseline.readBytes())
                assertEquals(attrs.fileKey(), Files.readAttributes(baseline.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
                assertEquals(attrs.lastModifiedTime(), Files.getLastModifiedTime(baseline.toPath()))
                assertEquals("same new work", File(source, "item/child").readText())
                assertEquals("same new work", File(copy, "item/child").readText())
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun bothSidesEmptyDirectoriesDoNotKeepAFileBaseline() = runBlocking {
        val root = Files.createTempDirectory("droide-empty-directory-agreement-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "item").writeText("old file")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            for (tree in listOf(source, copy)) {
                assertTrue(File(tree, "item").delete())
                assertTrue(File(tree, "item").mkdir())
            }
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertTrue("An agreed directory must not retain a historical regular-file baseline", mirror.baseline().isEmpty())
            assertTrue(File(source, "item").isDirectory)
            assertTrue(File(copy, "item").isDirectory)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun divergentChildrenSurviveDirectoryTypeConvergence() = runBlocking {
        val root = Files.createTempDirectory("droide-directory-child-conflict-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "item").writeText("old file")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            for (tree in listOf(source, copy)) {
                assertTrue(File(tree, "item").delete())
                assertTrue(File(tree, "item").mkdir())
            }
            File(source, "item/child").writeText("user content")
            File(copy, "item/child").writeText("CLI content")
            File(copy, "item/unpublished").writeText("CLI only")
            mirror.refresh()
            assertFalse("Directory agreement must retire only the old file entry", mirror.baseline().containsKey("item"))
            assertFalse(mirror.baseline().containsKey("item/child"))
            assertFalse(mirror.baseline().containsKey("item/unpublished"))
            assertEquals("user content", File(source, "item/child").readText())
            assertEquals("CLI content", File(copy, "item/child").readText())
            assertEquals("CLI only", File(copy, "item/unpublished").readText())
            assertFalse(File(source, "item/unpublished").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun lateDirectoryChangeCannotTurnPendingDeletionIntoANewSourceFileOnNextRefresh() = runBlocking {
        val root = Files.createTempDirectory("droide-directory-late-change-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val item = File(source, "item").apply { writeText("old file") }
            File(source, "zz.txt").writeText("old sibling")
            val copy = File(root, "mirror")
            var armed = false
            var reads = 0
            var changed = false
            val fingerprints = WorkspaceFileFingerprintCache(maxEntries = 0, onHashRead = { path, _ ->
                if (armed && path == File(copy, "zz.txt").toPath() && ++reads == 2) {
                    assertTrue(File(copy, "item").delete())
                    assertTrue(item.delete())
                    item.writeText("host file created during refresh")
                    changed = true
                }
            })
            val mirror = LocalAgentWorkspaceMirror(source, copy, fingerprints)
            mirror.refresh()
            for (tree in listOf(source, copy)) {
                assertTrue(File(tree, "item").delete()); assertTrue(File(tree, "item").mkdir())
            }
            File(source, "zz.txt").writeText("new sibling")
            armed = true
            mirror.refresh()
            assertTrue("Fixture must change the observed directories before metadata commit", changed)
            assertFalse(File(copy, "item").exists())
            assertEquals("host file created during refresh", item.readText())
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertFalse("Metadata retirement must not resurrect a concurrent CLI deletion", File(copy, "item").exists())
            assertEquals("host file created during refresh", item.readText())
            assertEquals("new sibling", File(copy, "zz.txt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun directoryAgreementMustNotAuthorizeReplacingALaterRecreatedCliFile() = runBlocking {
        val root = Files.createTempDirectory("droide-directory-baseline-reuse-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "item").writeText("old agreed bytes")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            for (tree in listOf(source, copy)) {
                assertTrue(File(tree, "item").delete()); assertTrue(File(tree, "item").mkdir())
            }
            mirror.refresh()
            assertTrue(File(source, "item").delete()); assertTrue(File(copy, "item").delete())
            File(source, "item").writeText("new host file")
            File(copy, "item").writeText("old agreed bytes")
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertEquals("The file was recreated after both sides became directories; an older file baseline cannot authorize replacing it",
                "old agreed bytes", File(copy, "item").readText())
            assertEquals("new host file", File(source, "item").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun nativeEditsStayInMirrorUntilApprovedReconciliation() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "main.kt").writeText("source")
            File(source, ".env").writeText("secret")
            File(source, "module/node_modules/dependency").apply { parentFile.mkdirs(); writeText("ignore") }
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val originalBase = mirror.baseline()
            assertFalse(File(copy, ".env").exists())
            assertFalse(File(copy, "module/node_modules").exists())
            File(copy, "main.kt").writeText("agent")
            File(copy, "unapproved.kt").writeText("agent-new")
            assertEquals("source", File(source, "main.kt").readText())
            mirror.refresh()
            assertEquals("agent", File(copy, "main.kt").readText())
            assertEquals("agent-new", File(copy, "unapproved.kt").readText())
            assertEquals(originalBase, mirror.baseline())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun versionOneAndTwoManifestsKeepCorrectSourceHashes() {
        val hash = "a".repeat(64)
        assertEquals(mapOf("main.kt" to hash), AgentWorkspacePathPolicy.manifest("$hash\tmain.kt\n"))
        assertEquals(mapOf("main.kt" to hash), AgentWorkspacePathPolicy.manifest("$hash\t493\tmain.kt\n"))
        assertTrue(AgentWorkspacePathPolicy.manifest("$hash\t420\t.env\n").isEmpty())
        assertFalse(AgentWorkspacePathPolicy.visible("module/node_modules/x"))
    }

    @Test fun checksumPathsKeepSpacesAndRejectMalformedOrDuplicatedRows() {
        val hash = "a".repeat(64)
        assertEquals(mapOf("file name " to hash), AgentWorkspacePathPolicy.hashListing("$hash  ./file name \n"))
        assertTrue(AgentWorkspacePathPolicy.hashListing("$hash  ./module/node_modules/x\n").isEmpty())
        for (listing in listOf("truncated row", "$hash  ./main\n$hash  ./main\n")) {
            try { AgentWorkspacePathPolicy.hashListing(listing); fail("Unsafe checksum listing accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unknownMirrorSymlinkIsPreservedAndRequiresRecoveryWithoutTouchingExternalFiles() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "dir/main").apply { parentFile.mkdirs(); writeText("source") }
            val outside = File(root, "outside").apply { mkdirs() }
            File(outside, "main").writeText("outside")
            val copy = File(root, "mirror").apply { mkdirs() }
            Files.createSymbolicLink(File(copy, "dir").toPath(), outside.toPath())
            try { LocalAgentWorkspaceMirror(source, copy).refresh(); fail("Unknown mirror ancestry was guessed") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("baseline is missing")) }
            assertEquals("outside", File(outside, "main").readText())
            assertTrue(Files.isSymbolicLink(File(copy, "dir").toPath()))
            assertEquals("source", File(source, "dir/main").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun cancellationCannotChangeAuthoritativeSource() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "main").writeText("source")
            val job = Job().apply { cancel() }
            try { withContext(job) { LocalAgentWorkspaceMirror(source, File(root, "mirror")).refresh() }; fail("Cancellation ignored") }
            catch (_: CancellationException) { }
            assertEquals("source", File(source, "main").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun failedTurnBinaryAndLargeApkOutputsSurviveRefreshAndHostRecreation() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "make.py").writeText("# generator")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            File(copy, "kucing.glb").writeBytes(byteArrayOf(0x67, 0x6c, 0x54, 0x46, 0, -1))
            File(copy, "preview.png").writeBytes(byteArrayOf(-119, 80, 78, 71, 0, -1))
            File(copy, "app.apk").outputStream().use { output ->
                val block = ByteArray(64 * 1024) { (it % 256).toByte() }
                repeat(48) { output.write(block) } // 3 MiB: previously rejected at 2 MB.
            }
            val expected = listOf("kucing.glb", "preview.png", "app.apk")
                .associateWith { hash(File(copy, it)) }
            mirror.refresh() // The same refresh runs after a failed or cancelled CLI turn.
            val recreated = LocalAgentWorkspaceMirror(source, copy)
            recreated.refresh()
            expected.forEach { (path, digest) ->
                assertEquals(path, digest, hash(File(copy, path)))
                assertFalse(File(source, path).exists())
                assertFalse(recreated.baseline().containsKey(path))
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun bothSidesDivergingKeepOriginalBaseAndBothVersions() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "asset.bin").writeBytes(byteArrayOf(0, 1, 2))
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val base = mirror.baseline()
            File(source, "asset.bin").writeBytes(byteArrayOf(0, 3, 4))
            File(copy, "asset.bin").writeBytes(byteArrayOf(0, 5, 6))
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertArrayEquals(byteArrayOf(0, 3, 4), File(source, "asset.bin").readBytes())
            assertArrayEquals(byteArrayOf(0, 5, 6), File(copy, "asset.bin").readBytes())
            assertEquals(base, mirror.baseline())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun pendingCliDeletionSurvivesRefreshAndConvergedDeletionClearsBase() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val original = File(source, "removed.txt").apply { writeText("original") }
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val base = mirror.baseline()
            assertTrue(File(copy, "removed.txt").delete())
            original.writeText("human edit while CLI deletion is pending")
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertFalse(File(copy, "removed.txt").exists())
            assertEquals(base, mirror.baseline())
            assertTrue(original.delete())
            mirror.refresh()
            assertFalse(mirror.baseline().containsKey("removed.txt"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun pendingCliDeletionSurvivesSourceDirectoryTransitionAndMirrorRecreation() = runBlocking {
        val root = Files.createTempDirectory("droide-delete-type-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val item = File(source, "item").apply { writeText("original") }
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val base = mirror.baseline()
            val metadata = File(root, ".mirror-sync-baseline.tsv")
            val metadataBytes = metadata.readBytes()
            val attrs = Files.readAttributes(metadata.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            assertTrue(File(copy, "item").delete())
            assertTrue(item.delete()); assertTrue(item.mkdir())
            File(item, "deep/child").apply { parentFile.mkdir(); writeText("new host work") }
            repeat(3) {
                val restarted = LocalAgentWorkspaceMirror(source, copy)
                restarted.refresh()
                assertFalse("Pending deletion must block the whole replacement directory", File(copy, "item").exists())
                assertEquals("new host work", File(item, "deep/child").readText())
                assertEquals(base, restarted.baseline())
                assertArrayEquals(metadataBytes, metadata.readBytes())
                assertEquals(attrs.fileKey(), Files.readAttributes(metadata.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
                assertEquals(attrs.lastModifiedTime(), Files.getLastModifiedTime(metadata.toPath()))
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun nestedPendingDeletionBlocksOnlyItsDirectoryTransition() = runBlocking {
        val root = Files.createTempDirectory("droide-delete-sibling-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val item = File(source, "nested/item").apply { parentFile.mkdir(); writeText("original") }
            val sibling = File(source, "nested/other").apply { writeText("old sibling") }
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val itemBase = mirror.baseline().getValue("nested/item")
            assertTrue(File(copy, "nested/item").delete())
            assertTrue(item.delete()); assertTrue(item.mkdir())
            File(item, "child").writeText("new host work")
            sibling.writeText("new sibling")
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertFalse(File(copy, "nested/item").exists())
            assertEquals("new host work", File(item, "child").readText())
            assertEquals("new sibling", File(copy, "nested/other").readText())
            assertEquals(itemBase, mirror.baseline().getValue("nested/item"))
            assertEquals(hash(sibling), mirror.baseline().getValue("nested/other").sha256)
            assertFalse(mirror.baseline().containsKey("nested/item/child"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun pendingDeletionRemainsAfterHostRestoresFileAndClearsWhenBothSidesRemoveIt() = runBlocking {
        val root = Files.createTempDirectory("droide-delete-converge-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val item = File(source, "item").apply { writeText("original") }
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val base = mirror.baseline()
            assertTrue(File(copy, "item").delete())
            assertTrue(item.delete()); assertTrue(item.mkdir())
            File(item, "child").writeText("new host work")
            mirror.refresh()
            assertTrue(File(item, "child").delete()); assertTrue(item.delete())
            item.writeText("original")
            val restarted = LocalAgentWorkspaceMirror(source, copy)
            restarted.refresh()
            assertFalse(File(copy, "item").exists())
            assertEquals(base, restarted.baseline())
            assertEquals("original", item.readText())
            assertTrue(item.delete())
            restarted.refresh()
            assertFalse(File(copy, "item").exists())
            assertTrue(restarted.baseline().isEmpty())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun sourceOnlyChangesUpdateCleanMirrorAndConvergenceAdvancesBase() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val original = File(source, "file").apply { writeText("initial") }
            File(source, "deleted").writeText("remove")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            original.writeText("new source")
            assertTrue(File(source, "deleted").delete())
            mirror.refresh()
            assertEquals("new source", File(copy, "file").readText())
            assertFalse(File(copy, "deleted").exists())
            File(copy, "file").writeText("CLI result")
            original.writeText("CLI result") // Successful guarded publication in the host.
            mirror.refresh()
            assertEquals(hash(original), mirror.baseline().getValue("file").sha256)
            original.writeText("next source edit")
            mirror.refresh()
            assertEquals("next source edit", File(copy, "file").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun unchangedRefreshDoesNotRewriteWorkingFiles() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "file").writeText("same")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val file = File(copy, "file").toPath()
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(123_000))
            val attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
            mirror.refresh()
            val after = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
            assertEquals(attributes.fileKey(), after.fileKey())
            assertEquals(attributes.lastModifiedTime(), after.lastModifiedTime())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun guestManifestCannotAuthorizeOverwritingPendingOutput() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "file").writeText("initial")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val base = mirror.baseline()
            File(copy, "file").writeText("pending")
            File(source, "file").writeText("user edit")
            File(copy, ".droide-sync-manifest.tsv").writeText("${hash(File(copy, "file"))}\t420\tfile\n")
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertEquals("pending", File(copy, "file").readText())
            assertEquals("user edit", File(source, "file").readText())
            assertEquals(base, mirror.baseline())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun legacyMirrorWithoutTrustedBaselineRequiresRecoveryAndRetainsEveryFile() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "file").writeText("user source")
            File(source, "new-source").writeText("new")
            val copy = File(root, "mirror").apply { mkdirs() }
            File(copy, "file").writeText("pending legacy edit")
            File(copy, "output.bin").writeBytes(byteArrayOf(0, -1))
            File(copy, ".droide-sync-manifest.tsv").writeText("${hash(File(source, "file"))}\t420\tfile\n")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            try { mirror.refresh(); fail("Untrusted legacy ancestry was guessed") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("baseline is missing")) }
            assertEquals("pending legacy edit", File(copy, "file").readText())
            assertArrayEquals(byteArrayOf(0, -1), File(copy, "output.bin").readBytes())
            assertFalse(File(copy, "new-source").exists())
            assertEquals("new", File(source, "new-source").readText())
            assertFalse(File(root, ".mirror-sync-baseline.tsv").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun corruptTrustedBaselineFailsBeforeChangingWorkingFiles() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "file").writeText("initial")
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            File(source, "file").writeText("changed")
            File(copy, "new.bin").writeBytes(byteArrayOf(0, -1))
            File(root, ".mirror-sync-baseline.tsv").writeText("truncated baseline")
            try { mirror.refresh(); fail("Corrupt trusted baseline accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals("initial", File(copy, "file").readText())
            assertArrayEquals(byteArrayOf(0, -1), File(copy, "new.bin").readBytes())
            assertEquals("changed", File(source, "file").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun sourceShapeChangeCannotEraseNewOrIgnoredWorkingChildren() = runBlocking {
        for (child in listOf("artifact.glb", "node_modules/dependency", ".env", "empty/")) {
            val root = Files.createTempDirectory("droide-agent").toFile()
            try {
                val source = File(root, "source").apply { mkdirs() }
                val sourceItem = File(source, "item").apply { mkdir() }
                File(sourceItem, "known").writeText("known")
                val copy = File(root, "mirror")
                val mirror = LocalAgentWorkspaceMirror(source, copy)
                mirror.refresh()
                val pending = File(copy, "item/$child")
                if (child.endsWith('/')) pending.mkdirs() else {
                    pending.parentFile.mkdirs(); pending.writeText("pending")
                }
                assertTrue(PathSecurity.deleteTreeNoFollow(sourceItem))
                sourceItem.writeText("source changed directory to file")
                mirror.refresh()
                assertTrue(File(copy, "item").isDirectory)
                assertTrue(pending.exists())
                if (pending.isFile) assertEquals("pending", pending.readText())
                assertEquals("source changed directory to file", sourceItem.readText())
            } finally { PathSecurity.deleteTreeNoFollow(root) }
        }
    }

    @Test fun ignoredDependencySymlinksAreNotTraversedOrRemoved() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            val outside = File(root, "outside").apply { mkdir() }
            File(outside, "file").writeText("outside")
            val link = File(copy, "node_modules/link").apply { parentFile.mkdirs() }.toPath()
            Files.createSymbolicLink(link, outside.toPath())
            mirror.refresh()
            assertTrue(Files.isSymbolicLink(link))
            assertEquals("outside", File(outside, "file").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun hash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
