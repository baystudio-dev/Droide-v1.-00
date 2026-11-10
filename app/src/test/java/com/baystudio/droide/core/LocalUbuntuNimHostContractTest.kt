package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuNimHostContractTest {
    @Test fun unsupportedHostHasNoReviewedIdentity()=runBlocking {
        val host=object:StdioProcessHost {
            override val executionScope=ProcessExecutionScope.DEVICE_ADB
            override suspend fun resolveExecutable(command:String):String?=null
            override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess=error("Generic fallback forbidden")
        }
        assertNull(host.reviewedNimLaunchIdentity("/usr/bin/nimlangserver","/workspace"))
    }
    @Test fun fixedRefusalEntryNeverDelegatesOrEmitsStdout() {
        val entry=LspNimLaunchPolicy.refusalEntry("/tmp/droide-nim-123")
        assertTrue(entry.contains(" --refuse '/tmp/droide-nim-123'"));assertTrue(entry.contains(LspNimLaunchPolicy.HELPER));assertFalse(entry.contains("\$@"));assertFalse(entry.contains("guard.json"))
    }
}
