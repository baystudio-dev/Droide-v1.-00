package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Read-only old-format fixtures with original binary sentinels and exact namespace snapshots. */
class SafLegacyAdmissionTest {
    private val roots = arrayListOf<File>()
    private val original = byteArrayOf(0, 1, -1, 127, 10, 42)
    private val intended = byteArrayOf(90, 0, -8, 11)
    private val tree = "content://droide.legacy/tree/root"

    @After fun cleanup() { roots.forEach { PathSecurity.deleteTreeNoFollow(it) } }

    @Test(timeout = 30_000) fun absentEvidenceAllowsScopedAdmissionWithoutWriting() {
        val f = Fixture()
        val before = f.snapshot()
        val admitted = f.reader().inspect()
        assertEquals(SafLegacyManifestState.ABSENT, admitted.manifestState)
        assertTrue(admitted.blockers.isEmpty())
        admitted.revalidateAndRequireDisjoint(f.workspace, listOf("new/file")) {}
        f.assertSnapshot(before)
        assertEquals(0, f.forceOrMoveCalls)
    }

    @Test(timeout = 30_000) fun matchingPreviousOrIntendedManifestNeverClearsEvidence() {
        for (visible in listOf(original, intended)) {
            val f = Fixture()
            f.manifest("""{"version":2,"entries":{"file":"${fingerprint(visible)}","other":"d"}}""")
            val entry = f.pending("file", original)
            val before = f.snapshot()
            val admitted = f.reader().inspect()
            assertEquals(SafLegacyManifestState.JSON_V2, admitted.manifestState)
            assertEquals(listOf("file"), admitted.blockers.single().scopes)
            assertEquals(SafLegacyBlockReason.PENDING_WRITE, admitted.blockers.single().reason)
            assertEquals(fingerprint(visible), admitted.baselineEntries["file"])
            assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("file/child")) {} }.isFailure)
            admitted.revalidateAndRequireDisjoint(f.workspace, listOf("other")) {}
            assertArrayEquals(original, entry.resolve("previous").readBytes())
            assertArrayEquals(intended, entry.resolve("intended").readBytes())
            f.assertSnapshot(before)
            assertEquals(0, f.forceOrMoveCalls)
        }
    }

    @Test(timeout = 30_000) fun possibleCreationBlocksItsParentNamespaceIncludingTopLevelRoot() {
        for ((path, expected) in listOf("folder/new" to "folder", "new" to "")) {
            val f = Fixture()
            f.pending(path, null)
            val before = f.snapshot()
            val admitted = f.reader().inspect()
            assertEquals(SafLegacyBlockReason.POSSIBLE_CREATION, admitted.blockers.single().reason)
            assertEquals(listOf(expected), admitted.blockers.single().scopes)
            assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf(if (expected.isEmpty()) "other" else "folder/sibling")) {} }.isFailure)
            if (expected.isNotEmpty()) admitted.revalidateAndRequireDisjoint(f.workspace, listOf("other")) {}
            f.assertSnapshot(before)
        }
    }

    @Test(timeout = 30_000) fun orphanMalformedAndUnexpectedEntriesBlockRootWithoutCleanup() {
        for (kind in listOf("orphan", "corrupt", "unknown", "missing", "foreign-name")) {
            val f = Fixture()
            val entry = f.pending("file", original)
            when (kind) {
                "orphan" -> entry.resolve("pending.json").delete()
                "corrupt" -> entry.resolve("pending.json").writeText("{")
                "unknown" -> entry.resolve("unexpected").writeBytes(original)
                "missing" -> entry.resolve("intended").delete()
                "foreign-name" -> entry.renameTo(entry.parentFile!!.resolve("not-an-operation-id"))
            }
            val before = f.snapshot()
            val admitted = f.reader().inspect()
            f.assertRootBlock(admitted)
            f.assertSnapshot(before)
            assertEquals(0, f.forceOrMoveCalls)
        }
    }

    @Test(timeout = 30_000) fun badPayloadHashAndUnknownPreviousBytesStayRetained() {
        val f = Fixture()
        val entry = f.pending("file", original)
        entry.resolve("previous").writeBytes(intended)
        val before = f.snapshot()
        f.assertRootBlock(f.reader().inspect())
        assertArrayEquals(intended, entry.resolve("previous").readBytes())
        f.assertSnapshot(before)
    }

    @Test(timeout = 30_000) fun corruptJsonNeverFallsBackToAPathListOrEmptyTrust() {
        for (raw in listOf("{", """{"version":99,"entries":{}}""", """{"entries":{},"entries":{}}""")) {
            val f = Fixture()
            f.manifest(raw)
            f.workspace.resolve(".droide/saf_manifest.txt").writeText("original\n")
            val before = f.snapshot()
            val admitted = f.reader().inspect()
            assertEquals(SafLegacyManifestState.INVALID, admitted.manifestState)
            assertFalse(admitted.requiresFullRefreshMigration)
            f.assertRootBlock(admitted)
            f.assertSnapshot(before)
        }
    }

    @Test(timeout = 30_000) fun genuinePathListRequiresExplicitMigrationAndPreservesExactBytes() {
        val f = Fixture()
        val raw = "folder/\nfolder/original.bin\n".toByteArray()
        val pathList = f.workspace.resolve(".droide/saf_manifest.txt").apply { parentFile!!.mkdirs(); writeBytes(raw) }
        val before = f.snapshot()
        val admitted = f.reader().inspect()
        assertEquals(SafLegacyManifestState.PATH_LIST, admitted.manifestState)
        assertTrue(admitted.requiresFullRefreshMigration)
        assertTrue(admitted.baselineEntries.isEmpty())
        assertEquals(SafLegacyBlockReason.MIGRATION, admitted.blockers.single().reason)
        assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("new")) {} }.isFailure)
        assertArrayEquals(raw, pathList.readBytes())
        f.assertSnapshot(before)
    }

    @Test(timeout = 30_000) fun oldWriterOmittedDefaultsAndExplicitVersionBothAdmitWithoutTrustUpgrade() {
        for (raw in listOf("""{"entries":{"file":"${fingerprint(original)}"}}""", """{"version":2,"entries":{"file":"${fingerprint(original)}"}}""")) {
            val f = Fixture()
            f.manifest(raw)
            val before = f.snapshot()
            val admitted = f.reader().inspect()
            assertEquals(SafLegacyManifestState.JSON_V2, admitted.manifestState)
            assertTrue(admitted.blockers.isEmpty())
            assertEquals(fingerprint(original), admitted.baselineEntries["file"])
            assertTrue(runCatching { (admitted.baselineEntries as MutableMap)["file"] = "d" }.isFailure)
            f.assertSnapshot(before)
        }
    }

    @Test(timeout = 30_000) fun activationMarkerBlocksOnlyCapturedRootsAndNeverTouchesNamedSiblings() {
        val f = Fixture()
        val stage = f.root.resolve("retained-stage").apply { mkdir(); resolve("sentinel").writeBytes(original) }
        val backup = f.root.resolve("retained-backup").apply { mkdir(); resolve("sentinel").writeBytes(intended) }
        f.marker("""{"roots":["package"],"stage":"${stage.name}","backup":"${backup.name}"}""")
        val before = f.snapshot()
        val admitted = f.reader().inspect()
        assertEquals(SafLegacyBlockReason.ACTIVATION, admitted.blockers.single().reason)
        assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("package/deep")) {} }.isFailure)
        admitted.revalidateAndRequireDisjoint(f.workspace, listOf("unrelated")) {}
        assertArrayEquals(original, stage.resolve("sentinel").readBytes())
        assertArrayEquals(intended, backup.resolve("sentinel").readBytes())
        f.assertSnapshot(before)
    }

    @Test(timeout = 30_000) fun malformedUnknownOrAliasedActivationIsRootBlocking() {
        for (mode in listOf("corrupt", "version", "alias", "metadata-alias")) {
            val f = Fixture()
            val valid = """{"roots":["package"],"stage":"stage","backup":"backup"}"""
            when (mode) {
                "corrupt" -> f.marker("{")
                "version" -> f.marker("""{"version":99,"roots":["package"],"stage":"stage","backup":"backup"}""")
                "alias" -> {
                    f.workspace.resolve(".droide").mkdir()
                    f.workspace.resolve("actual").writeText(valid)
                    Files.createSymbolicLink(f.workspace.resolve(".droide/saf_activation_pending.json").toPath(), java.nio.file.Paths.get("../actual"))
                }
                "metadata-alias" -> {
                    val actual = f.workspace.resolve("actual").apply { mkdir(); resolve("saf_activation_pending.json").writeText(valid) }
                    Files.createSymbolicLink(f.workspace.resolve(".droide").toPath(), actual.toPath())
                }
            }
            val before = f.snapshot()
            f.assertRootBlock(f.reader().inspect())
            f.assertSnapshot(before)
        }
    }

    @Test(timeout = 30_000) fun inRootAliasesCannotBypassScopedLegacyBlocks() {
        val f = Fixture()
        f.pending("package/file", original)
        Files.createSymbolicLink(f.workspace.resolve("alias").toPath(), java.nio.file.Paths.get("package"))
        val before = f.snapshot()
        val admitted = f.reader().inspect()
        assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("alias/file")) {} }.isFailure)
        assertTrue(Files.isSymbolicLink(f.workspace.resolve("alias").toPath()))
        f.assertSnapshot(before)
    }

    @Test(timeout = 30_000) fun injectedSmallCapacitiesFailClosedWithoutEviction() {
        for (limits in listOf(SafLegacyLimits(entries = 2), SafLegacyLimits(recordBytes = 8), SafLegacyLimits(payloadBytes = 1))) {
            val f = Fixture()
            f.pending("file", original)
            val before = f.snapshot()
            f.assertRootBlock(f.reader(limits).inspect())
            f.assertSnapshot(before)
            assertEquals(0, f.forceOrMoveCalls)
        }
    }

    @Test(timeout = 30_000) fun cancellationDuringPayloadValidationPropagatesAndRetainsEveryByte() {
        val f = Fixture()
        f.pending("file", original)
        var cancelled = false
        f.beforeRead = { if (it.name == "previous") cancelled = true }
        val before = f.snapshot()
        val failure = runCatching { f.reader().inspect { if (cancelled) throw CancellationException("cancel legacy read") } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        f.assertSnapshot(before)
        assertEquals(0, f.forceOrMoveCalls)
    }

    @Test(timeout = 30_000) fun sourceExchangeDuringInspectionCannotBeAdoptedByPath() {
        val f = Fixture()
        val entry = f.pending("file", original)
        var reads = 0
        f.beforeRead = { if (it.name == "previous" && ++reads == 2) it.writeBytes(intended) }
        val admitted = f.reader().inspect()
        f.assertRootBlock(admitted)
        assertArrayEquals(intended, entry.resolve("previous").readBytes())
        assertTrue(entry.resolve("pending.json").isFile)
        assertEquals(0, f.forceOrMoveCalls)
    }

    @Test(timeout = 30_000) fun admissionCannotBeReusedForDifferentOrReplacedWorkspace() {
        val f = Fixture()
        val other = Fixture()
        val admitted = f.reader().inspect()
        assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(other.workspace, listOf("new")) {} }.isFailure)
        val retained = f.root.resolve("original-workspace")
        Files.move(f.workspace.toPath(), retained.toPath())
        f.workspace.mkdir()
        f.workspace.resolve("newer").writeBytes(intended)
        assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("new")) {} }.isFailure)
        assertArrayEquals(original, retained.resolve("sentinel.bin").readBytes())
        assertArrayEquals(intended, f.workspace.resolve("newer").readBytes())
    }

    @Test(timeout = 30_000) fun finalPayloadRehashCannotMissNewNamespacesOrAbsentMetadataSlots() {
        for (change in listOf("orphan", "entry-extra", "activation", "json", "path-list")) {
            val f = Fixture()
            f.workspace.resolve(".droide").mkdir()
            val entry = f.pending("file", original)
            var reads = 0
            var afterInjection: Map<String, String>? = null
            f.beforeRead = { file ->
                if (file.name == "previous" && ++reads == 2) {
                    when (change) {
                        "orphan" -> entry.parentFile!!.resolve(UUID.randomUUID().toString()).apply { mkdir(); resolve("sentinel").writeBytes(intended) }
                        "entry-extra" -> entry.resolve("extra").writeBytes(intended)
                        "activation" -> f.marker("{")
                        "json" -> f.manifest("{")
                        "path-list" -> f.workspace.resolve(".droide/saf_manifest.txt").writeText("retained\n")
                    }
                    afterInjection = f.snapshot()
                }
            }
            val admitted = f.reader().inspect()
            assertNotNull(change, afterInjection)
            f.assertRootBlock(admitted)
            f.assertSnapshot(afterInjection!!)
            assertArrayEquals(original, entry.resolve("previous").readBytes())
            assertArrayEquals(intended, entry.resolve("intended").readBytes())
            assertEquals(0, f.forceOrMoveCalls)
        }
    }

    @Test(timeout = 30_000) fun retainedPendingAndActivationAliasesBlockUnaliasedIncomingPaths() {
        for (kind in listOf("pending", "activation")) {
            val f = Fixture()
            val real = f.workspace.resolve("real").apply { mkdir(); resolve("file").writeBytes(original) }
            Files.createSymbolicLink(f.workspace.resolve("alias").toPath(), real.toPath())
            if (kind == "pending") f.pending("alias/file", original)
            else f.marker("""{"roots":["alias"],"stage":"stage","backup":"backup"}""")
            val before = f.snapshot()
            val admitted = f.reader().inspect()
            f.assertRootBlock(admitted)
            assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("real/file")) {} }.isFailure)
            f.assertSnapshot(before)
        }
    }

    @Test(timeout = 30_000) fun changedManifestCannotReuseStaleBaselineEvenAfterByteForByteRestoration() {
        val f = Fixture()
        val raw = """{"entries":{"file":"${fingerprint(original)}"}}"""
        f.manifest(raw)
        val admitted = f.reader().inspect()
        assertEquals(fingerprint(original), admitted.baselineEntries["file"])
        f.manifest("""{"entries":{"file":"${fingerprint(intended)}"}}""")
        val changed = f.snapshot()
        assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("other")) {} }.isFailure)
        f.assertSnapshot(changed)
        f.manifest(raw)
        val restored = f.snapshot()
        assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("other")) {} }.isFailure)
        f.assertSnapshot(restored)
    }

    @Test(timeout = 30_000) fun newEvidenceBetweenInspectAndConsumeRejectsPreviouslyAbsentSlots() {
        for (change in listOf("activation", "json", "path-list", "recovery")) {
            val f = Fixture()
            f.workspace.resolve(".droide").mkdir()
            val admitted = f.reader().inspect()
            assertTrue(admitted.blockers.isEmpty())
            when (change) {
                "activation" -> f.marker("{")
                "json" -> f.manifest("{")
                "path-list" -> f.workspace.resolve(".droide/saf_manifest.txt").writeText("retained\n")
                "recovery" -> f.pending("file", original)
            }
            val changed = f.snapshot()
            assertTrue(change, runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("other")) {} }.isFailure)
            f.assertSnapshot(changed)
        }
    }

    @Test(timeout = 30_000) fun consumeUsesItsOwnCancellationCheckAndCancelledAdmissionCannotBeReused() {
        val f = Fixture()
        f.pending("file", original)
        val admitted = f.reader().inspect()
        val cancellation = CancellationException("cancel current consumer")
        var cancel = false
        f.beforeRead = { if (it.name == "previous") cancel = true }
        val before = f.snapshot()
        val failure = runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("other")) { if (cancel) throw cancellation } }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("other")) {} }.isFailure)
        f.assertSnapshot(before)
        assertEquals(0, f.forceOrMoveCalls)
    }

    @Test(timeout = 30_000) fun retainedPathThatBecomesAliasedAfterInspectCannotAuthorizeAnotherPath() {
        val f = Fixture()
        f.pending("alias/file", original)
        val admitted = f.reader().inspect()
        assertEquals(listOf("alias/file"), admitted.blockers.single().scopes)
        val real = f.workspace.resolve("real").apply { mkdir(); resolve("file").writeBytes(original) }
        Files.createSymbolicLink(f.workspace.resolve("alias").toPath(), real.toPath())
        val changed = f.snapshot()
        assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("real/file")) {} }.isFailure)
        f.assertSnapshot(changed)
    }

    @Test(timeout = 30_000) fun coreAdmittedManifestCarriesLastTrustedEntriesAndKeepsLegacyBlockers() {
        for (kind in listOf("pending", "activation", "path-list")) {
            val f = Fixture()
            val current = SafManifestImage(2, UUID.randomUUID().toString(), 2, 1, "a".repeat(64), mapOf("file" to fingerprint(intended)))
            f.manifest(String(SafJournalCodec.encode(SafManifestImage.serializer(), current), Charsets.UTF_8))
            when (kind) {
                "pending" -> f.pending("file", original)
                "activation" -> f.marker("""{"roots":["file"],"stage":"stage","backup":"backup"}""")
                else -> f.workspace.resolve(".droide/saf_manifest.txt").writeText("file\n")
            }
            val visible = f.storage.describe(f.workspace.resolve(".droide/saf_manifest.json"), 16L * 1024 * 1024)
            val before = f.snapshot()
            val admitted = f.reader().inspectOwned(SafLegacyOwnedManifest(visible, mapOf("file" to fingerprint(original))))
            assertEquals(SafLegacyManifestState.OWNED, admitted.manifestState)
            assertEquals(fingerprint(original), admitted.baselineEntries["file"])
            assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(f.workspace, listOf("file")) {} }.isFailure)
            if (kind != "path-list") admitted.revalidateAndRequireDisjoint(f.workspace, listOf("other")) {}
            else f.assertRootBlock(admitted)
            f.assertSnapshot(before); assertEquals(0, f.forceOrMoveCalls)
        }
    }

    @Test(timeout = 30_000) fun ownedManifestIdentityMismatchNeverFallsBackAndSecondAdmissionIsReadOnly() {
        val f = Fixture()
        val image = SafManifestImage(2, UUID.randomUUID().toString(), 1, 0, "b".repeat(64), mapOf("file" to fingerprint(intended)))
        f.manifest(String(SafJournalCodec.encode(SafManifestImage.serializer(), image), Charsets.UTF_8))
        val identity = f.storage.describe(f.workspace.resolve(".droide/saf_manifest.json"), 16L * 1024 * 1024)
        val before = f.snapshot()
        repeat(2) {
            val admitted = f.reader().inspectOwned(SafLegacyOwnedManifest(identity, image.entries))
            admitted.revalidateAndRequireDisjoint(f.workspace, listOf("other")) {}
            assertEquals(image.entries, admitted.baselineEntries)
        }
        for (bad in listOf(identity.copy(key = "foreign"), identity.copy(bytes = identity.bytes + 1), identity.copy(sha256 = "0".repeat(64))))
            f.assertRootBlock(f.reader().inspectOwned(SafLegacyOwnedManifest(bad, image.entries)))
        f.assertSnapshot(before); assertEquals(0, f.forceOrMoveCalls)
    }

    private fun fingerprint(bytes: ByteArray) = "f:" + bytes.safDigest()
    private inner class Fixture {
        val root = Files.createTempDirectory("droide-legacy-admission-").toFile().also { roots += it }
        val workspace = root.resolve("workspace").apply { mkdir(); resolve("sentinel.bin").writeBytes(original) }
        val files = root.resolve("files").apply { mkdir() }
        var forceOrMoveCalls = 0
        var beforeRead: (File) -> Unit = {}
        val storage = SafOperationStorage(forceDirectory = { forceOrMoveCalls++; error("Read-only admission forced directory") },
            forceFile = { forceOrMoveCalls++; error("Read-only admission forced file") },
            replaceAtomically = { _, _ -> forceOrMoveCalls++; error("Read-only admission moved a file") }, beforeRead = { beforeRead(it) })
        fun reader(limits: SafLegacyLimits = SafLegacyLimits()) = SafLegacyEvidence(files, workspace, tree, storage, limits)
        fun manifest(raw: String) { workspace.resolve(".droide/saf_manifest.json").apply { parentFile!!.mkdirs(); writeText(raw) } }
        fun marker(raw: String) { workspace.resolve(".droide/saf_activation_pending.json").apply { parentFile!!.mkdirs(); writeText(raw) } }
        fun pending(path: String, previous: ByteArray?): File {
            val entry = reader().recoveryRoot().resolve(UUID.randomUUID().toString()).apply { check(mkdirs()) }
            if (previous != null) entry.resolve("previous").writeBytes(previous)
            entry.resolve("intended").writeBytes(intended)
            entry.resolve("pending.json").writeText("""{"path":"$path","previous":${previous?.let { "\"${fingerprint(it)}\"" } ?: "null"},"intended":"${fingerprint(intended)}"}""")
            return entry
        }
        fun snapshot(): Map<String, String> {
            val out = linkedMapOf<String, String>()
            Files.walk(root.toPath()).use { paths -> paths.forEach { path ->
                val name = root.toPath().relativize(path).toString()
                out[name] = when {
                    Files.isSymbolicLink(path) -> "link:${Files.readSymbolicLink(path)}"
                    Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> "directory"
                    else -> path.toFile().readBytes().safDigest()
                }
            } }
            return out
        }
        fun assertSnapshot(before: Map<String, String>) { assertEquals(before, snapshot()); assertArrayEquals(original, workspace.resolve("sentinel.bin").readBytes()) }
        fun assertRootBlock(admitted: SafLegacyAdmission) {
            assertTrue(admitted.blockers.any { "" in it.scopes })
            assertTrue(runCatching { admitted.revalidateAndRequireDisjoint(workspace, listOf("unrelated")) {} }.isFailure)
        }
    }
}
