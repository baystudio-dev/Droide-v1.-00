package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageRuntimePresentationTest {
    @Test fun configurationFormatsDoNotRequireACompilerInstaller() {
        listOf("json", "yaml", "toml", "ini", "properties").forEach { language ->
            assertEquals(LanguageRuntimePresentation.Kind.DATA_OR_DOCUMENT, LanguageRuntimePresentation.kind(language))
        }
        assertFalse(LanguageRuntimePresentation.description("json", "JSON").contains("installed separately"))
    }

    @Test fun missingRuntimeMappingDoesNotTurnExecutableLanguagesIntoConfiguration() {
        listOf("shell", "batch", "objectivec", "assembly", "vendor-language").forEach { language ->
            assertFalse(LanguageRuntimePresentation.kind(language) == LanguageRuntimePresentation.Kind.DATA_OR_DOCUMENT)
        }
        assertEquals(LanguageRuntimePresentation.Kind.LINUX_SHELL, LanguageRuntimePresentation.kind("shell"))
        assertEquals(LanguageRuntimePresentation.Kind.SEPARATE_TOOL, LanguageRuntimePresentation.kind("python"))
    }

    @Test fun shellIsAbsentWithoutActualGuestDiscovery() {
        val state = LanguageRuntimePresentation.shellStatus(emptyMap())
        assertFalse(state.runFileShellDetected)
        assertTrue(state.guidance.contains("Terminal → + Linux"))
    }

    @Test fun discoveredShellNeedsNoSeparateManagedReceipt() {
        val state = LanguageRuntimePresentation.shellStatus(mapOf("sh" to "/usr/bin/sh", "bash" to "/usr/bin/bash"))
        assertTrue(state.runFileShellDetected)
        assertEquals("/usr/bin/sh", state.shPath)
        assertEquals("/usr/bin/bash", state.bashPath)
        assertTrue(state.guidance.contains("No separate shell installation is needed"))
    }

    @Test fun bashAloneDoesNotEstablishRunFileShellReadiness() {
        val state = LanguageRuntimePresentation.shellStatus(mapOf("bash" to "/usr/bin/bash"))
        assertFalse(state.runFileShellDetected)
        assertEquals("/usr/bin/bash", state.bashPath)
        assertTrue(state.guidance.contains("Run File requires sh"))
    }

    @Test fun relativeOrMalformedDiscoveryCannotProduceShellReadiness() {
        listOf("sh", "./sh", "/usr/bin/sh\nfailed", "/usr/bin/sh\u0000").forEach { path ->
            assertFalse(LanguageRuntimePresentation.shellStatus(mapOf("sh" to path)).runFileShellDetected)
        }
    }

    @Test fun removedShellDoesNotReuseEarlierDetection() {
        assertTrue(LanguageRuntimePresentation.shellStatus(mapOf("sh" to "/usr/bin/sh")).runFileShellDetected)
        assertFalse(LanguageRuntimePresentation.shellStatus(mapOf("node" to "/usr/bin/node")).runFileShellDetected)
    }
}
