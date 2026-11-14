package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Only hash byte fixtures and fake validators; no ELF, process, native library or socket execution. */
class PackagedNativeAdmissionCancellationTest {
    private fun stopsAfterFirstBlock(blockBytes: Int, hash: (File, () -> Unit) -> String) {
        val file = Files.createTempFile("native-hash-", ".bytes").toFile()
        try {
            file.writeBytes(ByteArray(blockBytes + 1) { 42 })
            val cancelled = CancellationException("cancel after one hash block")
            var checkpoints = 0
            var returned = false
            try {
                hash(file) { if (++checkpoints == 3) throw cancelled }
                returned = true
            } catch (actual: CancellationException) { assertSame(cancelled, actual) }
            assertFalse(returned)
            assertEquals(3, checkpoints) // Before open, before first read, after first block.
        } finally { file.delete() }
    }

    @Test fun engineHashStopsAfterOneBoundedBlock() = stopsAfterFirstBlock(64 * 1024, PackagedLinuxEngine::sha256)

    @Test fun cliHashStopsAfterOneBoundedBlock() = stopsAfterFirstBlock(16 * 1024, DroideCliClientInstaller::sha256)

    @Test fun betweenArtifactsCancellationSkipsLaterValidators() {
        for (lastArtifact in 0..2) NativeAdmissionFixture().use { fixture ->
            val cancelled = CancellationException("cancel after artifact $lastArtifact")
            fixture.afterValidation = { index -> if (index == lastArtifact) throw cancelled }
            val attempt = fixture.attempt()
            try {
                attempt.require {}
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) { assertSame(cancelled, actual) }
            assertArrayEquals(IntArray(4) { if (it <= lastArtifact) 1 else 0 }, fixture.validations)
            fixture.afterValidation = {}
            fixture.attempt().use { it.require {} }
            assertArrayEquals(IntArray(4) { if (it <= lastArtifact) 2 else 1 }, fixture.validations)
        }
    }
}
