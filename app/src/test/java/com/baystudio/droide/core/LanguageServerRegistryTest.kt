package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageServerRegistryTest {
    @Test fun resolvesCompoundLanguageFamiliesDeterministically() {
        assertTrue(LanguageServerRegistry.forPath("src/main.ts").any { it.id == "typescript" })
        assertTrue(LanguageServerRegistry.forPath("native/main.cpp").any { it.id == "clangd" })
        assertTrue(LanguageServerRegistry.forPath("native/main.c++").any { it.id == "clangd" })
        assertTrue(LanguageServerRegistry.forPath("src/main.rs").any { it.id == "rust-analyzer" })
        assertTrue(LanguageServerRegistry.forPath("src/Main.kt").any { it.id == "kotlin" })
        assertTrue(LanguageServerRegistry.forPath("src/Main.java").any { it.id == "jdtls" })
    }

    @Test fun pythonKeepsPyrightBeforePylspFallback() {
        val ids = LanguageServerRegistry.forPath("main.py").map { it.id }
        assertEquals(listOf("python-pyright", "python-pylsp"), ids.take(2))
    }

    @Test fun usesStandardProtocolLanguageIdsInsteadOfEditorDisplayFamilies() {
        assertEquals("javascriptreact", LanguageServerRegistry.protocolLanguageId("src/component.jsx"))
        assertEquals("typescriptreact", LanguageServerRegistry.protocolLanguageId("src/component.tsx"))
        assertEquals("shellscript", LanguageServerRegistry.protocolLanguageId("scripts/install.sh"))
        assertEquals("objective-c", LanguageServerRegistry.protocolLanguageId("native/main.m"))
        assertEquals("objective-cpp", LanguageServerRegistry.protocolLanguageId("native/main.mm"))
        assertEquals("cpp", LanguageServerRegistry.protocolLanguageId("native/main.C"))
        assertEquals("cpp", LanguageServerRegistry.protocolLanguageId("native/header.hxx"))
        assertEquals("plaintext", LanguageServerRegistry.protocolLanguageId("README"))
    }

    @Test fun webVariantsAndReviewedRuntimeServersHaveActualCommands() {
        assertEquals("vscode-css-language-server", LanguageServerRegistry.forPath("styles/main.scss").single().id)
        assertEquals("vscode-css-language-server", LanguageServerRegistry.forPath("styles/main.less").single().id)
        assertEquals("vscode-json-language-server", LanguageServerRegistry.forPath(".vscode/settings.jsonc").single().id)
        assertEquals(listOf("dart", "language-server", "--protocol=lsp"), LanguageServerRegistry.forPath("lib/main.dart").single().commands.single())
        assertEquals(listOf("sourcekit-lsp"), LanguageServerRegistry.forPath("Sources/main.swift").single().commands.single())
        assertEquals(listOf("docker-langserver", "--stdio"), LanguageServerRegistry.forPath("src.with.dot/Dockerfile").single().commands.single())
        assertEquals(listOf("lemminx"), LanguageServerRegistry.forPath("data.xml").single { it.id == "lemminx" }.commands.single())
        assertFalse(LanguageServerRegistry.forPath("data.XML").any { it.id == "lemminx" })
        assertEquals(listOf("vue-language-server"), LanguageServerRegistry.forPath("Component.vue").single().commands.single())
        assertTrue(LanguageServerRegistry.forPath("Component.VUE").isEmpty())
        assertEquals(listOf("typescript"), LanguageServerRegistry.forPath("companion.ts").map { it.id })
        assertEquals(listOf("csharp-ls"), LanguageServerRegistry.forPath("Program.cs").single().commands.single())
        assertTrue(LanguageServerRegistry.forPath("Program.CS").isEmpty())
        assertTrue(LanguageServerRegistry.forPath("script.csx").isEmpty())
        assertEquals("csharp", LanguageServerRegistry.protocolLanguageId("Program.cs"))
    }
}
