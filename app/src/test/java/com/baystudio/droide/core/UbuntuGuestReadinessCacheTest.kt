package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class UbuntuGuestReadinessCacheTest {
    private fun guestFixture(block: (java.io.File, java.io.File, java.io.File) -> Unit) {
        val base = Files.createTempDirectory("ubuntu-shared-readiness-").toFile()
        val guest = base.resolve("guest/ubuntu").apply { mkdirs() }
        val bin = guest.resolve("rootfs/usr/bin").apply { mkdirs() }
        listOf("apt-get", "dpkg", "bash").forEach { bin.resolve(it).writeText(it) }
        val engine = base.resolve("proot").apply { writeText("engine") }
        guest.resolve("launch").writeText("launcher")
        guest.resolve("BOOTSTRAP_LOCK.txt").writeText("profile")
        try { block(base, guest, engine) } finally { PathSecurity.deleteTreeNoFollow(base) }
    }

    @Test fun installingPackagesAndWritingInstallerMetadataDoNotRepeatGuestAdmission() = guestFixture { _, guest, engine ->
        val cache = UbuntuGuestReadinessCache { 0L }
        fun seal() = UbuntuGuestReadinessCache.fingerprint(guest, engine, "launcher", emptyList())
        val admitted = seal()
        assertNotNull(admitted)
        cache.admit(admitted)
        guest.resolve(".droide-apt-install.lock").writeText("")
        guest.resolve(".apt-index-cache.json").writeText("metadata")
        guest.resolve("rootfs/usr/bin/python3").writeText("new user-installed command")
        guest.resolve("rootfs/var/lib/dpkg/status").apply { parentFile!!.mkdirs(); writeText("installed packages") }
        // Package checks still query dpkg separately. Directory contents do not replace the launcher,
        // receipt or the executables on which this short admission depends.
        assertTrue("A dependency install discarded a still-valid Ubuntu admission", cache.mayReuse(seal()))
    }

    @Test fun replacingAGuestDirectoryInvalidatesAdmissionEvenWithRestoredDirectoryTimes() = guestFixture { base, guest, engine ->
        fun seal() = UbuntuGuestReadinessCache.fingerprint(guest, engine, "launcher", emptyList())
        val admitted = seal()
        assertNotNull(admitted)
        val bin = guest.resolve("rootfs/usr/bin")
        val originalTime = bin.lastModified()
        val retained = base.resolve("old-bin")
        assertTrue(bin.renameTo(retained))
        assertTrue(bin.mkdir())
        listOf("apt-get", "dpkg", "bash").forEach { retained.resolve(it).copyTo(bin.resolve(it)) }
        assertTrue(bin.setLastModified(originalTime))
        assertNotEquals(admitted, seal())
    }

    @Test fun aRetainedPreviousRootDoesNotDisableReuseOfTheExistingCurrentRoot() = guestFixture { base, guest, engine ->
        val previous = base.resolve("guest/.previous/ubuntu").apply { mkdirs() }
        val userData = previous.resolve("root/home.txt").apply { parentFile!!.mkdirs(); writeText("keep this backup") }
        val stage = base.resolve("guest/.staging/ubuntu")
        fun seal() = UbuntuGuestReadinessCache.fingerprint(guest, engine, "launcher", listOf(stage), previous)
        val cache = UbuntuGuestReadinessCache { 0L }
        val admitted = seal()
        assertNotNull(admitted)
        cache.admit(admitted)
        repeat(100) { assertTrue(cache.mayReuse(seal())) }
        assertEquals("keep this backup", userData.readText())
        stage.mkdirs()
        assertNull(seal())
        stage.delete()
        assertTrue(guest.resolve("rootfs").renameTo(base.resolve("missing-current-rootfs")))
        assertNull(seal())
        assertEquals("keep this backup", userData.readText())
    }

    @Test fun aSymlinkedPreviousRootCannotReceiveAnAdmission() = guestFixture { base, guest, engine ->
        val previous = base.resolve("guest/.previous/ubuntu").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(previous.toPath(), guest.toPath())
        assertNull(UbuntuGuestReadinessCache.fingerprint(guest, engine, "launcher", emptyList(), previous))
    }

    @Test fun onlyMatchingRecentSuccessfulAdmissionCanBeReused() {
        var now = 0L
        val cache = UbuntuGuestReadinessCache { now }
        assertFalse(cache.mayReuse("root-a"))
        cache.admit("root-a")
        repeat(100) { assertTrue(cache.mayReuse("root-a")) }
        assertFalse(cache.mayReuse("root-b")); assertFalse(cache.mayReuse(null))
        now = UbuntuGuestReadinessCache.MAX_AGE_NANOS
        assertFalse(cache.mayReuse("root-a"))
        cache.admit("root-a"); assertTrue(cache.mayReuse("root-a"))
        now--; assertFalse(cache.mayReuse("root-a"))
        cache.invalidate(); assertFalse(cache.mayReuse("root-a"))
    }

    @Test fun warmReuseRemovesDuplicateProbesWithoutCertifyingFailedSetup() {
        var now = 0L; var probes = 0
        val cache = UbuntuGuestReadinessCache { now }
        fun ensure(seal: String, healthy: Boolean) {
            if (cache.mayReuse(seal)) return
            cache.invalidate(); probes++
            if (healthy) cache.admit(seal)
        }
        repeat(100) { ensure("a", true) }; assertEquals(1, probes)
        now += UbuntuGuestReadinessCache.MAX_AGE_NANOS
        ensure("a", true); assertEquals(2, probes)
        repeat(3) { ensure("broken", false) }; assertEquals(5, probes)
        assertFalse(cache.mayReuse("broken"))
    }

    @Test fun filesystemChangesAndInterruptedRecoveryInvalidateIdentity() {
        val base = Files.createTempDirectory("ubuntu-readiness-").toFile()
        val guest = base.resolve("guest/ubuntu").apply { mkdirs() }
        val bin = guest.resolve("rootfs/usr/bin").apply { mkdirs() }
        listOf("apt-get", "dpkg", "bash").forEach { bin.resolve(it).writeText(it) }
        val engine = base.resolve("proot").apply { writeText("engine") }
        guest.resolve("launch").writeText("launcher"); guest.resolve("BOOTSTRAP_LOCK.txt").writeText("profile")
        val pending = base.resolve("pending")
        fun seal() = UbuntuGuestReadinessCache.fingerprint(guest, engine, "expected-launcher", listOf(pending))
        try {
            val first = seal(); assertNotNull(first); assertEquals(first, seal())
            // Equal concatenated bytes across two files must never mean equal identities.
            guest.resolve("launch").writeText("ab"); guest.resolve("BOOTSTRAP_LOCK.txt").writeText("c")
            val splitA = seal()
            guest.resolve("launch").writeText("a"); guest.resolve("BOOTSTRAP_LOCK.txt").writeText("bc")
            assertNotEquals(splitA, seal())
            guest.resolve("launch").writeText("different-launcher"); assertNotEquals(first, seal())
            val changed = seal()
            guest.resolve("BOOTSTRAP_LOCK.txt").writeText("different-profile"); assertNotEquals(changed, seal())
            val binarySeal = seal(); bin.resolve("dpkg").writeText("replacement-dpkg"); assertNotEquals(binarySeal, seal())
            pending.mkdir(); assertNull(seal()); pending.delete(); assertNotNull(seal())
            guest.resolve("launch").delete(); Files.createSymbolicLink(guest.resolve("launch").toPath(), engine.toPath())
            assertNull(seal())
        } finally { PathSecurity.deleteTreeNoFollow(base) }
    }
}
