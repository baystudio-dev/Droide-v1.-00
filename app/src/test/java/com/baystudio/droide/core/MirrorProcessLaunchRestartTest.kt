package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MirrorProcessLaunchRestartTest {
    @Test(timeout = 90_000) fun livePreparedRuntimeCannotLaunchAfterAnotherJvmRevokesIt() = live("live-prepared", "revoke-live-prepared")
    @Test(timeout = 90_000) fun liveEnteredProcessRemainsOwnedAcrossCompetingJvm() = live("live-entered", "refuse-live-entered")
    @Test(timeout = 90_000) fun liveLegacyV5CleanupWorksAfterUpgradeIsDeferred() = live("legacy-owner", "refuse-upgrade", upgradeAfter = true)
    @Test(timeout = 90_000) fun pausedV5ValidatorCannotAppendAfterV6Upgrade() = live("legacy-validated", "upgrade")
    @Test(timeout = 90_000) fun entryBeforeCommitKillPreservesSidecar() = crash("writer-factory-entry-before-commit")
    @Test(timeout = 90_000) fun entryAfterCommitKillNeverInfersNoChild() = crash("writer-factory-entry-after-commit")
    @Test(timeout = 90_000) fun revocationBeforeCommitKillPreservesSidecar() = crash("writer-reservation-cancel-before-commit")
    @Test(timeout = 90_000) fun revocationAfterCommitKillLeavesDurablyRevokedPermission() = crash("writer-reservation-cancel-after-commit")
    @Test(timeout = 90_000) fun retirementBeforeCommitKillPreservesSidecar() = crash("writer-boot-reconciliation-before-commit")

    private fun live(actor: String, contender: String, upgradeAfter: Boolean = false) = fixture { root, children ->
        val old = start(root, actor).also(children::add); ready(root, old, actor)
        val other = start(root, contender).also(children::add); success(root, other, contender)
        assertTrue("Old runtime must still be live at revocation/upgrade", old.isAlive)
        root.resolve("release").writeText("continue")
        success(root, old, actor)
        assertFalse(root.resolve("unexpected-factory").exists())
        if (upgradeAfter) success(root, start(root, "upgrade").also(children::add), "upgrade")
        println("LAUNCH_CONCURRENCY_PASS $actor versus $contender")
    }
    private fun crash(boundary: String) = fixture { root, children ->
        val old = start(root, "kill", boundary).also(children::add); ready(root, old, "kill")
        old.destroyForcibly(); assertTrue(old.waitFor(10, TimeUnit.SECONDS)); assertNotEquals(0, old.exitValue())
        success(root, start(root, "verify-kill", boundary).also(children::add), "verify-kill")
        println("LAUNCH_RESTART_PASS $boundary (real JVM kill; no device/power-loss claim)")
    }
    private fun fixture(action: (File, MutableList<Process>) -> Unit) {
        val root = Files.createTempDirectory("launch-permission-jvm-").toFile(); val children = mutableListOf<Process>()
        try { action(root, children) }
        finally {
            root.resolve("release").writeText("cleanup")
            for (child in children) {
                if (child.isAlive && !child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly()
                check(child.waitFor(10, TimeUnit.SECONDS))
            }
            assertTrue(PathSecurity.deleteTreeNoFollow(root))
        }
    }
    private fun ready(root: File, child: Process, mode: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!root.resolve("ready").isFile && child.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(root.resolve("$mode.log").readText(), root.resolve("ready").isFile && child.isAlive)
    }
    private fun success(root: File, child: Process, mode: String) {
        assertTrue("Child JVM timed out: $mode", child.waitFor(30, TimeUnit.SECONDS))
        assertEquals(root.resolve("$mode.log").readText(), 0, child.exitValue())
    }
    private fun start(root: File, mode: String, boundary: String = ""): Process {
        val classpath = checkNotNull(System.getProperty("droide.custody.test.classpath"))
        val args = listOf("-Xmx768m", "-cp", classpath, "-Djava.library.path=${System.getProperty("java.library.path")}",
            "-Drobolectric.offline=true", "-Drobolectric.dependency.dir=${System.getProperty("robolectric.dependency.dir")}",
            "-Ddroide.launch.root=${root.path}", "-Ddroide.launch.mode=$mode", "-Ddroide.launch.boundary=$boundary",
            MirrorProcessLaunchRestartFixture::class.java.name)
        val file = root.resolve("$mode.args")
        file.writeText(args.joinToString("\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "@${file.path}")
            .redirectErrorStream(true).redirectOutput(root.resolve("$mode.log")).start()
    }
}
