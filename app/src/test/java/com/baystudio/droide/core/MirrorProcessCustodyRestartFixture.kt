package com.baystudio.droide.core

import android.app.Application
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Launched only by MirrorProcessCustodyRestartTest in a disposable child JVM. No CLI child is
 * started here; production launch/cleanup is covered separately. */
object MirrorProcessCustodyRestartFixture {
    @JvmStatic fun main(args: Array<String>) = JUnitCore.main(Worker::class.java.name)

    @RunWith(RobolectricTestRunner::class)
    @Config(application = Application::class, sdk = [35], manifest = Config.NONE)
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    class Worker {
        @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
        @Test fun runScenario() = runBlocking {
            val root = File(checkNotNull(System.getProperty("droide.custody.fixture.root")))
            val boundary = checkNotNull(System.getProperty("droide.custody.fixture.boundary"))
            val mode = checkNotNull(System.getProperty("droide.custody.fixture.mode"))
            val source = root.resolve("source"); val working = root.resolve("working")
            val directory = root.resolve(".working-sync-authority")
            // The JVM termination is real; only the trusted boot observations are simulated. This fixture
            // neither reboots nor certifies an Android device.
            val bootScenario = boundary.startsWith("writer-boot-")
            val oldBoot = WorkspaceProcessBootIdentity.Identity("a".repeat(64), "1b2bbbcf-a5f6-4221-96fb-521edc129d77")
            val currentBoot = oldBoot.copy(boot = "794dc4f3-97d3-43b9-a7d0-a4e667a864e2")
            if (mode == "hold") {
                assertTrue(source.mkdir()); source.resolve("file").writeText("preserved user bytes")
                LocalAgentWorkspaceMirror.prepareRetained(source, working)
                root.resolve("runtime").writeText(WorkspaceProcessCustody.runtimeId)
                if (bootScenario) {
                    MirrorMetadataAuthority(directory, source, working, processBootIdentity = { oldBoot })
                        .processCustody(UUID.randomUUID().toString()).acquire(working).also { it.withFactory({}, {}) }
                }
                if (boundary.startsWith("writer-cleanup-retirement-")) {
                    MirrorMetadataAuthority(directory, source, working, processBootIdentity = { null })
                        .processCustody().acquire(working).close()
                }
                val authority = MirrorMetadataAuthority(directory, source, working, processCheckpoint = { point ->
                    if (point == boundary) {
                        root.resolve("ready").writeText(point)
                        CountDownLatch(1).await()
                    }
                }, processBootIdentity = { if (bootScenario) currentBoot else null })
                if (bootScenario) authority.requireNoPendingProcessOwners()
                else authority.processCustody().acquire(working).close()
                fail("Crash boundary was not reached")
            } else {
                assertEquals("verify", mode)
                assertNotEquals(root.resolve("runtime").readText(), WorkspaceProcessCustody.runtimeId)
                val authority = MirrorMetadataAuthority(directory, source, working,
                    processBootIdentity = { if (bootScenario) currentBoot else null })
                when (boundary) {
                    "writer-admission-after-commit" -> {
                        assertEquals(1, authority.pendingProcessOwners().size)
                        // The owner died before receiving its new one-shot launch capability. Durable revocation is safe
                        // even with unknown boot.
                        authority.requireNoPendingProcessOwners()
                        assertTrue(authority.pendingProcessOwners().isEmpty())
                    }
                    "writer-cleanup-retirement-before-unlink", "writer-cleanup-retirement-after-unlink" -> {
                        assertTrue(authority.pendingProcessOwners().isEmpty())
                        val expected = if (boundary.endsWith("before-unlink")) 1 else 0
                        assertEquals(expected, directory.resolve("process-cleanup-receipts").listFiles()!!.size)
                        authority.processCustody().acquire(working).close()
                        assertTrue(authority.pendingProcessOwners().isEmpty())
                    }
                    "writer-cleanup-receipt-after-force" -> {
                        // Cleanup was confirmed through the exact receipt before the owner JVM died. No boot change or
                        // process scan is needed.
                        assertEquals(1, authority.pendingProcessOwners().size)
                        val proofs = directory.resolve("process-cleanup-receipts").listFiles()!!.associate { it.name to it.readBytes().toList() }
                        assertEquals(1, proofs.size)
                        authority.requireNoPendingProcessOwners()
                        assertTrue(authority.pendingProcessOwners().isEmpty())
                        assertEquals(proofs, directory.resolve("process-cleanup-receipts").listFiles()!!.associate { it.name to it.readBytes().toList() })
                        authority.processCustody().acquire(working).close()
                    }
                    "writer-completion-before-commit", "writer-boot-reconciliation-before-commit" -> {
                        // The interrupted SQLite transaction has a rollback sidecar. The production open path preserves
                        // that evidence instead of replaying it.
                        val database = directory.resolve("state.sqlite")
                        val bytes = database.readBytes(); val journal = File(database.path + "-journal")
                        assertTrue(journal.isFile); val journalBytes = journal.readBytes()
                        val failure = runCatching { authority.requireNoPendingProcessOwners() }.exceptionOrNull()
                        assertTrue(failure.toString(), failure?.message.orEmpty().contains("sidecars"))
                        assertArrayEquals(bytes, database.readBytes()); assertArrayEquals(journalBytes, journal.readBytes())
                    }
                    "writer-completion-after-commit", "writer-boot-reconciliation-after-commit" -> {
                        assertTrue(authority.pendingProcessOwners().isEmpty())
                        authority.requireNoPendingProcessOwners()
                        authority.processCustody().acquire(working).close()
                        assertTrue(authority.pendingProcessOwners().isEmpty())
                    }
                    else -> error("Unexpected crash boundary")
                }
                assertEquals("preserved user bytes", source.resolve("file").readText())
                assertEquals("preserved user bytes", working.resolve("file").readText())
                println("VERIFIED fresh JVM after $boundary")
            }
        }
    }
}
