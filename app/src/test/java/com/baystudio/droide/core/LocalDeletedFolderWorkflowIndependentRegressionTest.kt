package com.baystudio.droide.core

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** No recovery helper invocation. */

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35], manifest = Config.NONE)
class LocalDeletedFolderWorkflowIndependentRegressionTest {
    private class Fixture(val links: Boolean = false, val extraFiles: Int = 0) {
        val home = Files.createTempDirectory("f161-independent-public-workflow-").toFile()
        val root = home.resolve("workspace").apply { mkdir() }
        val cache = home.resolve("cache").apply { mkdir() }
        val current = AtomicBoolean(true)
        val contexts = mutableListOf<AgentTools.Ctx>()
        val responders = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val acceptedReadPrompts = java.util.concurrent.CopyOnWriteArrayList<ApprovalRequest>()
        lateinit var originalDescriptor: RandomAccessFile
        lateinit var operation: String
        lateinit var prepared: String
        lateinit var retained: File
        val payload = byteArrayOf(0, 1, 2, 0x7f, 0x80.toByte(), 0xff.toByte(), 10)
        suspend fun delete() {
            val source = root.resolve("deleted").apply { mkdir() }
            source.resolve("data.bin").writeBytes(payload)
            source.resolve(".hidden").writeText("hidden bytes\n")
            source.resolve("nested/empty").mkdirs()
            source.resolve("nested/deep.txt").writeText("nested bytes\n")
            if (links) {
                val outside = home.resolve("outside-secret").apply { writeText("outside bytes must not be read into TAR") }
                Files.createLink(source.resolve("hardlink.bin").toPath(), source.resolve("data.bin").toPath())
                Files.createLink(root.resolve("outside-hardlink.bin").toPath(), source.resolve("data.bin").toPath())
                Files.createSymbolicLink(source.resolve("external-link").toPath(), outside.toPath())
                Files.createSymbolicLink(source.resolve("dangling-link").toPath(), java.nio.file.Path.of("missing-target"))
            }
            repeat(extraFiles) { source.resolve("entry-$it.txt").writeText("entry $it") }
            originalDescriptor = RandomAccessFile(source.resolve("data.bin"), "rw")
            assertTrue("Actual production directory delete must succeed", FileRepository(root).delete("deleted"))
            assertFalse(source.exists())
            val custody = root.resolve(".droide/local-delete-custody").listFiles()!!.single { it.isDirectory }
            operation = custody.name
            prepared = hash(custody.resolve("prepared.json").readBytes())
            retained = custody.resolve("original")
            assertTrue(retained.isDirectory)
            println("PRESERVED_F161_FIXTURE ${home.path}")
        }
        fun context(mode: AgentMode = AgentMode.BUILD,
            permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
            files: FileRepository = FileRepository(root), approvals: ApprovalManager = ApprovalManager(),
            documents: WorkspaceDocumentAuthority? = null, approveTree: Boolean = true, extraReadPaths: Set<String> = emptySet(), progress: (AgentToolProgress) -> Unit = {}): AgentTools.Ctx {
            fun forbidden(): Nothing = error("External execution forbidden in public folder recovery fixture")
            val web = AgentWebResearch(root, AgentWebTransport { _, _ -> forbidden() }, object : AgentWebPdfBackend {
                override fun pageCount(file: File): Int = forbidden()
                override fun text(file: File, page: Int): String? = forbidden()
                override fun screenshot(file: File, page: Int, output: OutputStream): AgentWebImageSize = forbidden()
            })
            val provenance = AgentRequestProvenance.primary("f161-owner")
            val ctx = AgentTools.Ctx(files, NoProcessTerminal(::forbidden), GitManager(root), approvals,
                QuestionManager(), EditHistory(files), permissions, mode, LlmClient(), AgentConfig(), root,
                TodoManager(), "f161-owner", object : LspManager {
                    override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
                    override suspend fun diagnose(relPath: String): String = forbidden()
                    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = forbidden()
                }, onProgress = progress, provenance = provenance, documentAuthority = documents, webResearch = web, appContext = RuntimeEnvironment.getApplication())
            val epoch = ApplicationWorkAdmission.requireOpen()
            val owner = HookInvocationAuthority(mode, provenance, ctx.permissionScope,
                { current.get() && ApplicationWorkAdmission.isCurrentOpen(epoch) })
            for ((name, value) in listOf("localRecoveryOwner" to owner, "localRecoveryCache" to cache)) {
                AgentTools.Ctx::class.java.declaredFields.firstOrNull { it.name == name }?.let {
                    it.isAccessible = true; it.set(ctx, value)
                }
            }
            // Real explicit test-user answers, never a policy bypass or a root-recursive grant.
            val known = setOf("", "data.bin", ".hidden", "nested", "nested/empty", "nested/deep.txt",
                "hardlink.bin", "external-link", "dangling-link", "private")
            val paths = known.map { suffix -> ".droide/local-delete-custody/$operation/original" + if (suffix.isEmpty()) "" else "/$suffix" }.toSet() + ".droide/local-delete-custody" + extraReadPaths + (if (approveTree) setOf(".droide/local-delete-custody/$operation/original/**") else emptySet())
            responders.launch {
                approvals.pending.filterNotNull().collect { prompt ->
                    if (prompt.action == "read" && prompt.resource in paths) {
                        if (approvals.resolve(prompt.id, ApprovalResolution.ALLOW_ONCE)) {
                            acceptedReadPrompts += prompt
                            println("ACTUAL_READ_APPROVAL_ALLOW_ONCE ${prompt.resource}")
                        }
                    }
                }
            }
            contexts += ctx
            return ctx
        }
        fun args(action: String? = null, destination: String? = null) = buildJsonObject {
            if (action != null) put("action", action)
            put("operation", operation); put("prepared_sha256", prepared)
            if (destination != null) put("destination", destination)
        }
        fun metadata() = retained.parentFile!!.walkTopDown().filter { it.isFile && !it.toPath().startsWith(retained.toPath()) }
            .associate { it.relativeTo(retained.parentFile!!).path to hash(it.readBytes()) }
        fun close() { responders.cancel(); contexts.forEach { it.webResearch.close() }; if (::originalDescriptor.isInitialized) originalDescriptor.close() }
    }

