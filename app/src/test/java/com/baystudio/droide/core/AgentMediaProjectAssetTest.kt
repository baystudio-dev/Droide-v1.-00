package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class AgentMediaProjectAssetTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(20)
    private lateinit var sandbox: File
    private lateinit var root: File
    private lateinit var store: ChatMediaStore
    private val bytes = ByteArray(150_001) { (it % 251).toByte() }
    private val owner = "test-session"

    @Before fun setup() {
        sandbox = Files.createTempDirectory("droide-project-asset-").toFile()
        root = File(sandbox, "app/files/projects/example").apply { mkdirs() }
        store = ChatMediaStore(root)
    }
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(sandbox) }

    @Test fun uploadedAssetIsAnIndependentOriginalAndSurvivesRealSessionDeletion() = runBlocking {
        val sessions = SessionManager(root)
        val session = sessions.create()
        val ref = media(session.id)
        sessions.save(session.copy(messages = listOf(AgentMessage("user", "Use as logo", media = listOf(ref)))))
        val source = store.file(ref)!!
        val dependencies = sentinels()
        val progress = mutableListOf<AgentToolProgress>()
        val result = execute(ref, "public/brand/logo.png", context(sessionId = session.id, progress = progress::add,
            files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.native())))
        assertTrue(result, result.startsWith("MEDIA_SAVED_TO_PROJECT "))
        val receipt = Json.parseToJsonElement(result.substringAfter(' ')).jsonObject
        assertEquals("public/brand/logo.png", receipt.getValue("path").jsonPrimitive.content)
        assertEquals("project", receipt.getValue("retention").jsonPrimitive.content)
        assertEquals(bytes.size.toLong(), receipt.getValue("bytes").jsonPrimitive.long)
        val target = root.resolve("public/brand/logo.png")
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(Files.isSameFile(source.toPath(), target.toPath()))
        assertEquals("public/brand/logo.png", progress.last().fileChanges!!.single().path)
        sessions.delete(session.id)
        assertFalse(source.exists())
        assertArrayEquals(bytes, target.readBytes())
        dependencies.forEach { (file, expected) -> assertEquals(expected, file.readText()) }
        assertEquals(listOf("logo.png"), target.parentFile.listFiles()!!.map { it.name })
    }

    @Test fun savedProjectAssetSurvivesSevenDayExpiryAndUserEditsNeverModifyChatCopy() = runBlocking {
        val ref = media()
        val source = store.file(ref)!!
        assertSaved(execute(ref))
        root.resolve("assets/logo.png").writeText("user edited permanent asset")
        assertArrayEquals(bytes, source.readBytes())
        assertEquals(1, store.cleanupExpired(ref.expiresAt))
        assertFalse(source.exists())
        assertEquals("user edited permanent asset", root.resolve("assets/logo.png").readText())
    }

    @Test fun videoAndGenericFilesAreCopiedByteForByteWithoutDecodingOrChangingRetention() = runBlocking {
        for ((name, type) in listOf("clip.mp4" to "video/mp4", "archive.zip" to "application/zip")) {
            val ref = store.import(owner, name, type, bytes.inputStream())
            assertSaved(execute(ref, "assets/$name"))
            assertArrayEquals(bytes, root.resolve("assets/$name").readBytes())
            assertEquals(ref.expiresAt, store.ref(ref.id)!!.expiresAt)
        }
        assertEquals(2, store.ownerRefs(owner).size)
    }

    @Test fun assetIsVisibleToOrdinaryRepositoryAndExistingCliMirrorRefresh() = runBlocking {
        val ref = media()
        val mirror = LocalAgentWorkspaceMirror(root, File(sandbox, "cli-mirror"))
        mirror.refresh()
        assertSaved(execute(ref))
        assertTrue(FileRepository(root).listFiles("assets").any { it.path == "assets/logo.png" })
        mirror.refresh()
        assertArrayEquals(bytes, File(mirror.directory, "assets/logo.png").readBytes())
        assertFalse(File(mirror.directory, ".droide/chat-media").exists())
    }

    @Test fun toolVisibilityRequiresBuildMediaAndReadEditPermissions() {
        fun names(mode: AgentMode, media: Boolean, perms: PermissionEngine? = null) =
            AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root), mode = mode,
                permissions = perms, mediaAvailable = media).map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        for (mode in AgentMode.entries) for (media in listOf(true, false))
            assertEquals(mode == AgentMode.BUILD && media, "save_media_to_project" in names(mode, media))
        for (action in listOf("read", "edit"))
            assertFalse("save_media_to_project" in names(AgentMode.BUILD, true, denied(action)))
        assertEquals("edit", AgentTools.actionOf("save_media_to_project"))
    }

    @Test fun planReviewExploreAndProfileRestrictionsBlockInjectedCalls() = runBlocking {
        val ref = media()
        for (mode in AgentMode.entries.filter { it != AgentMode.BUILD })
            assertTrue(execute(ref, ctx = context(mode = mode)).startsWith("DENIED"))
        assertTrue(execute(ref, ctx = context(permissions = assetPermissions().fork(emptyList(), setOf("read_file")))).startsWith("DENIED"))
        assertFalse(root.resolve("assets").exists())
    }

    @Test fun readAndDestinationEditDenialsCreateNoProjectFilesOrFolders() = runBlocking {
        val ref = media()
        for (action in listOf("read", "edit"))
            assertTrue(execute(ref, ctx = context(permissions = denied(action))).startsWith("DENIED"))
        assertFalse(root.resolve("assets").exists())
        assertArrayEquals(bytes, store.file(ref)!!.readBytes())
    }

    @Test(timeout = 10_000) fun foreignAttachmentIsRejectedBeforeAnApprovalCanBeDisplayed() = runBlocking {
        val ref = media("another-session")
        val ctx = context(permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ASK))))
        assertTrue(execute(ref, ctx = ctx).startsWith("ERROR"))
        assertNull(ctx.approvals.pending.value)
        assertFalse(root.resolve("assets").exists())
    }

    @Test(timeout = 10_000) fun deletingSessionWhileEditApprovalWaitsPreventsTheCopy() = runBlocking {
        val ref = media()
        val ctx = context(permissions = assetPermissions(listOf(
            PermRule("edit", "*", PermEffect.ASK), PermRule("*", "*", PermEffect.ALLOW))))
        val task = async(Dispatchers.Default) { execute(ref, ctx = ctx) }
        val approval = ctx.approvals.pending.filterNotNull().first()
        assertEquals("edit", approval.action)
        assertEquals("assets/logo.png", approval.resource)
        store.releaseDeletedOwner(owner)
        ctx.approvals.resolve(true)
        assertTrue(task.await().startsWith("ERROR"))
        assertFalse(root.resolve("assets").exists())
    }

    @Test fun sourceReadApprovalMustCompleteBeforeAnyDestinationMutation() = runBlocking {
        val ref = media()
        // Without a project/session grant, the existing .droide sensitive-path rule still asks.
        val ctx = context(permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))))
        val task = async(Dispatchers.Default) { execute(ref, ctx = ctx) }
        val approval = ctx.approvals.pending.filterNotNull().first()
        assertEquals("read", approval.action)
        assertEquals(ref.path, approval.resource)
        assertFalse(root.resolve("assets").exists())
        ctx.approvals.resolve(false)
        assertTrue(task.await().startsWith("DENIED"))
        assertFalse(root.resolve("assets").exists())
    }

    @Test fun noSessionExpiredUnregisteredAndPreviewSourcesCannotBecomeAssets() = runBlocking {
        val ref = media()
        assertTrue(execute(ref, ctx = context(sessionId = null)).startsWith("ERROR"))
        val preview = store.importDerivative(ref, "preview.jpg", byteArrayOf(1, 2, 3).inputStream())
        val candidates = listOf(store.aliasForFile(preview), "https://example.com/logo.png", "assets/logo.png", store.file(ref)!!.path)
        for (path in candidates) assertTrue(AgentTools.execute("save_media_to_project", args(path), context()).startsWith("ERROR"))
        store.cleanupExpired(ref.expiresAt)
        assertTrue(execute(ref).startsWith("ERROR"))
        assertFalse(root.resolve("assets").exists())
    }

    @Test fun destinationTraversalMetadataControlsAbsolutePathsAndAliasesAreRejected() = runBlocking {
        val ref = media()
        val outside = File(sandbox, "outside").apply { mkdir() }
        root.resolve("real").mkdir()
        Files.createSymbolicLink(root.resolve("alias").toPath(), root.resolve("real").toPath())
        Files.createSymbolicLink(root.resolve("escape").toPath(), outside.toPath())
        for (path in listOf("../outside/logo.png", outside.resolve("logo.png").path, ".droide/logo.png", ".git/config",
            "assets/../logo.png", "assets//logo.png", "assets/", "assets/./logo.png", "assets\\logo.png", "assets/\nlogo.png",
            "alias/logo.png", "escape/logo.png", "")) {
            assertTrue(path, execute(ref, path).startsWith("ERROR"))
        }
        assertTrue(outside.listFiles()!!.isEmpty())
        assertTrue(root.resolve("real").listFiles()!!.isEmpty())
    }

    @Test fun invalidArgumentTypesAreRejectedWithoutCreatingAssetFolders() = runBlocking {
        val ref = media()
        for (args in listOf(buildJsonObject { put("path", ref.path) }, buildJsonObject { put("path", ref.path); put("destination", 42) },
            buildJsonObject { put("path", 42); put("destination", "logo.png") }))
            assertTrue(AgentTools.execute("save_media_to_project", args, context()).startsWith("ERROR"))
        assertFalse(root.resolve("logo.png").exists())
    }

    @Test fun existingFileDirectoryAndBrokenSymlinkAreNeverOverwritten() = runBlocking {
        val ref = media()
        root.resolve("old.png").writeText("existing")
        root.resolve("folder.png").mkdir()
        Files.createSymbolicLink(root.resolve("broken.png").toPath(), File(sandbox, "missing").toPath())
        for (path in listOf("old.png", "folder.png", "broken.png")) assertTrue(execute(ref, path).startsWith("ERROR"))
        assertEquals("existing", root.resolve("old.png").readText())
        assertTrue(root.resolve("folder.png").isDirectory)
        assertTrue(Files.isSymbolicLink(root.resolve("broken.png").toPath()))
    }

    @Test fun sourceSymlinkAndCorruptedIndexFailWithoutTouchingProjectData() = runBlocking {
        val ref = media()
        val original = store.file(ref)!!
        val outside = File(sandbox, "original.png").apply { writeBytes(bytes) }
        original.delete()
        Files.createSymbolicLink(original.toPath(), outside.toPath())
        assertTrue(execute(ref).startsWith("ERROR"))
        original.delete()
        original.writeBytes(bytes)
        File(store.storageDirectory, "index.json").writeText("corrupt manifest")
        assertTrue(execute(ref).startsWith("ERROR"))
        assertFalse(root.resolve("assets").exists())
        assertArrayEquals(bytes, outside.readBytes())
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test fun unsavedEditorDocumentAtNewDestinationIsPreserved() = runBlocking {
        val ref = media()
        val document = WorkspaceDocumentSnapshot("logo.svg", "unsaved", true, WorkspaceDocumentKind.TEXT,
            true, true, 1, 1, 0, 0, 0)
        val authority = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String) = document.takeIf { it.path == path }
            override suspend fun snapshots() = listOf(document)
        }
        assertTrue(execute(ref, "logo.svg", context(documents = authority)).startsWith("ERROR"))
        assertFalse(root.resolve("logo.svg").exists())
        assertEquals("unsaved", authority.snapshot("logo.svg")!!.content)
    }

    @Test fun repositorySyncFailureKeepsCommittedOriginalAndReportsSavedWithoutRetryingTheCopy() = runBlocking {
        val ref = media()
        val dependencies = sentinels()
        val events = mutableListOf<FileRepository.Mutation>()
        val progress = mutableListOf<AgentToolProgress>()
        val files = FileRepository(root, onMutation = { mutation ->
            events += mutation
            if (mutation is FileRepository.Mutation.Write) error("sync failed")
        })
        val result = execute(ref, ctx = context(files = files, progress = progress::add))
        assertSaved(result)
        val receipt = Json.parseToJsonElement(result.substringAfter(' ')).jsonObject
        assertEquals("assets/logo.png", receipt.getValue("path").jsonPrimitive.content)
        assertEquals(bytes.size.toLong(), receipt.getValue("bytes").jsonPrimitive.long)
        assertTrue(receipt.getValue("sync_pending").jsonPrimitive.boolean)
        assertTrue(receipt.getValue("note").jsonPrimitive.content.contains("do not retry"))
        assertArrayEquals(bytes, root.resolve("assets/logo.png").readBytes())
        val retained = root.resolve("assets").listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertArrayEquals(bytes, retained.readBytes())
        assertEquals(setOf("logo.png", retained.name), root.resolve("assets").listFiles()!!.map { it.name }.toSet())
        assertEquals(1, events.count { it == FileRepository.Mutation.Write("assets/logo.png") })
        assertTrue(progress.last().detail.contains("sync pending"))
        assertArrayEquals(bytes, store.file(ref)!!.readBytes())
        store.releaseDeletedOwner(owner)
        assertArrayEquals(bytes, root.resolve("assets/logo.png").readBytes())
        dependencies.forEach { (file, expected) -> assertEquals(expected, file.readText()) }
    }

    @Test fun unrelatedCommittedSyncExceptionBeforeThisAssetPublishesDoesNotClaimTheAssetWasSaved() = runBlocking {
        val ref = media()
        val files = FileRepository(root, beforeMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write)
                throw ProjectFileSyncException("another-file.zip", bytes.size.toLong(), java.io.IOException("unrelated sync"))
        })
        assertTrue(execute(ref, ctx = context(files = files)).startsWith("ERROR"))
        assertFalse(root.resolve("assets/logo.png").exists())
        assertArrayEquals(bytes, store.file(ref)!!.readBytes())
    }

    @Test fun failedFolderSyncPreservesDependenciesCreatedConcurrentlyByTerminal() = runBlocking {
        val ref = media()
        val dependency = root.resolve("assets/node_modules/package/native.node")
        val files = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.CreateDirectory) {
                dependency.parentFile.mkdirs()
                dependency.writeText("installed concurrently")
                error("folder sync failed")
            }
        })
        assertTrue(execute(ref, ctx = context(files = files)).startsWith("ERROR"))
        assertEquals("installed concurrently", dependency.readText())
        assertFalse(root.resolve("assets/logo.png").exists())
        store.releaseDeletedOwner(owner)
        assertEquals("installed concurrently", dependency.readText())
    }

    @Test fun failedFolderSyncPreservesConcurrentFileReplacementAtTheDirectoryPath() = runBlocking {
        val ref = media()
        val files = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.CreateDirectory) {
                val path = root.resolve(mutation.path)
                check(path.delete())
                path.writeText("terminal replaced folder with a file")
                error("folder sync failed")
            }
        })
        assertTrue(execute(ref, ctx = context(files = files)).startsWith("ERROR"))
        assertEquals("terminal replaced folder with a file", root.resolve("assets").readText())
        assertArrayEquals(bytes, store.file(ref)!!.readBytes())
    }

    @Test fun observerFailureAfterCommitStillReturnsSuccessfulReceipt() = runBlocking {
        val ref = media()
        assertSaved(execute(ref, ctx = context(progress = { error("closed UI") })))
        assertArrayEquals(bytes, root.resolve("assets/logo.png").readBytes())
    }

    @Test fun terminalFileCreatedAtCommitWinsWithoutTruncation() = runBlocking {
        val ref = media()
        val result = execute(ref, ctx = context(progress = { update ->
            if (update.detail == "Finishing project asset") root.resolve("assets/logo.png").writeText("from terminal")
        }))
        assertTrue(result.startsWith("ERROR"))
        assertEquals("from terminal", root.resolve("assets/logo.png").readText())
        val retained = root.resolve("assets").listFiles()!!.filter { it.name.startsWith(".droide-tmp-") }.single()
        assertArrayEquals(bytes, retained.readBytes())
        assertEquals(setOf("logo.png", retained.name), root.resolve("assets").listFiles()!!.map { it.name }.toSet())
        assertEquals(ProjectCopyJournal.Status.UNKNOWN,
            ProjectCopyJournal(root).recovery(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, owner)).records.single().status)
    }

    @Test fun directorySwitchedToSymlinkBeforeWriteIsRejected() = runBlocking {
        val ref = media()
        val outside = File(sandbox, "outside").apply { mkdir() }
        val files = FileRepository(root, beforeMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                root.resolve("assets").delete()
                Files.createSymbolicLink(root.resolve("assets").toPath(), outside.toPath())
            }
        })
        assertTrue(execute(ref, ctx = context(files = files)).startsWith("ERROR"))
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun concurrentSessionCleanupAfterOpenDoesNotBreakOrRegisterProjectCopy() = runBlocking {
        val ref = media()
        val result = execute(ref, ctx = context(progress = {
            if (it.detail == "Preparing project asset") store.releaseDeletedOwner(owner)
        }))
        assertSaved(result)
        assertTrue(store.ownerRefs(owner).isEmpty())
        assertNull(store.file(ref))
        assertArrayEquals(bytes, root.resolve("assets/logo.png").readBytes())
        store.cleanupExpired(ref.expiresAt)
        assertArrayEquals(bytes, root.resolve("assets/logo.png").readBytes())
    }

    @Test fun cancellationWhileCopyingLeavesOriginalAndRetainsUnpublishedWork() = runBlocking {
        val ref = media()
        val files = FileRepository(root)
        val cancelled = CancellationException("stop")
        var actual: CancellationException? = null
        try {
            execute(ref, ctx = context(files = files, progress = {
                if (it.detail.startsWith("Saving asset")) throw cancelled
            }))
        } catch (failure: CancellationException) { actual = failure }
        assertSame(cancelled, actual)
        assertFalse(root.resolve("assets/logo.png").exists())
        val retained = root.resolve("assets").listFiles()!!.single()
        assertTrue(RetainedWorkPolicy.isCandidateName(retained.name))
        assertArrayEquals(byteArrayOf(), retained.readBytes())
        val notice = files.retainedWork.value.notices.single()
        assertEquals("assets/logo.png", notice.target)
        assertEquals("assets/${retained.name}", notice.candidate.path)
        assertEquals(RetainedWorkKind.PRESENT, notice.candidate.kind)
        assertEquals(0L, notice.candidate.bytes)
        assertFalse(notice.historicalWriteReturned)
        assertArrayEquals(bytes, store.file(ref)!!.readBytes())
    }

    @Test fun originalTruncatedOrGrownAfterOpenNeverPublishesPartialAsset() = runBlocking {
        val files = FileRepository(root)
        val retainedPaths = mutableSetOf<String>()
        for (delta in listOf(-1, 1)) {
            val ref = media()
            val original = store.file(ref)!!
            val destination = "asset-$delta.png"
            val result = execute(ref, destination, context(files = files, progress = {
                if (it.detail == "Preparing project asset") RandomAccessFile(original, "rw").use { file -> file.setLength(bytes.size.toLong() + delta) }
            }))
            assertTrue(result, result.startsWith("ERROR"))
            assertFalse(root.resolve(destination).exists())
            val notice = files.retainedWork.value.notices.single { it.target == destination }
            val retained = root.resolve(notice.candidate.path)
            assertTrue(RetainedWorkPolicy.isCandidateName(retained.name))
            val expected = if (delta < 0) bytes.copyOf(bytes.size - 1) else byteArrayOf()
            assertArrayEquals(expected, retained.readBytes())
            assertEquals(RetainedWorkKind.PRESENT, notice.candidate.kind)
            assertEquals(expected.size.toLong(), notice.candidate.bytes)
            assertFalse(notice.historicalWriteReturned)
            assertTrue(original.exists())
            assertEquals(bytes.size.toLong() + delta, original.length())
            assertArrayEquals(bytes.copyOf(bytes.size - 1), original.readBytes().copyOf(bytes.size - 1))
            assertTrue(retainedPaths.add(notice.candidate.path))
        }
        assertEquals(retainedPaths, root.listFiles()!!.filter { RetainedWorkPolicy.isCandidateName(it.name) }.map { it.name }.toSet())
    }

    @Test fun foreignReplacementDuringFailedSyncIsPreserved() = runBlocking {
        val ref = media()
        val files = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                val replacement = root.resolve("replacement.tmp").apply { writeText("new user data") }
                Files.move(replacement.toPath(), root.resolve(mutation.path).toPath(), StandardCopyOption.REPLACE_EXISTING)
                error("sync failed")
            }
        })
        assertTrue(execute(ref, ctx = context(files = files)).startsWith("ERROR"))
        assertEquals("new user data", root.resolve("assets/logo.png").readText())
    }

    @Test fun repeatedCallKeepsPreviouslySavedAssetAndSharedForkCanSaveItsOwnCopy() = runBlocking {
        val ref = media()
        store.retain("fork-session", listOf(ref))
        assertSaved(execute(ref))
        assertTrue(execute(ref).startsWith("ERROR"))
        store.releaseDeletedOwner(owner)
        assertSaved(execute(ref, "fork/logo.png", context(sessionId = "fork-session")))
        store.releaseDeletedOwner("fork-session")
        assertArrayEquals(bytes, root.resolve("assets/logo.png").readBytes())
        assertArrayEquals(bytes, root.resolve("fork/logo.png").readBytes())
    }

    @Test fun defaultUnadmittedContextAndStaleRunCannotSaveMedia() = runBlocking {
        val ref = media()
        val unadmitted = agentWorkflowContext(root, permissions = assetPermissions())
        assertNull(unadmitted.mediaRunId)
        assertFalse(unadmitted.mediaRequestCurrent())
        assertTrue(execute(ref, ctx = unadmitted).startsWith("ERROR"))
        assertTrue(execute(ref, ctx = context(current = { false })).startsWith("ERROR"))
        assertFalse(root.resolve("assets").exists())
    }

    @Test fun samePathAndBytePrecommitExceptionCannotForgeLocalSuccess() = runBlocking {
        val ref = media()
        val files = FileRepository(root, beforeMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write)
                throw ProjectFileSyncException("assets/logo.png", bytes.size.toLong(), java.io.IOException("precommit"))
        })
        val result = execute(ref, ctx = context(files = files))
        assertTrue(result, result.startsWith("ERROR: MEDIA_COPY_FAILED "))
        assertFalse(result.contains("\"local_commit_completed\":true"))
        assertFalse(root.resolve("assets/logo.png").exists())
    }

    @Test fun foreignHistoricalExceptionBeforePublicationCannotForgeLocalSuccess() = runBlocking {
        val ref = media()
        val foreign = foreignHistoricalSignal()
        val files = FileRepository(root, beforeMutation = { throw foreign })
        val result = execute(ref, "asset.png", context(files = files))
        assertTrue(result, result.startsWith("ERROR: MEDIA_COPY_FAILED "))
        assertFalse(result.contains("\"local_commit_completed\":true"))
        assertFalse(result.contains(foreign.record.intent.id))
        assertFalse(root.resolve("asset.png").exists())
    }

    @Test fun foreignHistoricalExceptionInDocumentGuardCannotForgeLocalSuccess() = runBlocking {
        val ref = media()
        val foreign = foreignHistoricalSignal()
        val authority = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = throw foreign
            override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
        }
        val result = execute(ref, "asset.png", context(documents = authority))
        assertTrue(result, result.startsWith("ERROR: MEDIA_COPY_FAILED "))
        assertFalse(result.contains("\"local_commit_completed\":true"))
        assertFalse(root.resolve("asset.png").exists())
        assertTrue(ProjectCopyJournal(root).recovery(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, owner)).records.isEmpty())
    }

    @Test fun foreignHistoricalExceptionAfterCommitCannotReplaceActualLocalReceipt() = runBlocking {
        val ref = media()
        val foreign = foreignHistoricalSignal()
        val files = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                val replacement = root.resolve("replacement.tmp").apply { writeText("later user bytes") }
                Files.move(replacement.toPath(), root.resolve(mutation.path).toPath(), StandardCopyOption.REPLACE_EXISTING)
                throw foreign // Repository cannot wrap a changed target as an unchanged sync failure.
            }
        })
        val result = execute(ref, "asset.png", context(files = files))
        assertTrue(result, result.startsWith("ERROR: MEDIA_SAVED_LOCALLY_SYNC_FAILED "))
        val receipt = Json.parseToJsonElement(result.substringAfter("ERROR: MEDIA_SAVED_LOCALLY_SYNC_FAILED ")).jsonObject
        val actual = ProjectCopyJournal(root).recovery(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, owner)).records.single()
        assertEquals(ProjectCopyJournal.Status.COMMITTED, actual.status)
        assertEquals(actual.intent.id, receipt.getValue("transaction_id").jsonPrimitive.content)
        assertEquals(actual.receipt!!.preparation.publicationId, receipt.getValue("publication_id").jsonPrimitive.content)
        assertEquals("asset.png", receipt.getValue("path").jsonPrimitive.content)
        assertEquals(bytes.size.toLong(), receipt.getValue("bytes").jsonPrimitive.long)
        assertTrue(receipt.getValue("local_commit_completed").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("historical_receipt").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("current_file_reverified").jsonPrimitive.boolean)
        assertFalse(result.contains(foreign.record.intent.id))
        assertEquals("later user bytes", root.resolve("asset.png").readText())
    }

    @Test fun currentRunFenceIsRecheckedAfterLongCopyBeforePublication() = runBlocking {
        val ref = media()
        var current = true
        val result = execute(ref, ctx = context(current = { current }, progress = {
            if (it.detail.startsWith("Saving asset")) current = false
        }))
        assertTrue(result, result.startsWith("ERROR"))
        assertFalse(root.resolve("assets/logo.png").exists())
        val records = ProjectCopyJournal(root).recovery(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, owner)).records
        assertEquals(ProjectCopyJournal.Status.PREPARED, records.single().status)
    }

    @Test fun dirtyEditorAppearingDuringCopyIsPreservedByLateReadOnlyGuard() = runBlocking {
        val ref = media()
        var dirty = false
        val document = WorkspaceDocumentSnapshot("assets/logo.png", "unsaved", true, WorkspaceDocumentKind.TEXT,
            true, true, 1, 1, 0, 0, 0)
        val authority = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String) = document.takeIf { dirty && it.path == path }
            override suspend fun snapshots() = if (dirty) listOf(document) else emptyList()
        }
        val result = execute(ref, ctx = context(documents = authority, progress = {
            if (it.detail.startsWith("Saving asset")) dirty = true
        }))
        assertTrue(result, result.startsWith("ERROR"))
        assertFalse(root.resolve("assets/logo.png").exists())
        assertEquals("unsaved", authority.snapshot("assets/logo.png")!!.content)
    }

    @Test fun completedRunReplayDoesNotRecreateOrReverifyChangedDestination() = runBlocking {
        val ref = media()
        val ctx = context()
        assertSaved(execute(ref, ctx = ctx))
        root.resolve("assets/logo.png").writeText("later user edit")
        val replay = execute(ref, ctx = ctx)
        assertTrue(replay, replay.startsWith("MEDIA_SAVE_RECEIPT_REPLAY "))
        val receipt = Json.parseToJsonElement(replay.substringAfter(' ')).jsonObject
        assertTrue(receipt.getValue("historical_receipt").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("current_file_reverified").jsonPrimitive.boolean)
        assertEquals("later user edit", root.resolve("assets/logo.png").readText())
        root.resolve("assets/logo.png").delete()
        assertTrue(execute(ref, ctx = ctx).startsWith("MEDIA_SAVE_RECEIPT_REPLAY "))
        assertFalse(root.resolve("assets/logo.png").exists())
    }

    @Test fun trustedJournalHistoricalReceiptReplaysWithoutRecreatingCurrentBytes() = runBlocking {
        val ref = media()
        val runId = java.util.UUID.randomUUID().toString()
        assertSaved(execute(ref, "asset.png", context(runId = runId)))
        val actual = ProjectCopyJournal(root).recovery(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, owner)).records.single()
        Files.delete(root.resolve("asset.png").toPath())

        val replay = execute(ref, "asset.png", context(runId = runId))
        assertTrue(replay, replay.startsWith("MEDIA_SAVE_RECEIPT_REPLAY "))
        val receipt = Json.parseToJsonElement(replay.substringAfter(' ')).jsonObject
        assertEquals(actual.intent.id, receipt.getValue("transaction_id").jsonPrimitive.content)
        assertEquals(actual.receipt!!.preparation.publicationId, receipt.getValue("publication_id").jsonPrimitive.content)
        assertTrue(receipt.getValue("historical_receipt").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("current_file_reverified").jsonPrimitive.boolean)
        assertFalse(root.resolve("asset.png").exists())
    }

    @Test fun cancellationAfterExactCommitRetainsJournalAndBlocksAutomaticRecopy() = runBlocking {
        val ref = media()
        val files = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) throw CancellationException("caller stopped after publication")
        })
        val ctx = context(files = files)
        var cancelled = false
        try { execute(ref, ctx = ctx) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertArrayEquals(bytes, root.resolve("assets/logo.png").readBytes())
        val record = ProjectCopyJournal(root).recovery(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, owner)).records.single()
        assertEquals(ProjectCopyJournal.Status.COMMITTED, record.status)
        assertEquals(bytes.size.toLong(), record.receipt!!.preparation.bytes)
        assertTrue(execute(ref, "assets/duplicate.png", ctx).startsWith("ERROR: MEDIA_COPY_ALREADY_ATTEMPTED "))
        assertFalse(root.resolve("assets/duplicate.png").exists())
    }

    @Test fun uncertainPriorRunBlocksNewUuidAndAcknowledgementCannotFreshenUnknownAdmission() = runBlocking {
        val ref = media()
        val journal = ProjectCopyJournal(root)
        val lease = journal.begin(ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, owner, "old-run", ref.id), "old.png")
        lease.beforePublish(StreamPublicationPreparation(java.util.UUID.randomUUID().toString(), root.canonicalPath,
            ProjectCopyEvidence.root(root).second, "old.png", bytes.size.toLong(), ProjectCopyEvidence.sha256(bytes), "old-stage"))
        lease.close()
        val scope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, owner)
        val first = execute(ref, "new.png")
        assertTrue(first, first.startsWith("ERROR: MEDIA_COPY_OUTCOME_UNKNOWN "))
        journal.acknowledge(scope, journal.inspect(scope, lease.intent.id).token)

        val second = execute(ref, "new.png")
        assertTrue(second, second.startsWith("ERROR: MEDIA_COPY_OUTCOME_UNKNOWN "))
        assertFalse(root.resolve("new.png").exists())
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal.recovery(scope).records.single().status)
        val other = media()
        assertSaved(execute(other, "safe.png"))
    }

    @Test fun finalSavedProgressCancellationStillPropagatesAfterReceiptWasRecorded() = runBlocking {
        val ref = media()
        val ctx = context(progress = { if (it.detail.startsWith("Saved ")) throw CancellationException("display cancelled") })
        var cancelled = false
        try { execute(ref, ctx = ctx) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertArrayEquals(bytes, root.resolve("assets/logo.png").readBytes())
        val record = ProjectCopyJournal(root).recovery(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, owner)).records.single()
        assertEquals(ProjectCopyJournal.Status.COMMITTED, record.status)
        assertTrue(execute(ref, ctx = ctx).startsWith("MEDIA_SAVE_RECEIPT_REPLAY "))
    }

    @Test fun cancellationBeforeRepositoryDispatcherOwnershipClosesOriginalDescriptor() = runBlocking {
        val descriptors = File("/proc/self/fd")
        org.junit.Assume.assumeTrue("Linux descriptor inspection is required for this ownership regression", descriptors.isDirectory)
        val ref = media()
        val originalPath = store.file(ref)!!.canonicalPath
        fun openOriginals() = descriptors.listFiles().orEmpty().count { fd ->
            runCatching { Files.readSymbolicLink(fd.toPath()).toString() == originalPath }.getOrDefault(false)
        }
        val before = openOriginals()
        val copying = async {
            val caller = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!
            ChatMediaProjectAssets.save(store, owner, ref, FileRepository(root), "root-level.png",
                onIntegrityReady = { caller.cancel(CancellationException("cancel before dispatcher handoff")) })
        }
        var cancelled = false
        try { copying.await() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertFalse(root.resolve("root-level.png").exists())
        assertEquals(before, openOriginals())
    }

    @Test fun sharedInputCloseOwnerClosesUnderlyingSourceOnlyOnceEvenWhenCloseThrows() {
        var closes = 0
        val input = ChatMediaProjectAssets.CloseOnceInput(object : java.io.ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closes++; throw java.io.IOException("source close failure") }
        })
        try { input.close(); fail("Expected first close failure") } catch (_: java.io.IOException) { }
        input.close()
        assertEquals(1, closes)
    }

    @Test fun primaryHandoffCancellationIsNotReplacedByCloseFailure() {
        var closes = 0
        val input = ChatMediaProjectAssets.CloseOnceInput(object : java.io.ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closes++; throw java.io.IOException("close failure") }
        })
        val cancelled = CancellationException("primary cancellation")
        try { input.use { throw cancelled } } catch (failure: Exception) {
            assertSame(cancelled, failure)
            assertEquals(1, failure.suppressed.size)
            assertTrue(failure.suppressed.single() is java.io.IOException)
        }
        input.close()
        assertEquals(1, closes)
    }

    private fun foreignHistoricalSignal(): ProjectCopyJournal.AlreadyCommitted {
        val intent = ProjectCopyJournal.Intent(id = java.util.UUID.randomUUID().toString(), canonicalRoot = root.canonicalPath,
            rootIdentity = ProjectCopyEvidence.root(root).second,
            owner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, "foreign-owner", "foreign-run", "foreign-media"),
            destination = "foreign.bin", createdAt = 1)
        val preparation = StreamPublicationPreparation(java.util.UUID.randomUUID().toString(), root.canonicalPath,
            intent.rootIdentity, intent.destination, 999, ProjectCopyEvidence.sha256(byteArrayOf(9)), "foreign-stage")
        return ProjectCopyJournal.AlreadyCommitted(ProjectCopyJournal.Record(intent, ProjectCopyJournal.Status.COMMITTED,
            StreamPublicationReceipt(preparation, "foreign-destination"), null, "foreign-evidence", false))
    }

    private suspend fun media(session: String = owner) = store.import(session, "logo.png", "image/png", bytes.inputStream())
    private fun args(path: String, destination: String = "assets/logo.png") = buildJsonObject { put("path", path); put("destination", destination) }
    private suspend fun execute(ref: ChatMediaRef, destination: String = "assets/logo.png", ctx: AgentTools.Ctx = context()) =
        AgentTools.execute("save_media_to_project", args(ref.path, destination), ctx)
    private fun assertSaved(result: String) = assertTrue(result, result.startsWith("MEDIA_SAVED_TO_PROJECT "))
    private fun denied(action: String) = assetPermissions(listOf(PermRule(action, "*", PermEffect.DENY), PermRule("*", "*", PermEffect.ALLOW)))
    private fun assetPermissions(base: List<PermRule> = listOf(PermRule("*", "*", PermEffect.ALLOW))) =
        PermissionEngine(base, PermissionPolicyDocument(rules = listOf(PermRule("read", ".droide/chat-media/blobs/*", PermEffect.ALLOW))))
    private fun context(
        sessionId: String? = owner,
        mode: AgentMode = AgentMode.BUILD,
        permissions: PermissionEngine = assetPermissions(),
        files: FileRepository = FileRepository(root),
        documents: WorkspaceDocumentAuthority? = null,
        progress: (AgentToolProgress) -> Unit = {},
        runId: String? = java.util.UUID.randomUUID().toString(),
        current: () -> Boolean = { true },
    ): AgentTools.Ctx {
        val base = agentWorkflowContext(root, permissions = permissions, mode = mode)
        return AgentTools.Ctx(files, base.terminal, base.git, base.approvals, base.questions, base.history,
            permissions, mode, base.llm, base.llmConfig, root, base.todos, sessionId, base.lsp,
            documentAuthority = documents, mediaRunId = runId, mediaRequestCurrent = current, onProgress = progress)
    }
    private fun sentinels(): Map<File, String> = listOf("src/main.kt", "node_modules/native/addon.node", ".venv/bin/python",
        ".droide/runtime/receipt", "build/output.bin").associate { relative ->
        val file = root.resolve(relative).apply { parentFile.mkdirs(); writeText("preserve $relative") }
        file to file.readText()
    }
}
