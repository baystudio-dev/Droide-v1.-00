package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspTomlResultAuthorityTest {
    @Test fun exactRenameSubsetNeverTrimsOrQuotes() {
        for (name in listOf("a_0-", "''", "\"\"", "'clé 😀'", "\"clé 😀\"", "'a\\b'")) assertTrue(name,LspTomlResultAuthority.renameSpelling(name))
        for (name in listOf("", " a ", "a.b", "clé", "\"a\\b\"", "'a'b'", "'a\nb'", "a".repeat(257))) assertFalse(name,LspTomlResultAuthority.renameSpelling(name))
    }
    @Test fun currentDocumentEditsRetainOriginalCapture() {
        val text="'é' = 1\n";val uri="file:///tmp/a.toml"
        val edit=Json.parseToJsonElement("""{"changes":{"$uri":[{"range":{"start":{"line":0,"character":1},"end":{"line":0,"character":2}},"newText":"😀"}]}}""")
        var current=true
        val parsed=requireNotNull(LspTomlResultAuthority.edits(edit,false,uri,"a.toml",text,3){current})
        assertEquals(text,parsed.expectedContent["a.toml"]);assertEquals(3,parsed.documentVersions["a.toml"])
        current=false;assertFalse(parsed.applicationGuard!!.invoke())
        assertTrue(runCatching{LspTomlResultAuthority.edits(edit,false,"file:///tmp/b.toml","b.toml",text,3){true}}.isFailure)
        assertTrue(runCatching{LspTomlResultAuthority.prepareRename(JsonNull,text)}.isFailure)
    }
    @Test fun diagnosticProvenanceIsQualifiedAndNeverVersionInvented() {
        val raw=Json.parseToJsonElement("""{"uri":"file:///tmp/a.toml","diagnostics":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"message":"bad","severity":1}]}""")
        val d=LspTomlResultAuthority.diagnostics(raw,"file:///tmp/a.toml","a").single()
        assertTrue(d.source!!.contains("exact computation revision unverified"))
        assertTrue(runCatching{LspTomlResultAuthority.diagnostics(JsonObject(raw.jsonObject+("version" to JsonPrimitive(3))),"file:///tmp/a.toml","a")}.isFailure)
    }
}
