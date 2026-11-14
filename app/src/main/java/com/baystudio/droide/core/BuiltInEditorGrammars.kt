package com.baystudio.droide.core

import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.Registry

/** Small, immutable upstream syntax data. No guest process, file scan or installer. */
internal object BuiltInEditorGrammars {
    private class Entry(val resource: String) {
        // Cache both success and failure: a broken resource must not be parsed on each edit.
        val grammar: IGrammar? by lazy {
            try {
                Registry().addGrammar(IGrammarSource.fromResource(BuiltInEditorGrammars::class.java, resource))
            } catch (_: Exception) { null }
        }
    }

    private val entries = mapOf(
        "goose" to Entry("/droide/grammars/goose.tmLanguage.json"),
        "batch" to Entry("/droide/grammars/batchfile.tmLanguage.json"),
        "ini" to Entry("/droide/grammars/ini.tmLanguage.json"),
    )

    fun languageIds(): Set<String> = entries.keys

    fun highlight(text: String, languageId: String, maxSpans: Int): List<DroideHighlightSpan>? {
        val entry = entries[languageId] ?: return null
        if (text.length > DroideSyntaxSemanticEngine.MAX_HIGHLIGHT_CHARS || maxSpans <= 0) return emptyList()
        val grammar = entry.grammar ?: return emptyList()
        // No shared document state; serialize access to TM4E's grammar cache. A budget
        // failure returns plain text instead of guessing this language's comment rules.
        return synchronized(grammar) {
            DeclarativeTextMateRegistry.highlightGrammar(grammar, text, maxSpans)
        } ?: emptyList()
    }
}
