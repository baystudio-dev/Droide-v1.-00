package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Direct synthetic regression sources; no provider, native rebuild or device execution. */
class AcpAttachmentJournalTest {
    @Test fun successRequiresExactReceiptAndHistoricalReplayDoesNotRecreateDeletedDestination() = fixture { f ->
        var projections = 0
        val ref = f.ref()
        val access = f.bind(saved = { projections++ })
        val result = f.save(access, ref)
        assertTrue(result, result.startsWith("ATTACHMENT_SAVED_TO_PROJECT "))
        val receipt = Json.parseToJsonElement(result.substringAfter(' ')).jsonObject
        val record = f.journal.recovery(f.scope).records.single()
        assertEquals(ProjectCopyJournal.Status.COMMITTED, record.status)
        assertEquals(record.intent.id, receipt.getValue("transaction_id").jsonPrimitive.content)
        assertEquals(record.receipt!!.preparation.publicationId, receipt.getValue("publication_id").jsonPrimitive.content)
        assertEquals(ProjectCopyEvidence.sha256(f.bytes), receipt.getValue("sha256").jsonPrimitive.content)
        assertTrue(receipt.getValue("local_commit_completed").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("current_file_reverified").jsonPrimitive.boolean)
        assertArrayEquals(f.bytes, f.root.resolve("asset.bin").readBytes())
        assertArrayEquals(f.bytes, f.store.file(ref)!!.readBytes())
        f.root.resolve("asset.bin").writeText("User changed current bytes")
        failure { f.save(access, ref) }
        assertEquals("User changed current bytes", f.root.resolve("asset.bin").readText())
        Files.delete(f.root.resolve("asset.bin").toPath())
        val replay = f.save(access, ref)
        assertTrue(replay, replay.startsWith("ATTACHMENT_SAVE_RECEIPT_REPLAY "))
        assertTrue(replay.contains("\"no_new_copy\":true"))
        assertTrue(replay.contains("\"current_file_reverified\":false"))
        assertFalse(f.root.resolve("asset.bin").exists())
        assertEquals(1, projections)
    }

    @Test fun journalUsesStableDomainSeparatedTurnIdentityWithoutPersistingCapability() = fixture { f ->
        val ref = f.ref()
        val first = f.bind()
        f.save(first, ref, "one.bin")
        f.save(first, ref, "two.bin")
        val second = f.bind()
        f.save(second, ref, "three.bin")
        val records = f.journal.recovery(f.scope).records.associateBy { it.intent.destination }
        val firstId = records.getValue("one.bin").intent.owner.runId
        assertEquals(ProjectCopyEvidence.sha256(("droide-acp-attachment-turn-v1\u0000" + first.token).toByteArray(Charsets.UTF_8)), firstId)
        assertEquals(firstId, records.getValue("two.bin").intent.owner.runId)
        assertNotEquals(firstId, records.getValue("three.bin").intent.owner.runId)
        assertNotEquals(first.token, firstId)
        val metadata = f.root.resolve(".droide/copy-recovery").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(metadata.contains(first.token)); assertFalse(metadata.contains(second.token))
    }

    @Test fun replacingProjectRootAfterTurnBindingCannotRetargetAttachmentCopy() = fixture { f ->
        val ref = f.ref()
        val access = f.bind()
        val preserved = File(f.root.parentFile, "preserved-project")
        assertTrue(f.root.renameTo(preserved))
        assertTrue(f.root.mkdir())
        failure { f.save(access, ref) }
        assertFalse(f.root.resolve("asset.bin").exists())
        assertFalse(preserved.resolve("asset.bin").exists())
        assertFalse(f.root.resolve(".droide/copy-recovery").exists())
    }

