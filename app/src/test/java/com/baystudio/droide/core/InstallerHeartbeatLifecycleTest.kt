package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InstallerHeartbeatLifecycleTest {
    @Test fun idleActivityDoesNotSpliceAStatusIntoSplitCredentials() = runBlocking {
        withTimeout(5_000) {
            val now = AtomicLong(0)
            val log = InstallerProgressLog()
            val reported = CompletableDeferred<String>()
            val result = withInstallerOutputHeartbeat(
                output = { log.append(it) },
                activity = { reported.complete(it) },
                heartbeat = InstallerOutputHeartbeat(intervalMs = 1, clockNanos = now::get),
            ) { output ->
                output("Authorization: Be")
                now.set(20_000_000_000L)
                assertTrue(reported.await().contains("Still working"))
                output("arer fixture-private-value\ncompleted\n")
                "installed"
            }
            assertEquals("installed", result)
            val tail = log.append("")
            assertFalse(tail.contains("fixture-private-value"))
            assertFalse(tail.contains("Still working"))
            assertTrue(tail.contains("completed"))
        }
    }

    @Test fun successfulCommandJoinsHeartbeatBeforeReturningAndDropsLateOutput() = runBlocking {
        withTimeout(5_000) {
            val reported = CompletableDeferred<Unit>()
            val activity = mutableListOf<String>()
            val output = mutableListOf<String>()
            lateinit var commandScope: Job
            lateinit var capturedCallback: (String) -> Unit
            val result = withInstallerOutputHeartbeat(output::add, {
                activity += it
                reported.complete(Unit)
            }, InstallerOutputHeartbeat(intervalMs = 1)) { callback ->
                capturedCallback = callback
                commandScope = currentCoroutineContext().job
                reported.await()
                callback("done\n")
                7
            }
            assertEquals(7, result)
            assertTrue(commandScope.isCompleted)
            assertFalse(commandScope.children.any())
            capturedCallback("late output must not update a completed operation")
            assertEquals(listOf("done\n"), output)
            assertTrue(activity.isNotEmpty())
            assertTrue(activity.all { !it.contains("%") })
        }
    }

    @Test fun commandFailureRetainsItsCauseAndStopsHeartbeat() = runBlocking {
        withTimeout(5_000) {
            val reported = CompletableDeferred<Unit>()
            lateinit var commandScope: Job
            try {
                withInstallerOutputHeartbeat({}, { reported.complete(Unit) }, InstallerOutputHeartbeat(intervalMs = 1)) {
                    commandScope = currentCoroutineContext().job
                    reported.await()
                    throw IllegalArgumentException("fixture command failed")
                }
                fail("Command failure was swallowed")
            } catch (failure: IllegalArgumentException) {
                assertEquals("fixture command failed", failure.message)
            }
            assertTrue(commandScope.isCompleted)
            assertFalse(commandScope.children.any())
        }
    }

    @Test fun userCancellationStopsHeartbeatAndJoinsCleanup() = runBlocking {
        withTimeout(5_000) {
            val reported = CompletableDeferred<Unit>()
            lateinit var commandScope: Job
            lateinit var callback: (String) -> Unit
            val output = mutableListOf<String>()
            val command = async {
                withInstallerOutputHeartbeat(output::add, { reported.complete(Unit) }, InstallerOutputHeartbeat(intervalMs = 1)) {
                    commandScope = currentCoroutineContext().job
                    callback = it
                    awaitCancellation()
                }
            }
            reported.await()
            command.cancelAndJoin()
            assertTrue(command.isCancelled)
            assertTrue(commandScope.isCompleted)
            assertFalse(commandScope.children.any())
            callback("late output after cancellation")
            assertTrue(output.isEmpty())
        }
    }
}
