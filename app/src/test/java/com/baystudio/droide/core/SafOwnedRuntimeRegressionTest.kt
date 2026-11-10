package com.baystudio.droide.core

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SafPreservationDirectoryOs::class])
class SafOwnedRuntimeRegressionTest {
    private val roots = arrayListOf<File>()
    private val original = byteArrayOf(0, -1, 127, 10, 42)
    private val replacement = byteArrayOf(60, 0, -9, 20)

    @After fun cleanup() {
        try { assertEquals(0, SafPreservationDirectoryOs.openCount()) }
        finally { SafPreservationDirectoryOs.reset(); roots.asReversed().forEach { PathSecurity.deleteTreeNoFollow(it) } }
    }

    @Test(timeout = 30_000) fun nestedAfterOnlyWriteAndDirectoryDeclareEveryManifestAncestor(): Unit = runBlocking {
        val f = fixture()
        f.local.resolve("new/inner/data.bin").apply { parentFile!!.mkdirs(); writeBytes(replacement) }
        f.mirror().apply(FileRepository.Mutation.Write("new/inner/data.bin"))
        assertArrayEquals(replacement, f.external.resolve("new/inner/data.bin").readBytes())
        assertEquals("d", f.image().entries["new"])
        assertEquals("d", f.image().entries["new/inner"])
        f.local.resolve("folders/deep/empty").mkdirs()
        f.mirror().apply(FileRepository.Mutation.CreateDirectory("folders/deep/empty"))
        assertTrue(f.external.resolve("folders/deep/empty").isDirectory)
        assertEquals("d", f.image().entries["folders/deep"])
        f.mirror().refreshFromTree()
        f.sentinels()
        assertArrayEquals(replacement, f.local.resolve("new/inner/data.bin").readBytes())
    }

    @Test(timeout = 30_000) fun repositoryParticipantWritesMovesAndRetainsExactDeletedOriginal(): Unit = runBlocking {
        val f = fixture()
        val mirror = f.mirror()
        val repository = FileRepository(f.local, newFilePublisher = NewProjectFilePublisher.platform, participant = mirror.repositoryParticipant())
        repository.writeText("created/inner/text.txt", "owned bytes\n")
        assertEquals("owned bytes\n", f.external.resolve("created/inner/text.txt").readText())
        assertTrue(repository.move("created/inner/text.txt", "moved/leaf.txt"))
        assertFalse(f.external.resolve("created/inner/text.txt").exists())
        assertEquals("owned bytes\n", f.external.resolve("moved/leaf.txt").readText())
        assertTrue(repository.delete("moved/leaf.txt"))
        assertFalse(f.external.resolve("moved/leaf.txt").exists())
        assertFalse(f.local.resolve("moved/leaf.txt").exists())
        val deleted = f.records().single { it.phase == SafEffectPhase.VERIFIED && it.plan.local?.effect == SafLocalEffect.DELETE_STAGE }
        val retained = f.local.resolve(deleted.plan.local!!.destination.path)
        assertEquals("owned bytes\n", retained.readText())
        assertEquals(deleted.localVerified!!.destination.key, SafOperationStorage().key(retained, false))
        f.sentinels()
    }

    @Test(timeout = 30_000) fun directoryDeleteKeepsActualSafCustodyInsteadOfLocalDirectoryCustody(): Unit = runBlocking {
        val f = fixture()
        for (root in listOf(f.local, f.external)) {
            root.resolve("folder/nested/child.bin").apply { parentFile!!.mkdirs(); writeBytes(original) }
            root.resolve("folder/empty").mkdir()
        }
        f.seed(mapOf("folder" to "d", "folder/nested" to "d", "folder/nested/child.bin" to fingerprint(original), "folder/empty" to "d"))
        val mirror = f.mirror()
        var moves = 0
        val repository = FileRepository(f.local, newFilePublisher = NewProjectFilePublisher.platform,
            pathMover = WorkspacePathMove { source, destination -> moves++; WorkspacePathMove.platform.move(source, destination) },
            participant = mirror.repositoryParticipant())
        assertTrue(repository.delete("folder"))
        assertEquals(1, moves)
        assertFalse(f.local.resolve("folder").exists())
        assertFalse(f.external.resolve("folder").exists())
        val receipt = f.records().single { it.phase == SafEffectPhase.VERIFIED && it.plan.local?.effect == SafLocalEffect.DELETE_STAGE }
        val path = receipt.plan.local!!.destination.path
        assertTrue(path.startsWith(".droide/saf-delete-custody/"))
        assertFalse(f.local.resolve(".droide/local-delete-custody").exists())
        assertTrue(repository.retainedWork.value.notices.none { it.purpose == RetainedWorkPurpose.DIRECTORY_DELETE })
        val retained = f.local.resolve(path)
        val key = SafOperationStorage().key(retained, true)
        assertArrayEquals(original, retained.resolve("nested/child.bin").readBytes())
        assertTrue(retained.resolve("empty").isDirectory)
        mirror.refreshFromTree()
        assertEquals(1, moves)
        assertEquals(key, SafOperationStorage().key(retained, true))
        assertArrayEquals(original, retained.resolve("nested/child.bin").readBytes())
        assertFalse(f.external.resolve(".droide").exists())
        f.sentinels()
    }

