package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class CSharpResourcePolicyTest {
    @Test fun distinctControlsDoNotWidenGenericPolicy() {
        assertEquals(67_108_864L,CSharpResourcePolicy.ANALYSIS.addressSpaceKiB)
        assertEquals(536_870_912L,CSharpResourcePolicy.ANALYSIS.gcHeapBytes)
        assertEquals("0x20000000",CSharpResourcePolicy.ANALYSIS.dotnetEnvironment["DOTNET_GCHeapHardLimit"])
        assertEquals(listOf("--max-old-space-size=64"),CSharpResourcePolicy.ANALYSIS.helperArguments)
        assertEquals(600,CSharpResourcePolicy.ANALYSIS.cpuSeconds)
        assertEquals(60,CSharpResourcePolicy.HEALTH.cpuSeconds)
        assertEquals(768*1024,ProcessResourceLimits().virtualMemoryKiB)
        assertTrue(runCatching { ProcessResourceLimits(virtualMemoryKiB=67_108_864).validate() }.isFailure)
    }
    @Test fun eachHardAndSoftLimitIsAppliedAndReadBack() {
        val script=CSharpResourcePolicy.ANALYSIS.shellPrefix()[2]
        for(flag in listOf("c","t","f","n","u","v")) {
            assertTrue(script.contains("ulimit -S$flag "))
            assertTrue(script.contains("ulimit -H$flag "))
            assertTrue(script.contains("\$(ulimit -S$flag)"))
            assertTrue(script.contains("\$(ulimit -H$flag)"))
        }
        assertFalse(script.contains("|| true"))
    }
}
