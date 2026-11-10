package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Controlled metadata is deliberate: these tests do not assert Android ctime support/precision. */
internal class NativeAdmissionFixture : AutoCloseable {
    private val directory = Files.createTempDirectory("native-admission-").toFile().canonicalFile
    val files = listOf("libdroide_proot.so", "libproot-loader.so", "libproot-loader32.so", "libdroide_cli.so")
        .map { name -> File(directory, name).apply { writeText("reviewed-$name"); check(setExecutable(true)) } }
    val identities = files.map { "${it.name}:inode:mtime:ctime-0" }.toMutableList()
    val validations = IntArray(4)
    var identityAvailable = true
    var identityReads = 0
    var onIdentity: (Int) -> Unit = {}
    var afterValidation: (Int) -> Unit = {}
    var failArtifact: Int? = null

    fun attempt(): PackagedNativeAdmissionAttempt = PackagedNativeAdmissionAttempt(
        files,
        PackagedEngineAdmissionCache { requested, checkpoint ->
            assertEquals(files, requested)
            checkpoint()
            onIdentity(++identityReads)
            if (identityAvailable) identities.toList() else null
        },
        validateEngine = { checkpoint ->
            (0..2).forEach { validate(it, checkpoint) }
            files[0]
        },
        validateCli = { checkpoint -> validate(3, checkpoint); files[3] },
    )

    private fun validate(index: Int, checkpoint: () -> Unit) {
        checkpoint()
        validations[index]++
        check(failArtifact != index && files[index].readText() == "reviewed-${files[index].name}") { "Rejected artifact $index" }
        afterValidation(index)
        checkpoint()
    }

    override fun close() { PathSecurity.deleteTreeNoFollow(directory) }
}

