package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Source-only regressions. The queued dispatcher makes root-launch ordering deterministic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubagentOwnedLaunchFailureRegressionTest {
    @org.junit.Before fun openAuthoredUserLaunch() {
        // These workflow cases represent an explicitly opened app, not startup recovery. Establish a
        // fresh epoch per method before constructing owners.
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @Test fun exportAdmissionSpansQueuedDispatchAndBackgroundDelivery() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            var callbackChecked = false
            fixture.completion = {
                assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(fixture.root) }
                callbackChecked = true
            }
            val id = fixture.start { if (it.detail.startsWith("Running")) throw IOException("Controlled child failure") }
            assertEquals(SubagentTaskState.QUEUED, readRecord(fixture.root.resolve(".droide/subagents/$id.json")).state)
            assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(fixture.root) }
            fixture.dispatcher.drain()
            assertTrue(callbackChecked)
            assertEquals(1, fixture.delivered.size)
            assertEquals(SubagentTaskState.ERROR, fixture.delivered.single().record.state)
            ExportWriterAdmission.beginCapture(fixture.root).close()
        }
    }

    @Test fun activeCaptureRefusesSubagentBeforeQueuedPublication() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            ExportWriterAdmission.beginCapture(fixture.root).use {
                assertThrows(ExportCaptureBusyException::class.java) {
                    runBlocking { fixture.start() }
                }
                assertTrue(fixture.root.resolve(".droide/subagents").listFiles().orEmpty().isEmpty())
                assertFalse(fixture.root.resolve(".droide/sessions").exists())
            }
            ExportWriterAdmission.beginCapture(fixture.root).close()
        }
    }

    @Test fun blockedDashboardDirectoryIsReportedAndRecoveryIsRetriedAfterRepair() = runBlocking {
        Fixture().use { fixture ->
            val directory = fixture.root.resolve(".droide/subagents")
            directory.parentFile.mkdirs()
            directory.writeText("obstructed directory")
            fixture.manager.bindParent(PARENT)
            fixture.dispatcher.drain()

            val failed = fixture.manager.dashboard.value
            assertEquals(PARENT, failed.parentSessionId)
            assertTrue(failed.error, failed.error.contains("subagent task directory"))
            val notice = failed.tasks.single()
            assertEquals(SubagentTaskState.ERROR, notice.state)
            assertTrue(notice.errorPreview.isNotBlank())
            assertTrue(fixture.manager.control(PARENT, "transcript", notice.id).contains("subagent task directory"))
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())

            assertTrue(directory.delete())
            writeRecord(fixture.root, task("task-interrupted"))
            fixture.manager.bindParent(PARENT)
            fixture.dispatcher.drain()

            val recovered = fixture.manager.dashboard.value
            assertEquals("", recovered.error)
            assertEquals(SubagentTaskState.INTERRUPTED, recovered.tasks.single().state)
            assertEquals(SubagentDeliveryState.PENDING, fixture.manager.pendingDeliveries(PARENT).single().delivery)
        }
    }

    @Test fun recoveryWriteFailureDoesNotPermanentlySkipInterruptedRecords() = runBlocking {
        val root = Files.createTempDirectory("droide-subagent-recovery-write-").toFile()
        try {
            writeRecord(root, task("task-recover-one"))
            writeRecord(root, task("task-recover-two"))
            var writes = 0
            val store = SubagentTaskStore(root) { temporary, target ->
                writes++
                if (writes == 2) throw IOException("Injected permission-denied publication")
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Unit
            }
            val failure = runCatching { store.list(PARENT) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is IOException)
            assertEquals(1, root.resolve(".droide/subagents").listFiles()!!.count {
                it.extension == "json" && readRecord(it).state == SubagentTaskState.RUNNING
            })

            val recovered = store.list(PARENT)
            assertEquals(2, recovered.size)
            assertTrue(recovered.all { it.state == SubagentTaskState.INTERRUPTED })
            assertEquals(3, writes)
            assertFalse(root.resolve(".droide/subagents").listFiles()!!.any { ".tmp-" in it.name })
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun recoveryPublicationCancellationIsRethrownAndRemainsRetryable() = runBlocking {
        val root = Files.createTempDirectory("droide-subagent-recovery-cancel-").toFile()
        try {
            writeRecord(root, task("task-recover-cancel"))
            val cancellation = CancellationException("Injected recovery cancellation")
            var cancelNext = true
            val store = SubagentTaskStore(root) { temporary, target ->
                if (cancelNext) { cancelNext = false; throw cancellation }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Unit
            }
            assertSame(cancellation, runCatching { store.list(PARENT) }.exceptionOrNull())
            assertEquals(SubagentTaskState.RUNNING, readRecord(root.resolve(".droide/subagents/task-recover-cancel.json")).state)
            assertEquals(SubagentTaskState.INTERRUPTED, store.list(PARENT).single().state)
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun anAlreadyCommittedTerminalRecordNeedsNoFallibleRewrite() = runBlocking {
        val root = Files.createTempDirectory("droide-subagent-terminal-preservation-").toFile()
        try {
            val completed = task("task-committed-result").copy(state = SubagentTaskState.IDLE, result = "Keep this result")
            writeRecord(root, completed)
            val store = SubagentTaskStore(root) { _, _ -> throw IOException("Storage became read-only") }
            assertEquals(completed, store.update(completed.id) { it })
            assertEquals("Keep this result", store.pendingDeliveries(PARENT).single().result)
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun storageFailureBeforeChildStartupCannotEscapeAndUnsavedStatusIsRetried() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            val id = fixture.start()
            val job = fixture.queuedLaunch()
            val directory = fixture.root.resolve(".droide/subagents")
            val backup = fixture.root.resolve(".droide/subagents-backup")
            assertTrue(directory.renameTo(backup))
            directory.writeText("storage unavailable after admission")

            fixture.dispatcher.drain()
            assertTrue(job.isCompleted)
            assertFalse(job.isCancelled)
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
            assertTrue(fixture.delivered.isEmpty())
            val failed = fixture.manager.dashboard.value.tasks.single { it.id == id }
            assertEquals(SubagentTaskState.ERROR, failed.state)
            assertTrue(failed.errorPreview, failed.errorPreview.contains("could not be saved"))
            assertTrue(fixture.manager.dashboard.value.error.isNotBlank())

            assertTrue(directory.delete())
            assertTrue(backup.renameTo(directory))
            fixture.manager.bindParent(PARENT)
            fixture.dispatcher.drain()
            assertEquals("", fixture.manager.dashboard.value.error)
            assertEquals(SubagentTaskState.ERROR, fixture.manager.pendingDeliveries(PARENT).single().state)
            assertFalse(fixture.manager.dashboard.value.tasks.single().active)
            assertTrue("Retrying a status write must not redeliver a callback", fixture.delivered.isEmpty())
        }
    }

    @Test fun progressAndCompletionExceptionsAreReportedWithoutCancellingSiblings() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            fixture.completion = { throw IOException("Parent callback unavailable") }
            val first = fixture.start { throw IllegalStateException("Progress callback unavailable") }
            val second = fixture.start { throw IllegalStateException("Second child setup failed") }
            fixture.dispatcher.drain()

            val pending = fixture.manager.pendingDeliveries(PARENT)
            assertEquals(setOf(first, second), pending.map { it.id }.toSet())
            assertTrue(pending.all { it.state == SubagentTaskState.ERROR && it.delivery == SubagentDeliveryState.PENDING })
            assertEquals(2, fixture.delivered.size)
            assertTrue(fixture.manager.dashboard.value.error.contains("Parent callback unavailable"))
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
            assertTrue(fixture.owner.isActive)
            assertTrue(fixture.owner.children.single().children.none())
        }
    }

    @Test fun childStartupCancellationKeepsItsOriginalCauseAndNeverDeliversACompletion() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            val cancellation = CancellationException("Stop this child")
            val id = fixture.start { throw cancellation }
            val job = fixture.queuedLaunch()
            var cause: Throwable? = null
            job.invokeOnCompletion { cause = it }
            fixture.dispatcher.drain()

            assertTrue(job.isCancelled)
            assertSame(cancellation, cause)
            assertEquals(SubagentTaskState.KILLED, fixture.manager.pendingDeliveries(PARENT).single { it.id == id }.state)
            assertTrue(fixture.delivered.isEmpty())
            assertEquals("", fixture.manager.dashboard.value.error)
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
        }
    }

    @Test fun completionCallbackCancellationIsNotSwallowedOrConvertedToTaskFailure() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            val cancellation = CancellationException("Parent continuation cancelled")
            fixture.completion = { throw cancellation }
            fixture.start { if (it.detail.startsWith("Running")) throw IOException("Original child failure") }
            val job = fixture.queuedLaunch()
            var cause: Throwable? = null
            job.invokeOnCompletion { cause = it }
            fixture.dispatcher.drain()

            assertTrue(job.isCancelled)
            assertSame(cancellation, cause)
            val pending = fixture.manager.pendingDeliveries(PARENT).single()
            assertEquals(SubagentTaskState.ERROR, pending.state)
            assertEquals("Original child failure", pending.error)
            assertEquals("", fixture.manager.dashboard.value.error)
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
        }
    }

    @Test fun unavailableStorageDuringCancellationCannotReplaceItsCause() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            val cancellation = CancellationException("Stop despite storage failure")
            val directory = fixture.root.resolve(".droide/subagents")
            val backup = fixture.root.resolve(".droide/subagents-cancel-backup")
            val id = fixture.start {
                assertTrue(directory.renameTo(backup))
                directory.writeText("storage disappeared during cancellation")
                throw cancellation
            }
            val job = fixture.queuedLaunch()
            var cause: Throwable? = null
            job.invokeOnCompletion { cause = it }
            fixture.dispatcher.drain()

            assertTrue(job.isCancelled)
            assertSame(cancellation, cause)
            assertEquals(SubagentTaskState.KILLED, fixture.manager.dashboard.value.tasks.single { it.id == id }.state)
            assertTrue(fixture.manager.dashboard.value.error.contains("Could not save status"))
            assertTrue(fixture.delivered.isEmpty())
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
        }
    }

    @Test fun fatalErrorsStillReachTheExceptionHandlerInsteadOfBecomingRecoverableFailures() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            val fatal = AssertionError("Injected fatal failure")
            fixture.start { throw fatal }
            val job = fixture.queuedLaunch()
            fixture.dispatcher.drain()

            assertTrue(job.isCancelled)
            assertSame(fatal, fixture.uncaught.single())
            assertTrue(fixture.delivered.isEmpty())
            assertEquals("", fixture.manager.dashboard.value.error)
        }
    }

    @Test fun parentCancellationBeforeDispatchKeepsDeliveredStateAndDoesNotRunTheChild() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            val id = fixture.start { error("A cancelled queued child must not start") }
            val job = fixture.queuedLaunch()
            assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(fixture.root) }
            assertEquals("SUBAGENT_CANCELLED task_id=$id", fixture.manager.control(PARENT, "cancel", id))
            val cancelled = readRecord(fixture.root.resolve(".droide/subagents/$id.json"))
            assertEquals(SubagentTaskState.KILLED, cancelled.state)
            assertEquals(SubagentDeliveryState.DELIVERED, cancelled.delivery)

            fixture.dispatcher.drain()
            assertTrue(job.isCancelled)
            assertEquals(cancelled, readRecord(fixture.root.resolve(".droide/subagents/$id.json")))
            ExportWriterAdmission.beginCapture(fixture.root).close()
            assertTrue(fixture.manager.pendingDeliveries(PARENT).isEmpty())
            assertTrue(fixture.delivered.isEmpty())
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
        }
    }

    @Test fun cancellationFinalizationCannotOverwriteAParentOwnedDeliveredRecord() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            val cancellation = CancellationException("Child observed parent Stop")
            var id = ""
            var cancelled: SubagentTaskRecord? = null
            id = fixture.start { progress ->
                if (progress.detail.startsWith("Running")) {
                    val current = readRecord(fixture.root.resolve(".droide/subagents/$id.json"))
                    cancelled = current.copy(
                        state = SubagentTaskState.KILLED,
                        delivery = SubagentDeliveryState.DELIVERED,
                        error = "Cancelled by parent agent",
                        endedAtMs = 123L,
                    )
                    writeRecord(fixture.root, cancelled!!)
                    throw cancellation
                }
            }
            val job = fixture.queuedLaunch()
            var cause: Throwable? = null
            job.invokeOnCompletion { cause = it }
            fixture.dispatcher.drain()

            assertTrue(job.isCancelled)
            assertSame(cancellation, cause)
            assertNotNull(cancelled)
            assertEquals(cancelled, readRecord(fixture.root.resolve(".droide/subagents/$id.json")))
            assertTrue(fixture.manager.pendingDeliveries(PARENT).isEmpty())
            assertTrue(fixture.delivered.isEmpty())
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
        }
    }

    @Test fun lateProgressFailureCannotEraseACommittedResultOrRewriteItsTimestamps() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            var id = ""
            var completed: SubagentTaskRecord? = null
            id = fixture.start { progress ->
                if (progress.detail.startsWith("Running")) {
                    val current = readRecord(fixture.root.resolve(".droide/subagents/$id.json"))
                    completed = current.copy(
                        state = SubagentTaskState.IDLE,
                        result = "Committed child result",
                        error = "",
                        endedAtMs = 456L,
                    )
                    writeRecord(fixture.root, completed!!)
                    throw IOException("Late progress callback failure")
                }
            }
            fixture.dispatcher.drain()

            assertNotNull(completed)
            assertEquals(completed, readRecord(fixture.root.resolve(".droide/subagents/$id.json")))
            assertEquals(completed, fixture.delivered.single().record)
            assertEquals(SubagentTaskState.IDLE, fixture.manager.dashboard.value.tasks.single().state)
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
        }
    }

    @Test fun closedOrReboundDashboardCannotBeRepopulatedByAnOldLaunch() {
        Fixture().use { fixture ->
            fixture.root.resolve(".droide").mkdirs()
            fixture.root.resolve(".droide/subagents").writeText("blocked")
            fixture.manager.bindParent("old-parent")
            fixture.manager.bindParent("new-parent")
            fixture.dispatcher.drain()
            assertEquals("new-parent", fixture.manager.dashboard.value.parentSessionId)
            assertTrue(fixture.manager.dashboard.value.error.isNotBlank())
            fixture.manager.bindParent("pending-parent")
            fixture.manager.close()
            fixture.dispatcher.drain()
            assertEquals(SubagentDashboardState(), fixture.manager.dashboard.value)
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
        }
    }

    @Test fun refusedQueuedTransitionCannotRunOrDeliverAStoppedOrNewerAttempt() = runBlocking {
        for (newerAttempt in listOf(false, true)) {
            Fixture().use { fixture ->
                fixture.bind()
                val id = fixture.start { error("A refused transition must not enter child execution") }
                val job = fixture.queuedLaunch()
                val file = fixture.root.resolve(".droide/subagents/$id.json")
                val admitted = readRecord(file)
                val current = if (newerAttempt) admitted.copy(
                    attempt = admitted.attempt + 1, state = SubagentTaskState.IDLE, result = "Newer result",
                ) else admitted.copy(state = SubagentTaskState.KILLED, delivery = SubagentDeliveryState.DELIVERED)
                writeRecord(fixture.root, current)
                val bytes = file.readBytes()

                fixture.dispatcher.drain()
                assertTrue(job.isCompleted)
                assertFalse(job.isCancelled)
                assertArrayEquals(bytes, file.readBytes())
                assertEquals(current.state, fixture.manager.dashboard.value.tasks.single().state)
                assertTrue(fixture.delivered.isEmpty())
                assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
            }
        }
    }

    @Test fun aResumeCannotOverlapThePriorBackgroundCompletionCallback() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            var resumed = ""
            fixture.completion = { completion ->
                resumed = fixture.manager.run(
                    parentSessionId = PARENT, description = "Resume during completion", prompt = "Inspect source only",
                    profileName = "explore", taskId = completion.record.id, background = true, workspace = "shared",
                    config = AgentConfig(apiKey = "test", model = "gpt-4o", providerId = "openai"),
                )
            }
            val id = fixture.start { if (it.detail.startsWith("Running")) throw IOException("Child startup failed") }
            fixture.dispatcher.drain()
            assertTrue(resumed, resumed.contains("still finishing its previous execution"))
            assertEquals(1, readRecord(fixture.root.resolve(".droide/subagents/$id.json")).attempt)
            assertEquals(1, fixture.delivered.size)
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
        }
    }

    @Test fun failedSharedChildBootstrapDoesNotDisposeParentTerminalOrLsp() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            val id = fixture.start()
            val admitted = readRecord(fixture.root.resolve(".droide/subagents/$id.json"))
            assertTrue(fixture.root.resolve(".droide/sessions/${admitted.childSessionId}.json").delete())
            val job = fixture.queuedLaunch()
            withTimeout(5_000L) {
                while (!job.isCompleted) {
                    fixture.dispatcher.drain()
                    delay(1L)
                }
            }

            assertEquals(SubagentTaskState.ERROR, fixture.delivered.single().record.state)
            assertTrue(fixture.delivered.single().record.error.contains("Child session missing"))
            assertEquals(0, fixture.terminalDisposals)
            assertEquals(0, fixture.lspDisposals)
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
        }
    }

    @Test fun aResumeCannotOverlapForegroundFailureFinalization() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            var id = ""
            var resumed = ""
            val response = fixture.manager.run(
                parentSessionId = PARENT, description = "Foreground owner", prompt = "Inspect source only",
                profileName = "explore", taskId = null, background = false, workspace = "shared",
                config = AgentConfig(apiKey = "test", model = "gpt-4o", providerId = "openai"),
                onProgress = { progress ->
                    if (progress.detail.startsWith("Running")) {
                        val file = fixture.root.resolve(".droide/subagents").listFiles()!!.single { it.extension == "json" }
                        val current = readRecord(file)
                        id = current.id
                        writeRecord(fixture.root, current.copy(state = SubagentTaskState.IDLE, result = "Committed foreground result"))
                        throw IOException("Failure while finalizing committed foreground result")
                    }
                    if (progress.detail.startsWith("Failed")) {
                        resumed = runBlocking {
                            fixture.manager.run(
                                parentSessionId = PARENT, description = "Overlapping resume", prompt = "Inspect source only",
                                profileName = "explore", taskId = id, background = true, workspace = "shared",
                                config = AgentConfig(apiKey = "test", model = "gpt-4o", providerId = "openai"),
                            )
                        }
                    }
                },
            )
            assertTrue(response, response.startsWith("SUBAGENT_COMPLETED"))
            assertTrue(resumed, resumed.contains("still finishing its previous execution"))
            assertEquals(1, readRecord(fixture.root.resolve(".droide/subagents/$id.json")).attempt)
            assertTrue(fixture.delivered.isEmpty())
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
        }
    }

    @Test fun cancelDuringCommittedForegroundFinalizationAcknowledgesAndPreservesSuccess() = runBlocking {
        Fixture().use { fixture ->
            fixture.bind()
            var id = ""
            var acknowledgement = ""
            var committedBytes: ByteArray? = null
            val cancellation = try {
                fixture.manager.run(
                    parentSessionId = PARENT, description = "Committed foreground owner", prompt = "Inspect source only",
                    profileName = "explore", taskId = null, background = false, workspace = "shared",
                    config = AgentConfig(apiKey = "test", model = "gpt-4o", providerId = "openai"),
                    onProgress = { progress ->
                        if (progress.detail.startsWith("Running")) {
                            val file = fixture.root.resolve(".droide/subagents").listFiles()!!.single { it.extension == "json" }
                            val current = readRecord(file)
                            id = current.id
                            writeRecord(fixture.root, current.copy(
                                state = SubagentTaskState.IDLE, delivery = SubagentDeliveryState.DELIVERED,
                                result = "Committed foreground success", endedAtMs = 456L,
                            ))
                            committedBytes = file.readBytes()
                            throw IOException("Late progress failure after commit")
                        }
                        if (progress.detail.startsWith("Failed")) {
                            acknowledgement = runBlocking { fixture.manager.control(PARENT, "cancel", id) }
                        }
                    },
                )
                null
            } catch (failure: CancellationException) { failure }
            assertNotNull(cancellation)
            assertTrue(acknowledgement, acknowledgement.startsWith("SUBAGENT_TERMINAL_PRESERVED"))
            assertTrue(acknowledgement, acknowledgement.contains("state=idle delivery=delivered"))
            val file = fixture.root.resolve(".droide/subagents/$id.json")
            assertArrayEquals(committedBytes!!, file.readBytes())
            assertEquals("Committed foreground success", readRecord(file).result)
            assertTrue(fixture.delivered.isEmpty())
            assertTrue(fixture.uncaught.toString(), fixture.uncaught.isEmpty())
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
        fun drain() {
            repeat(1_000) { (queued.poll() ?: return).run() }
            error("Unexpected non-quiescent dispatcher")
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("droide-subagent-launch-").toFile()
        val dispatcher = QueuedDispatcher()
        val owner = SupervisorJob()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val delivered = CopyOnWriteArrayList<SubagentCompletion>()
        var completion: suspend (SubagentCompletion) -> Unit = {}
        var terminalDisposals = 0
        var lspDisposals = 0
        private val terminal = object : ITerminalSession {
            override val output = MutableStateFlow("")
            override fun start() = Unit
            override fun send(cmd: String) { error("No terminal execution belongs in this regression") }
            override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("Unexpected terminal execution")
            override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = error("Unexpected terminal execution")
            override fun interrupt() = Unit
            override fun clear() = Unit
            override fun destroy() { terminalDisposals++ }
        }
        val manager = SubagentManager(
            files = FileRepository(root), terminal = terminal, git = GitManager(root),
            approvals = ApprovalManager(), questions = QuestionManager(), parentPermissions = PermissionEngine(),
            lsp = object : LspManager {
                override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
                override suspend fun diagnose(relPath: String) = ""
                override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
                override fun close() { lspDisposals++ }
            },
            workDir = root, depth = 0, maxDepth = 1,
            runtimeContext = dispatcher + owner + CoroutineExceptionHandler { _, error -> uncaught.add(error) },
            onBackgroundCompletion = { delivered.add(it); completion(it) },
        )
        init { ProviderModelCertificationRegistry.certify(ProviderRegistry.byId("openai"), listOf("gpt-4o")) }
        fun bind() { manager.bindParent(PARENT); dispatcher.drain() }
        suspend fun start(onProgress: (AgentToolProgress) -> Unit = {}): String {
            val response = manager.run(
                parentSessionId = PARENT, description = "Launch boundary regression", prompt = "Inspect source only",
                profileName = "explore", taskId = null, background = true, workspace = "shared",
                config = AgentConfig(apiKey = "test", model = "gpt-4o", providerId = "openai"), onProgress = onProgress,
            )
            assertTrue(response, response.startsWith("SUBAGENT_STARTED"))
            return response.substringAfter("task_id=").substringBefore(' ')
        }
        fun queuedLaunch(): Job = owner.children.single().children.single()
        override fun close() {
            manager.close(); dispatcher.drain(); owner.cancel()
            ProviderModelCertificationRegistry.revoke("openai")
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    companion object {
        private const val PARENT = "parent-launch-boundary"
        private fun task(id: String) = SubagentTaskRecord(
            id = id, parentSessionId = PARENT, childSessionId = "child-$id", profile = "explore",
            description = "Interrupted child", background = true, state = SubagentTaskState.RUNNING,
            delivery = SubagentDeliveryState.PENDING,
        )
        private fun writeRecord(root: File, record: SubagentTaskRecord) {
            root.resolve(".droide/subagents").mkdirs()
            root.resolve(".droide/subagents/${record.id}.json").writeText(Json.encodeToString(SubagentTaskRecord.serializer(), record))
        }
        private fun readRecord(file: File): SubagentTaskRecord = Json.decodeFromString(file.readText())
    }
}
