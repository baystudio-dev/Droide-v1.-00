package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class StreamedDiskEditorBarrierTest {
    @Test fun initializedAbsentTargetPublishesOnce() {
        val state = EditorWorkspaceState().apply { completeRecovery() }
        var calls = 0
        state.commitDiskOnly("src/new.txt") { calls++ }
        assertEquals(1, calls)
    }
    @Test fun unloadedOpenTargetAndAncestorsNeverFallBackToDisk() {
        val state = EditorWorkspaceState().apply { completeRecovery(); document("src/file") }
        for (path in listOf("src/file", "src", "src/file/child")) {
            assertThrows(WorkspaceDocumentConflictException::class.java) { state.commitDiskOnly(path) { error("unsafe") } }
        }
    }
    @Test fun retainedAndRecoveryAndRetirementBlockPublication() {
        val state = EditorWorkspaceState()
        assertThrows(IllegalStateException::class.java) { state.commitDiskOnly("file") { error("unsafe") } }
        state.completeRecovery()
        state.retainRecoveryConflict("file", "retained", null)
        assertThrows(WorkspaceDocumentConflictException::class.java) { state.commitDiskOnly("file") { error("unsafe") } }
        state.sealForProjectHandoff(Any())
        assertThrows(IllegalStateException::class.java) { state.commitDiskOnly("other") { error("unsafe") } }
    }
    @Test fun normalizedPathsOnly() {
        val state = EditorWorkspaceState().apply { completeRecovery() }
        for (path in listOf("", "/file", "a//b", "a/../b", "./a", "a\\b", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) { state.commitDiskOnly(path) { error("unsafe") } }
        }
    }
    @Test fun directBackgroundRegistrationCannotInterleaveFinalCheckAndRename() {
        val state = EditorWorkspaceState().apply { completeRecovery() }
        val executor = Executors.newFixedThreadPool(2)
        val inAction = CountDownLatch(1); val release = CountDownLatch(1); val registered = CountDownLatch(1)
        try {
            val publication = executor.submit {
                state.commitDiskOnly("file") {
                    inAction.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    assertNull(state.peek("file"))
                }
            }
            assertTrue(inAction.await(5, TimeUnit.SECONDS))
            val registration = executor.submit { state.document("file"); registered.countDown() }
            assertFalse(registered.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            publication.get(5, TimeUnit.SECONDS); registration.get(5, TimeUnit.SECONDS)
            assertNotNull(state.peek("file"))
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun adapterRunsOnlyFinalActionOnEditorDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "editor-owner") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val state = EditorWorkspaceState().apply { completeRecovery() }
            val authority = EditorWorkspaceDocumentAuthority(state, editorDispatcher = dispatcher)
            var calls = 0
            authority.commitDiskOnly("file") { assertEquals("editor-owner", Thread.currentThread().name); calls++ }
            assertEquals(1, calls)
        } finally { dispatcher.close() }
    }
    @Test fun bridgeRejectsRebindingWhileWaitingForEditorBarrier() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delegate = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
            override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
            override suspend fun commitDiskOnly(path: String, action: () -> Unit) {
                entered.complete(Unit); release.await(); action()
            }
        }
        bridge.bind(delegate)
        var calls = 0
        val job = async { runCatching { bridge.commitDiskOnly("file") { calls++ } } }
        entered.await()
        bridge.bind(delegate)
        release.complete(Unit)
        assertTrue(job.await().exceptionOrNull() is IllegalStateException)
        assertEquals(0, calls)
    }
    @Test fun openingTargetWhilePublicationIsQueuedRejectsRename() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val occupied = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            executor.submit { occupied.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(occupied.await(5, TimeUnit.SECONDS))
            val state = EditorWorkspaceState().apply { completeRecovery() }
            val authority = EditorWorkspaceDocumentAuthority(state, editorDispatcher = dispatcher)
            var calls = 0
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { authority.commitDiskOnly("file") { calls++ } }
            }

            state.document("file")
            release.countDown()
            assertTrue(job.await().exceptionOrNull() is WorkspaceDocumentConflictException)
            assertEquals(0, calls)
        } finally { release.countDown(); dispatcher.close() }
    }
    @Test fun cancelledQueuedPublicationNeverInvokesAction() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val occupied = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            executor.submit { occupied.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(occupied.await(5, TimeUnit.SECONDS))
            val authority = EditorWorkspaceDocumentAuthority(EditorWorkspaceState().apply { completeRecovery() }, editorDispatcher = dispatcher)
            var calls = 0
            val job = async(start = CoroutineStart.UNDISPATCHED) { authority.commitDiskOnly("file") { calls++ } }
            job.cancel(); release.countDown()
            assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, calls)
        } finally { release.countDown(); dispatcher.close() }
    }

    @Test fun unrelatedOrdinaryEditorDoesNotBlockLargeDiskRoute() {
        val root = java.nio.file.Files.createTempDirectory("disk-editor-normal-").toFile()
        try {
            root.resolve("other").writeText("normal editor content")
            val state = EditorWorkspaceState().apply { completeRecovery(); document("other") }
            var calls = 0
            state.commitDiskOnlyChecked("target", { EditorDiskPublicationPaths.requireUnaliased(root, it) }) { calls++ }
            assertEquals(1, calls)
        } finally { root.deleteRecursively() }
    }
    @Test fun lexicalDotAliasStillCountsAsLiveTarget() {
        val state = EditorWorkspaceState().apply { completeRecovery(); document("./target") }
        assertThrows(WorkspaceDocumentConflictException::class.java) { state.commitDiskOnly("target") { error("unsafe") } }
    }
    @Test fun symlinkEditorAndRetainedAliasesFailExplicitlyWithoutFollowing() {
        val root = java.nio.file.Files.createTempDirectory("disk-editor-alias-").toFile()
        try {
            root.resolve("real").mkdir(); root.resolve("real/file").writeText("live")
            java.nio.file.Files.createSymbolicLink(root.resolve("alias").toPath(), root.resolve("real").toPath())
            for (retained in listOf(false, true)) {
                val state = EditorWorkspaceState().apply {
                    completeRecovery()
                    if (retained) retainRecoveryConflict("alias/file", "retained", null)
                    else document("alias/file")
                }
                assertThrows(WorkspaceRecoveryEditorUnavailableException::class.java) {
                    state.commitDiskOnlyChecked("real/file", { EditorDiskPublicationPaths.requireUnaliased(root, it) }) { error("unsafe") }
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun differentSpellingsSharingFileIdentityAreNotTreatedAsUnrelated() {
        val root = java.nio.file.Files.createTempDirectory("disk-editor-identity-").toFile()
        try {
            root.resolve("target").writeText("content")
            for (alias in listOf("TARGET", "hard-linked-copy")) {
                java.nio.file.Files.createLink(root.resolve(alias).toPath(), root.resolve("target").toPath())
                val state = EditorWorkspaceState().apply { completeRecovery(); document(alias) }
                assertThrows(WorkspaceRecoveryEditorUnavailableException::class.java) {
                    state.commitDiskOnlyChecked("target", { EditorDiskPublicationPaths.requireUnaliased(root, it, "target") }) { error("unsafe") }
                }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun pendingNewUnrelatedDocumentWithMissingParentRemainsAllowed() {
        val root = java.nio.file.Files.createTempDirectory("disk-editor-new-").toFile()
        try {
            val state = EditorWorkspaceState().apply { completeRecovery(); document("new-parent/unsaved") }
            var calls = 0
            state.commitDiskOnlyChecked("target", { EditorDiskPublicationPaths.requireUnaliased(root, it, "target") }) { calls++ }
            assertEquals(1, calls)
        } finally { root.deleteRecursively() }
    }

    @Test fun thrownPublicationIsNotRetried() {
        val state = EditorWorkspaceState().apply { completeRecovery() }
        val error = IllegalStateException("native publication outcome unknown")
        var calls = 0
        assertSame(error, runCatching { state.commitDiskOnly("file") { calls++; throw error } }.exceptionOrNull())
        assertEquals(1, calls)
    }
}
