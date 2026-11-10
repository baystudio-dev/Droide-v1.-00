package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspTomlProfileTest {
    @Test fun exactConfigurationAndFullNativeCapabilities() {
        val root = "file:///workspace/project/"
        val params = Json.parseToJsonElement("""{"items":[{"section":"evenBetterToml"},{"section":"evenBetterToml","scopeUri":"$root"}]}""")
        assertEquals(JsonArray(listOf(LspTomlProfile.settings(), LspTomlProfile.settings())), LspTomlProfile.configuration(params, root))
        for (bad in listOf("""{"items":[]}""", """{"items":[{"section":"evenBetterToml","scopeUri":"$root"},{"section":"evenBetterToml"}]}"""))
            assertTrue(runCatching { LspTomlProfile.configuration(Json.parseToJsonElement(bad), root) }.isFailure)
        val good = Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":1,"hoverProvider":true,"definitionProvider":true,"renameProvider":{"prepareProvider":true}}}""")
        assertTrue(LspTomlProfile.capabilities(good).hover)
        assertFalse(LspTomlProfile.capabilities(good).definition)
        assertFalse(LspTomlProfile.capabilities(good).sync.save)
        assertTrue(runCatching { LspTomlProfile.capabilities(Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":2}}""")) }.isFailure)
    }
    @Test fun routeCaseAndCanonicalRootAreExact() {
        assertTrue(LspTomlProfile.supportsPath("test.TOML")); assertFalse(LspTomlProfile.supportsPath("Cargo.lock"))
        assertEquals("file:///tmp/work/", LspTomlProfile.rootUri("/tmp/work"))
        assertEquals("/tmp/work/a.toml", LspTomlProfile.requireOwnedUri("file:///tmp/work/a.toml", "/tmp/work"))
        for (bad in listOf("file:///tmp/work-other/a.toml", "file:///tmp/work/%2Fa.toml", "file:///tmp/work/../a.toml", "file://host/tmp/work/a.toml", "file:///tmp/work/a.toml?q=1"))
            assertTrue(bad, runCatching { LspTomlProfile.requireOwnedUri(bad, "/tmp/work") }.isFailure)
    }
    @Test fun pinnedNativeSemanticLegendRetainsReadonlyModifier() {
        // Frozen upstream semantic_tokens.rs:55-72, not an invented empty modifier legend.
        val result = Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":1,"semanticTokensProvider":{"full":true,"legend":{"tokenTypes":["tomlArrayKey","tomlTableKey"],"tokenModifiers":["readonly"]}}}}""")
        val caps = LspTomlProfile.capabilities(result)
        assertTrue(caps.semanticTokens)
        assertEquals(listOf("readonly"), caps.semanticTokenLegend.tokenModifiers)
        val advertised = LspTomlProfile.clientCapabilities().jsonObject["textDocument"]!!.jsonObject["semanticTokens"]!!.jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("readonly"))), advertised["tokenModifiers"])
        assertTrue(runCatching { LspTomlProfile.capabilities(Json.parseToJsonElement(result.toString().replace("[\"readonly\"]", "[]"))) }.isFailure)
        val tokens = LspTomlResultAuthority.semantic(Json.parseToJsonElement("""{"data":[0,0,1,0,1]}"""), "a", caps.semanticTokenLegend)
        assertEquals(setOf("readonly"), tokens.single().modifiers)
        val spans = DroideSyntaxSemanticEngine.highlight("a", "toml", tokens).spans
        assertEquals(DroideHighlightKind.PROPERTY, spans.single { it.semantic }.kind)
    }

}
