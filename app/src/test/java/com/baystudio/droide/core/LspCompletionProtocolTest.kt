package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LspCompletionProtocolTest {
    @Test fun rawSnippetPolicyDefaultsToNullAndCannotComeFromServer() {
        val raw = Json.parseToJsonElement("""[{"label":"x","insertText":"RELATIVE_FILEPATH: ${'$'}1","insertTextFormat":2,"nativeSnippetPolicy":"GRAPHQL_NUMERIC_RAW"}]""")
        val item = LspCompletionProtocol.parse(raw).single()
        assertNull(item.nativeSnippetPolicy)
        assertEquals(ProfessionalSnippets.sanitizeForSora("RELATIVE_FILEPATH: ${'$'}1"), item.insertText)
        assertNull(LspCompletionItem("x","x").nativeSnippetPolicy)
    }
    @Test fun typedManagerOptInKeepsExactRawTextOnlyWhenExplicit() {
        val source = "RELATIVE_FILEPATH: ${'$'}1"
        val item = LspCompletionItem("x",source,isSnippet=true,nativeSnippetPolicy=LspNativeSnippetPolicy.GRAPHQL_NUMERIC_RAW)
        assertEquals(source,item.insertText)
        assertEquals(LspNativeSnippetPolicy.GRAPHQL_NUMERIC_RAW,item.nativeSnippetPolicy)
    }
}
