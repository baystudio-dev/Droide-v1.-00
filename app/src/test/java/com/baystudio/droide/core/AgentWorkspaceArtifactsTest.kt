package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AgentWorkspaceArtifactsTest {
    private fun fixture(block: suspend (File, File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("agent-artifact-bytes-").toFile()
        try {
            block(File(root, "workspace").apply { mkdirs() }, File(root, "staging").apply { mkdirs() })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun apkAboveFormerTextLimitAndNonUtf8AssetsPullAndSnapshotAsExactBytes() = fixture { root, staging ->
        val apk = File(root, "app/build/outputs/apk/debug/app-debug.apk").apply { parentFile.mkdirs() }
        val block = ByteArray(64 * 1024) { (it % 256).toByte() }
        apk.outputStream().use { output -> repeat(17 * 16) { output.write(block) } }
        assertEquals(17L * 1024 * 1024, apk.length())
        val assets = mapOf(
            "kucing.glb" to byteArrayOf(0x67, 0x6c, 0x54, 0x46, 2, 0, -1, -128, 0),
            "preview.png" to byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, -1),
            "archive.zip" to byteArrayOf(80, 75, 3, 4, 0, -64, -81),
            "opaque.custom" to byteArrayOf(0, -64, -81, -2, -1, 0),
            "empty.bin" to byteArrayOf(),
        )
        assets.forEach { (name, bytes) -> File(root, name).writeBytes(bytes) }
        val names = listOf("app/build/outputs/apk/debug/app-debug.apk") + assets.keys
        val expected = names.associateWith { digest(File(root, it)) }
        for ((index, name) in names.withIndex()) {
            val original = File(root, name)
            val copied = File(staging, "copy-$index")
            AgentWorkspaceArtifacts.pull(root, original, copied)
            assertEquals("Length changed for $name", original.length(), copied.length())
            assertEquals("Bytes changed for $name", expected[name], digest(copied))
            assertEquals("Pull must leave CLI output intact", expected[name], digest(original))
        }
        assertEquals(expected, AgentWorkspaceArtifacts.snapshot(root))
    }

    @Test fun snapshotIncludesPublishedBuildArtifactsAndSkipsDependenciesAndIntermediates() = fixture { root, _ ->
        val included = listOf(
            "src/main.kt", "app/build/outputs/apk/debug/app-debug.apk",
            "app/build/outputs/bundle/release/app.aab", "build/libs/example.jar",
            "assets/model.glb", "dist/index.html", "build/results/report.bin",
        )
        val excluded = listOf(
            ".git/objects/object", ".gradle/state", ".droide/internal.bin",
            "module/node_modules/dependency.bin", "app/build/intermediates/object.o",
            "app/build/tmp/work.bin", "app/build/kotlin/cache.bin", "app/build/.transforms/cache.bin",
            "local.properties", "config/.env", "config/key.pem", ".droide-sync-manifest.tsv",
        )
        (included + excluded).forEach { name ->
            File(root, name).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0, -1, 1)) }
        }
        assertEquals(included.toSet(), AgentWorkspaceArtifacts.snapshot(root).keys)
        excluded.forEach { assertTrue("Inspection must not delete excluded work", File(root, it).isFile) }
    }

    @Test fun symlinkFilesDirectoriesAndRootCannotExposeOutsideBytes() = fixture { root, staging ->
        val outside = File(staging, "outside").apply { mkdirs() }
        val secret = File(outside, "private.bin").apply { writeBytes(byteArrayOf(0, -1, 91)) }
        Files.createSymbolicLink(File(root, "file-link").toPath(), secret.toPath())
        Files.createSymbolicLink(File(root, "directory-link").toPath(), outside.toPath())
        val linkedRoot = File(staging, "linked-root")
        Files.createSymbolicLink(linkedRoot.toPath(), root.toPath())
        for ((base, path) in listOf(
            root to File(root, "file-link"), root to File(root, "directory-link/private.bin"),
            linkedRoot to File(linkedRoot, "file-link"), root to secret,
        )) {
            val output = File(staging, "rejected-copy")
            try { AgentWorkspaceArtifacts.pull(base, path, output); fail("Unsafe path was copied: $path") }
            catch (_: IllegalArgumentException) { }
            assertFalse(output.exists())
        }
        assertTrue(AgentWorkspaceArtifacts.snapshot(root).isEmpty())
        assertArrayEquals(byteArrayOf(0, -1, 91), secret.readBytes())
    }

    @Test fun directoriesAreRejectedButOrdinaryFilesAboveFourGiBAreAdmitted() = fixture { root, staging ->
        val directory = File(root, "folder").apply { mkdirs() }
        val output = File(staging, "copy").apply { writeText("previous staging bytes") }
        try { AgentWorkspaceArtifacts.pull(root, directory, output); fail("Directory was copied") }
        catch (_: IllegalArgumentException) { }
        assertEquals("previous staging bytes", output.readText())
        val large = File(root, "huge.apk")
        val bytes = 4L * 1024 * 1024 * 1024 + 1L
        RandomAccessFile(large, "rw").use { it.setLength(bytes) }
        assertEquals(bytes, AgentWorkspaceArtifacts.requireRegular(root, large).size())
        // Actual >4 GiB transfer is covered separately by WorkspaceLargeFileTransferTest.
    }

    @Test fun cancelledAdmissionLeavesOutputAndExistingStagingUntouched() = fixture { root, staging ->
        val original = File(root, "model.glb").apply { writeBytes(byteArrayOf(0, -1, 12)) }
        val copied = File(staging, "copy").apply { writeText("unchanged staging") }
        for (operation in listOf<suspend () -> Unit>(
            { AgentWorkspaceArtifacts.pull(root, original, copied) },
            { AgentWorkspaceArtifacts.snapshot(root); Unit },
        )) {
            val cancelled = Job().apply { cancel() }
            try { withContext(cancelled) { operation() }; fail("Cancelled operation was admitted") }
            catch (_: CancellationException) { }
        }
        assertArrayEquals(byteArrayOf(0, -1, 12), original.readBytes())
        assertEquals("unchanged staging", copied.readText())
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(32 * 1024)
            while (true) {
                val size = input.read(bytes)
                if (size < 0) break
                hash.update(bytes, 0, size)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
