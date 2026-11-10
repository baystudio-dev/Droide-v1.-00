package com.baystudio.droide.ui

import com.baystudio.droide.core.DeclarativeLanguageSpec
import com.baystudio.droide.core.LanguageRegistry
import org.junit.Assert.*
import org.junit.Test

internal fun catalogRunPaths(): List<String> = LanguageRegistry.all.flatMap { language ->
    language.extensions.map { "source folder/Example.$it" } + language.filenames.map { "project/$it" }
}.distinct() + listOf("Dockerfile", "README.mdown", "README.mkd", "build.gradle", "build.gradle.kts",
    "settings.gradle", "settings.gradle.kts", "gradle.properties", "unregistered.file", "no-extension", "")

internal fun expectedCatalogTarget(path: String, android: Boolean): PrimaryRunTarget {
    val name = path.substringAfterLast('/').lowercase(java.util.Locale.ROOT)
    val extension = name.substringAfterLast('.', "")
    if (extension in setOf("html", "htm", "md", "markdown", "mdown", "mkd")) return PrimaryRunTarget.PREVIEW
    if (android && (path.isBlank() || extension in setOf("kt", "java", "xml") ||
        name in setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties")))
        return PrimaryRunTarget.ANDROID_APP
    return PrimaryRunTarget.FILE
}

class PrimaryRunCatalogTest {
    @Test fun everyRegisteredExtensionAndFilenameHasAnExplicitRunDestination() {
        assertTrue(LanguageRegistry.all.size >= 50)
        for (path in catalogRunPaths()) for (android in listOf(false, true)) {
            assertEquals("$path android=$android", expectedCatalogTarget(path, android), primaryRunTarget(path, android))
            println("RUN_CATALOG_CHECK path=$path android=$android target=${primaryRunTarget(path, android)}")
        }
    }

    @Test fun caseAndPathCharactersDoNotRedirectAnyRegisteredLanguage() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            for (path in catalogRunPaths().filter(String::isNotEmpty)) for (android in listOf(false, true)) {
                val upper = "proyek ü/" + path.uppercase(java.util.Locale.ROOT)
                assertEquals(upper, expectedCatalogTarget(upper, android), primaryRunTarget(upper, android))
            }
        } finally { java.util.Locale.setDefault(previous) }
    }

    @Test fun extensionLanguagesUseFileRoutingAndNeverBecomeAndroidByDefault() {
        val id = "test.run-routing"
        try {
            LanguageRegistry.registerDeclarative(id, listOf(DeclarativeLanguageSpec("fixture-language", "Fixture",
                listOf(".customrun", "fixture2"), listOf("ProjectRecipe"))))
            for (path in listOf("test.customrun", "test.fixture2", "ProjectRecipe")) {
                assertEquals("fixture-language", LanguageRegistry.forFile(path)?.id)
                for (android in listOf(false, true)) assertEquals(path, PrimaryRunTarget.FILE, primaryRunTarget(path, android))
                assertNull("Syntax support alone must not invent a runner", LanguageRegistry.runPlanFor(path))
            }
        } finally { LanguageRegistry.unregisterDeclarative(id) }
    }
}
