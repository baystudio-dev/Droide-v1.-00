package com.baystudio.droide.core

import android.app.Application
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeclarativeRegistryCancellationTest {
    @Test fun cancelledReloadDoesNotPublishOrRemoveCurrentProjectionAndRetryReverifies() {
        val context = RuntimeEnvironment.getApplication()
        val archive = File.createTempFile("registry-cancellation-", ".vsix", context.cacheDir)
        val manifest = """{"name":"b1cancel","publisher":"test","version":"1","contributes":{"languages":[{"id":"b1cancel","extensions":[".b1cancel"],"configuration":"language.json"}]}}"""
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("extension/package.json" to manifest, "extension/language.json" to (" ".repeat(128 * 1024) + "{}")).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
            }
        }
        val store = DeclarativeExtensionStore(context)
        val record = store.install(archive)
        try {
            var fullChecks = 0
            DeclarativeExtensionRuntime.initialize(context) { fullChecks++ }
            val previous = DeclarativeExtensionRuntime.records()
            assertTrue(fullChecks > 10)
            assertEquals("b1cancel", LanguageRegistry.forExtension("b1cancel")?.id)
            val cancellation = CancellationException("cancel before registry publication")
            var checks = 0
            val failure = runCatching {
                DeclarativeExtensionRuntime.initialize(context) { if (++checks == fullChecks / 2) throw cancellation }
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertEquals(previous, DeclarativeExtensionRuntime.records())
            assertEquals("b1cancel", LanguageRegistry.forExtension("b1cancel")?.id)
            assertTrue(store.installRoot(record).isDirectory)
            // The last callback is reloadLocked's check immediately before any unregister.
            val publicationCancellation = CancellationException("cancel at final publication boundary")
            var publicationChecks = 0
            val publicationFailure = runCatching {
                DeclarativeExtensionRuntime.initialize(context) {
                    if (++publicationChecks == fullChecks) throw publicationCancellation
                }
            }.exceptionOrNull()
            assertSame(publicationCancellation, publicationFailure)
            assertEquals(fullChecks, publicationChecks)
            assertEquals(previous, DeclarativeExtensionRuntime.records())
            assertEquals("b1cancel", LanguageRegistry.forExtension("b1cancel")?.id)
            assertTrue(store.installRoot(record).isDirectory)
            var retryChecks = 0
            DeclarativeExtensionRuntime.initialize(context) { retryChecks++ }
            assertEquals(fullChecks, retryChecks)
        } finally {
            DeclarativeExtensionRuntime.uninstall(record.extensionId)
            archive.delete()
        }
    }
}
