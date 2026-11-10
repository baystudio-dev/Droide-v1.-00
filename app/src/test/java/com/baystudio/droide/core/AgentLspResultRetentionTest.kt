package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentLspResultRetentionTest {
    private fun assertRejected(output: String) {
        assertTrue(output, output.startsWith("ERROR:"))
        assertFalse(output, output.contains("LSP_PAGE_TEXT"))
    }

    private fun long(f: LspDeliveryFixture) { f.text = "x".repeat(12_013) }

    @Test fun continuationWorksAcrossCallContextsWithinOneOwner(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            long(f)
            val shared = AgentLspResultPages()
            val first = f.context().apply { lspResultPages = shared }
            val meta = lspDeliveryMetadata(AgentTools.execute("lsp", lspDeliveryArgs(), first))
            val second = f.context().apply { lspResultPages = shared }
            val output = AgentTools.execute("lsp", lspDeliveryArgs(meta), second)
            assertEquals(5000, lspDeliveryMetadata(output)["offset"]!!.jsonPrimitive.int)
            assertEquals(1, f.calls)
        }
    }

    @Test fun resultCannotCrossRunSessionWorkspaceOrQuery(): Unit = runBlocking {
        LspDeliveryFixture().use { f -> LspDeliveryFixture().use { other ->
            long(f)
            val c = f.context()
            val meta = lspDeliveryMetadata(AgentTools.execute("lsp", lspDeliveryArgs(), c))
            val freshRun = AgentTools.execute("lsp", lspDeliveryArgs(meta), f.context())
            assertTrue(freshRun.startsWith("ERROR:"))
            val foreignOwner = f.context("other-owner").apply { lspResultPages = c.lspResultPages }
            val foreignWorkspace = other.context().apply { lspResultPages = c.lspResultPages }
            assertRejected(AgentTools.execute("lsp", lspDeliveryArgs(meta), foreignOwner))
            assertRejected(AgentTools.execute("lsp", lspDeliveryArgs(meta), foreignWorkspace))
            assertRejected(AgentTools.execute("lsp", lspDeliveryArgs(meta, operation = "findReferences"), c))
            assertRejected(AgentTools.execute("diagnose", lspDeliveryArgs(meta), c))
            assertEquals(1, f.calls); assertEquals(0, other.calls)
        } }
    }

    @Test fun changedDiskExpiresCapturedTextAndKeepsNewBytes(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            long(f); val c = f.context()
            val meta = lspDeliveryMetadata(AgentTools.execute("lsp", lspDeliveryArgs(), c))
            f.root.resolve("main.kt").writeText("new content from terminal\n")
            val output = AgentTools.execute("lsp", lspDeliveryArgs(meta), c)
            assertTrue(output.startsWith("ERROR:")); assertFalse(output.contains("LSP_PAGE_TEXT"))
            assertEquals("new content from terminal\n", f.root.resolve("main.kt").readText())
            assertEquals(1, f.calls)
        }
    }

    @Test fun dirtyEditorChangeExpiresCapturedText(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            long(f)
            val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("main.kt", "val draft = 1\n"))
            val c = f.context(documents = docs)
            val meta = lspDeliveryMetadata(AgentTools.execute("lsp", lspDeliveryArgs(), c))
            docs.current = docs.current.copy(content = "val draft = 2\n", changeVersion = docs.current.changeVersion + 1)
            val output = AgentTools.execute("lsp", lspDeliveryArgs(meta), c)
            assertTrue(output.startsWith("ERROR:")); assertFalse(output.contains("LSP_PAGE_TEXT"))
            assertEquals("val draft = 2\n", docs.current.content)
            assertEquals("val before = 1\n", f.root.resolve("main.kt").readText())
        }
    }

    @Test fun changesDuringProviderWorkCannotPublishAnAlreadyStalePage(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            long(f); val c = f.context()
            f.respond = { f.root.resolve("main.kt").writeText("changed while awaiting language server"); f.text }
            assertRejected(AgentTools.execute("lsp", lspDeliveryArgs(), c))
            assertEquals("changed while awaiting language server", f.root.resolve("main.kt").readText())
        }
    }

    @Test fun permissionIsCheckedAgainForContinuation(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            long(f); val c = f.context()
            val meta = lspDeliveryMetadata(AgentTools.execute("lsp", lspDeliveryArgs(), c))
            val denied = f.context(permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.DENY))))
                .apply { lspResultPages = c.lspResultPages }
            val output = AgentTools.execute("lsp", lspDeliveryArgs(meta), denied)
            assertTrue(output.startsWith("DENIED")); assertFalse(output.contains("LSP_PAGE_TEXT"))
            assertEquals(1, f.calls)
        }
    }

    @Test fun expiredAndEvictedResultsNeverRerunProviderAutomatically(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            long(f); var clock = 0L
            val c = f.context().apply { lspResultPages = AgentLspResultPages { clock } }
            val first = lspDeliveryMetadata(AgentTools.execute("lsp", lspDeliveryArgs(), c))
            clock = AgentLspResultPages.MAX_AGE_NANOS
            assertTrue(AgentTools.execute("lsp", lspDeliveryArgs(first), c).startsWith("ERROR:"))
            assertEquals(1, f.calls)
            val oldest = lspDeliveryMetadata(AgentTools.execute("lsp", lspDeliveryArgs(), c))
            repeat(AgentLspResultPages.MAX_RESULTS) { AgentTools.execute("lsp", lspDeliveryArgs(), c) }
            val beforeResume = f.calls
            assertTrue(AgentTools.execute("lsp", lspDeliveryArgs(oldest), c).startsWith("ERROR:"))
            assertEquals(beforeResume, f.calls)
        }
    }

    @Test fun characterBudgetEvictsLargeResultsAndOversizeLossIsExplicit(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            f.text = "z".repeat(80_000)
            val c = f.context()
            val output = AgentTools.execute("lsp", lspDeliveryArgs(), c)
            val first = lspDeliveryMetadata(output)
            assertTrue(first["retention_truncated"]!!.jsonPrimitive.boolean)
            assertEquals(64_000, first["retained_chars"]!!.jsonPrimitive.int)
            assertEquals(80_000, first["provider_chars"]!!.jsonPrimitive.int)
            assertTrue(output.contains("omitted text"))
            val last = AgentTools.execute("lsp", lspDeliveryArgs(first, offset = 60_000), c)
            assertFalse(lspDeliveryMetadata(last)["more_output"]!!.jsonPrimitive.boolean)
            assertEquals(4_000, lspDeliveryText(last).length)
            AgentTools.execute("lsp", lspDeliveryArgs(), c)
            AgentTools.execute("lsp", lspDeliveryArgs(), c)
            assertTrue(AgentTools.execute("lsp", lspDeliveryArgs(first), c).startsWith("ERROR:"))
        }
    }

    @Test fun closeReleasesRunAndRefusesFutureRequests(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            long(f); val c = f.context()
            val meta = lspDeliveryMetadata(AgentTools.execute("lsp", lspDeliveryArgs(), c))
            c.lspResultPages.close()
            assertRejected(AgentTools.execute("lsp", lspDeliveryArgs(meta), c))
            assertRejected(AgentTools.execute("lsp", lspDeliveryArgs(), c))
            assertEquals(1, f.calls)
        }
    }

    @Test fun cancellationDuringProviderWorkPropagatesWithoutResult(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            long(f); val entered = CompletableDeferred<Unit>(); val c = f.context()
            f.respond = { entered.complete(Unit); awaitCancellation() }
            val job = async { AgentTools.execute("lsp", lspDeliveryArgs(), c) }
            entered.await(); job.cancel()
            val failure = runCatching { job.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            c.lspResultPages.close()
            assertEquals("val before = 1\n", f.root.resolve("main.kt").readText())
        }
    }

    @Test fun replacementSymlinkDirectoryAndDeletionCannotServeRetainedText(): Unit = runBlocking {
        for (kind in listOf("symlink", "directory", "delete")) LspDeliveryFixture().use { f ->
            long(f); val c = f.context()
            val meta = lspDeliveryMetadata(AgentTools.execute("lsp", lspDeliveryArgs(), c))
            Files.delete(f.root.resolve("main.kt").toPath())
            if (kind == "symlink") {
                f.root.resolve("other.kt").writeText("retained separate content")
                Files.createSymbolicLink(f.root.resolve("main.kt").toPath(), f.root.resolve("other.kt").toPath())
            } else if (kind == "directory") f.root.resolve("main.kt").mkdir()
            val output = AgentTools.execute("lsp", lspDeliveryArgs(meta), c)
            assertTrue(kind, output.startsWith("ERROR:")); assertFalse(output.contains("LSP_PAGE_TEXT"))
            assertEquals(1, f.calls)
        }
    }

    @Test fun invalidOffsetAndPathDoNotLaunchAnotherRequest(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            f.text = "x".repeat(4_999) + "😀" + "z".repeat(7_000)
            val c = f.context(); val meta = lspDeliveryMetadata(AgentTools.execute("lsp", lspDeliveryArgs(), c))
            for (offset in listOf(-1, 5_000, 100_000))
                assertRejected(AgentTools.execute("lsp", lspDeliveryArgs(meta, offset = offset), c))
            assertRejected(AgentTools.execute("lsp", lspDeliveryArgs(path = "../other.kt"), c))
            val noToken = buildJsonObject { put("path", "main.kt"); put("offset", 5_000) }
            assertRejected(AgentTools.execute("lsp", noToken, c))
            assertEquals(1, f.calls)
        }
    }

    @Test fun explicitProviderFailureRetainsFailureOnEveryPage(): Unit = runBlocking {
        LspDeliveryFixture().use { f ->
            f.text = "ERROR: " + "x".repeat(10_000)
            val c = f.context(); val first = AgentTools.execute("lsp", lspDeliveryArgs(), c)
            val second = AgentTools.execute("lsp", lspDeliveryArgs(lspDeliveryMetadata(first)), c)
            assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("lsp", first).state)
            assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("lsp", second).state)
        }
    }

    @Test fun upstreamCharacterLimitIsBoundedVisibleAndUnicodeSafe() {
        val value = "a".repeat(11_907) + "😀" + "b".repeat(2_000)
        val bounded = LspTextLimit.characters(value)
        assertTrue(bounded.length <= 12_000)
        assertTrue(bounded.contains("LSP_RESULT_LIMIT"))
        assertEquals("small", LspTextLimit.characters("small"))
        assertFalse(bounded.substringBefore("\n[LSP_RESULT_LIMIT").last().isHighSurrogate())
    }
}