    private fun fixture(links: Boolean = false, extraFiles: Int = 0, body: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val ownerField = ApplicationWorkAdmission::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val stateField = ApplicationWorkAdmission::class.java.getDeclaredField("state").apply { isAccessible = true }
        val oldOwner = ownerField.get(ApplicationWorkAdmission); val oldState = stateField.get(ApplicationWorkAdmission)
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication()); ApplicationWorkAdmission.reopenFromUser()
        val f = Fixture(links, extraFiles)
        try { withTimeout(20_000) { f.delete(); body(f) } }
        finally { f.close(); ownerField.set(ApplicationWorkAdmission, oldOwner); stateField.set(ApplicationWorkAdmission, oldState) }
    }

    @Test fun actualLongCaptureReportsTruthfulProgressBeforeFinalPublication() = fixture { f ->
        f.retained.resolve("large.bin").outputStream().use { out -> repeat(192) { out.write(ByteArray(65536) { 47 }) } }
        val events = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Boolean>>()
        val result = success(AgentTools.execute("archive_deleted_folder", f.args(destination = "progress.tar"), f.context(permissions = permissions(), progress = { p ->
            events += p.detail to f.root.resolve("progress.tar").exists()
        })))
        println("F161_PROGRESS ${events.joinToString(" | ")}")
        assertTrue("Long actual capture must emit a capture phase before destination publication: $events", events.any { it.first.contains("Reading retained folder", true) && !it.second })
        assertTrue("Actual TAR transfer must report its phase: $events", events.any { it.first.contains("recovery archive", true) && !it.second })
        assertTrue("Staged verification is not final publication: $events", events.any { it.first.contains("Archive checked", true) && !it.second })
        assertFalse("Never claim the archive committed while staging", events.any { !it.second && (it.first.contains("committed", true) || it.first.contains("archive complete", true) || it.first.contains("archive published", true)) })
        assertEquals(JsonPrimitive(true), result["committed"]); assertEquals(JsonPrimitive(false), result["historical"])
        assertEquals(hash(f.root.resolve("progress.tar").readBytes()), result["sha256"]!!.jsonPrimitive.content)
    }

    @Test fun freshCaptureStillRequiresRealEditApprovalAndDenialWritesNothing() = fixture { f ->
        val approvals = ApprovalManager(); val before = treeIdentity(f.root); val cacheBefore = treeIdentity(f.cache)
        val work = async { AgentTools.execute("archive_deleted_folder", f.args(destination = "denied.tar"), f.context(permissions = askingEdit(), approvals = approvals)) }
        val request = pending(approvals); assertEquals("denied.tar", request.resource)
        assertEquals(before, treeIdentity(f.root)); assertTrue(approvals.resolve(request.id, ApprovalResolution.DENY))
        assertTrue(work.await().startsWith("DENIED")); assertEquals(before, treeIdentity(f.root)); assertEquals(cacheBefore, treeIdentity(f.cache))
    }

    @Test fun sealedReplayStillHonorsEditDenyModeAndProfileAuthority() = fixture { f ->
        success(AgentTools.execute("archive_deleted_folder", f.args(destination = "rescue.tar"), f.context(permissions = permissions())))
        val before = treeIdentity(f.root)
        for (ctx in listOf(f.context(permissions = permissions(PermEffect.DENY)), f.context(mode = AgentMode.PLAN, permissions = permissions()))) {
            val raw = AgentTools.execute("archive_deleted_folder", f.args(destination = "rescue.tar"), ctx)
            assertTrue(raw, raw.startsWith("DENIED")); assertEquals(before, treeIdentity(f.root))
        }
        val restrictions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)), toolRestrictions = listOf(setOf("local_deleted_folders")))
        val raw = AgentTools.execute("archive_deleted_folder", f.args(destination = "rescue.tar"), f.context(permissions = restrictions))
        assertTrue(raw, raw.startsWith("DENIED")); assertEquals(before, treeIdentity(f.root))
    }

    @Test fun progressDisplayExceptionDoesNotRevokeSuccessfulCommit() = fixture { f ->
        val events = java.util.concurrent.atomic.AtomicInteger()
        val raw = AgentTools.execute("archive_deleted_folder", f.args(destination = "display.tar"), f.context(permissions = permissions(), progress = {
            events.incrementAndGet(); throw IOException("independent injected display failure")
        }))
        val result = success(raw); assertTrue("Progress must actually be attempted", events.get() > 0)
        assertEquals(JsonPrimitive(true), result["committed"]); assertTrue(f.root.resolve("display.tar").isFile)
        assertEquals(hash(f.root.resolve("display.tar").readBytes()), result["sha256"]!!.jsonPrimitive.content)
    }

    @Test fun ownerCancellationDuringCaptureProgressCannotPublish() = fixture { f ->
        var seen = false
        val raw = runCatching { AgentTools.execute("archive_deleted_folder", f.args(destination = "owner-cancel.tar"), f.context(permissions = permissions(), progress = { p ->
            if (p.detail.contains("Reading retained folder", true)) { seen = true; f.current.set(false) }
        })) }
        assertTrue("Must exercise actual capture progress", seen)
        assertTrue(raw.toString(), raw.exceptionOrNull() is CancellationException)
        assertFalse(f.root.resolve("owner-cancel.tar").exists()); assertArrayEquals(f.payload, f.retained.resolve("data.bin").readBytes())
        assertFalse(f.root.walkTopDown().any { it.name == "seal.json" })
    }

    @Test fun callerCancellationDuringCaptureProgressCannotPublish() = fixture { f ->
        var seen = false
        val call = async(start = CoroutineStart.LAZY) {
            val ownJob = currentCoroutineContext()[Job]!!
            AgentTools.execute("archive_deleted_folder", f.args(destination = "caller-cancel.tar"), f.context(permissions = permissions(), progress = { p ->
                if (p.detail.contains("Reading retained folder", true)) { seen = true; ownJob.cancel(CancellationException("independent caller stop")) }
            }))
        }
        call.start(); val raw = runCatching { call.await() }
        assertTrue("Must exercise actual capture progress", seen); assertTrue(raw.exceptionOrNull() is CancellationException)
        assertFalse(f.root.resolve("caller-cancel.tar").exists()); assertArrayEquals(f.payload, f.retained.resolve("data.bin").readBytes())
        assertFalse(f.root.walkTopDown().any { it.name == "seal.json" })
    }

    @Test fun exactDestinationInspectorIsReadOnlyEvenWhenEditIsDenied() = fixture { f ->
        val original = success(AgentTools.execute("archive_deleted_folder", f.args(destination = "rescue.tar"), f.context(permissions = permissions())))
        Files.move(f.root.resolve("rescue.tar").toPath(), f.home.resolve("original-rescue.tar").toPath())
        f.root.resolve("rescue.tar").writeText("replacement user content")
        val before = treeIdentity(f.root); val cacheBefore = treeIdentity(f.cache)
        val approvals = ApprovalManager(); val seen = java.util.concurrent.CopyOnWriteArrayList<ApprovalRequest>()
        val responder = launch { approvals.pending.filterNotNull().collect { p -> seen += p; approvals.resolve(p.id, ApprovalResolution.DENY) } }
        val raw = try { AgentTools.execute("local_deleted_folders", f.args("inspect", "rescue.tar"), f.context(mode = AgentMode.PLAN, permissions = permissions(PermEffect.DENY), approvals = approvals)) }
            finally { responder.cancelAndJoin() }
        val observed = success(raw).getValue("archive").jsonObject
        assertTrue(seen.isEmpty()); assertEquals(JsonPrimitive("COMMITTED"), observed["status"])
        assertEquals(original["sha256"], observed["sha256"]); assertEquals(JsonPrimitive(false), observed["current_file_reverified"])
        assertEquals(JsonPrimitive(false), observed["receipt_durability_reconfirmed"])
        assertEquals(JsonPrimitive("UNKNOWN_NOT_REPLAYED"), observed["synchronization_status"])
        assertEquals(before, treeIdentity(f.root)); assertEquals(cacheBefore, treeIdentity(f.cache))
    }

    @Test fun samePhaseProgressIsRateBoundedAndNeverQueuedAfterReturn() = fixture(extraFiles = 600) { f ->
        val events = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Long>>()
        var slowed = false
        success(AgentTools.execute("archive_deleted_folder", f.args(destination = "bounded.tar"), f.context(permissions = permissions(), progress = { p ->
            events += p.detail to System.nanoTime()
            if (!slowed && p.detail.startsWith("Reading retained folder")) { slowed = true; Thread.sleep(350) }
        })))
        val capture = events.filter { it.first.startsWith("Reading retained folder") }
        assertTrue("Exercise more than one actual capture progress event: $capture", capture.size >= 2)
        capture.zipWithNext().forEach { (a, b) -> assertTrue("Same-phase progress must be throttled: $capture", b.second - a.second >= 240_000_000L) }
        val observedDuration = capture.last().second - capture.first().second
        assertTrue("Capture event count must remain bounded by elapsed time", capture.size <= 2 + (observedDuration / 240_000_000L).toInt())
        val completedCount = events.size; delay(350); assertEquals("No delayed progress after final result", completedCount, events.size)
    }

    @Test fun revokingOwnerDuringCopyingCannotPublish() = fixture { f ->
        var seen = false
        val outcome = runCatching { AgentTools.execute("archive_deleted_folder", f.args(destination = "copy-cancel.tar"), f.context(permissions = permissions(), progress = { p ->
            if (p.detail.startsWith("Saving archive to project")) { seen = true; f.current.set(false) }
        })) }
        assertTrue("Exercise actual project-copy progress", seen); assertTrue(outcome.toString(), outcome.exceptionOrNull() is CancellationException)
        assertFalse(f.root.resolve("copy-cancel.tar").exists()); assertFalse(f.root.walkTopDown().any { it.name == "seal.json" })
    }

    @Test fun revokingOwnerDuringSynchronizationPreservesCommitWithoutSuccessDisclosure() = fixture { f ->
        var seen = false
        val outcome = runCatching { AgentTools.execute("archive_deleted_folder", f.args(destination = "sync-cancel.tar"), f.context(permissions = permissions(), progress = { p ->
            if (p.detail.startsWith("Updating project")) { seen = true; f.current.set(false) }
        })) }
        assertTrue("Exercise actual post-publication synchronization progress", seen)
        assertTrue(outcome.toString(), outcome.exceptionOrNull() is CancellationException)
        assertTrue("Actual publication cannot be rolled back by late cancellation", f.root.resolve("sync-cancel.tar").isFile)
        f.current.set(true)
        val receipt = success(AgentTools.execute("local_deleted_folders", f.args("inspect", "sync-cancel.tar"), f.context(mode = AgentMode.PLAN, permissions = permissions(PermEffect.DENY)))).getValue("archive").jsonObject
        assertEquals(JsonPrimitive("COMMITTED"), receipt["status"])
        assertEquals(hash(f.root.resolve("sync-cancel.tar").readBytes()), receipt["sha256"]!!.jsonPrimitive.content)
    }

    @Test fun revokingPolicyDuringSynchronizationPreservesCommitWithoutSuccessDisclosure() = fixture { f ->
        var seen = false; val policy = permissions()
        val outcome = runCatching { AgentTools.execute("archive_deleted_folder", f.args(destination = "sync-policy.tar"), f.context(permissions = policy, progress = { p ->
            if (p.detail.startsWith("Updating project")) { seen = true; policy.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("edit", "*", PermEffect.DENY)))) }
        })) }
        assertTrue("Exercise actual post-publication policy change", seen)
        assertTrue("Policy revocation must cross the repository callback boundary as cancellation: $outcome", outcome.exceptionOrNull() is CancellationException)
        assertTrue("Policy failure must be retained as the cancellation cause", outcome.exceptionOrNull()?.cause is IllegalStateException)
        assertTrue(f.root.resolve("sync-policy.tar").isFile)
        val receipt = success(AgentTools.execute("local_deleted_folders", f.args("inspect", "sync-policy.tar"), f.context(mode = AgentMode.PLAN, permissions = permissions(PermEffect.DENY)))).getValue("archive").jsonObject
        assertEquals(JsonPrimitive("COMMITTED"), receipt["status"])
    }

    @Test fun revokingPolicyDuringCopyingPropagatesCancellationAndCannotPublish() = fixture { f ->
        var seen = false; val policy = permissions()
        val outcome = runCatching { AgentTools.execute("archive_deleted_folder", f.args(destination = "copy-policy.tar"), f.context(permissions = policy, progress = { p ->
            if (p.detail.startsWith("Saving archive to project")) { seen = true; policy.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("edit", "*", PermEffect.DENY)))) }
        })) }
        assertTrue("Exercise policy revocation across actual repository copy callback", seen)
        assertTrue(outcome.toString(), outcome.exceptionOrNull() is CancellationException)
        assertTrue("Policy failure must remain available", outcome.exceptionOrNull()?.cause is IllegalStateException)
        assertFalse(f.root.resolve("copy-policy.tar").exists()); assertFalse(f.root.walkTopDown().any { it.name == "seal.json" })
        assertArrayEquals(f.payload, f.retained.resolve("data.bin").readBytes())
    }

    private fun askingEdit() = permissions(PermEffect.ASK)
    private fun permissions(edit: PermEffect = PermEffect.ALLOW, extra: List<PermRule> = emptyList()) = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("edit", "*", edit)) + extra, PermissionPolicyDocument(rules = listOf(PermRule("read", ".droide/*", PermEffect.ALLOW))))
    private fun treeIdentity(root: File) = root.walkTopDown().associate { it.relativeTo(root).path to listOf(Files.getAttribute(it.toPath(), "unix:ino", NOFOLLOW_LINKS).toString(), Files.getLastModifiedTime(it.toPath(), NOFOLLOW_LINKS).toString(), if (it.isFile) hash(it.readBytes()) else "directory") }
    private suspend fun pending(approvals: ApprovalManager, action: String = "edit", suffix: String? = null, exceptId: Long? = null): ApprovalRequest = withTimeout(5_000) {
        while (true) {
            val prompt = approvals.pending.value
            if (prompt != null && prompt.action == action && (suffix == null || prompt.resource.endsWith(suffix)) && prompt.id != exceptId) return@withTimeout prompt
            delay(5)
        }
        error("unreachable")
    }
    private fun workspaceBytes(root: File) = root.walkTopDown().filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
        .associate { it.relativeTo(root).path to hash(it.readBytes()) }

    /** Independent test extractor. Never follows symlinks while writing an archive entry. */
    private fun extract(archive: File, destination: File): Map<String, String> {
        val types = linkedMapOf<String, String>()
        val links = mutableListOf<Pair<java.nio.file.Path, java.nio.file.Path>>()
        TarArchiveInputStream(archive.inputStream()).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                val name = e.name.removeSuffix("/")
                require(!name.startsWith("/") && name.split('/').none { it == ".." })
                val path = destination.toPath().resolve(name).normalize()
                require(path.startsWith(destination.toPath()))
                var parent = path.parent
                while (parent != null && parent.startsWith(destination.toPath())) {
                    require(!Files.isSymbolicLink(parent)); parent = parent.parent
                }
                when {
                    e.isDirectory -> { Files.createDirectories(path); types[name] = "directory" }
                    e.isSymbolicLink -> { Files.createDirectories(path.parent); Files.createSymbolicLink(path, java.nio.file.Path.of(e.linkName)); types[name] = "symlink" }
                    e.isLink -> { val target = destination.toPath().resolve(e.linkName).normalize(); require(target.startsWith(destination.toPath())); links += path to target; types[name] = "hardlink" }
                    e.isFile -> { Files.createDirectories(path.parent); Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW).use { tar.copyTo(it) }; types[name] = "file" }
                    else -> error("Unexpected TAR entry ${e.name}")
                }
            }
        }
        links.forEach { (path, target) -> require(Files.isRegularFile(target, NOFOLLOW_LINKS)); Files.createLink(path, target) }
        return types
    }

    private class NoProcessTerminal(private val forbidden: () -> Nothing) : ITerminalSession {
        override val output = MutableStateFlow("")
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer, onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun success(result: String): JsonObject {
            assertFalse(result, result.startsWith("ERROR:") || result.startsWith("DENIED"))
            return Json.parseToJsonElement(result).jsonObject.also { assertEquals(result, JsonPrimitive(true), it["success"]) }
        }
    }
}
