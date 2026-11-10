package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ToolchainProbeRecoveryTest {
    @Test fun batchMissingIsNotProbeFailure() = runBlocking {
        assertEquals(mapOf("java" to "/usr/bin/java"), ExternalToolchainDiscovery.observe(listOf("java", "rustc")) {
            ExecResult(0,"java\t/usr/bin/java\nrustc\t\n",false)
        })
    }
    @Test fun terminalInstalledExecutableAppearsOnNextFreshProbe() = runBlocking {
        var installed=false
        val probe:suspend (String)->ExecResult={ExecResult(0,"node\t${if(installed) "/root/.local/bin/node" else ""}\n",false)}
        assertTrue(ExternalToolchainDiscovery.observe(listOf("node"),probe).isEmpty())
        installed=true
        assertEquals("/root/.local/bin/node",ExternalToolchainDiscovery.observe(listOf("node"),probe)["node"])
    }
    @Test fun failedRefreshCannotReplaceLastPublishedCapabilitiesWithEmptyMap() = runBlocking {
        var published=mapOf("node" to "/root/.local/bin/node")
        val failure=runCatching { published=ExternalToolchainDiscovery.observe(listOf("node")) { error("workspace process admission pending") } }.exceptionOrNull()
        assertTrue(failure is ToolchainProbeFailed)
        assertEquals("/root/.local/bin/node",published["node"])
        assertTrue(failure!!.message!!.contains("admission pending"))
        assertFalse(failure.message!!.contains("Install or select"))
    }
    @Test fun timeoutExitAndTruncationAreNotAbsence() = runBlocking {
        for(r in listOf(ExecResult(0,"",true),ExecResult(7,"failure",false),ExecResult(0,"node\t/usr/bin/node\n",false))) {
            assertTrue(runCatching { ExternalToolchainDiscovery.observe(listOf("node","java")){r} }.exceptionOrNull() is ToolchainProbeFailed)
        }
    }
    @Test fun lookupKeepsMissingNullButPreservesExceptionCause() = runBlocking {
        assertNull(checkedToolchainProbe("Debug adapter"){null})
        val original=java.io.IOException("cannot start")
        val error=runCatching { checkedToolchainProbe("Debug adapter"){throw original} }.exceptionOrNull()
        assertSame(original,error!!.cause)
        assertTrue(error.message!!.contains("not missing"))
    }
    @Test fun cancellationPropagatesWithoutWrapping() = runBlocking {
        val cancel=CancellationException("cancel")
        assertSame(cancel,runCatching { checkedToolchainProbe("LSP"){throw cancel} }.exceptionOrNull())
    }
    @Test fun nestedProbeFailureIsNotWrappedTwice() = runBlocking {
        val original=ToolchainProbeFailed("first",IllegalStateException("blocked"))
        assertSame(original,runCatching { checkedToolchainProbe("second"){throw original} }.exceptionOrNull())
    }
    @Test fun androidStartupFailureDoesNotPrescribeInstallation() = runBlocking {
        val result=LocalAndroidToolchainDiscovery(File(".")){_,_->error("process admission blocked")}.resolve(AndroidToolchainRequirements(compileSdk=36))
        assertTrue(result.probeFailed)
        assertTrue(result.diagnostic().contains("process admission blocked"))
        assertFalse(result.diagnostic().contains("Install or select"))
    }
    @Test fun androidMissingToolStillPrescribesInstallation() = runBlocking {
        val result=LocalAndroidToolchainDiscovery(File(".")){_,_->ExecResult(41,"ERROR=JDK not found in local Ubuntu",false)}.resolve(AndroidToolchainRequirements(compileSdk=36))
        assertFalse(result.probeFailed)
        assertTrue(result.diagnostic().contains("Install or select"))
    }
    @Test fun androidTimeoutIsFailedCheckNotMissing() = runBlocking {
        val result=LocalAndroidToolchainDiscovery(File(".")){_,_->ExecResult(0,"",true)}.resolve(AndroidToolchainRequirements(compileSdk=36))
        assertTrue(result.probeFailed)
        assertFalse(result.diagnostic().contains("Install or select"))
    }
    @Test fun failedCandidateDoesNotHideHealthyAlternative() = runBlocking {
        val result = resolveToolchainCommand(listOf(listOf("bad"), listOf("good", "--stdio"))) {
            if (it == "bad") error("blocked") else "/bin/good"
        }
        assertEquals(listOf("/bin/good", "--stdio"), result)
    }
    @Test fun failedCandidateDoesNotBecomeMissingWhenAlternativesAbsent() = runBlocking {
        val failure = runCatching { resolveToolchainCommand(listOf(listOf("bad"), listOf("missing"))) {
            if (it == "bad") error("blocked") else null
        } }.exceptionOrNull()
        assertTrue(failure is ToolchainProbeFailed)
    }
    @Test fun shellFailureAndExecutableFailureAreNotMissingInstallation() = runBlocking {
        for (code in listOf(1, 49, 50, 64, 127)) {
            val result = LocalAndroidToolchainDiscovery(File(".")){_,_->ExecResult(code,"ERROR=execution failed",false)}.resolve(AndroidToolchainRequirements(compileSdk=36))
            assertTrue(result.probeFailed)
            assertFalse(result.diagnostic().contains("Install or select"))
        }
    }
}
