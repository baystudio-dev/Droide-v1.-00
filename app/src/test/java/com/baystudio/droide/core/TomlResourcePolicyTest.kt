package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class TomlResourcePolicyTest {
 @Test fun explicitTomlLimitsDoNotChangeGenericDefaults(){assertEquals(16_777_216L,TomlResourcePolicy.ANALYSIS.addressSpaceKiB);assertEquals(600,TomlResourcePolicy.ANALYSIS.cpuSeconds);assertEquals(60,TomlResourcePolicy.HEALTH.cpuSeconds);assertEquals(768*1024,ProcessResourceLimits().virtualMemoryKiB);assertEquals(64,ProcessResourceLimits().processes);assertEquals(listOf("--disable-wasm-trap-handler","--max-old-space-size=256"),TomlResourcePolicy.ANALYSIS.nodeArguments);assertEquals(805_306_368L,TomlResourcePolicy.OBSERVED_RSS_BYTES_MAX);assertEquals(8,TomlResourcePolicy.GROUP_MEMBERS_MAX)}
 @Test fun shellSetsAndReadsBothLimits(){val shell=TomlResourcePolicy.HEALTH.shellPrefix().joinToString(" ");for(flag in listOf("c","t","f","n","u","v")){assertTrue(shell.contains("ulimit -S$flag"));assertTrue(shell.contains("ulimit -H$flag"))};assertTrue(shell.contains("16777216"))}
}
