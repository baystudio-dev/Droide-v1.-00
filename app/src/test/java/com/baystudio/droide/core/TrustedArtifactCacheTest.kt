package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TrustedArtifactCacheTest {
    @Test fun canonicalParentAliasUsesTheSameLeaseDuringPruning() = runBlocking {
        val root = Files.createTempDirectory("droide-cache-alias-").toFile()
        try {
            val cache = File(root, "cache").apply { mkdir() }
            val alias = File(root, "alias")
            Files.createSymbolicLink(alias.toPath(), cache.toPath())
            val artifact = File(cache, "artifact.zip").apply { writeText("verified") }
            TrustedArtifactCache.withLease(File(alias, artifact.name)) {
                TrustedArtifactCache.prune(cache, 0, emptySet())
                assertEquals("verified", artifact.readText())
            }
            TrustedArtifactCache.prune(cache, 0, emptySet())
            assertFalse(artifact.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun primaryAndEarlierOverlaysSurviveLaterDownloadPruning() = runBlocking {
        val root = Files.createTempDirectory("droide-cache-lease-").toFile()
        try {
            val primary = File(root, "platform.zip").apply { writeBytes(ByteArray(256)); setLastModified(1) }
            val overlay = File(root, "aapt2").apply { writeBytes(ByteArray(128)); setLastModified(2) }
            val old = File(root, "unused.zip").apply { writeBytes(ByteArray(128)); setLastModified(3) }
            TrustedArtifactCache.withLease(primary) {
                TrustedArtifactCache.withLease(overlay) {
                    TrustedArtifactCache.prune(root, 0, setOf("new-overlay"))
                    assertTrue(primary.isFile)
                    assertTrue(overlay.isFile)
                    assertFalse(old.exists())
                    assertEquals(256, primary.readBytes().size)
                }
                TrustedArtifactCache.prune(root, 0, emptySet())
                assertTrue(primary.isFile)
                assertFalse(overlay.exists())
            }
            TrustedArtifactCache.prune(root, 0, emptySet())
            assertFalse(primary.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun clearCacheDefersDeletionUntilAllConsumersReleaseTheFile() = runBlocking {
        val root = Files.createTempDirectory("droide-cache-discard-").toFile()
        try {
            val artifact = File(root, "artifact.zip").apply { writeText("verified") }
            TrustedArtifactCache.withLease(artifact) {
                TrustedArtifactCache.withLease(artifact) {
                    TrustedArtifactCache.discard(artifact)
                    assertTrue(artifact.isFile)
                }
                assertEquals("verified", artifact.readText())
            }
            assertFalse(artifact.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun leaseAcquiredBeforeDownloadProtectsNewlyPublishedFile() = runBlocking {
        val root = Files.createTempDirectory("droide-cache-publish-").toFile()
        try {
            val artifact = File(root, "artifact.zip")
            TrustedArtifactCache.withLease(artifact) {
                artifact.writeText("downloaded")
                TrustedArtifactCache.prune(root, 0, emptySet())
                assertEquals("downloaded", artifact.readText())
            }
            TrustedArtifactCache.prune(root, 0, emptySet())
            assertFalse(artifact.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationReleasesLeaseSoDisposableCacheCanBePruned() = runBlocking {
        val root = Files.createTempDirectory("droide-cache-cancel-").toFile()
        try {
            val artifact = File(root, "artifact.zip").apply { writeText("verified") }
            try {
                TrustedArtifactCache.withLease(artifact) { throw CancellationException("cancel") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            TrustedArtifactCache.prune(root, 0, emptySet())
            assertFalse(artifact.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun pruningPreservesPartialTransferFilesAndDoesNotFollowSymlinks() {
        val root = Files.createTempDirectory("droide-cache-paths-").toFile()
        val outside = Files.createTempFile("droide-cache-outside-", ".data").toFile()
        try {
            outside.writeText("keep")
            File(root, "archive.zip.part").writeText("resumable")
            File(root, "archive.zip.part.properties").writeText("identity")
            Files.createSymbolicLink(File(root, "link.zip").toPath(), outside.toPath())
            TrustedArtifactCache.prune(root, 0, emptySet())
            assertTrue(File(root, "archive.zip.part").isFile)
            assertTrue(File(root, "archive.zip.part.properties").isFile)
            assertEquals("keep", outside.readText())
            assertTrue(Files.isSymbolicLink(File(root, "link.zip").toPath()))
        } finally { root.deleteRecursively(); outside.delete() }
    }
}
