package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class OrdinaryLockReadProbeTest {
    @Test fun ordinaryRepositoryReadCannotDropHeldMetadataLease() = runBlocking {
        val root = Files.createTempDirectory("f160-read-lock-probe-").toFile()
        root.resolve("src").mkdir(); root.resolve("src/payload").writeText("kept")
        val repo = FileRepository(root); assertTrue(repo.delete("src"))
        val item = repo.localDirectoryRecovery.list().items.single()
        val custody = LocalDirectoryDeleteCustody(root)
        val lease = WorkspaceFileMutationLocks.withCaptureRoot(root) { custody.acquireRecovery(item.token!!, it) }
        val path = "${LocalDirectoryDeleteCustody.HOME}/${item.operation}/lock"
        fun childProbe(): Int = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", System.getProperty("java.class.path"), F160MetadataLockProbe::class.java.name, File(root, path).path)
            .redirectErrorStream(true).start().let { p -> val output=p.inputStream.bufferedReader().readText(); val result=p.waitFor(); println("probe=$result $output"); result }
        lease.use {
            assertEquals(3, childProbe())
            val read = runCatching { repo.readText(path) }
            println("ordinary read outcome: $read")
            it.validate()
            assertEquals("A supported same-JVM repository read must not release another operation's kernel lock", 3, childProbe())
        }
    }
    @Test fun fullProjectExportCannotDropHeldMetadataLease() = runBlocking {
        val root = Files.createTempDirectory("f160-export-lock-probe-").toFile()
        root.resolve("src").mkdir(); root.resolve("src/payload").writeText("kept")
        val repo = FileRepository(root); assertTrue(repo.delete("src"))
        val item = repo.localDirectoryRecovery.list().items.single()
        val custody = LocalDirectoryDeleteCustody(root)
        val lease = WorkspaceFileMutationLocks.withCaptureRoot(root) { custody.acquireRecovery(item.token!!, it) }
        val path = "${LocalDirectoryDeleteCustody.HOME}/${item.operation}/lock"
        fun childProbe(): Int = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", System.getProperty("java.class.path"), F160MetadataLockProbe::class.java.name, File(root, path).path)
            .redirectErrorStream(true).start().let { p -> val output=p.inputStream.bufferedReader().readText(); val result=p.waitFor(); println("export probe=$result $output"); result }
        lease.use {
            assertEquals(3, childProbe())
            val permit = object : ExportCapturePermit {
                override val root = root
                override val scope = ExportScope("")
                override val stabilityAssumptions = emptyList<String>()
                override fun validate() { lease.validate() }
            }
            val stage = Files.createTempDirectory("f160-export-lock-stage-").toFile()
            ExportCaptureStore(stage).capture(permit).use { capture -> println("export entries=${capture.entries}") }
            it.validate()
            assertEquals("Full-project export must not release another operation's kernel lock", 3, childProbe())
        }
    }

}
