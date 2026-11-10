package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspClojureProfileTest {
    private val spec = LanguageServerRegistry.builtIns.single { it.id == "clojure-lsp" }

    @Test fun exactLowercaseRoutesShareOneFamilyWithoutAddingDialectRunners() {
        assertEquals(setOf("clj", "cljs", "cljc", "edn", "bb", "cljd", "clj_kondo"), spec.extensions)
        assertEquals(setOf("clojure"), spec.languageIds)
        assertEquals(listOf(listOf("clojure-lsp")), spec.commands)
        for (extension in spec.extensions) {
            val path = "src/example.$extension"
            assertEquals(listOf("clojure-lsp"), LanguageServerRegistry.forPath(path).map { it.id })
            assertEquals("clojure", LanguageServerRegistry.protocolLanguageId(path))
            assertEquals("clojure", LanguageRegistry.forFile(path)?.id)
            assertTrue(LanguageServerRegistry.forPath(path.uppercase()).none { it.id == spec.id })
            assertEquals("clojure", LanguageRegistry.forFile(path.uppercase())?.id)
            if (extension == "clj") {
                assertEquals(listOf(listOf("clojure", path)), LanguageRegistry.runPlanFor(path)?.steps)
                assertNotNull(LanguageRegistry.runPlanFor(path.uppercase()))
            } else {
                assertNull(path, LanguageRegistry.runPlanFor(path))
                assertNull(path.uppercase(), LanguageRegistry.runPlanFor(path.uppercase()))
            }
        }
        for (path in listOf("sample.clj.bak", "sample.clj/", "clj", "sample.clj-kondo", "sample.cljsx"))
            assertFalse(path, LanguageServerRegistry.forPath(path).any { it.id == spec.id })
        assertEquals(55, LanguageRegistry.all.size)
        assertEquals(46, LanguageServerRegistry.builtIns.size)
        assertNotNull(LanguageRegistry.runPlanFor("main.py"))
        assertNull(LanguageRegistry.runPlanFor("build.zon"))
    }

    @Test fun oldNineteenFamiliesRetainTheirWorkspaceAndInitializationBehavior() = workspace { root ->
        val original = LegacyLspCatalogFixture.workspaceProfiles()
        assertEquals(19, original.size)
        val doc = File(root, "nested/main.py").apply { parentFile.mkdirs() }
        File(doc.parentFile, ".git").mkdir()
        for (old in original) {
            assertEquals(root, LspServerProfiles.projectRoot(old, root, doc))
            assertTrue(LspServerProfiles.initializationOptions(old, root).isEmpty())
            assertEquals(LspProtocolContract.clientCapabilities(), LspServerProfiles.clientCapabilities(old))
        }
    }

    @Test fun nearestTypedProjectMarkerWinsAndVcsNeverOverridesWorkspaceFallback() = workspace { root ->
        val nested = File(root, "outer/nested").apply { mkdirs() }
        val doc = File(nested, "a.clj")
        File(nested, ".git").mkdir()
        assertEquals(root, LspServerProfiles.projectRoot(spec, root, doc))
        File(root, "deps.edn").writeText("not parsed: #=(arbitrary)")
        File(nested.parentFile, "project.clj").writeText("not evaluated")
        assertEquals(nested.parentFile, LspServerProfiles.projectRoot(spec, root, doc))
        for (marker in listOf("deps.edn", "project.clj", "bb.edn", "build.boot", "shadow-cljs.edn", "squint.edn")) {
            File(nested, marker).writeText("")
            assertEquals(marker, nested, LspServerProfiles.projectRoot(spec, root, doc))
            File(nested, marker).delete()
        }
        File(nested, ".lsp").mkdir()
        File(nested, ".lsp/config.edn").writeText("")
        assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc))
    }

    @Test fun symlinkAndWrongTypeMarkersAreIgnoredIncludingLspParent() = workspace { root ->
        val nested = File(root, "nested").apply { mkdir() }
        val doc = File(nested, "a.clj")
        File(nested, "deps.edn").mkdir()
        File(root, "actual.edn").writeText("{}")
        Files.createSymbolicLink(File(nested, "bb.edn").toPath(), File(root, "actual.edn").toPath())
        val config = File(root, "config").apply { mkdir() }
        File(config, "config.edn").writeText("{}")
        Files.createSymbolicLink(File(nested, ".lsp").toPath(), config.toPath())
        assertFalse(LspClojureProfile.projectMarker(nested))
        assertTrue(LspClojureProfile.toolHints(nested).isEmpty())
        assertEquals(root, LspServerProfiles.projectRoot(spec, root, doc))
        File(nested, ".lsp").delete()
        File(nested, ".lsp").mkdir()
        Files.createSymbolicLink(File(nested, ".lsp/config.edn").toPath(), File(root, "actual.edn").toPath())
        assertFalse(LspClojureProfile.projectMarker(nested))
    }

    @Test fun missingParentDoesNotBorrowAncestorAndOutsideWorkspaceIsRejected() = workspace { root ->
        File(root, "deps.edn").writeText("{}")
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "bb.edn").writeText("{}")
        val doc = File(nested, "a.clj")
        assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc))
        assertTrue(PathSecurity.deleteTreeNoFollow(nested))
        val absent = LspServerProfiles.projectRoot(spec, root, doc)
        assertEquals(nested, absent)
        assertFalse(LspServerProfiles.projectAvailable(spec, absent))
        assertTrue(LspServerProfiles.unavailableProjectMessage(spec).contains("Clojure"))
        assertTrue(runCatching { LspServerProfiles.projectRoot(spec, root, File(root.parentFile, "outside.clj")) }.isFailure)
    }

    @Test fun customRoutesNeedTrustButNeverInheritBuiltinAdapters() {
        val routes = listOf(
            spec.copy(source = "extension"),
            LanguageServerSpec("custom-language", setOf("clojure"), emptySet(), listOf(listOf("other")), "extension"),
            LanguageServerSpec("custom-extension", emptySet(), setOf("cljc"), listOf(listOf("other")), "extension"),
            LanguageServerSpec("custom-filename", emptySet(), emptySet(), listOf(listOf("other")), "extension", setOf("special.edn")),
        )
        for (route in routes) {
            assertFalse(LspServerProfiles.isClojure(route))
            assertTrue(LspServerProfiles.requiresProjectAnalysis(route, "special.edn"))
            assertFalse(LspServerProfiles.hasProjectRoots(route))
            assertFalse(LspServerProfiles.refreshAfterPersistedMutation(route))
            assertFalse(LspServerProfiles.analysisConfiguration(route).contains(LspClojureProfile.IDENTITY))
            assertEquals(LspClojureProfile.INSTALLATION_DISCLOSURE, LspServerProfiles.installationDisclosure(route, "special.edn"))
            val copy = LspServerProfiles.approvalExplanation(route, path = "special.edn")
            assertTrue(copy.contains("custom Clojure route"))
            assertTrue(copy.contains("credentials"))
            assertTrue(copy.contains("local guest temporary log"))
            assertTrue(copy.contains("download dependencies"))
        }
        assertNull(LspServerProfiles.installationDisclosure(LanguageServerRegistry.builtIns.first { it.id == "make-ls" }))
    }

    @Test fun allToolHintsAndTruthfulResidueDependencyReadinessCopyArePreserved() = workspace { root ->
        val markers = listOf("deps.edn", "project.clj", "bb.edn", "build.boot", "shadow-cljs.edn", "squint.edn")
        markers.forEach { File(root, it).writeText("") }
        val hints = LspClojureProfile.toolHints(root)
        assertEquals(6, hints.size)
        for (marker in markers) assertTrue(hints.any { it.startsWith(marker + ":") })
        val explanation = LspServerProfiles.approvalExplanation(spec, root)
        for (marker in markers) assertTrue(explanation.contains(marker))
        for (term in listOf("credentials", "outside this folder", "network", "stub generation", "deletes/replaces", "telemetry", "temporary log", "project-specs", "defaults, not a sandbox"))
            assertTrue(term, explanation.contains(term))
        assertTrue(LspServerProfiles.enabledNote(spec).contains("Preparing"))
        val note = requireNotNull(LspServerProfiles.optionalToolNote(spec))
        for (term in listOf("does not certify", "unversioned and may lag", "tools.deps", "jar:file:", "zipfile:", "not navigable", "Command-backed", "restart/reopen"))
            assertTrue(term, note.contains(term))
    }

    @Test fun consentIdentityBindsStablePoliciesButNotProjectContentOrFreshness() = workspace { root ->
        val before = LspServerProfiles.analysisConfiguration(spec, root, root)
        File(root, "deps.edn").writeText("{:deps {}}"); File(root, "bb.edn").writeText("{}")
        assertEquals(before, LspServerProfiles.analysisConfiguration(spec, root, root))
        for (term in listOf(LspClojureProfile.IDENTITY, LspClojureProfile.ROOT_POLICY, LspClojureProfile.READINESS_POLICY, LspClojureProfile.LAUNCH_POLICY_REVISION, "text-document-sync-kind", "edit-only", "sentinel"))
            assertTrue(term, before.contains(term))
        assertNotEquals(before, LspServerProfiles.analysisConfiguration(spec.copy(commands = listOf(listOf("other"))), root, root))
        assertTrue(LspServerProfiles.refreshAfterPersistedMutation(spec))
        assertEquals(300_000L, LspClojureProfile.INITIALIZE_TIMEOUT_MS)
        assertEquals(330_000L, LspClojureProfile.ATTEMPT_TIMEOUT_MS)
        assertEquals(15_000L, LspClojureProfile.REPLAY_TIMEOUT_MS)
    }

    @Test fun fullSyncDefaultsPreserveProjectAnalysisAndCapabilitiesOmitUnsupportedMembers() = workspace { root ->
        assertEquals(Json.parseToJsonElement("""{"text-document-sync-kind":"full"}"""), LspServerProfiles.initializationOptions(spec, root))
        assertNull(LspServerProfiles.configurationAfterInitialize(spec, null))
        val base = LspProtocolContract.clientCapabilities()
        val injected = JsonObject(base + mapOf(
            "workspace" to JsonObject(base.getValue("workspace").jsonObject + ("didChangeWatchedFiles" to buildJsonObject { put("dynamicRegistration", false) })),
            "window" to buildJsonObject { put("showDocument", buildJsonObject { put("support", false) }); put("workDoneProgress", false) },
        ))
        for (capabilities in listOf(LspServerProfiles.clientCapabilities(spec), LspClojureProfile.clientCapabilities(injected))) {
            val workspace = capabilities.getValue("workspace").jsonObject
            assertFalse(workspace.containsKey("didChangeWatchedFiles"))
            assertFalse(workspace.containsKey("didChangeConfiguration"))
            assertEquals(JsonPrimitive(false), workspace["configuration"])
            assertEquals(JsonPrimitive(false), workspace["applyEdit"])
            assertFalse((capabilities["window"] as? JsonObject)?.containsKey("showDocument") == true)
            assertFalse(capabilities.getValue("textDocument").jsonObject.getValue("codeAction").jsonObject.containsKey("resolveSupport"))
            assertFalse(capabilities.getValue("textDocument").jsonObject.getValue("completion").jsonObject.getValue("completionItem").jsonObject.containsKey("resolveSupport"))
            assertEquals(JsonArray(listOf(JsonPrimitive("utf-16"))), capabilities.getValue("general").jsonObject["positionEncodings"])
        }
        assertTrue(injected.getValue("workspace").jsonObject.containsKey("didChangeWatchedFiles"))
        assertEquals(base, LspServerProfiles.clientCapabilities(spec.copy(source = "extension")))
        val native = LspNegotiatedCapabilities.fromInitialize(Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":{"openClose":true,"change":1,"save":{"includeText":true}},"hoverProvider":true,"documentFormattingProvider":true,"completionProvider":{}}}"""))
        assertTrue(native.sync.openClose); assertTrue(native.sync.saveIncludeText)
        assertEquals(LspTextSyncPolicy.ChangeKind.FULL, native.sync.change)
        assertTrue(native.hover); assertTrue(native.formatting); assertTrue(native.completion)
    }

    @Test fun exactFormattingSentinelUsesTrueCapturedUtf16EofIncludingEmptyAndCrlf() {
        val samples = mapOf("" to (0 to 0), "abc" to (0 to 3), "😀" to (0 to 2), "a\r\n😀" to (1 to 2), "a\r\n" to (1 to 0), "\n\n" to (2 to 0), "x\r" to (1 to 0))
        for ((text, expected) in samples) {
            val normalized = LspClojureProfile.normalizeFormatting(sentinel(), text)
            val end = normalized.single().jsonObject.getValue("range").jsonObject.getValue("end").jsonObject
            assertEquals(expected.first, end.getValue("line").jsonPrimitive.int)
            assertEquals(expected.second, end.getValue("character").jsonPrimitive.int)
            val edit = TextRangeEdit(1, 1, expected.first + 1, expected.second + 1, "formatted\n")
            assertEquals("formatted\n", TextEditApplier.apply(text, listOf(edit)))
        }
    }

    @Test fun nearMultipleMalformedAndServerInitiatedShapesAreNeverNormalized() {
        val edit = sentinel().single().jsonObject
        val cases = listOf(
            JsonArray(emptyList()), JsonArray(listOf(edit, edit)),
            sentinel(999998, 999998), sentinel(999999, 999998), sentinel(999998, 999999),
            Json.parseToJsonElement("""[{"range":{"start":{"line":"0","character":0},"end":{"line":999999,"character":999999}},"newText":"x"}]""").jsonArray,
            Json.parseToJsonElement("""[{"range":{"start":{"line":0.0,"character":0},"end":{"line":999999,"character":999999}},"newText":"x"}]""").jsonArray,
            JsonArray(listOf(JsonObject(edit + ("command" to JsonPrimitive("unsafe"))))),
            JsonArray(listOf(JsonObject(edit + ("newText" to JsonPrimitive(1))))),
            JsonArray(listOf(JsonObject(edit + ("newText" to JsonNull)))),
            JsonArray(listOf(JsonPrimitive("bad"))),
            sentinel(replacement = "x".repeat(200_001)),
            sentinel(replacement = "\uD800"), sentinel(replacement = "\uDC00"),
        )
        for (result in cases) assertSame(LspClojureProfile.normalizeFormatting(result, "current"), result)
        val original = sentinel()
        assertSame(original, LspClojureProfile.normalizeFormatting(original, "\uD800"))
        val atLimit = sentinel(replacement = "x".repeat(200_000))
        assertNotEquals(atLimit, LspClojureProfile.normalizeFormatting(atLimit, "current"))
    }

    @Test fun ordinaryFormattingStaysOnTheExistingStrictValidatorPath() {
        val normal = Json.parseToJsonElement("""[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"newText":"b"}]""").jsonArray
        assertSame(normal, LspClojureProfile.normalizeFormatting(normal, "a"))
        assertTrue(runCatching { TextEditApplier.apply("a", listOf(TextRangeEdit(1, 1, 1_000_000, 1_000_000, "x"))) }.isFailure)
    }

    private fun sentinel(endLine: Int = 999999, endCharacter: Int = 999999, replacement: String = "formatted\n"): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("range", buildJsonObject {
                put("start", buildJsonObject { put("line", 0); put("character", 0) })
                put("end", buildJsonObject { put("line", endLine); put("character", endCharacter) })
            })
            put("newText", replacement)
        })
    }

    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("clojure-profile-").toFile().canonicalFile
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
