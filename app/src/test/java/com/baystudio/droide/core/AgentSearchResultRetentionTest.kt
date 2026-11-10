package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentSearchResultRetentionTest {
    private suspend fun fixture(body: suspend (File, AgentTools.Ctx) -> Unit) {
        val root = Files.createTempDirectory("droide-search-retention-").toFile()
        try {
            root.resolve("a.kt").writeText(searchPageText())
            body(root, agentWorkflowContext(root))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun expiredPageCannotRerunSearch() = runBlocking { fixture { _, c ->
        var now = 0L
        c.searchResultPages = AgentSearchResultPages { now }
        val page = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        now = AgentSearchResultPages.MAX_AGE_NANOS
        assertTrue(AgentTools.execute("search_files", searchPageArgs(page), c).contains("expired"))
    } }

    @Test fun oldResultsAreEvictedAtBoundedEntryLimit() = runBlocking { fixture { _, c ->
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        repeat(AgentSearchResultPages.MAX_RESULTS) { AgentTools.execute("search_files", searchPageArgs(), c) }
        assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).contains("expired"))
    } }

    @Test fun closeReleasesPagesAndRejectsNewSearches() = runBlocking { fixture { _, c ->
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        c.searchResultPages.close()
        assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).contains("run has ended"))
        assertTrue(AgentTools.execute("search_files", searchPageArgs(), c).contains("run has ended"))
    } }

    @Test fun cancellationDuringCaptureCannotPublishAPage() = runBlocking { fixture { root, _ ->
        val entered = CompletableDeferred<Unit>()
        val docs = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
            override suspend fun snapshots() = emptyList<WorkspaceDocumentSnapshot>()
            override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture {
                entered.complete(Unit); awaitCancellation()
            }
        }
        val c = agentWorkflowContext(root, documents = docs)
        val job = async { AgentTools.execute("search_files", searchPageArgs(), c) }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        c.searchResultPages.close()
        assertEquals(searchPageText(), root.resolve("a.kt").readText())
    } }

    @Test fun closingDuringCaptureCannotPublishAResult() = runBlocking { fixture { root, _ ->
        val pages = AgentSearchResultPages()
        val docs = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
            override suspend fun snapshots() = emptyList<WorkspaceDocumentSnapshot>()
            override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture {
                pages.close(); return request.captureFiniteSnapshots(emptyList())
            }
        }
        val c = agentWorkflowContext(root, documents = docs).also { it.searchResultPages = pages }
        assertTrue(AgentTools.execute("search_files", searchPageArgs(), c).contains("run has ended"))
    } }

    @Test fun continuationRespectsCurrentPermissions() = runBlocking { fixture { root, c ->
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        val denied = agentWorkflowContext(root, permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.DENY))))
            .also { it.searchResultPages = c.searchResultPages }
        val text = AgentTools.execute("search_files", searchPageArgs(first), denied)
        assertTrue(text.startsWith("DENIED")); assertFalse(text.contains("a.kt:"))
    } }

    @Test fun modeAndDocumentAuthorityCannotBorrowCapturedPages() = runBlocking { fixture { root, c ->
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        for (other in listOf(agentWorkflowContext(root, mode = AgentMode.PLAN),
            agentWorkflowContext(root, documents = AgentWorkflowDocuments(agentWorkflowSnapshot("a.kt", searchPageText()))))) {
            other.searchResultPages = c.searchResultPages
            assertTrue(AgentTools.execute("search_files", searchPageArgs(first), other).startsWith("ERROR:"))
        }
    } }

    @Test fun replacedRootRejectsEvenIdenticalFiles() = runBlocking { fixture { root, c ->
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        val moved = File(root.parentFile, root.name + "-old")
        try {
            Files.move(root.toPath(), moved.toPath()); root.mkdir()
            root.resolve("a.kt").writeText(searchPageText())
            assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).contains("stale"))
            assertEquals(searchPageText(), moved.resolve("a.kt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(moved) }
    } }

    @Test fun parentSymlinkReplacementIsRejected() = runBlocking { fixture { root, c ->
        root.resolve("dir").mkdir()
        Files.move(root.resolve("a.kt").toPath(), root.resolve("dir/a.kt").toPath())
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        Files.move(root.resolve("dir").toPath(), root.resolve("other").toPath())
        Files.createSymbolicLink(root.resolve("dir").toPath(), root.resolve("other").toPath())
        assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).contains("stale"))
    } }

    @Test fun invalidOffsetsNeverExposeOrDiscardTheStoredResult() = runBlocking { fixture { _, c ->
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        for (offset in listOf(-1, 121)) {
            val args = JsonObject(searchPageArgs(first) + ("offset" to JsonPrimitive(offset)))
            assertTrue(AgentTools.execute("search_files", args, c).startsWith("ERROR:"))
        }
        assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).startsWith("SEARCH_RESULT_PAGE"))
    } }

    @Test fun outputRetentionLimitRemainsExplicitAfterLastPage() = runBlocking { fixture { root, c ->
        val query = "n".repeat(256)
        root.resolve("a.kt").writeText((1..500).joinToString("\n") { query + " x".repeat(70) })
        var output = AgentTools.execute("search_files", searchPageArgs(query = query), c)
        while (true) {
            val page = searchPageMetadata(output)
            assertTrue(page.getValue("retention_truncated").jsonPrimitive.boolean)
            assertTrue(output.contains("Retention limit reached"))
            if (!page.getValue("more_output").jsonPrimitive.boolean) break
            output = AgentTools.execute("search_files", searchPageArgs(page, query), c)
        }
        assertTrue(output.contains("result_limit=true"))
    } }

    @Test fun changedDuringSearchReadCannotValidateCapturedBytes() = runBlocking { fixture { root, c ->
        val snapshot = AgentSearchSnapshot(root)
        val input = snapshot.open(root.resolve("a.kt"))
        input.read(ByteArray(100))
        root.resolve("a.kt").writeText("changed")
        assertTrue(runCatching { input.close() }.isFailure)
        assertTrue(runCatching { snapshot.validate(setOf("a.kt"), c.documentAuthority) }.isFailure)
    } }

    @Test fun identicalBytesInReplacementInodeAreStillRejected() = runBlocking { fixture { root, c ->
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        Files.move(root.resolve("a.kt").toPath(), root.resolve("old.kt").toPath())
        root.resolve("a.kt").writeText(searchPageText())
        assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).contains("stale"))
    } }

    @Test fun scanLimitRemainsVisibleAfterOutputContinuationEnds() = runBlocking { fixture { root, c ->
        root.resolve("a.kt").appendText("\n" + "x".repeat(70_000))
        var output = AgentTools.execute("search_files", searchPageArgs(), c)
        while (true) {
            val page = searchPageMetadata(output)
            assertTrue(output.contains("scan_limit=true"))
            assertTrue(output.contains("line_length_limit"))
            if (!page.getValue("more_output").jsonPrimitive.boolean) break
            output = AgentTools.execute("search_files", searchPageArgs(page), c)
        }
        assertTrue(output.contains("Results are incomplete"))
    } }

    @Test fun replacedBufferContentCannotReuseTheSameRevisionNumbers() = runBlocking { fixture { root, _ ->
        val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("a.kt", searchPageText()))
        val c = agentWorkflowContext(root, documents = docs)
        val first = searchPageMetadata(AgentTools.execute("search_files", searchPageArgs(), c))
        docs.current = docs.current.copy(content = docs.current.content.replace("needle", "change"))
        assertTrue(AgentTools.execute("search_files", searchPageArgs(first), c).contains("stale"))
        assertTrue(docs.current.content.startsWith("change"))
    } }
}
