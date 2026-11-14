package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentDroideHelpHostTest {
    private val assetRoot get() = File(System.getProperty("droide.source")).resolve("app/src/main/assets")
    private fun args(section: String = "editor", image: String? = "editor") = buildJsonObject {
        put("section", section); image?.let { put("screenshot", it) }
    }
    private fun fixture(test: suspend (File, HandbookAssets) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-help-handler-host-").toFile()
        try { test(root, HandbookAssets(assetRoot)) }
        finally { ChatMediaStore.states.remove(root.absolutePath); root.deleteRecursively() }
    }
    private fun ctx(root: File, assets: HandbookAssets, vision: Boolean = true, owner: String? = "owner",
        current: () -> Boolean = { true }, sink: ((List<AgentImageRef>) -> Unit)? = null) =
        AgentTools.Ctx(root, HandbookContext(assets), owner, AgentConfig(vision), current, sink)
    private suspend fun run(request: JsonObject, context: AgentTools.Ctx) = AgentDroideHelp.execute(request, context) { _, _ -> null }
    private suspend inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") }
        catch (failure: Throwable) { if (failure !is T) throw failure }
    }

    @Test fun realRuntimePromptIncludesBootstrapOnlyForTheActiveTool() = fixture { root, assets ->
        val withHelp = AgentToolCatalog.definitions
        val withoutHelp = JsonArray(withHelp.filterNot { it.jsonObject["function"]!!.jsonObject["name"] == JsonPrimitive("droide_help") })
        fun prompt(tools: JsonArray?, greeting: Boolean = false, final: Boolean = false) = AgentRuntimePrompt.compose(
            AgentMode.BUILD, "", "", "", final, tools, greetingOnly = greeting)
        assertTrue(prompt(withHelp).contains(DroideHandbookPolicy.BOOTSTRAP))
        assertFalse(prompt(withoutHelp).contains(DroideHandbookPolicy.BOOTSTRAP))
        assertFalse(prompt(null, greeting = true).contains(DroideHandbookPolicy.BOOTSTRAP))
        assertFalse(prompt(null, final = true).contains(DroideHandbookPolicy.BOOTSTRAP))
        for (section in DroideHandbookPolicy.sections.keys) {
            val text = DroideHandbookPolicy.readSection(assetRoot.resolve(DroideHandbookPolicy.ASSET).reader(), section)
            assertFalse(prompt(withHelp).contains(text))
            assertFalse(prompt(withHelp).contains(text.lineSequence().first()))
        }
        assertTrue(assets.opened.isEmpty()); assertTrue(ChatMediaStore(root).state.refs.isEmpty())
    }
    @Test fun realCatalogOnlyExposesFixedSectionAndImageSelections() {
        val definition = AgentToolCatalog.definitions.map { it.jsonObject["function"]!!.jsonObject }
            .single { it["name"] == JsonPrimitive("droide_help") }
        val parameters = definition["parameters"]!!.jsonObject
        val properties = parameters["properties"]!!.jsonObject
        assertEquals(setOf("section", "screenshot"), properties.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("section"))), parameters["required"])
        assertEquals(DroideHandbookPolicy.sections.keys.toSet(), properties["section"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(DroideHandbookPolicy.screenshots.keys.toSet(), properties["screenshot"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }
    @Test fun realPermissionClassificationAndAllReadOnlyModesKeepHelpReadOnly() {
        assertEquals("read", AgentToolPermissionPolicy.actionOf("droide_help"))
        AgentMode.entries.forEach { assertNull(AgentModePolicy.check(it, "droide_help")) }
    }

    @Test fun textReadsOneSectionWithoutImageOrWorkspaceCopy() = fixture { root, assets ->
        val result = run(args("identity", null), ctx(root, assets))
        assertTrue(result.contains("BayStudio")); assertFalse(result.contains("## 2."))
        assertEquals(listOf(DroideHandbookPolicy.ASSET), assets.opened)
        assertTrue(ChatMediaStore(root).state.refs.isEmpty()); assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun bothReadGatesPrecedeAllAssetIo() = fixture { root, assets ->
        for (denied in listOf("droide_help:editor", "droide_help:screenshot:editor")) {
            val result = AgentDroideHelp.execute(args(), ctx(root, assets, sink = { fail("No publication") })) { action, resource ->
                assertEquals("read", action); if (resource == denied) "DENIED: fixture" else null
            }
            assertEquals("DENIED: fixture", result)
            assertTrue(assets.opened.isEmpty()); assertTrue(ChatMediaStore(root).state.refs.isEmpty())
        }
    }
    @Test fun nonvisionAndMissingSinkReadOnlyText() = fixture { root, assets ->
        for (context in listOf(ctx(root, assets, vision = false, sink = { fail("No image") }), ctx(root, assets))) {
            val result = run(args(), context)
            assertTrue(result.contains("Image input is unavailable")); assertTrue(result.contains("Review Mode"))
        }
        assertEquals(listOf(DroideHandbookPolicy.ASSET, DroideHandbookPolicy.ASSET), assets.opened)
        assertTrue(ChatMediaStore(root).state.refs.isEmpty())
    }
    @Test fun exactScreenshotIsImportedToOnlyTheCapturedOwner() = fixture { root, assets ->
        val images = mutableListOf<AgentImageRef>()
        val result = run(args(), ctx(root, assets, sink = images::addAll))
        assertEquals(1, images.size)
        assertEquals(DroideHandbookPolicy.screenshots.getValue("editor").sha256, images.single().sha256)
        assertEquals("owner", ChatMediaStore(root).state.refs.single().owner)
        assertTrue(result.contains("Capture date and app build unknown")); assertTrue(result.contains("tool-result group"))
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun staleBeforeReadAndMissingOwnerDoNotOpenAssets() = fixture { root, assets ->
        rejects<IllegalStateException> { run(args(), ctx(root, assets, current = { false })) }
        rejects<IllegalArgumentException> { run(args(), ctx(root, assets, owner = null)) }
        assertTrue(assets.opened.isEmpty())
    }
    @Test fun stalenessAfterPermissionPreventsAssetIo() = fixture { root, assets ->
        var current = true
        rejects<IllegalStateException> {
            AgentDroideHelp.execute(args(), ctx(root, assets, current = { current })) { _, _ -> current = false; null }
        }
        assertTrue(assets.opened.isEmpty())
    }
    @Test fun ownerChangeAfterImportCleansOnlyNewRef() = fixture { root, assets ->
        val store = ChatMediaStore(root)
        val existing = store.import("owner", "keep", "text/plain", "keep".byteInputStream())
        var current = true
        store.state.afterImport = { current = false }
        rejects<IllegalStateException> { run(args(), ctx(root, assets, current = { current }, sink = { fail("No image") })) }
        assertEquals(listOf(existing), store.state.refs)
        assertEquals(0, store.state.stageCalls)
    }
    @Test fun ownerChangeAfterStageCleansOnlyNewRef() = fixture { root, assets ->
        var current = true
        val store = ChatMediaStore(root)
        store.state.afterStage = { current = false }
        rejects<IllegalStateException> { run(args(), ctx(root, assets, current = { current }, sink = { fail("No image") })) }
        assertTrue(store.state.refs.isEmpty())
        assertEquals(1, store.state.stageCalls)
    }
    @Test fun sinkFailureAndCancellationReleaseNewCopy() = fixture { root, assets ->
        val store = ChatMediaStore(root)
        val existing = store.import("owner", "keep", "text/plain", "keep".byteInputStream())
        rejects<IllegalStateException> { run(args(), ctx(root, assets, sink = { error("sink rejected") })) }
        rejects<CancellationException> { run(args(), ctx(root, assets, sink = { throw CancellationException("stopped") })) }
        assertEquals(listOf(existing), store.state.refs)
    }
    @Test fun imageCorruptionFailsBeforeImport() = fixture { root, assets ->
        assets.replace = { path, bytes -> if (path.endsWith(".jpg")) bytes.copyOf().also { it[it.lastIndex] = 0 } else bytes }
        rejects<IllegalArgumentException> { run(args(), ctx(root, assets, sink = { fail("No image") })) }
        assertTrue(ChatMediaStore(root).state.refs.isEmpty())
    }
    @Test fun missingSectionFailsWithoutImageOrWholeCorpusFallback() = fixture { root, assets ->
        assets.replace = { path, bytes -> if (path.endsWith(".md")) "## 1. Identity\nonly".toByteArray() else bytes }
        rejects<IllegalArgumentException> { run(args(), ctx(root, assets, sink = { fail("No image") })) }
        assertEquals(listOf(DroideHandbookPolicy.ASSET), assets.opened)
        assertTrue(ChatMediaStore(root).state.refs.isEmpty())
    }
    @Test fun strictArgumentsRejectUnknownFieldsTypesPathsAndMismatchedImages() = fixture { root, assets ->
        for (request in listOf(args("all"), args("editor", "../editor"), args("identity", "editor"),
            buildJsonObject { put("section", 1) }, buildJsonObject { put("section", "editor"); put("screenshot", JsonNull) },
            buildJsonObject { put("section", "identity"); put("url", "https://example.com") })) {
            rejects<IllegalArgumentException> { run(request, ctx(root, assets)) }
        }
        assertTrue(assets.opened.isEmpty())
    }
    @Test fun groupBudgetDenialPrecedesImageReadAndCopy() = fixture { root, assets ->
        val context = ctx(root, assets, sink = { fail("No image") })
        context.handbookBudget.reserve(DroideHandbookPolicy.MAX_RESULT_CHARS)
        rejects<IllegalArgumentException> { run(args("terminal", "terminal_packages"), context) }
        assertEquals(listOf(DroideHandbookPolicy.ASSET), assets.opened)
        assertTrue(ChatMediaStore(root).state.refs.isEmpty())
    }
}
