package com.baystudio.droide.ui

import com.baystudio.droide.core.AgentMentionTarget

internal enum class AgentComposerCommand(val token: String, val description: String) {
    SKILLS("/skills", "Manage skills"),
    MCP("/mcp", "Manage MCP servers"),
    PLUGINS("/plugins", "Manage installed plugins"),
    COMPACT("/compact", "Compact this conversation"),
    RESULTS("/results", "Open this session's files and media"),
    WORKFLOWS("/workflows", "Choose a reusable task plan"),
    RESUME("/resume", "Review and continue an unfinished task"),
}

internal data class AgentComposerQuery(val start: Int, val end: Int, val token: String) {
    val isMention: Boolean get() = token.startsWith('@')
}

internal data class AgentComposerChoice(val token: String, val title: String, val detail: String, val command: Boolean = false)

internal object AgentComposerShortcuts {
    fun query(text: String, caret: Int, selectionCollapsed: Boolean = true): AgentComposerQuery? {
        if (!selectionCollapsed || caret !in 1..text.length) return null
        val prefix = text.substring(0, caret)
        // Do not offer feature actions in code, email addresses or path fragments.
        if (prefix.split("```").size % 2 == 0 || prefix.substringAfterLast('\n').count { it == '`' } % 2 != 0) return null
        val start = prefix.indexOfLast { it.isWhitespace() } + 1
        val token = prefix.substring(start)
        if (token.length > 160 || token.firstOrNull() !in listOf('/', '@')) return null
        if (token.drop(1).any { !it.isLetterOrDigit() && it !in "._-/:" }) return null
        if (token.startsWith('/') && (start != 0 || caret != text.length)) return null
        // Editing the middle of an existing identifier must not leave a duplicated suffix.
        if (caret < text.length && !text[caret].isWhitespace()) return null
        return AgentComposerQuery(start, caret, token)
    }

    fun choices(query: AgentComposerQuery, commands: List<AgentComposerCommand>, mentions: List<AgentMentionTarget>): List<AgentComposerChoice> {
        val candidates = if (query.isMention) mentions.map { AgentComposerChoice(it.token, it.title, it.detail) }
        else commands.map { AgentComposerChoice(it.token, it.token, it.description, command = true) } +
            mentions.filter { it.token.startsWith("@skill/") }.map { AgentComposerChoice(it.token.replaceFirst('@', '/'), it.title, it.detail) }
        val filter = query.token.drop(1)
        return candidates.filter { it.token.drop(1).contains(filter, true) || it.title.contains(filter, true) }.take(8)
    }

    fun command(
        text: String,
        hasAttachments: Boolean,
        commands: List<AgentComposerCommand> = AgentComposerCommand.entries,
    ): AgentComposerCommand? = if (hasAttachments) null else commands.firstOrNull { it.token == text.trim() }

    fun insert(text: String, query: AgentComposerQuery, choice: AgentComposerChoice): Pair<String, Int> {
        require(query.start in 0..query.end && query.end <= text.length && text.substring(query.start, query.end) == query.token) {
            "The draft changed. Select the suggestion again."
        }
        val suffix = if (query.end == text.length || !text[query.end].isWhitespace()) " " else ""
        val replacement = choice.token + suffix
        return text.replaceRange(query.start, query.end, replacement) to (query.start + replacement.length)
    }
}
