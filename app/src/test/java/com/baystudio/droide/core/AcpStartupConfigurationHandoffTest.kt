package com.baystudio.droide.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Tests the real handoff component, with explicit lifecycle inputs instead of an Android host. */
class AcpStartupConfigurationHandoffTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun mode(value: String) = json("{\"sessionUpdate\":\"current_mode_update\",\"currentModeId\":\"$value\"}")
    private fun response(owner: String = "owned", current: String = "default") = json(
        """{"sessionId":"$owner","modes":{"currentModeId":"$current","availableModes":[{"id":"default"},{"id":"plan"}]}}""")
    private class Fixture {
        val commands = AcpSessionCommands()
        @Volatile var session: String? = null
        @Volatile var readBinding = false
        @Volatile var failure: Throwable? = null
        val handoff = AcpStartupConfigurationHandoff(commands, { session }, { readBinding }, { failure })
        fun openLifecycle(owner: String = "owned") { commands.bind(owner); session = owner; readBinding = true }
        fun snapshot(owner: String = "owned"): WorkspaceAgentSessionConfiguration {
            var value: WorkspaceAgentSessionConfiguration? = null
            handoff.installConfigurationListener(owner, { value = it }, {})
            return checkNotNull(value)
        }
    }

    @Test fun retainedUpdatesBeforeBindingAndLiveUpdatesAfterHandoffStayOrdered() {
        val f = Fixture()
        f.handoff.retainStartupConfiguration("session/new", null, response())
        f.handoff.observe("owned", mode("plan"))
        f.openLifecycle()
        var value: WorkspaceAgentSessionConfiguration? = null
        val order = mutableListOf<String>()
        f.handoff.installConfigurationListener("owned", { value = it; order += "snapshot:${it.currentModeId}" }, {
            value = WorkspaceAgentSessionConfigurations.update(it, checkNotNull(value)); order += "update:${value!!.currentModeId}"
        })
        assertEquals("plan", value!!.currentModeId)
        f.handoff.observe("owned", mode("default"))
        assertEquals(listOf("snapshot:plan", "update:default"), order)
    }

    @Test fun preResponseAndWrongOwnerNotificationsCannotSelectStartupIdentity() {
        val f = Fixture()
        f.handoff.observe("owned", mode("plan"))
        f.handoff.retainStartupConfiguration("session/new", null, response())
        f.handoff.observe("other", mode("plan"))
        f.openLifecycle()
        assertEquals("default", f.snapshot().currentModeId)
    }

    @Test fun loadWithoutReturnedSessionIdUsesTheRequestedOwner() {
        val f = Fixture()
        val result = JsonObject(response("loaded").filterKeys { it != "sessionId" })
        f.handoff.retainStartupConfiguration("session/load", "loaded", result)
        f.openLifecycle("loaded")
        assertEquals("default", f.snapshot("loaded").currentModeId)
    }

    @Test fun loadRejectsChangedAndNonStringReturnedSessionIdentity() {
        for (id in listOf("\"other\"", "null", "42", "[]")) {
            val f = Fixture()
            val failure = assertThrows(IllegalArgumentException::class.java) {
                f.handoff.retainStartupConfiguration("session/load", "loaded", json("{\"sessionId\":$id}"))
            }
            assertEquals("ACP load response does not match the requested session", failure.message)
        }
    }

    @Test fun newSessionRejectsMissingBlankOversizedAndNonStringIdentity() {
        val cases = listOf(json("{}"), json("{\"sessionId\":null}"), json("{\"sessionId\":42}"),
            json("{\"sessionId\":\" \"}"), buildJsonObject { put("sessionId", "x".repeat(513)) })
        for (result in cases) {
            val f = Fixture()
            val failure = assertThrows(IllegalArgumentException::class.java) { f.handoff.retainStartupConfiguration("session/new", null, result) }
            assertEquals("ACP startup response has no valid session identity", failure.message)
        }
    }

    @Test fun failedSnapshotDeliveryRetainsLatestStateAndDoesNotInstallSubscription() {
        val f = Fixture()
        f.handoff.retainStartupConfiguration("session/new", null, response())
        f.openLifecycle()
        val failure = IllegalStateException("snapshot receiver failed")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            f.handoff.installConfigurationListener("owned", { throw failure }, { fail("Not installed") })
        })
        assertNull(f.handoff.listener)
        f.handoff.observe("owned", mode("plan"))
        assertEquals("plan", f.snapshot().currentModeId)
    }

    @Test fun retainedSnapshotCannotBeReplacedAndSubscriptionCanInstallOnlyOnce() {
        val f = Fixture()
        f.handoff.retainStartupConfiguration("session/new", null, response())
        assertThrows(IllegalStateException::class.java) { f.handoff.retainStartupConfiguration("session/new", null, response("other")) }
        f.openLifecycle()
        assertEquals("default", f.snapshot().currentModeId)
        assertThrows(IllegalStateException::class.java) { f.handoff.installConfigurationListener("owned", {}, {}) }
        assertThrows(IllegalStateException::class.java) { f.handoff.retainStartupConfiguration("session/new", null, response()) }
    }

    @Test fun closedTransportRejectsRetentionAndHandoffAtTheirOwnBoundaries() {
        val before = Fixture(); before.failure = IllegalStateException("closed")
        assertThrows(IllegalStateException::class.java) { before.handoff.retainStartupConfiguration("session/new", null, response()) }
        val after = Fixture(); after.handoff.retainStartupConfiguration("session/new", null, response()); after.openLifecycle()
        after.failure = IllegalStateException("closed")
        assertThrows(IllegalStateException::class.java) { after.handoff.installConfigurationListener("owned", { fail("Closed snapshot delivered") }, {}) }
        assertNull(after.handoff.listener)
    }

    @Test fun wrongBoundSessionAndAbsentReadBindingCannotConsumeHandoff() {
        val f = Fixture(); f.handoff.retainStartupConfiguration("session/new", null, response()); f.openLifecycle()
        f.session = "other"
        assertThrows(IllegalStateException::class.java) { f.handoff.installConfigurationListener("owned", { fail("Wrong binding") }, {}) }
        f.session = "owned"; f.readBinding = false
        assertThrows(IllegalStateException::class.java) { f.handoff.installConfigurationListener("owned", { fail("Missing binding") }, {}) }
        f.readBinding = true
        assertEquals("default", f.snapshot().currentModeId)
    }

    @Test fun retainedOwnerMismatchRejectsEvenWhenLifecycleMatchesRequestedOwner() {
        val f = Fixture(); f.handoff.retainStartupConfiguration("session/new", null, response()); f.openLifecycle("other")
        val failure = assertThrows(IllegalStateException::class.java) {
            f.handoff.installConfigurationListener("other", { fail("Foreign startup snapshot delivered") }, {})
        }
        assertEquals("ACP startup configuration belongs to another session", failure.message)
    }

    @Test(timeout = 5_000) fun concurrentUpdateWaitsForSnapshotAndThenReachesInstalledListener() {
        val f = Fixture(); f.handoff.retainStartupConfiguration("session/new", null, response()); f.openLifecycle()
        // Observe the actual private production monitor without adding a production test hook.
        // Monitor ownership is a deterministic assertion; transient Thread.State sampling is not.
        val monitor = f.handoff.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(f.handoff)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val attempted = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable?>()
        val installer = Thread {
            try { f.handoff.installConfigurationListener("owned", {
                assertTrue("Snapshot callback must hold the handoff monitor", Thread.holdsLock(monitor))
                order += "snapshot:${it.currentModeId}"; entered.countDown()
                release.await(); order += "snapshot-returned"
            }, {
                assertTrue("Update listener must hold the same handoff monitor", Thread.holdsLock(monitor))
                order += "update:${it["currentModeId"]!!.jsonPrimitive.content}"
            }) }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { entered.countDown() } // A failed installer must not strand the test driver.
        }
        val updater = Thread {
            try { attempted.countDown(); f.handoff.observe("owned", mode("plan")) }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        try {
            // The unchanged JUnit timeout is the single watchdog for these handshakes.
            installer.start(); entered.await(); assertNull(failure.get()); updater.start()
            attempted.await()
            assertEquals(listOf("snapshot:default"), order.toList())
        } finally { release.countDown(); installer.join(1_000); updater.join(1_000) }
        assertFalse(installer.isAlive); assertFalse(updater.isAlive); assertNull(failure.get())
        assertEquals(listOf("snapshot:default", "snapshot-returned", "update:plan"), order.toList())
    }

    @Test fun losingReadAuthorityDuringSnapshotPreventsSubscriptionCommit() {
        val f = Fixture(); f.handoff.retainStartupConfiguration("session/new", null, response()); f.openLifecycle()
        var delivered = false
        assertThrows(IllegalStateException::class.java) {
            f.handoff.installConfigurationListener("owned", { delivered = true; f.readBinding = false }, { fail("Not installed") })
        }
        assertTrue(delivered)
        assertNull(f.handoff.listener)
    }

    @Test fun liveListenerFailurePropagatesSynchronouslyToItsReaderCaller() {
        val f = Fixture(); f.handoff.retainStartupConfiguration("session/new", null, response()); f.openLifecycle()
        val expected = IllegalStateException("listener failed")
        f.handoff.installConfigurationListener("owned", {}, { throw expected })
        assertSame(expected, assertThrows(IllegalStateException::class.java) { f.handoff.observe("owned", mode("plan")) })
    }

    @Test fun commandSnapshotUsesTheBoundCollectorIncludingPreResponseAnnouncements() {
        val f = Fixture(); f.commands.beginCapture()
        f.commands.observe(json("""{"sessionId":"owned","update":{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"compact"}]}}"""))
        f.handoff.retainStartupConfiguration("session/new", null, response()); f.openLifecycle()
        assertEquals(listOf("compact"), f.snapshot().availableCommands)
    }
}
