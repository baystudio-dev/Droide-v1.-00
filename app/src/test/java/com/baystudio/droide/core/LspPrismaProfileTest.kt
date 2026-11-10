package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.regex.Pattern
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspPrismaProfileTest {
    private val spec = LanguageServerRegistry.builtIns.single { it.id == LspPrismaProfile.ID }

    @Test fun exactLegacyRouteAndManagedPlanNeverAddGenerationAliases() {
        assertEquals(setOf("prisma"), spec.languageIds)
        assertEquals(setOf("prisma"), spec.extensions)
        assertEquals(listOf(listOf("prisma-language-server", "--stdio")), spec.commands)
        for (path in listOf("schema.prisma", "nested/schema.prisma", "win\\schema.prisma", ".prisma")) {
            assertEquals(path, listOf(spec), LanguageServerRegistry.forPath(path))
            assertEquals("prisma", LanguageServerRegistry.protocolLanguageId(path))
        }
        for (path in listOf("schema.PRISMA", "schema.Prisma", "schema.prisma.bak", "schema.prisma/", "prisma", "schema.prisma-next", "schema.prisma8"))
            assertFalse(path, LanguageServerRegistry.forPath(path).any { it.id == spec.id })
        assertEquals("prisma", LanguageRegistry.forFile("schema.PRISMA")?.id)
        assertEquals(46, LanguageServerRegistry.builtIns.size)
        ManagedLanguageServerPlan.validate()
        val plan = requireNotNull(ManagedLanguageServerPlan.forServer(spec.id))
        assertEquals("lsp.prisma-language-server", plan.packageFamilyId)
        assertEquals("31.13.0", plan.pinnedVersion)
        assertEquals(LanguageServerRuntimeKind.NODE, plan.runtime)
        assertEquals(setOf("node"), plan.requiredCommands)
        assertEquals(22, plan.runtimeRequirements.single().minimumMajor)
        assertTrue(plan.notes.contains("npm 10"))
        assertTrue(plan.notes.contains("unverified"))
    }

    @Test fun exactDirectiveGrammarIncludesBomAndBacktrackingButNotUnrelatedLeadingComments() {
        val unavailable = listOf("// use prisma-next", "// use prisma-8", "//use prisma-next", "  //  use   prisma-8  ",
            "\uFEFF\r\n// use prisma-next", "// use prisma-next trailing words", "// use prisma-8\ttrailing words",
            "// use prisma-8\r\nmodel A {}", "\u00a0\u1680\u2000// use prisma-next\u2028tail")
        val legacy = listOf("", "model A {}", "// ordinary\n// use prisma-next", "// Use prisma-next", "// use Prisma-8",
            "//\tuse prisma-next", "// use\tprisma-next", "// use prisma-nextx", "// use prisma-80", "// use prisma-8x",
            "/* use prisma-8 */", "// use prisma-6", "\u0085// use prisma-8", "\u180e// use prisma-8", "\u200b// use prisma-8",
            "\uD83D\uDE00// use prisma-next", "// use prisma-8\uD83D\uDE00")
        unavailable.forEach { assertEquals(it, LspPrismaProfile.Generation.NEXT_OR_8, LspPrismaProfile.classify(it)) }
        legacy.forEach { assertEquals(it, LspPrismaProfile.Generation.LEGACY, LspPrismaProfile.classify(it)) }
    }

    @Test fun everyUtf16CodeUnitMatchesTheExplicitEcmaWhitespaceRegexAtFourGrammarPositions() {
        // An independently authored regex oracle with the ECMAScript whitespace set written out; Java's
        // default \\s is intentionally never used.
        val ws = "[\\x09-\\x0d\\x20\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"
        val reference = Pattern.compile("^$ws*// *use +prisma-(?:next|8) *(?=$ws|$)")
        for (code in 0..0xffff) {
            val c = code.toChar()
            for (text in listOf("$c// use prisma-8", "// use prisma-next${c}tail", "//$c" + "use prisma-8", "// use${c}prisma-8")) {
                val expected = if (reference.matcher(text).find()) LspPrismaProfile.Generation.NEXT_OR_8 else LspPrismaProfile.Generation.LEGACY
                assertEquals("code=$code", expected, LspPrismaProfile.classify(text))
            }
        }
    }

    @Test fun configFirstTwoPassChoosesFartherConfigBeforeNearerPackageOrVcs() = workspace { root ->
        val parent = File(root, "outer").apply { mkdir() }
        val nested = File(parent, "nested").apply { mkdir() }
        val doc = File(nested, "schema.prisma")
        assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc))
        File(parent, "package.json").writeText("not parsed")
        assertEquals(parent, LspServerProfiles.projectRoot(spec, root, doc))
        File(nested, ".git").mkdir()
        assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc))
        File(root, "prisma.config.ts").writeText("throw new Error('must never execute')")
        assertEquals(root, LspServerProfiles.projectRoot(spec, root, doc))
        File(parent, "prisma.config.js").writeText("not executed")
        assertEquals(parent, LspServerProfiles.projectRoot(spec, root, doc))
        assertTrue(LspServerProfiles.projectAvailable(spec, parent))
    }

    @Test fun allEighteenSupportedConfigFormsAreRealFilesAndDoNotRun() = workspace { root ->
        val nested = File(root, "nested").apply { mkdir() }
        val doc = File(nested, "schema.prisma")
        File(nested, ".config").mkdir()
        for (stem in listOf("prisma.config", ".config/prisma", ".config/prisma.config")) {
            for (suffix in listOf("js", "ts", "mjs", "cjs", "mts", "cts")) {
                val marker = File(nested, "$stem.$suffix").apply { writeText("unparsed executable configuration") }
                assertEquals(marker.name, nested, LspServerProfiles.projectRoot(spec, root, doc))
                assertTrue(marker.name, LspServerProfiles.projectAvailable(spec, nested))
                assertEquals("$stem.$suffix", LspPrismaProfile.selectionIdentity(nested)["selected"]?.jsonPrimitive?.content)
                assertTrue(marker.delete())
            }
        }
    }

    @Test fun everyOccupiedUnsupportedStemAndWrongTypeIsAnUnavailableBoundary() = workspace { root ->
        File(root, "prisma.config.ts").writeText("trusted ancestor must not be borrowed")
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, ".config").mkdir()
        val doc = File(nested, "schema.prisma")
        for (stem in listOf("prisma.config", ".config/prisma", ".config/prisma.config")) {
            for (suffix in listOf("", ".json", ".jsonc", ".json5", ".yaml", ".yml", ".toml")) {
                val occupied = File(nested, "$stem$suffix").apply { writeText("not parsed") }
                assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc))
                assertFalse(occupied.path, LspServerProfiles.projectAvailable(spec, nested))
                assertTrue(occupied.delete())
            }
            val directory = File(nested, stem).apply { mkdir() }
            File(directory, "index.ts").writeText("not imported")
            assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc))
            assertFalse(LspServerProfiles.projectAvailable(spec, nested))
            assertTrue(PathSecurity.deleteTreeNoFollow(directory))
        }
        val directory = File(nested, "prisma.config.js").apply { mkdir() }
        File(nested, "prisma.config.ts").writeText("later valid candidate cannot mask the invalid one")
        assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc))
        assertFalse(LspServerProfiles.projectAvailable(spec, nested))
        assertTrue(directory.delete())
        File(nested, "prisma.config.toml").writeText("even a later unsupported candidate fails closed")
        assertFalse(LspServerProfiles.projectAvailable(spec, nested))
    }

    @Test fun configSymlinksAndNonDirectoryConfigContainerCannotBorrowAncestor() = workspace { root ->
        val nested = File(root, "nested").apply { mkdir() }
        val doc = File(nested, "schema.prisma")
        val real = File(root, "prisma.config.ts").apply { writeText("not imported") }
        for (name in listOf("prisma.config.js", "prisma.config.ts", "prisma.config.json", "prisma.config")) {
            val link = File(nested, name)
            Files.createSymbolicLink(link.toPath(), real.toPath())
            assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc))
            assertFalse(LspServerProfiles.projectAvailable(spec, nested))
            assertTrue(link.delete())
        }
        val config = File(nested, ".config")
        Files.createSymbolicLink(config.toPath(), File(root, "missing").toPath())
        assertFalse(LspServerProfiles.projectAvailable(spec, nested))
        assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc))
        assertTrue(config.delete())
        config.writeText("not a directory")
        assertFalse(LspServerProfiles.projectAvailable(spec, nested))
    }

    @Test fun packageAndVcsFallbackRequireRealTypesAndCannotEscapeOrResurrectDeletedParent() = workspace { root ->
        val nested = File(root, "nested").apply { mkdir() }
        val doc = File(nested, "schema.prisma")
        File(root, "package.json").mkdir()
        assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc))
        Files.createSymbolicLink(File(root, ".git").toPath(), nested.toPath())
        assertEquals(nested, LspServerProfiles.projectRoot(spec, root, doc))
        File(root, ".hg").mkdir()
        assertEquals(root, LspServerProfiles.projectRoot(spec, root, doc))
        assertTrue(PathSecurity.deleteTreeNoFollow(nested))
        val unavailable = LspServerProfiles.projectRoot(spec, root, doc)
        assertEquals(nested, unavailable)
        assertFalse(LspServerProfiles.projectAvailable(spec, unavailable))
        assertTrue(LspServerProfiles.unavailableProjectMessage(spec).contains("configuration boundary"))
        assertTrue(runCatching { LspServerProfiles.projectRoot(spec, root, File(root.parentFile, "outside.prisma")) }.isFailure)
    }

    @Test fun rootConfigurationIdentityBindsMarkerSelectionAndPolicyButNeverContentsOrGeneration() = workspace { root ->
        val before = LspServerProfiles.analysisConfiguration(spec, root, root)
        val marker = File(root, "prisma.config.ts").apply { writeText("first configuration") }
        val selected = LspServerProfiles.analysisConfiguration(spec, root, root)
        assertNotEquals(before, selected)
        marker.writeText("different executable content")
        File(root, "schema.prisma").writeText("// use prisma-next")
        assertEquals(selected, LspServerProfiles.analysisConfiguration(spec, root, root))
        assertTrue(marker.delete()); assertTrue(marker.mkdir())
        assertNotEquals(selected, LspServerProfiles.analysisConfiguration(spec, root, root))
        for (term in listOf(LspPrismaProfile.IDENTITY, LspPrismaProfile.ROOT_POLICY, LspPrismaProfile.LAUNCH_POLICY_REVISION,
            LspPrismaProfile.TRANSPORT_POLICY, "\"configuration\":false", "\"pushedSettings\":false", "unverified", "edit-only"))
            assertTrue(term, selected.contains(term))
        assertFalse(selected.contains("// use prisma-next"))
        assertTrue(LspServerProfiles.refreshAfterPersistedMutation(spec))
    }

    @Test fun customIdLanguageExtensionAndPathRoutesNeedConsentWithoutBuiltinClaimsOrGenerationAdapters() = workspace { root ->
        val routes = listOf(spec.copy(source = "extension"), spec.copy(commands = listOf(listOf("other"))),
            LanguageServerSpec("custom-language", setOf("prisma"), emptySet(), listOf(listOf("other")), "extension"),
            LanguageServerSpec("custom-extension", emptySet(), setOf("prisma"), listOf(listOf("other")), "extension"),
            LanguageServerSpec("custom-path", emptySet(), emptySet(), listOf(listOf("other")), "extension", setOf("schema.prisma")))
        for (route in routes) {
            assertFalse(LspServerProfiles.isPrisma(route))
            assertTrue(LspServerProfiles.requiresProjectAnalysis(route, "schema.prisma"))
            assertFalse(LspServerProfiles.hasProjectRoots(route))
            assertFalse(LspServerProfiles.refreshAfterPersistedMutation(route))
            assertFalse(LspServerProfiles.analysisConfiguration(route, root, root).contains(LspPrismaProfile.IDENTITY))
            assertEquals(LspProtocolContract.clientCapabilities(), LspServerProfiles.clientCapabilities(route))
            assertEquals(LspPrismaProfile.INSTALLATION_DISCLOSURE, LspServerProfiles.installationDisclosure(route, "schema.prisma"))
            assertTrue(LspServerProfiles.approvalExplanation(route, root, "schema.prisma").contains("custom Prisma route"))
        }
        assertEquals(listOf(routes.first()), LanguageServerRegistry.forPath("schema.prisma", listOf(routes.first())))
        for (route in routes.take(4)) assertTrue(LspServerProfiles.requiresProjectAnalysis(route, "unrelated.txt"))
    }

    @Test fun defaultsPreserveUtf16IncrementalSyncAndExplicitDefinitionLinksWithoutSettings() = workspace { root ->
        assertTrue(LspServerProfiles.initializationOptions(spec, root).isEmpty())
        assertNull(LspServerProfiles.configurationAfterInitialize(spec, null))
        val capabilities = LspServerProfiles.clientCapabilities(spec)
        val workspace = capabilities.getValue("workspace").jsonObject
        assertEquals(JsonPrimitive(false), workspace["configuration"])
        assertFalse(workspace.containsKey("didChangeConfiguration"))
        assertFalse(workspace.containsKey("didChangeWatchedFiles"))
        assertEquals(JsonPrimitive(true), capabilities.getValue("textDocument").jsonObject.getValue("definition").jsonObject["linkSupport"])
        assertEquals(JsonArray(listOf(JsonPrimitive("utf-16"))), capabilities.getValue("general").jsonObject["positionEncodings"])
        val negotiated = LspNegotiatedCapabilities.fromInitialize(Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":2,"definitionProvider":true,"completionProvider":{}}}"""))
        assertEquals(LspTextSyncPolicy.ChangeKind.INCREMENTAL, negotiated.sync.change)
        assertTrue(negotiated.sync.openClose); assertTrue(negotiated.definition); assertTrue(negotiated.completion)
        assertEquals("utf-16", negotiated.positionEncoding)
    }

    @Test fun approvalAndFeatureCopyDiscloseExecutableConfigAndLimitsWithoutAttestingPathDiscovery() {
        val approval = LspServerProfiles.approvalExplanation(spec)
        for (term in listOf("repeatedly", "executable", "credentials", "outside this folder", "network", "symlinks", "not a sandbox", "unverified", "Prisma 6"))
            assertTrue(term, approval.contains(term))
        val note = requireNotNull(LspServerProfiles.optionalToolNote(spec))
        for (term in listOf("Legacy", "Next/ORM 8", "mixed-generation", "unversioned", "empty reports", "restart/reopen", "stdout", "does not inherit", "defaults"))
            assertTrue(term, note.contains(term))
        assertTrue(LspServerProfiles.enabledNote(spec).contains("did not start or install"))
        assertTrue(LspServerProfiles.runningToolNote(spec).contains("unverified"))
    }

    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("prisma-profile-").toFile().canonicalFile
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
