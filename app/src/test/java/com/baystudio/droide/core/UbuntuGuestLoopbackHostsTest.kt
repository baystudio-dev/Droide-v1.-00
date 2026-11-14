package com.baystudio.droide.core

import com.baystudio.droide.core.UbuntuGuestLoopbackHosts.Status
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Actual production helper against disposable rootfs fixtures only. Never reads/writes /etc,
 * invokes a resolver, changes host network settings, or launches guest/host executables.
 * Callback races establish detection at the documented checkpoint, not hostile-path CAS safety.
 */
class UbuntuGuestLoopbackHostsTest {
    private class Fixture {
        val base = Files.createTempDirectory("ubuntu-loopback-hosts-").toFile()
        val guest = File(base, "guest").apply { mkdir() }
        val rootfs = File(guest, "rootfs").apply { mkdir() }
        val etc = File(rootfs, "etc").apply { mkdir() }
        val hosts = File(etc, "hosts").apply { writeText("") }
        val nss = File(etc, "nsswitch.conf").apply {
            writeText("# Keep NSS policy\npasswd: files\ngroup: files\nhosts: files dns\nnetworks: files\n")
        }
        val resolv = File(etc, "resolv.conf").apply {
            writeText("# Custom DNS must survive\nnameserver 192.0.2.53\nsearch fixture.invalid\noptions timeout:2\n")
        }

        init {
            mode(base, "rwx------")
            listOf(guest, rootfs, etc).forEach { mode(it, "rwxr-xr-x") }
            listOf(hosts, nss, resolv).forEach { mode(it, "rw-r--r--") }
        }

