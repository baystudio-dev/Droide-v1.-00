package com.baystudio.droide.core

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Keep serialized names stable for backward compatibility.

@Serializable
data class DroideTheme(
    val name: String,
    @SerialName("dark") val background: String = "#0D1117",
    @SerialName("light") val foreground: String = "#E6EDF3",

    val colors: Map<String, String> = emptyMap(),
)

data class DroideUiPalette(
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val error: Color,
    val success: Color,
    val warning: Color,
    val purple: Color,
    val status: Color,
    val statusText: Color,
)

data class DroideTerminalPalette(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val ansi: List<Int>,
)

data class DroideThemeSnapshot(
    val name: String,
    val fingerprint: String,
    val isDark: Boolean,
    val colorScheme: ColorScheme,
    val ui: DroideUiPalette,
    val terminal: DroideTerminalPalette,
    val editor: DroideEditorPalette,
)

class ThemeManager(private val workDir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val selectionPersistence = ThemeSelectionPersistence(workDir)
    private val customDir = PathSecurity.resolveWithin(workDir, ".droide/themes")
    private val builtins = listOf(
        DroideTheme(
            name = "droide-dark",
            background = "#0D1117",
            foreground = "#E6EDF3",
            colors = mapOf(
                "sideBar.background" to "#161B22",
                "panel.background" to "#161B22",
                "activityBar.background" to "#0D1117",
                "statusBar.background" to "#0B6BBB",
                "panel.border" to "#30363D",
                "descriptionForeground" to "#8B949E",
                "focusBorder" to "#58A6FF",

                "editor.background" to "#17181D",
                "editor.foreground" to "#E6EDF3",
                "editorLineNumber.foreground" to "#8B949E",
                "editorLineNumber.activeForeground" to "#E6EDF3",
                "editor.selectionBackground" to "#264F78",
                "editor.lineHighlightBackground" to "#1E1F26",
                "editorIndentGuide.background1" to "#404040",
                "editorIndentGuide.activeBackground1" to "#707070",
            ),
        ),
        DroideTheme(
            name = "droide-light",
            background = "#F6F8FA",
            foreground = "#24292F",
            colors = mapOf(
                "sideBar.background" to "#FFFFFF",
                "panel.background" to "#FFFFFF",
                "activityBar.background" to "#F6F8FA",
                "statusBar.background" to "#0969DA",
                "panel.border" to "#D0D7DE",
                "descriptionForeground" to "#57606A",
                "focusBorder" to "#0969DA",
            ),
        ),
        DroideTheme("matrix", "#001100", "#00FF00", mapOf("focusBorder" to "#00FF00")),
        DroideTheme("tokyonight", "#1A1B26", "#C0CAF5", mapOf("focusBorder" to "#7AA2F7", "descriptionForeground" to "#9AA5CE")),
    )
    private var current = builtins.first()

    fun list(): List<DroideTheme> = (builtins + DeclarativeThemeRegistry.list() + loadCustom())
        .filter { theme ->
            theme.name.isNotBlank() && theme.name.length <= 80 &&
                validColor(theme.background) && validColor(theme.foreground) &&
                theme.colors.size <= 96 && theme.colors.all { (key, value) -> key.length <= 96 && validColor(value) }
        }
        .distinctBy { it.name }

    fun current(): DroideTheme {
        loadPersisted()
        return current
    }

    fun select(name: String) {
        val selected = list().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("The selected theme is no longer available")
        selectionPersistence.write(selected.name)
        current = selected
    }

    fun snapshot(): DroideThemeSnapshot = buildSnapshot(current())

    // Compatibility shim for old callers.
    @Suppress("UNUSED_PARAMETER")
    fun toColorScheme(isDark: Boolean): ColorScheme = snapshot().colorScheme

    private fun loadPersisted() {
        runCatching {
            val name = selectionPersistence.read() ?: return@runCatching
            list().firstOrNull { it.name == name }?.let { current = it }
        }
    }

    private fun buildSnapshot(theme: DroideTheme): DroideThemeSnapshot {
        val math = ThemeContrastPolicy

        val background = math.opaque(parseColor(theme.background))
        val requestedForeground = math.opaque(parseColor(theme.foreground))
        val foreground = math.ensureTextContrast(background, requestedForeground, 4.5)
        val dark = math.luminance(background) < 0.42
        val editorBackground = math.opaque(parseColor(theme.color("editor.background") ?: theme.background))
        val requestedEditorForeground = math.opaque(parseColor(theme.color("editor.foreground") ?: theme.foreground))
        val editorForeground = math.ensureTextContrast(editorBackground, requestedEditorForeground, 4.5)

        fun optional(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key -> theme.color(key)?.let(::parseColor) }
        fun colorOr(fallback: Int, vararg keys: String): Int = optional(*keys) ?: fallback

        val surface = math.opaque(colorOr(math.mix(background, foreground, if (dark) 0.055 else 0.025), "sideBar.background", "panel.background"))
        val surface2 = math.opaque(colorOr(math.mix(background, foreground, if (dark) 0.085 else 0.05), "editorWidget.background", "panel.background"))
        val surface3 = math.opaque(colorOr(math.mix(background, foreground, if (dark) 0.12 else 0.085), "activityBar.background"))
        val border = math.opaque(colorOr(math.mix(background, foreground, if (dark) 0.18 else 0.18), "panel.border", "sideBar.border"))
        val borderStrong = math.mix(background, foreground, if (dark) 0.25 else 0.28)
        val mutedRequested = math.opaque(colorOr(math.mix(foreground, background, 0.43), "descriptionForeground"))
        val muted = math.ensureTextContrast(background, mutedRequested, 3.0)
        val primaryRequested = math.opaque(colorOr(if (dark) 0xFF58A6FF.toInt() else 0xFF0969DA.toInt(), "focusBorder", "textLink.foreground", "button.background"))
        val primary = math.ensureAccentVisibility(background, primaryRequested)
        val error = math.opaque(colorOr(if (dark) 0xFFF85149.toInt() else 0xFFCF222E.toInt(), "errorForeground"))
        val success = math.opaque(colorOr(if (dark) 0xFF3FB950.toInt() else 0xFF1A7F37.toInt(), "testing.iconPassed"))
        val warning = math.opaque(colorOr(if (dark) 0xFFD29922.toInt() else 0xFF9A6700.toInt(), "editorWarning.foreground"))
        val purple = math.opaque(colorOr(if (dark) 0xFFBC8CFF.toInt() else 0xFF8250DF.toInt(), "symbolIcon.classForeground"))
        val status = math.opaque(colorOr(primary, "statusBar.background"))

        val ui = DroideUiPalette(
            background = Color(background),
            surface = Color(surface),
            surface2 = Color(surface2),
            surface3 = Color(surface3),
            border = Color(border),
            borderStrong = Color(borderStrong),
            text = Color(foreground),
            muted = Color(muted),
            primary = Color(primary),
            error = Color(error),
            success = Color(success),
            warning = Color(warning),
            purple = Color(purple),
            status = Color(status),
            statusText = Color(math.bestMonochromeForeground(status)),
        )

        val onPrimary = Color(math.bestMonochromeForeground(primary))
        val colorScheme = if (dark) {
            darkColorScheme(
                primary = ui.primary,
                onPrimary = onPrimary,
                primaryContainer = Color(math.mix(background, primary, 0.24)),
                onPrimaryContainer = ui.text,
                secondary = ui.purple,
                onSecondary = Color(math.bestMonochromeForeground(purple)),
                tertiary = ui.warning,
                onTertiary = Color(math.bestMonochromeForeground(warning)),
                background = ui.background,
                onBackground = ui.text,
                surface = ui.surface,
                onSurface = Color(math.ensureTextContrast(surface, foreground, 4.5)),
                surfaceVariant = ui.surface2,
                onSurfaceVariant = Color(math.ensureTextContrast(surface2, muted, 3.0)),
                outline = ui.border,
                outlineVariant = ui.borderStrong,
                error = ui.error,
                onError = Color(math.bestMonochromeForeground(error)),
            )
        } else {
            lightColorScheme(
                primary = ui.primary,
                onPrimary = onPrimary,
                primaryContainer = Color(math.mix(background, primary, 0.14)),
                onPrimaryContainer = Color(math.ensureTextContrast(math.mix(background, primary, 0.14), foreground, 4.5)),
                secondary = ui.purple,
                onSecondary = Color(math.bestMonochromeForeground(purple)),
                tertiary = ui.warning,
                onTertiary = Color(math.bestMonochromeForeground(warning)),
                background = ui.background,
                onBackground = ui.text,
                surface = ui.surface,
                onSurface = Color(math.ensureTextContrast(surface, foreground, 4.5)),
                surfaceVariant = ui.surface2,
                onSurfaceVariant = Color(math.ensureTextContrast(surface2, muted, 3.0)),
                outline = ui.border,
                outlineVariant = ui.borderStrong,
                error = ui.error,
                onError = Color(math.bestMonochromeForeground(error)),
            )
        }

        val terminalBackground = math.opaque(colorOr(background, "terminal.background"))
        val terminalForeground = math.ensureTextContrast(terminalBackground, colorOr(foreground, "terminal.foreground"), 4.5)
        val terminalCursor = math.ensureAccentVisibility(terminalBackground, colorOr(terminalForeground, "terminalCursor.foreground"))
        val ansiKeys = listOf(
            "terminal.ansiBlack", "terminal.ansiRed", "terminal.ansiGreen", "terminal.ansiYellow",
            "terminal.ansiBlue", "terminal.ansiMagenta", "terminal.ansiCyan", "terminal.ansiWhite",
            "terminal.ansiBrightBlack", "terminal.ansiBrightRed", "terminal.ansiBrightGreen", "terminal.ansiBrightYellow",
            "terminal.ansiBrightBlue", "terminal.ansiBrightMagenta", "terminal.ansiBrightCyan", "terminal.ansiBrightWhite",
        )
        val ansiDefaults = if (dark) DARK_ANSI else LIGHT_ANSI
        val ansi = ansiKeys.mapIndexed { index, key -> colorOr(ansiDefaults[index], key) }
        val terminal = DroideTerminalPalette(terminalBackground, terminalForeground, terminalCursor, ansi)

        val editor = EditorCodePalette.create(editorBackground, editorForeground, primary) { key -> optional(key) }

        val fingerprint = buildString {
            append(theme.name).append('|').append(dark).append('|')
            append(theme.background).append('|').append(theme.foreground)
            theme.colors.toSortedMap().forEach { (k, v) -> append('|').append(k).append('=').append(v) }
        }.hashCode().toUInt().toString(16)

        return DroideThemeSnapshot(theme.name, fingerprint, dark, colorScheme, ui, terminal, editor)
    }

    private fun loadCustom(): List<DroideTheme> {
        if (!customDir.isDirectory) return emptyList()
        return customDir.listFiles()?.asSequence()
            ?.filter { it.isFile && PathSecurity.contains(workDir, it) && it.extension.equals("json", true) && it.length() <= 32_000 }
            ?.take(100)
            ?.mapNotNull { f -> runCatching { json.decodeFromString<DroideTheme>(f.readText()) }.getOrNull() }
            ?.toList() ?: emptyList()
    }

    private fun DroideTheme.color(key: String): String? = colors[key]?.takeIf(::validColor)

    private fun validColor(value: String): Boolean = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$").matches(value)

    private fun parseColor(value: String): Int = android.graphics.Color.parseColor(value)

    private companion object {
        val DARK_ANSI = listOf(
            0xFF484F58.toInt(), 0xFFFF7B72.toInt(), 0xFF3FB950.toInt(), 0xFFD29922.toInt(),
            0xFF58A6FF.toInt(), 0xFFBC8CFF.toInt(), 0xFF39C5CF.toInt(), 0xFFB1BAC4.toInt(),
            0xFF6E7681.toInt(), 0xFFFFA198.toInt(), 0xFF56D364.toInt(), 0xFFE3B341.toInt(),
            0xFF79C0FF.toInt(), 0xFFD2A8FF.toInt(), 0xFF56D4DD.toInt(), 0xFFFFFFFF.toInt(),
        )
        val LIGHT_ANSI = listOf(
            0xFF24292F.toInt(), 0xFFCF222E.toInt(), 0xFF1A7F37.toInt(), 0xFF9A6700.toInt(),
            0xFF0969DA.toInt(), 0xFF8250DF.toInt(), 0xFF1B7C83.toInt(), 0xFF57606A.toInt(),
            0xFF6E7781.toInt(), 0xFFA40E26.toInt(), 0xFF116329.toInt(), 0xFF7D4E00.toInt(),
            0xFF0550AE.toInt(), 0xFF6639BA.toInt(), 0xFF0A737D.toInt(), 0xFF24292F.toInt(),
        )
    }
}
