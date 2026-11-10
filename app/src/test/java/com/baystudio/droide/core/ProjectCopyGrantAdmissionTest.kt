package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Source-only regressions for the synchronous final-grant owner handshake. */
class ProjectCopyGrantAdmissionTest {
    @Test fun ownerRefusalAfterEvidenceValidationCreatesNoPermission() = fixture { f ->
        assertNotNull(runCatching { f.journal.acknowledge(f.scope, f.inspect()) { error("Owner switched") } }.exceptionOrNull())
        f.assertBlocked()
    }

    @Test fun returningWithoutGrantAndRetainingCallbackCannotAuthorizeLater() = fixture { f ->
        var retained: (() -> Unit)? = null
        assertNotNull(runCatching { f.journal.acknowledge(f.scope, f.inspect()) { retained = it } }.exceptionOrNull())
        assertNotNull(runCatching { retained!!.invoke() }.exceptionOrNull())
        f.assertBlocked()
    }

    @Test fun grantFromAnotherThreadIsRejectedBeforePermissionInsertion() = fixture { f ->
        val wrongThread = AtomicReference<Throwable?>()
        assertNotNull(runCatching {
            f.journal.acknowledge(f.scope, f.inspect()) { grant ->
                val thread = Thread { wrongThread.set(runCatching { grant() }.exceptionOrNull()) }
                thread.start(); thread.join(5_000)
                assertFalse(thread.isAlive)
            }
        }.exceptionOrNull())
        assertNotNull(wrongThread.get())
        f.assertBlocked()
    }

    @Test fun inspectionExpiryDuringOwnerAdmissionRejectsGrant() = fixture { f ->
        val token = f.inspect()
        assertNotNull(runCatching {
            f.journal.acknowledge(f.scope, token) { grant -> f.elapsed += 300_001; grant() }
        }.exceptionOrNull())
        f.assertBlocked()
    }

    @Test fun acceptedGrantIsSingleUseEvenIfOwnerCallbackThenThrows() = fixture { f ->
        assertNotNull(runCatching {
            f.journal.acknowledge(f.scope, f.inspect()) { grant -> grant(); error("Caller failed after accepting permission") }
        }.exceptionOrNull())
        // A failure after actual in-memory acceptance cannot truthfully promise rollback.
        f.admission++
        f.journal.begin(f.owner.copy(runId = "new-user-run"), "asset.bin", f.admission).close()
        f.assertBlocked()
        assertFalse(f.root.resolve("asset.bin").exists())
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, f.journal.recovery(f.scope).records.single { it.intent.id == f.id }.status)
    }

    @Test fun duplicateOrRetainedSuccessfulGrantCannotCreateAnotherPermission() = fixture { f ->
        var retained: (() -> Unit)? = null
        f.journal.acknowledge(f.scope, f.inspect()) { grant ->
            retained = grant
            grant()
            assertNotNull(runCatching { grant() }.exceptionOrNull())
        }
        assertNotNull(runCatching { retained!!.invoke() }.exceptionOrNull())
        f.admission++
        f.journal.begin(f.owner.copy(runId = "new-user-run"), "asset.bin", f.admission).close()
        f.assertBlocked()
    }

    private class Fixture(val root: File) {
        var elapsed = 1000L
        var admission = 100L
        val journal = ProjectCopyJournal(root, monotonicClock = { elapsed }, admissionClockNanos = { admission })
        val owner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, "session", "old-run", "media")
        val scope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, "session")
        val id: String
        init {
            val lease = journal.begin(owner, "asset.bin")
            id = lease.intent.id
            lease.beforePublish(StreamPublicationPreparation(UUID.randomUUID().toString(), root.canonicalPath,
                ProjectCopyEvidence.root(root).second, "asset.bin", 1, ProjectCopyEvidence.sha256(byteArrayOf(1)), "old-stage"))
            lease.close()
        }
        fun inspect() = journal.inspect(scope, id).token
        fun assertBlocked() {
            admission++
            val failure = runCatching { journal.begin(owner.copy(runId = UUID.randomUUID().toString()), "asset.bin", admission) }.exceptionOrNull()
            assertTrue("Expected unresolved old operation to remain blocked: $failure", failure is ProjectCopyJournal.Blocked)
        }
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("copy-grant-admission-").toFile()
        try { block(Fixture(root)) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
