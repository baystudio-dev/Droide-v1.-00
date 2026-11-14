package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.AfterClass
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UbuntuArchiveSafetyTest {
    companion object {
        private val applicationRoot = Files.createTempDirectory("droide-archive-test-app-").toFile()
        @AfterClass @JvmStatic fun removeApplicationRoot() { applicationRoot.deleteRecursively() }
    }

    private fun fixture(block: (File, File) -> Unit) {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = applicationRoot
        }
        LocalExecutionSubstrate.init(context)
        val root = File(context.filesDir, "archive-safety").apply { mkdirs() }
        try { block(root, File(root, "download.gz").apply { writeText("truncated download") }) }
        finally { root.deleteRecursively() }
    }

    @Test fun corruptArchiveCannotReachExtractionOrWriteIntoStaging() = fixture { root, archive ->
        val stage = File(root, "stage").apply { mkdir() }
        val phases = mutableListOf<EnvironmentSetupPhase>()
        val failure = assertThrows(IllegalStateException::class.java) { runBlocking {
            BundledRootfsExtractor.extractDownloadedUbuntu(archive, stage, phases::add)
        } }
        assertTrue(failure.message!!.contains("integrity"))
        assertEquals(listOf(EnvironmentSetupPhase.VERIFYING), phases)
        assertTrue(stage.listFiles()!!.isEmpty())
    }

    @Test fun nonemptyDestinationIsNeverUsedAsAnInstallationStage() = fixture { root, archive ->
        val stage = File(root, "existing").apply { mkdir() }
        val userFile = File(stage, "user-work.txt").apply { writeText("Keep this") }
        assertThrows(IllegalStateException::class.java) { runBlocking {
            BundledRootfsExtractor.extractDownloadedUbuntu(archive, stage)
        } }
        assertEquals("Keep this", userFile.readText())
    }

    @Test fun symlinkDestinationIsRejectedEvenWhenItPointsInsideThePrivateRoot() = fixture { root, archive ->
        val destination = File(root, "kept").apply { mkdir() }
        val alias = File(root, "alias")
        Files.createSymbolicLink(alias.toPath(), destination.toPath())
        assertThrows(IllegalStateException::class.java) { runBlocking {
            BundledRootfsExtractor.extractDownloadedUbuntu(archive, alias)
        } }
        assertTrue(destination.listFiles()!!.isEmpty())
        alias.delete()
    }
}
