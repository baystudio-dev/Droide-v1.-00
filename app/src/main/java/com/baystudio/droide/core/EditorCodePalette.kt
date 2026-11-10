package com.baystudio.droide.core

data class DroideEditorPalette(
    val isDark: Boolean,
    val background: Int,
    val foreground: Int,
    val lineNumber: Int,
    val activeLineNumber: Int,
    val selection: Int,
    val currentLine: Int,
    val indentGuide: Int,
    val activeIndentGuide: Int,
    val keyword: Int,
    val comment: Int,
    val literal: Int,
    val operator: Int,
    val function: Int,
    val type: Int,
    val variable: Int,
)

/** Editor surface colors only. Global UI/terminal colors and language tokenization are independent. */
internal object EditorCodePalette {
    fun create(
        background: Int,
        foreground: Int,
        primary: Int,
        explicitColor: (String) -> Int? = { null },
    ): DroideEditorPalette {
        val math = ThemeContrastPolicy
        val dark = math.luminance(background) < 0.42
        fun explicitOr(fallback: Int, vararg keys: String): Int =
            keys.firstNotNullOfOrNull(explicitColor) ?: fallback
        // These are screenshot-inspired defaults, not a claim about GitHub Android's native tokens.
        // Correct defaults against the actual editor surface; retain existing explicit theme overrides.
        fun readable(darkColor: Int, lightColor: Int): Int =
            math.ensureTextContrast(background, if (dark) darkColor else lightColor, 4.5)
        val comment = readable(0xFF8B949E.toInt(), 0xFF57606A.toInt())
        val keyword = readable(0xFFF44761.toInt(), 0xFFCF222E.toInt())
        val entity = readable(0xFFC79BF2.toInt(), 0xFF8250DF.toInt())
        return DroideEditorPalette(
            isDark = dark,
            background = background,
            foreground = foreground,
            lineNumber = explicitOr(comment, "editorLineNumber.foreground"),
            activeLineNumber = explicitOr(foreground, "editorLineNumber.activeForeground"),
            selection = explicitOr(math.withAlpha(primary, if (dark) 0x66 else 0x44), "editor.selectionBackground"),
            currentLine = explicitOr(math.withAlpha(foreground, if (dark) 0x0D else 0x0A), "editor.lineHighlightBackground"),
            indentGuide = explicitOr(math.mix(background, foreground, if (dark) 0.17 else 0.14), "editorIndentGuide.background1", "editorIndentGuide.background"),
            activeIndentGuide = explicitOr(math.mix(background, foreground, if (dark) 0.36 else 0.30), "editorIndentGuide.activeBackground1", "editorIndentGuide.activeBackground"),
            keyword = keyword,
            comment = comment,
            literal = readable(0xFF79C0FF.toInt(), 0xFF0A3069.toInt()),
            operator = keyword,
            function = entity,
            type = entity,
            variable = foreground,
        )
    }
}
