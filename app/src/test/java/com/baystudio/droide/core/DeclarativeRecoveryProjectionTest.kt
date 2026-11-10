package com.baystudio.droide.core

import android.app.Application
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeclarativeRecoveryProjectionTest {
    private fun snapshot(root: File): Map<String, String> = root.walkTopDown().associate { file ->
        file.relativeTo(root).path to if (file.isDirectory) "directory" else
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    @Test fun partialRegistrationIsOwnedAndClearPreservesBuiltInsStoreAndNormalRetry() {
        val context = RuntimeEnvironment.getApplication()
        val archive = File.createTempFile("recovery-projection-", ".vsix", context.cacheDir)
        val manifest = """{"name":"recoveryfixture","publisher":"test","version":"1","contributes":{"languages":[{"id":"recoveryfixture","extensions":[".recoveryfixture"],"configuration":"language.json"}],"snippets":[{"language":"recoveryfixture","path":"snippets.json"}],"themes":[{"label":"Recovery Fixture","uiTheme":"vs-dark","path":"theme.json"}]}}"""
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("extension/package.json" to manifest, "extension/language.json" to "{}",
                "extension/snippets.json" to """{"fixture":{"prefix":"rfx","body":"fixture"}}""",
                "extension/theme.json" to """{"colors":{"editor.background":"#101010","editor.foreground":"#eeeeee"}}""").forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
            }
        }
        val store = DeclarativeExtensionStore(context)
        val record = store.install(archive)
        val storeRoot = File(context.filesDir, "declarative-extensions")
        val index = File(storeRoot, "index.json")
        val indexBytes = index.readBytes()
        val builtInLanguage = LanguageRegistry.forExtension("kt")
        val builtInSnippets = ProfessionalSnippets.builtIns("kotlin", "fun")
        val builtInThemes = ThemeManager(context.filesDir).list()
        assertNotNull(builtInLanguage); assertTrue(builtInSnippets.isNotEmpty()); assertTrue(builtInThemes.isNotEmpty())
        try {
            val expected = IllegalStateException("after first real registration")
            val failure = runCatching {
                DeclarativeExtensionRuntime.registerVerifiedProjection(record, store.installRoot(record)) { throw expected }
            }.exceptionOrNull()
            assertSame(expected, failure)
            assertNotNull(LanguageRegistry.forExtension("recoveryfixture"))
            index.writeText("corrupt index must not be read by in-memory clear")
            val beforeClear = snapshot(storeRoot)
            DeclarativeExtensionRuntime.suspendInstalledProjections()
            assertNull(LanguageRegistry.forExtension("recoveryfixture"))
            assertTrue(DeclarativeSnippetRegistry.candidates("recoveryfixture", "rfx").isEmpty())
            assertFalse(DeclarativeTextMateRegistry.hasGrammar("recoveryfixture"))
            assertTrue(DeclarativeThemeRegistry.list().none { it.name.startsWith(record.extensionId + ":") })
            assertTrue(DeclarativeExtensionRuntime.records().isEmpty())
            assertEquals(beforeClear, snapshot(storeRoot))
            assertNotNull(runCatching { DeclarativeExtensionRuntime.uninstall(record.extensionId) }.exceptionOrNull())
            assertEquals(beforeClear, snapshot(storeRoot))
            assertSame(builtInLanguage, LanguageRegistry.forExtension("kt"))
            assertEquals(builtInSnippets, ProfessionalSnippets.builtIns("kotlin", "fun"))
            assertEquals(builtInThemes, ThemeManager(context.filesDir).list())

            index.writeBytes(indexBytes)
            val beforeRetry = snapshot(storeRoot)
            var checks = 0
            DeclarativeExtensionRuntime.initialize(context) { checks++ }
            assertTrue(checks > 0)
            assertNotNull(LanguageRegistry.forExtension("recoveryfixture"))
            assertTrue(DeclarativeSnippetRegistry.candidates("recoveryfixture", "rfx").isNotEmpty())
            assertTrue(DeclarativeThemeRegistry.list().any { it.name.startsWith(record.extensionId + ":") })
            assertEquals(beforeRetry, snapshot(storeRoot))
            DeclarativeExtensionRuntime.suspendInstalledProjections()
            assertNull(LanguageRegistry.forExtension("recoveryfixture"))
            assertTrue(DeclarativeSnippetRegistry.candidates("recoveryfixture", "rfx").isEmpty())
            assertEquals(builtInThemes, ThemeManager(context.filesDir).list())
            assertEquals(beforeRetry, snapshot(storeRoot))
        } finally {
            index.writeBytes(indexBytes)
            DeclarativeExtensionRuntime.initialize(context)
            DeclarativeExtensionRuntime.uninstall(record.extensionId)
            archive.delete()
        }
    }
}
