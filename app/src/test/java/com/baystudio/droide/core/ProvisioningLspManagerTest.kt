package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningLspManagerTest {
    @Test fun environmentHintInvalidatesPresenceWithoutStartingAnything() = runBlocking {
        var calls=0;var hints=0
        val delegate=object:RecordingLsp(){override fun environmentInvalidated(){hints++}}
        val manager=ProvisioningLspManager(delegate,ensureLanguageServer={calls++;true})
        manager.completionItems("a.rs",1,1);manager.completionItems("a.rs",1,1)
        assertEquals(1,calls)
        manager.environmentInvalidated();assertEquals(1,calls);assertEquals(1,hints)
        manager.completionItems("a.rs",1,1);assertEquals(2,calls)
    }
    @Test fun invalidationDuringProbeCannotPublishStaleMissingBackoff() = runBlocking {
        var calls=0
        lateinit var manager:ProvisioningLspManager
        manager=ProvisioningLspManager(RecordingLsp(),ensureLanguageServer={
            calls++;if(calls==1){manager.environmentInvalidated();false}else true
        })
        manager.completionItems("a.rs",1,1);manager.completionItems("a.rs",1,1)
        assertEquals(2,calls)
    }
    @Test fun tomlTypedDelegateNeverUsesGenericPresenceHealthBeforeOrAfterConsent() = runBlocking {
        var checks = 0
        var enabled = false
        val delegate = object : RecordingLsp() {
            override fun projectAnalysisStatus(path: String) =
                LspProjectAnalysisStatus(LspTomlProfile.ID, enabled, "/project")
        }
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { checks++; true })
        lsp.didOpen("config.toml", "name = \"dirty\"")
        lsp.completionItems("config.toml", 1, 1)
        enabled = true
        lsp.completionItems("config.toml", 1, 1)
        lsp.semanticTokens("config.toml", "name = \"dirty\"")
        assertEquals(0, checks)
        assertEquals(3, delegate.requests)
    }

    @Test fun tomlKnownUnreadyDelegateBlocksBeforeGenericPresenceHealth() = runBlocking {
        var checks = 0
        val delegate = object : RecordingLsp() {
            override fun projectAnalysisStatus(path: String) =
                LspProjectAnalysisStatus(LspTomlProfile.ID, true, "/project",
                    readiness = LspProjectAnalysisReadiness.PREPARING)
        }
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { checks++; true })
        assertTrue(runCatching { lsp.completionItems("config.toml", 1, 1) }.exceptionOrNull() is LspProjectAnalysisNotReadyException)
        assertEquals(0, checks)
        assertEquals(0, delegate.requests)
    }

    @Test fun svelteDiscoveryWaitsForExplicitConsentAndDoesNotRunOnLifecycleEvents() = runBlocking {
        val checks = mutableListOf<String>()
        var enabled = false
        val delegate = object : RecordingLsp() {
            override fun projectAnalysisStatus(path: String) =
                LspProjectAnalysisStatus("svelte-language-server", enabled, "/project")
        }
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { checks += it; true })
        lsp.didOpen("App.svelte", "<h1>Hello</h1>")
        lsp.didChange("helper.ts", "export const text = 'dirty'")
        lsp.completionItems("App.svelte", 1, 1)
        assertTrue(checks.isEmpty())
        enabled = true
        lsp.completionItems("App.svelte", 1, 1)
        assertEquals(listOf("svelte-language-server"), checks)
    }

    @Test fun svelteKnownUnreadyAnalysisBlocksBeforePresenceDiscovery() = runBlocking {
        var checks = 0
        val delegate = object : RecordingLsp() {
            override fun projectAnalysisStatus(path: String) =
                LspProjectAnalysisStatus("svelte-language-server", true, "/project",
                    readiness = LspProjectAnalysisReadiness.PREPARING)
        }
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { checks++; true })
        assertTrue(runCatching { lsp.completionItems("App.svelte", 1, 1) }.exceptionOrNull() is LspProjectAnalysisNotReadyException)
        assertEquals(0, checks)
        assertEquals(0, delegate.requests)
    }

    @Test fun savedSnapshotIsForwardedWithoutProvisioningOrLosingTheNewerLiveText() = runBlocking {
        var provisions = 0
        val events = mutableListOf<Pair<String, String?>>()
        val delegate = object : RecordingLsp() {
            override suspend fun didSave(path: String, text: String?) { events += "save" to text }
            override suspend fun didChange(path: String, text: String) { events += "change" to text }
        }
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { provisions++; true })
        lsp.didSaveSnapshot("main.rs", "saved-B", "live-C")
        assertEquals(listOf("save" to "saved-B", "change" to "live-C"), events)
        assertEquals(0, provisions)
    }

    @Test fun failedPythonProviderStillAllowsHealthyAlternative() = runBlocking {
        val delegate = RecordingLsp()
        val checked = mutableListOf<String>()
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = {
            checked += it
            if (it == "python-pyright") error("probe blocked")
            it == "python-pylsp"
        })
        lsp.completionItems("main.py", 1, 1)
        assertEquals(listOf("python-pyright", "python-pylsp"), checked)
        assertEquals(1, delegate.requests)
    }

    @Test fun failedProbeNeverDelegatesOrSuppressesImmediateRetry() = runBlocking {
        var provisions = 0
        val delegate = RecordingLsp()
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = {
            provisions++
            if (provisions == 1) error("process admission blocked")
            true
        })
        try {
            lsp.completionItems("src/lib.rs", 1, 1)
            throw AssertionError("Expected failed probe diagnostic")
        } catch (failure: ToolchainProbeFailed) {
            assertTrue(failure.message.orEmpty().contains("process admission blocked"))
        }
        assertEquals(0, delegate.requests)
        lsp.completionItems("src/lib.rs", 1, 1)
        assertEquals(2, provisions)
        assertEquals(1, delegate.requests)
    }

    @Test fun cancelledProvisioningNeverDelegatesOrSuppressesRetry() = runBlocking {
        var provisions = 0
        val delegate = RecordingLsp()
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = {
            provisions++
            if (provisions == 1) throw CancellationException("cancelled install")
            true
        })
        try {
            lsp.completionItems("src/lib.rs", 1, 1)
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, delegate.requests)
        lsp.completionItems("src/lib.rs", 1, 1)
        assertEquals(2, provisions)
        assertEquals(1, delegate.requests)
    }

    @Test fun editorLifecycleNeverProvisions() = runBlocking {
        var provisions = 0
        val delegate = RecordingLsp()
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { provisions++; true })

        lsp.didOpen("src/lib.rs", "fn main() {}")
        lsp.didChange("src/lib.rs", "fn main() { println!(\"ok\"); }")
        lsp.didSave("src/lib.rs")
        lsp.didClose("src/lib.rs")

        assertEquals(0, provisions)
        assertEquals(listOf("open", "change", "save", "close"), delegate.lifecycle)
    }

    @Test fun explicitIntelligenceProvisionsOnceThenDelegates() = runBlocking {
        val provisioned = mutableListOf<String>()
        val delegate = RecordingLsp()
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { serverId ->
            provisioned += serverId
            serverId == "rust-analyzer"
        })

        lsp.completionItems("src/lib.rs", 1, 4)
        lsp.semanticTokens("src/lib.rs", "fn main() {}")

        assertEquals(listOf("rust-analyzer"), provisioned)
        assertEquals(2, delegate.requests)
    }

    @Test fun failedProvisionBacksOffAndRetriesAfterDeadline() = runBlocking {
        var now = 1_000L
        var provisions = 0
        val delegate = RecordingLsp()
        val lsp = ProvisioningLspManager(
            delegate = delegate,
            ensureLanguageServer = { provisions++; false },
            retryBackoffMs = 30_000L,
            nowMs = { now },
        )

        lsp.completionItems("src/lib.rs", 1, 1)
        lsp.completionItems("src/lib.rs", 1, 2)
        assertEquals(1, provisions)

        now += 30_001L
        lsp.completionItems("src/lib.rs", 1, 3)
        assertEquals(2, provisions)
        assertEquals(3, delegate.requests)
    }

    @Test fun unrelatedTextFileDoesNotProvision() = runBlocking {
        var provisions = 0
        val delegate = RecordingLsp()
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { provisions++; true })

        val result = lsp.completionItems("notes.txt", 1, 1)

        assertTrue(result.isEmpty())
        assertEquals(0, provisions)
        assertEquals(1, delegate.requests)
    }

    @Test fun forwardsInlayHintsFromTheRealDelegateWithTheDirtySnapshot() = runBlocking {
        val expected = listOf(LspInlayHint(1, 8, "name:"))
        var observedText: String? = null
        var checks = 0
        val delegate = object : RecordingLsp() {
            override suspend fun inlayHints(path: String, currentText: String?): List<LspInlayHint> {
                assertEquals("src/lib.rs", path)
                observedText = currentText
                return expected
            }
        }
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { checks++; true })
        assertEquals(expected, lsp.inlayHints("src/lib.rs", "fn main() { call(1); }"))
        assertEquals("fn main() { call(1); }", observedText)
        assertEquals(1, checks)
    }

    @Test fun readinessRechecksAfterUninstallOrActivationAndAfterClockRegression() = runBlocking {
        var now = 1_000L
        var checks = 0
        var available = true
        val lsp = ProvisioningLspManager(
            RecordingLsp(), ensureLanguageServer = { checks++; available },
            nowMs = { now }, readinessTtlMs = 100L,
        )
        lsp.completionItems("src/lib.rs", 1, 1)
        now += 99L
        lsp.completionItems("src/lib.rs", 1, 2)
        assertEquals(1, checks)
        now += 1L
        lsp.completionItems("src/lib.rs", 1, 3)
        assertEquals(2, checks)
        now = 900L
        lsp.completionItems("src/lib.rs", 1, 4)
        assertEquals(3, checks)
        available = false
        now += 101L
        lsp.completionItems("src/lib.rs", 1, 5)
        lsp.completionItems("src/lib.rs", 1, 6)
        assertEquals("Failed readiness still backs off instead of retrying every keystroke", 4, checks)
    }

    private open class RecordingLsp : LspManager {
        private val state = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = state
        val lifecycle = mutableListOf<String>()
        var requests = 0

        override suspend fun diagnose(relPath: String): String { requests++; return "OK" }
        override suspend fun lsp(op: String, path: String, line: Int, col: Int): String { requests++; return "OK" }
        override suspend fun completionItems(path: String, line: Int, col: Int): List<LspCompletionItem> {
            requests++
            return emptyList()
        }
        override suspend fun semanticTokens(path: String, currentText: String?): List<LspSemanticToken> {
            requests++
            return emptyList()
        }
        override suspend fun didOpen(path: String, text: String) { lifecycle += "open" }
        override suspend fun didChange(path: String, text: String) { lifecycle += "change" }
        override suspend fun didSave(path: String, text: String?) { lifecycle += "save" }
        override suspend fun didClose(path: String) { lifecycle += "close" }
    }
}
