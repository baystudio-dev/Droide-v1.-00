package com.baystudio.droide.core

import android.app.Application
import android.provider.Settings
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WorkspaceProcessBootIdentityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val boot = "55a91fa3-7d61-47de-8eea-76f4d360beed"
    private fun device(value: String?) { Settings.Secure.putString(app.contentResolver, Settings.Secure.ANDROID_ID, value) }
    private fun identity(root: File = File(app.filesDir, "mirror"), value: () -> String? = { boot }) =
        WorkspaceProcessBootIdentity.forApplication(app, root, value)
    private fun parse(value: String) = WorkspaceProcessBootIdentity.readBootId(ByteArrayInputStream(value.toByteArray(Charsets.US_ASCII)))

    @Test fun privateMirrorIdentityIsStableHashedAndScopedToDevice() {
        device("1234567890abcdef")
        val first = requireNotNull(identity())
        assertEquals(first, identity()); assertEquals(boot, first.boot)
        assertTrue(first.device.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first.device.contains("1234567890abcdef"))
        device("1234567890ABCDEF"); assertEquals(first, identity())
        device("fedcba0987654321"); assertNotEquals(first.device, requireNotNull(identity()).device)
    }

    @Test fun unavailableOrUnusableDeviceIdentityNeverProvesAReboot() {
        for (value in listOf(null, "", "unknown", "1234", "g".repeat(16), "0000000000000000", "9774d56d682e549c")) {
            device(value); assertNull(value, identity())
        }
    }

    @Test fun sharedRootAndSymlinkOutsidePrivateStorageStayUnknown() {
        device("1234567890abcdef")
        val outside = Files.createTempDirectory("untrusted-boot-root-").toFile()
        val link = File(app.filesDir, "outside-link")
        try {
            assertNull(identity(outside))
            assertNull(identity(File(app.filesDir.path + "-lookalike")))
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertNull(identity(link))
        } finally { Files.deleteIfExists(link.toPath()); assertTrue(outside.delete()) }
    }

    @Test fun inaccessibleAndInvalidBootIdentityRemainUnknown() {
        device("1234567890abcdef")
        assertNull(identity(value = { throw IOException("boot_id denied") }))
        for (value in listOf(null, "", "00000000-0000-0000-0000-000000000000", "1-2-3-4-5", boot.uppercase(), "$boot\n")) {
            assertNull(value, identity(value = { value }))
        }
    }

    @Test fun boundedBootReaderAcceptsKernelNewlineAndRejectsMalformedContent() {
        assertEquals(boot, parse(boot)); assertEquals(boot, parse("$boot\n"))
        for (value in listOf("", "00000000-0000-0000-0000-000000000000", "1-2-3-4-5", boot.uppercase(),
            "${boot}extra", "\u0000$boot", "$boot\n" + " ".repeat(64))) assertNull(value, parse(value))
    }

    @Test fun oversizedOrStalledBootReaderCannotSupplyAnIdentity() {
        var consumed = 0
        val endless = object : InputStream() {
            override fun read(): Int { consumed++; return 'a'.code }
        }
        assertNull(WorkspaceProcessBootIdentity.readBootId(endless)); assertEquals(65, consumed)
        val stalled = object : InputStream() {
            override fun read(): Int = error("unexpected single read")
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int = 0
        }
        assertNull(WorkspaceProcessBootIdentity.readBootId(stalled))
    }

    @Test fun androidBootCountSurvivesRestrictedKernelBootId() {
        device("1234567890abcdef")
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        val first = requireNotNull(identity(value = { throw IOException("SELinux denies boot_id") }))
        assertEquals(first, identity(value = { null }))
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        val next = requireNotNull(identity(value = { null }))
        assertEquals(first.device, next.device); assertNotEquals(first.boot, next.boot)
    }

    @Test fun changingProofSourceWithinSameBootCannotProveAReboot() {
        device("1234567890abcdef")
        val root = File(app.filesDir, "mirror")
        val kernel = requireNotNull(WorkspaceProcessBootIdentity.fromSources(app, root, { boot }, { 7 }))
        val counter = requireNotNull(WorkspaceProcessBootIdentity.fromSources(app, root, { null }, { 7 }))
        assertNotEquals(kernel.device, counter.device)
        val owner = MirrorProcessCustodyLedger.Owner(java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(), kernel)
        assertEquals(listOf(owner), MirrorProcessCustodyLedger.withoutPreviousBoot(listOf(owner), counter, java.util.UUID.randomUUID().toString()))
    }

    @Test fun unusableFallbackDoesNotInventABootIdentity() {
        device("1234567890abcdef")
        val root = File(app.filesDir, "mirror")
        for (counter in listOf<Int?>(null, -1)) assertNull(WorkspaceProcessBootIdentity.fromSources(app, root, { null }, { counter }))
        assertNull(WorkspaceProcessBootIdentity.fromSources(app, root, { throw IOException() }, { throw SecurityException() }))
    }
}
