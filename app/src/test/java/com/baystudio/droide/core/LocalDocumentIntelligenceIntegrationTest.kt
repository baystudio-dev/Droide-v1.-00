package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class LocalDocumentIntelligenceIntegrationTest {
    @Test(timeout = 10000) fun localOutlineUsesDirtyBufferAndInvalidatesHeldResultsAfterChangesAndClose() = runBlocking {
        Fixture().use { f ->
            f.root.resolve("a.ini").writeText("[saved]\nkey=value")
            f.manager.didOpen("a.ini", "[dirty]\nnew=value")
            val symbols = f.manager.symbolItems("./a.ini")
            assertEquals(listOf("dirty", "new"), symbols.map { it.name })
            assertTrue(symbols.first().activationGuard!!.isCurrent())
            f.manager.didChange("a.ini", "[newest]")
            assertFalse(symbols.first().activationGuard!!.isCurrent())
            val latest = f.manager.symbolItems("a.ini").single()
            assertEquals("newest", latest.name)
            f.manager.didClose("a.ini")
            assertFalse(latest.activationGuard!!.isCurrent())
            assertEquals(0, f.lookups)
        }
    }

    @Test(timeout = 10000) fun agentFileBackedNavigationRetainsItsAdmissionGuard() = runBlocking {
        Fixture().use { f ->
            f.root.resolve("script.bat").writeText(":start\ngoto start")
            val result = f.manager.heldTextResult("goToDefinition", "script.bat", 2, 7)
            assertEquals("script.bat:1:2", result.text)
            assertTrue(result.guard!!.isCurrent())
            f.manager.didOpen("script.bat", ":changed\ngoto changed")
            assertFalse(result.guard!!.isCurrent())
            assertFalse(f.manager.diagnose("script.bat").contains("No issue found"))
        }
    }

    @Test(timeout = 10000) fun openingAndEditingNewLanguagesDoesNotProbeOrLaunchToolchains() = runBlocking {
        Fixture().use { f ->
            val provisioned = ProvisioningLspManager(f.manager, { error("Opening a file must not check or install a server") })
            for (path in listOf("a.goose", "a.mdx", "a.bat", "a.ini", "a.properties")) {
                provisioned.didOpen(path, "")
                provisioned.didChange(path, "edited")
                provisioned.didClose(path)
            }
            assertEquals(0, f.lookups)
        }
    }

    @Test(timeout = 10000) fun customServerTakesPrecedenceAndInvalidatesLocalResults() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("a.ini", "[local]")
            val old = f.manager.symbolItems("a.ini").single()
            f.custom = listOf(LanguageServerSpec("custom-ini", setOf("ini"), setOf("ini"), listOf(listOf("custom-ini-lsp")), source = "user"))
            assertFalse(old.activationGuard!!.isCurrent())
            assertTrue(f.manager.symbolItems("a.ini").isEmpty())
            assertTrue(f.lookups > 0)
        }
    }

    @Test(timeout = 10000) fun localSyntaxChecksFollowUnsavedContent() = runBlocking {
        Fixture().use { f ->
            f.root.resolve("a.properties").writeText("key=valid")
            f.manager.didOpen("a.properties", "key=\\uXYZW")
            assertTrue(f.manager.diagnose("a.properties").contains("Malformed Unicode"))
            f.manager.didChange("a.properties", "key=valid")
            assertFalse(f.manager.diagnose("a.properties").contains("Malformed Unicode"))
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("droide-local-intelligence").toFile()
        val job = SupervisorJob()
        var lookups = 0
        var custom = emptyList<LanguageServerSpec>()
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String): String? { lookups++; return null }
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess =
                error("Local editor features must not spawn a process")
        }
        val manager = ProfessionalLspManager(CoroutineScope(job + Dispatchers.IO), FileRepository(root), object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath: String) = "fallback"
            override suspend fun lsp(op: String, path: String, line: Int, col: Int) = "fallback"
        }, remoteProcessHost = host, extraLanguageServers = { custom })
        override fun close() {
            manager.close()
            job.cancel()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }
}