    @Test fun forgedPrecommitTypedPathAndBytesCannotForgeSuccessOrProjection() = fixture { f ->
        var projections = 0
        val ref = f.ref()
        val files = FileRepository(f.root, beforeMutation = {
            throw ProjectFileSyncException("asset.bin", f.bytes.size.toLong(), IOException("forged"))
        })
        val access = f.bind(files = files, saved = { projections++ })
        assertTrue(failure { f.save(access, ref) } is ProjectFileSyncException)
        assertEquals(0, projections)
        assertFalse(f.root.resolve("asset.bin").exists())
        assertEquals(ProjectCopyJournal.Status.PREPARED, f.journal.recovery(f.scope).records.single().status)
    }

    @Test fun foreignHistoricalExceptionBeforePublicationCannotForgeReceipt() = fixture { f ->
        val ref = f.ref()
        val foreign = f.foreignHistoricalSignal()
        var projections = 0
        val files = FileRepository(f.root, beforeMutation = { throw foreign })
        val access = f.bind(files = files, saved = { projections++ })
        failure { f.save(access, ref) }
        assertFalse(f.root.resolve("asset.bin").exists())
        assertEquals(0, projections)
        assertEquals(ProjectCopyJournal.Status.PREPARED, f.journal.recovery(f.scope).records.single().status)
    }

    @Test fun foreignHistoricalExceptionInLateDestinationGuardCannotForgeReceipt() = fixture { f ->
        val ref = f.ref()
        var checks = 0
        var projections = 0
        val foreign = f.foreignHistoricalSignal()
        val access = f.bind(saved = { projections++ }, destinationAvailable = { if (++checks == 3) throw foreign })
        failure { f.save(access, ref) }
        assertEquals(3, checks)
        assertFalse(f.root.resolve("asset.bin").exists())
        assertEquals(0, projections)
        assertEquals(ProjectCopyJournal.Status.PREPARED, f.journal.recovery(f.scope).records.single().status)
    }

