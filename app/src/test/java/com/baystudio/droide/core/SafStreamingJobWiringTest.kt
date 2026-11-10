package com.baystudio.droide.core

import android.app.Application
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the real production participant entry, without invoking a provider or deleting data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SafPreservationDirectoryOs::class])
class SafStreamingJobWiringTest {
    private val roots = arrayListOf<File>()
    @After fun cleanup() {
        try { assertEquals(0, SafPreservationDirectoryOs.openCount()) }
        finally { SafPreservationDirectoryOs.reset(); roots.asReversed().forEach { PathSecurity.deleteTreeNoFollow(it) } }
    }

    @Test(timeout = 30_000) fun realMirrorParticipantThreadsCapturedJobThroughBootstrapAndOpen(): Unit = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val parent = app.filesDir.resolve("saf-owned-operations").apply { mkdirs() }
        SafPreservationDirectoryOs.allow(parent)
        for (existing in listOf(false, true)) {
            val local = Files.createTempDirectory("saf-real-job-").toFile().also { roots += it }
            SafPreservationDirectoryOs.allow(local)
            val tree = "content://fixture/tree/root"
            val directory = parent.resolve((tree + "\u0000" + local.absoluteFile.toPath().normalize()).toByteArray().safDigest()).also { roots += it }
            if (existing) SafOperationJournal.create(local, tree, directory)
            WorkspaceFileMutationLocks.withRoot(local) { guard ->
                val captured = requireNotNull(currentCoroutineContext()[Job])
                val session = SafMirror(app, tree, local).repositoryParticipant().begin(guard, FileRepository.Mutation.Delete("absent"))
                val field = session.javaClass.declaredFields.single { it.type == SafOperationJournal::class.java }.apply { isAccessible = true }
                val journal = field.get(session) as SafOperationJournal
                val jobField = SafOperationJournal::class.java.getDeclaredField("cancellationJob").apply { isAccessible = true }
                assertSame(captured, jobField.get(journal))
                val admitted = journal.recoverAll()
                assertEquals(1, admitted.size)
                assertEquals(setOf("operation.json"), admitted.single().files)
                assertFalse(local.resolve("absent").exists())
            }
        }
    }
}
