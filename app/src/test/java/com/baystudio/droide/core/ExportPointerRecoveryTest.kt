package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ExportPointerRecoveryTest {
    private val malformed = "version=1\nid=old\nepoch=epoch\nphase=INVALID\nentries=0\nbytes=0\n"
    private fun fixture(body: (File) -> Unit) {
        val root = Files.createTempDirectory("pointer-recovery").toFile()
        try {
            File(root, "current.properties").writeText(malformed)
            File(root, "old/capture").mkdirs()
            File(root, "old/capture/payload").writeText("precious")
            File(root, "current.tmp").writeText("unfinished")
            body(root)
            assertEquals("precious", File(root, "old/capture/payload").readText())
            assertEquals("unfinished", File(root, "current.tmp").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun exactBytesRetainedAndRestartIsIdempotent() = fixture { root ->
        ExportPointerRecovery.open(root).use { assertTrue(it.recover().recovered) }
        assertFalse(File(root, "current.properties").exists())
        val evidence = root.listFiles()!!.single { it.name.startsWith("recovery-") }.resolve("current.properties")
        assertEquals(malformed, evidence.readText())
        repeat(3) { ExportPointerRecovery.open(root).use { assertEquals(1, it.recover().retainedRecords) } }
        assertEquals(malformed, evidence.readText())
    }
    @Test fun unknownSchemaNeverMoves() = fixture { root ->
        for (version in listOf("99", "0", "2")) {
            File(root, "current.properties").writeText("version=$version\n")
            ExportPointerRecovery.open(root).use { assertTrue(runCatching { it.recover() }.isFailure) }
            assertEquals("version=$version\n", File(root, "current.properties").readText())
        }
        assertFalse(root.listFiles()!!.any { it.name.startsWith("recovery-") })
    }
    @Test fun exactAuditorBrokenVersionAndTruncatedPointersRecoverWithoutLoss() = fixture { root ->
        for ((index, text) in listOf("version=broken\n", "id=old\n", "version=1\n").withIndex()) {
            val trial = File(root, "broken$index").apply { mkdir() }
            File(trial, "current.properties").writeText(text)
            ExportPointerRecovery.open(trial).use { assertTrue(it.recover().recovered) }
            assertEquals(text, trial.listFiles()!!.single { it.name.startsWith("recovery-") }.resolve("current.properties").readText())
        }
    }
    @Test fun numericFutureWithMalformedOtherFieldStillBlocks() = fixture { root ->
        for (text in listOf("version=99\nbad=\\uZZZZ\n", "bad=\\uZZZZ\nversion=99\n")) {
            File(root, "current.properties").writeText(text)
            ExportPointerRecovery.open(root).use { assertTrue(runCatching { it.recover() }.isFailure) }
            assertEquals(text, File(root, "current.properties").readText())
        }
    }
    @Test fun futureVersionLexicalFormsAndAmbiguousSyntaxRemainBlocked() = fixture { root ->
        val cases = listOf(
            "version=999999999999999999999999999999999999999\n",
            "version=99\nversion=1\n",
            "version=1\nversion=broken\n",
            "bad=\\uZZZZ\n\\u0076ersion=99\n",
            "bad=\\uZZZZ\nver" + "\\" + "\nsion=99\n",
            "\\u0076ersion=99\nversion=broken\n",
            "ver" + "\\" + "\nsion=99\nversion=broken\n",
            "bad=\\uZZZZ\n",
        )
        for (text in cases) {
            File(root, "current.properties").writeText(text)
            ExportPointerRecovery.open(root).use { assertTrue(runCatching { it.recover() }.isFailure) }
            assertEquals(text, File(root, "current.properties").readText())
        }
        assertFalse(root.listFiles()!!.any { it.name.startsWith("recovery-") })
    }
    @Test fun cancellationAndFaultsAtEveryBoundaryReopenWithoutLoss() = fixture { root ->
        for (point in ExportPointerRecovery.Checkpoint.entries) {

            val trial = File(root, point.name).apply { mkdir() }
            File(trial, "current.properties").writeText(malformed)
            ExportPointerRecovery.open(trial).use { owner ->
                assertTrue(runCatching { owner.recover(checkpoint = { if (it == point) throw CancellationException("fault") }) }.isFailure)
            }
            val expectedCount = if (point == ExportPointerRecovery.Checkpoint.DIRECTORY_CREATED) 2 else 1
            repeat(3) { ExportPointerRecovery.open(trial).use { assertEquals(expectedCount, it.recover().retainedRecords) } }
            val evidence = trial.listFiles()!!.single { it.name.startsWith("recovery-") && it.resolve("current.properties").isFile }.resolve("current.properties")
            assertEquals(malformed, evidence.readText())
        }
    }
    @Test fun simultaneousOwnerRejectedWithoutUnlockingFirst() = fixture { root ->
        ExportPointerRecovery.open(root).use { first ->
            assertTrue(runCatching { ExportPointerRecovery.open(root) }.isFailure)
            first.requireHeld()
            assertTrue(first.recover().recovered)
        }
        ExportPointerRecovery.open(root).use { it.requireHeld() }
    }
    @Test fun ancestorAliasCannotAcquireOrReleaseSameInodeLease() = fixture { root ->
        val holder = File(root, "holder").apply { mkdir() }
        val actual = File(holder, "exports").apply { mkdir() }
        File(actual, "current.properties").writeText(malformed)
        val alias = File(root, "alias")
        Files.createSymbolicLink(alias.toPath(), holder.toPath())
        ExportPointerRecovery.open(actual).use { first ->
            assertTrue(runCatching { ExportPointerRecovery.open(File(alias, "exports")) }.isFailure)
            first.requireHeld()
            assertTrue(first.recover().recovered)
        }
    }
    private fun externalLockProbe(file: File): Int {
        val executable = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(executable, "-Djava.library.path=" + System.getProperty("java.library.path"), "-cp", System.getProperty("java.class.path"),
            "com.baystudio.droide.ExportLockProbe", file.absolutePath).redirectErrorStream(true).start()
        try {
            check(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { "Lock probe timed out" }
            val code = process.exitValue()
            check(code == 0 || code == 7) { "Lock probe failed: " + process.inputStream.bufferedReader().readText().take(1024) }
            return code
        } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor() } }
    }
    @Test fun externalProcessObservesLeaseAfterAliasRejectionAndHistoryRead() = fixture { root ->
        val holder = File(root, "holder").apply { mkdir() }
        val actual = File(holder, "exports").apply { mkdir() }
        File(actual, "current.properties").writeText(malformed)
        val alias = File(root, "alias")
        Files.createSymbolicLink(alias.toPath(), holder.toPath())
        val lock = File(actual, "export-owner.lock")
        ExportPointerRecovery.open(actual).use { owner ->
            assertEquals(0, externalLockProbe(lock))
            assertTrue(runCatching { ExportPointerRecovery.open(File(alias, "exports")) }.isFailure)
            ExportJournal(actual).historySnapshot()
            owner.requireHeld()
            assertEquals(0, externalLockProbe(lock))
        }
        assertEquals(7, externalLockProbe(lock))
    }
    @Test fun historyHardlinkCloseCannotReleaseDescriptorLease() = fixture { root ->
        val store = ExportJournal(root)
        val job = store.jobDirectory("history")
        val lock = File(root, "export-owner.lock")
        ExportPointerRecovery.open(root).use { owner ->
            assertEquals(0, externalLockProbe(lock))
            Files.createLink(File(job, "state.properties").toPath(), lock.toPath())
            store.historySnapshot()
            owner.requireHeld()
            assertEquals(0, externalLockProbe(lock))
        }
        assertEquals(7, externalLockProbe(lock))
    }
    @Test fun symlinkCurrentRejectedAndTargetUntouched() = fixture { root ->
        val current = File(root, "current.properties")
        current.delete()
        val target = File(root, "target").apply { writeText(malformed) }
        Files.createSymbolicLink(current.toPath(), target.toPath())
        ExportPointerRecovery.open(root).use { assertTrue(runCatching { it.recover() }.isFailure) }
        assertTrue(Files.isSymbolicLink(current.toPath()))
        assertEquals(malformed, target.readText())
    }
    @Test fun changedSourceBeforeMoveDoesNotOverwriteOrQuarantineReplacement() = fixture { root ->
        ExportPointerRecovery.open(root).use { owner ->
            assertTrue(runCatching { owner.recover(checkpoint = {
                if (it == ExportPointerRecovery.Checkpoint.BEFORE_MOVE) File(root, "current.properties").writeText("replacement")
            }) }.isFailure)
        }
        assertEquals("replacement", File(root, "current.properties").readText())
        ExportPointerRecovery.open(root).use { assertTrue(runCatching { it.recover() }.isFailure) }
    }
    @Test fun changedEvidenceOnRestartBlocksWithoutDeletion() = fixture { root ->
        ExportPointerRecovery.open(root).use { it.recover() }
        val evidence = root.listFiles()!!.single { it.name.startsWith("recovery-") }.resolve("current.properties")
        evidence.writeText("changed")
        ExportPointerRecovery.open(root).use { assertTrue(runCatching { it.recover() }.isFailure) }
        assertEquals("changed", evidence.readText())
    }
    @Test fun pinnedRootSubstitutionCannotMoveReplacementPointer() = fixture { root ->
        val original = File(root, "trial").apply { mkdir() }
        File(original, "current.properties").writeText(malformed)
        val moved = File(root, "moved")
        ExportPointerRecovery.open(original).use { owner ->
            assertTrue(original.renameTo(moved))
            original.mkdir()
            File(original, "current.properties").writeText("replacement")
            assertTrue(runCatching { owner.recover() }.isFailure)
            assertEquals("replacement", File(original, "current.properties").readText())
            assertEquals(malformed, File(moved, "current.properties").readText())
        }
    }
    @Test fun harmlessIntentlessOrphanIsDiagnosticNotRecoveryAuthority() = fixture { root ->
        File(root, "current.properties").delete()
        File(root, "recovery-00000000-0000-0000-0000-000000000000").mkdir()
        repeat(3) { ExportPointerRecovery.open(root).use { assertEquals(ExportPointerRecovery.Result(false, 1), it.recover()) } }
    }
    @Test fun foreignEvidenceDestinationNeverReplaced() = fixture { root ->
        ExportPointerRecovery.open(root).use { owner ->
            assertTrue(runCatching { owner.recover(checkpoint = {
                if (it == ExportPointerRecovery.Checkpoint.INTENT_DURABLE)
                    root.listFiles()!!.single { it.name.startsWith("recovery-") }.resolve("current.properties").writeText("foreign")
            }) }.isFailure)
        }
        assertEquals(malformed, File(root, "current.properties").readText())
        assertEquals("foreign", root.listFiles()!!.single { it.name.startsWith("recovery-") }.resolve("current.properties").readText())
    }
    @Test fun healthyCurrentNotReplayedOrMoved() = fixture { root ->
        val valid = malformed.replace("INVALID", "WRITING")
        File(root, "current.properties").writeText(valid)
        ExportPointerRecovery.open(root).use { assertEquals(ExportPointerRecovery.Result(false, 0), it.recover()) }
        assertEquals(valid, File(root, "current.properties").readText())
    }
}