    @Test fun foreignHistoricalExceptionAfterCommitCannotReplaceActualReceipt() = fixture { f ->
        val ref = f.ref()
        val foreign = f.foreignHistoricalSignal()
        val files = FileRepository(f.root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                val replacement = f.root.resolve("replacement.tmp").apply { writeText("later user bytes") }
                Files.move(replacement.toPath(), f.root.resolve(mutation.path).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                throw foreign // Changed target makes Repository preserve the original callback exception.
            }
        })
        val access = f.bind(files = files)
        val result = f.save(access, ref)
        assertTrue(result, result.startsWith("ATTACHMENT_SAVED_TO_PROJECT "))
        val receipt = Json.parseToJsonElement(result.substringAfter(' ')).jsonObject
        val actual = f.journal.recovery(f.scope).records.single()
        assertEquals(ProjectCopyJournal.Status.COMMITTED, actual.status)
        assertEquals(actual.intent.id, receipt.getValue("transaction_id").jsonPrimitive.content)
        assertEquals(actual.receipt!!.preparation.publicationId, receipt.getValue("publication_id").jsonPrimitive.content)
        assertEquals("asset.bin", receipt.getValue("path").jsonPrimitive.content)
        assertEquals(f.bytes.size.toLong(), receipt.getValue("bytes").jsonPrimitive.long)
        assertFalse(receipt.getValue("historical_receipt").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("current_file_reverified").jsonPrimitive.boolean)
        assertFalse(result.contains(foreign.record.intent.id))
        assertEquals("later user bytes", f.root.resolve("asset.bin").readText())
    }

    @Test fun realPostcommitSyncFailureKeepsExactReceiptAndCanProjectSavedBytes() = fixture { f ->
        var projections = 0
        val ref = f.ref()
        val files = FileRepository(f.root, onMutation = { throw IOException("sync failed") })
        val access = f.bind(files = files, saved = { projections++ })
        val result = f.save(access, ref)
        assertTrue(result, result.startsWith("ATTACHMENT_SAVED_TO_PROJECT "))
        assertTrue(result.contains("\"sync_pending\":true"))
        assertTrue(result.contains("\"local_commit_completed\":true"))
        assertEquals(ProjectCopyJournal.Status.COMMITTED, f.journal.recovery(f.scope).records.single().status)
        assertArrayEquals(f.bytes, f.root.resolve("asset.bin").readBytes())
        assertEquals(1, projections)
    }

    @Test fun cancellationAfterCanonicalReceiptPropagatesWithoutErasingCommit() = fixture { f ->
        var projections = 0
        val ref = f.ref()
        val files = FileRepository(f.root, onMutation = { throw CancellationException("cancelled after publication") })
        val access = f.bind(files = files, saved = { projections++ })
        assertTrue(failure { f.save(access, ref) } is CancellationException)
        val record = f.journal.recovery(f.scope).records.single()
        assertEquals(ProjectCopyJournal.Status.COMMITTED, record.status)
        assertNotNull(record.receipt)
        assertArrayEquals(f.bytes, f.root.resolve("asset.bin").readBytes())
        assertEquals(0, projections)
    }

    @Test fun fatalPostcommitFailurePropagatesAndKeepsExactDurableReceipt() = fixture { f ->
        val ref = f.ref()
        var projections = 0
        val files = FileRepository(f.root, onMutation = { throw AssertionError("fatal postcommit fixture") })
        val access = f.bind(files = files, saved = { projections++ })
        val problem = runCatching { f.save(access, ref) }.exceptionOrNull()
        assertTrue(problem is AssertionError)
        assertEquals(ProjectCopyJournal.Status.COMMITTED, f.journal.recovery(f.scope).records.single().status)
        assertArrayEquals(f.bytes, f.root.resolve("asset.bin").readBytes())
        assertEquals(0, projections)
    }

    @Test fun projectionCancellationPreservesHistoricalCommitAndDoesNotRecopy() = fixture { f ->
        val ref = f.ref()
        val access = f.bind(saved = { throw CancellationException("detached projection") })
        assertTrue(failure { f.save(access, ref) } is CancellationException)
        assertEquals(ProjectCopyJournal.Status.COMMITTED, f.journal.recovery(f.scope).records.single().status)
        assertArrayEquals(f.bytes, f.root.resolve("asset.bin").readBytes())
        assertNotNull(failure { f.save(access, ref) })
        assertEquals(1, f.journal.recovery(f.scope).records.size)
    }

    @Test fun closingTurnInLateDestinationGuardPreventsPublication() = fixture { f ->
        val ref = f.ref()
        var checks = 0
        lateinit var access: AcpAttachmentAccess.Lease
        access = f.bind(destinationAvailable = { if (++checks == 3) access.close() })
        assertTrue(failure { f.save(access, ref) } is CancellationException)
        assertEquals(3, checks)
        assertFalse(f.root.resolve("asset.bin").exists())
        assertEquals(ProjectCopyJournal.Status.PREPARED, f.journal.recovery(f.scope).records.single().status)
    }

    @Test fun cancellationWhilePermissionWaitsCreatesNoIntentOrDestination() = fixture { f ->
        val ref = f.ref()
        val entered = CompletableDeferred<Unit>()
        val turn = Job()
        val access = f.bind(turn = turn, permission = { _, _ -> entered.complete(Unit); awaitCancellation() })
        val saving = async { runCatching { f.save(access, ref) }.exceptionOrNull() }
        entered.await(); turn.cancel(); access.finish()
        assertTrue(saving.await() is CancellationException)
        assertTrue(f.journal.recovery(f.scope).records.isEmpty())
        assertFalse(f.root.resolve("asset.bin").exists())
    }

    @Test fun permissionDenialLeavesNoJournalIntentAndNoProjection() = fixture { f ->
        val ref = f.ref()
        var projections = 0
        val access = f.bind(permission = { action, _ -> action != "edit" }, saved = { projections++ })
        failure { f.save(access, ref) }
        assertTrue(f.journal.recovery(f.scope).records.isEmpty())
        assertFalse(f.root.resolve("asset.bin").exists())
        assertEquals(0, projections)
    }

    @Test fun publisherResponseLossBlocksRenamedRetryAcrossTurnsAndRestartButNotUnrelatedSource() = fixture { f ->
        var publications = 0
        var projections = 0
        val ref = f.ref()
        val files = FileRepository(f.root, newFilePublisher = NewProjectFilePublisher { stage, target ->
            publications++
            Files.move(stage.toPath(), target.toPath())
            throw IOException("publication response lost")
        })
        val access = f.bind(files = files, saved = { projections++ })
        val problem = failure { f.save(access, ref) }
        assertTrue(problem.message.orEmpty(), problem.message.orEmpty().contains("ATTACHMENT_COPY_OUTCOME_UNKNOWN"))
        assertTrue(problem.message.orEmpty().contains("\"recovery_controls_available\":false"))
        assertFalse(problem.message.orEmpty().contains("ATTACHMENT_SAVED_TO_PROJECT"))
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, f.journal.recovery(f.scope).records.single().status)
        failure { f.save(access, ref, "renamed.bin") }
        access.finish()
        val restarted = ProjectCopyJournal(f.root, processEpoch = UUID.randomUUID().toString())
        val next = f.bind(journal = restarted)
        failure { f.save(next, ref, "after-restart.bin") }
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, restarted.recovery(f.scope).records.single().status)
        assertEquals(1, publications); assertEquals(0, projections)
        assertFalse(f.root.resolve("renamed.bin").exists()); assertFalse(f.root.resolve("after-restart.bin").exists())
        assertArrayEquals(f.bytes, f.root.resolve("asset.bin").readBytes())
        val unrelated = f.ref("independent.bin")
        val unrelatedTurn = f.bind(journal = restarted)
        assertTrue(f.save(unrelatedTurn, unrelated, "independent.bin").startsWith("ATTACHMENT_SAVED_TO_PROJECT "))
    }

    @Test fun sealForceFailureReturnsNonzeroCliOutcomeAndDoesNotProjectOrRetry() = fixture { f ->
        val ref = f.ref()
        var projections = 0
        val journal = ProjectCopyJournal(f.root, durability = object : ProjectCopyJournal.Durability {
            override fun forceFile(channel: FileChannel) = ProjectCopyJournal.Durability.platform.forceFile(channel)
            override fun forceDirectory(directory: File) {
                if (directory.resolve("seal.json").exists()) throw IOException("seal force failed")
                ProjectCopyJournal.Durability.platform.forceDirectory(directory)
            }
        })
        val access = f.bind(journal = journal, saved = { projections++ })
        val authority = Proxy.newProxyInstance(UnifiedPackageAuthority::class.java.classLoader,
            arrayOf(UnifiedPackageAuthority::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as UnifiedPackageAuthority
        val result = DroideCliTool(authority, f.workspace).executeResult(listOf("attachment", "save", access.token, ref.id, "asset.bin"))
        assertNotEquals(0, result.exitCode)
        assertTrue(result.stderr, result.stderr.contains("ATTACHMENT_COPY_OUTCOME_UNKNOWN"))
        assertEquals("", result.stdout)
        assertEquals(0, projections)
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal.recovery(f.scope).records.single().status)
        failure { f.save(access, ref, "renamed.bin") }
        assertFalse(f.root.resolve("renamed.bin").exists())
        assertArrayEquals(f.bytes, f.root.resolve("asset.bin").readBytes())
    }

    @Test fun oldOrNullAdmissionCannotConsumeLaterGrantButFreshDistinctTurnCan() = fixture { f ->
        val ref = f.ref()
        var now = 100L
        val journal = ProjectCopyJournal(f.root, admissionClockNanos = { now })
        val alreadyActive = f.bind(journal = journal, admitted = 100)
        val old = journal.begin(ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, f.owner,
            ProjectCopyEvidence.sha256(("droide-acp-attachment-turn-v1\u0000" + alreadyActive.token).toByteArray(Charsets.UTF_8)), ref.id), "asset.bin")
        old.beforePublish(StreamPublicationPreparation(UUID.randomUUID().toString(), f.root.canonicalPath,
            ProjectCopyEvidence.root(f.root).second, "asset.bin", f.bytes.size.toLong(), ProjectCopyEvidence.sha256(f.bytes), "old-stage"))
        old.close()
        now = 200
        journal.acknowledge(f.scope, journal.inspect(f.scope, old.intent.id).token)
        now = 300
        failure { f.save(alreadyActive, ref) }
        val unknownAdmission = f.bind(journal = journal)
        failure { f.save(unknownAdmission, ref) }
        assertFalse(f.root.resolve("asset.bin").exists())
        val fresh = f.bind(journal = journal, admitted = now)
        assertTrue(f.save(fresh, ref).startsWith("ATTACHMENT_SAVED_TO_PROJECT "))
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal.recovery(f.scope).records.single { it.intent.id == old.intent.id }.status)
    }

    private class Fixture(val root: File, val parent: Job) {
        val owner = "external-history"
        val workspace = "workspace-test"
        val bytes = byteArrayOf(1, 2, 3, 4)
        val store = ChatMediaStore(root)
        val journal = ProjectCopyJournal(root)
        val scope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, owner)
        val leases = mutableListOf<AcpAttachmentAccess.Lease>()
        fun foreignHistoricalSignal(): ProjectCopyJournal.AlreadyCommitted {
            val intent = ProjectCopyJournal.Intent(id = UUID.randomUUID().toString(), canonicalRoot = root.canonicalPath,
                rootIdentity = ProjectCopyEvidence.root(root).second,
                owner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, "foreign-owner", "foreign-run", "foreign-media"),
                destination = "foreign.bin", createdAt = 1)
            val preparation = StreamPublicationPreparation(UUID.randomUUID().toString(), root.canonicalPath,
                intent.rootIdentity, intent.destination, 999, ProjectCopyEvidence.sha256(byteArrayOf(9)), "foreign-stage")
            return ProjectCopyJournal.AlreadyCommitted(ProjectCopyJournal.Record(intent, ProjectCopyJournal.Status.COMMITTED,
                StreamPublicationReceipt(preparation, "foreign-destination"), null, "foreign-evidence", false))
        }
        suspend fun ref(name: String = "original.bin") = store.import(owner, name, "application/octet-stream", bytes.inputStream())
        suspend fun bind(files: FileRepository = FileRepository(root), journal: ProjectCopyJournal = this.journal,
            turn: Job = parent, permission: suspend (String, String) -> Boolean = { _, _ -> true },
            saved: suspend (String) -> Unit = {}, destinationAvailable: suspend (String) -> Unit = {}, admitted: Long? = null) =
            requireNotNull(AcpAttachmentAccess.bind(workspace, store, owner, files, WorkspaceAgentMode.EDIT,
                turnJob = turn, permission = permission, saved = saved, destinationAvailable = destinationAvailable,
                requestAdmittedAtNanos = admitted, copyJournal = journal)).also(leases::add)
        suspend fun save(access: AcpAttachmentAccess.Lease, ref: ChatMediaRef, destination: String = "asset.bin") =
            AcpAttachmentAccess.execute(workspace, listOf("save", access.token, ref.id, destination))
    }
    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected failed or conservatively blocked attachment copy")
    }
    private fun fixture(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val sandbox = Files.createTempDirectory("acp-copy-journal-").toFile()
        val root = sandbox.resolve("files/projects/project").apply { mkdirs() }
        val f = Fixture(root, currentCoroutineContext().job)
        try { withTimeout(20_000) { block(f) } }
        finally { f.leases.forEach { it.finish() }; PathSecurity.deleteTreeNoFollow(sandbox) }
    }
}
