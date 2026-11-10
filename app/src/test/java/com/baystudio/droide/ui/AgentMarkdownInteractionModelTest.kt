package com.baystudio.droide.ui

import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.*
import org.junit.Test

class AgentMarkdownInteractionModelTest {
    @Test fun linkAnnotationsIncludeFormattedLabelsAndFormattedBareUrlsButExcludeCode() {
        val text = markdownAnnotatedString("**[Docs](https://example.test/docs)** and *HTTPS://example.test/help* and `https://example.test/code`")
        assertEquals("Docs and HTTPS://example.test/help and https://example.test/code", text.text)
        assertEquals(listOf("https://example.test/docs", "HTTPS://example.test/help"),
            text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url })
    }

    @Test fun exactUrlsRetainBalancedParenthesesAndAuthenticationPunctuation() {
        val urls = listOf("https://example.test/wiki/Function_(math)",
            "https://example.test/auth?state=A%2BB%3D&next=http%3A%2F%2Flocalhost%3A3000%2F&code=x!?",
            "https://example.test/?token=" + "a".repeat(3_000))
        for (url in urls) {
            for (markdown in listOf(url, "[Open]($url)", "<$url>")) {
                val links = AgentMarkdownParser.parseInline(markdown).filter { it.kind == AgentMarkdownInlineKind.LINK }
                assertEquals(markdown, listOf(url), links.map { it.url })
            }
        }
        assertEquals("https://example.test/wiki/Function_(math)",
            AgentMarkdownParser.parseInline("(https://example.test/wiki/Function_(math)).").single { it.kind == AgentMarkdownInlineKind.LINK }.url)
    }

    @Test fun malformedAndNonWebSchemesNeverReceiveClickableAnnotations() {
        val bad = listOf("javascript:alert(1)", "file:///data/private", "intent://app", "https:///missing-host",
            "https://example.test:99999/x", "https://example.test/a b", "https://example.test/\nsecret",
            "https://example.test/" + "a".repeat(16_384))
        for (url in bad) {
            assertFalse(url, AgentMarkdownParser.isSafeHttpUrl(url))
            val annotated = markdownAnnotatedString("[Label]($url)")

            if (!url.contains(' ') && !url.contains('\n')) assertTrue(url, annotated.getLinkAnnotations(0, annotated.length).isEmpty())
        }
    }

    @Test fun annotationListenerReceivesTheExactDestinationOnce() {
        val calls = mutableListOf<String>()
        val url = "https://example.test/auth?redirect_uri=http%3A%2F%2Flocalhost&state=A%2BB%3D"
        val annotated = markdownAnnotatedString("**[Open]($url)**", calls::add)
        val link = annotated.getLinkAnnotations(0, annotated.length).single().item
        link.linkInteractionListener!!.onClick(link)
        assertEquals(listOf(url), calls)
    }

    @Test fun selectionIncludesHeadingsListsTablesCompleteCodeAndLinkTargets() {
        val code = (1..300).joinToString("\n") { "println(\"line $it\")" }
        val markdown = "# **Heading**\n\nOne *paragraph*.\n\n- First\n- [Docs](https://example.test/docs)\n\n| Key | Value |\n| --- | --- |\n| a | b |\n\n```kotlin\n$code\n```"
        val snapshot = AgentMessageSelection.fromMarkdown(markdown)
        assertTrue(snapshot.text.startsWith("Heading\n\nOne paragraph."))
        assertTrue(snapshot.text.contains("• Docs (https://example.test/docs)"))
        assertTrue(snapshot.text.contains("Key | Value\na | b"))
        assertTrue(snapshot.text.endsWith(code))
    }

    @Test fun hugeSelectionPagesAreBoundedContiguousAndNeverSplitUnicode() {
        val text = "a".repeat(AgentMessageSelection.PAGE_CHARS - 1) + "😄" + "b".repeat(100_000)
        val snapshot = AgentMessageSelection.fromText(text)
        assertEquals(text, snapshot.pages.indices.joinToString("") { snapshot.page(it) })
        assertTrue(snapshot.pages.size > 1)
        snapshot.pages.indices.forEach { page ->
            val value = snapshot.page(page)
            assertTrue(value.length <= AgentMessageSelection.PAGE_CHARS)
            assertFalse(value.first().isLowSurrogate())
            assertFalse(value.last().isHighSurrogate())
        }
        assertEquals("", AgentMessageSelection.fromText("").page(0))
    }

    @Test fun streamingProjectionPreservesCompleteBlocksAndDoesNotDropTheTailOnFinish() {
        val projector = AgentStreamingMarkdownProjector()
        val first = projector.project("First paragraph.\n\nSecond", false)
        val second = projector.project("First paragraph.\n\nSecond paragraph.", false)
        assertEquals(first.stableBlocks, second.stableBlocks)
        assertEquals("Second paragraph.", second.tail)
        val final = projector.project("First paragraph.\n\nSecond paragraph.", true)
        assertEquals(AgentMarkdownParser.parse("First paragraph.\n\nSecond paragraph."), final.stableBlocks)
        assertEquals("", final.tail)
    }
}
