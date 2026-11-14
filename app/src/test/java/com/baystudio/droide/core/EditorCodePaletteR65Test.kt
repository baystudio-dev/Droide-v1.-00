package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class EditorCodePaletteR65Test {
    private val bg = 0xFF17181D.toInt()
    private val fg = 0xFFE6EDF3.toInt()
    private val primary = 0xFF58A6FF.toInt()
    private fun palette(background: Int = bg, foreground: Int = fg, overrides: Map<String, Int> = emptyMap()) =
        EditorCodePalette.create(background, foreground, primary, overrides::get)
    private fun readable(p: DroideEditorPalette, background: Int = p.background) {
        listOf(p.keyword, p.comment, p.literal, p.operator, p.function, p.type, p.variable).forEach {
            assertTrue("${it.toUInt().toString(16)} against ${background.toUInt().toString(16)}", ThemeContrastPolicy.contrast(background, it) >= 4.5)
        }
    }
    @Test fun darkSurfaceAndForegroundAreNotReplaced() { val p = palette(); assertEquals(bg, p.background); assertEquals(fg, p.foreground); assertTrue(p.isDark) }
    @Test fun darkKeywordsUseScreenshotInspiredPink() { assertEquals(0xFFF44761.toInt(), palette().keyword) }
    @Test fun darkEntitiesUsePurple() { val p = palette(); assertEquals(0xFFC79BF2.toInt(), p.function); assertEquals(p.function, p.type) }
    @Test fun darkLiteralsUseBlue() { assertEquals(0xFF79C0FF.toInt(), palette().literal) }
    @Test fun normalIdentifiersUseActualForeground() { assertEquals(fg, palette().variable) }
    @Test fun operatorsFollowKeywordRole() { assertEquals(palette().keyword, palette().operator) }
    @Test fun commentsAndDefaultGutterAreReadableGray() { val p = palette(); assertEquals(0xFF8B949E.toInt(), p.comment); assertEquals(p.comment, p.lineNumber); assertTrue(ThemeContrastPolicy.contrast(bg, p.comment) >= 4.5) }
    @Test fun darkAllDefaultTextRolesMeetContrast() { readable(palette()) }
    @Test fun darkCurrentLineTextRolesMeetContrast() { readable(palette(), 0xFF1E1F26.toInt()) }
    @Test fun lightRetainsLightBranch() { val p = palette(0xFFF6F8FA.toInt(), 0xFF24292F.toInt()); assertFalse(p.isDark); assertEquals(0xFFCF222E.toInt(), p.keyword); assertEquals(0xFF0A3069.toInt(), p.literal); readable(p) }
    @Test fun customDarkSurfaceDoesNotBecomeReferenceSurface() { val p = palette(0xFF001122.toInt()); assertEquals(0xFF001122.toInt(), p.background); readable(p) }
    @Test fun customLightSurfaceDoesNotBecomeDark() { val p = palette(0xFFFFF0D0.toInt(), 0xFF17181D.toInt()); assertFalse(p.isDark); assertEquals(0xFFFFF0D0.toInt(), p.background); readable(p) }
    @Test fun customForegroundRemainsAuthoritativeForIdentifiers() { val p = palette(foreground = 0xFFFFFFCC.toInt()); assertEquals(0xFFFFFFCC.toInt(), p.foreground); assertEquals(p.foreground, p.variable) }
    @Test fun midtoneSurfaceCorrectsUnusableDefaults() { val background = 0xFF777777.toInt(); val p = palette(background, ThemeContrastPolicy.bestMonochromeForeground(background)); readable(p); assertNotEquals(0xFFF44761.toInt(), p.keyword) }
    @Test fun sweepBackgroundsNeverProducesLowContrastDefaultSyntax() { for (r in 0..255 step 51) for (g in 0..255 step 51) for (b in 0..255 step 51) { val c = (0xFF shl 24) or (r shl 16) or (g shl 8) or b; readable(palette(c, ThemeContrastPolicy.bestMonochromeForeground(c))) } }
    @Test fun explicitGutterColorsArePreservedEvenWhenUserChoosesLowContrast() { val p = palette(overrides = mapOf("editorLineNumber.foreground" to bg, "editorLineNumber.activeForeground" to 0xFFBBCCDD.toInt())); assertEquals(bg, p.lineNumber); assertEquals(0xFFBBCCDD.toInt(), p.activeLineNumber) }
    @Test fun explicitSelectionAndCurrentLineRemainExact() { val p = palette(overrides = mapOf("editor.selectionBackground" to 0x12345678, "editor.lineHighlightBackground" to 0x23456789)); assertEquals(0x12345678, p.selection); assertEquals(0x23456789, p.currentLine) }
    @Test fun explicitGuidePrecedenceRemainsNumberedThenLegacy() { val p = palette(overrides = mapOf("editorIndentGuide.background1" to 1, "editorIndentGuide.background" to 2, "editorIndentGuide.activeBackground1" to 3, "editorIndentGuide.activeBackground" to 4)); assertEquals(1, p.indentGuide); assertEquals(3, p.activeIndentGuide) }
    @Test fun legacyGuideOverrideStillWorks() { val p = palette(overrides = mapOf("editorIndentGuide.background" to 7, "editorIndentGuide.activeBackground" to 9)); assertEquals(7, p.indentGuide); assertEquals(9, p.activeIndentGuide) }
    @Test fun paletteDoesNotReadOrInventSyntaxOverrideKeys() { val keys = mutableListOf<String>(); EditorCodePalette.create(bg, fg, primary) { keys += it; null }; assertEquals(setOf("editorLineNumber.foreground", "editorLineNumber.activeForeground", "editor.selectionBackground", "editor.lineHighlightBackground", "editorIndentGuide.background1", "editorIndentGuide.background", "editorIndentGuide.activeBackground1", "editorIndentGuide.activeBackground"), keys.toSet()) }
    @Test fun repeatedProjectionIsDeterministic() { assertEquals(palette(), palette()); assertEquals(palette(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), palette(0xFFFFFFFF.toInt(), 0xFF000000.toInt())) }
    @Test fun uiAccentOnlyAffectsFallbackSelection() { val p = palette(); val other = EditorCodePalette.create(bg, fg, 0xFF00FF00.toInt()); assertEquals(p.copy(selection = other.selection), other) }
}
