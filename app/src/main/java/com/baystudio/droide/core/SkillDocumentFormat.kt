package com.baystudio.droide.core

/** The supported Agent Skills frontmatter subset. Unknown metadata never grants permissions. */
internal object SkillDocumentFormat {
    const val MAX_BYTES = 200_000
    const val HEADER_BYTES = 8_192
    data class Controls(val userInvocable: Boolean = true, val disableModelInvocation: Boolean = false)

    fun header(markdown: String, maxHeaderBytes: Int = MAX_BYTES): String {
        require(markdown.startsWith("---\n") || markdown.startsWith("---\r\n")) { "SKILL.md must start with YAML frontmatter (---)" }
        val end = Regex("(?m)^---[ \t]*\r?$").find(markdown, 3)
            ?: error("SKILL.md has no closing frontmatter delimiter")
        require(markdown.substring(0, end.range.last + 1).toByteArray(Charsets.UTF_8).size <= maxHeaderBytes) { "Skill frontmatter exceeds its size limit" }
        return markdown.substring(markdown.indexOf('\n') + 1, end.range.first)
    }

    fun body(markdown: String): String {
        header(markdown)
        val end = requireNotNull(Regex("(?m)^---[ \t]*\r?$").find(markdown, 3))
        return markdown.substring(end.range.last + 1).trim()
    }

    fun validate(markdown: String): String {
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(markdown)).remaining()
        require(bytes <= MAX_BYTES && '\u0000' !in markdown) { "Skill must be valid UTF-8 text, at most 200 KB" }
        val header = header(markdown, HEADER_BYTES)
        fun field(name: String): String {
            val matches = Regex("(?im)^$name:[ \t]*(.*)$").findAll(header).toList()
            require(matches.size == 1) { "Skill requires one $name field" }
            return matches.single().groupValues[1].trim().trim('\'', '"')
        }
        val name = field("name")
        require(name.length in 1..64 && Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(name)) {
            "Use a skill name of 1–64 lowercase letters, numbers and single hyphens"
        }
        val declaredDescription = field("description")
        val description = if (declaredDescription.startsWith('|') || declaredDescription.startsWith('>'))
            SkillYamlDescription.blockValue(header + "\n").orEmpty().trim() else declaredDescription
        require(description.isNotBlank() && description.length <= 1_024) { "Enter a description of at most 1,024 characters" }
        require(body(markdown).isNotBlank()) { "Skill instructions cannot be empty" }
        controls(markdown, strict = true)
        return name
    }

    fun controls(markdown: String, strict: Boolean = false): Controls = try {
        val header = header(markdown)
        fun flag(name: String, default: Boolean): Boolean {
            val matches = Regex("(?im)^$name:[ \t]*(.*)$").findAll(header).toList()
            if (matches.isEmpty()) return default
            require(matches.size == 1) { "Duplicate $name field" }
            val value = matches.single().groupValues[1].substringBefore(" #").trim()
            require(value == "true" || value == "false") { "$name must be true or false" }
            return value == "true"
        }
        Controls(flag("user-invocable", true), flag("disable-model-invocation", false))
    } catch (error: IllegalArgumentException) {
        if (strict) throw error else Controls(userInvocable = false, disableModelInvocation = true)
    } catch (error: IllegalStateException) {
        if (strict) throw error else Controls(userInvocable = false, disableModelInvocation = true)
    }
}
