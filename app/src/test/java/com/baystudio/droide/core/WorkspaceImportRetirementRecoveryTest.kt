package com.baystudio.droide.core

import android.app.Application
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceImportRetirementRecoveryTest {
    @Test fun projectRecoveryCompletesOnlyAfterExplicitConfirmation() = workspace { f, imports ->
        val before = f.snapshot()
        assertNotNull(imports.recovery().blockingProblem)
        val inspected = imports.inspectRetirementRecovery()
        assertEquals(before, f.snapshot())
        val proof = f.recordProof()
        imports.finishRetirementRecovery(inspected.token)
        f.assertComplete(proof)
        assertNull(imports.recovery().blockingProblem)
    }

    @Test fun closedWorkspaceCannotUseAnEarlierInspection() = workspace { f, imports ->
        val inspected = imports.inspectRetirementRecovery(); val before = f.snapshot()
        imports.close()
        assertTrue(runCatching { imports.finishRetirementRecovery(inspected.token) }.isFailure)
        assertEquals(before, f.snapshot())
    }

    @Test fun invalidatedRecoveryViewCannotStartTheFilesystemOperation() = workspace { f, imports ->
        val inspected = imports.inspectRetirementRecovery(); val before = f.snapshot()
        assertTrue(runCatching { imports.finishRetirementRecovery(inspected.token) { error("view closed") } }.isFailure)
        assertEquals(before, f.snapshot())
    }

    @Test fun workspaceCloseDuringRecoveryStopsBeforeNamespaceTransfer() = workspace { f, imports ->
        val inspected = imports.inspectRetirementRecovery(); val before = f.snapshot()
        f.journal.retirementCheckpoint = { phase -> if (phase == "recovery-intent-durable") imports.close() }
        assertTrue(runCatching { imports.finishRetirementRecovery(inspected.token) }.isFailure)
        assertEquals(before, f.snapshot())
        assertTrue(f.pending.isFile)
    }

    private fun workspace(block: suspend (RetirementRecoveryFixture, WorkspaceImportController) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("import-history-recovery-").toFile()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = RetirementRecoveryFixture(root)
        val source = object : WorkspaceImportSource {
            override suspend fun metadata(uri: Uri) = WorkspaceImportSourceMetadata("new.bin", 3)
            override suspend fun open(uri: Uri) = ByteArrayInputStream("new".toByteArray())
        }
        f.interrupt("intent-durable")
        val imports = WorkspaceImportController(FileRepository(root), parent, source, copyJournal = f.journal)
        try { block(f, imports) } finally { imports.closeAndJoin(); parent.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
