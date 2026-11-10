package com.baystudio.droide.ui

import org.junit.Before

import com.baystudio.droide.core.ApplicationWorkAdmission

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import coil.size.Dimension
import com.baystudio.droide.core.AgentMessage
import com.baystudio.droide.core.AgentService
import com.baystudio.droide.core.AgentWorkflowTerminal
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.GitManager
import com.baystudio.droide.core.ChatMediaRef
import com.baystudio.droide.core.ChatMediaStore
import com.baystudio.droide.core.PathSecurity
import com.baystudio.droide.core.AgentStreamState
import com.baystudio.droide.DroideApplication
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual menu, metadata-only cards and retained draft ownership; no live provider or decoder claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentChatMediaUiTest {
    @Before
    fun openApplicationWork() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val root = Files.createTempDirectory("agent-chat-media-ui-").toFile()
    private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state by lazy { AgentWorkspaceUiState(parent, app, root) }
    private val importedOwners = mutableSetOf<String>()

    @After fun cleanup() {
        state.close()
        parent.cancel()
        runBlocking(Dispatchers.IO) { importedOwners.forEach { ChatMediaStore(root).releaseOwner(it) } }
        PathSecurity.deleteTreeNoFollow(ChatMediaStore(root).storageDirectory)
        PathSecurity.deleteTreeNoFollow(root)
    }

    private fun import(name: String = "example.txt", mime: String = "text/plain") = runBlocking {
        val owner = state.mediaDraftToken().ownerId
        importedOwners += owner
        ChatMediaStore(root).import(owner, name, mime, ByteArrayInputStream("sample content".toByteArray()))
    }

    private fun mount(width: Int = 360, height: Int = 640, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { DroideTheme { Box(Modifier.width(width.dp).height(height.dp)) { content() } } }
    }

    @Test fun plusShowsCategoriesBeforeLaunchingTheSelectedPicker() {
        var visuals = 0
        var files = 0
        mount { AgentAttachmentMenu(true, { visuals++ }, { files++ }) }
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Images & videos").assertIsDisplayed()
        compose.onNodeWithText("Files").assertIsDisplayed()
        assertEquals(0, visuals)
        assertEquals(0, files)
        compose.onNodeWithText("Files").performClick()
        assertEquals(1, files)
        assertEquals(0, visuals)
        compose.onNodeWithText("Images & videos").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Images & videos").performClick()
        assertEquals(1, visuals)
    }

    @Test @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapeMenuAndDraftRemovalKeepFortyEightDpTargets() {
        state.bindMediaConversation(root, AgentChatConversationKey("droide", "s-landscape"))
        val ref = import()
        state.attachments.value = listOf(AgentAttachedContext(ref.name, "sample content", media = ref))
        mount(720, 360) { Column { AgentAttachmentMenu(true, {}, {})
            AgentDraftMediaRow(state.attachments.value, false, null, root, state::removeMediaAttachment) } }
        val minimum = 48f * app.resources.displayMetrics.density
        for (description in listOf("Add attachment", "Remove example.txt")) {
            val bounds = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= minimum)
            assertTrue(bounds.height >= minimum)
        }
        compose.onNodeWithContentDescription("Remove example.txt").performClick()
        assertTrue(state.attachments.value.isEmpty())
        compose.onNodeWithContentDescription("Remove example.txt").assertDoesNotExist()
    }

    @Test fun expiredImageHasAStablePlaceholderAndDisabledOpenAndExport() {
        val ref = ChatMediaRef("m-old", ".droide/chat-media/blobs/m-old/original.png", "expired.png", "image/png", 42, 0, 1)
        mount { AgentMediaCard(ref, root) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Media removed after 7 days").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open expired.png").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Export expired.png").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close image preview").assertDoesNotExist()
        val bounds = compose.onNodeWithContentDescription("Attachment expired.png").fetchSemanticsNode().boundsInRoot
        assertTrue("Card width is bounded on a phone", bounds.width <= 340f * app.resources.displayMetrics.density + 1f)
    }

    @Test fun assistantFileCardDoesNotChangeTheResponseCopyPayload() {
        state.bindMediaConversation(root, AgentChatConversationKey("droide", "s-copy-media"))
        val ref = import()
        val response = "Download the attached result. [Documentation](https://example.test/docs)"
        mount { AgentMessageInteractionHost("media-copy") {
            AgentTranscriptItem(AgentMessage("assistant", response, media = listOf(ref)), false, root)
        } }
        compose.onNodeWithContentDescription("Copy response").performClick()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(response, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithContentDescription("Attachment example.txt").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onNodeWithContentDescription("Open example.txt").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithContentDescription("Open example.txt").assertIsEnabled()
        compose.onNodeWithContentDescription("Export example.txt").assertIsEnabled()
    }

    @Test fun imageRequestsBoundThumbnailAndExplicitPreviewPixelsWithoutAnotherDiskCache() {
        val request = agentMediaImageRequest(app, root.resolve("original.png"), 256)
        val size = runBlocking { request.sizeResolver.size() }
        assertEquals(256, (size.width as Dimension.Pixels).px)
        assertEquals(256, (size.height as Dimension.Pixels).px)
        assertEquals(coil.request.CachePolicy.DISABLED, request.diskCachePolicy)
        val large = runBlocking { agentMediaImageRequest(app, root.resolve("original.png"), 40_000).sizeResolver.size() }
        assertEquals(1_024, (large.width as Dimension.Pixels).px)
    }

    @Test fun imageCacheHasOneApplicationOwnerAndSixteenMiBMaximum() {
        val first = agentMediaImageLoader(app)
        assertSame(first, agentMediaImageLoader(android.content.ContextWrapper(app)))
        assertEquals(16 * 1024 * 1024, first.memoryCache!!.maxSize)
        assertNull(first.diskCache)
        val file = root.resolve("original.png")
        assertNotEquals(agentMediaImageRequest(app, file, 128).memoryCacheKey, agentMediaImageRequest(app, file, 1_024).memoryCacheKey)
    }

    @Test fun longFileExcerptIncludesReadableAliasAndNeverClaimsToBeTheCompleteFile() {
        state.bindMediaConversation(root, AgentChatConversationKey("droide", "s-excerpt"))
        val ref = import("large.txt")
        val prompt = composeAgentPrompt("Find last line", listOf(AgentAttachedContext(ref.name, "x".repeat(12_000), media = ref, previewTruncated = true)))
        assertTrue(prompt.contains(ref.path))
        assertTrue(prompt.contains("Text excerpt is incomplete"))
        assertTrue(prompt.contains("next_offset"))
    }

    @Test fun reopeningThePanelKeepsItsDraftButChangingBackendInvalidatesPendingPicker() {
        val key = AgentChatConversationKey("droide", "s-draft")
        state.bindMediaConversation(root, key)
        val ref = import()
        state.attachments.value = listOf(AgentAttachedContext(ref.name, "sample content", media = ref))
        val token = state.mediaDraftToken()
        state.pendingMediaPicker = token
        val visible = mutableStateOf(true)
        mount { if (visible.value) AgentDraftMediaRow(state.attachments.value, false, null, root, state::removeMediaAttachment) else Text("Editor") }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true; state.bindMediaConversation(root, key) }
        assertTrue(state.isCurrentMediaDraft(token))
        assertEquals(listOf(ref), state.attachments.value.mapNotNull { it.media })
        compose.onNodeWithText("example.txt").assertIsDisplayed()
        compose.runOnIdle { state.bindMediaConversation(root, AgentChatConversationKey("codex", "p-other")) }
        assertFalse(state.isCurrentMediaDraft(token))
        assertTrue(state.attachments.value.isEmpty())
        importPickedAgentMedia(app, state, android.net.Uri.parse("content://unavailable/old-result"))
        assertTrue("A stale result must not start IO or become an attachment", state.attachments.value.isEmpty())
        assertFalse(state.mediaImporting.value)
    }

    @Test fun firstSessionCreationPreservesPendingAdmissionAndSuccessRotatesItsOwner() {
        state.bindMediaConversation(root, AgentChatConversationKey("droide", null))
        val ref = import()
        state.attachments.value = listOf(AgentAttachedContext(ref.name, "sample", media = ref))
        val token = state.mediaDraftToken()
        state.beginMediaSubmission(token)
        state.bindMediaConversation(root, AgentChatConversationKey("droide", "s-created"))
        assertTrue(state.isCurrentMediaDraft(token))
        assertEquals(1, state.attachments.value.size)
        state.finishMediaSubmission(token, accepted = true)
        assertFalse(state.isCurrentMediaDraft(token))
        assertTrue(state.attachments.value.isEmpty())
        assertNotEquals(token.ownerId, state.mediaDraftToken().ownerId)
        assertNotNull("The service, not the UI, transfers/releases accepted media", ChatMediaStore(root).file(ref))
    }

    @Test fun videoAndBinaryFilesAreNeverMistakenForTextContext() {
        assertFalse(agentAttachmentIsText("movie.mp4", "video/mp4"))
        assertFalse(agentAttachmentIsText("archive.zip", "application/zip"))
        assertFalse(agentAttachmentIsText("report.pdf", "application/pdf"))
        assertFalse(agentAttachmentIsText("image.png", "image/png"))
        assertTrue(agentAttachmentIsText("README.md", "application/octet-stream"))
        assertTrue(agentAttachmentIsText("data.json", "application/json"))
    }

    @Test fun aLargeHistoryMediaListOnlyComposesFourCardsUntilTheUserPagesIt() {
        val refs = (1..13).map { number -> ChatMediaRef("m-old-$number", ".droide/chat-media/blobs/m-old-$number/original.txt",
            "expired-$number.txt", "text/plain", 42, 0, 1) }
        mount { AgentMessageMedia(refs, root) }
        compose.onAllNodes(hasContentDescription("Attachment ", substring = true)).assertCountEquals(4)
        compose.onNodeWithText("Attachments 1–4 of 13").assertIsDisplayed()
        compose.onNodeWithText("expired-5.txt").assertDoesNotExist()
        compose.onNodeWithText("Next").performClick()
        compose.onAllNodes(hasContentDescription("Attachment ", substring = true)).assertCountEquals(4)
        compose.onNodeWithText("Attachments 5–8 of 13").assertIsDisplayed()
        compose.onNodeWithText("expired-1.txt").assertDoesNotExist()
        compose.onNodeWithText("expired-5.txt").assertIsDisplayed()
    }

    @Test fun staleImportIsCancelledAndItsFinallyCannotClearTheNewDraftImport() {
        state.bindMediaConversation(root, AgentChatConversationKey("droide", "s-import"))
        val old = AgentMediaImportOperation(state.mediaDraftToken()).also { it.job = parent.launch { awaitCancellation() } }
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        old.bindStream(object : ByteArrayInputStream(byteArrayOf(1)) { override fun close() { closed.set(true); super.close() } })
        state.ownMediaImport(old)
        state.bindMediaConversation(root, AgentChatConversationKey("codex", "p-import"))
        assertTrue(old.cancelled)
        assertTrue(old.job!!.isCancelled)
        compose.waitUntil(5_000) { closed.get() }
        val current = AgentMediaImportOperation(state.mediaDraftToken()).also { it.job = parent.launch { awaitCancellation() } }
        state.ownMediaImport(current)
        state.finishMediaImport(old)
        assertTrue("The previous import must not hide the new import indicator", state.mediaImporting.value)
        mount { AgentDraftMediaRow(emptyList(), state.mediaImporting.value, null, root, {}, state::cancelMediaImport) }
        val bounds = compose.onNodeWithContentDescription("Cancel attachment import").fetchSemanticsNode().boundsInRoot
        val minimum = 48f * app.resources.displayMetrics.density
        assertTrue(bounds.width >= minimum && bounds.height >= minimum)
        compose.onNodeWithContentDescription("Cancel attachment import").performClick()
        assertTrue(current.job!!.isCancelled)
        assertFalse(state.mediaImporting.value)
    }

    @Test fun attachmentBytesStayOutsideTheProjectAndUserOriginalIsPreserved() {
        state.bindMediaConversation(root, AgentChatConversationKey("droide", "s-storage"))
        val original = root.resolve("project-original.txt").apply { writeText("keep project source") }
        val ref = import()
        val physical = checkNotNull(ChatMediaStore(root).file(ref))
        assertFalse("Chat media must not pollute project or dependencies", physical.canonicalPath.startsWith(root.canonicalPath + java.io.File.separator))
        assertFalse("The presentation alias must not materialize in the project", root.resolve(ref.path).exists())
        assertEquals("keep project source", original.readText())
    }

    @Test fun deletingTheSelectedIdleSessionStartsAFreshMediaDraftWithoutTouchingProjectFiles() {
        val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0, runtimeScope = parent)
        try {
            val selected = runBlocking { agent.sessions.create("Selected media session") }
            runBlocking { agent.loadSession(selected) }
            state.bindMediaConversation(root, AgentChatConversationKey("droide", selected.id))
            state.input.value = "old draft"
            val oldToken = state.mediaDraftToken()
            val original = root.resolve("keep.txt").apply { writeText("original") }
            mount { SessionDrawer(agent.sessions, onPick = {}, onDeleted = { deletedId ->
                assertNull("Callback must follow successful durable deletion", runBlocking { agent.sessions.get(deletedId) })
                resetDeletedAgentConversation(agent, state, deletedId)
            }) }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Delete").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Delete").performClick()
            compose.waitUntil(5_000) { agent.currentSessionId == null }
            assertFalse(state.isCurrentMediaDraft(oldToken))
            assertEquals("", state.input.value)
            state.bindMediaConversation(root, AgentChatConversationKey("droide", null))
            assertNotEquals(oldToken.ownerId, state.mediaDraftToken().ownerId)
            assertNotNull(ChatMediaStore(root).file(import()))
            assertEquals("original", original.readText())
        } finally { agent.shutdown() }
    }

    @Test fun nativeLiveProjectionKeepsSixteenRecentStableKeysAndEndsWithoutDuplicates() {
        val refs = (1..20).map { number -> ChatMediaRef("m-live-$number", ".droide/chat-media/blobs/m-live-$number/original.txt",
            "live-$number.txt", "text/plain", 42, 0, 1) }
        val messages = listOf(AgentMessage("user", "Publish these results")) + refs.map { AgentMessage("assistant", "", media = listOf(it)) }
        val live = projectAgentNativeLiveMedia(messages, true, 1)
        assertEquals(20, live.total)
        assertEquals(4, live.hidden)
        assertEquals(refs.takeLast(16), live.entries.map { it.ref })
        val keys = live.entries.map { it.key(17L) }
        assertEquals(16, keys.toSet().size)
        val next = messages + AgentMessage("assistant", "Completed")
        assertEquals(keys, projectAgentNativeLiveMedia(next, true, 1).entries.map { it.key(17L) })
        assertTrue(projectAgentNativeLiveMedia(next, false, 1).entries.isEmpty())
        assertEquals("Evicting the entire frozen prefix must not hide live attachments", 16,
            projectAgentNativeLiveMedia(next, true, 0).entries.size)
    }

    @Test @Config(application = DroideApplication::class)
    fun nativePaneShowsToolProducedMediaImmediatelyAndOnlyOnceAfterCompletion() {
        val host = (app as DroideApplication).workspaceHost
        val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0, runtimeScope = parent)
        try {
            val ref = ChatMediaRef("m-native-live", ".droide/chat-media/blobs/m-native-live/original.txt", "native-live.txt", "text/plain", 42, 0, 1)
            @Suppress("UNCHECKED_CAST") val messages = AgentService::class.java.getDeclaredField("_messages").apply { isAccessible = true }.get(agent) as MutableStateFlow<List<AgentMessage>>
            @Suppress("UNCHECKED_CAST") val busy = AgentService::class.java.getDeclaredField("_busy").apply { isAccessible = true }.get(agent) as MutableStateFlow<Boolean>
            @Suppress("UNCHECKED_CAST") val stream = AgentService::class.java.getDeclaredField("_streamState").apply { isAccessible = true }.get(agent) as MutableStateFlow<AgentStreamState>
            messages.value = listOf(AgentMessage("user", "Create the file"), AgentMessage("assistant", "", media = listOf(ref)))
            stream.value = AgentStreamState(runId = 17, baseMessageCount = 1, status = AgentStreamState.Status.BUSY)
            busy.value = true
            mount { AgentPane(agent, state, host.coordinator.providerConnections, host.coordinator.localLlamaAgentModels) }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Attachment native-live.txt").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithContentDescription("Attachment native-live.txt").assertIsDisplayed()
            compose.runOnIdle { busy.value = false; stream.value = stream.value.copy(status = AgentStreamState.Status.IDLE) }
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("Attachment native-live.txt").assertCountEquals(1)
            compose.onNodeWithContentDescription("Attachment native-live.txt").assertIsDisplayed()
        } finally { agent.shutdown(); host.close() }
    }

    @Test @Config(application = DroideApplication::class)
    fun externalPaneShowsTypedOutputMediaDuringTheRunAndMovesItToCompletedHistory() {
        val host = (app as DroideApplication).workspaceHost
        val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0, runtimeScope = parent)
        try {
            val ref = ChatMediaRef("m-cli-live", ".droide/chat-media/blobs/m-cli-live/original.txt", "cli-live.txt", "text/plain", 42, 0, 1)
            val controller = state.externalController
            @Suppress("UNCHECKED_CAST") val states = ExternalAgentSessionController::class.java.getDeclaredField("states").apply { isAccessible = true }.get(controller) as MutableMap<String, ExternalAgentConversationState>
            @Suppress("UNCHECKED_CAST") val run = ExternalAgentSessionController::class.java.getDeclaredField("activeRun\$delegate").apply { isAccessible = true }.get(controller) as androidx.compose.runtime.MutableState<ExternalAgentActiveRun?>
            states["codex"] = ExternalAgentConversationState(historyId = "p-live", sessionId = "cli-native", liveText = "File ready", liveMedia = listOf(ref))
            run.value = ExternalAgentActiveRun(18, "codex", "cli-native")
            state.backendId.value = "codex"
            mount { AgentPane(agent, state, host.coordinator.providerConnections, host.coordinator.localLlamaAgentModels) }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Attachment cli-live.txt").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithContentDescription("Attachment cli-live.txt").assertIsDisplayed()
            compose.runOnIdle {
                states["codex"] = states.getValue("codex").copy(messages = listOf(AgentMessage("assistant", "File ready", media = listOf(ref))), liveText = "", liveMedia = emptyList())
                run.value = null
            }
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("Attachment cli-live.txt").assertCountEquals(1)
            compose.onNodeWithContentDescription("Attachment cli-live.txt").assertIsDisplayed()
        } finally { agent.shutdown(); host.close() }
    }
}