    @Test(timeout = 30_000) fun uncertainSafDirectoryDeleteKeepsTreeAndDoesNotSwitchToLocalCustodyOrReplay(): Unit = runBlocking {
        val f = fixture()
        for (root in listOf(f.local, f.external)) {
            root.resolve("folder/child.bin").apply { parentFile!!.mkdirs(); writeBytes(original) }
        }
        f.seed(mapOf("folder" to "d", "folder/child.bin" to fingerprint(original)))
        val mirror = f.mirror()
        val repository = FileRepository(f.local, newFilePublisher = NewProjectFilePublisher.platform,
            participant = mirror.repositoryParticipant())
        f.provider.afterDelete = { throw IOException("provider returned uncertain directory deletion") }
        assertNotNull(runCatching { repository.delete("folder") }.exceptionOrNull())
        val receipt = f.records().single { it.phase == SafEffectPhase.VERIFIED && it.plan.local?.effect == SafLocalEffect.DELETE_STAGE }
        val path = receipt.plan.local!!.destination.path
        assertTrue(path.startsWith(".droide/saf-delete-custody/"))
        val retained = f.local.resolve(path)
        val key = SafOperationStorage().key(retained, true)
        assertArrayEquals(original, retained.resolve("child.bin").readBytes())
        assertFalse(f.local.resolve("folder").exists())
        assertFalse(f.local.resolve(".droide/local-delete-custody").exists())
        val mutations = f.provider.mutations
        f.provider.afterDelete = {}
        assertNotNull(runCatching { mirror.refreshFromTree() }.exceptionOrNull())
        assertEquals(mutations, f.provider.mutations)
        assertEquals(key, SafOperationStorage().key(retained, true))
        assertArrayEquals(original, retained.resolve("child.bin").readBytes())

        assertFalse(f.external.resolve(".droide").exists())
        f.sentinels()
    }

    @Test(timeout = 30_000) fun fullRefreshReplacementRejectsChangedOriginalBeforeAnyProviderEffect(): Unit = runBlocking {
        for (directoryBefore in listOf(false, true)) {
            val f = fixture()
            for (root in listOf(f.local, f.external)) {
                if (directoryBefore) root.resolve("change/old.bin").apply { parentFile!!.mkdirs(); writeBytes(original) }
                else root.resolve("change").writeBytes(original)
            }
            f.seed(if (directoryBefore) mapOf("change" to "d", "change/old.bin" to fingerprint(original)) else mapOf("change" to fingerprint(original)))
            val before = f.manifest.readBytes()
            PathSecurity.deleteTreeNoFollow(f.local.resolve("change"))
            if (directoryBefore) f.local.resolve("change").writeBytes(replacement)
            else f.local.resolve("change/new.bin").apply { parentFile!!.mkdirs(); writeBytes(replacement) }
            val newer = byteArrayOf(92, 0, 93)
            var changed = false
            val failure = runCatching { f.mirror().refreshFromTree(onProgress = { progress ->
                if (progress.phase == SafMirrorPhase.APPLYING && !changed) {
                    changed = true
                    (if (directoryBefore) f.external.resolve("change/newer.bin") else f.external.resolve("change")).writeBytes(newer)
                }
            }) }.exceptionOrNull()
            assertTrue(changed)
            assertNotNull(failure)
            assertEquals(0, f.provider.mutations)
            assertArrayEquals(before, f.manifest.readBytes())
            if (directoryBefore) assertArrayEquals(original, f.external.resolve("change/old.bin").readBytes())
            assertArrayEquals(newer, (if (directoryBefore) f.external.resolve("change/newer.bin") else f.external.resolve("change")).readBytes())
            assertArrayEquals(replacement, (if (directoryBefore) f.local.resolve("change") else f.local.resolve("change/new.bin")).readBytes())
            f.sentinels()
        }
    }

