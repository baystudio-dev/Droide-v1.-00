package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test

class ElpResourcePolicyTest {
    @Test fun monotonicBudgetsKeepRetirementInsideTotal() {
        var now = 0L
        val total = ElpDeadline.after(180_000) { now }
        val work = total.reserve(10_000)
        now = 165_000_000_000L
        assertEquals(5000, work.remainingMillis())
        assertEquals(5000, work.limited(40_000).remainingMillis())
        now = 170_000_000_000L
        assertTrue(work.expired); assertFalse(total.expired)
        assertEquals(15_000L, ElpResourcePolicy.FEATURE_MS)
        assertEquals(5_000L, ElpResourcePolicy.FEATURE_RETIRE_MS)
    }
    @Test fun hardLimitsAreSetAndReadBack() {
        val script = ElpResourcePolicy.ANALYSIS.shellPrefix()[2]
        for ((flag, number) in mapOf("c" to 0, "t" to 600, "f" to 262144, "n" to 512, "u" to 256, "v" to 4194304)) {
            assertTrue(script.contains("ulimit -S$flag $number"))
            assertTrue(script.contains("ulimit -H$flag $number"))
            assertTrue(script.contains("\$(ulimit -S$flag)"))
            assertTrue(script.contains("\$(ulimit -H$flag)"))
        }
        assertEquals(64, ElpResourcePolicy.GROUP_MEMBERS_MAX)
        assertEquals(1_610_612_736L, ElpResourcePolicy.RSS_BYTES_MAX)
        assertEquals(250L, ElpResourcePolicy.MONITOR_MS)
    }
    @Test fun curatedEnvironmentCannotCarryAmbientSelectors() {
        val env = LspElpLaunchPolicy.environment("/run/owner", null, false, mapOf(
            "HOME" to "/root", "PATH" to "/usr/bin", "ELP_EQWALIZER_PATH" to "/evil", "LD_PRELOAD" to "/evil",
            "ERL_FLAGS" to "-eval evil", "REBAR_CONFIG" to "/evil", "BASH_ENV" to "/evil", "PYTHONPATH" to "/evil"), "a".repeat(32))
        for (bad in listOf("ELP_EQWALIZER_PATH", "LD_PRELOAD", "ERL_FLAGS", "REBAR_CONFIG", "BASH_ENV", "PYTHONPATH")) assertFalse(env.containsKey(bad))
        assertEquals("/run/owner/tmp", env["TMPDIR"])
        assertEquals("/root", env["HOME"])
    }
}
