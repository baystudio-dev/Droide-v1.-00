package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MirrorProcessCustodyRestartTest {
    @Test(timeout = 60_000) fun retirementBeforeUnlinkSurvivesKilledJvm() = crash("writer-cleanup-retirement-before-unlink")
    @Test(timeout = 60_000) fun retirementAfterUnlinkSurvivesKilledJvm() = crash("writer-cleanup-retirement-after-unlink")
    @Test(timeout = 60_000) fun exactCleanupProofReplaysInFreshJvmWithoutBootIdentity() = crash("writer-cleanup-receipt-after-force")
    @Test(timeout = 60_000) fun unenteredAdmissionCanBeRevokedAfterKilledOwnerJvm() = crash("writer-admission-after-commit")
    @Test(timeout = 60_000) fun interruptedConfirmationPreservesRollbackEvidenceAfterJvmDeath() = crash("writer-completion-before-commit")
    @Test(timeout = 60_000) fun committedConfirmationSurvivesLostResponseAndFreshJvm() = crash("writer-completion-after-commit")
    @Test(timeout = 60_000) fun interruptedBootReconciliationPreservesRollbackEvidenceAfterJvmDeath() = crash("writer-boot-reconciliation-before-commit")
    @Test(timeout = 60_000) fun committedBootReconciliationSurvivesLostResponseAndFreshJvm() = crash("writer-boot-reconciliation-after-commit")

    private fun crash(boundary: String) {
        val root = Files.createTempDirectory("custody-jvm-restart-").toFile()
        var child: Process? = null
        try {
            child = start(root, boundary, "hold")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            while (!root.resolve("ready").isFile && child.isAlive && System.nanoTime() < deadline) Thread.sleep(25)
            assertTrue(root.resolve("hold.log").readText(), root.resolve("ready").isFile && child.isAlive)
            child.destroyForcibly()
            assertTrue("Killed JVM did not exit", child.waitFor(10, TimeUnit.SECONDS))
            assertNotEquals(0, child.exitValue())
            child = start(root, boundary, "verify")
            assertTrue("Fresh JVM timed out", child.waitFor(25, TimeUnit.SECONDS))
            assertEquals(root.resolve("verify.log").readText(), 0, child.exitValue())
            assertTrue(root.resolve("verify.log").readText().contains("VERIFIED fresh JVM after $boundary"))
            val bootScope = if (boundary.startsWith("writer-boot-")) "; simulated boot identities, no device reboot" else ""
            println("PROCESS_RESTART_PASS $boundary (owner JVM force-killed; fresh verifier JVM$bootScope)")
        } finally {
            child?.let { if (it.isAlive) it.destroyForcibly(); check(it.waitFor(10, TimeUnit.SECONDS)) }
            assertTrue(PathSecurity.deleteTreeNoFollow(root))
        }
    }

    private fun start(root: File, boundary: String, mode: String): Process {
        val classpath = checkNotNull(System.getProperty("droide.custody.test.classpath")) { "Use process-custody-tests.init.gradle for the child JVM classpath" }
        val arguments = listOf("-Xmx768m", "-cp", classpath,
            "-Djava.library.path=${System.getProperty("java.library.path")}",
            "-Drobolectric.offline=true", "-Drobolectric.dependency.dir=${System.getProperty("robolectric.dependency.dir")}",
            "-Ddroide.custody.fixture.root=${root.path}", "-Ddroide.custody.fixture.boundary=$boundary",
            "-Ddroide.custody.fixture.mode=$mode", MirrorProcessCustodyRestartFixture::class.java.name)
        val argFile = root.resolve("$mode.args")
        argFile.writeText(arguments.joinToString("\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "@${argFile.path}")
            .redirectErrorStream(true).redirectOutput(root.resolve("$mode.log")).start()
    }
}
