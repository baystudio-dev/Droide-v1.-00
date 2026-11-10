package com.baystudio.droide.core

import android.app.Application
import java.io.File
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
class DeclarativeRegistryPublicationBaselineTest {
    @Test fun failedVerificationMustNotUnregisterPreviouslyVerifiedLanguages() {
        val context = RuntimeEnvironment.getApplication()
        val archive = File.createTempFile("registry-publication-", ".vsix", context.cacheDir)
        val manifest = """{"name":"b1fixture","publisher":"test","version":"1","contributes":{"languages":[{"id":"b1fixture","extensions":[".b1fixture"]}]}}"""
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("extension/package.json")); it.write(manifest.toByteArray()); it.closeEntry()
        }
        val store = DeclarativeExtensionStore(context)
        val record = store.install(archive)
        val index = File(context.filesDir, "declarative-extensions/index.json")
        val original = index.readBytes()
        try {
            DeclarativeExtensionRuntime.initialize(context)
            assertEquals("b1fixture", LanguageRegistry.forExtension("b1fixture")?.id)
            index.writeText("invalid index fixture")
            assertNotNull(runCatching { DeclarativeExtensionRuntime.initialize(context) }.exceptionOrNull())
            assertEquals("A rejected candidate must leave the previous projection intact",
                "b1fixture", LanguageRegistry.forExtension("b1fixture")?.id)
            assertTrue(store.installRoot(record).isDirectory)
            assertEquals("invalid index fixture", index.readText())
        } finally {
            index.writeBytes(original)
            DeclarativeExtensionRuntime.initialize(context)
            DeclarativeExtensionRuntime.uninstall(record.extensionId)
            archive.delete()
        }
    }
}
