package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Static packaged-source evidence plus a host Bash fixture. The groups function simulates
 * lookup failures; this is NOT a native coreutils/PRoot/Android or installed-APK reproduction.
 * It uses only APIs already present in R41, so the baseline does not depend on the new helper.
 */
class UbuntuGuestGroupStartupBaselineTest {
    @Test(timeout = 60_000) fun packagedInteractiveRcAutomaticallyLooksUpTheUnmappedAndroidGroups() {
        val base = Files.createTempDirectory("ubuntu-group-startup-baseline-").toFile()
        try {
            val rootfs = File(base, "rootfs").apply { mkdirs() }
            File(rootfs, "etc").mkdir()
            val group = File(rootfs, "etc/group")
            group.writeText(UbuntuStartupGroupFixture.resource("packaged-group"))
            val records = group.readLines().map { it.split(':') }
            for (gid in UbuntuStartupGroupFixture.SCREENSHOT_GIDS) assertFalse(records.any { it.getOrNull(2) == gid })
            val result = UbuntuStartupGroupFixture.openBash(rootfs, UbuntuStartupGroupFixture.SCREENSHOT_GIDS)
            assertEquals(result.output, 0, result.exitCode)
            for (gid in UbuntuStartupGroupFixture.SCREENSHOT_GIDS) {
                assertTrue(result.output, result.output.contains("groups: cannot find name for group ID $gid"))
            }
            assertTrue(result.output.contains("REACHED_PROMPT"))
            assertEquals(UbuntuStartupGroupFixture.resource("packaged-group"), group.readText())
            assertFalse(File(rootfs, "root/.hushlogin").exists())
        } finally { PathSecurity.deleteTreeNoFollow(base) }
    }
}

internal object UbuntuStartupGroupFixture {
    val SCREENSHOT_GIDS = listOf("3003", "9997", "20407", "50407", "99909997")
    fun resource(name: String): String = requireNotNull(javaClass.getResourceAsStream("/ubuntu-group-startup/$name"))
        .bufferedReader().use { it.readText() }

    fun openBash(rootfs: File, gids: List<String>): ExecResult {
        val rc = File(rootfs.parentFile, "packaged-bash.bashrc").apply { writeText(resource("packaged-bash.bashrc")) }
        val home = File(rootfs, "root").apply { mkdirs() }
        val output = File(rootfs.parentFile, "bash-output")
        // Deliberately shadow only groups, and source the EXACT packaged rc. No host group tool
        // runs and no host or Android account database is changed by this baseline fixture.
        val script = """
            groups() {
                result=0
                for gid in ${'$'}FIXTURE_GIDS; do
                    found=
                    while IFS=: read -r name password number members; do
                        if [ "${'$'}number" = "${'$'}gid" ]; then found=${'$'}name; break; fi
                    done < "${'$'}FIXTURE_GROUP_FILE"
                    if [ -n "${'$'}found" ]; then printf '%s ' "${'$'}found"
                    else printf 'groups: cannot find name for group ID %s\n' "${'$'}gid" >&2; result=1; fi
                done
                return "${'$'}result"
            }
            PS1='fixture prompt'
            . "${'$'}FIXTURE_RC"
            printf '\nREACHED_PROMPT\n'
        """.trimIndent()
        val process = ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", script)
            .redirectErrorStream(true).redirectOutput(output).apply {
                environment().clear()
                environment().putAll(mapOf("HOME" to home.path, "PATH" to "/no-host-tools",
                    "FIXTURE_GROUP_FILE" to File(rootfs, "etc/group").path, "FIXTURE_RC" to rc.path,
                    "FIXTURE_GIDS" to gids.joinToString(" ")))
            }.start()
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Inert startup fixture timed out" }
            return ExecResult(process.exitValue(), output.readText(), false)
        } finally { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS) }
    }
}
