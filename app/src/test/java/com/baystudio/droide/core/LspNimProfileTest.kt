package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspNimProfileTest {
    private fun root(block: (File) -> Unit) {
        val temp = Files.createTempDirectory("nim-profile-authored-").toFile()
        try { block(temp) } finally { PathSecurity.deleteTreeNoFollow(temp) }
    }
    private fun file(root: File, name: String, text: String = ""): File = File(root, name).apply { parentFile.mkdirs(); writeText(text) }
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun exhaustedRootSearchKeepsExactUnavailableDocumentParent() = root { root ->
        file(root, "root.nimble")
        val document = file(root, (1..130).joinToString("/") { "d" } + "/a.nim")
        assertEquals(document.parentFile, LspNimProfile.projectRoot(root, document))
        assertFalse(LspNimProfile.projectAvailable(document.parentFile))
        file(document.parentFile, "local.nimble")
        assertEquals(document.parentFile, LspNimProfile.projectRoot(root, document))
        assertTrue(LspNimProfile.projectAvailable(document.parentFile))
    }

    @Test fun exactCaseRoutingNeverIncludesScriptsManifestsOrCustomExtensions() {
        assertTrue(LspNimProfile.supportsPath("src/é😀.nim"))
        assertFalse(LspNimProfile.supportsPath("src/foo.NIM"))
        assertFalse(LspNimProfile.supportsPath("foo.nims"))
        assertFalse(LspNimProfile.supportsPath("pkg.nimble"))
        assertEquals("nimlangserver", LspNimProfile.ID)
        assertEquals("1.16.0", LspNimProfile.VERSION)
    }

    @Test fun nearestNimbleAndConfigMarkersSelectActualRoot() = root { root ->
        file(root, "top.nimble")
        file(root, "nested/inner.nimble")
        val document = file(root, "nested/src/main.nim")
        assertEquals(File(root, "nested"), LspNimProfile.projectRoot(root, document))
        assertTrue(LspNimProfile.projectAvailable(File(root, "nested")))
        file(root, "nested/src/nim.cfg")
        assertEquals(document.parentFile, LspNimProfile.projectRoot(root, document))
        assertFalse(LspNimProfile.projectAvailable(document.parentFile))
    }

    @Test fun configNimsAndGitFileAreNearestTypedMarkers() = root { root ->
        file(root, "top.nimble")
        val document = file(root, "project/src/main.nim")
        file(root, "project/.git", "gitdir: /somewhere")
        assertEquals(File(root, "project"), LspNimProfile.projectRoot(root, document))
        file(root, "project/src/config.nims")
        assertEquals(document.parentFile, LspNimProfile.projectRoot(root, document))
    }

    @Test fun gitAndHgDirectoriesAreNearestMarkers() = root { root ->
        val document = file(root, "p/src/main.nim")
        File(root, "p/.hg").mkdirs()
        assertEquals(File(root, "p"), LspNimProfile.projectRoot(root, document))
        File(root, "p/src/.git").mkdirs()
        assertEquals(document.parentFile, LspNimProfile.projectRoot(root, document))
    }

    @Test fun workspaceFallbackDoesNotSearchAboveBoundary() = root { root ->
        file(root, "outside.nimble")
        val workspace = File(root, "workspace").apply { mkdirs() }
        val document = file(workspace, "src/main.nim")
        assertEquals(workspace, LspNimProfile.projectRoot(workspace, document))
        assertFalse(LspNimProfile.projectAvailable(workspace))
        rejected { LspNimProfile.projectRoot(workspace, file(root, "outside.nim")) }
    }

    @Test fun missingParentIsExactUnavailableSentinel() = root { root ->
        file(root, "top.nimble")
        val missing = File(root, "removed/deep/main.nim")
        assertEquals(missing.parentFile, LspNimProfile.projectRoot(root, missing))
        assertFalse(LspNimProfile.projectAvailable(missing.parentFile))
    }

    @Test fun oneRegularManifestRequiredAndMarkersCannotBorrowAncestorManifest() = root { root ->
        file(root, "top.nimble")
        val document = file(root, "p/main.nim")
        file(root, "p/nim.cfg")
        assertFalse(LspNimProfile.projectAvailable(LspNimProfile.projectRoot(root, document)))
        file(root, "p/a.nimble")
        assertTrue(LspNimProfile.projectAvailable(document.parentFile))
        file(root, "p/b.nimble")
        assertFalse(LspNimProfile.projectAvailable(document.parentFile))
    }

    @Test fun wrongTypeAndSymlinkMarkersCannotBroadenOrBecomeAvailable() = root { root ->
        file(root, "top.nimble")
        val document = file(root, "p/main.nim")
        File(root, "p/wrong.nimble").mkdir()
        assertEquals(document.parentFile, LspNimProfile.projectRoot(root, document))
        assertFalse(LspNimProfile.projectAvailable(document.parentFile))
        File(root, "p/wrong.nimble").delete()
        Files.createSymbolicLink(File(root, "p/alias.nimble").toPath(), File(root, "top.nimble").toPath())
        assertFalse(LspNimProfile.projectAvailable(document.parentFile))
    }

    @Test fun rootAliasesCannotEscapeOrChangeOriginalCaseExtension() = root { root ->
        val workspace = File(root, "workspace").apply { mkdir() }
        val outside = file(root, "outside.nim")
        val alias = File(workspace, "alias.nim")
        Files.createSymbolicLink(alias.toPath(), outside.toPath())
        rejected { LspNimProfile.projectRoot(workspace, alias) }
        alias.delete()
        val upper = file(workspace, "upper.NIM")
        Files.createSymbolicLink(alias.toPath(), upper.toPath())
        rejected { LspNimProfile.projectRoot(workspace, alias) }
    }

    @Test fun wrongTypeConfigAndHgAreUnavailableBarriers() = root { root ->
        file(root, "top.nimble")
        val document = file(root, "p/main.nim")
        file(root, "p/local.nimble")
        file(root, "p/.hg")
        assertEquals(document.parentFile, LspNimProfile.projectRoot(root, document))
        assertFalse(LspNimProfile.projectAvailable(document.parentFile))
        File(root, "p/.hg").delete()
        File(root, "p/nim.cfg").mkdir()
        assertFalse(LspNimProfile.projectAvailable(document.parentFile))
    }

    @Test fun immediateDirectoryScanIsBoundedAndDoesNotBroaden() = root { root ->
        val directory = File(root, "many").apply { mkdir() }
        repeat(LspNimProfile.MAX_DIRECTORY_ENTRIES + 1) { file(directory, "item-$it") }
        assertFalse(LspNimProfile.projectAvailable(directory))
        assertEquals(directory, LspNimProfile.projectRoot(root, File(directory, "main.nim")))
    }

    @Test fun availabilityChangesDoNotChangeStableSelectionIdentity() = root { root ->
        val selected = File(root, "p").apply { mkdir() }
        val identity = LspNimProfile.selectionIdentity(selected)
        assertFalse(LspNimProfile.projectAvailable(selected))
        file(selected, "a.nimble")
        assertTrue(LspNimProfile.projectAvailable(selected))
        assertEquals(identity, LspNimProfile.selectionIdentity(selected))
        selected.deleteRecursively()
        assertEquals(identity, LspNimProfile.selectionIdentity(selected))
        assertFalse(LspNimProfile.projectAvailable(selected))
    }

    @Test fun immutableConfigurationHasNoToolSelectionOverrideOrExecutionFeatures() {
        val config = LspNimProfile.configuration()
        assertEquals(JsonPrimitive(""), config["nimsuggestPath"])
        assertEquals(JsonPrimitive(1), config["maxNimsuggestProcesses"])
        listOf("useNimCheck", "useNimTrack", "nimExpandArc", "nimExpandMacro", "formatOnSave", "logNimsuggest", "autoRestart")
            .forEach { assertEquals(JsonPrimitive(false), config[it]) }
        assertEquals(JsonArray(emptyList()), config["projectMapping"])
        assertEquals(JsonArray(emptyList()), config["workingDirectoryMapping"])
        assertTrue(config["inlayHints"]!!.jsonObject.values.all { it.jsonObject["enable"] == JsonPrimitive(false) })
        assertEquals(config, LspNimProfile.configuration())
        assertTrue(LspNimProfile.initializationOptions().isEmpty())
    }

    @Test fun configurationRepliesPreserveOrderAndAdmitScopes() {
        val params = Json.parseToJsonElement("""{"items":[{"section":"other"},{"section":"nim","scopeUri":"file:///root/a.nim"},{"section":"nim"}]}""")
        val scopes = mutableListOf<String>()
        val reply = LspNimProfile.configurationReply(params) { scopes += it; it == "file:///root/a.nim" }
        assertEquals(listOf("file:///root/a.nim"), scopes)
        assertEquals(JsonNull, reply[0])
        assertEquals(LspNimProfile.configuration(), reply[1])
        assertEquals(reply[1], reply[2])
        rejected { LspNimProfile.configurationReply(params) { false } }
    }

    @Test fun malformedAndOversizedCallbacksFailClosed() {
        listOf("null", "{}", "{\"items\":null}", "{\"items\":[1]}", "{\"items\":[{\"section\":42}]}",
            "{\"items\":[{\"section\":\"nim\",\"scopeUri\":[]}]}" )
            .forEach { bad -> rejected { LspNimProfile.configurationReply(Json.parseToJsonElement(bad)) { true } } }
        val large = buildJsonObject { put("items", JsonArray(List(33) { buildJsonObject { put("section", "nim") } })) }
        rejected { LspNimProfile.configurationReply(large) { true } }
        val scope = buildJsonObject { put("items", buildJsonArray { add(buildJsonObject { put("section", "nim"); put("scopeUri", "x".repeat(4097)) }) }) }
        rejected { LspNimProfile.configurationReply(scope) { true } }
    }

    @Test fun clientCapabilitiesAreNarrowAndSignatureTextOnly() {
        val caps = LspNimProfile.clientCapabilities(LspProtocolContract.clientCapabilities())
        assertEquals(JsonPrimitive(false), caps["window"]!!.jsonObject["workDoneProgress"])
        assertEquals(JsonPrimitive(true), caps["workspace"]!!.jsonObject["configuration"])
        assertEquals(JsonPrimitive(false), caps["workspace"]!!.jsonObject["workspaceFolders"])
        val text = caps["textDocument"]!!.jsonObject
        assertEquals(JsonPrimitive(false), text["synchronization"]!!.jsonObject["didSave"])
        assertEquals(JsonPrimitive(false), text["completion"]!!.jsonObject["completionItem"]!!.jsonObject["snippetSupport"])
        assertEquals(JsonPrimitive(false), text["signatureHelp"]!!.jsonObject["signatureInformation"]!!.jsonObject["activeParameterSupport"])
        assertEquals(JsonPrimitive(false), text["rename"]!!.jsonObject["prepareSupport"])
        listOf("inlayHint", "semanticTokens", "formatting", "callHierarchy", "implementation").forEach { assertFalse(text.containsKey(it)) }
    }

    private fun native(): JsonObject = Json.parseToJsonElement("""{"capabilities":{
        "textDocumentSync":{"openClose":true,"change":1,"willSave":false,"willSaveWaitUntil":true,"save":{"includeText":true}},
        "hoverProvider":true,"completionProvider":{"triggerCharacters":["."],"resolveProvider":false},
        "signatureHelpProvider":{"triggerCharacters":["(",","]},"definitionProvider":true,"referencesProvider":true,
        "documentSymbolProvider":true,"workspaceSymbolProvider":true,"renameProvider":true,
        "inlayHintProvider":{"resolveProvider":false},"codeActionProvider":true,"documentFormattingProvider":true
    }}""").jsonObject

    @Test fun authenticNativeContractIsAcceptedButUnsafeFeaturesAndSaveAreMasked() {
        val result = native()
        LspNimProfile.requireInitializeContract(result)
        val bounded = LspNimProfile.supportedCapabilities(LspNegotiatedCapabilities.fromInitialize(result))
        assertTrue(bounded.hover && bounded.completion && bounded.signatureHelp && bounded.rename && bounded.workspaceSymbol)
        assertFalse(bounded.sync.save || bounded.sync.saveIncludeText || bounded.inlayHint || bounded.codeAction || bounded.formatting || bounded.semanticTokens)
    }

    @Test fun incompatibleNativeContractIsRejected() {
        fun changed(key: String, value: JsonElement) = JsonObject(native() + ("capabilities" to JsonObject(native()["capabilities"]!!.jsonObject + (key to value))))
        listOf(
            changed("textDocumentSync", JsonPrimitive(1)),
            changed("completionProvider", buildJsonObject { put("triggerCharacters", buildJsonArray { add(".") }); put("resolveProvider", true) }),
            changed("renameProvider", buildJsonObject { put("prepareProvider", true) }),
            changed("positionEncoding", JsonPrimitive("utf-8")),
            changed("definitionProvider", JsonPrimitive(false)),
        ).forEach { rejected { LspNimProfile.requireInitializeContract(it) } }
    }

    @Test fun disclosuresNameTheFixedPairUnknownGraphAndDeferredRuntime() {
        assertTrue(LspNimProfile.APPROVAL_EXPLANATION.contains("2.2.12"))
        assertTrue(LspNimProfile.APPROVAL_EXPLANATION.contains("graph compatibility remains unknown"))
        assertTrue(LspNimProfile.INSTALLATION_DISCLOSURE.contains("three explicit"))
        assertTrue(LspNimProfile.FEATURE_NOTE.contains("unverified"))
        assertTrue(LspNimProfile.PARTIAL_RESULT_NOTE.contains("omitted"))
    }
}
