package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PluginContributionAuthorityTest {
    @Test fun modifiedInstalledPayloadCannotReuseAPreviousContributionList() = fixture { root, store, record ->
        val source = source(store)
        assertEquals(1, AgentPluginContributions.ruleFiles(source, root).size)
        val file = store.installRoot(record).resolve("rules/one.md")
        val original = file.readBytes()
        file.writeText("Modified instructions")
        assertTrue(AgentPluginContributions.ruleFiles(source, root).isEmpty())
        assertEquals("Modified instructions", file.readText())
        file.writeBytes(original)
        assertEquals(1, AgentPluginContributions.ruleFiles(source, root).size)
    }

    @Test fun disablingAStoredPluginRevokesFreshContributionLookups() = fixture { root, store, record ->
        val source = source(store)
        assertEquals(1, AgentPluginContributions.ruleFiles(source, root).size)
        store.setEnabled(record.id, false)
        assertTrue(AgentPluginContributions.ruleFiles(source, root).isEmpty())
        store.setEnabled(record.id, true)
        assertEquals(1, AgentPluginContributions.ruleFiles(source, root).size)
    }

    @Test fun corruptStoreIndexRemainsReportedAndUnmodified() = fixture { root, store, _ ->
        val source = source(store)
        assertEquals(1, AgentPluginContributions.ruleFiles(source, root).size)
        val index = root.resolve("agent-plugins/index.json").apply { writeText("invalid index fixture") }
        val before = index.readBytes()
        assertThrows(IOException::class.java) { AgentPluginContributions.ruleFiles(source, root) }
        assertArrayEquals(before, index.readBytes())
    }

    private fun source(store: AgentPluginStore) = AgentPluginSource {
        store.listVerified().filter { it.enabled }.map { record -> AgentPluginContributionRoot(
            record.id, record.displayName, record.version, record.description, AgentPluginFormat.valueOf(record.format),
            store.installRoot(record), record.contentSha256)
        }
    }
    private fun fixture(block: (File, AgentPluginStore, InstalledAgentPluginRecord) -> Unit) {
        val root = Files.createTempDirectory("droide-plugin-authority-").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        try {
            val zipFile = root.resolve("fixture.zip")
            ZipOutputStream(zipFile.outputStream()).use { zip ->
                for ((path, text) in linkedMapOf("droide-agent-plugin.json" to """{"id":"authority-fixture","name":"Fixture","version":"1"}""",
                    "rules/one.md" to "---\nname: one\n---\nInstructions")) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            val store = AgentPluginStore(context)
            block(root, store, store.install(zipFile))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
