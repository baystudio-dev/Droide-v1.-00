package com.baystudio.droide.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceToolchainPreferencesTest {
    @Test(timeout = 15_000) fun differentFamilyEditsAcrossInstancesCannotReadTheSameOldMap() = workspace { context, root ->
        orderedMutations(context, root,
            first = { it.set("runtime.node", "22") },
            second = { it.set("runtime.python", "3.12") })
        assertEquals(mapOf("runtime.node" to "22", "runtime.python" to "3.12"),
            WorkspaceToolchainPreferences(context, root).selections())
    }

    @Test(timeout = 15_000) fun clearAndSetKeepAReadModifyApplyOrderAcrossInstances() = workspace { context, root ->
        WorkspaceToolchainPreferences(context, root).set("runtime.node", "20")
        orderedMutations(context, root,
            first = { it.clear("runtime.node") },
            second = { it.set("runtime.python", "3.12") })
        assertEquals(mapOf("runtime.python" to "3.12"), WorkspaceToolchainPreferences(context, root).selections())
    }

    @Test(timeout = 15_000) fun pruneCannotOverwriteAConcurrentDifferentFamilySet() = workspace { context, root ->
        val preferences = WorkspaceToolchainPreferences(context, root)
        preferences.set("runtime.node", "22")
        preferences.set("runtime.ruby", "3.3")
        orderedMutations(context, root,
            first = { it.prune(setOf("runtime.node" to "22")) },
            second = { it.set("runtime.python", "3.12") })
        assertEquals(mapOf("runtime.node" to "22", "runtime.python" to "3.12"), preferences.selections())
    }

    @Test fun canonicalAliasesShareSelectionsAndDifferentWorkspacesKeepSeparateValues() = workspace { context, root ->
        val child = File(root, "child").also { check(it.mkdir()) }
        val first = WorkspaceToolchainPreferences(context, root)
        val alias = WorkspaceToolchainPreferences(context, File(child, ".."))
        val other = WorkspaceToolchainPreferences(context, child)
        assertEquals(first.workspaceId, alias.workspaceId)
        first.set("runtime.node", "20")
        alias.set("runtime.python", "3.12")
        other.set("runtime.node", "22")
        assertEquals(mapOf("runtime.node" to "20", "runtime.python" to "3.12"), alias.selections())
        assertEquals(first.selections(), alias.selections())
        assertEquals(mapOf("runtime.node" to "22"), other.selections())
    }

    @Test fun retainedOwnerCannotSetClearOrPruneAfterClose() = workspace { context, root ->
        val owner = InstallerOperationCoordinator()
        val preferences = WorkspaceToolchainPreferences(context, root) { mutation ->
            check(owner.publish(block = mutation)) { "Closed owner" }
        }
        preferences.set("runtime.node", "22")
        owner.close()
        listOf<() -> Unit>(
            { preferences.set("runtime.python", "3.12") },
            { preferences.clear("runtime.node") },
            { preferences.prune(emptySet()) },
        ).forEach { mutation ->
            assertTrue(runCatching(mutation).exceptionOrNull() is IllegalStateException)
            assertEquals(mapOf("runtime.node" to "22"), preferences.selections())
        }
    }

    @Test(timeout = 15_000) fun lifecycleAdmissionRunsBeforeAcquiringThePreferenceMonitor() = workspace { context, root ->
        val owner = InstallerOperationCoordinator()
        val independent = WorkspaceToolchainPreferences(context, root)
        val preferences = WorkspaceToolchainPreferences(context, root) { mutation ->
            check(owner.publish {
                // A wrong preference -> lifecycle order would retain the preference monitor
                // during admission and deadlock this independent writer. No sleeps or stress loop.
                val failure = AtomicReference<Throwable?>()
                val writer = thread(isDaemon = true, name = "preference-admission-order") {
                    try { independent.set("runtime.python", "3.12") } catch (error: Throwable) { failure.set(error) }
                }
                writer.join(3_000)
                check(!writer.isAlive) { "Preference monitor was held before lifecycle admission" }
                failure.get()?.let { throw it }
                mutation()
            })
        }
        preferences.set("runtime.node", "22")
        assertEquals(mapOf("runtime.node" to "22", "runtime.python" to "3.12"), preferences.selections())
    }

    @Test fun clearingLastEntryAndPruningAllEntriesRemoveOnlyTheirWorkspaceKey() = workspace { context, root ->
        val preferences = WorkspaceToolchainPreferences(context, root)
        val child = File(root, "child").also { check(it.mkdir()) }
        val other = WorkspaceToolchainPreferences(context, child)
        other.set("runtime.node", "20")
        val raw = context.getSharedPreferences("droide_workspace_toolchains", Context.MODE_PRIVATE)
        val key = "workspace_" + preferences.workspaceId
        val otherKey = "workspace_" + other.workspaceId
        assertNotEquals(key, otherKey)
        preferences.set("runtime.node", "22")
        preferences.clear("runtime.node")
        assertFalse(raw.contains(key))
        assertTrue(raw.contains(otherKey))
        assertEquals(mapOf("runtime.node" to "20"), other.selections())
        preferences.set("runtime.python", "3.12")
        preferences.prune(emptySet())
        assertFalse(raw.contains(key))
        assertTrue(preferences.selections().isEmpty())
        assertTrue(raw.contains(otherKey))
        assertEquals(mapOf("runtime.node" to "20"), other.selections())
    }

    @Test fun reviewedCreationRejectsAnySelectionMapChangeBeforeCallingFactory() = workspace { context, root ->
        val preferences = WorkspaceToolchainPreferences(context, root)
        val expected = preferences.selections()
        preferences.set("runtime.node", "22") // Compare the complete map, not only the Sqruff entry.
        var calls = 0
        assertTrue(runCatching { preferences.withUnchangedSelections(expected) { calls++; "created" } }
            .exceptionOrNull() is IllegalStateException)
        assertEquals(0, calls)
        assertEquals("created", preferences.withUnchangedSelections(preferences.selections()) { calls++; "created" })
        assertEquals(1, calls)
    }

    @Test(timeout = 15_000) fun reviewedCreationKeepsSelectionMutationBlockedThroughTheFactory() = workspace { context, root ->
        val preferences = WorkspaceToolchainPreferences(context, root)
        val other = WorkspaceToolchainPreferences(context, root)
        val expected = preferences.selections()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val failures = AtomicReference<Throwable?>()
        val creator = thread(start = false, isDaemon = true, name = "reviewed-sqruff-create") {
            try {
                preferences.withUnchangedSelections(expected) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    assertEquals(expected, preferences.selections())
                    calls.incrementAndGet()
                }
            } catch (failure: Throwable) { failures.compareAndSet(null, failure) }
        }
        val writer = thread(start = false, isDaemon = true, name = "reviewed-sqruff-selection") {
            try { other.set("lsp.sqruff", "0.41.0") }
            catch (failure: Throwable) { failures.compareAndSet(null, failure) }
        }
        try {
            creator.start()
            check(entered.await(3, TimeUnit.SECONDS))
            writer.start()
            val monitor = HostThreadMonitor()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var blocked = false
            while (System.nanoTime() < deadline && writer.isAlive) {
                if (monitor.isBlockedBy(writer, creator)) { blocked = true; break }
                Thread.yield()
            }
            assertTrue("Selection mutation must wait until the actual creation action returns", blocked)
            assertEquals(0, calls.get())
        } finally {
            release.countDown()
            creator.join(3_000)
            if (writer.state != Thread.State.NEW) writer.join(3_000)
        }
        assertFalse(creator.isAlive); assertFalse(writer.isAlive)
        failures.get()?.let { throw it }
        assertEquals(1, calls.get())
        assertEquals(mapOf("lsp.sqruff" to "0.41.0"), preferences.selections())
    }

    @Test fun reviewedCreationFailureReleasesTheSelectionMonitor() = workspace { context, root ->
        val preferences = WorkspaceToolchainPreferences(context, root)
        val failure = IllegalStateException("authored factory failed")
        assertSame(failure, runCatching {
            preferences.withUnchangedSelections(preferences.selections()) { throw failure }
        }.exceptionOrNull())
        preferences.set("lsp.sqruff", "0.41.0")
        assertEquals(mapOf("lsp.sqruff" to "0.41.0"), preferences.selections())
    }

    /** Pause the first actual read while a second instance attempts its mutation. In the fixed
     * implementation the second thread is observed BLOCKED on the first thread's monitor;
     * without the RMW lock its second read is observed instead, so the assertion fails. */
    private fun orderedMutations(
        context: Context,
        root: File,
        first: (WorkspaceToolchainPreferences) -> Unit,
        second: (WorkspaceToolchainPreferences) -> Unit,
    ) {
        val firstRead = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val reads = AtomicInteger()
        val store = context.getSharedPreferences("droide_workspace_toolchains", Context.MODE_PRIVATE)
        val intercepted = object : SharedPreferences by store {
            override fun getString(key: String?, defValue: String?): String? {
                val value = store.getString(key, defValue)
                if (reads.incrementAndGet() == 1) {
                    firstRead.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS)) { "First mutation was not released" }
                }
                return value
            }
        }
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = intercepted
        }
        val a = WorkspaceToolchainPreferences(wrapped, root)
        val b = WorkspaceToolchainPreferences(wrapped, root)
        val failure = AtomicReference<Throwable?>()
        fun worker(name: String, action: () -> Unit) = thread(start = false, isDaemon = true, name = name) {
            try { action() } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        val one = worker("preference-first") { first(a) }
        val two = worker("preference-second") { second(b) }
        try {
            one.start()
            check(firstRead.await(3, TimeUnit.SECONDS)) { "First read was not reached" }
            two.start()
            val threads = HostThreadMonitor()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var blockedByFirst = false
            while (System.nanoTime() < deadline && reads.get() < 2 && two.isAlive) {
                if (threads.isBlockedBy(two, one)) {
                    blockedByFirst = true
                    break
                }
                Thread.yield()
            }
            assertTrue("Second instance must wait for the first whole read-modify-apply", blockedByFirst)
            assertEquals("Second instance read stale preferences before first apply", 1, reads.get())
        } finally {
            releaseFirst.countDown()
            one.join(3_000)
            if (two.state != Thread.State.NEW) two.join(3_000)
        }
        assertFalse("First mutation did not finish", one.isAlive)
        assertFalse("Second mutation did not finish", two.isAlive)
        failure.get()?.let { throw it }
    }

    /** Android's compile API omits java.management, but these tests run on a host JVM.
     * Resolve public API declarations, not the bean's inaccessible implementation class.
     * Reflection failures fail the test; they never substitute a weaker scheduling check. */
    private class HostThreadMonitor {
        private val threads = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        private val getThreadInfo = Class.forName("java.lang.management.ThreadMXBean")
            .getMethod("getThreadInfo", java.lang.Long.TYPE)
        private val threadInfo = Class.forName("java.lang.management.ThreadInfo")
        private val getThreadState = threadInfo.getMethod("getThreadState")
        private val getLockOwnerId = threadInfo.getMethod("getLockOwnerId")

        fun isBlockedBy(thread: Thread, owner: Thread): Boolean {
            val info = getThreadInfo.invoke(threads, thread.id) ?: return false
            return getThreadState.invoke(info) == Thread.State.BLOCKED &&
                (getLockOwnerId.invoke(info) as Long) == owner.id
        }
    }

    private fun workspace(block: (Context, File) -> Unit) {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("droide-workspace-toolchains-").toFile()
        try { block(context, root) } finally {
            val store = context.getSharedPreferences("droide_workspace_toolchains", Context.MODE_PRIVATE)
            store.edit().remove("workspace_" + AndroidDevelopmentManager.stableProjectId(root))
                .remove("workspace_" + AndroidDevelopmentManager.stableProjectId(File(root, "child"))).apply()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }
}
