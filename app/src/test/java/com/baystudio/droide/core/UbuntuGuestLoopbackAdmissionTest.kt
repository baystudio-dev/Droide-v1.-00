package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UbuntuGuestLoopbackAdmissionTest {
    private fun fixture(block: (File) -> Unit) {
        val parent = Files.createTempDirectory("loopback-admission-").toFile()
        val root = File(parent, "rootfs").apply { mkdir() }
        File(root, "etc").mkdir()
        File(root, "etc/hosts").writeText("")
        File(root, "etc/nsswitch.conf").writeText("hosts: files dns\n")
        try { block(root) } finally { parent.deleteRecursively() }
    }
    @Test fun warmAttachReusesOnlyTheRepairedIdentity() = fixture { root ->
        val cache = UbuntuGuestReadinessCache { 0L }
        val empty = UbuntuGuestLoopbackHosts.fingerprint(root)
        assertEquals(UbuntuGuestLoopbackHosts.Status.SEEDED, UbuntuGuestLoopbackHosts.repair(root).status)
        val repaired = UbuntuGuestLoopbackHosts.fingerprint(root)
        assertNotNull(repaired); assertNotEquals(empty, repaired)
        cache.admit(repaired)
        repeat(20) { assertTrue(cache.mayReuse(UbuntuGuestLoopbackHosts.fingerprint(root))) }
    }
    @Test fun deletionInsideWarmCacheWindowRequiresPreparationAgain() = fixture { root ->
        UbuntuGuestLoopbackHosts.repair(root)
        val cache = UbuntuGuestReadinessCache { 0L }
        cache.admit(UbuntuGuestLoopbackHosts.fingerprint(root))
        assertTrue(File(root, "etc/hosts").delete())
        assertFalse(cache.mayReuse(UbuntuGuestLoopbackHosts.fingerprint(root)))
        assertEquals(UbuntuGuestLoopbackHosts.Status.SEEDED, UbuntuGuestLoopbackHosts.repair(root).status)
    }
    @Test fun restoredMtimeCannotHideChangedContent() = fixture { root ->
        UbuntuGuestLoopbackHosts.repair(root)
        val hosts = File(root, "etc/hosts"); val time = Files.getLastModifiedTime(hosts.toPath())
        val old = UbuntuGuestLoopbackHosts.fingerprint(root)
        hosts.writeText(hosts.readText().replace("127.0.0.1", "192.0.2.1"))
        Files.setLastModifiedTime(hosts.toPath(), time)
        assertNotEquals(old, UbuntuGuestLoopbackHosts.fingerprint(root))
        assertFalse(UbuntuGuestLoopbackHosts.repair(root).hasLocalhost)
    }
    @Test fun packageShortcutDeclinesEditedHostsWithoutWritingUnderTheOwner() = fixture { root -> runBlocking {
        UbuntuGuestLoopbackHosts.repair(root)
        val a = UbuntuPackageAttachAdmission()
        a.duringPackageOperation({ UbuntuGuestLoopbackHosts.fingerprint(root) }) {
            repeat(20) { assertTrue(a.mayAttach()) }
            File(root, "etc/hosts").writeText("")
            assertFalse(a.mayAttach())
            assertEquals("", File(root, "etc/hosts").readText())
        }
        assertFalse(a.mayAttach())
        assertTrue(UbuntuGuestLoopbackHosts.repair(root).hasLocalhost)
    } }
    @Test fun packageShortcutDeclinesNssChangesAndPreservesTheirPolicy() = fixture { root -> runBlocking {
        UbuntuGuestLoopbackHosts.repair(root)
        val a = UbuntuPackageAttachAdmission()
        a.duringPackageOperation({ UbuntuGuestLoopbackHosts.fingerprint(root) }) {
            File(root, "etc/nsswitch.conf").writeText("hosts: dns files\n")
            assertFalse(a.mayAttach())
        }
        assertEquals(UbuntuGuestLoopbackHosts.Status.PRESERVED, UbuntuGuestLoopbackHosts.repair(root).status)
        assertEquals("hosts: dns files\n", File(root, "etc/nsswitch.conf").readText())
    } }
    @Test fun unsafeNameFileCannotBorrowPackageAdmission() = fixture { root -> runBlocking {
        UbuntuGuestLoopbackHosts.repair(root)
        val a = UbuntuPackageAttachAdmission()
        a.duringPackageOperation({ UbuntuGuestLoopbackHosts.fingerprint(root) }) {
            val hosts = File(root, "etc/hosts"); assertTrue(hosts.delete())
            Files.createSymbolicLink(hosts.toPath(), File(root, "etc/nsswitch.conf").toPath())
            assertNull(UbuntuGuestLoopbackHosts.fingerprint(root)); assertFalse(a.mayAttach())
        }
    } }
}
