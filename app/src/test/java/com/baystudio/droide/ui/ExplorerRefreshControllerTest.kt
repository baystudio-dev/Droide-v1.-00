package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExplorerRefreshControllerTest {
    private fun dir(path: String) = FileEntry(path, true, 0, 0)
    private fun file(path: String) = FileEntry(path, false, 1, 0)
    private fun page(vararg entries: FileEntry) = DirectoryPage(entries.toList(), null)
    private data class Call(val path: String, val after: DirectoryPageCursor?, val budget: DiscoveryBudget)
    private class Watches : ExplorerDirectoryWatchFactory {
        data class Handle(val path: String, val invalidate: (Boolean) -> Unit, var closed: Boolean = false) : AutoCloseable {
            override fun close() { closed = true }
        }
        val all = mutableListOf<Handle>()
        val live get() = all.filterNot { it.closed }
        var peak = 0
        override fun open(path: String, invalidate: (Boolean) -> Unit): AutoCloseable = Handle(path, invalidate).also {
            all += it; peak = maxOf(peak, live.size)
        }
        fun event(path: String, self: Boolean = false) = live.last { it.path == path }.invalidate(self)
    }
    private inner class Harness(
        val test: TestScope,
        val state: ExplorerWorkspaceState = ExplorerWorkspaceState(),
        limits: ExplorerRefreshLimits = ExplorerRefreshLimits(),
        lane: Mutex = Mutex(),
        ioDispatcher: CoroutineDispatcher? = null,
    ) {
        val watches = Watches()
        val calls = mutableListOf<Call>()
        val disk = linkedMapOf("" to page())
        var loader: suspend (String, DirectoryPageCursor?, DiscoveryBudget, WorkspaceDiscoveryTraversal) -> DirectoryPage = { path, _, budget, _ ->
            val result = disk[path] ?: page()
            if (budget.beforeOpen()) {
                repeat(result.entries.size + 1) { if (budget.beforeProbe() && it < result.entries.size) budget.deliveredEntry() }
            }
            budget.requireCompleteDirectory(path)
            result
        }
        val dispatcher: CoroutineDispatcher = ioDispatcher ?: StandardTestDispatcher(test.testScheduler)
        val owner = ExplorerRefreshController(state, test.backgroundScope, lane,
            load = { path, after, _, budget, traversal -> calls += Call(path, after, budget); loader(path, after, budget, traversal) },
            watchFactory = watches, io = dispatcher, now = { test.testScheduler.currentTime }, limits = limits)
        var lease = owner.newUiLease()
        fun show(viewport: Set<String> = emptySet()) {
            owner.updateViewport(lease, viewport)
            owner.updateVisibility(lease, true, true, true)
        }
        fun current() = test.testScheduler.runCurrent()
        fun tick(millis: Long) { test.testScheduler.advanceTimeBy(millis); current() }
        fun close() { owner.close(); current() }
    }


    @Test fun repositoryAndWatchInvalidationsCannotReuseAnAppendVersion() = runTest {
        val h = Harness(this)
        val cursor = DirectoryPageCursor("first", false, h.state.options)
        h.disk[""] = DirectoryPage(listOf(file("first")), cursor)
        h.show(); h.current(); h.tick(2_000)
        // A repository event advances the page before the first platform event.
        h.owner.requestWorkspaceChange(); h.current(); h.tick(200)
        h.tick(2_000)
        val appended = CompletableDeferred<DirectoryPage>()
        val reread = CompletableDeferred<DirectoryPage>()
        h.loader = { _, after, _, _ -> if (after == null) reread.await() else appended.await() }
        h.owner.requestMore(h.lease, ""); h.current()
        assertEquals(cursor, h.calls.last().after)
        h.watches.event("")
        appended.complete(page(file("stale-next"))); h.current()
        // Even before the replacement read completes, stale cursor results must not publish.
        assertEquals(listOf("first"), h.state.children[""]!!.map { it.path })
        h.tick(2_000)
        reread.complete(page(file("fresh-first"))); h.current()
        assertEquals(listOf("fresh-first"), h.state.children[""]!!.map { it.path })
        h.close()
    }


    @Test fun watchThenRepositoryInvalidationAlsoRejectsAnInflightAppend() = runTest {
        val h = Harness(this)
        val cursor = DirectoryPageCursor("first", false, h.state.options)
        h.disk[""] = DirectoryPage(listOf(file("first")), cursor)
        h.show(); h.current(); h.tick(2_000)
        h.watches.event(""); h.current(); h.tick(200); h.tick(2_000)
        val appended = CompletableDeferred<DirectoryPage>()
        val reread = CompletableDeferred<DirectoryPage>()
        h.loader = { _, after, _, _ -> if (after == null) reread.await() else appended.await() }
        h.owner.requestMore(h.lease, ""); h.current()
        assertEquals(cursor, h.calls.last().after)
        h.owner.requestWorkspaceChange()
        appended.complete(page(file("stale-next"))); h.current()
        assertEquals(listOf("first"), h.state.children[""]!!.map { it.path })
        h.tick(2_000)
        reread.complete(page(file("fresh-first"))); h.current()
        assertEquals(listOf("fresh-first"), h.state.children[""]!!.map { it.path })
        h.close()
    }

    @Test fun committedRepositoryChangeRefreshesWithoutAnyFilesystemWatcherEvent() = runTest {
        val h = Harness(this)
        h.show(); h.current(); h.tick(2_000)
        h.disk[""] = page(file("accepted.html"))
        repeat(10_000) { h.owner.requestWorkspaceChange() }
        h.current(); h.tick(200)
        assertEquals("accepted.html", h.state.children[""]!!.single().path)
        assertEquals(2, h.calls.size)
        assertTrue(h.owner.pendingInvalidationsForTest() <= 64)
        h.close()
    }

    @Test fun hiddenWorkspaceChangesDoNotStartIdleReadsAndAppearanceCatchesUp() = runTest {
        val h = Harness(this)
        repeat(1_000) { h.owner.requestWorkspaceChange() }
        h.current(); h.tick(120_000)
        assertTrue(h.calls.isEmpty())
        h.disk[""] = page(file("from-terminal.txt"))
        h.show(); h.current()
        assertEquals("from-terminal.txt", h.state.children[""]!!.single().path)
        h.close()
    }

    @Test fun constructionAndUncommittedLeaseAreInert() = runTest {
        val h = Harness(this)
        h.owner.updateViewport(h.lease, setOf(""))
        h.current(); h.tick(600_000)
        assertTrue(h.calls.isEmpty()); assertTrue(h.watches.all.isEmpty())
        h.owner.updateVisibility(h.lease, false, true, true)
        h.current()
        assertTrue(h.calls.isEmpty())
        h.close()
    }

    @Test fun samePaneDependencyInstallCoalescesAtFirstEventWithoutRestartingSearchToken() = runTest {
        val h = Harness(this)
        h.show(); h.current(); h.tick(2_000)
        val token = h.state.refreshToken
        h.disk[""] = page(dir("node_modules"))
        repeat(10_000) { h.watches.event("") }
        assertEquals(1, h.owner.pendingInvalidationsForTest())
        h.current(); h.tick(199)
        assertEquals(1, h.calls.size)
        h.watches.event("")
        h.tick(1)
        assertEquals(2, h.calls.size)
        assertEquals("node_modules", h.state.children[""]!!.single().path)
        assertEquals(token, h.state.refreshToken)
        h.state.expandedPaths = listOf("node_modules")
        h.state.activeDirectory = "node_modules"
        h.disk["node_modules"] = page(dir("node_modules/package"))
        h.owner.requestExpansion(h.lease, "node_modules"); h.current()
        assertTrue("node_modules" in h.state.children)
        assertTrue(h.watches.live.any { it.path == "node_modules" })
        assertFalse(h.watches.all.any { it.path == "node_modules/package" })
        h.close()
    }

    @Test fun unchangedScopeRetainsAtMost64WatchesAndUnwatchedDependenciesRemainAccessible() = runTest {
        val state = ExplorerWorkspaceState()
        val paths = (0 until 100).map { "node_modules/package$it" }
        state.children[""] = listOf(dir("node_modules"))
        state.children["node_modules"] = paths.map(::dir)
        state.expandedPaths = listOf("node_modules") + paths
        paths.forEach { state.children[it] = emptyList() }
        val h = Harness(this, state)
        h.disk[""] = page(dir("node_modules")); h.disk["node_modules"] = DirectoryPage(paths.map(::dir), null)
        h.show(paths.toSet()); h.current()
        assertEquals(64, h.watches.live.size)
        val started = h.watches.all.size
        repeat(20) { h.owner.updateTreeScope(h.lease); h.owner.updateViewport(h.lease, paths.toSet()) }
        h.current()
        assertEquals(started, h.watches.all.size)
        assertTrue(h.watches.peak <= 64)
        val unwatched = paths.last()
        h.disk[unwatched] = page(file("$unwatched/index.js"))
        h.owner.requestExpansion(h.lease, unwatched)
        // Existing appearance targets are finite and share four-candidate drains.
        repeat(30) { h.tick(2_000) }
        assertEquals("$unwatched/index.js", state.children[unwatched]!!.single().path)
        h.owner.updateVisibility(h.lease, false, true, true)
        assertTrue(h.watches.live.isEmpty())
        h.close()
    }

    @Test fun offscreenWarmWatchDirtiesWithoutReadingUntilViewportEntry() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("a"), dir("b")); children["a"] = emptyList(); children["b"] = emptyList()
            expandedPaths = listOf("a", "b")
        }
        val h = Harness(this, state)
        h.disk[""] = page(dir("a"), dir("b"))
        h.show(setOf("b")); h.current(); h.tick(2_000)
        h.owner.updateViewport(h.lease, setOf("a")); h.current(); h.tick(2_000)
        val count = h.calls.count { it.path == "b" }
        h.watches.event("b"); h.current(); h.tick(2_000)
        assertEquals(count, h.calls.count { it.path == "b" })
        h.owner.updateViewport(h.lease, setOf("b")); h.current(); h.tick(2_000)
        assertEquals(count + 1, h.calls.count { it.path == "b" })
        h.close()
    }

    @Test fun selfReplacementDropsLateCallbackAndRebindsOnlyAfterAuthoritativeRead() = runTest {
        val state = ExplorerWorkspaceState().apply { children[""] = listOf(dir("src")); expandedPaths = listOf("src"); children["src"] = emptyList() }
        val h = Harness(this, state)
        h.disk[""] = page(dir("src")); h.show(setOf("src")); h.current(); h.tick(2_000)
        val old = h.watches.live.single { it.path == "src" }
        old.invalidate(true); h.current()
        assertTrue(old.closed)
        old.invalidate(false)
        h.tick(200)
        val replacement = h.watches.live.single { it.path == "src" }
        assertNotSame(old, replacement)
        val pending = h.owner.pendingInvalidationsForTest()
        old.invalidate(true)
        assertEquals(pending, h.owner.pendingInvalidationsForTest())
        h.close()
    }

    @Test fun inboxSaturationReconcilesOnlyCurrentViewportRatherThanAllExpansions() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("a"), dir("b"), dir("c")); expandedPaths = listOf("a", "b", "c")
            listOf("a", "b", "c").forEach { children[it] = emptyList() }
        }
        val h = Harness(this, state, ExplorerRefreshLimits(inbox = 1))
        h.disk[""] = page(dir("a"), dir("b"), dir("c"))
        h.show(setOf("a", "b")); h.current(); h.tick(2_000)
        h.owner.updateViewport(h.lease, setOf("a")); h.current()
        val before = h.calls.size
        h.watches.event("a"); h.watches.event("b")
        assertEquals(1, h.owner.pendingInvalidationsForTest())
        h.current(); h.tick(2_000)
        assertFalse(h.calls.drop(before).any { it.path == "b" || it.path == "c" })
        assertTrue(h.calls.drop(before).any { it.path == "" })
        h.close()
    }

    @Test fun eventDuringReadAndAtPublicationCannotBeClearedByEarlierScan() = runTest {
        val h = Harness(this)
        h.show(); h.current(); h.tick(2_000)
        val response = CompletableDeferred<DirectoryPage>()
        var blocked = true
        h.loader = { _, _, _, _ -> if (blocked) response.await() else page(file("fresh")) }
        h.watches.event(""); h.current(); h.tick(200)
        h.watches.event("")
        blocked = false; response.complete(page(file("earlier")))
        h.current()
        assertNotNull(h.state.pageFreshness[""]!!.dirtySince)
        h.tick(2_000)
        assertEquals("fresh", h.state.children[""]!!.single().path)
        // A wake posted after the drain has gone idle still admits a new bounded read.
        h.watches.event(""); h.current(); h.tick(2_000)
        assertEquals(4, h.calls.size)
        h.close()
    }

    @Test fun sharedSevenProbeBatchPublishesCompletePagesAndResumesDeferredDirectoryFirst() = runTest {
        withDiscoveryFiles("a/one", "a/two", "b/one", "b/two") { root ->
            val state = ExplorerWorkspaceState().apply {
                children[""] = listOf(dir("a"), dir("b")); expandedPaths = listOf("a", "b")
                children["a"] = listOf(file("a/old")); children["b"] = listOf(file("b/old"))
            }
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("a", "b"), "a" to listOf("one", "two"), "b" to listOf("one", "two")))
            val h = Harness(this, state, ExplorerRefreshLimits(discovery = DiscoveryLimits(directoryProbes = 7, directoryOpens = 4)))
            h.loader = { path, after, budget, _ -> FileRepository(root).listFilesPageBounded(path, after, 250, state.options, budget, streams.traversal) }
            h.show(); h.owner.requestRefresh(h.lease, state.refreshToken); h.current()
            assertEquals(listOf("", "a", "b"), h.calls.map { it.path })
            assertEquals(7, streams.probes); assertEquals(3, streams.opened.size); assertEquals(0, streams.live)
            assertEquals(1, h.calls.map { it.budget }.distinct().size)
            assertEquals(listOf("a/one", "a/two"), state.children["a"]!!.map { it.path })
            assertEquals("b/old", state.children["b"]!!.single().path)
            assertEquals(ExplorerRefreshOutcome.PENDING, state.refreshOutcome)
            assertEquals(-1, state.loadedRefreshToken)
            h.tick(2_000)
            assertEquals("b", h.calls[3].path)
            assertEquals(listOf("b/one", "b/two"), state.children["b"]!!.map { it.path })
            assertEquals(state.refreshToken, state.loadedRefreshToken)
            assertEquals(ExplorerRefreshOutcome.COMPLETE, state.refreshOutcome)
            h.close()
        }
    }

    @Test fun directoryOpenLimitStopsTheBatchAndPreservesOldUntouchedPages() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("a"), dir("b")); expandedPaths = listOf("a", "b")
            children["a"] = emptyList(); children["b"] = listOf(file("b/old"))
        }
        val h = Harness(this, state, ExplorerRefreshLimits(discovery = DiscoveryLimits(directoryOpens = 2)))
        h.disk[""] = page(dir("a"), dir("b")); h.show(setOf("a", "b")); h.current()
        assertEquals(2, h.calls.first().budget.directoryOpenAttempts)
        assertEquals("b/old", state.children["b"]!!.single().path)
        h.tick(2_000)
        assertEquals("b", h.calls.last().path)
        assertTrue(state.children["b"]!!.isEmpty())
        h.close()
    }

    @Test fun wholeBudgetFailureAdvancesPassAndStormDoesNotReplayFailedDirectory() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("a"), dir("b")); expandedPaths = listOf("a", "b")
            children["a"] = listOf(file("a/old")); children["b"] = emptyList()
        }
        val h = Harness(this, state, ExplorerRefreshLimits(discovery = DiscoveryLimits(directoryProbes = 3, directoryOpens = 4)))
        h.disk[""] = page(dir("a"), dir("b")); h.disk["a"] = page(file("a/1"), file("a/2"), file("a/3"))
        h.disk["b"] = page(file("b/new"))
        h.show(setOf("a", "b")); h.owner.requestRefresh(h.lease, state.refreshToken); h.current()
        h.tick(2_000); h.tick(2_000)
        assertEquals("b/new", state.children["b"]!!.single().path)
        assertEquals("a/old", state.children["a"]!!.single().path)
        assertNotNull(state.pageFreshness["a"]!!.failure)
        assertEquals(ExplorerRefreshOutcome.INCOMPLETE, state.refreshOutcome)
        assertEquals(-1, state.loadedRefreshToken)
        val attempts = h.calls.count { it.path == "a" }
        repeat(10_000) { h.watches.event("a") }
        h.current(); h.tick(60_000)
        assertEquals(attempts, h.calls.count { it.path == "a" })
        h.owner.requestRefresh(h.lease, state.refreshToken)
        h.current()
        assertEquals(attempts, h.calls.count { it.path == "a" })
        h.close()
    }

    @Test fun failureAfterRootSuccessKeepsSiblingPageCursorAndImportNotice() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("a"), dir("b")); expandedPaths = listOf("a", "b")
            children["a"] = listOf(file("a/old")); children["b"] = listOf(file("b/keep"))
            moreAfter["a"] = DirectoryPageCursor("a/old", false, options)
            importNotice = "Import finished; sync is pending"
            treeFirstVisibleItemIndex = 7; treeFirstVisibleItemScrollOffset = 13
        }
        val h = Harness(this, state)
        h.loader = { path, _, _, _ -> when (path) {
            "" -> page(dir("a"), dir("b"), file("new"))
            "a" -> throw IOException("denied")
            else -> page(file("b/keep"))
        } }
        h.show(); h.owner.requestRefresh(h.lease, state.refreshToken); h.current()
        assertTrue(state.children[""]!!.any { it.path == "new" })
        assertEquals("a/old", state.children["a"]!!.single().path)
        assertNotNull(state.moreAfter["a"])
        assertEquals("b/keep", state.children["b"]!!.single().path)
        assertEquals("Import finished; sync is pending", state.importNotice)
        assertEquals(7, state.treeFirstVisibleItemIndex); assertEquals(13, state.treeFirstVisibleItemScrollOffset)
        assertTrue(state.refreshNotice!!.contains("denied"))
        h.close()
    }

    @Test fun recreatedUiWaitsForNonCancellableOldReadAndRejectsOldLeaseUpdates() = runTest {
        val h = Harness(this)
        val release = CompletableDeferred<DirectoryPage>()
        var first = true
        h.loader = { _, _, _, _ -> if (first) { first = false; withContext(NonCancellable) { release.await() } } else page(file("new")) }
        h.show(); h.current()
        val oldLease = h.lease
        h.owner.detach(oldLease)
        h.lease = h.owner.newUiLease(); h.show(); h.current()
        h.owner.updateVisibility(oldLease, false, false, false); h.owner.detach(oldLease)
        assertEquals(1, h.calls.size)
        release.complete(page(file("old"))); h.current(); h.tick(2_000)
        assertEquals(2, h.calls.size)
        assertEquals("new", h.state.children[""]!!.single().path)
        assertTrue(h.watches.peak <= 1)
        h.close()
    }

    @Test fun replacementWorkspaceOwnerUsesSameLaneUntilOldReadReallyFinishes() = runTest {
        val lane = Mutex()
        val old = Harness(this, lane = lane)
        val release = CompletableDeferred<DirectoryPage>()
        old.loader = { _, _, _, _ -> withContext(NonCancellable) { release.await() } }
        old.show(); old.current(); old.owner.close()
        val next = Harness(this, lane = lane)
        next.disk[""] = page(file("new-workspace")); next.show(); next.current()
        assertTrue(next.calls.isEmpty())
        release.complete(page(file("old-workspace"))); next.current()
        assertTrue(old.state.children.isEmpty())
        assertEquals("new-workspace", next.state.children[""]!!.single().path)
        assertTrue(old.watches.live.isEmpty())
        next.close()
    }

    @Test fun collapseAndSortFenceLateReadsAndEvictRefreshBookkeeping() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("src")); expandedPaths = listOf("src"); children["src"] = emptyList()
        }
        val h = Harness(this, state)
        h.disk[""] = page(dir("src")); h.show(); h.current(); h.tick(2_000)
        val release = CompletableDeferred<DirectoryPage>()
        h.loader = { path, _, _, _ -> if (path == "src") withContext(NonCancellable) { release.await() } else page(dir("src")) }
        h.owner.requestExpansion(h.lease, "src"); h.current()
        state.collapseDirectory("src"); h.owner.updateTreeScope(h.lease)
        state.changeSortOrder(ExplorerSortOrder.NAME_DESC); h.owner.updateTreeScope(h.lease)
        release.complete(page(file("src/stale"))); h.current(); h.tick(2_000)
        assertFalse("src" in state.children)
        assertFalse("src" in state.pageFreshness)
        assertFalse("src" in state.loadingPages)
        assertFalse("src" in state.moreAfter)
        h.close()
    }

    @Test fun invalidatedLoadMoreReadsFirstPageInsteadOfAppendingOldCursor() = runTest {
        val h = Harness(this)
        val cursor = DirectoryPageCursor("first", false, h.state.options)
        h.disk[""] = DirectoryPage(listOf(file("first")), cursor)
        h.show(); h.current(); h.tick(2_000)
        val release = CompletableDeferred<DirectoryPage>()
        h.loader = { _, after, _, _ -> if (after == null) page(file("fresh-first")) else release.await() }
        h.owner.requestMore(h.lease, ""); h.current()
        assertEquals(cursor, h.calls.last().after)
        h.watches.event("")
        release.complete(page(file("stale-next"))); h.current()
        assertEquals(listOf("fresh-first"), h.state.children[""]!!.map { it.path })
        assertNull(h.calls.last().after)
        h.close()
    }

    @Test fun repeatedCommandsStayBoundedAndCollapseDropsNeverSuccessfulFailureRecords() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = (0 until 100).map { dir("d$it") }; expandedPaths = (0 until 100).map { "d$it" }
        }
        val h = Harness(this, state)
        h.disk[""] = DirectoryPage(state.children[""]!!, null)
        h.show()
        repeat(10_000) { h.owner.requestExpansion(h.lease, "d${it % 100}") }
        assertTrue(h.owner.pendingDemandsForTest() <= 64)
        h.loader = { path, _, _, _ -> if (path.isEmpty()) h.disk.getValue("") else throw IOException("denied") }
        h.current()
        assertTrue(state.pageFreshness.values.any { it.failure != null })
        state.expandedPaths.toList().forEach(state::collapseDirectory)
        h.owner.updateTreeScope(h.lease); h.current()
        assertEquals(setOf(""), state.pageFreshness.keys)
        assertEquals(0, h.owner.pendingDemandsForTest())
        h.close()
    }

    @Test fun treeSearchTreeTransitionStopsPendingEventsAndSafetyUntilCommittedReturn() = runTest {
        val h = Harness(this)
        h.show(); h.current(); h.tick(2_000)
        h.watches.event("")
        h.state.query = "ab"
        h.owner.updateViewport(h.lease, emptySet())
        h.owner.updateVisibility(h.lease, true, true, explorerTreeMode(h.state.query))
        h.current(); h.tick(600_000)
        assertEquals(1, h.calls.size); assertTrue(h.watches.live.isEmpty())
        h.state.query = "*a?[]"
        h.owner.updateVisibility(h.lease, true, true, explorerTreeMode(h.state.query))
        h.current()
        assertEquals(2, h.calls.size)
        h.tick(1_000)
        assertEquals(2, h.calls.size)
        h.close()
    }

    @Test fun safetyRequiresFiveMinuteAgeAndAdmitsAtMostOneVisibleDirectoryPerMinute() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("a"), dir("offscreen")); expandedPaths = listOf("a", "offscreen")
            children["a"] = emptyList(); children["offscreen"] = emptyList()
        }
        val h = Harness(this, state)
        h.disk[""] = page(dir("a"), dir("offscreen")); h.show(setOf("a")); h.current()
        assertEquals(listOf("", "a"), h.calls.map { it.path })
        h.tick(299_999); assertEquals(2, h.calls.size)
        h.tick(1); assertEquals(3, h.calls.size)
        h.tick(59_999); assertEquals(3, h.calls.size)
        h.tick(1); assertEquals(4, h.calls.size)
        assertFalse(h.calls.any { it.path == "offscreen" })
        h.close()
    }

    @Test fun failedNeverSuccessfulPageDoesNotRetryEverySafetyMinute() = runTest {
        val state = ExplorerWorkspaceState().apply { children[""] = listOf(dir("denied")); expandedPaths = listOf("denied") }
        val h = Harness(this, state)
        h.loader = { path, _, _, _ -> if (path.isEmpty()) page(dir("denied")) else throw IOException("denied") }
        h.show(setOf("denied")); h.current()
        assertEquals(1, h.calls.count { it.path == "denied" })
        h.tick(299_999)
        assertEquals(1, h.calls.count { it.path == "denied" })
        h.tick(60_001)
        val attempts = h.calls.count { it.path == "denied" }
        assertEquals(2, attempts)
        h.tick(240_000)
        assertEquals(attempts, h.calls.count { it.path == "denied" })
        h.close()
    }

    @Test fun safetyReachesViewportDirectoriesOutsideWatchCapWhileBusyRootStaysFresh() = runTest {
        val names = (0 until 70).map { "d$it" }
        val state = ExplorerWorkspaceState().apply {
            children[""] = names.map(::dir); expandedPaths = names
            names.forEach { children[it] = emptyList(); pageFreshness[it] = ExplorerPageFreshness(succeededAt = 0) }
        }
        val h = Harness(this, state)
        h.disk[""] = DirectoryPage(names.map(::dir), null)
        h.show(); h.current()
        // Viewport entry sees recent pages and therefore does not enqueue 70 demand reads.
        h.owner.updateViewport(h.lease, names.toSet()); h.current()
        h.tick(300_000)
        repeat(72) { h.watches.event(""); h.current(); h.tick(60_000) }
        assertTrue(names.all { name -> h.calls.any { it.path == name } })
        assertTrue(h.watches.peak <= 64)
        h.close()
    }

    @Test fun fiveSecondDrainEnforces45SecondCooldownAndRejectsExpiredPage() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("a")); expandedPaths = listOf("a"); children["a"] = emptyList()
        }
        val h = Harness(this, state)
        h.loader = { path, _, _, _ -> if (path.isEmpty()) { delay(5_000); page(file("expired")) } else page(file("a/fresh")) }
        h.show(setOf("a")); h.current(); h.tick(5_000)
        assertEquals("a", state.children[""]!!.single().path)
        h.tick(44_999); assertEquals(1, h.calls.size)
        h.tick(1); assertEquals(2, h.calls.size)
        assertEquals("a/fresh", state.children["a"]!!.single().path)
        h.close()
    }

    @Test fun successful100MillisecondDrainEnforcesTwoSecondMinimumCooldown() = runTest {
        val h = Harness(this)
        h.loader = { _, _, _, _ -> delay(100); page() }
        h.show(); h.current(); h.tick(100)
        h.watches.event(""); h.current()
        h.tick(1_999); assertEquals(1, h.calls.size)
        h.tick(1); assertEquals(2, h.calls.size)
        h.tick(100); h.close()
    }

    private class QueuedIo : CoroutineDispatcher() {
        private val queued = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun runQueued() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    @Test fun closeAfterIoRegistrationBeforeMainReturnStillClosesTransferredHandle() = runTest {
        val io = QueuedIo()
        val h = Harness(this, ioDispatcher = io)
        h.show(); h.current() // Worker has dispatched registration but IO has not executed it.
        assertTrue(h.watches.all.isEmpty())
        io.runQueued() // A live handle now awaits its return to the distinct Main dispatcher.
        assertEquals(1, h.watches.live.size)
        h.owner.close()
        h.current(); io.runQueued(); h.current()
        assertTrue(h.watches.live.isEmpty())
        assertTrue(h.calls.isEmpty())
    }

    @Test fun automaticViewportDemandAndPendingSafetyAreRecheckedAfterScroll() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("a"), dir("b")); expandedPaths = listOf("a", "b")
            children["a"] = emptyList(); children["b"] = emptyList()
        }
        val h = Harness(this, state)
        h.disk[""] = page(dir("a"), dir("b")); h.show(); h.current()
        h.owner.updateViewport(h.lease, setOf("a")) // Stale page demand is held by cooldown.
        h.current()
        h.owner.updateViewport(h.lease, setOf("b"))
        h.tick(2_000)
        assertFalse(h.calls.any { it.path == "a" })
        assertTrue(h.calls.any { it.path == "b" })
        h.close()
    }

    @Test fun failedSafetyAttemptsRotatePastTheFirstFiveDirectories() = runTest {
        val names = (0 until 8).map { "denied$it" }
        val state = ExplorerWorkspaceState().apply {
            children[""] = names.map(::dir); expandedPaths = names
            names.forEach { children[it] = emptyList(); pageFreshness[it] = ExplorerPageFreshness(succeededAt = 0) }
        }
        val h = Harness(this, state)
        h.disk[""] = DirectoryPage(names.map(::dir), null); h.show(); h.current()
        h.owner.updateViewport(h.lease, names.toSet()); h.current()
        h.loader = { _, _, _, _ -> throw IOException("denied") }
        h.tick(300_000)
        repeat(9) { h.tick(60_000) }
        assertTrue(names.all { name -> h.calls.any { it.path == name } })
        h.close()
    }

    @Test fun unrelatedCollapseDuringDirtyReadRestoresAdmittedInvalidation() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("a"), dir("b")); expandedPaths = listOf("a", "b")
            children["a"] = emptyList(); children["b"] = emptyList()
        }
        val h = Harness(this, state)
        h.disk[""] = page(dir("a"), dir("b")); h.show(setOf("a", "b")); h.current(); h.tick(2_000)
        val release = CompletableDeferred<DirectoryPage>()
        var blocked = true
        h.loader = { path, _, _, _ -> if (path == "a" && blocked) withContext(NonCancellable) { release.await() } else page(file("$path/fresh")) }
        h.watches.event("a"); h.current(); h.tick(200)
        state.collapseDirectory("b"); h.owner.updateTreeScope(h.lease)
        blocked = false; release.complete(page(file("a/obsolete")))
        h.current(); h.tick(2_000)
        assertEquals("a/fresh", state.children["a"]!!.single().path)
        assertFalse("b" in state.children)
        h.close()
    }

    @Test fun safetyAttemptsQuietViewportPageWhileEveryRootReadIsInvalidatedInFlight() = runTest {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("quiet")); expandedPaths = listOf("quiet"); children["quiet"] = emptyList()
        }
        val h = Harness(this, state)
        h.disk[""] = page(dir("quiet")); h.show(setOf("quiet")); h.current(); h.tick(2_000)
        h.loader = { path, _, _, _ ->
            if (path.isEmpty()) {
                h.watches.event("") // Every complete root read remains dirty, with no new success timestamp.
                page(dir("quiet"))
            } else page(file("quiet/fresh"))
        }
        h.watches.event(""); h.current(); h.tick(360_000)
        assertEquals(0L, state.pageFreshness[""]!!.succeededAt)
        assertTrue(state.pageFreshness[""]!!.attemptedAt!! > 0L)
        assertEquals(2, h.calls.count { it.path == "quiet" })
        assertEquals("quiet/fresh", state.children["quiet"]!!.single().path)
        h.close()
    }
}