    @Test(timeout = 30_000) fun writeBeforeimageCannotAdoptProviderEditDuringPreparation(): Unit = runBlocking {
        val f = fixture()
        f.local.resolve("sentinel.bin").writeBytes(replacement)
        val before = f.manifest.readBytes()
        var reads = 0
        val newer = byteArrayOf(91, 92, 93)
        f.provider.beforeRead = { id -> if (id == "root/sentinel.bin" && ++reads == 2) f.external.resolve("sentinel.bin").writeBytes(newer) }
        val failure = runCatching { f.mirror().apply(FileRepository.Mutation.Write("sentinel.bin")) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("beforeimage changed"))
        assertEquals(0, f.provider.mutations)
        assertArrayEquals(newer, f.external.resolve("sentinel.bin").readBytes())
        assertArrayEquals(replacement, f.local.resolve("sentinel.bin").readBytes())
        assertArrayEquals(before, f.manifest.readBytes())
    }

    @Test(timeout = 30_000) fun providerFailureAfterTruncateRetainsBothPayloadsAndNeverReplays(): Unit = runBlocking {
        val f = fixture()
        val before = f.manifest.readBytes()
        f.local.resolve("sentinel.bin").writeBytes(replacement)
        f.provider.afterWriteOpen = { throw IOException("provider failed after truncate") }
        assertTrue(runCatching { f.mirror().apply(FileRepository.Mutation.Write("sentinel.bin")) }.isFailure)
        val raw = f.records().single { it.plan.kind == SafEffectKind.WRITE_FILE && it.phase == SafEffectPhase.RETURNED }
        assertEquals(SafEffectReturn.THREW, raw.result)
        assertEquals(SafProviderReturnShape.THREW, raw.providerReturn!!.shape)
        val directory = f.journal.resolve(raw.operationId)
        assertArrayEquals(original, directory.resolve(raw.plan.previous!!.name).readBytes())
        assertArrayEquals(replacement, directory.resolve(raw.plan.intended!!.name).readBytes())
        assertArrayEquals(replacement, f.local.resolve("sentinel.bin").readBytes())
        assertArrayEquals(before, f.manifest.readBytes())
        val count = f.provider.mutations
        assertTrue(runCatching { f.mirror().apply(FileRepository.Mutation.Write("sentinel.bin")) }.isFailure)
        assertEquals(count, f.provider.mutations)
    }

    @Test(timeout = 30_000) fun cancellationAfterCreateKeepsRawReturnedUriBeforeIdentityQueries(): Unit = runBlocking {
        val f = fixture()
        f.local.resolve("new.bin").writeBytes(replacement)
        val before = f.manifest.readBytes()
        val child = launch {
            val self = this
            f.provider.afterCreate = { self.cancel(CancellationException("cancel after provider return")) }
            runCatching { f.mirror().apply(FileRepository.Mutation.Write("new.bin")) }
        }
        child.join()
        assertTrue(child.isCancelled)
        val raw = f.records().single { it.plan.kind == SafEffectKind.CREATE_FILE && it.phase == SafEffectPhase.RETURNED }
        assertEquals(SafEffectReturn.COMPLETED, raw.result)
        assertTrue(raw.providerReturn!!.uri!!.contains("new.bin"))
        assertFalse(f.records().any { it.actionId == raw.actionId && it.phase == SafEffectPhase.VERIFIED })
        assertArrayEquals(before, f.manifest.readBytes())
        assertArrayEquals(replacement, f.local.resolve("new.bin").readBytes())
        val count = f.provider.mutations
        assertTrue(runCatching { f.mirror().apply(FileRepository.Mutation.Write("new.bin")) }.isFailure)
        assertEquals(count, f.provider.mutations)
        f.sentinels()
    }

