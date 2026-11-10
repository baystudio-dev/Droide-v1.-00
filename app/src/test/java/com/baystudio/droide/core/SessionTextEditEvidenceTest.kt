package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bounded metadata controls. No terminal, subprocess, provider, socket or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTextEditEvidenceTest {
    @Test(timeout = 20_000)
    fun metadataWritesStayConstantAcrossTiny64KiBAnd128KiBConversations() = scenario { env ->
        val normalizedCosts = mutableListOf<Pair<Long, Long>>()
        for ((index, size) in listOf(128, 64 * 1024, 128 * 1024).withIndex()) {
            env.session = env.session.copy(messages = listOf(AgentMessage("user", "x".repeat(size))))
            env.sessions.save(env.session)
            val sessionBytes = env.sessionFile.length()
            env.root.resolve("note.txt").writeText("a")
            env.events.clear()
            val prepared = env.prepare(after = "b")
            env.files.writeTextIfUnchanged(prepared.observation)
            val record = env.sessions.completeTextEdit(prepared.lease, prepared.observation, null)
            val totals = env.totals()
            assertEquals(1L, totals[TextEditIo.INTENT_CREATE])
            assertEquals(1L, totals[TextEditIo.OUTCOME_CREATE])
            assertEquals(0L, totals[TextEditIo.SESSION_WRITE])
            assertEquals(0L, totals[TextEditIo.INDEX_WRITE])
            assertEquals(2L * sessionBytes, totals[TextEditIo.SESSION_READ])
            assertEquals(2, env.events.count { it.first == TextEditIo.SESSION_READ })
            assertEquals(totals[TextEditIo.INTENT_BYTES], totals[TextEditIo.METADATA_READ])
            assertTrue(requireNotNull(totals[TextEditIo.INTENT_BYTES]) in 1..16_384)
            assertTrue(requireNotNull(totals[TextEditIo.OUTCOME_BYTES]) in 1..16_384)
            assertEquals(2L, totals[TextEditIo.FILE_FORCE])
            assertEquals(8L, totals[TextEditIo.DIRECTORY_FORCE])
            assertEquals((index * 3).toLong(), totals[TextEditIo.DIRECTORY_ENTRY])
            // Only filesystem-generated scalar widths vary. Subtract their exact JSON-escaped UTF-8 widths,
            // never payload bytes, then require equality rather than a tolerance.
            fun scalarWidth(value: String?) = value?.let { JsonPrimitive(it).toString().toByteArray(Charsets.UTF_8).size - 2 } ?: 0
            val intent = record.intent
            val outcome = requireNotNull(record.outcome)
            val normalizedIntent = requireNotNull(totals[TextEditIo.INTENT_BYTES]) - scalarWidth(intent.operationDirectoryIdentity) -
                scalarWidth(intent.backup.relativePath) - scalarWidth(intent.backup.identity)
            val normalizedOutcome = requireNotNull(totals[TextEditIo.OUTCOME_BYTES]) - scalarWidth(outcome.sourceIdentity) - scalarWidth(outcome.destinationIdentity)
            normalizedCosts += normalizedIntent to normalizedOutcome
            println("TEXT_EDIT_COST conversation_bytes=$sessionBytes session_reads=2 session_read_bytes=${totals[TextEditIo.SESSION_READ]} intent_bytes=${totals[TextEditIo.INTENT_BYTES]} outcome_bytes=${totals[TextEditIo.OUTCOME_BYTES]} normalized_intent_bytes=$normalizedIntent normalized_outcome_bytes=$normalizedOutcome metadata_read_bytes=${totals[TextEditIo.METADATA_READ]} session_write_bytes=0 index_write_bytes=0 intent_creates=1 outcome_creates=1 file_forces=2 directory_forces=8 directory_entries=${totals[TextEditIo.DIRECTORY_ENTRY]}")
        }
        assertEquals(1, normalizedCosts.distinct().size)
        env.events.clear()
        assertEquals(env.session.id, env.sessions.getSummary(env.session.id)?.id)
        assertEquals(1, env.sessions.listSummaries().size)
        assertEquals(0L, env.totals()[TextEditIo.METADATA_READ])
        assertEquals(0L, env.totals()[TextEditIo.DIRECTORY_ENTRY])
        assertEquals(0L, env.totals()[TextEditIo.SESSION_READ])
    }

    @Test(timeout = 20_000)
    fun intentOnlyFreshManagerIsUnknownAndDoesNotReplayOrReadBackupPayload() = scenario { env ->
        val prepared = env.prepare()
        assertTrue(prepared.directory.resolve("intent.json").isFile)
        assertFalse(prepared.directory.resolve("outcome.json").exists())
        val evidence = SessionManager(env.root).readTextEditEvidence(env.session)
        assertEquals(1, evidence.records.size)
        assertNull(evidence.records.single().outcome)
        val projected = AgentTextEditProjection.apply(emptyList(), evidence)
        val part = requireNotNull(projected.single().streamPart)
        assertEquals(AgentStreamPartKind.STATUS, part.kind)
        assertEquals(AgentStreamPartState.ERROR, part.state)
        assertTrue(part.toolCallId.isEmpty())
        assertTrue(part.detail.contains("publication unverified"))
        assertEquals("a", env.root.resolve("note.txt").readText())
        assertEquals(projected, AgentTextEditProjection.apply(projected, evidence))
        assertFalse(prepared.directory.resolve("intent.json").readText().contains("\"content\""))
    }

    @Test(timeout = 20_000)
    fun intentFileForceFailureDoesNotAdmitTargetWrite() = scenario { env ->
        val injected = UncopyableIOException("intent barrier fixture")
        env.barriers.failFileAt = 1
        env.barriers.failure = injected
        val failure = runCatching { env.prepare() }.exceptionOrNull()
        assertSame(injected, failure)
        assertEquals("a", env.root.resolve("note.txt").readText())
        assertEquals(0L, env.totals()[TextEditIo.OUTCOME_CREATE])
        val restored = SessionManager(env.root).readTextEditEvidence(env.session)
        assertEquals(1, restored.records.size)
        assertNull(restored.records.single().outcome)
    }

    @Test(timeout = 20_000)
    fun completeOutcomeAfterFailedForceIsHistoricalButUnacknowledgedAndNotRetried() = scenario { env ->
        val prepared = env.prepare()
        env.files.writeTextIfUnchanged(prepared.observation)
        val injected = UncopyableIOException("outcome barrier fixture")
        env.barriers.failFileAt = 2
        env.barriers.failure = injected
        assertSame(injected, runCatching { env.sessions.completeTextEdit(prepared.lease, prepared.observation, null) }.exceptionOrNull())
        assertFalse(prepared.lease.acknowledged)
        val restored = SessionManager(env.root).readTextEditEvidence(env.session).records.single()
        assertEquals("OWNED_CURRENT", restored.outcome?.disposition)
        assertFalse(restored.association().metadataAcknowledged)
        assertTrue(restored.association().detail(AgentStreamPartState.ERROR, "").contains("acknowledgement is unverified"))
        val before = prepared.directory.resolve("outcome.json").readBytes()
        val creates = env.totals()[TextEditIo.OUTCOME_CREATE]
        assertNotNull(runCatching { env.sessions.completeTextEdit(prepared.lease, prepared.observation, null) }.exceptionOrNull())
        assertEquals(creates, env.totals()[TextEditIo.OUTCOME_CREATE])
        assertArrayEquals(before, prepared.directory.resolve("outcome.json").readBytes())
        assertEquals("b", env.root.resolve("note.txt").readText())
    }

    @Test(timeout = 20_000)
    fun malformedAndPendingSlotsStayOccupiedWithoutPayloadRescanOrEviction() = scenario { env ->
        env.sessions.textEditRecordLimit = 2
        val first = env.prepare()
        first.directory.resolve("intent.json").writeText("{")
        env.events.clear()
        val second = env.prepare()
        assertEquals(0L, env.totals()[TextEditIo.METADATA_READ])
        val failure = runCatching { env.prepare() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(first.directory.isDirectory && second.directory.isDirectory)
        assertEquals("{", first.directory.resolve("intent.json").readText())
        assertEquals(2, requireNotNull(first.directory.parentFile.list()).size)
        val restored = SessionManager(env.root).readTextEditEvidence(env.session)
        assertEquals(1, restored.records.size)
        assertEquals(1, restored.problems.size)
        assertNull(restored.records.single().outcome)
        assertEquals("a", env.root.resolve("note.txt").readText())
    }

    @Test(timeout = 20_000)
    fun originalCallCapturesRootBeforeLaterBackupPreparation() = scenario { env ->
        val call = env.sessions.textEditCall(env.owner(), { true }) { _, _, _ -> }
        val savedBytes = env.sessionFile.readBytes()
        Files.move(env.root.toPath(), env.container.resolve("old-root").toPath())
        assertTrue(env.root.mkdir())
        env.root.resolve(".droide/sessions").mkdirs()
        env.sessionFile.writeBytes(savedBytes)
        env.root.resolve("note.txt").writeText("a")
        val observation = env.files.prepareTextWriteObservation("note.txt", "b", DiskFileVersion.fromText("a"))
        val backup = env.history.prepareBatch("edit note.txt", mapOf("note.txt" to "a")).snapshots.single()
        assertNotNull(runCatching { call.admit(observation, backup) }.exceptionOrNull())
        assertEquals("a", env.root.resolve("note.txt").readText())
        assertFalse(env.root.resolve(".droide/sessions/.text-edits").exists())
    }

    @Test(timeout = 20_000)
    fun replacementRootBeforeRepositoryGuardCannotUseOriginalAdmission() = scenario { env ->
        val prepared = env.prepare()
        Files.move(env.root.toPath(), env.container.resolve("old-root").toPath())
        assertTrue(env.root.mkdir())
        env.root.resolve("note.txt").writeText("a")
        assertNotNull(runCatching { env.files.writeTextIfUnchanged(prepared.observation) }.exceptionOrNull())
        assertFalse(prepared.observation.writeAttempted)
        assertEquals("a", env.root.resolve("note.txt").readText())
        assertNotNull(runCatching { env.sessions.completeTextEdit(prepared.lease, prepared.observation, null) }.exceptionOrNull())
        assertFalse(env.root.resolve(".droide/sessions/.text-edits").exists())
    }

    @Test(timeout = 20_000)
    fun wrongInvocationObservationCannotCompleteAnOriginalLease() = scenario { env ->
        val first = env.prepare()
        val second = env.prepare()
        env.files.writeTextIfUnchanged(second.observation)
        assertNotNull(runCatching { env.sessions.completeTextEdit(first.lease, second.observation, null) }.exceptionOrNull())
        assertFalse(first.directory.resolve("outcome.json").exists())
        assertFalse(first.lease.attemptedCompletion)
        assertEquals("OWNED_CURRENT", env.sessions.completeTextEdit(second.lease, second.observation, null).outcome?.disposition)
    }

    @Test(timeout = 20_000)
    fun removedSessionAndSubstitutedNamespaceRejectLateCompletion() = scenario { env ->
        val prepared = env.prepare()
        env.files.writeTextIfUnchanged(prepared.observation)
        val savedBytes = env.sessionFile.readBytes()
        env.sessions.delete(env.session.id)
        env.sessionFile.writeBytes(savedBytes) // A path recreation cannot defeat the retained deletion tombstone.
        assertNotNull(runCatching { env.sessions.completeTextEdit(prepared.lease, prepared.observation, null) }.exceptionOrNull())
        assertFalse(prepared.directory.resolve("outcome.json").exists())
        val other = env.sessions.create("Other")
        val another = env.prepare(session = other, after = "c")
        val parent = another.directory.parentFile
        Files.move(parent.toPath(), env.container.resolve("old-namespace").toPath())
        assertTrue(parent.mkdir())
        assertNotNull(runCatching { env.sessions.completeTextEdit(another.lease, another.observation, CancellationException("stopped")) }.exceptionOrNull())
        assertTrue(requireNotNull(parent.list()).isEmpty())
        assertEquals("b", env.root.resolve("note.txt").readText())
    }

    @Test(timeout = 20_000)
    fun onlyTerminalHolderPublicationEntersHistoricalProjection() = scenario { env ->
        val historical = mutableListOf<SessionTextEditEvidence.Record>()
        val call = env.sessions.textEditCall(env.owner(), { true }) { record, _, terminal ->
            if (terminal) historical += record
        }
        val observation = env.files.prepareTextWriteObservation("note.txt", "b", DiskFileVersion.fromText("a"))
        val backup = env.history.prepareBatch("edit note.txt", mapOf("note.txt" to "a")).snapshots.single()
        call.admit(observation, backup)
        val unrelated = listOf(AgentMessage("assistant", "Other tool is running"))
        fun displayed() = AgentTextEditProjection.apply(unrelated,
            SessionTextEditEvidence.Evidence(env.session.id, env.session.createdAt, historical, emptyList()))
        assertEquals(unrelated, displayed())
        env.files.writeTextIfUnchanged(observation)
        assertNull(call.finish(observation, null))
        assertEquals(unrelated, displayed())
        call.terminal(AgentStreamPartState.CANCELLED)
        assertEquals(1, historical.size)
        assertEquals(2, displayed().size)
    }

    @Test(timeout = 20_000)
    fun exactNormalSaveRetiresOnlyAfterItsSavedConversationBarrierAcknowledges() = scenario { env ->
        val prepared = env.prepare()
        env.files.writeTextIfUnchanged(prepared.observation)
        val record = env.sessions.completeTextEdit(prepared.lease, prepared.observation, null)
        val association = record.association(true).copy(normalTerminal = true)
        val part = AgentStreamPart(prepared.lease.intent.partId, 1, AgentStreamPartKind.TOOL,
            AgentStreamPartState.COMPLETED, toolName = prepared.lease.intent.tool,
            toolCallId = prepared.lease.intent.callId, textEdit = association)
        val normal = env.session.copy(messages = listOf(AgentMessage("assistant", "", streamPart = part)))
        env.barriers.failFileAt = env.barriers.fileCalls + 1
        env.sessions.save(normal)
        assertTrue(prepared.directory.resolve("intent.json").isFile)
        assertTrue(prepared.directory.resolve("outcome.json").isFile)
        env.barriers.failFileAt = null
        env.barriers.failDirectory = env.root.resolve(".droide/sessions")
        env.sessions.save(normal)
        assertTrue(prepared.directory.resolve("outcome.json").isFile)
        env.barriers.failDirectory = null
        env.sessions.save(normal.copy(messages = listOf(AgentMessage("assistant", "", streamPart = part.copy(toolCallId = "other-call")))))
        assertTrue(prepared.directory.resolve("outcome.json").isFile)
        env.sessions.save(normal)
        assertFalse(prepared.directory.exists())
        assertEquals(normal.messages, requireNotNull(SessionManager(env.root).get(env.session.id)).messages)
        assertNotNull(runCatching { env.sessions.completeTextEdit(prepared.lease, prepared.observation, null) }.exceptionOrNull())
        assertFalse(prepared.directory.exists())
        assertEquals("b", env.root.resolve("note.txt").readText())
    }

    @Test(timeout = 20_000)
    fun alreadyRetiredAssociationsCannotStarveOneOccupiedTerminalSlot() = scenario { env ->
        val prepared = env.prepare()
        env.files.writeTextIfUnchanged(prepared.observation)
        val record = env.sessions.completeTextEdit(prepared.lease, prepared.observation, null)
        val association = record.association(true).copy(normalTerminal = true)
        val part = AgentStreamPart(prepared.lease.intent.partId, 1, AgentStreamPartKind.TOOL,
            AgentStreamPartState.COMPLETED, toolName = prepared.lease.intent.tool,
            toolCallId = prepared.lease.intent.callId, textEdit = association)
        val retired = List(64) { index -> AgentMessage("assistant", "", streamPart = part.copy(id = "old-$index",
            textEdit = association.copy(operationId = UUID.randomUUID().toString()))) }
        env.sessions.save(env.session.copy(messages = retired + AgentMessage("assistant", "", streamPart = part)))
        assertFalse(prepared.directory.exists())
    }

    @Test(timeout = 20_000)
    fun duplicateIsReadOnlyConflictingCompletionFailsAndTruncatedOutcomeStaysUnknown() = scenario { env ->
        val prepared = env.prepare()
        env.files.writeTextIfUnchanged(prepared.observation)
        val original = env.sessions.completeTextEdit(prepared.lease, prepared.observation, null)
        env.events.clear()
        assertNotNull(runCatching {
            SessionManager(env.root).completeTextEdit(prepared.lease, prepared.observation, null)
        }.exceptionOrNull())
        assertEquals(original, env.sessions.completeTextEdit(prepared.lease, prepared.observation, null))
        assertEquals(0L, env.totals()[TextEditIo.OUTCOME_CREATE])
        assertEquals(0L, env.totals()[TextEditIo.OUTCOME_BYTES])
        assertNotNull(runCatching {
            env.sessions.completeTextEdit(prepared.lease, prepared.observation, CancellationException("conflicting fixture"))
        }.exceptionOrNull())
        val outcome = prepared.directory.resolve("outcome.json")
        val originalBytes = outcome.readBytes()
        assertEquals(original.outcomeDigest, ProjectCopyEvidence.sha256(originalBytes))
        outcome.writeBytes(originalBytes.copyOf(originalBytes.size / 2))
        val truncated = SessionManager(env.root).readTextEditEvidence(env.session).records.single()
        assertNull(truncated.outcome)
        assertNotNull(truncated.problem)
        assertFalse(truncated.association().detail(AgentStreamPartState.ERROR, "").contains("local write observed"))
        assertEquals("b", env.root.resolve("note.txt").readText())
    }

    @Test(timeout = 20_000)
    fun cleanupFailureAfterOutcomeDeletionLeavesOccupiedIntentAndNoSuccessPromotion() = scenario { env ->
        val prepared = env.prepare()
        env.files.writeTextIfUnchanged(prepared.observation)
        val record = env.sessions.completeTextEdit(prepared.lease, prepared.observation, null)
        val part = AgentStreamPart(prepared.lease.intent.partId, 1, AgentStreamPartKind.TOOL,
            AgentStreamPartState.COMPLETED, toolName = prepared.lease.intent.tool, toolCallId = prepared.lease.intent.callId,
            textEdit = record.association(true).copy(normalTerminal = true))
        val normal = env.session.copy(messages = listOf(AgentMessage("assistant", "", streamPart = part)))
        env.barriers.failDirectory = prepared.directory
        env.sessions.save(normal)
        assertFalse(prepared.directory.resolve("outcome.json").exists())
        assertTrue(prepared.directory.resolve("intent.json").isFile)
        env.barriers.failDirectory = null
        env.sessions.save(normal)
        assertTrue(prepared.directory.resolve("intent.json").isFile)
        val evidence = SessionManager(env.root).readTextEditEvidence(normal)
        assertNull(evidence.records.single().outcome)
        val displayed = AgentTextEditProjection.apply(normal.messages, evidence)
        assertEquals(normal.messages.single(), displayed.first()) // Mismatched saved outcome is not rebound.
        assertEquals(AgentStreamPartKind.STATUS, displayed.last().streamPart?.kind)
        assertTrue(displayed.last().streamPart!!.detail.contains("publication unverified"))
    }

    @Test(timeout = 20_000)
    fun repeatedProviderIdsAndMismatchedOutcomeDigestNeverRebindAStoredPart() = scenario { env ->
        val first = env.prepare()
        env.files.writeTextIfUnchanged(first.observation)
        val firstRecord = env.sessions.completeTextEdit(first.lease, first.observation, CancellationException("first stopped"))
        val second = env.prepare(after = "c")
        env.files.writeTextIfUnchanged(second.observation)
        env.sessions.completeTextEdit(second.lease, second.observation, CancellationException("second stopped"))
        assertEquals(first.lease.intent.callId, second.lease.intent.callId)
        assertEquals(first.lease.intent.partId, second.lease.intent.partId)
        val original = AgentStreamPart(first.lease.intent.partId, 1, AgentStreamPartKind.TOOL,
            AgentStreamPartState.CANCELLED, toolName = first.lease.intent.tool, toolCallId = first.lease.intent.callId,
            textEdit = firstRecord.association(true))
        val evidence = SessionManager(env.root).readTextEditEvidence(env.session)
        val messages = listOf(AgentMessage("assistant", "", streamPart = original))
        val restored = AgentTextEditProjection.apply(messages, evidence)
        assertEquals(2, restored.size)
        assertEquals(first.lease.intent.operationId, restored.first().streamPart?.textEdit?.operationId)
        assertEquals(second.lease.intent.operationId, restored.last().streamPart?.textEdit?.operationId)
        assertEquals(AgentStreamPartKind.STATUS, restored.last().streamPart?.kind)
        val mismatched = messages.single().copy(streamPart = original.copy(textEdit = firstRecord.association(true).copy(outcomeDigest = "0".repeat(64))))
        val unknown = AgentTextEditProjection.apply(listOf(mismatched), evidence)
        assertEquals(mismatched, unknown.first())
        assertEquals(2, unknown.count { it.streamPart?.kind == AgentStreamPartKind.STATUS })
    }

    @Test(timeout = 20_000)
    fun orphanEvidenceSurvivesTinyUiRetentionAndSaveWithoutBackupAvailabilityOrForkAuthority() = scenario { env ->
        val prepared = env.prepare()
        env.files.writeTextIfUnchanged(prepared.observation)
        env.sessions.completeTextEdit(prepared.lease, prepared.observation, CancellationException("stopped"))
        assertTrue(env.root.resolve(prepared.lease.intent.backup.relativePath).delete())
        val evidence = SessionManager(env.root).readTextEditEvidence(env.session)
        val ordinary = List(3) { AgentMessage("user", "message-$it") }
        val bounded = AgentHistory.boundedUi(ordinary, 1, 32)
        val projected = AgentTextEditProjection.apply(bounded, evidence)
        assertEquals(bounded.size + 1, projected.size)
        val status = requireNotNull(projected.last().streamPart)
        assertEquals(AgentStreamPartKind.STATUS, status.kind)
        assertTrue(status.detail.contains("local write observed"))
        assertFalse(status.detail.contains("backup", ignoreCase = true))
        val saved = env.session.copy(messages = projected)
        env.sessions.save(saved)
        val fresh = SessionManager(env.root)
        val restored = requireNotNull(fresh.get(saved.id))
        val again = AgentTextEditProjection.apply(restored.messages, fresh.readTextEditEvidence(restored))
        assertEquals(1, again.count { it.streamPart?.textEdit?.operationId == prepared.lease.intent.operationId })
        assertTrue(restored.apiHistory.isEmpty() && restored.forkHistory.isEmpty())
        val fork = requireNotNull(fresh.fork(saved.id))
        assertNotEquals(saved.id, fork.id)
        assertTrue(fork.messages.none { it.streamPart?.textEdit != null })
        assertTrue(fresh.readTextEditEvidence(fork).records.isEmpty())
        assertTrue(prepared.directory.resolve("outcome.json").isFile)
        assertEquals("b", env.root.resolve("note.txt").readText())
    }

    @Test(timeout = 20_000)
    fun normalSavedLocalSyncPendingStaysCompletedButCannotRetireItsEvidence() = scenario { env ->
        val owner = env.owner()
        val call = env.sessions.textEditCall(owner, { true }) { _, _, _ -> }
        val observation = env.files.prepareTextWriteObservation("note.txt", "b", DiskFileVersion.fromText("a"))
        val backup = env.history.prepareBatch("edit note.txt", mapOf("note.txt" to "a")).snapshots.single()
        call.admit(observation, backup)
        env.files.writeTextIfUnchanged(observation)
        assertNull(call.finish(observation, ProjectFileSyncException("note.txt", 1, IOException("sync fixture"))))
        val association = requireNotNull(call.terminal(AgentStreamPartState.COMPLETED))
        assertTrue(association.normalTerminal)
        val part = AgentStreamPart(owner.partId, 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED,
            detail = "Saved locally; synchronization pending", toolName = owner.tool, toolCallId = owner.callId, textEdit = association)
        val normal = env.session.copy(messages = listOf(AgentMessage("assistant", "", streamPart = part)))
        env.sessions.save(normal)
        val evidence = SessionManager(env.root).readTextEditEvidence(normal)
        assertEquals(1, evidence.records.size)
        assertEquals("SYNC_FAILED", evidence.records.single().outcome?.repositoryReturn)
        val projected = AgentTextEditProjection.apply(normal.messages, evidence)
        assertEquals(AgentStreamPartState.COMPLETED, projected.single().streamPart?.state)
        assertEquals(part.detail, projected.single().streamPart?.detail)
    }

    @Test(timeout = 20_000)
    fun syncPendingWithFailedMetadataForceCannotMintNormalTerminalProof() = scenario { env ->
        val owner = env.owner()
        val call = env.sessions.textEditCall(owner, { true }) { _, _, _ -> }
        val observation = env.files.prepareTextWriteObservation("note.txt", "b", DiskFileVersion.fromText("a"))
        val backup = env.history.prepareBatch("edit note.txt", mapOf("note.txt" to "a")).snapshots.single()
        call.admit(observation, backup)
        env.files.writeTextIfUnchanged(observation)
        env.barriers.failFileAt = 2
        assertSame(env.barriers.failure, call.finish(observation,
            ProjectFileSyncException("note.txt", 1, IOException("sync fixture"))))
        val association = requireNotNull(call.terminal(AgentStreamPartState.COMPLETED))
        assertFalse(association.metadataAcknowledged)
        assertFalse(association.normalTerminal)
        val part = AgentStreamPart(owner.partId, 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED,
            toolName = owner.tool, toolCallId = owner.callId, textEdit = association)
        val evidence = SessionManager(env.root).readTextEditEvidence(env.session)
        val projected = AgentTextEditProjection.apply(listOf(AgentMessage("assistant", "", streamPart = part)), evidence)
        assertEquals(AgentStreamPartState.ERROR, projected.single().streamPart?.state)
        assertTrue(projected.single().streamPart!!.detail.contains("local write observed"))
        env.sessions.save(env.session.copy(messages = projected))
        assertEquals(1, SessionManager(env.root).readTextEditEvidence(env.session).records.size)
    }

    @Test(timeout = 20_000)
    fun realToolKeepsOriginalCancellationAndLiveUndoWhenOutcomeForceFails() = scenario { env ->
        val stopped = UncopyableCancellation("original local publication cancellation")
        val metadataFailure = IOException("secondary outcome force failure")
        var cancelPublication = true
        val files = FileRepository(env.root, onMutation = { mutation ->
            if (cancelPublication && mutation is FileRepository.Mutation.Write && mutation.path == "note.txt") throw stopped
        })
        val history = EditHistory(files)
        val forbiddenCalls = AtomicInteger()
        fun forbidden(): Nothing { forbiddenCalls.incrementAndGet(); throw AssertionError("No external execution is permitted") }
        val terminal = NoProcessTerminal(::forbidden)
        val web = AgentWebResearch(env.root, AgentWebTransport { _, _ -> forbidden() }, object : AgentWebPdfBackend {
            override fun pageCount(file: File): Int = forbidden()
            override fun text(file: File, page: Int): String? = forbidden()
            override fun screenshot(file: File, page: Int, output: OutputStream): AgentWebImageSize = forbidden()
        })
        try {
            val lsp = object : LspManager {
                override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
                override suspend fun diagnose(relPath: String): String = forbidden()
                override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = forbidden()
            }
            val ctx = AgentTools.Ctx(files, terminal, GitManager(env.root), ApprovalManager(), QuestionManager(), history,
                PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), AgentMode.BUILD, LlmClient(),
                AgentConfig(baseUrl = "", apiKey = "", model = "unreachable"), env.root, TodoManager(), env.session.id, lsp,
                webResearch = web)
            ctx.textEditCall = env.sessions.textEditCall(env.owner(), { true }) { _, _, _ -> }
            env.barriers.failFileAt = 2
            env.barriers.failure = metadataFailure
            val failure = runCatching { AgentTools.execute("write_file", buildJsonObject {
                put("path", "note.txt"); put("content", "b")
            }, ctx) }.exceptionOrNull()
            assertSame(stopped, failure)
            assertEquals(1, stopped.suppressed.size)
            assertOriginatingFailure(metadataFailure, stopped.suppressed.single())
            assertEquals("b", env.root.resolve("note.txt").readText())
            assertEquals(1, history.depth())
            val association = requireNotNull(ctx.textEditCall?.terminal(AgentStreamPartState.CANCELLED))
            assertFalse(association.metadataAcknowledged)
            assertFalse(association.normalTerminal)
            val historical = SessionManager(env.root).readTextEditEvidence(env.session)
            assertEquals("CANCELLED", historical.records.single().outcome?.repositoryReturn)
            assertEquals("OWNED_CURRENT", historical.records.single().outcome?.disposition)
            assertTrue(AgentTextEditProjection.apply(emptyList(), historical).single().streamPart!!.detail.contains("local write observed"))
            cancelPublication = false
            history.undo()
            assertEquals(0, history.depth())
            assertEquals("a", env.root.resolve("note.txt").readText())
            val afterUndo = SessionManager(env.root).readTextEditEvidence(env.session)
            assertEquals(historical.records, afterUndo.records)
            assertEquals(0, forbiddenCalls.get())
        } finally { web.close() }
        assertEquals(0, forbiddenCalls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class UncopyableCancellation(message: String) : CancellationException(message), CopyableThrowable<UncopyableCancellation> {
        override fun createCopy(): UncopyableCancellation? = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class UncopyableIOException(message: String) : IOException(message), CopyableThrowable<UncopyableIOException> {
        override fun createCopy(): UncopyableIOException? = null
    }

    private class NoProcessTerminal(private val forbidden: () -> Nothing) : ITerminalSession {
        override val output = MutableStateFlow("")
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    private class Barriers : ProjectCopyJournal.Durability {
        var fileCalls = 0
        var failFileAt: Int? = null
        var failDirectory: File? = null
        var failure: IOException = UncopyableIOException("barrier fixture")
        override fun forceFile(channel: FileChannel) {
            fileCalls++
            if (fileCalls == failFileAt) throw failure
            ProjectCopyJournal.Durability.platform.forceFile(channel)
        }
        override fun forceDirectory(directory: File) {
            if (directory == failDirectory) throw failure
            ProjectCopyJournal.Durability.platform.forceDirectory(directory)
        }
    }

    private data class Prepared(val observation: FileRepository.TextWriteObservation,
        val lease: SessionTextEditEvidence.Lease, val directory: File)

    private class Environment(val container: File, val root: File, var session: DroideSession,
        val sessions: SessionManager, val files: FileRepository, val history: EditHistory, val barriers: Barriers) {
        val events = mutableListOf<Pair<TextEditIo, Long>>()
        val sessionFile get() = root.resolve(".droide/sessions/${session.id}.json")
        fun totals() = TextEditIo.entries.associateWith { kind -> events.filter { it.first == kind }.sumOf { it.second } }
        fun owner(session: DroideSession = this.session) = SessionTextEditEvidence.Owner(session.id,
            UUID.randomUUID().toString(), "call-one", "part-one", "write_file")
        suspend fun prepare(after: String = "b", session: DroideSession = this.session): Prepared {
            val owner = owner(session)
            sessions.textEditCall(owner, { true }) { _, _, _ -> }
            val before = root.resolve("note.txt").readText()
            val observation = files.prepareTextWriteObservation("note.txt", after, DiskFileVersion.fromText(before))
            val backup = history.prepareBatch("edit note.txt", mapOf("note.txt" to before)).snapshots.single()
            val lease = sessions.admitTextEdit(owner, observation, backup) { true }
            observation.bindAdmission(FileRepository.TextWriteAdmission(lease.intent.canonicalRoot,
                lease.intent.rootIdentity, lease.intent.operationId) { true })
            return Prepared(observation, lease, root.resolve(".droide/sessions/.text-edits/${session.id}/${lease.intent.operationId}"))
        }
    }

    private fun scenario(block: suspend (Environment) -> Unit) = runBlocking {
        withTimeout(12_000) {
            val container = Files.createTempDirectory("text-edit-evidence-").toFile()
            try {
                val root = container.resolve("work").apply { check(mkdir()) }
                root.resolve("note.txt").writeText("a")
                val sessions = SessionManager(root)
                val files = FileRepository(root)
                val barriers = Barriers()
                sessions.textEditDurability = barriers
                val env = Environment(container, root, sessions.create("Original"), sessions, files, EditHistory(files), barriers)
                sessions.textEditIoProbe = { event, bytes -> env.events += event to bytes }
                block(env)
            } finally { container.deleteRecursively() }
        }
    }
}
