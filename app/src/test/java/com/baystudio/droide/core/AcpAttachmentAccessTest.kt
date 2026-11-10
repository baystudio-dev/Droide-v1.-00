package com.baystudio.droide.core

import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class AcpAttachmentAccessTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(20)
    private val sandbox = Files.createTempDirectory("droide-cli-attachment-").toFile()
    private val root = File(sandbox, "files/projects/example").apply { mkdirs() }
    private val store = ChatMediaStore(root)
    private val files = FileRepository(root)
    private val owner = "conversation"
    private val workspace = "workspace-example"
    private val original = ByteArray(150_001) { (it % 251).toByte() }
    private val leases = mutableListOf<AcpAttachmentAccess.Lease>()
    private val authority = Proxy.newProxyInstance(UnifiedPackageAuthority::class.java.classLoader,
        arrayOf(UnifiedPackageAuthority::class.java)) { _, method, _ -> error("Unexpected package operation: ${method.name}") } as UnifiedPackageAuthority
    private val cli get() = DroideCliTool(authority, workspace)

    @After fun cleanup() {
        leases.forEach { it.close() }
        assertTrue(PathSecurity.deleteTreeNoFollow(sandbox))
    }

    private suspend fun ref(name: String = "archive.zip", mime: String = "application/zip", data: ByteArray = original,
                            session: String = owner) = store.import(session, name, mime, data.inputStream())

    private suspend fun lease(mode: WorkspaceAgentMode = WorkspaceAgentMode.EDIT, turnJob: Job? = null,
                              permission: suspend (String, String) -> Boolean = { _, _ -> true },
                              saved: suspend (String) -> Unit = {}, repository: FileRepository = files,
                              destinationAvailable: suspend (String) -> Unit = {}): AcpAttachmentAccess.Lease =
        requireNotNull(AcpAttachmentAccess.bind(workspace, store, owner, repository, mode, turnJob, permission, saved, destinationAvailable))
            .also(leases::add)

    private fun saveArgs(lease: AcpAttachmentAccess.Lease, ref: ChatMediaRef, destination: String = "imports/${ref.name}") =
        listOf("attachment", "save", lease.token, ref.id, destination)

    @Test fun nativeCliEntryCopiesZipPdfOfficeAndSourceAsExactOriginalsWithoutImageCapability() = runBlocking {
        for ((name, type) in listOf("project.zip" to "application/zip", "spec.pdf" to "application/pdf",
            "report.docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "Main.kt" to "text/plain", "data.bin" to "application/octet-stream")) {
            val attachment = ref(name, type)
            val access = lease()
            try {
                val result = cli.executeResult(saveArgs(access, attachment))
                assertEquals(result.stderr, 0, result.exitCode)
                assertTrue(result.stdout.startsWith("ATTACHMENT_SAVED_TO_PROJECT "))
                assertArrayEquals(original, root.resolve("imports/$name").readBytes())
                assertArrayEquals(original, store.file(attachment)!!.readBytes())
                assertFalse(Files.isSameFile(root.resolve("imports/$name").toPath(), store.file(attachment)!!.toPath()))
            } finally { access.finish() }
        }
    }

    @Test fun copiedFileSurvivesRealSessionDeletionAndExpiryWithoutTouchingDependencies() = runBlocking {
        val sessions = SessionManager(root)
        val session = sessions.create()
        val attachment = ref(session = session.id)
        sessions.save(session.copy(messages = listOf(AgentMessage("user", "copy archive", media = listOf(attachment)))))
        val dependencies = root.resolve("node_modules/pkg/index.js").apply { parentFile.mkdirs(); writeText("dependency") }
        val rootfs = sandbox.resolve("files/guest/ubuntu/rootfs/keep").apply { parentFile.mkdirs(); writeText("Ubuntu") }
        val access = requireNotNull(AcpAttachmentAccess.bind(workspace, store, session.id, files,
            WorkspaceAgentMode.EDIT, permission = { _, _ -> true }, saved = {})).also(leases::add)
        try {
            assertEquals(0, cli.executeResult(saveArgs(access, attachment)).exitCode)
            sessions.delete(session.id)
            store.cleanupExpired(attachment.expiresAt)
            assertArrayEquals(original, root.resolve("imports/archive.zip").readBytes())
            assertEquals("dependency", dependencies.readText())
            assertEquals("Ubuntu", rootfs.readText())
            assertNull(store.file(attachment))
            assertNotEquals(0, cli.executeResult(saveArgs(access, attachment, "imports/again.zip")).exitCode)
            assertFalse(root.resolve("imports/again.zip").exists())
        } finally { access.finish() }
    }

    @Test fun largeBinaryPromptUsesSmallMetadataAndTheSameCliTransportWithoutEmbeddedContext() = runBlocking {
        val attachment = ref(data = ByteArray(9 * 1024 * 1024) { 7 })
        val access = lease()
        try {
            val caps = AcpPromptCapabilities()
            assertNotNull(caps.admissionError(listOf(attachment)))
            assertNull(caps.admissionError(listOf(attachment), fileBridgeAvailable = true))
            val blocks = AcpChatMedia.prompt("Copy this archive", emptyList(), listOf(attachment to store.file(attachment)!!),
                root, caps, access)
            assertEquals(1, blocks.size)
            val text = blocks.single().jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(text.contains(attachment.name)); assertTrue(text.contains(attachment.id))
            assertTrue(text.contains("droide attachment save")); assertFalse(text.contains(store.file(attachment)!!.absolutePath))
            assertTrue(text.length < 5_000)
            assertEquals(0, cli.executeResult(saveArgs(access, attachment)).exitCode)
            assertEquals(attachment.bytes, root.resolve("imports/archive.zip").length())
        } finally { access.finish() }
    }

    @Test fun savedOriginalIsImmediatelyAvailableInTheActualLocalCliMirrorWithoutOverwritingAMirrorFile() = runBlocking {
        val attachment = ref()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = LocalAgentWorkspaceProcessHost(root, sandbox.resolve("cache"), scope)
        try {
            host.workspaceIo.refresh()
            val mapper = host.pathMapper()
            val conflicting = File(mapper.localRelativeToRemote("imports/existing.zip"))
            val access = lease(destinationAvailable = { relative ->
                host.workspaceIo.requireNewPath(mapper.localRelativeToRemote(relative))
            }, saved = { relative ->

                if (relative == "imports/existing.zip") {
                    conflicting.parentFile.mkdirs(); conflicting.writeText("CLI work in progress")
                }
                host.workspaceIo.pushNew(root.resolve(relative), mapper.localRelativeToRemote(relative))
            })
            try {
                assertEquals(0, cli.executeResult(saveArgs(access, attachment)).exitCode)
                assertArrayEquals(original, File(mapper.localRelativeToRemote("imports/archive.zip")).readBytes())
                val collision = cli.executeResult(saveArgs(access, attachment, "imports/existing.zip"))
                assertEquals(0, collision.exitCode)
                assertTrue(collision.stdout.contains("\"cli_workspace_ready\":false"))
                assertEquals("CLI work in progress", conflicting.readText())
                assertArrayEquals(original, root.resolve("imports/existing.zip").readBytes())
                host.workspaceIo.refresh()
                assertEquals("CLI work in progress", conflicting.readText())
                val reopened = LocalAgentWorkspaceProcessHost(root, sandbox.resolve("cache"), scope)
                reopened.workspaceIo.refresh()
                assertEquals("CLI work in progress", conflicting.readText())
                assertArrayEquals(original, root.resolve("imports/existing.zip").readBytes())
                assertTrue(conflicting.renameTo(File(conflicting.parentFile, "renamed-CLI-work.zip")))

                root.resolve("imports/renamed-CLI-work.zip").writeText("CLI work in progress")
                reopened.workspaceIo.refresh()
                assertArrayEquals(original, conflicting.readBytes())
                assertEquals("CLI work in progress", root.resolve("imports/renamed-CLI-work.zip").readText())
                store.releaseDeletedOwner(owner)
                assertArrayEquals(original, root.resolve("imports/archive.zip").readBytes())
                assertFalse(root.resolve(".droide/chat-media").exists())
            } finally { access.finish() }
        } finally { scope.cancel() }
    }

    @Test fun preflightCollisionBeforeOrDuringApprovalNeverPublishesTheSourceAndSurvivesRefresh() = runBlocking {
        val attachment = ref()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = LocalAgentWorkspaceProcessHost(root, sandbox.resolve("cache"), scope)
        try {
            host.workspaceIo.refresh()
            val mapper = host.pathMapper()
            for (duringApproval in listOf(false, true)) {
                val destination = "imports/conflict-$duringApproval.zip"
                val conflicting = File(mapper.localRelativeToRemote(destination))
                if (!duringApproval) { conflicting.parentFile.mkdirs(); conflicting.writeText("existing CLI work") }
                val access = lease(permission = { action, _ ->
                    if (duringApproval && action == "edit") { conflicting.parentFile.mkdirs(); conflicting.writeText("existing CLI work") }
                    true
                }, destinationAvailable = { relative -> host.workspaceIo.requireNewPath(mapper.localRelativeToRemote(relative)) })
                try {
                    assertNotEquals(0, cli.executeResult(saveArgs(access, attachment, destination)).exitCode)
                    assertFalse(root.resolve(destination).exists())
                    host.workspaceIo.refresh()
                    assertEquals("existing CLI work", conflicting.readText())
                } finally { access.finish() }
            }
        } finally { scope.cancel() }
    }

    @Test fun expiryWithoutCleanupRevokesLeaseWhileLeavingUnregisteredProjectFilesAlone() = runBlocking {
        var now = 1_000L
        val timedStore = ChatMediaStore(root, clock = { now })
        val attachment = timedStore.import(owner, "input.zip", "application/zip", original.inputStream())
        val source = timedStore.file(attachment)!!
        val access = requireNotNull(AcpAttachmentAccess.bind(workspace, timedStore, owner, files,
            WorkspaceAgentMode.EDIT, permission = { _, _ -> true }, saved = {})).also(leases::add)
        try {
            now = attachment.expiresAt
            assertTrue(source.isFile)
            assertNotEquals(0, cli.executeResult(saveArgs(access, attachment)).exitCode)
            assertTrue(source.isFile)
            assertFalse(root.resolve("imports").exists())
        } finally { access.finish() }
    }

    @Test fun textReadPagesTheOriginalBeyondTheUiExcerpt() = runBlocking {
        val attachment = ref("large.txt", "text/plain", ("a".repeat(12_000) + "TAIL_AFTER_PREVIEW").toByteArray())
        val access = lease()
        try {
            val result = cli.executeResult(listOf("attachment", "read", access.token, attachment.id, "12000"))
            assertEquals(result.stderr, 0, result.exitCode)
            assertTrue(result.stdout.contains("TAIL_AFTER_PREVIEW"))
            assertFalse(root.resolve("imports").exists())
        } finally { access.finish() }
    }

    @Test fun noOverwriteAppliesToBothAnExistingFileAndAnUnsavedEditorMutationGuard() = runBlocking {
        val attachment = ref()
        root.resolve("existing.zip").writeText("keep")
        val guarded = FileRepository(root, beforeMutation = { error("Unsaved document is open") })
        val access = lease(repository = guarded)
        try {
            assertNotEquals(0, cli.executeResult(saveArgs(access, attachment, "existing.zip")).exitCode)
            assertEquals("keep", root.resolve("existing.zip").readText())
            assertNotEquals(0, cli.executeResult(saveArgs(access, attachment, "new.zip")).exitCode)
            assertFalse(root.resolve("new.zip").exists())
        } finally { access.finish() }
    }

    @Test fun traversalInternalMetadataSymlinksAndAbsoluteTargetsPreserveFilesOutsideTheDestination() = runBlocking {
        val attachment = ref()
        val outside = sandbox.resolve("outside").apply { mkdir() }
        root.resolve("linked").toPath().let { Files.createSymbolicLink(it, outside.toPath()) }
        val access = lease()
        try {
            for (target in listOf("../escape.zip", ".droide/archive.zip", ".git/archive.zip", outside.resolve("file.zip").path,
                "linked/escape.zip", "imports/../../escape.zip", "imports\\file.zip")) {
                assertNotEquals(target, 0, cli.executeResult(saveArgs(access, attachment, target)).exitCode)
            }
            assertTrue(outside.listFiles()!!.isEmpty())
        } finally { access.finish() }
    }

    @Test fun wrongWorkspaceForeignAttachmentExpiredFileAndRevokedLeaseAreRejectedBeforeApproval() = runBlocking {
        val attachment = ref()
        val foreign = ref("foreign.zip", session = "other-session")
        var approvals = 0
        val access = lease(permission = { _, _ -> approvals++; true })
        val args = saveArgs(access, attachment)
        try {
            assertNotEquals(0, DroideCliTool(authority, "another-workspace").executeResult(args).exitCode)
            assertNotEquals(0, cli.executeResult(saveArgs(access, foreign)).exitCode)
            store.cleanupExpired(attachment.expiresAt)
            assertNotEquals(0, cli.executeResult(args).exitCode)
            access.finish()
            assertNotEquals(0, cli.executeResult(args).exitCode)
            assertEquals(0, approvals)
            assertFalse(root.resolve("imports").exists())
        } finally { access.finish() }
    }

    @Test fun readOnlyAndDeniedReadOrEditNeverCreateAProjectAsset() = runBlocking {
        val attachment = ref()
        for (mode in listOf(WorkspaceAgentMode.PLAN, WorkspaceAgentMode.EDIT)) {
            for (denied in listOf("read", "edit")) {
                val access = lease(mode, permission = { action, _ -> action != denied })
                try { assertNotEquals(0, cli.executeResult(saveArgs(access, attachment)).exitCode) }
                finally { access.finish() }
            }
        }
        assertFalse(root.resolve("imports").exists())
    }

    @Test fun deletionWhileAwaitingDestinationApprovalCannotCopyTheRevokedOriginal() = runBlocking {
        val attachment = ref()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val access = lease(permission = { action, _ ->
            if (action == "edit") { entered.complete(Unit); proceed.await() }
            true
        })
        try {
            val result = async { cli.executeResult(saveArgs(access, attachment)) }
            entered.await()
            store.releaseDeletedOwner(owner)
            proceed.complete(Unit)
            assertNotEquals(0, result.await().exitCode)
            assertFalse(root.resolve("imports").exists())
        } finally { access.finish() }
    }

    @Test fun stopRevokesLeaseCancelsWaitingApprovalAndCannotWriteAfterItResolves() = runBlocking {
        val attachment = ref()
        val entered = CompletableDeferred<Unit>()
        val turn = Job()
        val access = lease(turnJob = turn, permission = { _, _ -> entered.complete(Unit); awaitCancellation() })
        try {
            val request = async { runCatching { cli.executeResult(saveArgs(access, attachment)) } }
            entered.await()
            turn.cancel()
            access.finish()
            assertTrue(request.await().exceptionOrNull() is CancellationException)
            assertNotEquals(0, cli.executeResult(saveArgs(access, attachment)).exitCode)
            assertFalse(root.resolve("imports").exists())
        } finally { access.finish(); turn.cancel() }
    }

    @Test fun mirrorFailureReportsTheCommittedProjectCopyWithoutDeletingOrRewritingIt() = runBlocking {
        val attachment = ref()
        val access = lease(saved = { error("CLI mirror has another file") })
        try {
            val result = cli.executeResult(saveArgs(access, attachment))
            assertEquals(result.stderr, 0, result.exitCode)
            val receipt = Json.parseToJsonElement(result.stdout.substringAfter(' ')).jsonObject
            assertFalse(receipt["cli_workspace_ready"]!!.jsonPrimitive.boolean)
            assertArrayEquals(original, root.resolve("imports/archive.zip").readBytes())
        } finally { access.finish() }
    }

    @Test fun committedSyncFailureStillProjectsTheOriginalToCliAndReportsSavedWithoutCopyRetry() = runBlocking {
        val attachment = ref()
        val dependency = root.resolve("node_modules/pkg/native.node").apply { parentFile.mkdirs(); writeText("keep dependency") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = LocalAgentWorkspaceProcessHost(root, sandbox.resolve("cache"), scope)
        var writes = 0
        var projections = 0
        val syncing = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) { writes++; error("linked-folder sync unavailable") }
        })
        try {
            host.workspaceIo.refresh()
            val mapper = host.pathMapper()
            val access = lease(repository = syncing, saved = { path ->
                projections++
                host.workspaceIo.pushNew(root.resolve(path), mapper.localRelativeToRemote(path))
            })
            try {
                val result = cli.executeResult(saveArgs(access, attachment, "archive.zip"))
                assertEquals(result.stderr, 0, result.exitCode)
                val receipt = Json.parseToJsonElement(result.stdout.substringAfter(' ')).jsonObject
                assertEquals("archive.zip", receipt["path"]!!.jsonPrimitive.content)
                assertEquals(attachment.bytes, receipt["bytes"]!!.jsonPrimitive.long)
                assertTrue(receipt["sync_pending"]!!.jsonPrimitive.boolean)
                assertTrue(receipt["cli_workspace_ready"]!!.jsonPrimitive.boolean)
                assertTrue(receipt["note"]!!.jsonPrimitive.content.contains("do not retry"))
                assertEquals(1, writes); assertEquals(1, projections)
                assertArrayEquals(original, root.resolve("archive.zip").readBytes())
                assertArrayEquals(original, File(mapper.localRelativeToRemote("archive.zip")).readBytes())
                assertArrayEquals(original, store.file(attachment)!!.readBytes())
                store.releaseDeletedOwner(owner)
                assertArrayEquals(original, root.resolve("archive.zip").readBytes())
                assertEquals("keep dependency", dependency.readText())
            } finally { access.finish() }
        } finally { scope.cancel() }
    }

    @Test fun committedSyncFailureAndCliProjectionFailureReportBothStatesAndPreserveCanonicalBytes() = runBlocking {
        val attachment = ref()
        val dependency = root.resolve(".venv/bin/python").apply { parentFile.mkdirs(); writeText("keep runtime") }
        var writes = 0
        var projections = 0
        val syncing = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) { writes++; error("sync failed") }
        })
        val access = lease(repository = syncing, saved = { projections++; error("CLI workspace unavailable") })
        try {
            val result = cli.executeResult(saveArgs(access, attachment, "archive.zip"))
            assertEquals(result.stderr, 0, result.exitCode)
            val receipt = Json.parseToJsonElement(result.stdout.substringAfter(' ')).jsonObject
            assertTrue(receipt["sync_pending"]!!.jsonPrimitive.boolean)
            assertFalse(receipt["cli_workspace_ready"]!!.jsonPrimitive.boolean)
            assertTrue(receipt["note"]!!.jsonPrimitive.content.contains("do not retry"))
            assertTrue(receipt["note"]!!.jsonPrimitive.content.contains("CLI projection failed"))
            assertEquals(1, writes); assertEquals(1, projections)
            assertArrayEquals(original, root.resolve("archive.zip").readBytes())
            assertArrayEquals(original, store.file(attachment)!!.readBytes())
            assertEquals("keep runtime", dependency.readText())
        } finally { access.finish() }
    }

    @Test fun oldTurnLeaseNeverBecomesValidAgainAndMalformedArgumentsDoNotReachAHandler() = runBlocking {
        val attachment = ref()
        val old = lease()
        old.finish()
        val current = lease()
        try {
            assertNotEquals(0, cli.executeResult(saveArgs(old, attachment)).exitCode)
            for (args in listOf(listOf("attachment"), listOf("attachment", "save", current.token, attachment.id),
                listOf("attachment", "save", current.token, attachment.id, "bad\nname"),
                listOf("attachment", "read", current.token, attachment.id, "-1"),
                listOf("attachment", "save", current.token, attachment.id, "x".repeat(513)))) {
                assertNotEquals(args.toString(), 0, cli.executeResult(args).exitCode)
            }
            assertEquals(0, cli.executeResult(saveArgs(current, attachment)).exitCode)
        } finally { current.finish() }
    }

    @Test fun nativeReceiptNamesGenericAttachmentsAndBinaryReadFailureOffersTheActualCopyTool() = runBlocking {
        val attachment = ref("User source.zip")
        val receipt = AgentMediaPromptReceipt.forModel("Copy this", listOf(attachment), emptyList())
        assertTrue(receipt.contains("User source.zip")); assertTrue(receipt.contains("save_media_to_project"))
        val failure = runCatching { AgentMediaTextReader.read(root, attachment.path, null, null, null, owner) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("save_media_to_project"))
        assertTrue(failure.message!!.contains("every file type"))
    }
}
