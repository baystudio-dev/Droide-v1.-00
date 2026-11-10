package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentRuleAdmissionDiagnosticsTest {
    private fun fixture(block: (File, AgentPluginContributionRoot, AgentPluginSource) -> Unit) {
        val root = Files.createTempDirectory("rule-admission-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir() }
            val plugin = AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
            block(root, plugin, AgentPluginSource { listOf(plugin) })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun file(plugin: AgentPluginContributionRoot, name: String = "one.md", text: String = "Body") =
        plugin.root.resolve("rules/$name").apply { parentFile.mkdirs(); writeText(text) }
    private fun report(root: File, source: AgentPluginSource) = AgentRuleManager(root, source).catalogWithDiagnostics()
    private fun assertIssue(report: AgentRuleDiscovery<*>, reason: String, count: Int = 1) {
        assertEquals(count, report.skippedCount); assertTrue(report.notice(), report.notice().contains("($reason)"))
    }

    @Test fun emptyFileIsReportedWithoutReadingIt() = fixture { root, plugin, source ->
        val f = file(plugin, text = ""); var opens = 0
        val report = AgentRuleManager(root, source) { opens++; it.inputStream() }.catalogWithDiagnostics()
        assertTrue(report.values.isEmpty()); assertIssue(report, "empty_file"); assertEquals(0, opens); assertEquals(0L, f.length())
    }

    @Test fun oversizedFileIsReportedWhileExactByteLimitRemainsLoadable() = fixture { root, plugin, source ->
        val f = file(plugin, text = "x".repeat(12_000)); val manager = AgentRuleManager(root, source)
        assertEquals(1, manager.catalogWithDiagnostics().values.size); assertEquals(12_000, manager.load("fixture:one")!!.body.length)
        f.appendText("x"); val before = f.readBytes(); var opens = 0
        val report = AgentRuleManager(root, source) { opens++; it.inputStream() }.catalogWithDiagnostics()
        assertIssue(report, "file_too_large"); assertTrue(report.values.isEmpty()); assertEquals(0, opens); assertArrayEquals(before, f.readBytes())
    }

    @Test fun sizeAdmissionUsesUtf8BytesAndDoesNotTruncateTheSource() = fixture { root, plugin, source ->
        val f = file(plugin, text = "é".repeat(6_000)); val manager = AgentRuleManager(root, source)
        assertEquals("é".repeat(6_000), manager.load("fixture:one")!!.body)
        f.appendText("é"); assertIssue(manager.catalogWithDiagnostics(), "file_too_large"); assertEquals(12_002L, f.length())
    }

    @Test fun markdownDirectoryIsReportedAsWrongType() = fixture { root, plugin, source ->
        plugin.root.resolve("rules/folder.md").mkdirs()
        val report = report(root, source); assertIssue(report, "not_regular_file"); assertTrue(report.values.isEmpty())
    }

    @Test fun stableLeafSymlinkIsRejectedWithoutExposingOrChangingItsTarget() = fixture { root, plugin, source ->
        val outside = root.resolve("private.md").apply { writeText("PRIVATE_TARGET_BODY") }
        val link = plugin.root.resolve("rules/link.md").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val report = report(root, source); assertIssue(report, "symbolic_link"); assertTrue(report.values.isEmpty())
        assertFalse(report.notice().contains("PRIVATE_TARGET_BODY")); assertEquals("PRIVATE_TARGET_BODY", outside.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun danglingLeafSymlinkIsReportedRatherThanTreatedAsAbsent() = fixture { root, plugin, source ->
        val link = plugin.root.resolve("rules/link.md").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(link.toPath(), root.resolve("missing").toPath())
        assertIssue(report(root, source), "symbolic_link"); assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun internalDirectorySymlinkNeverAdmitsRedirectedRules() = fixture { root, plugin, source ->
        val target = plugin.root.resolve("other").apply { mkdir(); resolve("redirected.md").writeText("Redirected") }
        Files.createSymbolicLink(plugin.root.resolve("rules").toPath(), target.toPath())
        assertTrue(AgentPluginContributions.ruleFiles(source, root).isEmpty())
        val report = report(root, source); assertIssue(report, "directory_symlink"); assertTrue(report.values.isEmpty())
        assertNull(AgentRuleManager(root, source).load("fixture:redirected")); assertEquals("Redirected", target.resolve("redirected.md").readText())
    }

    @Test fun externalDirectorySymlinkHasADiagnosticWithoutTargetBodyDisclosure() = fixture { root, plugin, source ->
        val target = root.resolve("outside").apply { mkdir(); resolve("private.md").writeText("PRIVATE_TARGET") }
        Files.createSymbolicLink(plugin.root.resolve("rules").toPath(), target.toPath())
        val report = report(root, source); assertIssue(report, "directory_symlink"); assertTrue(report.values.isEmpty())
        assertFalse(report.notice().contains("PRIVATE_TARGET")); assertEquals("PRIVATE_TARGET", target.resolve("private.md").readText())
    }

    @Test fun danglingDirectorySymlinkIsReported() = fixture { root, plugin, source ->
        Files.createSymbolicLink(plugin.root.resolve("rules").toPath(), root.resolve("missing").toPath())
        val report = report(root, source); assertIssue(report, "directory_symlink"); assertTrue(report.values.isEmpty())
    }

    @Test fun ruleDirectoryReplacedWithAFileIsReported() = fixture { root, plugin, source ->
        plugin.root.resolve("rules").writeText("Keep this file")
        assertIssue(report(root, source), "not_directory"); assertEquals("Keep this file", plugin.root.resolve("rules").readText())
    }

    @Test fun missingPluginRootIsNotReportedAsACleanEmptyCatalog() = fixture { root, plugin, source ->
        check(plugin.root.delete()); val report = report(root, source)
        assertIssue(report, "directory_unavailable"); assertTrue(report.values.isEmpty()); assertFalse(plugin.root.exists())
    }

    @Test fun absentOptionalRuleDirectoryIsNotAnError() = fixture { root, _, source ->
        val report = report(root, source); assertTrue(report.values.isEmpty()); assertEquals(0, report.skippedCount); assertEquals("", report.notice())
    }

    @Test fun emptyDirectoryAndUnrelatedFilesDoNotInventFailures() = fixture { root, plugin, source ->
        plugin.root.resolve("rules").mkdir(); assertEquals(0, report(root, source).skippedCount)
        file(plugin, "README.txt", ""); plugin.root.resolve("rules/subfolder").mkdir()
        val report = report(root, source); assertTrue(report.values.isEmpty()); assertEquals(0, report.skippedCount)
    }

    @Test fun unreadableDirectoryIsReportedAndPermissionsArePreserved() = fixture { root, plugin, source ->
        val f = file(plugin); val dir = f.parentFile.toPath(); val permissions = Files.getPosixFilePermissions(dir)
        try {
            Files.setPosixFilePermissions(dir, emptySet())
            assertIssue(report(root, source), "directory_unreadable")
            assertEquals(emptySet<PosixFilePermission>(), Files.getPosixFilePermissions(dir))
        } finally { Files.setPosixFilePermissions(dir, permissions) }
        assertEquals("Body", f.readText())
    }

    @Test fun enumerableDirectoryWithoutSearchPermissionReportsUnreadableEntries() = fixture { root, plugin, source ->
        val f = file(plugin); val dir = f.parentFile.toPath(); val permissions = Files.getPosixFilePermissions(dir)
        try {
            Files.setPosixFilePermissions(dir, setOf(PosixFilePermission.OWNER_READ))
            val report = report(root, source); assertIssue(report, "entry_unreadable"); assertTrue(report.values.isEmpty())
            assertEquals(setOf(PosixFilePermission.OWNER_READ), Files.getPosixFilePermissions(dir))
        } finally { Files.setPosixFilePermissions(dir, permissions) }
        assertEquals("Body", f.readText())
    }

    @Test fun canonicalResolutionErrorIsReportedWithoutExceptionPayload() = fixture { root, plugin, _ ->
        val faulty = object : File(plugin.root.path) { override fun getCanonicalFile(): File = throw IOException("PRIVATE_ERROR".repeat(10_000)) }
        val report = report(root, AgentPluginSource { listOf(plugin.copy(root = faulty)) })
        assertIssue(report, "directory_unavailable"); assertFalse(report.notice().contains("PRIVATE_ERROR")); assertTrue(report.notice().length < 1_000)
    }

    @Test fun canonicalResolutionCancellationIsNeverSilentlySwallowed() = fixture { root, plugin, _ ->
        val cancelled = CancellationException("Stop")
        val faulty = object : File(plugin.root.path) { override fun getCanonicalFile(): File = throw cancelled }
        val source = AgentPluginSource { listOf(plugin.copy(root = faulty)) }
        try { report(root, source); fail("Cancellation must propagate") } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun manyRejectedFilesKeepDiagnosticsBoundedAndDoNotReadBodies() = fixture { root, plugin, _ ->
        repeat(512) { file(plugin, "r%04d.md".format(it), "") }
        val record = plugin.copy(id = "id\n\u202e" + "😀".repeat(100_000)); var opens = 0
        val report = AgentRuleManager(root, AgentPluginSource { listOf(record) }) { opens++; it.inputStream() }.catalogWithDiagnostics()
        assertEquals(512, report.skippedCount); assertEquals(4, report.details.size); assertEquals(0, opens)
        assertTrue(report.notice().contains("508 further issue(s) omitted")); assertTrue(report.notice().toByteArray().size < 2_000)
        assertFalse(report.notice().contains('\n')); assertFalse(report.notice().contains('\u202e'))
    }

    @Test fun repairingAndDisablingAPluginClearsOnlyTheCurrentReport() = fixture { root, plugin, _ ->
        val f = file(plugin, text = ""); var enabled = true
        val manager = AgentRuleManager(root, AgentPluginSource { if (enabled) listOf(plugin) else emptyList() })
        val first = manager.catalogWithDiagnostics(); assertIssue(first, "empty_file")
        enabled = false; assertEquals("", manager.catalogWithDiagnostics().notice())
        enabled = true; f.writeText("Repaired")
        assertEquals("", manager.catalogWithDiagnostics().notice()); assertEquals("Repaired", manager.load("fixture:one")!!.body)
        assertIssue(first, "empty_file")
    }

    @Test fun validOrderingAndFreshAuthorityArePreservedWithRejectedEntries() = fixture { root, plugin, _ ->
        file(plugin, "z.MD"); file(plugin, "b.md"); file(plugin, "a.md", ""); var calls = 0
        val source = AgentPluginSource { calls++; listOf(plugin) }
        val report = report(root, source)
        assertEquals(listOf("fixture:b", "fixture:z"), report.values.map { it.name }); assertIssue(report, "empty_file"); assertEquals(1, calls)
    }

    @Test fun permissionDenialDoesNotEnumerateRejectedCandidates() = fixture { root, _, _ -> runBlocking {
        var calls = 0; val source = AgentPluginSource { calls++; error("Do not enumerate") }
        val ctx = context(root, source, PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("skill", "*", PermEffect.DENY))))
        val output = AgentTools.execute("rule", buildJsonObject { put("name", "fixture:one") }, ctx)
        assertTrue(output.startsWith("DENIED")); assertEquals(0, calls)
    } }

    @Test fun explicitLookupAndInitialInstructionsDiscloseRejectedCandidates() = fixture { root, plugin, source -> runBlocking {
        file(plugin, text = "")
        val output = AgentTools.execute("rule", buildJsonObject { put("name", "fixture:one") }, context(root, source))
        assertTrue(output.startsWith("ERROR: Rule unavailable:")); assertTrue(output.contains("empty_file"))
        val brief = AgentProjectInstructions.load(FileRepository(root), root, source)
        assertTrue(brief.startsWith("[DROIDE_RULE_DIAGNOSTICS]")); assertTrue(brief.contains("empty_file")); assertTrue(brief.length <= 28_000)
    } }

    @Test fun incompleteCatalogHasNoExhaustiveEnum() = fixture { root, plugin, source ->
        file(plugin, "bad.md", ""); file(plugin, "good.md")
        val fn = AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root), rules = AgentRuleManager(root, source))
            .map { it.jsonObject.getValue("function").jsonObject }.single { it["name"] == JsonPrimitive("rule") }
        assertTrue(fn.getValue("description").jsonPrimitive.content.contains("empty_file"))
        assertNull(fn.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject["enum"])
    }

    @Test fun zeroWindowStillAvoidsAllDiscovery() = fixture { root, _, _ ->
        var calls = 0
        val report = AgentRuleManager(root, AgentPluginSource { calls++; error("Do not enumerate") }).catalogWithDiagnostics(0)
        assertEquals(0, calls); assertEquals("", report.notice()); assertTrue(report.values.isEmpty())
    }

    private fun context(root: File, source: AgentPluginSource, permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))): AgentTools.Ctx {
        val c = agentWorkflowContext(root, permissions = permissions)
        return AgentTools.Ctx(c.files, c.terminal, c.git, c.approvals, c.questions, c.history, c.perms, c.mode,
            c.llm, c.llmConfig, root, c.todos, c.sessionId, c.lsp, pluginSource = source)
    }
}
