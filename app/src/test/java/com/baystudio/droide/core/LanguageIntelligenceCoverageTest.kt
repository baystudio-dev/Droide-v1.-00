package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.Registry

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LanguageIntelligenceCoverageTest {
    @Test fun everyBuiltInFileAssociationHasAServerOrExplicitEditingSupport() {
        val missing = mutableListOf<String>()
        for (language in LanguageRegistry.all.filter { LanguageRegistry.isBuiltIn(it.id) }) {
            for (path in language.extensions.map { "sample.$it" } + language.filenames) {
                if (LanguageServerRegistry.forPath(path).isEmpty() && LanguageServerRegistry.unavailableReason(path) == null)
                    missing += "${language.id}: $path"
            }
        }
        assertEquals(emptyList<String>(), missing)
        assertTrue(LanguageServerRegistry.forPath("build.makefile").isEmpty())
        assertTrue(LanguageServerRegistry.unavailableReason("build.makefile")!!.contains("syntax support only"))
    }

    @Test fun mdxGetsItsOwnWireIdentityAndInstallableServer() {
        assertEquals(listOf("mdx-language-server"), LanguageServerRegistry.forPath("Page.mdx").map { it.id })
        assertEquals("mdx", LanguageServerRegistry.protocolLanguageId("Page.mdx"))
        assertEquals(listOf("marksman"), LanguageServerRegistry.forPath("Page.md").map { it.id })
        val entry = UbuntuNpmCatalog.entries.single { it.familyId == "lsp.mdx-language-server" }
        assertEquals("@mdx-js/language-server", entry.packageName)
        assertTrue(entry.allowsPublisherUpdates)
        assertNotNull(ManagedLanguageServerPlan.forServer("mdx-language-server"))
    }

    @Test fun gooseSharesTheSameLanguageIdentityAcrossEditorRunServerAndCatalog() {
        val path = "folder with spaces/main.goose"
        val language = requireNotNull(LanguageRegistry.forFile(path))
        assertEquals("goose", language.id)
        assertEquals("goose", LanguageServerRegistry.protocolLanguageId(path))
        assertEquals(listOf(listOf("goose-lang", "run", path)), language.runner!!.invoke(path).steps)
        assertEquals(listOf(listOf("goose-lang", "lsp")), LanguageServerRegistry.forPath(path).single().commands)
        assertEquals("goose", FileIconRegistry.iconNameFor(path))
        assertEquals("toolchain.goose", UbuntuAptCatalog.languageRuntime[language.id])
        assertNotNull(DevelopmentExtensionCatalog.byId("toolchain.goose"))
        assertNotNull(DevelopmentExtensionCatalog.byId("lsp.goose"))
        assertNull(UbuntuAptCatalog.find("toolchain.goose"))
        assertNull(ManagedLanguageServerPlan.forServer("goose"))
    }

    @Test fun gooseSyntaxAndCompletionWorkWithoutACompiler() {
        val grammar = Registry().addGrammar(IGrammarSource.fromResource(BuiltInEditorGrammars::class.java, "/droide/grammars/goose.tmLanguage.json"))
        val scopes = listOf("// note", "fn main() { print(\"hello\"); }").flatMap { grammar.tokenizeLine(it).tokens.toList() }.flatMap { it.scopes }
        assertTrue(scopes.any { it.startsWith("comment.") })
        assertTrue(scopes.any { it.startsWith("string.") })
        assertTrue(scopes.any { it.startsWith("entity.name.function.") })
        assertTrue("goose" in BuiltInEditorGrammars.languageIds())
        assertTrue(ProfessionalCompletion.localCandidates("goose", "def", 3, "def").any { it.label == "defer" })
        assertTrue(ProfessionalSnippets.builtIns("goose", "main").any { it.insertText.contains("fn main()") })
    }

    @Test fun localFormatsNeverBorrowAnUnrelatedServerAndCustomServersRemainAvailable() {
        for (path in listOf("a.bat", "a.cmd", "a.ini", "a.properties")) {
            assertTrue(LanguageServerRegistry.forPath(path).isEmpty())
            assertNotNull(LanguageServerRegistry.unavailableReason(path))
        }
        val spec = LanguageServerSpec("custom-ini", setOf("ini"), setOf("ini"), listOf(listOf("custom-ini-lsp")), source = "user")
        assertEquals(listOf(spec), LanguageServerRegistry.forPath("a.ini", listOf(spec)))
    }
}
