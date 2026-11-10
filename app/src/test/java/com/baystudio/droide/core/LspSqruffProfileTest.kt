package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspSqruffProfileTest {
    private val spec = LanguageServerRegistry.builtIns.single { it.id == "sqruff" }
    private fun wire(text: String) = requireNotNull(LspSqruffProfile.wireSnapshot(text))
    private fun pos(line: Int, column: Int) = buildJsonObject { put("line", line); put("character", column) }
    private fun format(text: String): JsonArray {
        val lines = if (text.isEmpty()) 0 else text.split('\n').size - if (text.endsWith('\n')) 1 else 0
        val scalars = text.toCharArray().count { !it.isLowSurrogate() }
        return buildJsonArray { add(buildJsonObject {
            put("range", buildJsonObject { put("start", pos(0, 0)); put("end", pos(lines, scalars)) }); put("newText", text)
        }) }
    }
    private fun warning(line: Int, scalar: Int): JsonObject = buildJsonObject {
        put("range", buildJsonObject { put("start", pos(line, scalar)); put("end", pos(line, scalar)) })
        put("severity", 2); put("source", "sqruff"); put("code", "LT01"); put("message", "Native warning")
    }
    private fun tokens(vararg data: Int): JsonObject = buildJsonObject { put("data", JsonArray(data.map(::JsonPrimitive))) }
    private fun initialize(): JsonObject = buildJsonObject { put("capabilities", buildJsonObject {
        put("textDocumentSync", 1); put("documentFormattingProvider", true)
        put("semanticTokensProvider", buildJsonObject {
            put("full", true); put("range", false)
            put("legend", buildJsonObject {
                put("tokenTypes", JsonArray(LspSqruffProfile.tokenTypes.map(::JsonPrimitive))); put("tokenModifiers", buildJsonArray {})
            })
        })
        for (key in listOf("hoverProvider", "completionProvider", "signatureHelpProvider", "inlayHintProvider", "definitionProvider",
            "referencesProvider", "implementationProvider", "documentSymbolProvider", "workspaceSymbolProvider", "codeActionProvider",
            "callHierarchyProvider", "renameProvider", "diagnosticProvider")) put(key, true)
    }) }

    @Test fun exactSqlRoutePreservesCaseInsensitiveEditorFamilyAndOverrides() {
        assertEquals(listOf(listOf("sqruff", "lsp")), spec.commands)
        assertEquals(setOf("sql"), spec.languageIds); assertEquals(setOf("sql"), spec.extensions)
        for (path in listOf("query.sql", "Query.SQL", "file.Sql", ".sql", "a\\b.SQL")) {
            assertEquals(path, listOf(spec), LanguageServerRegistry.forPath(path))
            assertEquals("sql", LanguageServerRegistry.protocolLanguageId(path))
        }
        for (path in listOf("sql", "query.sql/", "query.sql.bak", "query.sqlite", "query.psql"))
            assertFalse(path, LanguageServerRegistry.forPath(path).contains(spec))
        val custom = spec.copy(source = "custom", commands = listOf(listOf("other-sql-lsp")))
        assertEquals(listOf(custom), LanguageServerRegistry.forPath("query.sql", listOf(custom)))
        assertFalse(LspServerProfiles.isSqruff(custom))
        assertFalse(LspServerProfiles.isUnverifiedSqruff(custom))
        assertTrue(LspServerProfiles.isUnverifiedSqruff(custom.copy(commands = listOf(listOf("/usr/bin/sqruff", "lsp")))))
        assertFalse(LspServerProfiles.isUnverifiedSqruff(spec))
        val unusual = LanguageServerSpec("custom-name", emptySet(), setOf("odd"), listOf(listOf("/opt/sqruff", "lsp")), "custom")
        assertTrue(LspServerProfiles.requiresProjectAnalysis(unusual, "file.odd"))
        assertTrue(LspServerProfiles.optionalToolNote(custom.copy(commands = listOf(listOf("sqruff", "lsp"))))!!.contains("formatting is unavailable"))
        assertFalse(LspServerProfiles.isSqruff(spec.copy(commands = listOf(listOf("sqruff", "lsp", "extra")))))
        assertFalse(LspServerProfiles.isSqruff(spec.copy(languageIds = setOf("sql", "other"))))
        assertEquals(46, LanguageServerRegistry.builtIns.size)
        ManagedLanguageServerPlan.validate()
        val plan = requireNotNull(ManagedLanguageServerPlan.forServer("sqruff"))
        assertEquals("lsp.sqruff", plan.packageFamilyId); assertEquals("0.41.0", plan.pinnedVersion)
        assertEquals(LanguageServerRuntimeKind.LINUX_ARM64_PROOT, plan.runtime)
        assertTrue(plan.requiredCommands.isEmpty()); assertTrue(plan.runtimeRequirements.isEmpty())
        assertTrue(plan.notes.contains("Node is private installer-health only"))
    }

    @Test fun allThirtyPriorBuiltInCommandsAndRoutesRemainPresent() {
        val routes = mapOf("main.py" to listOf("python-pyright", "python-pylsp"), "main.ts" to listOf("typescript"),
            "Main.kt" to listOf("kotlin"), "main.go" to listOf("gopls"), "main.rs" to listOf("rust-analyzer"), "main.cpp" to listOf("clangd"),
            "Main.java" to listOf("jdtls"), "main.rb" to listOf("ruby-lsp"), "main.php" to listOf("intelephense"), "main.lua" to listOf("lua-language-server"),
            "main.sh" to listOf("bash-language-server"), "main.yaml" to listOf("yaml-language-server"), "main.json" to listOf("vscode-json-language-server"),
            "main.html" to listOf("vscode-html-language-server"), "main.css" to listOf("vscode-css-language-server"), "main.swift" to listOf("sourcekit-lsp"),
            "main.dart" to listOf("dart"), "Dockerfile" to listOf("dockerfile-language-server"), "main.vim" to listOf("vim-language-server"),
            "schema.prisma" to listOf("prisma-language-server"), "main.clj" to listOf("clojure-lsp"), "Makefile" to listOf("make-ls"),
            "main.xml" to listOf("lemminx"), "main.zig" to listOf("zls"), "main.proto" to listOf("buf"), "main.tf" to listOf("terraform-ls"),
            "main.tex" to listOf("texlab"), "main.md" to listOf("marksman"), "main.pl" to listOf("perlnavigator"))
        for ((path, ids) in routes) assertEquals(path, ids, LanguageServerRegistry.forPath(path).map { it.id })
        assertTrue(LanguageServerRegistry.builtIns.map { it.id }.containsAll(routes.values.flatten()))
    }

    @Test fun everyCustomSqlEntryPointRequiresConsentWithoutManagedRepairs() {
        val routes = listOf(spec, spec.copy(source = "custom"), spec.copy(id = "other", source = "custom"),
            LanguageServerSpec("odd", emptySet(), setOf("sql"), listOf(listOf("custom")), "custom"),
            LanguageServerSpec("odd", setOf("sql"), emptySet(), listOf(listOf("custom")), "custom"),
            LanguageServerSpec("odd", emptySet(), emptySet(), listOf(listOf("custom")), "custom"))
        routes.forEach { route ->
            assertTrue(LspServerProfiles.requiresProjectAnalysis(route, "nested/query.SQL"))
            assertTrue(LspServerProfiles.approvalExplanation(route, path = "query.sql").contains("outside this selected root"))
            assertNotNull(LspServerProfiles.installationDisclosure(route, "query.sql"))
        }
        assertTrue(LspServerProfiles.refreshAfterPersistedMutation(spec))
        assertFalse(LspServerProfiles.refreshAfterPersistedMutation(spec.copy(source = "custom")))
        assertFalse(LspServerProfiles.hasProjectRoots(spec.copy(source = "custom")))
        assertTrue(LspServerProfiles.approvalExplanation(spec.copy(source = "custom")).contains("do not apply"))
    }

    @Test fun rootSearchIsConfigFirstThenRealVcsThenParent() = workspace { root ->
        val outer = File(root, "outer").apply { mkdir() }; val nested = File(outer, "nested").apply { mkdir() }
        val document = File(nested, "query.sql")
        assertEquals(nested, LspServerProfiles.projectRoot(spec, root, document))
        File(outer, ".git").mkdir(); assertEquals(outer, LspServerProfiles.projectRoot(spec, root, document))
        File(nested, ".git").writeText("a .git file is not a VCS directory")
        assertEquals(outer, LspServerProfiles.projectRoot(spec, root, document))
        File(root, "sqruff.toml").writeText("unparsed root config")
        assertEquals(root, LspServerProfiles.projectRoot(spec, root, document))
        File(outer, ".sqruff").writeText("unparsed closer config")
        assertEquals(outer, LspServerProfiles.projectRoot(spec, root, document))
        assertEquals("config", LspSqruffProfile.selectionIdentity(outer)["reason"]?.jsonPrimitive?.content)
    }

    @Test fun allTypedConfigMarkersHaveStableContentIndependentGrantIdentity() = workspace { root ->
        val nested = File(root, "nested").apply { mkdir() }; val document = File(nested, "query.sql")
        for (name in listOf(".sqlfluff", ".sqruff", ".sqruff.ini", "pyproject.toml", "sqruff.toml")) {
            val marker = File(nested, name); marker.writeText("original config never interpreted")
            assertEquals(nested, LspServerProfiles.projectRoot(spec, root, document)); assertTrue(LspServerProfiles.projectAvailable(spec, nested))
            val identity = LspServerProfiles.analysisConfiguration(spec, root, nested)
            marker.writeText("changed config also never interpreted")
            assertEquals(identity, LspServerProfiles.analysisConfiguration(spec, root, nested))
            assertTrue(marker.delete()); assertNotEquals(identity, LspServerProfiles.analysisConfiguration(spec, root, nested))
        }
    }

    @Test fun invalidConfigTypesAndMissingParentsAreBarriers() = workspace { root ->
        File(root, ".sqruff").writeText("do not borrow this ancestor grant")
        val nested = File(root, "nested").apply { mkdir() }; val document = File(nested, "query.sql")
        for (name in listOf(".sqlfluff", ".sqruff", ".sqruff.ini", "pyproject.toml", "sqruff.toml")) {
            val marker = File(nested, name); marker.mkdir()
            assertEquals(nested, LspServerProfiles.projectRoot(spec, root, document)); assertFalse(LspServerProfiles.projectAvailable(spec, nested))
            assertTrue(marker.delete()); Files.createSymbolicLink(marker.toPath(), File(root, ".sqruff").toPath())
            assertEquals(nested, LspServerProfiles.projectRoot(spec, root, document)); assertFalse(LspServerProfiles.projectAvailable(spec, nested))
            Files.delete(marker.toPath()); Files.createSymbolicLink(marker.toPath(), File(root, "missing").toPath())
            assertEquals(nested, LspServerProfiles.projectRoot(spec, root, document)); assertFalse(LspServerProfiles.projectAvailable(spec, nested))
            Files.delete(marker.toPath())
        }
        val missing = File(nested, "missing"); assertEquals(missing, LspServerProfiles.projectRoot(spec, root, File(missing, "q.sql")))
        assertFalse(LspServerProfiles.projectAvailable(spec, missing)); missing.writeText("not a directory")
        assertEquals(missing, LspServerProfiles.projectRoot(spec, root, File(missing, "q.sql")))
        assertFalse(LspServerProfiles.projectAvailable(spec, missing))
        assertTrue(LspServerProfiles.unavailableProjectMessage(spec).contains("configuration boundary"))
    }

    @Test fun invalidMarkerBeatsAValidSiblingAndRealVcsMarkersBindTypesNotContents() = workspace { root ->
        val nested = File(root, "nested").apply { mkdir() }; val doc = File(nested, "q.sql")
        File(nested, ".sqruff").writeText("valid type only")
        File(nested, "sqruff.toml").mkdir()
        assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc)); assertFalse(LspServerProfiles.projectAvailable(spec, nested))
        File(nested, "sqruff.toml").delete(); File(nested, ".sqruff").delete()
        for (name in listOf(".git", ".hg", ".svn", ".jj")) {
            val marker = File(root, name).apply { mkdir() }
            assertEquals(root, LspServerProfiles.projectRoot(spec, root, doc))
            val before = LspSqruffProfile.selectionIdentity(root)
            File(marker, "head").writeText("metadata is not a source of grant identity")
            assertEquals(before, LspSqruffProfile.selectionIdentity(root))
            marker.deleteRecursively()
        }
    }

    @Test fun escapedDocumentCannotSelectOrBorrowAProjectRoot() = workspace { root ->
        assertThrows(IllegalArgumentException::class.java) { LspServerProfiles.projectRoot(spec, root, File(root.parentFile, "outside.sql")) }
    }

    @Test fun wireViewPreservesOriginalAndRejectsLoneCrMalformedUtf16AndOverCap() {
        for (text in listOf("", "a", "a\n", "\n\n", "a\r\nb\r\n", "😀x\r\ne\u0301\t")) {
            val snapshot = wire(text); assertEquals(text, snapshot.originalText); assertEquals(text.replace("\r\n", "\n"), snapshot.wireText)
        }
        for (text in listOf("\r", "a\rb", "\r\n\r", "\ud800", "\udc00", "x\ud800y", "\udc00\ud800"))
            assertNull(text, LspSqruffProfile.wireSnapshot(text))
        assertNotNull(LspSqruffProfile.wireSnapshot("a".repeat(LspSqruffProfile.MAX_TEXT_CHARS)))
        assertNull(LspSqruffProfile.wireSnapshot("a".repeat(LspSqruffProfile.MAX_TEXT_CHARS + 1)))
        assertNotNull(LspSqruffProfile.wireSnapshot("😀".repeat(LspSqruffProfile.MAX_TEXT_CHARS / 2)))
    }

    @Test fun diagnosticScalarConversionUsesOneImmutableWireAndPreservesRealFields() {
        val original = "😀x\r\ne\u0301\t世界\r\n"
        val snapshot = wire(original)
        for ((line, scalar, expected) in listOf(Triple(0, 0, 0), Triple(0, 1, 2), Triple(0, 2, 3), Triple(1, 4, 4), Triple(2, 0, 0))) {
            val input = warning(line, scalar)
            val output = requireNotNull(LspSqruffProfile.convertDiagnostics(JsonArray(listOf(input)), snapshot)).single().jsonObject
            assertEquals(pos(line, expected), output["range"]?.jsonObject?.get("start"))
            assertEquals(pos(line, expected), output["range"]?.jsonObject?.get("end"))
            for (key in listOf("severity", "source", "code", "message")) assertEquals(input[key], output[key])
        }
        assertEquals(original, snapshot.originalText)
        assertEquals(JsonArray(emptyList()), LspSqruffProfile.convertDiagnostics(JsonArray(emptyList()), snapshot))
        assertNotNull(LspSqruffProfile.convertDiagnostics(JsonArray(listOf(warning(0, 0))), wire("")))
    }

    @Test fun malformedNonzeroNumericStringFloatAndOutOfBoundsDiagnosticsAreUnavailable() {
        val good = warning(0, 1)
        val malformed = mutableListOf<JsonElement>(JsonNull, JsonPrimitive("warning"), JsonObject(good - "message"), JsonObject(good + ("message" to JsonPrimitive(2))),
            JsonObject(good + ("severity" to JsonPrimitive("2"))), JsonObject(good + ("severity" to JsonPrimitive(2.0))), JsonObject(good + ("source" to JsonPrimitive("custom"))),
            JsonObject(good + ("code" to JsonPrimitive(5))), JsonObject(good + ("data" to JsonPrimitive("extra"))), warning(0, 3), warning(2, 0))
        val range = good.getValue("range").jsonObject
        malformed += JsonObject(good + ("range" to JsonObject(range + ("end" to pos(0, 2)))))
        for (number in listOf(JsonPrimitive("1"), JsonPrimitive(1.0), JsonPrimitive(-1), JsonPrimitive(Int.MAX_VALUE), JsonNull))
            malformed += JsonObject(good + ("range" to buildJsonObject {
                val p = buildJsonObject { put("line", 0); put("character", number) }; put("start", p); put("end", p)
            }))
        for (item in malformed) assertNull(item.toString(), LspSqruffProfile.convertDiagnostics(JsonArray(listOf(item)), wire("😀x\r\n")))
        assertNull(LspSqruffProfile.convertDiagnostics(JsonArray(List(LspSqruffProfile.MAX_DIAGNOSTICS + 1) { good }), wire("ab")))
    }

    @Test fun formattingFormulaMatrixUsesOriginalEofAndUntouchedNativeReplacement() {
        val originals = listOf("", "a", "😀x", "e\u0301", "a\n", "a\r\n", "a\n\n", "a\r\nb", "😀\r\nx\n")
        val replacements = originals + listOf("\r", "a\rb", "\n\n", "a\r\n\r", "\r\r", "\t😀 \u0301\n")
        for (original in originals) for (replacement in replacements) {
            val normalized = requireNotNull(LspSqruffProfile.normalizeFormatting(format(replacement), original)).single().jsonObject
            assertEquals(replacement, normalized["newText"]?.jsonPrimitive?.content)
            val end = pos(original.count { it == '\n' }, original.length - original.lastIndexOf('\n') - 1)
            assertEquals(end, normalized["range"]?.jsonObject?.get("end"))
            assertEquals(pos(0, 0), normalized["range"]?.jsonObject?.get("start"))
        }
    }

    @Test fun formattingRejectsEveryUnrecognizedShapeIncludingPlausibleNearMatch() {
        val good = format("a\n"); val edit = good.single().jsonObject; val range = edit.getValue("range").jsonObject
        val invalid = listOf<JsonElement?>(null, JsonNull, JsonPrimitive("edits"), JsonArray(emptyList()), JsonArray(listOf(edit, edit)),
            JsonArray(listOf(JsonObject(edit + ("annotationId" to JsonPrimitive("extra"))))), JsonArray(listOf(JsonObject(edit - "newText"))),
            JsonArray(listOf(JsonObject(edit + ("newText" to JsonPrimitive(1))))),
            JsonArray(listOf(JsonObject(edit + ("range" to JsonObject(range + ("end" to pos(1, 1))))))),
            JsonArray(listOf(JsonObject(edit + ("range" to JsonObject(range + ("start" to pos(0, 1))))))),
            JsonArray(listOf(JsonObject(edit + ("range" to JsonObject(range + ("extra" to JsonNull)))))),
            JsonArray(listOf(JsonObject(edit + ("newText" to JsonPrimitive("\ud800"))))))
        for (value in invalid) assertNull(value.toString(), LspSqruffProfile.normalizeFormatting(value, "large old document"))
        for (number in listOf(JsonPrimitive("0"), JsonPrimitive(0.0), JsonPrimitive(-1), JsonNull)) {
            val invalidStart = buildJsonObject { put("line", number); put("character", 0) }
            assertNull(LspSqruffProfile.normalizeFormatting(JsonArray(listOf(JsonObject(edit + ("range" to JsonObject(range + ("start" to invalidStart)))))), "old"))
        }
        assertNull(LspSqruffProfile.normalizeFormatting(good, "\ud800")); assertNull(LspSqruffProfile.normalizeFormatting(good, "x\ry"))
        val cap = "x".repeat(LspSqruffProfile.MAX_TEXT_CHARS)
        assertNotNull(LspSqruffProfile.normalizeFormatting(format(cap), cap))
        assertNull(LspSqruffProfile.normalizeFormatting(format(cap + "x"), cap)); assertNull(LspSqruffProfile.normalizeFormatting(format("x"), cap + "x"))
    }

    @Test fun tokensRespectOriginalCrlfLinePositionsUtf16AndExactLegend() {
        val snapshot = wire("😀x\r\ne\u0301\t世界\r\n")
        val result = tokens(0, 0, 2, 1, 0, 0, 2, 1, 7, 0, 1, 0, 2, 7, 0, 0, 3, 2, 7, 0)
        val decoded = requireNotNull(LspSqruffProfile.parseSemanticTokens(result, LspSqruffProfile.legend, snapshot))
        assertEquals(4, decoded.size); assertEquals(2, decoded[1].startCharacter); assertEquals(1, decoded[2].line)
        assertEquals(emptyList<LspSemanticToken>(), LspSqruffProfile.parseSemanticTokens(tokens(), LspSqruffProfile.legend, snapshot))
        assertNull(LspSqruffProfile.parseSemanticTokens(result, LspSqruffProfile.legend.copy(tokenModifiers = listOf("static")), snapshot))
    }

    @Test fun tokensRejectSplitSurrogatesLineCrossingOverlapUnknownBitsAndMalformedRawNumbers() {
        val snapshot = wire("😀x\r\ny\r\n")
        val invalid = listOf(tokens(0, 0, 1, 0, 0), tokens(0, 1, 1, 0, 0), tokens(0, 0, 4, 0, 0), tokens(0, 3, 1, 0, 0),
            tokens(0, 0, 2, 0, 0, 0, 1, 2, 0, 0), tokens(3, 0, 1, 0, 0), tokens(0, 0, 1, 99, 0), tokens(0, 0, 2, 0, 1),
            tokens(0, 0, 0, 0, 0), tokens(0, -1, 2, 0, 0), tokens(0, Int.MAX_VALUE, 2, 0, 0), tokens(1, 0, 1, 0),
            JsonObject(tokens() + ("resultId" to JsonPrimitive("unexpected-native-field"))))
        for (value in invalid) assertNull(value.toString(), LspSqruffProfile.parseSemanticTokens(value, LspSqruffProfile.legend, snapshot))
        for (number in listOf(JsonPrimitive("0"), JsonPrimitive(0.0), JsonNull, JsonPrimitive(true))) {
            val data = JsonArray(listOf(number, JsonPrimitive(0), JsonPrimitive(2), JsonPrimitive(0), JsonPrimitive(0)))
            assertNull(LspSqruffProfile.parseSemanticTokens(buildJsonObject { put("data", data) }, LspSqruffProfile.legend, snapshot))
        }
    }

    @Test fun exactCapabilitiesMaskAllUnsupportedFeaturesAndSaveWithoutChangingGenericParser() {
        val result = initialize(); LspSqruffProfile.requireInitializeContract(result)
        val native = LspNegotiatedCapabilities.fromInitialize(result); assertTrue(native.sync.save)
        val managed = LspSqruffProfile.supportedCapabilities(native, true)
        assertTrue(managed.semanticTokens); assertTrue(managed.formatting); assertFalse(managed.sync.save); assertFalse(managed.sync.saveIncludeText)
        assertEquals(LspTextSyncPolicy.ChangeKind.FULL, managed.sync.change)
        assertFalse(managed.hover || managed.completion || managed.signatureHelp || managed.inlayHint || managed.definition || managed.references ||
            managed.implementation || managed.documentSymbol || managed.workspaceSymbol || managed.codeAction || managed.codeActionResolve ||
            managed.callHierarchy || managed.rename || managed.pullDiagnostics)
        assertFalse(LspSqruffProfile.supportedCapabilities(native, false).formatting)
        assertThrows(IllegalArgumentException::class.java) { LspSqruffProfile.supportedCapabilities(native.copy(sync = native.sync.copy(change = LspTextSyncPolicy.ChangeKind.INCREMENTAL)), true) }
        assertThrows(IllegalArgumentException::class.java) { LspSqruffProfile.supportedCapabilities(native.copy(semanticTokenLegend = LspSemanticTokenLegend()), true) }
        val client = LspServerProfiles.clientCapabilities(spec)
        assertEquals(JsonPrimitive(false), client["workspace"]?.jsonObject?.get("configuration"))
        assertEquals(JsonPrimitive(false), client["textDocument"]?.jsonObject?.get("synchronization")?.jsonObject?.get("didSave"))
        assertEquals(buildJsonObject {}, LspServerProfiles.initializationOptions(spec, File(".")))
        assertNull(LspServerProfiles.configurationAfterInitialize(spec, null))
    }

    @Test fun rawInitializeRejectsCoercionsAndWrongFullTokenShape() {
        val result = initialize(); val caps = result.getValue("capabilities").jsonObject
        for (sync in listOf(JsonPrimitive("1"), JsonPrimitive(1.0), JsonPrimitive(2), JsonNull))
            assertThrows(IllegalArgumentException::class.java) { LspSqruffProfile.requireInitializeContract(JsonObject(result + ("capabilities" to JsonObject(caps + ("textDocumentSync" to sync))))) }
        val provider = caps.getValue("semanticTokensProvider").jsonObject
        for ((key, value) in listOf("full" to JsonPrimitive("true"), "full" to buildJsonObject {}, "range" to JsonPrimitive(true)))
            assertThrows(IllegalArgumentException::class.java) { LspSqruffProfile.requireInitializeContract(JsonObject(result + ("capabilities" to JsonObject(caps + ("semanticTokensProvider" to JsonObject(provider + (key to value))))))) }
    }

    @Test fun disclosuresIncludeCostConfigurationScopeAndUnverifiedPendingLimitations() {
        for (term in listOf("HOME", "ignore", "globs", "inline directives", "outside", "Python", "custom", "ANSI"))
            assertTrue(term, LspSqruffProfile.APPROVAL_EXPLANATION.contains(term))
        for (term in listOf("lint", "semantic", "formatting", "unversioned", "pending", "restart", "CRLF", "lone CR", "database", "dynamic watcher"))
            assertTrue(term, LspSqruffProfile.FEATURE_NOTE.contains(term, ignoreCase = true))
    }

    private fun workspace(body: (File) -> Unit) {
        val root = Files.createTempDirectory("sqruff-profile-").toFile().canonicalFile
        try { body(root) } finally { root.deleteRecursively() }
    }
}