    @Test(timeout = 30_000) fun cancellationDuringRefreshPreflightHasNoProviderOrCanonicalEffect(): Unit = runBlocking {
        val f = fixture()
        f.local.resolve("new.bin").writeBytes(replacement)
        val before = f.manifest.readBytes()
        var reached = false
        val child = launch {
            val self = this
            runCatching { f.mirror().refreshFromTree(onProgress = {
                if (it.phase == SafMirrorPhase.RESOLVING) { reached = true; self.cancel(CancellationException("preflight cancelled")) }
            }) }
        }
        child.join()
        assertTrue(reached)
        assertTrue(child.isCancelled)
        assertEquals(0, f.provider.mutations)
        assertFalse(f.external.resolve("new.bin").exists())
        assertArrayEquals(replacement, f.local.resolve("new.bin").readBytes())
        assertArrayEquals(before, f.manifest.readBytes())
        assertFalse(f.journal.exists())
        assertFalse(f.parent.list()!!.any { it.startsWith(".local.saf-stage-") })
        f.sentinels()
    }

    @Test(timeout = 30_000) fun explicitRetryAbandonsOnlyPreparedAuthorityAndRetainsEveryByte(): Unit = runBlocking {
        val f = fixture()
        f.local.resolve("new.bin").writeBytes(replacement)
        val journal = f.core()
        val pending = journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("new.bin")), listOf(""), smallReservation())
        val payload = journal.preserve(pending, f.local.resolve("new.bin"))
        journal.prepare(pending, SafEffectPlan(SafEffectKind.CREATE_FILE, "new.bin", null, f.parentDocument(), null, null, null,
            requestedMimeType = "application/octet-stream"))
        val retained = journal.directory.resolve(pending.id).listFiles()!!.associate { it.name to it.readBytes() }
        assertEquals(0, f.provider.mutations)
        assertFalse(journal.file(pending, "abandoned.json").exists())

        f.mirror().apply(FileRepository.Mutation.Write("new.bin"))

        assertTrue(journal.file(pending, "abandoned.json").isFile)
        retained.forEach { (name, bytes) -> assertArrayEquals(bytes, journal.file(pending, name).readBytes()) }
        assertArrayEquals(replacement, journal.file(pending, payload.name).readBytes())
        assertArrayEquals(replacement, f.external.resolve("new.bin").readBytes())
        f.sentinels()
    }

    @Test(timeout = 30_000) fun cancelledPreparationCanRetryOnlyOnTheNextExplicitInvocation(): Unit = runBlocking {
        val f = fixture()
        f.local.resolve("sentinel.bin").writeBytes(replacement)
        val before = f.manifest.readBytes()
        val child = launch {
            val self = this
            f.provider.beforeRead = { self.cancel(CancellationException("cancel before provider attempt")) }
            runCatching { f.mirror().apply(FileRepository.Mutation.Write("sentinel.bin")) }
        }
        child.join()
        assertTrue(child.isCancelled)
        assertEquals(0, f.provider.mutations)
        assertArrayEquals(before, f.manifest.readBytes())
        val pending = f.journal.listFiles()!!.single { it.isDirectory }
        val retained = pending.listFiles()!!.associate { it.name to it.readBytes() }
        assertFalse(pending.resolve("abandoned.json").exists())
        f.provider.beforeRead = {}

        f.mirror().apply(FileRepository.Mutation.Write("sentinel.bin"))

        assertTrue(pending.resolve("abandoned.json").isFile)
        retained.forEach { (name, bytes) -> assertArrayEquals(bytes, pending.resolve(name).readBytes()) }
        assertArrayEquals(replacement, f.local.resolve("sentinel.bin").readBytes())
        assertArrayEquals(replacement, f.external.resolve("sentinel.bin").readBytes())
    }

    @Test(timeout = 30_000) fun corruptPreparedEvidenceNeverAbandonsOrStartsAnEffect(): Unit = runBlocking {
        val f = fixture()
        f.local.resolve("new.bin").writeBytes(replacement)
        val journal = f.core()
        val pending = journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("new.bin")), listOf(""), smallReservation())
        val action = journal.prepare(pending, SafEffectPlan(SafEffectKind.CREATE_FILE, "new.bin", null, f.parentDocument(), null, null, null,
            requestedMimeType = "application/octet-stream"))
        journal.file(pending, SafOperationJournal.actionName(action.id, SafEffectPhase.PREPARED)).writeText("{")
        val retained = journal.directory.resolve(pending.id).listFiles()!!.associate { it.name to it.readBytes() }
        assertTrue(runCatching { f.mirror().apply(FileRepository.Mutation.Write("new.bin")) }.isFailure)
        assertEquals(0, f.provider.mutations)
        assertFalse(journal.file(pending, "abandoned.json").exists())
        retained.forEach { (name, bytes) -> assertArrayEquals(bytes, journal.file(pending, name).readBytes()) }
        assertArrayEquals(replacement, f.local.resolve("new.bin").readBytes())
        f.sentinels()
    }

    @Test(timeout = 30_000) fun unownedBackupCleanupNeverUsesPathnameAsAuthority(): Unit = runBlocking {
        val f = fixture()
        val collision = f.parent.resolve("uncreated-backup-name").apply { mkdir(); resolve("unowned.bin").writeBytes(replacement) }
        val cleanup = SafMirror::class.java.getDeclaredMethod("cleanupOwnedArtifact", File::class.java, String::class.java)
            .apply { isAccessible = true }
        assertEquals(true, cleanup.invoke(f.mirror(), collision, null))
        assertArrayEquals(replacement, collision.resolve("unowned.bin").readBytes())
        assertTrue(runCatching { cleanup.invoke(f.mirror(), collision, "different-owned-key") }.isFailure)
        assertArrayEquals(replacement, collision.resolve("unowned.bin").readBytes())
        assertEquals(0, f.provider.mutations)
        assertFalse(f.journal.exists())
        f.sentinels()
    }

    @Test(timeout = 30_000) fun protectedNameBytesAddedToBackupAreNeverCleaned(): Unit = runBlocking {
        for (protected in listOf(".git", ".droide")) {
            val f = fixture()
            // A real external-only change must reach activation rather than the unchanged fast path.
            val changed = f.external.resolve("refresh-change.bin").apply { writeBytes(byteArrayOf(1)) }
            assertTrue(changed.isFile)
            assertFalse(f.local.resolve(changed.name).exists())
            val mirror = f.mirror()
            var retained: File? = null
            SafMirror::class.java.getDeclaredField("activationMarker").apply { isAccessible = true }.set(mirror,
                object : File(f.local.resolve(".droide/saf_activation_pending.json").path) {
                    override fun delete(): Boolean {
                        retained = f.parent.listFiles()!!.single { it.name.startsWith(".local.saf-backup-") }
                        requireNotNull(retained).resolve("$protected/newer.bin").apply { parentFile!!.mkdir(); writeBytes(replacement) }
                        return super.delete()
                    }
                })
            val failure = runCatching { mirror.refreshFromTree() }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("protected-name"))
            assertArrayEquals(replacement, requireNotNull(retained).resolve("$protected/newer.bin").readBytes())
            assertArrayEquals(original, requireNotNull(retained).resolve("sentinel.bin").readBytes())
            assertTrue(f.parent.listFiles()!!.any { it.name.startsWith(".local.saf-stage-") })
            f.sentinels()
        }
    }

    @Test(timeout = 30_000) fun exchangedEmptyBackupIsRejectedBeforeMovingOriginals(): Unit = runBlocking {
        val f = fixture()
        // A real external-only change must reach activation rather than the unchanged fast path.
        val changed = f.external.resolve("refresh-change.bin").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(changed.isFile)
        assertFalse(f.local.resolve(changed.name).exists())
        var displaced: File? = null
        var replacementBackup: File? = null
        val root = object : File(f.local.path) {
            override fun listFiles(): Array<File>? {
                val backup = f.parent.listFiles()!!.firstOrNull { it.name.startsWith(".local.saf-backup-") }
                if (backup != null && displaced == null) {
                    displaced = f.parent.resolve("retained-owned-backup")
                    Files.move(backup.toPath(), requireNotNull(displaced).toPath())
                    backup.mkdir()
                    replacementBackup = backup
                }
                return super.listFiles()
            }
        }
        val failure = runCatching { SafMirror(RuntimeEnvironment.getApplication(), f.tree.toString(), root).refreshFromTree() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("artifact identity"))
        assertNotNull(displaced)
        assertTrue(requireNotNull(replacementBackup).list()!!.isEmpty())
        assertTrue(requireNotNull(displaced).isDirectory)
        assertTrue(f.local.resolve(".droide/saf_activation_pending.json").isFile)
        assertTrue(f.records().any { it.plan.kind == SafEffectKind.LOCAL_ACTIVATION && it.phase == SafEffectPhase.RETURNED && it.result == SafEffectReturn.THREW })
        f.sentinels()
    }

    @Test(timeout = 30_000) fun successfulDeleteThenRefreshPreservesExactCustodyWithoutUploadingIt(): Unit = runBlocking {
        val f = fixture()
        val mirror = f.mirror()
        val repository = FileRepository(f.local, newFilePublisher = NewProjectFilePublisher.platform, participant = mirror.repositoryParticipant())
        assertTrue(repository.delete("sentinel.bin"))
        val receipt = f.records().single { it.phase == SafEffectPhase.VERIFIED && it.plan.local?.effect == SafLocalEffect.DELETE_STAGE }
        val path = receipt.plan.local!!.destination.path
        val retained = f.local.resolve(path)
        val key = SafOperationStorage().key(retained, false)
        assertArrayEquals(original, retained.readBytes())
        assertFalse(f.external.resolve(path).exists())

        mirror.refreshFromTree()

        assertFalse("Private retained Delete evidence was uploaded by Refresh", f.external.resolve(path).exists())
        assertEquals("Refresh changed retained original identity", key, SafOperationStorage().key(retained, false))
        assertArrayEquals(original, retained.readBytes())
        assertFalse(f.local.resolve("sentinel.bin").exists())
        assertFalse(f.external.resolve("sentinel.bin").exists())
    }

    @Test(timeout = 30_000) fun writeMoveDeleteAndRepeatedRefreshKeepProtectedCustodyWithOneDeleteMove(): Unit = runBlocking {
        val f = fixture()
        val mirror = f.mirror()
        var moves = 0
        val repository = FileRepository(f.local, newFilePublisher = NewProjectFilePublisher.platform,
            pathMover = WorkspacePathMove { source, destination -> moves++; WorkspacePathMove.platform.move(source, destination) },
            participant = mirror.repositoryParticipant())
        repository.writeText("new/deep/file.txt", "retained original")
        assertTrue(repository.move("new/deep/file.txt", "other/file.txt"))
        val beforeDelete = moves
        assertTrue(repository.delete("other/file.txt"))
        assertEquals(beforeDelete + 1, moves)
        val record = f.records().single { it.phase == SafEffectPhase.VERIFIED && it.plan.local?.effect == SafLocalEffect.DELETE_STAGE }
        val plan = record.plan.local!!
        val binding = requireNotNull(plan.deleteCustody)
        assertEquals(".droide/saf-delete-custody/${record.operationId}", binding.directory)
        val retained = f.local.resolve(plan.destination.path)
        val key = SafOperationStorage().key(retained, false)
        val payloads = f.journal.resolve(record.operationId).listFiles()!!.filter { it.name.startsWith("payload-") }
        assertEquals(1, payloads.size) // Required provider beforeimage only; no extra local custody copy.
        assertEquals("retained original", payloads.single().readText())
        repeat(2) { mirror.refreshFromTree() }
        assertEquals(beforeDelete + 1, moves)
        assertEquals(key, SafOperationStorage().key(retained, false))
        assertEquals("retained original", retained.readText())
        assertFalse(f.external.resolve("other/file.txt").exists())
        assertFalse(f.external.resolve(".droide").exists())
        assertTrue(f.image().entries.keys.none { it.startsWith(".droide") })
        f.sentinels()
    }

    @Test(timeout = 30_000) fun uncertainProtectedDeleteRetainsOriginalAndStillBlocksRefresh(): Unit = runBlocking {
        val f = fixture()
        val mirror = f.mirror()
        val repository = FileRepository(f.local, newFilePublisher = NewProjectFilePublisher.platform, participant = mirror.repositoryParticipant())
        f.provider.afterDelete = { throw IOException("provider deleted but failed to return") }
        assertTrue(runCatching { repository.delete("sentinel.bin") }.isFailure)
        val record = f.records().single { it.phase == SafEffectPhase.VERIFIED && it.plan.local?.effect == SafLocalEffect.DELETE_STAGE }
        val retained = f.local.resolve(record.plan.local!!.destination.path)
        val key = SafOperationStorage().key(retained, false)
        assertArrayEquals(original, retained.readBytes())
        assertFalse(f.journal.resolve(record.operationId).resolve("abandoned.json").exists())
        val count = f.provider.mutations
        assertTrue(runCatching { mirror.refreshFromTree() }.isFailure)
        assertEquals(count, f.provider.mutations)
        assertEquals(key, SafOperationStorage().key(retained, false))
        assertArrayEquals(original, retained.readBytes())
        assertFalse(f.external.resolve(".droide").exists())
    }

    @Test(timeout = 30_000) fun oldSiblingDeleteCustodyBlocksRefreshWithoutMigrationOrUpload(): Unit = runBlocking {
        val f = fixture()
        for (root in listOf(f.local, f.external)) root.resolve("folder/old.bin").apply { parentFile!!.mkdir(); writeBytes(original) }
        f.seed(mapOf("folder" to "d", "folder/old.bin" to fingerprint(original)))
        val mirror = f.mirror()
        val oldParticipant = WorkspaceMutationParticipant { guard, mutation ->
            val delegate = mirror.repositoryParticipant().begin(guard, mutation)
            object : WorkspaceMutationSession(guard, mutation) {

                override val retainLocalOnSyncFailure get() = delegate.retainLocalOnSyncFailure
                override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) = delegate.prepareLocal(preparation)
                override fun beforeLocalAttempt(preparation: WorkspaceLocalPreparation) = delegate.beforeLocalAttempt(preparation)
                override fun localReturned(receipt: WorkspaceLocalReceipt) = delegate.localReturned(receipt)
                override suspend fun synchronize() = delegate.synchronize()
                override fun retainsDeleteStage(receipt: WorkspaceLocalReceipt) = delegate.retainsDeleteStage(receipt)
                override fun finish(outcome: WorkspaceMutationOutcome) = delegate.finish(outcome)
            }
        }
        val repository = FileRepository(f.local, newFilePublisher = NewProjectFilePublisher.platform, participant = oldParticipant)
        assertTrue(repository.delete("folder/old.bin"))
        val record = f.records().single { it.phase == SafEffectPhase.VERIFIED && it.plan.local?.effect == SafLocalEffect.DELETE_STAGE }
        assertNull(record.plan.local!!.deleteCustody)
        val retained = f.local.resolve(record.plan.local!!.destination.path)
        val key = SafOperationStorage().key(retained, false)
        f.local.resolve("new.bin").writeBytes(replacement)
        mirror.apply(FileRepository.Mutation.Write("new.bin"))
        assertArrayEquals(replacement, f.external.resolve("new.bin").readBytes())
        val count = f.provider.mutations
        for (mutation in listOf(FileRepository.Mutation.Sync("folder"), FileRepository.Mutation.Delete("folder"), FileRepository.Mutation.Move("folder", "moved"))) {
            assertTrue(runCatching { mirror.apply(mutation) }.isFailure)
            assertEquals(key, SafOperationStorage().key(retained, false))
            assertArrayEquals(original, retained.readBytes())
        }
        val failure = runCatching { mirror.refreshFromTree() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("legacy sibling Delete custody"))
        assertEquals(count, f.provider.mutations)
        assertEquals(key, SafOperationStorage().key(retained, false))
        assertArrayEquals(original, retained.readBytes())
        assertFalse(f.external.resolve(record.plan.local!!.destination.path).exists())
        f.sentinels()
    }

    private fun smallReservation() = SafJournalReservation(8, 1024 * 1024, 8L * 1024 * 1024, evidenceFiles = 16)

    private fun fingerprint(bytes: ByteArray) = "f:${bytes.safDigest()}"
    private fun fixture(): Fixture {
        val app = RuntimeEnvironment.getApplication()
        val parent = Files.createTempDirectory("saf-owned-runtime-").toFile().also { roots += it }
        SafPreservationDirectoryOs.allow(parent)
        val journalParent = app.filesDir.resolve("saf-owned-operations").apply { mkdirs() }
        SafPreservationDirectoryOs.allow(journalParent)
        val local = parent.resolve("local").apply { mkdir() }
        val external = parent.resolve("external").apply { mkdir() }
        local.resolve("sentinel.bin").writeBytes(original); external.resolve("sentinel.bin").writeBytes(original)
        val authority = "droide.owned.${UUID.randomUUID()}"
        val provider = Provider(external)
        val info = ProviderInfo().apply {
            this.authority = authority; name = "$authority.Provider"; packageName = app.packageName; applicationInfo = app.applicationInfo
            enabled = true; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        }
        provider.attachInfo(app, info)
        Shadows.shadowOf(app.packageManager).apply {
            addOrUpdateProvider(info)
            addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        }
        val bridge = Bridge(provider).apply { attachInfo(app, ProviderInfo().apply { this.authority = authority }) }
        ShadowContentResolver.registerProviderInternal(authority, bridge)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val journal = app.filesDir.resolve("saf-owned-operations").resolve((tree.toString() + "\u0000" + local.absolutePath).toByteArray().safDigest())
        roots += journal
        return Fixture(parent, local, external, provider, tree, journal).also { it.seed(emptyMap()) }
    }
    private inner class Fixture(val parent: File, val local: File, val external: File, val provider: Provider, val tree: Uri, val journal: File) {
        val manifest get() = local.resolve(".droide/saf_manifest.json")
        fun mirror() = SafMirror(RuntimeEnvironment.getApplication(), tree.toString(), local)
        fun core() = SafOperationJournal.create(local, tree.toString(), journal, SafOperationStorage())
        fun parentDocument() = SafOwnedDocument(DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(),
            "root", external.name, DocumentsContract.Document.MIME_TYPE_DIR, "root")
        fun seed(extra: Map<String, String>) {
            manifest.parentFile!!.mkdirs()
            manifest.writeBytes(SafJournalCodec.encode(SafLegacyTrustedManifest.serializer(),
                SafLegacyTrustedManifest(entries = (extra + ("sentinel.bin" to fingerprint(original))).toSortedMap())))
        }
        fun image() = SafJournalCodec.decode(SafManifestImage.serializer(), manifest.readBytes())
        fun records(): List<SafEffectRecord> = journal.listFiles().orEmpty().filter { it.isDirectory }.flatMap { directory ->
            directory.listFiles().orEmpty().filter { it.name.startsWith("action-") }.map { SafJournalCodec.decode(SafEffectRecord.serializer(), it.readBytes()) }
        }
        fun sentinels() { assertArrayEquals(original, local.resolve("sentinel.bin").readBytes()); assertArrayEquals(original, external.resolve("sentinel.bin").readBytes()) }
    }
    private class Provider(private val root: File) : DocumentsProvider() {
        var mutations = 0
        var beforeRead: (String) -> Unit = {}
        var afterWriteOpen: () -> Unit = {}
        var afterCreate: () -> Unit = {}
        var afterDelete: () -> Unit = {}
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(projection).apply { row(this, documentId, file(documentId)) }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) = cursor(projection).apply {
            file(parentDocumentId).listFiles().orEmpty().sortedBy { it.name }.forEach { row(this, "$parentDocumentId/${it.name}", it) }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            if ('w' !in mode) beforeRead(documentId)
            val fd = ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
            if ('w' in mode) {
                mutations++
                try { afterWriteOpen() } catch (failure: Throwable) { fd.close(); throw failure }
            }
            return fd
        }
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            val file = file(parentDocumentId).resolve(PathSecurity.safeLeafName(displayName))
            check(!file.exists())
            check(if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile())
            mutations++; afterCreate()
            return "$parentDocumentId/$displayName"
        }
        override fun deleteDocument(documentId: String) { check(PathSecurity.deleteTreeNoFollow(file(documentId))); mutations++; afterDelete() }
        override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId.startsWith("$parentDocumentId/")
        private fun file(id: String): File { require(id == "root" || id.startsWith("root/")); return if (id == "root") root else root.resolve(id.removePrefix("root/")) }
        private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED))
        private fun row(cursor: MatrixCursor, id: String, file: File) {
            val values = mapOf<String, Any>(DocumentsContract.Document.COLUMN_DOCUMENT_ID to id, DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name,
                DocumentsContract.Document.COLUMN_MIME_TYPE to if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                DocumentsContract.Document.COLUMN_SIZE to if (file.isFile) file.length() else 0L, DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified())
            cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
        }
    }
    private class Bridge(private val delegate: DocumentsProvider) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) =
            delegate.query(uri, projection, Bundle().apply {
                if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                if (sort != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
            }, null)
        override fun query(uri: Uri, projection: Array<out String>?, args: Bundle?, signal: CancellationSignal?) = delegate.query(uri, projection, args, signal)
        override fun getType(uri: Uri) = delegate.getType(uri)
        override fun openFile(uri: Uri, mode: String) = delegate.openFile(uri, mode)
        override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?) = delegate.openFile(uri, mode, signal)
        override fun call(method: String, arg: String?, extras: Bundle?) = delegate.call(method, arg, extras)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("DocumentsContract only")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("DocumentsContract only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("DocumentsContract only")
    }
}
