package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExportAuxiliaryWriterAdmissionTest {
    private fun <T> withRoot(block: (File) -> T): T {
        val root = Files.createTempDirectory("export-auxiliary-writer").toFile()
        return try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun captureRejectsThemeArtifactProjectionAndLegacyCleanupBeforeSideEffects() = withRoot { root ->
        val theme = ThemeSelectionPersistence(root)
        theme.write("Original")
        val legacy = File(root, ".droide/auth.json").apply { writeText("legacy-test-fixture") }
        val projection = File(root, ".droide/.runtime-agent-plugins").apply { mkdirs() }
        File(projection, "owned-test.txt").writeText("preserve")
        ExportWriterAdmission.beginCapture(root).use {
            assertThrows(ExportCaptureBusyException::class.java) { theme.write("Changed") }
            assertEquals("Original", theme.read())
            val artifacts = ArtifactManager(root)
            assertFalse(File(root, ".droide/artifacts").exists())
            assertThrows(ExportCaptureBusyException::class.java) { artifacts.create("note", "content") }
            assertThrows(ExportCaptureBusyException::class.java) { AgentPluginRuntime.contributions(root) }
            assertThrows(ExportCaptureBusyException::class.java) { AgentPluginRuntime.cleanupWorkspace(root) }
            assertThrows(ExportCaptureBusyException::class.java) { ProviderAuthManager.cleanupLegacyWorkspaceSecrets(root) }
            assertTrue(legacy.isFile)
            assertTrue(File(projection, "owned-test.txt").isFile)
        }
    }

    @Test fun captureRejectsFormatterCliAndGeneratedPreparationBeforeWrites() = withRoot { root ->
        runBlocking {
            File(root, "source.kt").writeText("fun main() = Unit")
            ExportWriterAdmission.beginCapture(root).use {
                assertThrows(ExportCaptureBusyException::class.java) {
                    runBlocking { GuestDocumentFormatter.format(root, "source.kt", "fun main() = Unit") { error("Must not execute") } }
                }
                assertThrows(ExportCaptureBusyException::class.java) {
                    runBlocking { CliLspManager(AgentWorkflowTerminal(), FileRepository(root)).diagnose("source.kt") }
                }
                assertThrows(ExportCaptureBusyException::class.java) {
                    SourceRunPreparation.prepare(root, requireNotNull(SourceRunPreparation.nativePlan("source.c").preparation))
                }
                assertEquals(listOf("source.kt"), root.list().orEmpty().toList())
            }
        }
    }

    @Test fun formatterRetainsAdmissionThroughProcessAndSnapshotCleanup() = withRoot { root ->
        runBlocking {
            File(root, "source.kt").writeText("fun main() = Unit")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val formatting = async {
                GuestDocumentFormatter.format(root, "source.kt", "fun main() = Unit") {
                    entered.complete(Unit)
                    release.await()
                    ExecResult(0, "", false)
                }
            }
            entered.await()
            try {
                assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(root) }
            } finally { release.complete(Unit) }
            formatting.await()
            assertFalse(root.list().orEmpty().any { it.startsWith(".droide-format-") })
            ExportWriterAdmission.beginCapture(root).close()
        }
    }
}
