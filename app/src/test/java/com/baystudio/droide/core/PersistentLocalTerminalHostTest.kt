package com.baystudio.droide.core

import android.content.Context
import android.app.ApplicationExitInfo
import com.termux.terminal.TerminalSession as TermuxSession
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the production retention/restore host; fake handles never launch native Android PTYs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistentLocalTerminalHostTest {
    @Test fun abnormalRestartAndLifecycleFlushPreserveDescriptorsWithoutStartingAnyNativeSession() = runBlocking<Unit> {
        val policy = StartupRecoveryPolicy.decide(PreviousProcessExit(1_000, 101, ApplicationExitInfo.REASON_CRASH_NATIVE), null)
        fixture(policy).use { fixture ->
            val key = "abnormal-${UUID.randomUUID()}"
            val store = TerminalReviveStore(fixture.context.filesDir)
            store.save(key, fixture.root, listOf(TerminalReviveDescriptor("saved", "Saved", fixture.root)), "saved")
            val stateFile = File(fixture.context.filesDir, ".droide/terminal-revive/$key.json")
            val original = stateFile.readBytes()
            assertTrue(fixture.host.reviveProject(key, fixture.root).isEmpty())
            fixture.host.flush(key, fixture.root)
            assertArrayEquals(original, stateFile.readBytes())
            assertTrue(fixture.host.reviveProject(key, fixture.root).isEmpty())
            assertTrue(fixture.created.isEmpty())
            assertNotNull(store.load(key, fixture.root))
            stateFile.delete()
        }
    }

    @Test fun explicitRecoveryLocalTabClosePreventsItsStaleRevivalAndPreservesOtherProjects() = runBlocking<Unit> {
        val policy = StartupRecoveryPolicy.decide(PreviousProcessExit(1_000, 101, ApplicationExitInfo.REASON_LOW_MEMORY), null)
        fixture(policy).use { fixture ->
            val key = "close-${UUID.randomUUID()}"
            val other = "keep-${UUID.randomUUID()}"
            val store = TerminalReviveStore(fixture.context.filesDir)
            for (project in listOf(key, other)) store.save(project, fixture.root,
                listOf(TerminalReviveDescriptor("saved", "Saved", fixture.root)), "saved")
            val otherFile = File(fixture.context.filesDir, ".droide/terminal-revive/$other.json")
            val originalOther = otherFile.readBytes()
            fixture.host.acknowledgeSuppressedLocalTabClose(key, fixture.root)
            fixture.host.flush(key, fixture.root)
            assertNull(store.load(key, fixture.root))
            fixture.host.flush(other, fixture.root)
            assertArrayEquals(originalOther, otherFile.readBytes())
            assertTrue(fixture.created.isEmpty())
            otherFile.delete()
        }
    }

    @Test fun globalBudgetRejectsBeforeSpawnAndClosingAnOwnedTabReleasesCapacity() = runBlocking<Unit> {
        fixture().use { fixture ->
            val host = fixture.host
            val first = (0 until PersistentLocalTerminalHost.MAX_RETAINED_LOCAL_TABS).map { index ->
                val key = "project-$index"
                key to host.create(key, fixture.root, "Local", fixture.root, activate = true)
            }
            val rejected = runCatching { host.create("one-too-many", fixture.root, "Local", fixture.root, true) }
            assertTrue(rejected.exceptionOrNull() is IllegalStateException)
            assertTrue(rejected.exceptionOrNull()!!.message!!.contains("across projects"))
            assertEquals(32, fixture.created.size)
            first.forEach { (key, tab) ->
                assertTrue(host.owns(key, tab.id, tab.session))
                assertEquals(0, (tab.session as FakeHandle).destroyed)
            }
            val (firstKey, firstTab) = first.first()
            assertTrue(host.remove(firstKey, fixture.root, firstTab.id))
            assertEquals(1, (firstTab.session as FakeHandle).destroyed)
            val replacement = host.create("one-too-many", fixture.root, "Replacement", fixture.root, true)
            assertTrue(host.owns("one-too-many", replacement.id, replacement.session))
            assertEquals(33, fixture.created.size)
            first.drop(1).forEach { (_, tab) -> assertEquals(0, (tab.session as FakeHandle).destroyed) }
            fixture.projects += first.map { it.first } + "one-too-many"
        }
    }

    @Test fun perProjectBudgetPreservesSelectionAndDoesNotConstructRejectedShell() = runBlocking<Unit> {
        fixture().use { fixture ->
            val tabs = (0 until 16).map { fixture.host.create("default", fixture.root, "Local", fixture.root, true) }
            assertEquals(tabs.last().id, fixture.host.activeId("default"))
            val result = runCatching { fixture.host.create("default", fixture.root, "Rejected", fixture.root, true) }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("local terminal tabs"))
            assertEquals(16, fixture.created.size)
            assertEquals(tabs.last().id, fixture.host.activeId("default"))
            assertEquals(tabs, fixture.host.snapshot("default"))
            assertTrue(fixture.created.all { it.started == 1 && it.destroyed == 0 })
            fixture.projects += "default"
        }
    }

    @Test fun capacityRejectedRestorePreservesLaunchMetadataAndCanBeRetriedAfterClose() = runBlocking<Unit> {
        fixture().use { fixture ->
            val store = TerminalReviveStore(fixture.context.filesDir)
            val restoredKey = "restore-${UUID.randomUUID()}"
            val nested = File(fixture.root, "src").apply { mkdirs() }
            store.save(restoredKey, fixture.root, listOf(TerminalReviveDescriptor("restore-t1", "Saved", nested)), "restore-t1")
            val stateFile = File(fixture.context.filesDir, ".droide/terminal-revive/$restoredKey.json")
            val originalBytes = stateFile.readBytes()
            val tabs = (0 until 32).map { index ->
                val key = "active-$index"
                key to fixture.host.create(key, fixture.root, "Existing", fixture.root, true)
            }
            val result = runCatching { fixture.host.reviveProject(restoredKey, fixture.root) }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertArrayEquals(originalBytes, stateFile.readBytes())
            assertEquals(32, fixture.created.size)
            assertTrue(fixture.host.snapshot(restoredKey).isEmpty())
            assertTrue(fixture.created.all { it.destroyed == 0 })
            val (key, tab) = tabs.first()
            fixture.host.remove(key, fixture.root, tab.id)
            val restored = fixture.host.reviveProject(restoredKey, fixture.root).single()
            assertEquals("restore-t1", restored.id)
            assertEquals("Saved", restored.title)
            assertEquals(nested.canonicalFile, restored.workDir.canonicalFile)
            assertEquals(restored.id, fixture.host.activeId(restoredKey))
            assertTrue(fixture.host.owns(restoredKey, restored.id, restored.session))
            assertEquals(33, fixture.created.size)
            fixture.projects += tabs.map { it.first } + restoredKey
        }
    }

    @Test fun restoreCreatesAtMostSixteenTabsAndPreservesSavedActiveTab() = runBlocking<Unit> {
        fixture().use { fixture ->
            val key = "restore-${UUID.randomUUID()}"
            val store = TerminalReviveStore(fixture.context.filesDir)
            val descriptors = (0 until 20).map { TerminalReviveDescriptor("saved-$it", "Tab $it", fixture.root) }
            store.save(key, fixture.root, descriptors, "saved-15")
            val revived = fixture.host.reviveProject(key, fixture.root)
            assertEquals(16, revived.size)
            assertEquals(16, fixture.created.size)
            assertEquals("saved-15", fixture.host.activeId(key))
            assertTrue(revived.all { fixture.host.owns(key, it.id, it.session) })
            assertEquals(revived, fixture.host.reviveProject(key, fixture.root))
            assertEquals(16, fixture.created.size)
            fixture.projects += key
        }
    }

    @Test fun failedCreateDestroysPartialHandleWithoutPublishingOrChangingExistingTab() = runBlocking<Unit> {
        fixture().use { fixture ->
            val first = fixture.host.create("default", fixture.root, "Existing", fixture.root, true)
            fixture.failNextStart = true
            val result = runCatching { fixture.host.create("default", fixture.root, "Broken", fixture.root, true) }
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals(2, fixture.created.size)
            assertEquals(1, fixture.created.last().destroyed)
            assertEquals(0, fixture.created.first().destroyed)
            assertEquals(listOf(first), fixture.host.snapshot("default"))
            assertEquals(first.id, fixture.host.activeId("default"))
            fixture.projects += "default"
        }
    }

    @Test fun failedRestoreDestroysEveryConstructedHandleIncludingOneWhoseStartFailed() = runBlocking<Unit> {
        fixture().use { fixture ->
            val key = "restore-${UUID.randomUUID()}"
            TerminalReviveStore(fixture.context.filesDir).save(key, fixture.root,
                listOf(TerminalReviveDescriptor("first", "First", fixture.root), TerminalReviveDescriptor("second", "Second", fixture.root)), "second")
            fixture.failOnFactoryCall = 2
            val result = runCatching { fixture.host.reviveProject(key, fixture.root) }
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals(2, fixture.created.size)
            assertTrue(fixture.created.all { it.started == 1 && it.destroyed == 1 })
            assertTrue(fixture.host.snapshot(key).isEmpty())
            assertNull(fixture.host.activeId(key))
            assertNull(TerminalReviveStore(fixture.context.filesDir).load(key, fixture.root))
            fixture.projects += key
        }
    }

    @Test fun emptyProjectHydrationHistoryIsBoundedWhileLiveShellOwnershipIsRetained() = runBlocking<Unit> {
        fixture().use { fixture ->
            val live = fixture.host.create("retained-live", fixture.root, "Live", fixture.root, true)
            repeat(PersistentLocalTerminalHost.MAX_HYDRATED_HISTORY + 32) { index ->
                assertTrue(fixture.host.reviveProject("empty-$index", fixture.root).isEmpty())
            }
            assertTrue(fixture.host.hydratedHistorySize <= PersistentLocalTerminalHost.MAX_HYDRATED_HISTORY)
            assertEquals(1, fixture.created.size)
            assertTrue(fixture.host.owns("retained-live", live.id, live.session))
            assertEquals(listOf(live), fixture.host.reviveProject("retained-live", fixture.root))
            assertEquals(0, fixture.created.single().destroyed)
            fixture.projects += "retained-live"
        }
    }

    @Test fun flushingLastClosedTabRemovesEmptyPersistenceAndHydrationEntry() = runBlocking<Unit> {
        fixture().use { fixture ->
            val key = "closed-${UUID.randomUUID()}"
            val tab = fixture.host.create(key, fixture.root, "Local", fixture.root, true)
            fixture.host.flush(key, fixture.root)
            assertNotNull(TerminalReviveStore(fixture.context.filesDir).load(key, fixture.root))
            fixture.host.remove(key, fixture.root, tab.id)
            fixture.host.flush(key, fixture.root)
            assertEquals(1, fixture.created.single().destroyed)
            assertEquals(0, fixture.host.hydratedHistorySize)
            assertNull(TerminalReviveStore(fixture.context.filesDir).load(key, fixture.root))
            assertTrue(fixture.host.snapshot(key).isEmpty())
            assertNull(fixture.host.activeId(key))
        }
    }

    private suspend fun fixture(recoveryPolicy: StartupRecoveryPolicy? = null) = Fixture(
        RuntimeEnvironment.getApplication(),
        requireNotNull(currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher),
        recoveryPolicy,
    )

    private class Fixture(val context: Context, mainDispatcher: CoroutineDispatcher, recoveryPolicy: StartupRecoveryPolicy?) : AutoCloseable {
        val root = File(context.filesDir, "host-test-${UUID.randomUUID()}").apply { mkdirs() }
        val created = mutableListOf<FakeHandle>()
        val projects = mutableSetOf<String>()
        var failNextStart = false
        var failOnFactoryCall = -1
        // Robolectric suites can change SDK/main Looper in one JVM while the global
        // Dispatchers.Main still belongs to a prior SDK. Preserve this test's actual
        // main thread through runBlocking's event loop without changing global state.
        val host = PersistentLocalTerminalHost.forTesting(context, mainDispatcher, recoveryPolicy) { _, _ ->
            FakeHandle(failNextStart || created.size + 1 == failOnFactoryCall).also {
                failNextStart = false
                created += it
            }
        }
        override fun close() {
            projects.forEach { key -> host.destroyProject(key, root) }
            runBlocking { projects.forEach { key -> host.flush(key, root) } }
            root.deleteRecursively()
        }
    }

    @Test fun verifiedExitMetadataRemovalDoesNotReplayDestroyAndPreservesOtherOwners() = runBlocking<Unit> {
        fixture().use { fixture ->
            val key = "verified-exit-${UUID.randomUUID()}"
            fixture.projects += key
            val first = fixture.host.create(key, fixture.root, "First", fixture.root, true)
            val second = fixture.host.create(key, fixture.root, "Second", fixture.root, true)
            assertTrue(runCatching { fixture.host.removeAfterVerifiedExit(key, fixture.root, first.id, second.session) }.isFailure)
            assertTrue(fixture.host.owns(key, first.id, first.session))
            assertTrue(fixture.host.owns(key, second.id, second.session))
            fixture.host.removeAfterVerifiedExit(key, fixture.root, first.id, first.session)
            fixture.host.removeAfterVerifiedExit(key, fixture.root, first.id, first.session)
            fixture.host.flush(key, fixture.root)
            assertFalse(fixture.host.owns(key, first.id, first.session))
            assertTrue(fixture.host.owns(key, second.id, second.session))
            assertEquals(0, (first.session as FakeHandle).destroyed)
            assertEquals(0, (second.session as FakeHandle).destroyed)
            assertEquals(second.id, fixture.host.activeId(key))
            assertEquals(listOf(second.id), TerminalReviveStore(fixture.context.filesDir).load(key, fixture.root)!!.tabs.map { it.id })
        }
    }

    private class FakeHandle(private val failStart: Boolean) : NativePtySessionHandle {
        override val output = MutableStateFlow("")
        override val terminalTitle = MutableStateFlow<String?>(null)
        override val workingDirectory = MutableStateFlow<String?>(null)
        override val nativePtySession: TermuxSession get() = error("test must not instantiate native PTY")
        override val displayedPtySession: StateFlow<TermuxSession> get() = error("test must not instantiate native PTY")
        var started = 0
        var destroyed = 0
        override fun start() { started++; if (failStart) throw IOException("partial native startup fixture") }
        override fun destroy() { destroyed++ }
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun writeRaw(text: String) = Unit
        override fun setScreenUpdateListener(listener: (() -> Unit)?) = Unit
        override fun setBellListener(listener: (() -> Unit)?) = Unit
        override fun captureTranscript() = ""
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }
}
