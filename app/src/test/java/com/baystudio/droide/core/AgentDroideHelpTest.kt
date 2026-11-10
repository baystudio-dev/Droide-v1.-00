package com.baystudio.droide.core

import com.baystudio.droide.BuildConfig
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentDroideHelpTest {
    @Test fun availabilityReadPermissionsAndProfileAllowlistFilterTheTool() = workspace { root ->
        fun names(available: Boolean, permission: PermissionEngine? = null) = AgentRuntimePrompt.toolNames(
            AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root),
                handbookAvailable = available, permissions = permission))
        assertFalse("droide_help" in names(false))
        assertTrue("droide_help" in names(true))
        assertFalse("droide_help" in names(true, PermissionEngine(listOf(PermRule("read", "*", PermEffect.DENY)))))
        val filtered = PermissionEngine().fork(emptyList(), setOf("read_file"))
        assertFalse("droide_help" in names(true, filtered))
        assertEquals("read", AgentTools.actionOf("droide_help"))
        AgentMode.entries.forEach { assertNull(AgentModePolicy.check(it, "droide_help")) }
    }

    @Test fun bootstrapOnlyAppearsWhenTheFilteredActiveToolIsPresent() = workspace { root ->
        val tools = AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root), handbookAvailable = true)
        fun prompt(surface: JsonArray?, greeting: Boolean = false, final: Boolean = false) = AgentRuntimePrompt.compose(
            AgentMode.BUILD, "", "", "", final, surface, greetingOnly = greeting)
        assertTrue(prompt(tools).contains(DroideHandbookPolicy.BOOTSTRAP))
        assertFalse(prompt(JsonArray(emptyList())).contains(DroideHandbookPolicy.BOOTSTRAP))
        assertFalse(prompt(null, greeting = true).contains(DroideHandbookPolicy.BOOTSTRAP))
        assertFalse(prompt(null, final = true).contains(DroideHandbookPolicy.BOOTSTRAP))
        assertFalse(prompt(tools).contains("## 1. Identitas yang terverifikasi"))
        assertFalse(ChatMediaStore(root).hasManifest())
    }

    @Test fun textHelpWorksOfflineInReadOnlyModeWithoutMakingMediaOrProjectFiles() = workspace { root -> runBlocking {
        val result = AgentTools.execute("droide_help", args("identity"), context(root, mode = AgentMode.REVIEW))
        assertTrue(result.startsWith("[DROIDE_HELP_REFERENCE")); assertTrue(result.contains("BayStudio"))
        assertFalse(result.contains("## 2.")); assertTrue(result.length <= DroideHandbookPolicy.MAX_RESULT_CHARS)
        assertFalse(ChatMediaStore(root).hasManifest())
        assertFalse(root.resolve("AGENTS.md").exists()); assertFalse(root.resolve(".droide/vision").exists())
    } }

    @Test fun releaseAndArchitectureHelpPreserveInstalledBuildMetadataAndSectionIsolation() = workspace { root -> runBlocking {
        for (section in listOf("architecture", "diagnostics", "troubleshooting_agent", "troubleshooting_runtime", "lifecycle", "release", "integrations")) {
            val result = AgentTools.execute("droide_help", args(section), context(root, mode = AgentMode.REVIEW))
            assertTrue(result.startsWith("[DROIDE_HELP_REFERENCE section=$section "))
            assertTrue(result.contains("Runtime app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE});"))
            assertTrue(result.contains(DroideHandbookPolicy.SOURCE_REFERENCE))
            assertTrue(result.contains("## ${DroideHandbookPolicy.sections.getValue(section)}. "))
            assertEquals(1, Regex("(?m)^## ").findAll(result).count())
            assertTrue(result.length <= DroideHandbookPolicy.MAX_RESULT_CHARS)
        }
        assertFalse(ChatMediaStore(root).hasManifest())
        assertFalse(root.resolve("AGENTS.md").exists())
    } }

    @Test fun invalidSelectionsAndReadDenialsNeverPublishImages() = workspace { root -> runBlocking {
        val images = AgentToolImages()
        val ctx = context(root, imageSink = images::add)
        for (request in listOf(args("all"), args("editor", "../private.jpg"), args("identity", "editor"),
            buildJsonObject { put("section", "identity"); put("path", "/private") })) {
            assertTrue(AgentTools.execute("droide_help", request, ctx).startsWith("ERROR"))
        }
        val denied = context(root, permissions = PermissionEngine(listOf(PermRule("read", "*", PermEffect.DENY))), imageSink = images::add)
        assertTrue(AgentTools.execute("droide_help", args("editor", "editor"), denied).startsWith("DENIED"))
        assertTrue(images.refs().isEmpty()); assertFalse(ChatMediaStore(root).hasManifest())
    } }

    @Test fun nonvisionRequestUsesTextAndDoesNotImportOrPretendToInspectPixels() = workspace { root -> runBlocking {
        val images = AgentToolImages()
        val ctx = context(root, imageSink = images::add, config = AgentConfig(providerId = "unknown-text-provider", model = "text-only"))
        val result = AgentTools.execute("droide_help", args("editor", "editor"), ctx)
        assertTrue(result.contains("Image input is unavailable")); assertTrue(result.contains("Review Mode"))
        assertTrue(images.refs().isEmpty()); assertFalse(ChatMediaStore(root).hasManifest())
    } }

    @Test fun screenshotUsesExactOwnedBytesAndTheExistingDeferredImageGroup() = workspace { root -> runBlocking {
        val images = AgentToolImages()
        val result = AgentTools.execute("droide_help", args("editor", "editor"), context(root, imageSink = images::add))
        assertTrue(result.contains("after the complete tool-result group")); assertTrue(result.contains("Capture date and app build unknown"))
        val ref = images.refs().single()
        assertEquals(DroideHandbookPolicy.screenshots.getValue("editor").sha256, ref.sha256)
        assertTrue(ref.path.startsWith(".droide/chat-media/blobs/")); assertNotNull(ref.mediaId)
        val store = ChatMediaStore(root)
        assertEquals(listOf(ref.mediaId), store.ownerRefs("test-session").map { it.id })
        assertTrue(store.ownerRefs("other-session").isEmpty())
        assertFalse(root.resolve(".droide/vision").exists()); assertFalse(root.resolve("agent-handbook").exists())
        val history = listOf(requireNotNull(images.message()))
        assertTrue(VisionSupport.materializeHistory(history, root).single().toString().contains("data:image/jpeg;base64,"))
    } }

    @Test fun staleRequestMissingOwnerAndMissingContextFailBeforeImport() = workspace { root -> runBlocking {
        for (ctx in listOf(context(root, current = { false }), context(root, owner = null), context(root, available = false))) {
            assertTrue(AgentTools.execute("droide_help", args("editor", "editor"), ctx).startsWith("ERROR"))
        }
        assertFalse(ChatMediaStore(root).hasManifest())
    } }

    @Test fun ownerChangeAfterImportReclaimsTheNewCopyBeforePublication() = workspace { root -> runBlocking {
        var published = false
        val ctx = context(root, imageSink = { published = true }, current = {
            !ChatMediaStore(root).hasManifest()
        })
        assertTrue(AgentTools.execute("droide_help", args("editor", "editor"), ctx).startsWith("ERROR"))
        assertFalse(published); assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
    } }

    @Test fun publicationFailureOrCancellationReclaimsOnlyItsNewImage() = workspace { root -> runBlocking {
        val store = ChatMediaStore(root)
        val earlier = store.import("test-session", "keep.txt", "text/plain", "keep".byteInputStream())
        val failed = context(root, imageSink = { error("sink rejected") })
        assertTrue(AgentTools.execute("droide_help", args("editor", "editor"), failed).startsWith("ERROR"))
        try {
            AgentTools.execute("droide_help", args("editor", "editor"), context(root, imageSink = { throw CancellationException("stop") }))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf(earlier), store.ownerRefs("test-session"))
        assertEquals("keep", store.file(earlier)!!.readText())
    } }

    @Test fun sharedGroupBudgetDeniesRepeatedCorpusLoadingAndDoesNotFetchImages() = workspace { root -> runBlocking {
        val budget = DroideHandbookBudget().apply { reserve(DroideHandbookPolicy.MAX_RESULT_CHARS) }
        val ctx = context(root, imageSink = { fail("No image before budget admission") }).also { it.handbookBudget = budget }
        val result = AgentTools.execute("droide_help", args("terminal", "terminal_packages"), ctx)
        assertTrue(result.startsWith("ERROR")); assertTrue(result.contains("budget"))
        assertFalse(ChatMediaStore(root).hasManifest())
    } }

    private fun args(section: String, screenshot: String? = null) = buildJsonObject {
        put("section", section); screenshot?.let { put("screenshot", it) }
    }
    private fun context(root: File, permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
        mode: AgentMode = AgentMode.BUILD, config: AgentConfig = AgentConfig(providerId = "openai", model = "gpt-4o"),
        owner: String? = "test-session", available: Boolean = true, current: () -> Boolean = { true },
        imageSink: ((List<AgentImageRef>) -> Unit)? = null): AgentTools.Ctx {
        val base = agentWorkflowContext(root, permissions = permissions, mode = mode)
        return AgentTools.Ctx(base.files, base.terminal, base.git, base.approvals, base.questions, base.history,
            base.perms, base.mode, base.llm, config, root, base.todos, owner, base.lsp,
            onImageReady = imageSink, appContext = if (available) RuntimeEnvironment.getApplication() else null,
            mediaRequestCurrent = current)
    }
    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-help-").toFile()
        try { block(root) } finally {
            PathSecurity.deleteTreeNoFollow(ChatMediaStore(root).storageDirectory)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }
}
