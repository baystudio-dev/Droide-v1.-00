package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Test

class UbuntuAptMetadataCacheTest {
    private class Fixture(val root: File) {
        var now = 1_750_000_000_000L
        val stamp = File(root, ".apt-index-cache.json")
        val rootfs = File(root, "rootfs").apply { mkdirs() }
        val source = write("etc/apt/sources.list.d/ubuntu.sources", "Types: deb\nURIs: https://ports.ubuntu.com/ubuntu-ports\nSuites: noble\nComponents: main\nSigned-By: /usr/share/keyrings/ubuntu.gpg\n")
        val key = write("usr/share/keyrings/ubuntu.gpg", "test key")
        val release = write("var/lib/apt/lists/ports_noble_InRelease", "-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\nSuite: noble\n")
        val packages = write("var/lib/apt/lists/ports_noble_main_binary-arm64_Packages.lz4", "package index bytes")
        val cache = UbuntuAptMetadataCache(rootfs, stamp) { now }
        fun write(path: String, content: String) = File(rootfs, path).apply { parentFile!!.mkdirs(); writeText(content) }
        fun expiry(time: Long) { release.appendText("Valid-Until: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC))}\n") }
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("droide-apt-indexes").toFile()
        try { block(Fixture(root)) } finally { root.deleteRecursively() }
    }

    @Test fun onlyASuccessfulUpdateCheckpointEnablesReuseUntilTheBoundedAge() = fixture { f ->
        assertFalse(f.cache.isFresh()); f.cache.refreshed(); assertTrue(f.cache.isFresh())
        f.now += UbuntuAptMetadataCache.MAX_AGE_MS - 1; assertTrue(f.cache.isFresh())
        f.now++; assertFalse(f.cache.isFresh())
    }

    @Test fun sourceKeyAndAptConfigurationChangesInvalidateCachedMetadata() = fixture { f ->
        for (file in listOf(f.source, f.key, f.write("etc/apt/apt.conf.d/99local", "Acquire::Languages none;"))) {
            f.cache.refreshed(); assertTrue(f.cache.isFresh())
            file.appendText("\nchanged"); assertFalse(f.cache.isFresh())
        }
    }

    @Test fun deletedOrReplacedPackageIndexesCannotUseThePreviousCheckpoint() = fixture { f ->
        f.cache.refreshed(); f.packages.appendText("new packages"); assertFalse(f.cache.isFresh())
        f.cache.refreshed(); assertTrue(f.cache.isFresh())
        assertTrue(f.packages.setLastModified(f.packages.lastModified() + 1000)); assertFalse(f.cache.isFresh())
        f.cache.refreshed(); f.packages.delete(); assertFalse(f.cache.isFresh())
    }

    @Test fun releaseExpiryIsHonoredEvenInsideTheSixHourReuseWindow() = fixture { f ->
        f.expiry(f.now + 60_000); f.cache.refreshed(); assertTrue(f.cache.isFresh())
        f.now += 60_000; assertFalse(f.cache.isFresh())
    }

    @Test fun malformedExpiryOrBackwardsClockCannotProlongReuse() = fixture { f ->
        f.cache.refreshed(); f.now--; assertFalse(f.cache.isFresh())
        f.release.appendText("Valid-Until: malformed\n"); f.cache.refreshed(); assertFalse(f.cache.isFresh())
    }

    @Test fun bracketedSourcesAndConventionalUserKeyringsCanBeCached() = fixture { f ->
        val key = f.write("etc/apt/keyrings/custom.gpg", "custom key")
        f.source.writeText("deb [arch=arm64 signed-by=/etc/apt/keyrings/custom.gpg] https://example.invalid/ubuntu noble main\n")
        f.cache.refreshed(); assertTrue(f.cache.isFresh())
        key.appendText("changed"); assertFalse(f.cache.isFresh())
    }

    @Test fun keysOutsideTheCapturedDirectoriesRequireANormalAuthenticatedRefresh() = fixture { f ->
        f.write("root/custom.gpg", "outside key")
        f.source.writeText("Types: deb\nSigned-By: /root/custom.gpg\n")
        f.cache.refreshed(); assertFalse(f.cache.isFresh()); assertFalse(f.stamp.exists())
    }

    @Test fun corruptCheckpointsAndExplicitInvalidationPreserveIndexBytes() = fixture { f ->
        val before = f.packages.readBytes()
        f.stamp.writeText("not json"); assertFalse(f.cache.isFresh())
        f.cache.refreshed(); assertTrue(f.cache.isFresh()); f.cache.invalidate(); assertFalse(f.cache.isFresh())
        assertArrayEquals(before, f.packages.readBytes()); assertTrue(f.release.isFile)
    }

    @Test fun symlinkedCheckpointCannotOverwriteAnExternalFile() = fixture { f ->
        val outside = File(f.root, "keep.txt").apply { writeText("keep") }
        Files.createSymbolicLink(f.stamp.toPath(), outside.toPath())
        assertFalse(f.cache.isFresh())
        try { f.cache.refreshed(); fail("Symlink checkpoint accepted") } catch (_: IllegalStateException) {}
        f.cache.invalidate(); assertEquals("keep", outside.readText()); assertTrue(Files.isSymbolicLink(f.stamp.toPath()))
    }

    @Test fun sharedLockAndPartialDownloadsDoNotInvalidateCompleteIndexes() = fixture { f ->
        f.cache.refreshed()
        f.write("var/lib/apt/lists/lock", "")
        f.write("var/lib/apt/lists/partial/incoming", "pending")
        f.write("var/lib/apt/lists/auxfiles/aux", "auxiliary")
        assertTrue(f.cache.isFresh())
    }
}
