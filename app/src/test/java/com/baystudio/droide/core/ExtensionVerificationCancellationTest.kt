package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
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
class ExtensionVerificationCancellationTest {
    private fun fixture(block: (Context, File) -> Unit) {
        val root = Files.createTempDirectory("extension-verification-").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        try { block(context, root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun archive(root: File, entries: Map<String, String>): File = File(root, "fixture.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, body) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
        } }
    }

    private fun snapshot(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate { file ->
        file.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    private fun <T> verifyCancellation(root: File, cancelAt: Int? = null, list: (() -> Unit) -> List<T>) {
        var fullChecks = 0
        val expected = list { fullChecks++ }
        assertEquals(1, expected.size)
        assertTrue("Multi-chunk verification must check cancellation repeatedly", fullChecks > 10)
        val before = snapshot(root)
        val cancelled = CancellationException("deterministic verification cancellation")
        val stopAt = cancelAt ?: (fullChecks / 2)
        var cancelledChecks = 0
        val failure = runCatching { list { if (++cancelledChecks == stopAt) throw cancelled } }.exceptionOrNull()
        assertSame("Cancellation must escape invalid-record filtering", cancelled, failure)
        assertEquals(stopAt, cancelledChecks)
        assertEquals(before, snapshot(root))
        var retryChecks = 0
        assertEquals(expected, list { retryChecks++ })
        assertEquals("Retry must perform the full verification again", fullChecks, retryChecks)
        assertEquals(before, snapshot(root))
    }

    @Test fun declarativeResourceCancellationPreservesStoreAndFreshRetry() = fixture { context, root ->
        val manifest = """{"name":"cancel","publisher":"test","version":"1","contributes":{"languages":[{"id":"cancel-fixture","extensions":[".cancel-fixture"],"configuration":"language.json"}]}}"""
        val store = DeclarativeExtensionStore(context)
        store.install(archive(root, linkedMapOf("extension/package.json" to manifest,
            "extension/language.json" to (" ".repeat(128 * 1024) + "{}"))))
        verifyCancellation(root) { check -> store.listVerified(check) }
    }

    @Test fun declarativeCancellationInsideSecondResourceChunkPreservesBytes() = fixture { context, root ->
        val manifest = """{"name":"chunk","publisher":"test","version":"1","contributes":{"languages":[{"id":"chunk-fixture","extensions":[".chunk-fixture"],"configuration":"language.json"}]}}"""
        val store = DeclarativeExtensionStore(context)
        val record = store.install(archive(root, linkedMapOf("extension/package.json" to manifest,
            "extension/language.json" to (" ".repeat(128 * 1024) + "{}"))))
        val resources = record.resourceSha256.keys.toList()
        val resourceIndex = resources.indexOfFirst { it.endsWith("language.json") }
        assertTrue(resourceIndex >= 0)
        val installRoot = store.installRoot(record)
        assertTrue(File(installRoot, resources[resourceIndex]).length() > 128 * 1024)

        val beforeResource = 3 + resources.take(resourceIndex).sumOf { relative ->
            4 + ((File(installRoot, relative).length() + 65_535L) / 65_536L).toInt()
        }
        // Resource check, hash entry and first read have passed. Cancel immediately before the second 64
        // KiB read, rather than an arbitrary midpoint.
        verifyCancellation(root, cancelAt = beforeResource + 4) { check -> store.listVerified(check) }
    }

    @Test fun agentResourceCancellationPreservesStoreAndFreshRetry() = fixture { context, root ->
        val store = AgentPluginStore(context)
        store.install(archive(root, linkedMapOf("droide-agent-plugin.json" to """{"id":"cancel-fixture","version":"1","name":"Fixture"}""",
            "SKILL.md" to ("# Fixture\n" + "x".repeat(128 * 1024)))))
        verifyCancellation(root) { check -> store.listVerified(check) }
    }
}