class PackagedNativeAdmissionAttemptTest {
    private fun rejected(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalStateException) { rejected = true }
        assertTrue("Expected closed/failed admission", rejected)
    }

    @Test fun oneAttemptValidatesAllFourOnceAndRetryRevalidatesAllFour() = NativeAdmissionFixture().use { fixture ->
        // A separate warm three-file cache cannot supply this complete owner's admission.
        val separate = PackagedEngineAdmissionCache { _, _ -> fixture.identities.take(3) }
        var standaloneChecks = 0
        repeat(2) { separate.require(fixture.files.take(3)) { standaloneChecks++; fixture.files[0] } }
        assertEquals(1, standaloneChecks)
        val first = fixture.attempt()
        try {
            repeat(3) {
                val result = first.require {}
                assertEquals(fixture.files, listOf(result.launcher, result.loader, result.loader32, result.cli))
                assertEquals(fixture.files[1].absolutePath, result.loaderEnvironment["PROOT_LOADER"])
            }
            assertArrayEquals(intArrayOf(1, 1, 1, 1), fixture.validations)
        } finally { first.close() }
        rejected { first.require {} }
        fixture.attempt().use { it.require {} }
        assertArrayEquals(intArrayOf(2, 2, 2, 2), fixture.validations)
    }

    @Test fun eachArtifactIdentityChangeForcesACompleteRevalidation() = NativeAdmissionFixture().use { fixture ->
        fixture.attempt().use { attempt ->
            attempt.require {}
            fixture.files.indices.forEach { index ->
                fixture.identities[index] += ":new-ctime"
                attempt.require {}
                assertArrayEquals(IntArray(4) { index + 2 }, fixture.validations)
            }
            val cli = fixture.files[3]
            val mtime = Files.getLastModifiedTime(cli.toPath())
            cli.writeText("x".repeat(cli.length().toInt()))
            Files.setLastModifiedTime(cli.toPath(), mtime)
            fixture.identities[3] += ":changed-bytes-with-restored-mtime"
            rejected { attempt.require {} }
            rejected { attempt.require {} }
            assertArrayEquals(intArrayOf(6, 6, 6, 6), fixture.validations)
        }
    }

    @Test fun missingPermissionOrUnknownIdentityNeverReusesAdmission() = NativeAdmissionFixture().use { fixture ->
        val attempt = fixture.attempt()
        attempt.require {}
        check(fixture.files[2].setExecutable(false, false))
        rejected { attempt.require {} }
        assertArrayEquals(intArrayOf(1, 1, 1, 1), fixture.validations)
        check(fixture.files[2].setExecutable(true))
        rejected { attempt.require {} }
        fixture.identityAvailable = false
        fixture.attempt().use { fresh -> repeat(2) { fresh.require {} } }
        assertArrayEquals(intArrayOf(3, 3, 3, 3), fixture.validations)
    }

    @Test fun cliFailureNeverPublishesAnEngineOnlyAdmission() = NativeAdmissionFixture().use { fixture ->
        fixture.failArtifact = 3
        val attempt = fixture.attempt()
        rejected { attempt.require {} }
        fixture.failArtifact = null
        rejected { attempt.require {} }
        assertArrayEquals(intArrayOf(1, 1, 1, 1), fixture.validations)
        fixture.attempt().use { it.require {} }
        assertArrayEquals(intArrayOf(2, 2, 2, 2), fixture.validations)
    }

    @Test fun namedSymlinkCannotHideBehindCanonicalTarget() = NativeAdmissionFixture().use { fixture ->
        val attempt = fixture.attempt()
        check(fixture.files[1].delete())
        Files.createSymbolicLink(fixture.files[1].toPath(), fixture.files[2].toPath())
        rejected { attempt.require {} }
        assertArrayEquals(intArrayOf(0, 0, 0, 0), fixture.validations)
    }

    @Test fun mutationBetweenBeforeAndAfterIdentityRetiresAttempt() = NativeAdmissionFixture().use { fixture ->
        fixture.onIdentity = { read -> if (read == 2) fixture.identities[1] += ":replaced" }
        val attempt = fixture.attempt()
        rejected { attempt.require {} }
        rejected { attempt.require {} }
        assertArrayEquals(intArrayOf(1, 1, 1, 1), fixture.validations)
        fixture.attempt().use { it.require {} }
        assertArrayEquals(intArrayOf(2, 2, 2, 2), fixture.validations)
    }

    @Test fun cancellationAtCacheHitAndBeforePublicationClearsAdmission() {
        for (warm in listOf(false, true)) NativeAdmissionFixture().use { fixture ->
            val attempt = fixture.attempt()
            if (warm) attempt.require {}
            val cancelled = CancellationException("injected admission cancellation")
            var cancel = false
            // Warm: the next identity is the hit. Cold: the second identity is after validation.
            fixture.onIdentity = { read -> if (read == (if (warm) 3 else 2)) cancel = true }
            try {
                attempt.require { if (cancel) throw cancelled }
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) { assertSame(cancelled, actual) }
            rejected { attempt.require {} }
            assertArrayEquals(intArrayOf(1, 1, 1, 1), fixture.validations)
            fixture.onIdentity = {}
            fixture.attempt().use { it.require {} }
            assertArrayEquals(intArrayOf(2, 2, 2, 2), fixture.validations)
        }
    }

    @Test fun ownerClosesAfterGuestOrSpecFailureAndNeverEscapesPreparation() = NativeAdmissionFixture().use { fixture ->
        var guestCalls = 0
        var specCalls = 0
        for (failGuest in listOf(true, false)) {
            val attempt = fixture.attempt()
            val failure = IllegalArgumentException("injected preparation failure")
            try {
                try {
                    attempt.require {}
                    guestCalls++
                    attempt.require {}
                    if (failGuest) throw failure
                    specCalls++
                    attempt.require {}
                    throw failure
                } finally { attempt.close() }
            } catch (actual: IllegalArgumentException) { assertSame(failure, actual) }
            attempt.close() // Idempotent; no validation or reopen.
            rejected { attempt.require {} }
        }
        assertEquals(2, guestCalls)
        assertEquals(1, specCalls)
        assertArrayEquals(intArrayOf(2, 2, 2, 2), fixture.validations)
    }
}
