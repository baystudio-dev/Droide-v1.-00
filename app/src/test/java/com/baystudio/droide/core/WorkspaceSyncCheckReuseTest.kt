package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.system.measureNanoTime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSyncCheckReuseTest {
    private fun fixture(block: suspend (File, File, File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("workspace-reuse-").toFile()
        try { block(root, File(root, "source").apply { mkdirs() }, File(root, "mirror")) }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun warmRefreshAndInventoryReadZeroPayloadBytesWithoutRewritingMetadata() = fixture { root, source, copy ->
        val asset = File(source, "model.glb")
        asset.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024) { it.toByte() }
            repeat(512) { out.write(buffer) } // Actual 32 MiB file, not a sparse/count-only fixture.
        }
        var optimizedBytes = 0L
        val mirror = LocalAgentWorkspaceMirror(source, copy, WorkspaceFileFingerprintCache(
            onHashRead = { _, count -> optimizedBytes += count }))
        mirror.refresh()
        File(copy, ".droide-sync-manifest.tsv").writeText(WorkspaceSyncManifest.serialize(mirror.baseline()))
        // Test setup only: let actual ctime move out of the conservative recent-write window. Production
        // never sleeps/waits for this; it simply hashes recent files.
        delay(3_100)
        mirror.refresh()
        val expected = mirror.inventory()
        val indexes = listOf(File(root, ".mirror-sync-baseline.tsv"), File(copy, ".droide-sync-manifest.tsv"))
        val identities = indexes.map { Files.readAttributes(it.toPath(), BasicFileAttributes::class.java) }
        var controlBytes = 0L
        val control = LocalAgentWorkspaceMirror(source, copy, WorkspaceFileFingerprintCache(maxEntries = 0,
            onHashRead = { _, count -> controlBytes += count }))
        val controlNanos = measureNanoTime {
            repeat(3) { control.refresh(); assertEquals(expected, control.inventory()) }
        }
        optimizedBytes = 0
        val optimizedNanos = measureNanoTime {
            repeat(3) { mirror.refresh(); assertEquals(expected, mirror.inventory()) }
        }
        assertEquals(9 * asset.length(), controlBytes)
        assertEquals(0L, optimizedBytes)
        indexes.forEachIndexed { i, file ->
            val current = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            assertEquals(identities[i].fileKey(), current.fileKey())
            assertEquals(identities[i].lastModifiedTime(), current.lastModifiedTime())
        }
        println("R116_SYNC_METRICS {\"file_bytes\":${asset.length()},\"cycles\":3," +
            "\"uncached_hash_bytes\":$controlBytes,\"warm_hash_bytes\":$optimizedBytes," +
            "\"uncached_ms\":${controlNanos / 1_000_000.0},\"warm_ms\":${optimizedNanos / 1_000_000.0}," +
            "\"unchanged_metadata_rewrites\":0,\"scope\":\"host_real_clock_warm_inventory\"}")
    }

    @Test fun changedFileAloneIsRehashedAndPendingCliChangeStillSurvives() = fixture { _, source, copy ->
        File(source, "one.bin").writeText("source-one")
        File(source, "two.bin").writeText("source-two")
        val readPaths = mutableSetOf<String>()
        val mirror = LocalAgentWorkspaceMirror(source, copy, WorkspaceFileFingerprintCache(
            nowMillis = { System.currentTimeMillis() + 30_000 },
            onHashRead = { path, _ -> readPaths += path.toString() }))
        mirror.refresh(); mirror.refresh(); mirror.inventory()
        val oldBase = mirror.baseline()
        val changed = File(copy, "one.bin")
        val modified = Files.getLastModifiedTime(changed.toPath())
        changed.writeText("agent-one!")
        Files.setLastModifiedTime(changed.toPath(), modified)
        readPaths.clear()
        val inventory = mirror.inventory()
        assertEquals(setOf(changed.absolutePath), readPaths)
        assertNotEquals(oldBase.getValue("one.bin").sha256, inventory["one.bin"])
        mirror.refresh()
        assertEquals("agent-one!", changed.readText())
        assertEquals("source-one", File(source, "one.bin").readText())
        assertEquals(oldBase, mirror.baseline())
    }

    @Test fun cacheCannotHideSourceEditOrPendingDeletion() = fixture { _, source, copy ->
        val original = File(source, "file").apply { writeText("first!") }
        val mirror = LocalAgentWorkspaceMirror(source, copy, WorkspaceFileFingerprintCache(
            nowMillis = { System.currentTimeMillis() + 30_000 }))
        mirror.refresh(); mirror.refresh()
        val modified = Files.getLastModifiedTime(original.toPath())
        original.writeText("second"); Files.setLastModifiedTime(original.toPath(), modified)
        mirror.refresh()
        assertEquals("second", File(copy, "file").readText())
        assertTrue(File(copy, "file").delete())
        original.writeText("third!")
        mirror.refresh()
        assertFalse("Pending CLI deletion must not be recreated", File(copy, "file").exists())
        assertEquals("third!", original.readText())
    }

    @Test fun guestManifestChangeIsPreservedAndWarnedWithoutChangingTrustedBaseline() = fixture { root, source, copy ->
        File(source, "file").writeText("base")
        val mirror = LocalAgentWorkspaceMirror(source, copy)
        mirror.refresh()
        val guest = File(copy, ".droide-sync-manifest.tsv")
        val baseline = File(root, ".mirror-sync-baseline.tsv")
        val trusted = baseline.readText()
        val savedTime = FileTime.fromMillis(123_000)
        Files.setLastModifiedTime(baseline.toPath(), savedTime)
        val wrong = trusted.map { if (it == 'a') 'b' else if (it == 'b') 'a' else it }.joinToString("")
        val foreign = if (wrong == trusted) "0".repeat(64) + trusted.drop(64) else wrong
        guest.writeText(foreign)
        val identity = Files.readAttributes(guest.toPath(), BasicFileAttributes::class.java)
        mirror.refresh()
        assertEquals(foreign, guest.readText())
        assertEquals(identity.fileKey(), Files.readAttributes(guest.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals(trusted, baseline.readText())
        assertTrue(mirror.metadataNotice.orEmpty().contains("preserved"))
        assertEquals("base", File(source, "file").readText())
        assertEquals("Unchanged host index must not be rewritten", savedTime, Files.getLastModifiedTime(baseline.toPath()))
    }

    @Test fun recreatedHostRehashesRatherThanTrustingDiskCache() = fixture { _, source, copy ->
        File(source, "file").writeText("content")
        val mirror = LocalAgentWorkspaceMirror(source, copy)
        mirror.refresh()
        var bytes = 0L
        val restarted = LocalAgentWorkspaceMirror(source, copy, WorkspaceFileFingerprintCache(
            nowMillis = { System.currentTimeMillis() + 30_000 },
            onHashRead = { _, count -> bytes += count }))
        restarted.refresh()
        assertEquals(14L, bytes)
        bytes = 0
        restarted.refresh(); restarted.inventory()
        assertEquals(0L, bytes)
    }
}
