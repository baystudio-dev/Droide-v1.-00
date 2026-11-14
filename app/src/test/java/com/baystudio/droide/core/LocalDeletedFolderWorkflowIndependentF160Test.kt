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

/** Real deletion, fresh repository, public Agent discovery/execution. No recovery helper invocation.
 * Context lifecycle slots are installed reflectively solely so this identical regression compiles
 * against the pre-F160 baseline where those slots and tools do not yet exist. Fixtures are retained.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35], manifest = Config.NONE)
class LocalDeletedFolderWorkflowIndependentF160Test {
    private class Fixture(val links: Boolean = false, val extraFiles: Int = 0) {
        val home = Files.createTempDirectory("f160-independent-public-workflow-").toFile()
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
            println("PRESERVED_F160_FIXTURE ${home.path}")
        }
        fun context(mode: AgentMode = AgentMode.BUILD,
            permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
            files: FileRepository = FileRepository(root), approvals: ApprovalManager = ApprovalManager(),
            documents: WorkspaceDocumentAuthority? = null, approveTree: Boolean = true, extraReadPaths: Set<String> = emptySet()): AgentTools.Ctx {
            fun forbidden(): Nothing = error("External execution forbidden in public folder recovery fixture")
            val web = AgentWebResearch(root, AgentWebTransport { _, _ -> forbidden() }, object : AgentWebPdfBackend {
                override fun pageCount(file: File): Int = forbidden()
                override fun text(file: File, page: Int): String? = forbidden()
                override fun screenshot(file: File, page: Int, output: OutputStream): AgentWebImageSize = forbidden()
            })
            val provenance = AgentRequestProvenance.primary("f160-owner")
            val ctx = AgentTools.Ctx(files, NoProcessTerminal(::forbidden), GitManager(root), approvals,
                QuestionManager(), EditHistory(files), permissions, mode, LlmClient(), AgentConfig(), root,
                TodoManager(), "f160-owner", object : LspManager {
                    override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
                    override suspend fun diagnose(relPath: String): String = forbidden()
                    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = forbidden()
                }, provenance = provenance, documentAuthority = documents, webResearch = web, appContext = RuntimeEnvironment.getApplication())
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

    @Test fun realDeleteRestartPublicInspectAndArchivePreserveEveryPayloadByte() = fixture { f ->
        val ctx = f.context() // A genuinely new repository and context, no original deletion object retained.
        val discovered = AgentTools.definitions.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        val listed = AgentTools.execute("local_deleted_folders", buildJsonObject { put("action", "list") }, ctx)
        val inspected = AgentTools.execute("local_deleted_folders", f.args("inspect"), ctx)
        val archived = AgentTools.execute("archive_deleted_folder", f.args(destination = "rescue.tar"), ctx)
        println("PUBLIC_ROUTE_DISCOVERY local_deleted_folders=${"local_deleted_folders" in discovered} archive_deleted_folder=${"archive_deleted_folder" in discovered}")
        println("PUBLIC_ROUTE_LIST $listed\nPUBLIC_ROUTE_INSPECT $inspected\nPUBLIC_ROUTE_ARCHIVE $archived")
        assertTrue("Real deleted-folder public route missing; discovery=$discovered; list=$listed; inspect=$inspected; archive=$archived",
            "local_deleted_folders" in discovered && "archive_deleted_folder" in discovered)
        val listing = success(listed)
        val selected = listing.getValue("items").jsonArray.single().jsonObject
        assertEquals(f.operation, selected.getValue("operation").jsonPrimitive.content)
        assertEquals(f.prepared, selected.getValue("prepared_sha256").jsonPrimitive.content)
        assertEquals("deleted", success(inspected).getValue("item").jsonObject.getValue("source").jsonPrimitive.content)
        val receipt = success(archived)
        assertEquals(JsonPrimitive(true), receipt["committed"])
        assertEquals(JsonPrimitive(false), receipt["historical"])
        assertTrue(f.root.resolve("rescue.tar").isFile)
        assertArrayEquals(f.payload, f.retained.resolve("data.bin").readBytes())
        assertEquals("hidden bytes\n", f.retained.resolve(".hidden").readText())
        assertTrue(f.retained.resolve("nested/empty").isDirectory)
        assertFalse(f.root.resolve("deleted").exists())
    }


    @Test fun archiveExtractsBinaryHiddenEmptySymlinksAndHardlinksWithoutTouchingOriginals() = fixture(links = true) { f ->
        val before = f.metadata()
        val outside = f.home.resolve("outside-secret").readBytes()
        val result = success(AgentTools.execute("archive_deleted_folder", f.args(destination = "rescue.tar"), f.context()))
        val tar = f.root.resolve("rescue.tar")
        assertEquals(tar.length(), result.getValue("bytes").jsonPrimitive.long)
        assertEquals(hash(tar.readBytes()), result.getValue("sha256").jsonPrimitive.content)
        val extracted = f.home.resolve("extracted").apply { mkdir() }
        val types = extract(tar, extracted)
        assertArrayEquals(f.payload, extracted.resolve("data.bin").readBytes())
        assertArrayEquals(f.payload, extracted.resolve("hardlink.bin").readBytes())
        assertTrue(Files.isSameFile(extracted.resolve("data.bin").toPath(), extracted.resolve("hardlink.bin").toPath()))
        assertEquals("hidden bytes\n", extracted.resolve(".hidden").readText())
        assertEquals("nested bytes\n", extracted.resolve("nested/deep.txt").readText())
        assertTrue(extracted.resolve("nested/empty").isDirectory)
        assertTrue(Files.isSymbolicLink(extracted.resolve("external-link").toPath()))
        assertEquals(f.home.resolve("outside-secret").toPath(), Files.readSymbolicLink(extracted.resolve("external-link").toPath()))
        assertEquals(java.nio.file.Path.of("missing-target"), Files.readSymbolicLink(extracted.resolve("dangling-link").toPath()))
        assertTrue(types.values.any { it == "hardlink" }); assertEquals(2, types.values.count { it == "symlink" })
        assertFalse(types.keys.any { it.contains("outside-secret") || it.contains("outside-hardlink") })
        assertArrayEquals(outside, f.home.resolve("outside-secret").readBytes())
        assertEquals(before, f.metadata())
        assertTrue(Files.isSameFile(f.retained.resolve("data.bin").toPath(), f.root.resolve("outside-hardlink.bin").toPath()))
        f.originalDescriptor.seek(f.payload.size.toLong()); f.originalDescriptor.write("late writer".toByteArray())
        assertArrayEquals(f.payload + "late writer".toByteArray(), f.retained.resolve("data.bin").readBytes())
        assertArrayEquals(f.payload, extracted.resolve("data.bin").readBytes())
        assertEquals(hash(tar.readBytes()), result.getValue("sha256").jsonPrimitive.content)
        assertEquals(before, f.metadata())
    }

    @Test fun buildArchiveWaitsForExplicitApprovalBeforeAnyRecoveryMutation() = fixture { f ->
        val approvals = ApprovalManager()
        val ctx = f.context(permissions = askingEdit(), approvals = approvals)
        val before = workspaceBytes(f.root)
        val action = async { AgentTools.execute("archive_deleted_folder", f.args(destination = "approved.tar"), ctx) }
        val prompt = pending(approvals)
        assertEquals("edit", prompt.action); assertEquals("approved.tar", prompt.resource)
        assertTrue(prompt.detail.contains(f.operation)); assertTrue(prompt.detail.contains(f.prepared))
        assertEquals(before, workspaceBytes(f.root)); assertFalse(action.isCompleted)
        assertTrue(approvals.resolve(prompt.id, ApprovalResolution.ALLOW_ONCE))
        success(action.await()); assertTrue(f.root.resolve("approved.tar").isFile)
    }

    @Test fun planReviewAndExploreCanInspectButCannotArchiveOrAllocateRecoveryEvidence() = fixture { f ->
        val before = workspaceBytes(f.root)
        for (mode in listOf(AgentMode.PLAN, AgentMode.REVIEW, AgentMode.EXPLORE)) {
            val ctx = f.context(mode)
            success(AgentTools.execute("local_deleted_folders", f.args("inspect"), ctx))
            val result = AgentTools.execute("archive_deleted_folder", f.args(destination = "${mode.name}.tar"), ctx)
            assertTrue(result, result.startsWith("DENIED"))
        }
        assertEquals(before, workspaceBytes(f.root))
    }

    @Test fun policyDenialForEditOriginalReadAndCustodyReadCannotAllocateRecoveryEvidence() = fixture { f ->
        val before = workspaceBytes(f.root)
        val denied = listOf(PermRule("edit", "*", PermEffect.DENY),
            PermRule("read", "deleted", PermEffect.DENY),
            PermRule("read", ".droide/local-delete-custody/*", PermEffect.DENY))
        for (rule in denied) {
            val ctx = f.context(permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), rule)))
            val result = AgentTools.execute("archive_deleted_folder", f.args(destination = "denied.tar"), ctx)
            assertTrue(result, result.startsWith("DENIED")); assertEquals(before, workspaceBytes(f.root))
        }
    }

    @Test fun canceledApprovalCannotCreateArchiveOrBeResolvedLater() = fixture { f ->
        val approvals = ApprovalManager(); val ctx = f.context(permissions = askingEdit(), approvals = approvals)
        val before = workspaceBytes(f.root)
        val action = async { AgentTools.execute("archive_deleted_folder", f.args(destination = "canceled.tar"), ctx) }
        val prompt = pending(approvals)
        action.cancelAndJoin()
        assertTrue(action.isCancelled); assertNull(approvals.pending.value)
        assertFalse(approvals.resolve(prompt.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals(before, workspaceBytes(f.root))
    }

    @Test fun staleApplicationEpochAfterApprovalCannotPublishArchive() = fixture { f ->
        val approvals = ApprovalManager(); val ctx = f.context(permissions = askingEdit(), approvals = approvals)
        val before = workspaceBytes(f.root)
        val action = async { runCatching { AgentTools.execute("archive_deleted_folder", f.args(destination = "stale.tar"), ctx) } }
        val prompt = pending(approvals)
        ApplicationWorkAdmission.closeForExit(); ApplicationWorkAdmission.reopenFromUser()
        approvals.resolve(prompt.id, ApprovalResolution.ALLOW_ONCE)
        assertTrue("Stale owner must propagate cancellation", action.await().exceptionOrNull() is CancellationException)
        assertEquals(before, workspaceBytes(f.root))
    }

    @Test fun staleOwnerBeforeCallCannotPublishArchive() = fixture { f ->
        val ctx = f.context(); val before = workspaceBytes(f.root); f.current.set(false)
        val result = runCatching { AgentTools.execute("archive_deleted_folder", f.args(destination = "stale.tar"), ctx) }
        assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is CancellationException)
        assertEquals(before, workspaceBytes(f.root))
    }

    @Test fun destinationAppearingDuringApprovalIsPreservedWithoutPublication() = fixture { f ->
        val approvals = ApprovalManager(); val ctx = f.context(permissions = askingEdit(), approvals = approvals)
        val originalMetadata = f.metadata()
        val action = async { AgentTools.execute("archive_deleted_folder", f.args(destination = "conflict.tar"), ctx) }
        val prompt = pending(approvals)
        f.root.resolve("conflict.tar").writeText("user-created destination")
        assertTrue(approvals.resolve(prompt.id, ApprovalResolution.ALLOW_ONCE))
        val result = action.await(); assertTrue(result, result.startsWith("ERROR:"))
        assertEquals("user-created destination", f.root.resolve("conflict.tar").readText())
        assertEquals(originalMetadata, f.metadata()); assertFalse(f.root.walkTopDown().any { it.name == "receipt.json" || it.name == "seal.json" })
    }

    @Test fun rootReplacedDuringApprovalPreservesBothRootsWithoutPublication() = fixture { f ->
        val approvals = ApprovalManager(); val ctx = f.context(permissions = askingEdit(), approvals = approvals)
        val before = workspaceBytes(f.root)
        val action = async { AgentTools.execute("archive_deleted_folder", f.args(destination = "root-race.tar"), ctx) }
        val prompt = pending(approvals)
        val moved = f.home.resolve("original-workspace")
        Files.move(f.root.toPath(), moved.toPath()); f.root.mkdir(); f.root.resolve("user.txt").writeText("replacement root")
        assertTrue(approvals.resolve(prompt.id, ApprovalResolution.ALLOW_ONCE))
        val result = action.await(); assertTrue(result, result.startsWith("ERROR:"))
        assertEquals(before, workspaceBytes(moved)); assertEquals(mapOf("user.txt" to hash("replacement root".toByteArray())), workspaceBytes(f.root))
    }

    @Test fun syncFailureReportsCommittedArchiveAndRestartRetryIsHistoricalOnly() = fixture { f ->
        var writes = 0
        val repo = FileRepository(f.root, onMutation = { if (it is FileRepository.Mutation.Write) { writes++; throw IOException("injected sync failure") } })
        val first = success(AgentTools.execute("archive_deleted_folder", f.args(destination = "sync.tar"), f.context(files = repo)))
        assertEquals(JsonPrimitive(true), first["committed"]); assertEquals(JsonPrimitive(true), first["synchronization_pending"])
        assertEquals(1, writes)
        val before = workspaceBytes(f.root)
        val retry = success(AgentTools.execute("archive_deleted_folder", f.args(destination = "sync.tar"), f.context()))
        assertEquals(JsonPrimitive(true), retry["historical"]); assertEquals(JsonPrimitive(false), retry["current_file_reverified"])
        assertEquals(first["archive_id"], retry["archive_id"]); assertEquals(first["sha256"], retry["sha256"])
        assertEquals(JsonNull, retry["synchronization_pending"]); assertEquals(JsonPrimitive("UNKNOWN_NOT_REPLAYED"), retry["synchronization_status"])
        assertEquals(before, workspaceBytes(f.root)); assertEquals(1, writes)
    }

    @Test fun lostReturnAfterPublicationReopensDurableReceiptThroughFreshPublicRoute() = fixture { f ->
        val canceled = CancellationException("injected lost return after archive publication")
        val repo = FileRepository(f.root, onMutation = { if (it is FileRepository.Mutation.Write) throw canceled })
        val lost = runCatching { AgentTools.execute("archive_deleted_folder", f.args(destination = "lost.tar"), f.context(files = repo)) }
        assertTrue(lost.exceptionOrNull().toString(), lost.exceptionOrNull() is CancellationException)
        val tar = f.root.resolve("lost.tar"); assertTrue("Publication must precede lost return", tar.isFile)
        f.originalDescriptor.seek(f.payload.size.toLong()); f.originalDescriptor.write("after lost return".toByteArray())
        val before = workspaceBytes(f.root)
        val retry = success(AgentTools.execute("archive_deleted_folder", f.args(destination = "lost.tar"), f.context()))
        assertEquals(JsonPrimitive(true), retry["historical"]); assertEquals(hash(tar.readBytes()), retry["sha256"]!!.jsonPrimitive.content)
        assertEquals(before, workspaceBytes(f.root))
    }

    @Test fun retryNeverOverwritesForeignReplacementAtHistoricallyPublishedDestination() = fixture { f ->
        val first = success(AgentTools.execute("archive_deleted_folder", f.args(destination = "historic.tar"), f.context()))
        Files.move(f.root.resolve("historic.tar").toPath(), f.home.resolve("published-original.tar").toPath())
        f.root.resolve("historic.tar").writeText("foreign newer content")
        val before = workspaceBytes(f.root)
        val retry = success(AgentTools.execute("archive_deleted_folder", f.args(destination = "historic.tar"), f.context()))
        assertEquals(JsonPrimitive(true), retry["historical"]); assertEquals(JsonPrimitive(false), retry["current_file_reverified"])
        assertEquals(first["sha256"], retry["sha256"]); assertEquals(before, workspaceBytes(f.root))
        assertEquals("foreign newer content", f.root.resolve("historic.tar").readText())
    }


    @Test fun sensitiveEnvRequiresExactReadApprovalsBeforeCaptureAndApprovedArchiveContainsIt() = fixture { f ->
        f.retained.resolve(".env").writeText("API_TOKEN=fixture-only\n")
        val approvals = ApprovalManager(); val ctx = f.context(approvals = approvals)
        val before = workspaceBytes(f.root)
        val action = async { AgentTools.execute("archive_deleted_folder", f.args(destination = "env-approved.tar"), ctx) }
        val first = pending(approvals, action = "read", suffix = "/.env")
        assertFalse(action.isCompleted); assertEquals(before, workspaceBytes(f.root))
        assertEquals("deleted/.env", first.resource)
        val tree = f.acceptedReadPrompts.single { it.kind == "local_deleted_folder_tree" }
        assertTrue(tree.detail.contains("hidden and sensitive")); assertTrue(tree.detail.contains(f.operation))
        assertEquals(".droide/local-delete-custody/${f.operation}/original/**", tree.resource)
        assertFalse(tree.canAllowForSession)
        assertTrue(approvals.resolve(first.id, ApprovalResolution.ALLOW_ONCE))
        success(action.await())
        val destination = f.home.resolve("env-extracted").apply { mkdir() }; extract(f.root.resolve("env-approved.tar"), destination)
        assertEquals("API_TOKEN=fixture-only\n", destination.resolve(".env").readText())
        assertTrue(f.acceptedReadPrompts.none { it.resource.endsWith("/.env") })
    }

    @Test fun canceledSensitiveReadApprovalLeavesNoPublishedArchiveOrCustodyMutation() = fixture { f ->
        f.retained.resolve(".env").writeText("secret fixture")
        val approvals = ApprovalManager(); val ctx = f.context(approvals = approvals)
        val before = workspaceBytes(f.root)
        val action = async { AgentTools.execute("archive_deleted_folder", f.args(destination = "env-canceled.tar"), ctx) }
        val prompt = pending(approvals, action = "read", suffix = "/.env")
        action.cancelAndJoin(); assertTrue(action.isCancelled)
        assertFalse(approvals.resolve(prompt.id, ApprovalResolution.ALLOW_ONCE)); assertEquals(before, workspaceBytes(f.root))
    }

    @Test fun originalAndRetainedDescendantDenialsOverrideApprovedRoot() = fixture { f ->
        f.retained.resolve("private").mkdir(); f.retained.resolve("private/secret.txt").writeText("nested private fixture")
        val before = workspaceBytes(f.root)
        for (resource in listOf("deleted/private/*", ".droide/local-delete-custody/${f.operation}/original/private/*")) {
            val ctx = f.context(permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("read", resource, PermEffect.DENY))))
            val result = AgentTools.execute("archive_deleted_folder", f.args(destination = "private-denied.tar"), ctx)
            assertTrue(result, result.startsWith("DENIED")); assertEquals(before, workspaceBytes(f.root)); assertTrue(f.cache.listFiles().orEmpty().isEmpty())
        }
        assertTrue(f.acceptedReadPrompts.any { it.resource == ".droide/local-delete-custody/${f.operation}/original" })
    }

    @Test fun newUnapprovedSensitiveEntryAfterPreflightCannotReachArchivePublication() = fixture { f ->
        var snapshots = 0
        val documents = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? {
                snapshots++
                if (snapshots == 1) f.retained.resolve(".env").writeText("new unapproved fixture")
                return null
            }
            override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
            override suspend fun replaceText(path: String, expected: WorkspaceDocumentVersion, content: String, reason: String): WorkspaceDocumentMutationResult = error("No editor replacement expected")
        }
        val result = AgentTools.execute("archive_deleted_folder", f.args(destination = "new-sensitive.tar"), f.context(documents = documents))
        assertTrue("Expected real document-authority boundary", snapshots > 0)
        assertTrue(result, result.startsWith("ERROR:") && result.contains("fresh read approval"))
        assertFalse(f.root.resolve("new-sensitive.tar").exists())
        assertEquals("new unapproved fixture", f.retained.resolve(".env").readText())
        assertTrue(f.acceptedReadPrompts.none { it.resource.endsWith("/.env") })
        val beforeInspect = workspaceBytes(f.root)
        val inspected = inspectArchive(f, "new-sensitive.tar")
        assertEquals(JsonPrimitive("PREPARED"), inspected["status"]); assertEquals(JsonNull, inspected["bytes"])
        assertEquals(beforeInspect, workspaceBytes(f.root))
    }

    @Test fun recreatedSourceIsPreservedAndRetainedFolderStillArchives() = fixture { f ->
        val recreated = f.root.resolve("deleted").apply { mkdir() }; recreated.resolve("new.txt").writeText("new user folder")
        val ctx = f.context()
        val item = success(AgentTools.execute("local_deleted_folders", f.args("inspect"), ctx)).getValue("item").jsonObject
        assertEquals(JsonPrimitive("RECREATED"), item["source_state"])
        success(AgentTools.execute("archive_deleted_folder", f.args(destination = "recreated.tar"), ctx))
        assertEquals("new user folder", recreated.resolve("new.txt").readText()); assertArrayEquals(f.payload, f.retained.resolve("data.bin").readBytes())
        val extracted = f.home.resolve("recreated-extracted").apply { mkdir() }; extract(f.root.resolve("recreated.tar"), extracted)
        assertArrayEquals(f.payload, extracted.resolve("data.bin").readBytes()); assertFalse(extracted.resolve("new.txt").exists())
    }


    @Test fun oneExplicitRetainedTreeConsentSupportsSixHundredOrdinaryFiles() = fixture(extraFiles = 600) { f ->
        val result = success(AgentTools.execute("archive_deleted_folder", f.args(destination = "large-folder.tar"), f.context()))
        val grants = f.acceptedReadPrompts.filter { it.kind == "local_deleted_folder_tree" }
        assertEquals(1, grants.size); assertFalse(grants.single().canAllowForSession)
        val extracted = f.home.resolve("large-extracted").apply { mkdir() }; val entries = extract(f.root.resolve("large-folder.tar"), extracted)
        repeat(600) { assertEquals("entry $it", extracted.resolve("entry-$it.txt").readText()) }
        assertTrue(entries.size >= 606); assertEquals(JsonPrimitive(true), result["committed"])
    }

    @Test fun canceledExplicitTreeConsentDoesNotCaptureOrPublishAnything() = fixture { f ->
        val approvals = ApprovalManager(); val ctx = f.context(approvals = approvals, approveTree = false)
        val before = workspaceBytes(f.root)
        val action = async { AgentTools.execute("archive_deleted_folder", f.args(destination = "tree-canceled.tar"), ctx) }
        val prompt = pending(approvals, action = "read", suffix = "/**")
        assertEquals("local_deleted_folder_tree", prompt.kind); assertFalse(prompt.canAllowForSession)
        assertTrue(prompt.detail.contains("hidden and sensitive")); assertEquals(before, workspaceBytes(f.root))
        action.cancelAndJoin(); assertFalse(approvals.resolve(prompt.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals(before, workspaceBytes(f.root))
    }

    @Test fun publicListPaginatesPastOneHundredTwentyEightRealDirectoryDeletions() = fixture { f ->
        val expected = mutableSetOf("deleted")
        repeat(130) { index ->
            val name = "another-$index"; expected += name
            f.root.resolve(name).apply { mkdir(); resolve("file.txt").writeText("$index") }
            assertTrue(FileRepository(f.root).delete(name))
        }
        val ctx = f.context(); var cursor: String? = null; val found = mutableSetOf<String>(); var pages = 0
        do {
            val result = success(AgentTools.execute("local_deleted_folders", buildJsonObject {
                put("action", "list"); cursor?.let { put("cursor", it) }
            }, ctx))
            val items = result.getValue("items").jsonArray
            assertTrue(items.size <= 32)
            items.forEach { assertTrue("Duplicate operation in pagination", found.add(it.jsonObject.getValue("source").jsonPrimitive.content)) }
            cursor = result["next_cursor"]?.jsonPrimitive?.contentOrNull
            pages++
            assertEquals(cursor == null, result.getValue("complete").jsonPrimitive.boolean)
        } while (cursor != null)
        assertEquals(expected, found); assertEquals(5, pages)
    }

    @Test fun ownerRevokedInsideFinalStreamAdmissionCannotPublish() = suspendedFinalAdmission("owner")
    @Test fun policyRevokedInsideFinalStreamAdmissionCannotPublish() = suspendedFinalAdmission("policy")
    @Test fun callerCanceledInsideFinalStreamAdmissionCannotPublish() = suspendedFinalAdmission("caller")

    private fun suspendedFinalAdmission(revoke: String) = fixture { f ->
        var calls = 0
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = FileRepository.forAdmittedStreamWrites(f.root) {
            calls++
            if (calls == 2) { reached.complete(Unit); release.await() }
        }
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))
        val ctx = f.context(files = repo, permissions = permissions)
        val metadata = f.metadata()
        val action = async { runCatching { AgentTools.execute("archive_deleted_folder", f.args(destination = "final-$revoke.tar"), ctx) } }
        withTimeout(10_000) { reached.await() }
        assertFalse(f.root.resolve("final-$revoke.tar").exists())
        when (revoke) {
            "owner" -> f.current.set(false)
            "policy" -> permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("edit", "*", PermEffect.DENY))))
            "caller" -> action.cancel()
        }
        release.complete(Unit)
        if (revoke == "caller") { action.join(); assertTrue(action.isCancelled) }
        else {
            val result = action.await()
            assertTrue("Final revocation must fail or cancel: $result", result.exceptionOrNull() is CancellationException || result.getOrNull()?.startsWith("ERROR:") == true)
        }
        assertFalse("Final stale admission must not publish", f.root.resolve("final-$revoke.tar").exists())
        assertEquals(metadata, f.metadata()); assertArrayEquals(f.payload, f.retained.resolve("data.bin").readBytes())
    }


    @Test fun modelToolProjectionRequiresBuildAvailableNativeContextReadEditAndProfile() = fixture { f ->
        fun names(mode: AgentMode = AgentMode.BUILD, available: Boolean = true,
            permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))) =
            AgentTools.definitionsWithCustom(SkillManager(f.root), CustomToolManager(f.root), mode = mode,
                permissions = permissions, localArchiveAvailable = available).map {
                it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
            }.toSet()
        assertTrue(names().containsAll(listOf("local_deleted_folders", "archive_deleted_folder")))
        assertFalse("No native publication owner capability, no archive tool", "archive_deleted_folder" in names(available = false))
        for (mode in listOf(AgentMode.PLAN, AgentMode.REVIEW, AgentMode.EXPLORE)) {
            assertTrue("Inspect remains available in $mode", "local_deleted_folders" in names(mode))
            assertFalse("Archive must be hidden in $mode", "archive_deleted_folder" in names(mode))
        }
        for (action in listOf("read", "edit")) {
            val perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule(action, "*", PermEffect.DENY)))
            assertFalse("$action DENY hides archive", "archive_deleted_folder" in names(permissions = perms))
        }
        val profile = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)), toolRestrictions = listOf(setOf("local_deleted_folders")))
        assertTrue("local_deleted_folders" in names(permissions = profile)); assertFalse("archive_deleted_folder" in names(permissions = profile))
    }

    @Test fun missingCurrentOwnerCannotPublishEvenWhenModelInventsTheToolCall() = fixture { f ->
        val ctx = f.context()
        AgentTools.Ctx::class.java.getDeclaredField("localRecoveryOwner").apply { isAccessible = true }.set(ctx, null)
        val before = workspaceBytes(f.root)
        val result = AgentTools.execute("archive_deleted_folder", f.args(destination = "unowned.tar"), ctx)
        assertTrue(result, result.startsWith("ERROR:") && result.contains("unavailable"))
        assertEquals(before, workspaceBytes(f.root)); assertTrue(f.cache.listFiles().orEmpty().isEmpty())
    }


    @Test fun knownDestinationInspectionIsReadOnlyBeforeAndAfterHistoricalCommit() = fixture { f ->
        val before = workspaceBytes(f.root)
        val none = inspectArchive(f, "inspect.tar")
        assertEquals(JsonPrimitive("NOT_STARTED"), none["status"]); assertEquals(JsonNull, none["sha256"])
        assertEquals(before, workspaceBytes(f.root))
        val published = success(AgentTools.execute("archive_deleted_folder", f.args(destination = "inspect.tar"), f.context()))
        Files.move(f.root.resolve("inspect.tar").toPath(), f.home.resolve("inspect-original.tar").toPath())
        f.root.resolve("inspect.tar").writeText("new user file that inspection must not reverify or replace")
        val committedBefore = workspaceBytes(f.root)
        val committed = inspectArchive(f, "inspect.tar")
        assertEquals(JsonPrimitive("COMMITTED"), committed["status"]); assertEquals(published["sha256"], committed["sha256"])
        assertEquals(published["archive_id"], committed["archive_id"]); assertEquals(published["bytes"], committed["bytes"])
        assertEquals(JsonPrimitive(false), committed["current_file_reverified"])
        assertEquals(JsonPrimitive("UNKNOWN_NOT_REPLAYED"), committed["synchronization_status"])
        assertEquals(committedBefore, workspaceBytes(f.root))
    }

    @Test fun lostNativePublishReturnExposesUnknownReceiptAndNeverRetriesPublication() = fixture { f ->
        var publishes = 0
        val repo = FileRepository(f.root, newFilePublisher = NewProjectFilePublisher { stage, destination ->
            publishes++
            NewProjectFilePublisher.platform.publish(stage, destination)
            throw IOException("injected lost native publication return")
        })
        val output = AgentTools.execute("archive_deleted_folder", f.args(destination = "unknown.tar"), f.context(files = repo))
        assertTrue(output, output.startsWith("ERROR:")); assertEquals(1, publishes)
        assertTrue("Native publication happened before the injected lost return", f.root.resolve("unknown.tar").isFile)
        val before = workspaceBytes(f.root)
        val status = inspectArchive(f, "unknown.tar")
        assertEquals(JsonPrimitive("UNKNOWN"), status["status"])
        assertEquals(JsonNull, status["sha256"]); assertEquals(JsonPrimitive(false), status["current_file_reverified"])
        assertEquals(before, workspaceBytes(f.root))
        val retry = AgentTools.execute("archive_deleted_folder", f.args(destination = "unknown.tar"), f.context())
        assertTrue(retry, retry.startsWith("ERROR:")); assertEquals(before, workspaceBytes(f.root)); assertEquals(1, publishes)
    }

    @Test fun receiptInspectionRequiresItsOwnExactReadPermission() = fixture { f ->
        val destination = "inspect-denied.tar"
        val record = ".droide/local-delete-archives/${f.operation}/*"
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("read", record, PermEffect.DENY)))
        val before = workspaceBytes(f.root)
        val result = AgentTools.execute("local_deleted_folders", f.args("inspect", destination), f.context(AgentMode.PLAN, permissions))
        assertTrue(result, result.startsWith("DENIED")); assertEquals(before, workspaceBytes(f.root))
    }

    private suspend fun inspectArchive(f: Fixture, destination: String): JsonObject = coroutineScope {
        val approvals = ApprovalManager()
        val ctx = f.context(AgentMode.PLAN, approvals = approvals)
        val action = async { AgentTools.execute("local_deleted_folders", f.args("inspect", destination), ctx) }
        val prefix = ".droide/local-delete-archives/${f.operation}/"
        val request = withTimeout(5_000) {
            while (true) {
                val pending = approvals.pending.value
                if (pending?.action == "read" && pending.resource.startsWith(prefix)) return@withTimeout pending
                delay(5)
            }
            error("unreachable")
        }
        val identifier = request.resource.removePrefix(prefix)
        assertEquals(java.util.UUID.fromString(identifier).toString(), identifier)
        assertTrue(approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
        success(action.await()).getValue("archive").jsonObject
    }

    private fun askingEdit() = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("edit", "*", PermEffect.ASK)))
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
