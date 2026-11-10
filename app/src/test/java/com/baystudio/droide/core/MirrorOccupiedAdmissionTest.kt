package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MirrorOccupiedAdmissionTest {
    private class Fixture(val root: File) {
        val source = File(root, "source").apply { mkdir() }
        val working = File(root, "working")
        val metadata = File(root, ".working-sync-authority")
        val store get() = MirrorMetadataAuthority(metadata, source, working)
        lateinit var mirror: LocalAgentWorkspaceMirror
        val processes = mutableListOf<Process>()

        suspend fun prepare() {
            File(source, "folder/nested").mkdirs()
            File(source, "folder/nested/file").writeText("base")
            File(source, "other").writeText("base")
            mirror = LocalAgentWorkspaceMirror.prepareRetained(source, working)
            mirror.refresh()
        }

        fun hold(relative: String = "folder"): Process {
            val directory = File(working, relative)
            val process = ProcessBuilder("/bin/sh", "-c", "exec cat").directory(directory).start()
            processes += process
            assertTrue(process.isAlive)
            val pid = (Process::class.java.getMethod("pid").invoke(process) as Number).toLong()
            assertEquals(directory.canonicalPath, Files.readSymbolicLink(File("/proc/$pid/cwd").toPath()).toString())
            return process
        }

        fun stop(process: Process) {
            process.outputStream.close()
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
        }

        fun deletion() = MirrorMetadataAuthority.Proposal("folder", "delete",
            MirrorWorkingCopyMutations.directoryNode(File(working, "folder"), EmptyCoroutineContext), MirrorMetadataAuthority.Node.absent)

        suspend fun apply(proposals: List<MirrorMetadataAuthority.Proposal>, hook: (String, MirrorMetadataAuthority.Intent) -> Unit = { _, _ -> }) =
            WorkspaceFileMutationLocks.withRoot(working) { guard ->
                MirrorWorkingCopyMutations(source, working, store, hook).apply(guard, store.load().revision, proposals)
            }

        fun assertNoNewIntent(before: List<MirrorMetadataAuthority.Intent>, bytes: ByteArray) {
            assertEquals(before, store.operations())
            assertArrayEquals(bytes, File(metadata, "state.sqlite").readBytes())
            assertEquals("base", File(working, "folder/nested/file").readText())
            assertTrue(File(metadata, "prepared").list()!!.isEmpty())
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-occupied-admission-").toFile()
        val fixture = Fixture(root)
        try { fixture.prepare(); fixture.block() }
        finally {
            fixture.processes.forEach { process ->
                process.destroyForcibly()
                check(process.waitFor(3, TimeUnit.SECONDS))
            }
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun occupiedDeleteRefusesBeforeCreatingRecoveryIntent() = fixture {
        val process = hold()
        val before = store.operations()
        val bytes = File(metadata, "state.sqlite").readBytes()
        val failure = runCatching { apply(listOf(deletion())) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("Stop the command"))
        assertTrue(process.isAlive)
        assertNoNewIntent(before, bytes)
    }

    @Test fun occupiedDirectoryReplacementDoesNotStageOrPrepare() = fixture {
        hold()
        PathSecurity.deleteTreeNoFollow(File(source, "folder"))
        File(source, "folder").writeText("replacement")
        val before = store.operations()
        val bytes = File(metadata, "state.sqlite").readBytes()
        val proposed = MirrorMetadataAuthority.Node.file(StreamFileVersion.capture(source, "folder").let {
            WorkspaceSyncManifest.Entry(it.sha256, if (it.executable) 493 else 420)
        })
        val operation = deletion().copy(kind = "directory", proposed = proposed)
        assertNotNull(runCatching { apply(listOf(operation)) }.exceptionOrNull())
        assertNoNewIntent(before, bytes)
        assertEquals("replacement", File(source, "folder").readText())
    }

    @Test fun anOccupiedLaterProposalBlocksTheWholeBatchBeforeAnyMutation() = fixture {
        hold()
        File(source, "other").writeText("changed")
        val state = store.load()
        val proposal = MirrorMetadataAuthority.Proposal("other", "replace", MirrorMetadataAuthority.Node.file(state.baseline.getValue("other")),
            MirrorMetadataAuthority.Node.file(WorkspaceSyncManifest.Entry(StreamFileVersion.capture(source, "other").sha256, 420)))
        val before = store.operations()
        val bytes = File(metadata, "state.sqlite").readBytes()
        assertNotNull(runCatching { apply(listOf(proposal, deletion())) }.exceptionOrNull())
        assertNoNewIntent(before, bytes)
        assertEquals("base", File(working, "other").readText())
        assertEquals("changed", File(source, "other").readText())
    }

    @Test fun aStoppedOwnerAllowsRetryWithoutRecoveryOrReinstallation() = fixture {
        val process = hold()
        val operation = deletion()
        assertNotNull(runCatching { apply(listOf(operation)) }.exceptionOrNull())
        stop(process)
        val result = apply(listOf(operation)).single()
        assertTrue(result.applied)
        assertFalse(File(working, "folder").exists())
        assertEquals("base", File(result.retained!!, "nested/file").readText())
        assertEquals("base", File(result.stable!!, "nested/file").readText())
        assertTrue(store.operations().all { it.phase == MirrorMetadataAuthority.Phase.APPLIED })
    }

    @Test fun ordinaryAdmissionRefusalDoesNotBreakTheUbuntuLocalBarrier() = fixture {
        val process = hold()
        assertNotNull(runCatching { apply(listOf(deletion())) }.exceptionOrNull())
        WorkspaceFileMutationLocks.withRoot(working) { guard ->
            MirrorWorkingCopyMutations(source, working, store).requireUbuntuLocalConsistency(guard)
        }
        assertTrue(process.isAlive)
        assertEquals("base", File(working, "folder/nested/file").readText())
    }

    @Test fun aProcessArrivingAfterPreflightStillStopsRetirementAndRequiresRecovery() = fixture {
        val operation = deletion()
        var process: Process? = null
        val failure = runCatching { apply(listOf(operation)) { boundary, _ ->
            if (boundary == "staged") process = hold()
        } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(process!!.isAlive)
        assertEquals("base", File(working, "folder/nested/file").readText())
        assertEquals(1, store.operations().count { it.phase == MirrorMetadataAuthority.Phase.PREPARED })
        assertNotNull(runCatching { MirrorWorkingCopyMutations(source, working, store).requireNoInterruptedWork() }.exceptionOrNull())
    }

    @Test fun anUnoccupiedDirectoryStillRetainsBothVersions() = fixture {
        val result = apply(listOf(deletion())).single()
        assertTrue(result.applied)
        assertEquals("base", File(result.retained!!, "nested/file").readText())
        assertEquals("base", File(result.stable!!, "nested/file").readText())
        assertFalse(File(working, "folder").exists())
    }

    @Test fun aSiblingProcessDoesNotBlockTheRequestedDirectory() = fixture {
        File(working, "sibling").mkdir()
        val process = hold("sibling")
        assertTrue(apply(listOf(deletion())).single().applied)
        assertTrue(process.isAlive)
        assertTrue(File(working, "sibling").isDirectory)
    }

    @Test fun nestedProcessOccupancyIsRejectedBeforeIntent() = fixture {
        hold("folder/nested")
        val before = store.operations()
        val bytes = File(metadata, "state.sqlite").readBytes()
        assertNotNull(runCatching { apply(listOf(deletion())) }.exceptionOrNull())
        assertNoNewIntent(before, bytes)
    }

    @Test fun mirrorRefreshCanResumeAfterTheOwningProcessExits() = fixture {
        val process = hold()
        PathSecurity.deleteTreeNoFollow(File(source, "folder"))
        val before = store.operations()
        val bytes = File(metadata, "state.sqlite").readBytes()
        assertNotNull(runCatching { mirror.refresh() }.exceptionOrNull())
        assertNoNewIntent(before, bytes)
        stop(process)
        val restarted = LocalAgentWorkspaceMirror.prepareRetained(source, working)
        restarted.refresh()
        assertFalse(File(working, "folder").exists())
        assertTrue(store.operations().all { it.phase == MirrorMetadataAuthority.Phase.APPLIED })
    }
}
