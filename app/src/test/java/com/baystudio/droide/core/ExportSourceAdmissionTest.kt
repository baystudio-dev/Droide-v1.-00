package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ExportSourceAdmissionTest {
    private val roots = mutableListOf<File>()
    private fun root() = Files.createTempDirectory("export-admission-").toFile().also { roots += it }
    @After fun cleanup() { roots.forEach { PathSecurity.deleteTreeNoFollow(it) } }
    private class Documents : WorkspaceDocumentAuthority {
        var values = emptyList<WorkspaceDocumentSnapshot>()
        override suspend fun snapshot(path: String) = values.firstOrNull { it.path == path }
        override suspend fun snapshots() = values
    }
    private fun dirty(path: String, version: Int = 1) = WorkspaceDocumentSnapshot(path, "edit", true,
        WorkspaceDocumentKind.TEXT, true, true, version, version, 0, 0, 0)
    private fun owner(root: File, docs: Documents = Documents(), safe: () -> Unit = {}, epoch: () -> Boolean = { true }, blockers: () -> List<String> = { emptyList() }) =
        ExportSourceAdmission("project", root, docs, safe, knownWriterBlockers = blockers, requireEpoch = { "epoch" }, epochOpen = { epoch() })
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected refusal") } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
    }

    @Test fun stalePickerAndDifferentRuntimeDoNotRetarget() = runBlocking {
        val root = root(); root.resolve("a").writeText("A")
        val first = owner(root); val second = owner(root)
        val ticket = first.ticket(ExportScope("a"))
        rejects { second.validate(ticket) }
        first.ticket(ExportScope("a"))
        rejects { first.validate(ticket) }
    }
    @Test fun rootAndAncestorSymlinkAreRefusedButLeafSymlinkIsNotFollowed() = runBlocking {
        val root = root(); val outside = root(); outside.resolve("secret").writeText("secret")
        Files.createSymbolicLink(root.resolve("link").toPath(), outside.toPath())
        val owner = owner(root)
        rejects { owner.ticket(ExportScope("link/secret")) }
        owner.validate(owner.ticket(ExportScope("link")))
        val old = owner.ticket(ExportScope(""))
        val moved = root.resolveSibling(root.name + "-old").also { roots += it }
        Files.move(root.toPath(), moved.toPath()); root.mkdir()
        rejects { owner.validate(old) }
    }
    @Test fun selectionUncertaintyAndEpochExitRefuseLateCallback() = runBlocking {
        val root = root(); var certain = true; var open = true
        val owner = owner(root, safe = { check(certain) }, epoch = { open })
        val ticket = owner.ticket(ExportScope(""))
        certain = false; rejects { owner.validate(ticket) }
        certain = true; open = false; rejects { owner.validate(ticket) }
    }
    @Test fun dirtyChoiceIsScopeBoundAndNewerEditsRequireAnotherChoice() = runBlocking {
        val root = root(); root.resolve("folder").mkdir(); root.resolve("folder/file").writeText("disk")
        val docs = Documents().apply { values = listOf(dirty("folder/file"), dirty("other")) }
        val owner = owner(root, docs)
        val ticket = owner.ticket(ExportScope("folder"))
        assertEquals(setOf("folder/file"), ticket.dirtyVersions.keys)
        rejects { owner.prepare(ticket, false) }
        owner.prepare(ticket, true)
        docs.values = listOf(dirty("folder/file", 2), dirty("other"))
        rejects { owner.prepare(ticket, true) }
        docs.values = listOf(dirty("other"))
        assertTrue(owner.prepare(ticket, false).dirtyVersions.isEmpty())
    }
    @Test fun acknowledgedScopedSaveCanReplaceLeafButNeverRoot() = runBlocking {
        val root = root(); root.resolve("file").writeText("old")
        val docs = Documents().apply { values = listOf(dirty("file")) }
        val owner = owner(root, docs); val ticket = owner.ticket(ExportScope("file"))
        val replacement = root.resolve("replacement").apply { writeText("saved") }
        Files.move(replacement.toPath(), root.resolve("file").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        rejects { owner.validate(ticket) }
        rejects { owner.refreshAfterScopedSave(ticket, setOf("file")) }
        docs.values = emptyList()
        rejects { owner.refreshAfterScopedSave(ticket, emptySet()) }
        val refreshed = owner.refreshAfterScopedSave(ticket, setOf("file"))
        owner.validate(owner.prepare(refreshed, false))
        assertEquals(ticket.generation, refreshed.generation)
        assertEquals(ticket.rootIdentity, refreshed.rootIdentity)
    }

    @Test fun externalAssumptionAndKnownActiveWriterAreRequired() = runBlocking {
        val root = root(); var active = false
        val owner = owner(root, blockers = { if (active) listOf("build") else emptyList() })
        val ticket = owner.ticket(ExportScope(""))
        rejects { owner.withCapture(ticket, false) { } }
        active = true; rejects { owner.withCapture(ticket, true) { } }
        active = false
        owner.withCapture(ticket, true) { assertTrue(it.stabilityAssumptions.any { it.contains("no OS snapshot") }) }
    }
    @Test fun mappedWriterAndCaptureAreMutuallyExclusiveWithoutNestedLocks() = runBlocking {
        val root = root(); val owner = owner(root); val ticket = owner.ticket(ExportScope(""))
        ExportWriterAdmission.withSuspendingWriter(root, "Git") {
            rejects { owner.withCapture(ticket, true) { } }
        }
        owner.withCapture(ticket, true) {
            rejects { ExportWriterAdmission.withWriter(root, "Git") { fail("Writer entered") } }
        }
        ExportWriterAdmission.withWriter(root, "Git") { }
    }
    @Test fun repositoryWriterIsRefusedDuringCaptureAndWorksAfterRelease() = runBlocking {
        withTimeout(5000) {
            val root = root(); val owner = owner(root); val ticket = owner.ticket(ExportScope(""))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val capture = launch(start = CoroutineStart.UNDISPATCHED) {
                owner.withCapture(ticket, true) { entered.complete(Unit); release.await() }
            }
            entered.await()
            var wrote = false
            rejects { WorkspaceFileMutationLocks.withRoot(root) { wrote = true } }
            assertFalse(wrote)
            release.complete(Unit); capture.join()
            WorkspaceFileMutationLocks.withRoot(root) { wrote = true }; assertTrue(wrote)
        }
    }
    @Test fun switchDrainsPresealCaptureAndRetiresLatePickerWithoutWaitingForTransfer() = runBlocking {
        withTimeout(5000) {
            val root = root(); val owner = owner(root); val ticket = owner.ticket(ExportScope(""))
            val capture = launch(start = CoroutineStart.UNDISPATCHED) { owner.withCapture(ticket, true) { awaitCancellation() } }
            owner.pauseAndDrain(); capture.join(); rejects { owner.validate(ticket) }
            owner.resume()
            val next = owner.ticket(ExportScope(""))
            val sealed = CompletableDeferred<Unit>(); val releaseTransfer = CompletableDeferred<Unit>()
            val transfer = launch(start = CoroutineStart.UNDISPATCHED) {
                owner.withCapture(next, true) { }; sealed.complete(Unit); releaseTransfer.await()
            }
            sealed.await(); owner.pauseAndDrain()
            assertTrue(transfer.isActive)
            releaseTransfer.complete(Unit); transfer.join()
        }
    }
    @Test fun exitDuringCaptureInvalidatesPermitAndRetirementCannotBeReopened() = runBlocking {
        val root = root(); var open = true
        val owner = owner(root, epoch = { open }); val ticket = owner.ticket(ExportScope(""))
        rejects { owner.withCapture(ticket, true) { permit -> open = false; permit.validate() } }
        owner.retire(); owner.resume(); open = true
        rejects { owner.ticket(ExportScope("")) }
    }
}
