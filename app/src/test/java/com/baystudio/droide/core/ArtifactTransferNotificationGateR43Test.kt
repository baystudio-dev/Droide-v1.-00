package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class ArtifactTransferNotificationGateR43Test {
    @Test fun numericVerificationProgressRemainsThrottledWithinOnePhase() {
        val gate = ArtifactTransferNotificationGate()
        fun progress(bytes: Long) = TrustedArtifactDownloadProgress(100L, 100L,
            detail = "Verifying downloaded archive $bytes / 100 KiB…",
            phase = TrustedArtifactDownloadPhase.VERIFYING)
        assertTrue(gate.shouldNotify(progress(0), 0L))
        for (time in 1L..749L) assertFalse(gate.shouldNotify(progress(time), time))
        assertTrue(gate.shouldNotify(progress(750), 750L))
        assertFalse(gate.shouldNotify(progress(751), 751L))
    }

    @Test fun actualPhaseTransitionsAreImmediateEvenInsideTheByteThrottle() {
        val gate = ArtifactTransferNotificationGate()
        val phases = listOf(TrustedArtifactDownloadPhase.QUEUED, TrustedArtifactDownloadPhase.PREPARING,
            TrustedArtifactDownloadPhase.RESOLVING, TrustedArtifactDownloadPhase.AWAITING_RESPONSE,
            TrustedArtifactDownloadPhase.AWAITING_BODY, TrustedArtifactDownloadPhase.DOWNLOADING,
            TrustedArtifactDownloadPhase.VERIFYING, TrustedArtifactDownloadPhase.READY)
        phases.forEachIndexed { index, phase ->
            val progress = TrustedArtifactDownloadProgress(0L, 100L, detail = phase.name, phase = phase)
            assertTrue("New phase $phase must not be hidden", gate.shouldNotify(progress, index.toLong()))
            assertFalse(gate.shouldNotify(progress.copy(detail = "Updated text in $phase"), index.toLong()))
        }
    }

    @Test fun unclassifiedProgressAndBodyByteUpdatesStillHaveABoundedRate() {
        val gate = ArtifactTransferNotificationGate()
        assertTrue(gate.shouldNotify(TrustedArtifactDownloadProgress(0, 100), 10L))
        assertFalse(gate.shouldNotify(TrustedArtifactDownloadProgress(5, 100, detail = "legacy detail"), 11L))
        assertTrue(gate.shouldNotify(TrustedArtifactDownloadProgress(10, 100,
            phase = TrustedArtifactDownloadPhase.DOWNLOADING), 12L))
        assertFalse(gate.shouldNotify(TrustedArtifactDownloadProgress(20, 100,
            phase = TrustedArtifactDownloadPhase.DOWNLOADING), 13L))
        assertTrue(gate.shouldNotify(TrustedArtifactDownloadProgress(30, 100,
            phase = TrustedArtifactDownloadPhase.DOWNLOADING), 762L))
    }
}
