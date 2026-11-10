package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-only response contract: no workspace, process host, provisioner, sockets or filesystem fixture. */
class TemporaryRecoveryLspManagerTest {
    @Test fun diagnosticsAndStatusExplainTemporaryUnavailabilityWithoutClaimingSuccess() = runBlocking {
        val manager: LspManager = TemporaryRecoveryLspManager()
        assertFalse(manager.diagnostics is MutableStateFlow<*>)
        for (path in listOf("main.py", "Main.kt", "view.tsx")) {
            assertEquals("Diagnostics unavailable. $reason", manager.diagnose(path))
            assertEquals(reason, manager.serverStatus(path))
            for (operation in listOf("hover", "completion", "documentSymbol", "unknown")) {
                assertEquals(reason, manager.lsp(operation, path, 1, 1))
            }
        }
        assertEquals(emptyMap<String, List<LspDiagnostic>>(), manager.diagnostics.value)
        assertEquals(listOf(emptyMap<String, List<LspDiagnostic>>()), manager.diagnostics.replayCache)
    }

    @Test fun everyOptionalRequestUsesAnEmptyOrNullResultWithoutExternalDependencies() = runBlocking {
        val manager: LspManager = TemporaryRecoveryLspManager()
        assertOptionalRequestsAreInert(manager)
        assertTrue(manager.diagnostics.value.isEmpty())
    }

    @Test fun documentNotificationsAndRepeatedShutdownRemainInertForLaterRequests() = runBlocking {
        val manager: LspManager = TemporaryRecoveryLspManager()
        val diagnostics = manager.diagnostics
        repeat(2) {
            manager.didOpen(path, "before")
            manager.didChange(path, "dirty")
            manager.didSave(path)
            manager.didSave(path, "saved")
            manager.didSaveSnapshot(path, "saved", "saved")
            manager.didSaveSnapshot(path, "saved", "newer dirty")
            manager.didClose(path)
            manager.shutdown()
            manager.close()
            assertSame(diagnostics, manager.diagnostics)
            assertTrue(diagnostics.value.isEmpty())
            assertEquals("Diagnostics unavailable. $reason", manager.diagnose(path))
            assertEquals(reason, manager.lsp("hover", path))
            assertEquals(reason, manager.serverStatus(path))
            assertOptionalRequestsAreInert(manager)
        }
    }

    private suspend fun assertOptionalRequestsAreInert(manager: LspManager) {
        assertEquals(emptyList<LspCompletionItem>(), manager.completionItems(path, 1, 1))
        assertEquals(emptyList<LspCompletionItem>(), manager.completionItems(path, 1, 1, ".", "dirty"))
        assertEquals(LspSignatureTriggers(), manager.signatureHelpTriggers(path))
        assertNull(manager.signatureHelp(path, 1, 1, null))
        for (kind in LspSignatureTriggerKind.values()) {
            assertNull(manager.signatureHelp(
                path, 1, 1, "dirty", kind, "(", true,
                LspSignatureHelp(listOf(LspSignatureInformation("f(value)"))),
            ))
        }
        for (text in listOf(null, "dirty")) {
            assertEquals(emptyList<LspSemanticToken>(), manager.semanticTokens(path, text))
            assertEquals(emptyList<LspInlayHint>(), manager.inlayHints(path, text))
        }
        for (operation in listOf("goToDefinition", "findReferences", "goToImplementation")) {
            assertEquals(emptyList<LspLocation>(), manager.locationItems(operation, path, 1, 1))
        }
        assertEquals(emptyList<LspSymbol>(), manager.symbolItems(path))
        assertEquals(emptyList<LspSymbol>(), manager.workspaceSymbols(path, "value"))
        val diagnostic = LspDiagnostic(1, 1, 1, 2, 1, "fixture", "fixture")
        assertEquals(emptyList<LspCodeAction>(), manager.codeActions(path, diagnostic))
        assertEquals(emptyList<LspCodeAction>(), manager.codeActionsAt(path, 1, 1))
        val action = LspCodeAction("existing caller-owned action", rawJson = "{}")
        assertSame(action, manager.resolveCodeAction(path, action))
        val call = LspCallHierarchyItem(
            "f", detail = null, kind = null, path = path, line = 1, column = 1, rawJson = "{}",
        )
        assertEquals(emptyList<LspCallHierarchyItem>(), manager.prepareCallHierarchy(path, 1, 1))
        assertEquals(emptyList<LspCallHierarchyItem>(), manager.incomingCalls(call))
        assertEquals(emptyList<LspCallHierarchyItem>(), manager.outgoingCalls(call))
        assertNull(manager.renameSymbol(path, 1, 1, "renamed"))
        assertNull(manager.formatDocument(path))
    }

    private companion object {
        const val path = "main.py"
        const val reason =
            "Language servers and installed tools are temporarily unavailable in built-in startup recovery."
    }
}
