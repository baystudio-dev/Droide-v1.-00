package com.baystudio.droide.core

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SafProviderEffectsTest {
    private val roots = arrayListOf<File>()
    private val original = byteArrayOf(0, 1, -1, 127, 10, 42)
    private val intended = byteArrayOf(90, 0, -8, 11)
    private val tree = Uri.parse("content://droide.effects/tree/root")
    private val directory = SafOperationJournal.DIRECTORY_MIME
    private val binary = "application/octet-stream"

    @After fun cleanup() { roots.forEach { PathSecurity.deleteTreeNoFollow(it) } }

    @Test(timeout = 30_000) fun fourKindsRetainEvidenceAcrossFourRawOutcomes(): Unit = runBlocking {
        for (kind in listOf(SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY, SafEffectKind.WRITE_FILE, SafEffectKind.DELETE)) {
            for (outcome in listOf("success", "before", "after", "reject")) {
                val f = Fixture()
                WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                    val helper = f.helper(guard)
                    val prepared = f.prepare(helper, kind)
                    f.calls.outcome = outcome
                    val failure = runCatching { f.execute(helper, prepared, kind) }.exceptionOrNull()
                    assertEquals("$kind/$outcome invokes exactly once", 1, f.calls.mutations)
                    val last = f.operation.effects.values.last()
                    assertNotNull(last.providerReturn)
                    if (outcome == "success") {
                        assertNull(failure)
                        assertEquals(SafEffectPhase.VERIFIED, last.phase)
                    } else {
                        assertNotNull("$kind/$outcome", failure)
                        assertEquals(SafEffectPhase.RETURNED, last.phase)
                        assertNull(last.returnedDocument)
                        assertTrue(runCatching { f.execute(helper, prepared, kind) }.isFailure)
                    }
                    f.assertSentinel()
                    f.assertEvidence()
                    val count = f.calls.mutations
                    f.reopen().summaries()
                    assertEquals("Reopen never calls the provider", count, f.calls.mutations)
                }
            }
        }
    }

    @Test(timeout = 30_000) fun nestedDeletePreservesEveryOriginalAndEmptyDirectory(): Unit = runBlocking {
        val f = Fixture()
        f.calls.add("package", directory)
        f.calls.add("package/empty", directory)
        f.calls.add("package/node_modules", directory)
        f.calls.add("package/node_modules/pkg", directory)
        f.calls.add("package/node_modules/pkg/index.js", binary, original)
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            val captured = helper.captureBeforeTree("package", f.calls.document("package"))
            assertEquals(listOf("", "empty", "node_modules", "node_modules/pkg", "node_modules/pkg/index.js"), captured.image.entries.map { it.relativePath })
            val bytes = captured.image.entries.single { it.payload != null }.payload!!
            assertArrayEquals(original, f.journal.file(f.operation, bytes.name).readBytes())
            val effect = helper.prepare(SafEffectPlan(SafEffectKind.DELETE, "package", captured.image.rootDocument, f.calls.document(""), null, null, null, beforeTree = captured.reference))
            helper.deleteTree(effect)
            assertNull(f.calls.nodes["package"])
            assertArrayEquals(original, f.journal.file(f.operation, bytes.name).readBytes())
            assertEquals(1, f.calls.mutations)
            f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun rawReturnIsDurableBeforeAnyPostCallQueryAndCancellationLeavesItUnverified(): Unit = runBlocking {
        for (kind in listOf(SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY, SafEffectKind.WRITE_FILE, SafEffectKind.DELETE)) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val effect = f.prepare(helper, kind)
                f.calls.onQuery = {
                    if (f.calls.mutations > 0) {
                        val returned = f.operation.effects.values.last()
                        assertEquals(SafEffectPhase.RETURNED, returned.phase)
                        assertNull(returned.returnedDocument)
                        assertNotNull(returned.providerReturn)
                        assertTrue(f.journal.file(f.operation, SafOperationJournal.actionName(returned.actionId, SafEffectPhase.RETURNED)).isFile)
                        throw CancellationException("cancel post-call admission")
                    }
                }
                assertTrue(runCatching { f.execute(helper, effect, kind) }.exceptionOrNull() is CancellationException)
                assertEquals(SafEffectPhase.RETURNED, f.operation.effects.values.last().phase)
                assertEquals(1, f.calls.mutations)
                f.assertEvidence()
            }
        }
    }

    @Test(timeout = 30_000) fun wrongCreationIdentityNameMimeParentOrAuthorityNeverBecomesOwned(): Unit = runBlocking {
        for (wrong in listOf("identity", "name", "mime", "parent", "authority")) {
            val f = Fixture()
            f.calls.add("other", directory)
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val effect = f.prepare(helper, SafEffectKind.CREATE_DIRECTORY)
                f.calls.wrongCreation = wrong
                assertTrue(wrong, runCatching { helper.createDirectory(effect) }.isFailure)
                val last = f.operation.effects.values.last()
                assertEquals(SafEffectPhase.RETURNED, last.phase)
                assertNull(last.returnedDocument)
                assertNotNull(last.providerReturn!!.uri)
                assertEquals(listOf(""), SafProviderEffects.blockingScopes(f.operation))
                assertEquals(1, f.calls.mutations)
                assertTrue("Unexpected created objects are retained", f.calls.nodes.size >= 4)
                f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun creationRequiresParentNamespaceAndDoesNotCallProviderOnRejection(): Unit = runBlocking {
        val f = Fixture(scopes = listOf("new"))
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            assertTrue(runCatching { f.prepare(helper, SafEffectKind.CREATE_DIRECTORY) }.isFailure)
            assertEquals(0, f.calls.mutations)
            assertTrue(f.operation.effects.isEmpty())
        }
    }

    @Test(timeout = 30_000) fun linkedWriteWaitsForVerifiedCreation(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            val create = f.prepare(helper, SafEffectKind.CREATE_FILE)
            val intendedRef = f.payload(intended)
            val write = helper.prepare(SafEffectPlan(SafEffectKind.WRITE_FILE, "new", null, f.calls.document(""), helper.actionId(create), null, intendedRef))
            helper.createFile(create)
            helper.writeFile(write)
            assertArrayEquals(intended, f.calls.nodes.getValue("new").bytes)
            assertEquals(2, f.calls.mutations)
            assertTrue(f.operation.effects.values.all { it.phase == SafEffectPhase.VERIFIED })
        }
    }

    @Test(timeout = 30_000) fun lateDescendantBlocksDeleteBeforeFirstEffect(): Unit = runBlocking {
        val f = Fixture()
        f.calls.add("package", directory)
        f.calls.add("package/original.bin", binary, original)
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            val before = helper.captureBeforeTree("package", f.calls.document("package"))
            val effect = helper.prepare(SafEffectPlan(SafEffectKind.DELETE, "package", before.image.rootDocument, f.calls.document(""), null, null, null, beforeTree = before.reference))
            f.calls.add("package/new.bin", binary, intended)
            assertTrue(runCatching { helper.deleteTree(effect) }.isFailure)
            assertEquals(0, f.calls.mutations)
            assertArrayEquals(original, f.calls.nodes.getValue("package/original.bin").bytes)
            assertArrayEquals(intended, f.calls.nodes.getValue("package/new.bin").bytes)
            assertArrayEquals(original, f.journal.file(f.operation, before.image.entries.last().payload!!.name).readBytes())
        }
    }

    @Test(timeout = 30_000) fun everyIntendedPayloadIsCheckedBeforeFirstMutation(): Unit = runBlocking {
        val f = Fixture()
        f.calls.add("a", binary, original)
        f.calls.add("b", binary, original)
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            val effects = listOf("a", "b").map { path ->
                val before = helper.captureBeforeTree(path, f.calls.document(path))
                val payload = f.payload(intended)
                helper.prepare(SafEffectPlan(SafEffectKind.WRITE_FILE, path, f.calls.document(path), f.calls.document(""), null, before.image.entries.single().payload, payload)) to payload
            }
            f.journal.file(f.operation, effects.last().second.name).writeBytes(original)
            assertTrue(runCatching { helper.writeFile(effects.first().first) }.isFailure)
            assertEquals(0, f.calls.mutations)
            assertArrayEquals(original, f.calls.nodes.getValue("a").bytes)
        }
    }

    @Test(timeout = 30_000) fun cancellationDuringCaptureAndTransferRetainsEvidenceWithoutReplay(): Unit = runBlocking {
        val capturing = Fixture()
        capturing.calls.add("old", binary, original)
        WorkspaceFileMutationLocks.withRoot(capturing.workspace) { guard ->
            val helper = capturing.helper(guard)
            capturing.calls.onInput = { capturing.cancel = true; ByteArrayInputStream(original) }
            assertTrue(runCatching { helper.captureBeforeTree("old", capturing.calls.document("old")) }.exceptionOrNull() is CancellationException)
            assertEquals(0, capturing.calls.mutations)
            capturing.assertSentinel()
        }
        val writing = Fixture()
        WorkspaceFileMutationLocks.withRoot(writing.workspace) { guard ->
            val helper = writing.helper(guard)
            val effect = writing.prepare(helper, SafEffectKind.WRITE_FILE)
            writing.calls.onWrite = { writing.cancel = true }
            assertTrue(runCatching { helper.writeFile(effect) }.exceptionOrNull() is CancellationException)
            assertEquals(1, writing.calls.mutations)
            assertEquals(SafEffectReturn.THREW, writing.operation.effects.values.last().result)
            writing.assertEvidence()
        }
    }

    @Test(timeout = 30_000) fun failedBeforeimageCloseAndZeroProgressNeverAdmitDestruction(): Unit = runBlocking {
        for (closeFailure in listOf(true, false)) {
            val f = Fixture()
            f.calls.add("old", binary, original)
            var reads = 0
            f.calls.onInput = {
                if (closeFailure) object : ByteArrayInputStream(original) { override fun close() { throw IOException("close beforeimage") } }
                else object : InputStream() {
                    override fun read() = error("Unexpected single-byte read")
                    override fun read(bytes: ByteArray, off: Int, len: Int): Int { reads++; return 0 }
                }
            }
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                assertTrue(runCatching { f.helper(guard).captureBeforeTree("old", f.calls.document("old")) }.isFailure)
                assertEquals(0, f.calls.mutations)
                if (!closeFailure) assertEquals(32, reads)
                assertArrayEquals(original, f.calls.nodes.getValue("old").bytes)
            }
        }
    }

    @Test(timeout = 30_000) fun boundedBytesEntriesAndEncodedIndexRejectBeforeEffects(): Unit = runBlocking {
        for (limits in listOf(SafJournalLimits(treeBytes = 2), SafJournalLimits(treeEntries = 1), SafJournalLimits(treeIndexBytes = 128))) {
            val f = Fixture(limits)
            f.calls.add("package", directory)
            f.calls.add("package/original.bin", binary, original)
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                assertTrue(runCatching { f.helper(guard).captureBeforeTree("package", f.calls.document("package")) }.isFailure)
                assertEquals(0, f.calls.mutations)
                f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun duplicateOrIncompleteQueriesNeverBecomeAbsence(): Unit = runBlocking {
        for (mode in listOf("name", "id", "failure")) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val effect = f.prepare(helper, SafEffectKind.CREATE_DIRECTORY)
                f.calls.badChildren = mode
                assertTrue(runCatching { helper.createDirectory(effect) }.isFailure)
                assertEquals(0, f.calls.mutations)
            }
        }
    }

    @Test(timeout = 30_000) fun returnFinalizerReadsNoPayloadIndexOrHistoryAndRepeatedExecutionIsRejected(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            val effect = f.prepare(helper, SafEffectKind.WRITE_FILE)
            var inReturn = false
            val finalizerReads = arrayListOf<String>()
            f.beforeRead = { if (inReturn) finalizerReads += it.name }
            f.calls.onOutputClosed = { inReturn = true }
            f.calls.onQuery = { inReturn = false }
            helper.writeFile(effect)
            assertTrue(finalizerReads.isNotEmpty())
            assertTrue(finalizerReads.all { it == "anchor.json" || it == "operation.json" || it.startsWith("action-") })
            assertTrue(runCatching { helper.writeFile(effect) }.isFailure)
            assertEquals(1, f.calls.mutations)
            assertTrue(runCatching { f.helper(guard) }.isFailure)
            f.assertEvidence()
        }
    }

    @Test(timeout = 30_000) fun wrongGuardAndProtectedPathRejectWithoutProviderCalls(): Unit = runBlocking {
        val f = Fixture()
        val foreign = Fixture()
        WorkspaceFileMutationLocks.withRoot(foreign.workspace) { guard ->
            assertTrue(runCatching { f.helper(guard) }.isFailure)
        }
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            assertTrue(runCatching { helper.captureBeforeTree(".git/secret", f.calls.document("")) }.isFailure)
            assertEquals(0, f.calls.mutations)
        }
    }

    @Test(timeout = 30_000) fun equalBytesUnderReplacedIdentityCannotPassPreEffectAdmission(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            val effect = f.prepare(helper, SafEffectKind.WRITE_FILE)
            f.calls.nodes["old"] = f.calls.nodes.getValue("old").copy(id = "replacement-identity")
            assertTrue(runCatching { helper.writeFile(effect) }.isFailure)
            assertEquals(0, f.calls.mutations)
            assertArrayEquals(original, f.calls.nodes.getValue("old").bytes)
            f.assertEvidence()
        }
    }

    @Test(timeout = 30_000) fun returnRecordForceFailureRetainsAttemptAndOriginalExceptionWithoutReplay(): Unit = runBlocking {
        val f = Fixture()
        val originalFailure = IOException("provider original failure")
        val recordFailure = IOException("returned receipt force failure")
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            val effect = f.prepare(helper, SafEffectKind.CREATE_DIRECTORY)
            f.calls.exactFailure = originalFailure
            f.beforeForceFile = { if (it.name.endsWith("-2.json")) throw recordFailure }
            val observed = runCatching { helper.createDirectory(effect) }.exceptionOrNull()
            assertSame(originalFailure, observed)
            assertTrue(observed!!.suppressed.any { it === recordFailure })
            assertEquals(1, f.calls.mutations)
            assertEquals(SafEffectPhase.ATTEMPTING, f.operation.effects.values.last().phase)
            assertTrue(runCatching { helper.createDirectory(effect) }.isFailure)
            assertEquals(1, f.calls.mutations)
            assertTrue(f.privateRoot.resolve(f.operation.id).listFiles()!!.any { it.name.endsWith("-2.json") })
            f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun providerChangesDuringDeepAttemptAdmissionRefuseTheOriginalCall(): Unit = runBlocking {
        for (kind in listOf(SafEffectKind.WRITE_FILE, SafEffectKind.DELETE)) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val effect = if (kind == SafEffectKind.WRITE_FILE) f.prepare(helper, kind) else {
                    f.calls.add("package", directory)
                    f.calls.add("package/original.bin", binary, original)
                    val before = helper.captureBeforeTree("package", f.calls.document("package"))
                    f.evidence[before.image.entries.last().payload!!.name] = original.copyOf()
                    helper.prepare(SafEffectPlan(kind, "package", before.image.rootDocument, f.calls.document(""), null,
                        null, null, beforeTree = before.reference))
                }
                val id = helper.actionId(effect)
                var inAttempt = false
                var injected = false
                f.beforeRead = { file ->

                    if (file.name == SafOperationJournal.actionName(id, SafEffectPhase.PREPARED)) inAttempt = true
                    if (inAttempt && !injected && file.name.startsWith("payload-")) {
                        injected = true
                        if (kind == SafEffectKind.DELETE) f.calls.add("package/new.bin", binary, intended)
                        else f.calls.nodes.getValue("old").bytes = intended.copyOf()
                    }
                }
                assertTrue(runCatching { f.execute(helper, effect, kind) }.isFailure)
                assertTrue("Injection must reach actual deep ATTEMPTING reads", injected)
                assertEquals(0, f.calls.mutations)
                assertEquals(SafEffectPhase.ATTEMPTING, f.operation.effects.getValue(id).phase)
                assertFalse(f.journal.file(f.operation, SafOperationJournal.actionName(id, SafEffectPhase.RETURNED)).exists())
                if (kind == SafEffectKind.DELETE) {
                    assertArrayEquals(original, f.calls.nodes.getValue("package/original.bin").bytes)
                    assertArrayEquals(intended, f.calls.nodes.getValue("package/new.bin").bytes)
                } else assertArrayEquals(intended, f.calls.nodes.getValue("old").bytes)
                assertTrue(runCatching { f.execute(helper, effect, kind) }.isFailure)
                assertEquals(0, f.calls.mutations)
                f.assertEvidence()
                f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun conflictingPlansAreRejectedAsASetBeforeAnyAttemptOrProviderEffect(): Unit = runBlocking {
        for (mode in listOf("duplicate-create", "duplicate-write", "write-delete", "delete-create", "ancestor", "double-linked-write")) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val first: SafProviderEffects.Effect
                val kind: SafEffectKind
                when (mode) {
                    "duplicate-create" -> {
                        kind = SafEffectKind.CREATE_DIRECTORY
                        first = f.prepare(helper, kind)
                        helper.prepare(f.operation.effects.values.last().plan)
                    }
                    "duplicate-write", "write-delete" -> {
                        kind = SafEffectKind.WRITE_FILE
                        first = f.prepare(helper, kind)
                        if (mode == "duplicate-write") helper.prepare(f.operation.effects.values.last().plan)
                        else {
                            val before = helper.captureBeforeTree("old", f.calls.document("old"))
                            helper.prepare(SafEffectPlan(SafEffectKind.DELETE, "old", before.image.rootDocument, f.calls.document(""), null,
                                null, null, beforeTree = before.reference))
                        }
                    }
                    "delete-create" -> {
                        kind = SafEffectKind.DELETE
                        first = f.prepare(helper, kind)
                        helper.prepare(SafEffectPlan(SafEffectKind.CREATE_DIRECTORY, "old", null, f.calls.document(""), null,
                            null, null, requestedMimeType = directory))
                    }
                    "ancestor" -> {
                        kind = SafEffectKind.DELETE
                        f.calls.add("package", directory)
                        f.calls.add("package/original.bin", binary, original)
                        val before = helper.captureBeforeTree("package", f.calls.document("package"))
                        first = helper.prepare(SafEffectPlan(kind, "package", before.image.rootDocument, f.calls.document(""), null,
                            null, null, beforeTree = before.reference))
                        helper.prepare(SafEffectPlan(SafEffectKind.WRITE_FILE, "package/original.bin", f.calls.document("package/original.bin"),
                            f.calls.document("package"), null, before.image.entries.last().payload, f.payload(intended)))
                    }
                    else -> {
                        kind = SafEffectKind.CREATE_FILE
                        first = f.prepare(helper, kind)
                        val write = SafEffectPlan(SafEffectKind.WRITE_FILE, "new", null, f.calls.document(""), helper.actionId(first), null, f.payload(intended))
                        helper.prepare(write)
                        helper.prepare(write)
                    }
                }
                val files = f.privateRoot.resolve(f.operation.id).list()!!.toSet()
                assertTrue(mode, runCatching { f.execute(helper, first, kind) }.isFailure)
                assertEquals(0, f.calls.mutations)
                assertTrue(f.operation.effects.values.all { it.phase == SafEffectPhase.PREPARED })
                assertEquals(files, f.privateRoot.resolve(f.operation.id).list()!!.toSet())
                f.assertEvidence()
                f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun predeclaredOpaqueParentChainPublishesNestedDependencyBytesAndEmptyDirectory(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            val a = f.newDirectory(helper, "package")
            val b = f.newDirectory(helper, "package/node_modules", a)
            val c = f.newDirectory(helper, "package/node_modules/pkg", b)
            val empty = f.newDirectory(helper, "package/empty", a)
            val path = "package/node_modules/pkg/index.js"
            val file = f.newFile(helper, path, c)
            val write = f.newWrite(helper, path, c, file)
            assertEquals(6, f.operation.effects.size)
            assertTrue(f.operation.effects.values.all { it.phase == SafEffectPhase.PREPARED })
            assertEquals(0, f.calls.mutations)
            listOf(a, b, c, empty).forEach { helper.createDirectory(it) }
            helper.createFile(file)
            helper.writeFile(write)
            assertArrayEquals(intended, f.calls.nodes.getValue(path).bytes)
            assertEquals(directory, f.calls.nodes.getValue("package/empty").mime)
            assertTrue(f.calls.nodes.keys.none { it.startsWith("package/empty/") })
            assertTrue(f.operation.effects.values.all { it.phase == SafEffectPhase.VERIFIED })
            assertEquals(6, f.calls.mutations)
            assertTrue(f.operation.effects.values.filter { it.plan.parentCreationActionId != null }.all { it.version == 3 })
            assertEquals(6, f.reopen().recover(f.operation.id).effects.size)
            f.assertEvidence()
            f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun childOrderAndForwardReferencesCannotStartProviderWork(): Unit = runBlocking {
        for (mode in listOf("child-first", "forward")) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                if (mode == "forward") {
                    assertTrue(runCatching { helper.prepare(SafEffectPlan(SafEffectKind.CREATE_FILE, "a/file", null, null, null,
                        null, null, requestedMimeType = binary, parentCreationActionId = "00000000-0000-0000-0000-000000000099")) }.isFailure)
                } else {
                    val a = f.newDirectory(helper, "a")
                    val child = f.newFile(helper, "a/file", a)
                    assertTrue(runCatching { helper.createFile(child) }.isFailure)
                    assertTrue(f.operation.effects.values.all { it.phase == SafEffectPhase.PREPARED })
                }
                assertEquals(0, f.calls.mutations)
                f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun uncertainParentOutcomesNeverAuthorizePreparedChildren(): Unit = runBlocking {
        for (mode in listOf("before", "after", "reject", "wrong-uri", "cancel-query")) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val parent = f.newDirectory(helper, "a")
                val child = f.newFile(helper, "a/file", parent)
                when (mode) {
                    "wrong-uri" -> f.calls.wrongCreation = "identity"
                    "cancel-query" -> f.calls.onQuery = { if (f.calls.mutations > 0) {
                        assertEquals(SafEffectPhase.RETURNED, f.operation.effects.getValue(helper.actionId(parent)).phase)
                        throw CancellationException("cancel parent verification")
                    } }
                    else -> f.calls.outcome = mode
                }
                assertTrue(mode, runCatching { helper.createDirectory(parent) }.isFailure)
                assertTrue(runCatching { helper.createFile(child) }.isFailure)
                assertEquals(1, f.calls.mutations)
                assertEquals(SafEffectPhase.RETURNED, f.operation.effects.getValue(helper.actionId(parent)).phase)
                assertEquals(SafEffectPhase.PREPARED, f.operation.effects.getValue(helper.actionId(child)).phase)
                assertEquals(listOf(""), SafProviderEffects.blockingScopes(f.operation))
                f.calls.onQuery = {}
                f.reopen().summaries()
                assertEquals(1, f.calls.mutations)
                f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun parentReceiptChangedInsideChildCallCannotDiscardRawReturnedLocation(): Unit = runBlocking {
        for (throwsAfterCreation in listOf(false, true)) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val parent = f.newDirectory(helper, "a")
                val child = f.newFile(helper, "a/file", parent)
                helper.createDirectory(parent)
                val receipt = f.journal.file(f.operation, SafOperationJournal.actionName(helper.actionId(parent), SafEffectPhase.VERIFIED))
                val exactFailure = IOException("child created, provider threw")
                var createdUri: Uri? = null
                f.calls.onCreated = { uri ->
                    createdUri = uri
                    receipt.appendText(" ")
                    if (throwsAfterCreation) throw exactFailure
                }
                f.calls.onQuery = { if (f.calls.mutations == 2) {
                    assertEquals(SafEffectPhase.RETURNED, f.operation.effects.getValue(helper.actionId(child)).phase)
                } }
                val failure = runCatching { helper.createFile(child) }.exceptionOrNull()
                assertNotNull(failure)
                if (throwsAfterCreation) assertSame(exactFailure, failure)
                val returned = f.operation.effects.getValue(helper.actionId(child))
                assertEquals(SafEffectPhase.RETURNED, returned.phase)
                assertNull(returned.returnedDocument)
                if (throwsAfterCreation) assertEquals(SafProviderReturnShape.THREW, returned.providerReturn!!.shape)
                else assertEquals(createdUri.toString(), returned.providerReturn!!.uri)
                val raw = f.journal.file(f.operation, SafOperationJournal.actionName(helper.actionId(child), SafEffectPhase.RETURNED))
                assertEquals(returned, SafJournalCodec.decode(SafEffectRecord.serializer(), raw.readBytes()))
                assertTrue(f.calls.nodes.containsKey("a/file"))
                assertTrue(receipt.readText().endsWith(" "))
                assertEquals(2, f.calls.mutations)
                assertTrue(runCatching { helper.createFile(child) }.isFailure)
                assertEquals(2, f.calls.mutations)
                f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun exchangedParentOrReceiptBeforeChildAttemptPreservesAllNewerData(): Unit = runBlocking {
        for (mode in listOf("identity", "receipt")) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val parent = f.newDirectory(helper, "a")
                val child = f.newFile(helper, "a/file", parent)
                f.newWrite(helper, "a/file", parent, child)
                f.payload(original)
                helper.createDirectory(parent)
                if (mode == "identity") f.calls.nodes["a"] = f.calls.nodes.getValue("a").copy(id = "newer-parent-id")
                else f.journal.file(f.operation, SafOperationJournal.actionName(helper.actionId(parent), SafEffectPhase.VERIFIED)).appendText(" ")
                f.calls.add("a/newer.bin", binary, intended)
                assertTrue(runCatching { helper.createFile(child) }.isFailure)
                assertEquals(1, f.calls.mutations)
                assertFalse(f.calls.nodes.containsKey("a/file"))
                assertArrayEquals(intended, f.calls.nodes.getValue("a/newer.bin").bytes)
                f.assertEvidence()
                f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun corruptedLastNestedPayloadRefusesTheOutermostCreation(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            val parent = f.newDirectory(helper, "a")
            val nested = f.newDirectory(helper, "a/b", parent)
            val file = f.newFile(helper, "a/b/file", nested)
            val write = f.newWrite(helper, "a/b/file", nested, file)
            val payload = f.operation.effects.getValue(helper.actionId(write)).plan.intended!!
            f.journal.file(f.operation, payload.name).writeBytes(original)
            assertTrue(runCatching { helper.createDirectory(parent) }.isFailure)
            assertEquals(0, f.calls.mutations)
            assertTrue(f.operation.effects.values.all { it.phase == SafEffectPhase.PREPARED })
            assertArrayEquals(original, f.journal.file(f.operation, payload.name).readBytes())
            assertFalse(f.calls.nodes.containsKey("a"))
            f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun fullLexicalDepthLinkedDepthAndTinyReservationsFailBeforeEffects(): Unit = runBlocking {
        for (mode in listOf("lexical", "chain", "actions", "metadata", "evidence")) {
            val reserve = SafJournalReservation(if (mode == "actions") 1 else 16, if (mode == "metadata") 1024 else 2L * 1024 * 1024,
                2L * 1024 * 1024, evidenceFiles = if (mode == "evidence") 0 else 32)
            val f = Fixture(limits = SafJournalLimits(treeDepth = if (mode == "chain") 2 else if (mode == "lexical") 3 else 64), reservation = reserve)
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val failure = runCatching {
                    if (mode == "lexical") {
                        f.calls.add("existing", directory)
                        f.calls.add("existing/deep", directory)
                        val parent = f.newDirectory(helper, "existing/deep/new", knownParent = "existing/deep")
                        f.newFile(helper, "existing/deep/new/file", parent)
                    } else {
                        val parent = f.newDirectory(helper, "a")
                        val child = f.newDirectory(helper, "a/b", parent)
                        f.newFile(helper, "a/b/file", child)
                        f.payload(intended)
                    }
                }
                assertTrue(mode, failure.isFailure)
                assertEquals(0, f.calls.mutations)
                assertTrue(f.operation.effects.values.all { it.phase == SafEffectPhase.PREPARED })
                f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun nestedWriteRawFinalizerReadsNoParentChainAndLatePrepareStaysClosed(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            val a = f.newDirectory(helper, "a")
            val b = f.newDirectory(helper, "a/b", a)
            val c = f.newDirectory(helper, "a/b/c", b)
            val file = f.newFile(helper, "a/b/c/file", c)
            val write = f.newWrite(helper, "a/b/c/file", c, file)
            listOf(a, b, c).forEach { helper.createDirectory(it) }
            helper.createFile(file)
            var finalizing = false
            val reads = arrayListOf<String>()
            f.calls.onOutputClosed = { finalizing = true }
            f.calls.onQuery = { finalizing = false }
            f.beforeRead = { if (finalizing) reads += it.name }
            helper.writeFile(write)
            assertTrue(reads.isNotEmpty() && reads.size <= 6)
            val parentReceipts = listOf(a, b, c, file).map { SafOperationJournal.actionName(helper.actionId(it), SafEffectPhase.VERIFIED) }
            assertTrue(reads.none { it in parentReceipts || it.endsWith(".bin") || it.startsWith("tree-") })
            assertArrayEquals(intended, f.calls.nodes.getValue("a/b/c/file").bytes)
            val before = f.calls.mutations
            assertTrue(runCatching { f.newDirectory(helper, "late") }.isFailure)
            assertTrue(runCatching { helper.writeFile(write) }.isFailure)
            assertEquals(before, f.calls.mutations)
            f.assertEvidence()
        }
    }

    @Test(timeout = 30_000) fun exactReplacementGroupsPreserveTheCompleteOriginalBeforeEveryEffect(): Unit = runBlocking {
        for (directoryBefore in listOf(false, true)) {
            val f = Fixture(request = SafOperationRequest(SafOperationKind.REFRESH, emptyList()))
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val steps = f.replacement(helper, directoryBefore)
                steps.forEach { (kind, effect) -> f.execute(helper, effect, kind); f.assertEvidence() }
                assertEquals(steps.size, f.calls.mutations)
                assertEquals(if (directoryBefore) binary else directory, f.calls.nodes.getValue("change").mime)
                assertArrayEquals(intended, f.calls.nodes.getValue(if (directoryBefore) "change" else "change/new.bin").bytes)
                assertTrue(f.operation.effects.values.all { it.phase == SafEffectPhase.VERIFIED })
                assertEquals(6, f.operation.effects.values.single { it.plan.replacementDeleteActionId != null }.version)
                assertEquals(f.operation.effects, f.reopen().recoverAll().single().effects)
                f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun replacementSetRejectsUnboundDuplicatesAndOutOfOrderCallsBeforeEffects(): Unit = runBlocking {
        for (mode in listOf("unbound", "duplicate", "out-of-order")) {
            val f = Fixture(request = SafOperationRequest(SafOperationKind.REFRESH, emptyList()))
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val steps = if (mode == "unbound") {
                    f.calls.add("change", binary, original)
                    val capture = helper.captureBeforeTree("change", f.calls.document("change"))
                    val deletion = helper.prepare(SafEffectPlan(SafEffectKind.DELETE, "change", f.calls.document("change"), f.calls.document(""), null, null, null, beforeTree = capture.reference))
                    f.newDirectory(helper, "change")
                    listOf(SafEffectKind.DELETE to deletion)
                } else f.replacement(helper, directoryBefore = false)
                if (mode == "duplicate") f.newDirectory(helper, "change")
                val selected = if (mode == "out-of-order") steps[1] else steps[0]
                assertTrue(mode, runCatching { f.execute(helper, selected.second, selected.first) }.isFailure)
                assertEquals(0, f.calls.mutations)
                assertArrayEquals(original, f.calls.nodes.getValue("change").bytes)
                f.assertEvidence(); f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun capabilityQueriesCannotHideNewOriginalBytesOrWithdrawnSupportBeforeDelete(): Unit = runBlocking {
        for (mode in listOf("changed-bytes", "new-descendant", "lost-capability")) {
            val f = Fixture(request = SafOperationRequest(SafOperationKind.REFRESH, emptyList()))
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val steps = f.replacement(helper, directoryBefore = mode == "new-descendant")
                val deletionId = helper.actionId(steps.first().second)
                var injected = false
                var sawOld = false
                var oldReadCount = 0
                f.calls.onRead = { id ->
                    if (f.operation.effects.getValue(deletionId).phase == SafEffectPhase.ATTEMPTING && !injected) {
                        if (id == f.calls.nodes.getValue("change").id) { oldReadCount++; sawOld = true }

                        if (id == "root" && sawOld && oldReadCount >= 2) {
                            injected = true
                            if (mode == "changed-bytes") f.calls.nodes.getValue("change").bytes = byteArrayOf(99, 98)
                            else if (mode == "new-descendant") f.calls.add("change/newer.bin", binary, byteArrayOf(99, 98))
                            else f.calls.flags = 0
                        }
                    }
                }
                assertTrue(runCatching { helper.deleteTree(steps.first().second) }.isFailure)
                assertTrue(injected)
                assertEquals(0, f.calls.mutations)
                if (mode == "new-descendant") {
                    assertArrayEquals(byteArrayOf(99, 98), f.calls.nodes.getValue("change/newer.bin").bytes)
                    assertArrayEquals(original, f.calls.nodes.getValue("change/old.bin").bytes)
                } else assertArrayEquals(if (mode == "changed-bytes") byteArrayOf(99, 98) else original, f.calls.nodes.getValue("change").bytes)
                f.assertEvidence(); f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun changedDeleteReceiptDuringReplacementCallRetainsRawUriOrThrow(): Unit = runBlocking {
        for (throwsAfter in listOf(false, true)) {
            val f = Fixture(request = SafOperationRequest(SafOperationKind.REFRESH, emptyList()))
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val helper = f.helper(guard)
                val steps = f.replacement(helper, directoryBefore = false)
                helper.deleteTree(steps[0].second)
                val receipt = f.journal.file(f.operation, SafOperationJournal.actionName(helper.actionId(steps[0].second), SafEffectPhase.VERIFIED))
                val exact = IOException("created replacement, then threw")
                var uri: Uri? = null
                f.calls.onCreated = { created -> uri = created; receipt.appendText(" "); if (throwsAfter) throw exact }
                val failure = runCatching { helper.createDirectory(steps[1].second) }.exceptionOrNull()
                assertNotNull(failure)
                if (throwsAfter) assertSame(exact, failure)
                val raw = f.operation.effects.getValue(helper.actionId(steps[1].second))
                assertEquals(SafEffectPhase.RETURNED, raw.phase)
                assertNull(raw.returnedDocument)
                if (throwsAfter) assertEquals(SafProviderReturnShape.THREW, raw.providerReturn!!.shape)
                else assertEquals(uri.toString(), raw.providerReturn!!.uri)
                assertEquals(2, f.calls.mutations)
                assertTrue(f.calls.nodes.containsKey("change"))
                assertTrue(runCatching { helper.createDirectory(steps[1].second) }.isFailure)
                assertEquals(2, f.calls.mutations)
                f.assertEvidence(); f.assertSentinel()
            }
        }
    }

    private fun Fixture.replacement(helper: SafProviderEffects, directoryBefore: Boolean): List<Pair<SafEffectKind, SafProviderEffects.Effect>> {
        calls.add("change", if (directoryBefore) directory else binary, if (directoryBefore) byteArrayOf() else original)
        if (directoryBefore) { calls.add("change/old.bin", binary, original); calls.add("change/empty", directory) }
        val capture = helper.captureBeforeTree("change", calls.document("change"))
        capture.image.entries.forEach { entry -> entry.payload?.let { evidence[it.name] = original.copyOf() } }
        val deletion = helper.prepare(SafEffectPlan(SafEffectKind.DELETE, "change", calls.document("change"), calls.document(""), null, null, null, beforeTree = capture.reference))
        val kind = if (directoryBefore) SafEffectKind.CREATE_FILE else SafEffectKind.CREATE_DIRECTORY
        val creation = helper.prepare(SafEffectPlan(kind, "change", null, calls.document(""), null, null, null,
            requestedMimeType = if (directoryBefore) binary else directory, replacementDeleteActionId = helper.actionId(deletion)))
        val steps = arrayListOf(SafEffectKind.DELETE to deletion, kind to creation)
        if (directoryBefore) {
            steps += SafEffectKind.WRITE_FILE to helper.prepare(SafEffectPlan(SafEffectKind.WRITE_FILE, "change", null,
                calls.document(""), helper.actionId(creation), null, payload(intended)))
        } else {
            val child = newFile(helper, "change/new.bin", creation)
            steps += SafEffectKind.CREATE_FILE to child
            steps += SafEffectKind.WRITE_FILE to newWrite(helper, "change/new.bin", creation, child)
        }
        return steps
    }

    private data class Node(val id: String, val name: String, val mime: String, val parent: String, var bytes: ByteArray)
    private inner class Calls : SafProviderCalls {
        val nodes = linkedMapOf<String, Node>("" to Node("root", "root", directory, "root", byteArrayOf()))
        var mutations = 0
        var queries = 0
        var outcome = "success"
        var wrongCreation = ""
        var badChildren = ""
        var exactFailure: Throwable? = null
        var onQuery: () -> Unit = {}
        var onRead: (String) -> Unit = {}
        var flags: Int? = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        var onInput: (() -> InputStream)? = null
        var onWrite: () -> Unit = {}
        var onOutputClosed: () -> Unit = {}
        var onCreated: (Uri) -> Unit = {}
        private var ids = 0
        fun add(path: String, mime: String, bytes: ByteArray = byteArrayOf()) {
            val parent = nodes.getValue(path.substringBeforeLast('/', "")).id
            nodes[path] = Node("opaque-${++ids}", path.substringAfterLast('/'), mime, parent, bytes.copyOf())
        }
        fun document(path: String): SafOwnedDocument = nodes.getValue(path).let { SafOwnedDocument(uri(it.id).toString(), it.id, it.name, it.mime, it.parent) }
        private fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        private fun node(n: Node) = SafDocumentNode(n.id, uri(n.id), n.name, n.mime, n.bytes.size.toLong(), 0, flags)
        private fun path(uri: Uri) = nodes.entries.single { it.value.id == DocumentsContract.getDocumentId(uri) }.key
        override fun read(id: String, checkActive: () -> Unit): SafDocumentNode? { checkActive(); check(++queries <= 4096); onQuery(); onRead(id); return nodes.values.singleOrNull { it.id == id }?.let(::node) }
        override fun children(id: String, maximum: Int, checkActive: () -> Unit): List<SafDocumentNode> {
            checkActive(); check(++queries <= 4096); onQuery()
            if (badChildren == "failure") throw SafDirectQueryException("incomplete cursor")
            val result = nodes.filterKeys { it.isNotEmpty() }.values.filter { it.parent == id }.map(::node).toMutableList()
            if (result.isNotEmpty() && badChildren == "name") result += result.first().copy(documentId = "different", uri = uri("different"))
            if (result.isNotEmpty() && badChildren == "id") result += result.first().copy(name = "different")
            return result
        }
        override fun input(uri: Uri): InputStream = onInput?.invoke() ?: ByteArrayInputStream(nodes.getValue(path(uri)).bytes)
        override fun output(uri: Uri): OutputStream {
            mutations++
            if (outcome == "before") throw IOException("before output")
            val n = nodes.getValue(path(uri))
            n.bytes = byteArrayOf()
            if (outcome == "after") throw IOException("after truncate")
            return object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, off: Int, len: Int) { super.write(bytes, off, len); n.bytes = toByteArray(); onWrite() }
                override fun close() { if (outcome == "reject") throw IOException("close failed"); super.close(); onOutputClosed() }
            }
        }
        override fun create(parent: Uri, mime: String, name: String): Uri? {
            mutations++
            exactFailure?.let { throw it }
            if (outcome == "before") throw IOException("before create")
            val parentPath = if (wrongCreation == "parent") "other" else path(parent)
            val actualName = if (wrongCreation == "name") "unexpected" else name
            val target = if (parentPath.isEmpty()) actualName else "$parentPath/$actualName"
            add(target, if (wrongCreation == "mime") binary else mime)
            onCreated(uri(nodes.getValue(target).id))
            if (outcome == "after") throw IOException("after create")
            if (outcome == "reject") return null
            return when (wrongCreation) {
                "identity" -> uri("different-opaque-identity")
                "authority" -> Uri.parse("content://foreign/tree/root/document/foreign")
                else -> uri(nodes.getValue(target).id)
            }
        }
        override fun delete(uri: Uri): Boolean {
            mutations++
            if (outcome == "before") throw IOException("before delete")
            val path = path(uri)
            nodes.keys.removeAll { it == path || it.startsWith("$path/") }
            if (outcome == "after") throw IOException("after delete")
            return outcome != "reject"
        }
    }

    @Test(timeout = 30_000) fun verifiedLocalReceiptAllowsCompleteProviderPreparationAndExecution(): Unit = runBlocking {
        val f = Fixture(request = SafOperationRequest(SafOperationKind.WRITE, listOf("new")))
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val stage = f.workspace.resolve("owned-stage").apply { writeBytes(intended) }
            val local = SafLocalEffects(guard, f.journal, f.operation) {}
            val prep = WorkspaceLocalPreparation(WorkspaceLocalEffect.WRITE, "new", stage.name, f.storage.key(stage, false), intended.size.toLong(), intended.safDigest())
            val effect = local.prepare(prep)
            local.beforeAttempt(effect)
            Files.move(stage.toPath(), f.workspace.resolve("new").toPath())
            local.returned(effect, WorkspaceLocalReceipt(prep, true, f.storage.key(f.workspace.resolve("new"), false)))
            local.verify(effect)
            val provider = f.helper(guard)
            val before = provider.captureBeforeTree("sentinel.bin", f.calls.document("sentinel.bin"))
            assertArrayEquals(original, f.journal.file(f.operation, before.image.entries.single().payload!!.name).readBytes())
            val created = provider.prepare(SafEffectPlan(SafEffectKind.CREATE_FILE, "new", null, f.calls.document(""), null, null, null, requestedMimeType = binary))
            val write = provider.prepare(SafEffectPlan(SafEffectKind.WRITE_FILE, "new", null, f.calls.document(""), provider.actionId(created), null, f.payload(intended)))
            provider.createFile(created); provider.writeFile(write)
            assertEquals(2, f.calls.mutations)
            assertArrayEquals(intended, f.calls.nodes.getValue("new").bytes)
            assertTrue(f.operation.effects.values.all { it.phase == SafEffectPhase.VERIFIED })
            f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun unresolvedOrChangedLocalReceiptPreventsEveryProviderCall(): Unit = runBlocking {
        for (phase in listOf("prepared", "attempted", "returned", "changed-verified")) {
            val f = Fixture(request = SafOperationRequest(SafOperationKind.WRITE, listOf("new")))
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val stage = f.workspace.resolve("owned-stage").apply { writeBytes(intended) }
                val local = SafLocalEffects(guard, f.journal, f.operation) {}
                val prep = WorkspaceLocalPreparation(WorkspaceLocalEffect.WRITE, "new", stage.name, f.storage.key(stage, false), intended.size.toLong(), intended.safDigest())
                val effect = local.prepare(prep)
                if (phase != "prepared") local.beforeAttempt(effect)
                if (phase in setOf("returned", "changed-verified")) {
                    Files.move(stage.toPath(), f.workspace.resolve("new").toPath())
                    local.returned(effect, WorkspaceLocalReceipt(prep, true, f.storage.key(f.workspace.resolve("new"), false)))
                }
                if (phase == "changed-verified") {
                    local.verify(effect)
                    f.journal.file(f.operation, SafOperationJournal.actionName(effect.action.id, SafEffectPhase.VERIFIED)).appendText(" ")
                }
                assertTrue(phase, runCatching { f.helper(guard) }.isFailure)
                assertEquals(0, f.calls.mutations); f.assertSentinel()
            }
        }
    }

    private inner class Fixture(val limits: SafJournalLimits = SafJournalLimits(), scopes: List<String> = listOf(""),
        reservation: SafJournalReservation = SafJournalReservation(16, 2L * 1024 * 1024, 2L * 1024 * 1024, evidenceFiles = 32),
        request: SafOperationRequest = SafOperationRequest(SafOperationKind.SYNC, listOf("new"))) {
        val root = Files.createTempDirectory("droide-provider-effects-").toFile().also { roots += it }
        val workspace = root.resolve("workspace").apply { mkdir() }
        val sentinel = workspace.resolve("sentinel.bin").apply { writeBytes(original) }
        val privateRoot = root.resolve("journal")
        var beforeRead: (File) -> Unit = {}
        var beforeForceFile: (File) -> Unit = {}
        var cancel = false
        val storage = SafOperationStorage(
            forceDirectory = { FileChannel.open(it.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { c -> c.force(true) } },
            forceFile = { beforeForceFile(it); FileChannel.open(it.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { c -> c.force(true) } },
            replaceAtomically = { _, _ -> error("Unwired helper cannot publish manifest") }, beforeRead = { beforeRead(it) })
        val journal = SafOperationJournal.create(workspace, tree.toString(), privateRoot, storage, limits)
        val operation = journal.begin(request, scopes,
            reservation)
        val calls = Calls().apply { add("sentinel.bin", binary, original) }
        val evidence = linkedMapOf<String, ByteArray>()
        fun helper(guard: WorkspaceMutationGuard) = SafProviderEffects(guard, journal, operation, calls) { if (cancel) throw CancellationException("fixture cancelled") }
        fun payload(bytes: ByteArray): SafEvidenceFile = journal.preserveInput(operation, ByteArrayInputStream(bytes), bytes.size.toLong()).also { evidence[it.name] = bytes.copyOf() }
        fun newDirectory(helper: SafProviderEffects, path: String, parent: SafProviderEffects.Effect? = null, knownParent: String = "") =
            helper.prepare(SafEffectPlan(SafEffectKind.CREATE_DIRECTORY, path, null, if (parent == null) calls.document(knownParent) else null,
                null, null, null, requestedMimeType = directory, parentCreationActionId = parent?.let { helper.actionId(it) }))
        fun newFile(helper: SafProviderEffects, path: String, parent: SafProviderEffects.Effect) =
            helper.prepare(SafEffectPlan(SafEffectKind.CREATE_FILE, path, null, null, null, null, null,
                requestedMimeType = binary, parentCreationActionId = helper.actionId(parent)))
        fun newWrite(helper: SafProviderEffects, path: String, parent: SafProviderEffects.Effect, file: SafProviderEffects.Effect) =
            helper.prepare(SafEffectPlan(SafEffectKind.WRITE_FILE, path, null, null, helper.actionId(file), null, payload(intended),
                parentCreationActionId = helper.actionId(parent)))
        fun prepare(helper: SafProviderEffects, kind: SafEffectKind): SafProviderEffects.Effect {
            return when (kind) {
                SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY -> helper.prepare(SafEffectPlan(kind, "new", null, calls.document(""), null, null, null,
                    requestedMimeType = if (kind == SafEffectKind.CREATE_DIRECTORY) directory else binary))
                SafEffectKind.WRITE_FILE, SafEffectKind.DELETE -> {
                    calls.add("old", binary, original)
                    val before = helper.captureBeforeTree("old", calls.document("old"))
                    val previous = before.image.entries.single().payload!!
                    evidence[previous.name] = original.copyOf()
                    helper.prepare(SafEffectPlan(kind, "old", calls.document("old"), calls.document(""), null,
                        if (kind == SafEffectKind.WRITE_FILE) previous else null,
                        if (kind == SafEffectKind.WRITE_FILE) payload(intended) else null,
                        beforeTree = if (kind == SafEffectKind.DELETE) before.reference else null))
                }
                else -> error("Unsupported fixture kind")
            }
        }
        fun execute(helper: SafProviderEffects, effect: SafProviderEffects.Effect, kind: SafEffectKind) {
            when (kind) {
                SafEffectKind.CREATE_FILE -> helper.createFile(effect)
                SafEffectKind.CREATE_DIRECTORY -> helper.createDirectory(effect)
                SafEffectKind.WRITE_FILE -> helper.writeFile(effect)
                SafEffectKind.DELETE -> helper.deleteTree(effect)
                else -> error("Unsupported fixture kind")
            }
        }
        fun assertEvidence() { evidence.forEach { (name, bytes) -> assertArrayEquals(bytes, journal.file(operation, name).readBytes()) } }
        fun assertSentinel() { assertArrayEquals(original, sentinel.readBytes()); assertArrayEquals(original, calls.nodes.getValue("sentinel.bin").bytes) }
        fun reopen() = SafOperationJournal.open(workspace, tree.toString(), privateRoot, storage, limits)
    }
}