        fun repair(checkCancelled: () -> Unit = {}) = UbuntuGuestLoopbackHosts.repair(rootfs, checkCancelled)
        fun fingerprint() = UbuntuGuestLoopbackHosts.fingerprint(rootfs)
        fun pending(): List<File> = etc.listFiles().orEmpty().filter { it.name.startsWith(".droide-hosts-") }
        fun clean() {
            // No FOLLOW_LINKS. Restore fixture directory permissions only so read-only cases clean up.
            Files.walkFileTree(base.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.setPosixFilePermissions(dir, permissions("rwx------"))
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    private data class State(val bytes: List<Byte>, val key: Any?, val time: FileTime,
        val mode: Set<PosixFilePermission>, val unixMode: Int)

    companion object {
        private fun permissions(value: String): Set<PosixFilePermission> = PosixFilePermissions.fromString(value)
        private fun mode(file: File, value: String) { Files.setPosixFilePermissions(file.toPath(), permissions(value)) }
        private fun state(file: File): State {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            return State(file.readBytes().toList(), attrs.fileKey(), attrs.lastModifiedTime(),
                Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS),
                (Files.getAttribute(file.toPath(), "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 4095)
        }
    }

    private fun fixture(block: Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.clean() }
    }

    private fun Fixture.assertPreserved(expected: Status, diagnostic: String? = null) {
        val oldHosts = state(hosts); val oldNss = state(nss); val oldDns = state(resolv)
        val result = repair()
        assertEquals(result.diagnostic, expected, result.status)
        assertEquals(expected == Status.PRESENT, result.hasLocalhost)
        if (diagnostic != null) assertTrue(result.diagnostic, result.diagnostic.contains(diagnostic))
        assertEquals(oldHosts, state(hosts)); assertEquals(oldNss, state(nss)); assertEquals(oldDns, state(resolv))
        assertTrue(pending().toString(), pending().isEmpty())
    }

    @Test fun emptyAndMissingHostsGetBothLoopbackDefaultsWithoutChangingNssOrDns() {
        for (missing in listOf(false, true)) fixture {
            if (missing) check(hosts.delete())
            val oldNss = state(nss); val oldDns = state(resolv)
            val before = fingerprint()
            assertNotNull(before)
            val result = repair()
            assertEquals(result.diagnostic, Status.SEEDED, result.status)
            assertTrue(result.hasLocalhost)
            assertEquals("127.0.0.1 localhost\n::1 localhost\n", hosts.readText())
            assertEquals(420, state(hosts).unixMode)
            assertEquals(oldNss, state(nss)); assertEquals(oldDns, state(resolv))
            assertNotEquals(before, fingerprint())
            assertTrue(pending().isEmpty())
        }
    }

    @Test fun exactBundledUbuntuResourcesSeedLocalhostWhilePreservingStockNssAndDns() = fixture {
        val baseline = listOf("hosts", "nsswitch.conf", "resolv.conf").associateWith { name ->
            requireNotNull(javaClass.getResourceAsStream("/ubuntu-localhost-base/$name")) {
                "Missing bundled Ubuntu source fixture: $name"
            }.use { it.readBytes() }
        }
        assertEquals(0, baseline.getValue("hosts").size)
        assertEquals(494, baseline.getValue("nsswitch.conf").size)
        assertEquals(0, baseline.getValue("resolv.conf").size)
        baseline.forEach { (name, bytes) -> File(etc, name).writeBytes(bytes) }
        val nssBefore = state(nss); val dnsBefore = state(resolv)
        val result = repair()
        assertEquals(result.diagnostic, Status.SEEDED, result.status)
        assertTrue(result.hasLocalhost)
        assertEquals("127.0.0.1 localhost\n::1 localhost\n", hosts.readText())
        assertArrayEquals(baseline.getValue("nsswitch.conf"), nss.readBytes())
        assertArrayEquals(baseline.getValue("resolv.conf"), resolv.readBytes())
        assertEquals(nssBefore, state(nss)); assertEquals(dnsBefore, state(resolv))
        assertTrue(pending().isEmpty())
    }

    @Test fun appendingPreservesCommentsAliasesUtf8CrlfAndMissingFinalNewlineExactly() {
        for (original in listOf(
            "# A café fixture; localhost here is only a comment\n192.0.2.9 build.example build\n",
            "# retained CRLF\r\n192.0.2.9\tbuild.example\tbuild # keep\r\n",
            "192.0.2.9 build.example build",
            "# comment without newline",
            "# trailing carriage return\r",
            "\n\t\r\n"
        )) fixture {
            hosts.writeText(original)
            val oldNss = state(nss); val oldDns = state(resolv)
            val result = repair()
            assertEquals(result.diagnostic, Status.SEEDED, result.status)
            val separator = if (original.endsWith('\n')) "" else "\n"
            assertArrayEquals((original + separator + UbuntuGuestLoopbackHosts.DEFAULTS).toByteArray(), hosts.readBytes())
            assertEquals(oldNss, state(nss)); assertEquals(oldDns, state(resolv))
            assertTrue(pending().isEmpty())
        }
    }

    @Test fun onlyExactAliasesCountAndCommentsNeverSupplyAnExplicitMapping() {
        for (original in listOf(
            "192.0.2.9 notlocalhost localhost.example # localhost\n",
            "192.0.2.9 localhost2 my-localhost\n",
            "127.0.0.1 other # localhost\n",
            "127.0.0.1 localho\u017ft # Unicode case folding is not ASCII DNS case folding\n"
        )) fixture {
            hosts.writeText(original)
            assertEquals(Status.SEEDED, repair().status)
            assertEquals(original + UbuntuGuestLoopbackHosts.DEFAULTS, hosts.readText())
        }
    }

    @Test fun existingMixedCaseTrailingDotAndAliasPositionArePreservedWithoutAddingAFamily() {
        for (record in listOf(
            "127.0.0.1 localhost\n",
            "127.42.1.9 localbox LoCaLhOsT. alias # keep\r\n",
            "::1 LOCALHOST\n",
            "0:0:0:0:0:0:0:1 localhost.\n",
            "0000:0000:0000:0000:0000:0000:0000:0001 localhost\n",
            "0:0::0:1 canonical localhost alias",
            "127.0.0.1 localhost\n::1 localhost\n127.0.0.2 localhost\n"
        )) fixture {
            hosts.writeText(record)
            assertPreserved(Status.PRESENT, "Existing loopback")
        }
    }

    @Test fun explicitNonLoopbackMalformedAndDuplicateConflictsArePreservedAndDiagnosed() {
        for (record in listOf(
            "192.0.2.9 localhost\n", "0.0.0.0 localhost\n", ":: localhost\n",
            "2001:db8::1 localbox LOCALHOST.\n", "::ffff:127.0.0.1 localhost\n",
            "not-an-address localhost\n", "127.0.0.999 localhost\n", "127.1 localhost\n",
            "::1:: localhost\n", "0:0:0:0:0:0:0:1:1 localhost\n",
            "127.0.0.1 localhost\n192.0.2.9 localhost\n",
            "192.0.2.9 localhost\n::1 localhost\n"
        )) fixture {
            hosts.writeText(record)
            assertPreserved(Status.PRESERVED, "custom/non-loopback")
        }
    }

    @Test fun ambiguousZeroPaddedIpv4DoesNotFalselyReportUsableGoLoopback() {
        for (address in listOf("127.00.0.1", "127.0.00.1", "127.0.0.01", "0127.0.0.1")) fixture {
            hosts.writeText("$address localhost\n")
            assertPreserved(Status.PRESERVED, "custom/non-loopback")
        }
    }

    @Test fun supportedNssWhitespaceCommentsAndFilesOnlyRemainByteExact() {
        for (policy in listOf("hosts: files\n", "# policy\r\nhosts:\tfiles\tdns  # retain\r\n",
            "passwd: files\n hosts : files dns\ngroup: files\n")) fixture {
            nss.writeText(policy)
            val original = state(nss)
            assertEquals(Status.SEEDED, repair().status)
            assertEquals(original, state(nss))
        }
    }

    @Test fun customDuplicateEmptyOrMissingHostsNssPolicyIsNeverRewritten() {
        for (policy in listOf(
            "hosts: dns files\n", "hosts: dns\n", "hosts: files mdns4_minimal [NOTFOUND=return] dns\n",
            "hosts: files [SUCCESS=return] dns\n", "hosts: files resolve dns\n",
            "hosts: files\nhosts: files dns\n", "passwd: files\n", "hosts:\n", "", "HOSTS: files dns\n"
        )) fixture {
            nss.writeText(policy)
            assertPreserved(Status.PRESERVED, "Custom hosts NSS policy")
        }
        fixture {
            check(nss.delete())
            val originalHosts = state(hosts); val originalDns = state(resolv)
            val result = repair()
            assertEquals(Status.SKIPPED, result.status); assertFalse(result.hasLocalhost)
            assertFalse(nss.exists()); assertEquals(originalHosts, state(hosts)); assertEquals(originalDns, state(resolv))
            assertNull(fingerprint()); assertTrue(pending().isEmpty())
        }
    }

    @Test fun symlinkedHostsAndNssNeverTouchTheirTargetsIncludingDanglingLinks() {
        for (name in listOf("hosts", "nsswitch.conf")) for (dangling in listOf(false, true)) fixture {
            val target = File(base, "outside-fixture-record")
            if (!dangling) target.writeText("Preserve this target\n")
            val path = File(etc, name)
            check(path.delete()); Files.createSymbolicLink(path.toPath(), target.toPath())
            val dns = state(resolv)
            val result = repair()
            assertEquals(Status.SKIPPED, result.status); assertFalse(result.hasLocalhost)
            assertTrue(Files.isSymbolicLink(path.toPath()))
            if (dangling) assertFalse(target.exists()) else assertEquals("Preserve this target\n", target.readText())
            assertEquals(dns, state(resolv)); assertNull(fingerprint()); assertTrue(pending().isEmpty())
        }
    }

    @Test fun symlinkedEtcRootfsAndGuestParentsAreRejectedWithoutMutation() {
        for (kind in listOf("etc", "rootfs", "guest")) fixture {
            val originalHosts = state(hosts); val originalNss = state(nss); val originalDns = state(resolv)
            val path = when (kind) { "etc" -> etc; "rootfs" -> rootfs; else -> guest }
            val saved = File(base, "saved-$kind")
            check(path.renameTo(saved)); Files.createSymbolicLink(path.toPath(), saved.toPath())
            val result = repair()
            assertEquals(kind + result.diagnostic, Status.SKIPPED, result.status)
            assertEquals(originalHosts, state(hosts)); assertEquals(originalNss, state(nss)); assertEquals(originalDns, state(resolv))
            assertNull(fingerprint()); assertTrue(pending().isEmpty())
        }
    }

    @Test fun hardlinkedHostsOrNssArePreservedAndNotAdmitted() {
        for (name in listOf("hosts", "nsswitch.conf")) fixture {
            val target = File(etc, name)
            val alias = File(base, "hardlink")
            Files.createLink(alias.toPath(), target.toPath())
            val original = state(alias)
            assertPreserved(Status.SKIPPED, "Hard-linked")
            assertEquals(original, state(alias)); assertNull(fingerprint())
        }
    }

    @Test fun readOnlyAndSharedWritableFilesAndDirectoriesArePreserved() {
        for (name in listOf("hosts", "nss", "etc", "rootfs", "guest")) {
            for (kind in listOf("readonly", "group", "other")) fixture {
                val target = when (name) { "hosts" -> hosts; "nss" -> nss; "etc" -> etc; "rootfs" -> rootfs; else -> guest }
                val isDir = target.isDirectory
                val bits = when (kind) {
                    "readonly" -> if (isDir) "r-xr-xr-x" else "r--r--r--"
                    "group" -> if (isDir) "rwxrwxr-x" else "rw-rw-r--"
                    else -> if (isDir) "rwxr-xrwx" else "rw-r--rw-"
                }
                mode(target, bits)
                val originalMode = Files.getPosixFilePermissions(target.toPath(), NOFOLLOW_LINKS)
                assertPreserved(Status.SKIPPED, if (kind == "readonly") "Read-only" else "Shared-writable")
                assertEquals(originalMode, Files.getPosixFilePermissions(target.toPath(), NOFOLLOW_LINKS))
                assertNull(fingerprint())
            }
        }
    }

    @Test fun nonregularHostsAndNssAreRejectedWithoutReplacingThem() {
        for (name in listOf("hosts", "nsswitch.conf")) fixture {
            val target = File(etc, name)
            check(target.delete()); check(target.mkdir())
            val marker = File(target, "preserved-child").apply { writeText("keep") }
            val result = repair()
            assertEquals(Status.SKIPPED, result.status); assertFalse(result.hasLocalhost)
            assertTrue(target.isDirectory); assertEquals("keep", marker.readText())
            assertNull(fingerprint()); assertTrue(pending().isEmpty())
        }
    }

    @Test fun malformedRecordAndOversizeInputOrOutputNeverPublishPartially() {
        for (text in listOf(
            "127.0.0.1\n", "# keep\nmalformed\n", "192.0.2.9 good\nincomplete\n",
            "#" + "x".repeat(UbuntuGuestLoopbackHosts.MAX_BYTES),
            "#" + "x".repeat(UbuntuGuestLoopbackHosts.MAX_BYTES - 1)
        )) fixture {
            hosts.writeText(text)
            assertPreserved(Status.SKIPPED)
        }
        fixture {
            nss.writeText("#" + "x".repeat(UbuntuGuestLoopbackHosts.MAX_BYTES))
            assertPreserved(Status.SKIPPED, "Oversize")
            assertNull(fingerprint())
        }
    }

    @Test fun maximumBoundedOutputIsAllowedAndOneAdditionalByteIsRejected() {
        fixture {
            val original = "#" + "x".repeat(UbuntuGuestLoopbackHosts.MAX_BYTES - UbuntuGuestLoopbackHosts.DEFAULTS.length - 2)
            hosts.writeText(original)
            assertEquals(Status.SEEDED, repair().status)
            assertEquals(UbuntuGuestLoopbackHosts.MAX_BYTES.toLong(), hosts.length())
            assertEquals(original + "\n" + UbuntuGuestLoopbackHosts.DEFAULTS, hosts.readText())
        }
        fixture {
            hosts.writeText("#" + "x".repeat(UbuntuGuestLoopbackHosts.MAX_BYTES - UbuntuGuestLoopbackHosts.DEFAULTS.length - 1))
            assertPreserved(Status.SKIPPED, "bounded space")
        }
    }

    @Test fun invalidUtf8AndForbiddenControlsInEitherFileArePreserved() {
        val samples = listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xff.toByte()),
            "# invalid\u0000\n".toByteArray(), "# invalid\u0001\n".toByteArray(),
            "# invalid\u000b\n".toByteArray(), "# invalid\u001b\n".toByteArray(), "# invalid\u007f\n".toByteArray())
        for (name in listOf("hosts", "nsswitch.conf")) for (sample in samples) fixture {
            File(etc, name).writeBytes(sample)
            assertPreserved(Status.SKIPPED)
        }
    }

    @Test fun exactExistingPermissionsArePreservedAndNewFileDefaultsAre0644() {
        for (bits in listOf("rw-------", "rw-r-----", "rw-r--r--", "rwxr-xr-x")) fixture {
            mode(hosts, bits)
            val original = Files.getPosixFilePermissions(hosts.toPath())
            assertEquals(Status.SEEDED, repair().status)
            assertEquals(original, Files.getPosixFilePermissions(hosts.toPath()))
            assertEquals(0, state(hosts).unixMode and 3584)
        }
    }

    @Test fun specialFileModeBitsAreNeverCopiedIntoPublishedDefaults() {
        for (name in listOf("hosts", "nsswitch.conf")) for (extra in listOf(512, 1024, 2048)) fixture {
            val target = File(etc, name)
            Files.setAttribute(target.toPath(), "unix:mode", 420 or extra, NOFOLLOW_LINKS)
            assertEquals(420 or extra, state(target).unixMode)
            assertPreserved(Status.SKIPPED, "Special file mode")
            assertNull(fingerprint())
        }
    }

    @Test fun repeatedRepairIsByteMtimeInodeModeAndFingerprintIdempotent() = fixture {
        assertEquals(Status.SEEDED, repair().status)
        Files.setLastModifiedTime(hosts.toPath(), FileTime.fromMillis(1_600_000_000_123L))
        val original = state(hosts); val oldNss = state(nss); val oldDns = state(resolv); val identity = fingerprint()
        assertNotNull(original.key); assertNotNull(identity)
        repeat(12) {
            val result = repair()
            assertEquals(Status.PRESENT, result.status); assertTrue(result.hasLocalhost)
            assertEquals(original, state(hosts)); assertEquals(oldNss, state(nss)); assertEquals(oldDns, state(resolv))
            assertEquals(identity, fingerprint()); assertTrue(pending().isEmpty())
        }
    }

    @Test fun cancellationBeforeInspectionAndBeforePublicationPropagatesAndCleansTemporaryFile() {
        for (cancelAt in listOf(1, 2)) fixture {
            val original = state(hosts); val oldNss = state(nss); val oldDns = state(resolv)
            val cancellation = CancellationException("fixture cancellation")
            var calls = 0
            try {
                repair {
                    calls++
                    if (calls == cancelAt) {
                        assertEquals(if (cancelAt == 2) 1 else 0, pending().size)
                        throw cancellation
                    }
                }
                fail("Cancellation must propagate")
            } catch (caught: CancellationException) { assertSame(cancellation, caught) }
            assertEquals(cancelAt, calls)
            assertEquals(original, state(hosts)); assertEquals(oldNss, state(nss)); assertEquals(oldDns, state(resolv))
            assertTrue(pending().isEmpty())
        }
    }

    @Test fun cancellationBeforePublishingMissingHostsDoesNotCreateThem() = fixture {
        check(hosts.delete())
        var calls = 0
        try {
            repair { if (++calls == 2) throw CancellationException("before creation") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(2, calls); assertFalse(hosts.exists()); assertTrue(pending().isEmpty())
    }

    @Test fun concurrentHostsOrNssContentEditSurvivesEvenWithOriginalSizeAndMtime() {
        for (name in listOf("hosts", "nsswitch.conf")) fixture {
            hosts.writeText("192.0.2.1 builder\n")
            nss.writeText("hosts: files dns\n")
            val target = File(etc, name)
            val before = state(target)
            val replacement = if (name == "hosts") "192.0.2.2 builder\n" else "hosts: dns files\n"
            assertEquals(before.bytes.size, replacement.toByteArray().size)
            val unchanged = state(if (name == "hosts") nss else hosts)
            val dns = state(resolv)
            var calls = 0
            val result = repair {
                if (++calls == 2) {
                    target.writeText(replacement)
                    Files.setLastModifiedTime(target.toPath(), before.time)
                }
            }
            assertEquals(2, calls); assertEquals(Status.SKIPPED, result.status)
            assertTrue(result.diagnostic, result.diagnostic.contains("changed during preparation"))
            assertEquals(replacement, target.readText())
            assertEquals(unchanged, state(if (name == "hosts") nss else hosts))
            assertEquals(dns, state(resolv)); assertTrue(pending().isEmpty())
        }
    }

    @Test fun concurrentCreationOfMissingHostsWinsWithoutBeingOverwritten() = fixture {
        check(hosts.delete())
        val replacement = "127.0.0.2 localhost # user edit\n"
        var calls = 0
        val result = repair { if (++calls == 2) { hosts.writeText(replacement); mode(hosts, "rw-r--r--") } }
        assertEquals(Status.SKIPPED, result.status); assertFalse(result.hasLocalhost)
        assertEquals(replacement, hosts.readText()); assertTrue(pending().isEmpty())
    }

    @Test fun concurrentSymlinkModeOrPendingDataReplacementCannotPublishDefaults() {
        for (kind in listOf("hosts-link", "nss-link", "hosts-mode", "nss-mode", "pending")) fixture {
            val originalHosts = hosts.readBytes(); val originalNss = nss.readBytes()
            val target = File(base, "untouched-target").apply { writeText("do not overwrite\n") }
            var calls = 0
            val result = repair {
                if (++calls == 2) when (kind) {
                    "hosts-link", "nss-link" -> {
                        val path = if (kind == "hosts-link") hosts else nss
                        check(path.delete()); Files.createSymbolicLink(path.toPath(), target.toPath())
                    }
                    "hosts-mode" -> mode(hosts, "rw-------")
                    "nss-mode" -> mode(nss, "rw-------")
                    else -> pending().single().writeText("corrupted pending data\n")
                }
            }
            assertEquals(kind + result.diagnostic, Status.SKIPPED, result.status)
            assertEquals("do not overwrite\n", target.readText())
            if (kind == "hosts-link") assertTrue(Files.isSymbolicLink(hosts.toPath())) else assertArrayEquals(originalHosts, hosts.readBytes())
            if (kind == "nss-link") assertTrue(Files.isSymbolicLink(nss.toPath())) else assertArrayEquals(originalNss, nss.readBytes())
            assertTrue(pending().isEmpty())
        }
    }

    @Test fun admissionFingerprintDetectsContentModePresenceAndInodeChangesWithoutWriting() = fixture {
        assertEquals(Status.SEEDED, repair().status)
        val original = state(hosts); val oldNss = state(nss); val oldDns = state(resolv)
        var identity = requireNotNull(fingerprint())
        repeat(4) { assertEquals(identity, fingerprint()) }
        assertEquals(original, state(hosts)); assertEquals(oldNss, state(nss)); assertEquals(oldDns, state(resolv))

        hosts.writeText(hosts.readText().replace("127.0.0.1", "127.0.0.2"))
        Files.setLastModifiedTime(hosts.toPath(), original.time)
        var next = requireNotNull(fingerprint()); assertNotEquals(identity, next); identity = next
        nss.writeText(nss.readText().replace("files dns", "dns files"))
        Files.setLastModifiedTime(nss.toPath(), oldNss.time)
        next = requireNotNull(fingerprint()); assertNotEquals(identity, next); identity = next
        mode(hosts, "rw-------")
        next = requireNotNull(fingerprint()); assertNotEquals(identity, next); identity = next

        val beforeReplacement = state(hosts)
        val old = File(etc, "retained-hosts")
        Files.move(hosts.toPath(), old.toPath())
        hosts.writeBytes(beforeReplacement.bytes.toByteArray()); mode(hosts, "rw-------")
        Files.setLastModifiedTime(hosts.toPath(), beforeReplacement.time)
        assertNotEquals(beforeReplacement.key, state(hosts).key)
        next = requireNotNull(fingerprint()); assertNotEquals(identity, next); identity = next
        check(hosts.delete())
        next = requireNotNull(fingerprint()); assertNotEquals(identity, next)
        check(nss.delete()); assertNull(fingerprint())
        assertEquals(oldDns, state(resolv)); assertTrue(pending().isEmpty())
    }

    @Test fun unrelatedEtcChildrenDoNotInvalidateAdmissionFingerprint() = fixture {
        assertEquals(Status.SEEDED, repair().status)
        val before = requireNotNull(fingerprint())
        val unrelated = File(etc, "unrelated-user-file").apply { writeText("content") }
        assertEquals(before, fingerprint())
        check(unrelated.delete()); assertEquals(before, fingerprint())
    }
}
